import fs from "node:fs";
import { createHash } from "node:crypto";
import net from "node:net";

const [host, portText, channel, expectedUuid, expectedBalance, expectedVersion, probeRequestId, outputPath, timeoutText] =
  process.argv.slice(2);
const port = Number(portText);
const timeoutMs = Number(timeoutText);
if (!host || !Number.isInteger(port) || port < 1 || port > 65535 || !channel ||
  !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(expectedUuid ?? "") ||
  !/^\d+\.\d{2}$/.test(expectedBalance ?? "") || !/^\d+$/.test(expectedVersion ?? "") ||
  !/^[0-9a-f]{8,64}$/i.test(probeRequestId ?? "") || !outputPath ||
  !Number.isInteger(timeoutMs) || timeoutMs < 1000 || timeoutMs > 120000) {
  throw new Error("Invalid M10 Redis Pub/Sub observer arguments.");
}

const outputFd = fs.openSync(outputPath, "wx");
let socket;
let buffered = Buffer.alloc(0);
let finished = false;
let subscribed = false;

function record(event, details = {}) {
  fs.writeSync(outputFd, `${JSON.stringify({ atUtc: new Date().toISOString(), event, ...details })}\n`);
  fs.fsyncSync(outputFd);
}

function parseLine(buffer, offset) {
  const end = buffer.indexOf(Buffer.from("\r\n"), offset);
  if (end < 0) return null;
  return [buffer.subarray(offset, end).toString("utf8"), end + 2];
}

function parseResp(buffer, offset = 0) {
  if (offset >= buffer.length) return null;
  const type = String.fromCharCode(buffer[offset]);
  if (type === "*" || type === "$" || type === ":" || type === "+" || type === "-") {
    const line = parseLine(buffer, offset + 1);
    if (!line) return null;
    const [value, afterLine] = line;
    if (type === "*") {
      const count = Number(value);
      if (!Number.isInteger(count) || count < 0 || count > 16) throw new Error("Invalid Redis RESP array length.");
      const items = [];
      let cursor = afterLine;
      for (let index = 0; index < count; index += 1) {
        const parsed = parseResp(buffer, cursor);
        if (!parsed) return null;
        items.push(parsed.value);
        cursor = parsed.next;
      }
      return { value: items, next: cursor };
    }
    if (type === "$") {
      const length = Number(value);
      if (!Number.isInteger(length) || length < -1 || length > 16 * 1024 * 1024) throw new Error("Invalid Redis RESP bulk length.");
      if (length === -1) return { value: null, next: afterLine };
      const next = afterLine + length + 2;
      if (next > buffer.length) return null;
      if (buffer[next - 2] !== 13 || buffer[next - 1] !== 10) throw new Error("Invalid Redis RESP bulk terminator.");
      return { value: buffer.subarray(afterLine, afterLine + length).toString("utf8"), next };
    }
    if (type === ":") return { value: Number(value), next: afterLine };
    if (type === "-") throw new Error(`Redis returned an error: ${value}`);
    return { value, next: afterLine };
  }
  throw new Error(`Unsupported Redis RESP frame type: ${type}`);
}

function cents(value) {
  const text = String(value);
  const match = /^(\d+)(?:\.(\d{1,}))?$/.exec(text);
  if (!match) throw new Error(`Invalid Redis balance value: ${text}`);
  const fraction = match[2] ?? "";
  if (fraction.length > 2 && /[^0]/.test(fraction.slice(2))) throw new Error(`Redis balance has fractional precision beyond cents: ${text}`);
  return BigInt(match[1]) * 100n + BigInt((fraction.slice(0, 2) + "00").slice(0, 2));
}

function fail(error) {
  if (finished) return;
  finished = true;
  try { record("failed", { message: String(error?.stack ?? error).replace(/[\r\n]+/g, " ") }); } catch { }
  if (socket && !socket.destroyed) socket.destroy();
  fs.closeSync(outputFd);
  process.exitCode = 1;
}

const timer = setTimeout(() => fail(new Error(`Timed out waiting for exact Pub/Sub publication after ${timeoutMs}ms.`)), timeoutMs);
socket = net.createConnection({ host, port });
socket.setNoDelay(true);
socket.on("connect", () => {
  const command = ["SUBSCRIBE", channel].map(value => `$${Buffer.byteLength(value)}\r\n${value}\r\n`).join("");
  socket.write(`*2\r\n${command}`);
});
socket.on("data", chunk => {
  try {
    buffered = Buffer.concat([buffered, chunk]);
    while (buffered.length > 0) {
      const parsed = parseResp(buffered);
      if (!parsed) break;
      buffered = buffered.subarray(parsed.next);
      const frame = parsed.value;
      if (!Array.isArray(frame) || frame.length < 3) continue;
      if (frame[0] === "subscribe" && frame[1] === channel) {
        subscribed = true;
        record("subscribed", { channel, subscriberCount: frame[2] });
        continue;
      }
      if (frame[0] !== "message" || frame[1] !== channel) continue;
      if (!subscribed) throw new Error("Redis delivered a message before the subscription acknowledgement.");
      const payloadText = frame[2];
      let payload;
      try { payload = JSON.parse(payloadText); }
      catch { continue; }
      if (String(payload.uuid ?? "").toLowerCase() !== expectedUuid.toLowerCase()) continue;
      const actualBalanceCents = cents(payload.balance);
      const expectedBalanceCents = cents(expectedBalance);
      if (actualBalanceCents !== expectedBalanceCents || String(payload.version) !== expectedVersion ||
        payload.eventType !== "DEPOSIT" || String(payload.sourceServer) !== "m10-a" ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(String(payload.messageId ?? ""))) {
        throw new Error("Observed target-account Pub/Sub message did not match the exact expected balance/version/deposit/source/messageId tuple.");
      }
      finished = true;
      clearTimeout(timer);
      record("publicationObserved", {
        channel,
        probeRequestId,
        uuid: String(payload.uuid),
        balance: expectedBalance,
        version: expectedVersion,
        eventType: String(payload.eventType),
        sourceServer: String(payload.sourceServer),
        messageId: String(payload.messageId),
        payloadSha256: createHash("sha256").update(payloadText).digest("hex"),
      });
      socket.end();
      socket.destroy();
      fs.closeSync(outputFd);
      process.exitCode = 0;
      return;
    }
  } catch (error) {
    fail(error);
  }
});
socket.on("error", fail);
socket.on("close", hadError => {
  if (!finished) fail(new Error(`Redis observer socket closed before the publication barrier (hadError=${hadError}).`));
});

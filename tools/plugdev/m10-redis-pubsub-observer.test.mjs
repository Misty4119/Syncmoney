import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:net";
import fs from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";

const observerPath = fileURLToPath(new URL("./m10-redis-pubsub-observer.mjs", import.meta.url));
const accountUuid = "123e4567-e89b-12d3-a456-426614174000";
const eventId = "123e4567-e89b-12d3-a456-426614174001";
const probeId = "123e4567e89b12d3a456426614174002";

function redisArray(values) {
  return `*${values.length}\r\n${values.map(value => {
    const text = String(value);
    return `$${Buffer.byteLength(text)}\r\n${text}\r\n`;
  }).join("")}`;
}

test("M10 observer waits for the exact account balance/version before acknowledging publication", async t => {
  const outputDir = await fs.mkdtemp(path.join(os.tmpdir(), "syncmoney-m10-observer-"));
  const outputPath = path.join(outputDir, "observer.jsonl");
  const wrongUuid = "123e4567-e89b-12d3-a456-426614174099";
  let delivered = false;
  const server = createServer(socket => {
    socket.on("data", data => {
      if (delivered || !data.toString("utf8").includes("SUBSCRIBE")) return;
      delivered = true;
      socket.write(redisArray(["subscribe", "syncmoney:balance:update", 1]));
      setTimeout(() => {
        const irrelevant = JSON.stringify({ uuid: wrongUuid, balance: 2, version: 42, eventType: "DEPOSIT", sourceServer: "m10-a", messageId: eventId });
        const expected = JSON.stringify({ uuid: accountUuid, balance: 2, version: 42, eventType: "DEPOSIT", sourceServer: "m10-a", messageId: eventId });
        socket.write(redisArray(["message", "syncmoney:balance:update", irrelevant]) + redisArray(["message", "syncmoney:balance:update", expected]));
      }, 25);
    });
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  t.after(async () => {
    server.close();
    await fs.rm(outputDir, { recursive: true, force: true });
  });

  const port = server.address().port;
  const child = spawn(process.execPath, [observerPath, "127.0.0.1", String(port), "syncmoney:balance:update",
    accountUuid, "2.00", "42", probeId, outputPath, "5000"], { stdio: ["ignore", "pipe", "pipe"] });
  let stdout = "";
  let stderr = "";
  child.stdout.setEncoding("utf8").on("data", value => { stdout += value; });
  child.stderr.setEncoding("utf8").on("data", value => { stderr += value; });
  const exitCode = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      child.kill();
      reject(new Error("Observer subprocess did not exit within five seconds."));
    }, 5000);
    child.once("error", error => { clearTimeout(timer); reject(error); });
    child.once("close", code => { clearTimeout(timer); resolve(code); });
  });
  assert.equal(exitCode, 0, `stdout=${stdout}\nstderr=${stderr}`);
  const events = (await fs.readFile(outputPath, "utf8")).trim().split(/\r?\n/).map(line => JSON.parse(line));
  assert.deepEqual(events.map(event => event.event), ["subscribed", "publicationObserved"]);
  assert.equal(events[1].uuid, accountUuid);
  assert.equal(events[1].balance, "2.00");
  assert.equal(events[1].version, "42");
  assert.equal(events[1].probeRequestId, probeId);
  assert.equal(events[1].messageId, eventId);
  assert.match(events[1].payloadSha256, /^[0-9a-f]{64}$/);
});

import { createRequire } from "node:module";
import path from "node:path";
import fs from "node:fs";
import { resolveMineflayerUuid } from "./m10-online-client-identity.mjs";

const [version, portText, username, marker, mineflayerRoot, logPath] = process.argv.slice(2);
const port = Number(portText);
if (!/^\d+\.\d+(?:\.\d+)?$/.test(version ?? "")) throw new Error(`Invalid protocol version: ${version}`);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error(`Invalid server port: ${portText}`);
if (!username?.startsWith("Acceptance") || !marker?.startsWith("T21_M10_RUN=")) {
  throw new Error("An Acceptance username and run-scoped marker are required.");
}
if (!mineflayerRoot || !logPath) throw new Error("Local Mineflayer root and log path are required.");

const require = createRequire(path.join(path.resolve(mineflayerRoot), "package.json"));
const { createBot } = require("mineflayer");
const output = fs.createWriteStream(path.resolve(logPath), { flags: "wx" });
let finished = false;
let requestedQuit = false;
function record(line) {
  output.write(`${new Date().toISOString()} ${line}\n`);
}
function closeInput() {
  process.stdin.pause();
  if (!process.stdin.destroyed) process.stdin.destroy();
}
function fail(reason) {
  if (finished) return;
  finished = true;
  record(`T21_M10_BOT_FAIL marker=${marker} reason=${String(reason).replace(/[\r\n]+/g, " ")}`);
  closeInput();
  output.end(() => process.exitCode = 1);
}

const bot = createBot({
  host: "127.0.0.1",
  port,
  username,
  auth: "offline",
  version,
  hideErrors: false,
});

bot.once("login", () => {
  const uuid = resolveMineflayerUuid(bot);
  if (!uuid) return fail("Mineflayer did not expose the logged-in UUID");
  record(`T21_M10_BOT_READY marker=${marker} name=${username} uuid=${uuid} protocol=${version}`);
});
bot.on("error", error => fail(error?.stack ?? error));
bot.on("kicked", reason => fail(`kicked: ${JSON.stringify(reason)}`));
bot.on("end", () => {
  closeInput();
  if (!finished) {
    finished = true;
    record(requestedQuit
      ? `T21_M10_BOT_EXIT_PASS marker=${marker}`
      : `T21_M10_BOT_EXIT_FAIL marker=${marker} reason=connection-ended-unexpectedly`);
    if (!requestedQuit) process.exitCode = 1;
    output.end();
  }
});

process.stdin.setEncoding("utf8");
process.stdin.on("data", chunk => {
  if (chunk.split(/\r?\n/).some(line => line.trim() === "quit") && !finished) {
    requestedQuit = true;
    record(`T21_M10_BOT_QUIT marker=${marker}`);
    bot.quit("M10 acceptance cleanup");
  }
});

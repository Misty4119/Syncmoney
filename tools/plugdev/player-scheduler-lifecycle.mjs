import { execFileSync } from "node:child_process";
import { createRequire } from "node:module";
import path from "node:path";
import {
  EXPECTED_LOGOUT_REASON,
  isExpectedLogoutTransportAbort,
  isExpectedLogoutKick,
  isTeleportArrivalConfirmed,
  isNetherDimension,
  normalizedDimension,
} from "./player-scheduler-lifecycle-assertions.mjs";

const [scenario, protocolVersion, gamePortText, redisPortText, pauseMsText, redisCliPath, mineflayerRoot] = process.argv.slice(2);
if (!["teleport", "logout"].includes(scenario)) {
  throw new Error("Usage: node player-scheduler-lifecycle.mjs <teleport|logout> <protocol-version> <game-port> <redis-port> <pause-ms> <redis-cli-path> <mineflayer-root>");
}

function readPort(value, name) {
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error(`Invalid ${name}: ${value}`);
  return port;
}

const gamePort = readPort(gamePortText, "game port");
const redisPort = readPort(redisPortText, "Redis port");
const pauseMs = Number(pauseMsText);
if (!Number.isInteger(pauseMs) || pauseMs < 100 || pauseMs > 30000) throw new Error(`Invalid Redis pause duration: ${pauseMsText}`);
if (!/^\d+\.\d+(?:\.\d+)?$/.test(protocolVersion ?? "")) throw new Error(`Invalid protocol version: ${protocolVersion}`);
if (!redisCliPath || !mineflayerRoot) throw new Error("Redis CLI and local Mineflayer checkout paths are required.");

const mineflayerPackage = path.join(path.resolve(mineflayerRoot), "package.json");
const require = createRequire(mineflayerPackage);
const { createBot } = require("mineflayer");
const bot = createBot({
  host: "127.0.0.1",
  port: gamePort,
  username: "AcceptanceA",
  auth: "offline",
  version: protocolVersion,
  hideErrors: false,
});

let finished = false;
let initialDimension = "unknown";
let dimensionPoll = null;
let deadline = null;
let logoutCommandSent = false;
let logoutTransportAbort = false;
let clientObservedNether = false;
let serverReportedNetherArrival = false;

function finish(code, reason) {
  if (finished) return;
  finished = true;
  if (deadline) clearTimeout(deadline);
  if (dimensionPoll) clearInterval(dimensionPoll);
  console.log(`BOT_SCENARIO_END scenario=${scenario} code=${code} reason=${reason}`);
  try { bot.quit("T15 scenario complete"); } catch {}
  setTimeout(() => process.exit(code), 250);
}

function observeDimension(trigger) {
  const current = normalizedDimension(bot.game?.dimension);
  console.log(`BOT_DIMENSION scenario=${scenario} trigger=${trigger} dimension=${current || "unknown"}`);
  if (scenario === "teleport" && isNetherDimension(current)) {
    clientObservedNether = true;
    console.log(`BOT_NETHER_DIMENSION dimension=${current} trigger=${trigger}`);
    completeTeleportIfConfirmed(trigger, current);
  }
}

function completeTeleportIfConfirmed(trigger, dimension) {
  if (scenario === "teleport" && isTeleportArrivalConfirmed(clientObservedNether, serverReportedNetherArrival)) {
    console.log(`BOT_NETHER_ARRIVAL dimension=${dimension} initial=${initialDimension} trigger=${trigger}`);
    finish(0, "client dimension and server-side entity world check both confirmed Nether arrival");
  }
}

console.log(`BOT_START scenario=${scenario} protocol=${protocolVersion} gamePort=${gamePort} redisPort=${redisPort} pauseMs=${pauseMs}`);
deadline = setTimeout(() => finish(1, "scenario timeout"), 30000);
bot.on("error", (error) => {
  console.log(`BOT_ERROR ${error?.stack ?? error}`);
  if (isExpectedLogoutTransportAbort(scenario, logoutCommandSent, error?.code)) {
    logoutTransportAbort = true;
    console.log("BOT_LOGOUT_TRANSPORT_ABORT code=ECONNABORTED awaiting server-confirmed disconnect");
    return;
  }
  if (!finished) finish(1, "client protocol or connection error");
});
bot.on("message", (message) => {
  const text = message.toString();
  console.log(`BOT_CHAT ${text}`);
  if (scenario === "teleport" && text.includes("T15_TELEPORT_REACHED world=world_nether")) {
    serverReportedNetherArrival = true;
    console.log("BOT_SERVER_TELEPORT_REACHED world=world_nether");
    completeTeleportIfConfirmed("server-world-change", normalizedDimension(bot.game?.dimension));
  }
});
bot.on("respawn", () => observeDimension("respawn"));
bot.on("kicked", (reason) => {
  console.log(`BOT_KICKED ${JSON.stringify(reason)}`);
  if (scenario === "logout" && isExpectedLogoutKick(reason)) {
    console.log(`BOT_KICK_REASON_PASS reason=${EXPECTED_LOGOUT_REASON}`);
    finish(0, "server sent the expected lifecycle kick reason");
  } else {
    finish(1, "unexpected server kick reason");
  }
});
bot.on("end", (reason) => {
  console.log(`BOT_END ${String(reason)}`);
  if (!finished && logoutTransportAbort) {
    console.log("BOT_LOGOUT_ABORTED_AFTER_COMMAND code=ECONNABORTED");
    finish(0, "logout connection closed after command; runner verifies the exact server kick reason");
  } else if (!finished) finish(1, "unexpected disconnect before acceptance outcome");
});

bot.once("spawn", () => {
  initialDimension = normalizedDimension(bot.game?.dimension) || "unknown";
  console.log(`BOT_JOINED name=${bot.username} uuid=${bot.uuid} dimension=${initialDimension}`);
  if (scenario === "teleport" && isNetherDimension(initialDimension)) {
    finish(1, "teleport probe did not start in the Overworld");
    return;
  }
  dimensionPoll = setInterval(() => observeDimension("poll"), 100);
  try {
    const pause = execFileSync(redisCliPath,
      ["-h", "127.0.0.1", "-p", String(redisPort), "CLIENT", "PAUSE", String(pauseMs), "ALL"],
      { encoding: "utf8", timeout: 5000 }).trim();
    if (pause !== "OK") throw new Error(`Unexpected Redis pause response: ${pause}`);
    console.log(`REDIS_PAUSE_STARTED ms=${pauseMs} scope=ALL port=${redisPort}`);
  } catch (error) {
    console.log(`REDIS_PAUSE_FAILED ${error?.stack ?? error}`);
    finish(1, "could not pause isolated Redis");
    return;
  }

  bot.chat(`/smaccept lifecycle ${scenario}`);
  if (scenario === "logout") logoutCommandSent = true;
  console.log(`BOT_COMMAND_SENT /smaccept lifecycle ${scenario}`);
});

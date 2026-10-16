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
  observeCrossOwnerClientPass,
} from "./player-scheduler-lifecycle-assertions.mjs";

const [scenario, protocolVersion, gamePortText, redisPortText, pauseMsText, redisCliPath, mineflayerRoot] = process.argv.slice(2);
if (!["teleport", "logout", "cross-owner", "guard", "guard-pair", "guard-pair-breaker", "breaker-pair"].includes(scenario)) {
  throw new Error("Usage: node player-scheduler-lifecycle.mjs <teleport|logout|cross-owner|guard|guard-pair|guard-pair-breaker|breaker-pair> <protocol-version> <game-port> <redis-port> <pause-ms> <redis-cli-path> <mineflayer-root>");
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
function createAcceptanceBot(username) {
  return createBot({
    host: "127.0.0.1",
    port: gamePort,
    username,
    auth: "offline",
    version: protocolVersion,
    hideErrors: false,
  });
}

const guardScenario = scenario === "guard";
const guardPairScenario = ["guard-pair", "guard-pair-breaker"].includes(scenario);
const globalBreakerPairScenario = scenario === "breaker-pair";
const pairedOwnerScenario = guardPairScenario || globalBreakerPairScenario;
const bot = createAcceptanceBot(pairedOwnerScenario ? "AcceptanceGuardA" : guardScenario ? "AcceptanceGuard" : "AcceptanceA");
const otherBot = scenario === "cross-owner" ? createAcceptanceBot("AcceptanceB")
  : pairedOwnerScenario ? createAcceptanceBot("AcceptanceGuardB") : null;
const spawnedPlayers = new Set();

let finished = false;
let initialDimension = "unknown";
let dimensionPoll = null;
let deadline = null;
let logoutCommandSent = false;
let logoutTransportAbort = false;
let clientObservedNether = false;
let serverReportedNetherArrival = false;
const crossOwnerClientsPassed = new Set();
let guardPairNoticeStage = 0;
const globalBreakerPasses = new Map();
const globalBreakerAcknowledgements = new Map();

function observeCrossOwnerPass(activeBot, text) {
  if (scenario !== "cross-owner") return;
  if (text !== `T21_CROSS_OWNER_PASS player=${activeBot.username}`) return;
  const complete = observeCrossOwnerClientPass(crossOwnerClientsPassed, activeBot.username, text);
  console.log(`BOT_CROSS_OWNER_PASS player=${activeBot.username}`);
  if (complete) finish(0, "both live clients received their targeted owner-proof completion marker");
}

function observeGuardPairNotice(activeBot, text) {
  if (!guardPairScenario || !text.includes("Transaction too fast, please wait a moment")) return;
  const player = activeBot.username;
  const uuid = activeBot.player?.uuid ?? activeBot.entity?.uuid;
  if (!uuid || !["AcceptanceGuardA", "AcceptanceGuardB"].includes(player)) {
    console.log(`BOT_GUARD_FAIL reason=missing-player-identity player=${player}`);
    finish(1, "rate-limit notice came from an unidentified guard participant");
    return;
  }
  const expectedPlayer = guardPairNoticeStage === 0 ? "AcceptanceGuardA"
    : guardPairNoticeStage === 1 ? "AcceptanceGuardB" : null;
  if (player !== expectedPlayer) {
    console.log(`BOT_GUARD_FAIL phase=notice-routing stage=${guardPairNoticeStage + 1} expected=${expectedPlayer ?? "none"} received=${player}`);
    finish(1, "rate-limit notice reached a player other than the owner under test");
    return;
  }
  const stage = guardPairNoticeStage + 1;
  console.log(`BOT_GUARD_RATE_LIMIT_NOTICE_PASS stage=${stage} player=${player} uuid=${uuid} message=${text}`);
  guardPairNoticeStage += 1;
  activeBot.chat(`/smaccept lifecycle guard-pair-notice-ack ${player}`);
  console.log(`BOT_GUARD_NOTICE_ACK_SENT stage=${stage} player=${player} uuid=${uuid}`);
  if (stage === 2) finish(0, "each entity owner received and acknowledged its own rate-limit notification in sequence");
}

function observeGlobalBreakerPass(activeBot, text) {
  if (!globalBreakerPairScenario) return;
  const pass = text.match(/T21_GLOBAL_BREAKER_PASS player=(AcceptanceGuardA|AcceptanceGuardB) uuid=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\b/i);
  if (pass) {
    const player = pass[1];
    const uuid = pass[2].toLowerCase();
    const clientUuid = String(activeBot.player?.uuid ?? activeBot.entity?.uuid ?? "").toLowerCase();
    if (player !== activeBot.username || uuid !== clientUuid || globalBreakerPasses.has(player)) {
      console.log(`BOT_GLOBAL_BREAKER_FAIL phase=proof-routing expected=${activeBot.username}:${clientUuid} received=${player}:${uuid}`);
      finish(1, "global breaker completion did not match a unique receiving owner");
      return;
    }
    globalBreakerPasses.set(player, uuid);
    console.log(`BOT_GLOBAL_BREAKER_PASS player=${player} uuid=${uuid}`);
    activeBot.chat(`/smaccept lifecycle breaker-pair-ack ${player}`);
    console.log(`BOT_GLOBAL_BREAKER_ACK_SENT player=${player} uuid=${uuid}`);
    return;
  }

  const acknowledgement = text.match(/T21_GLOBAL_BREAKER_ACK player=(AcceptanceGuardA|AcceptanceGuardB) uuid=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) recipient=(AcceptanceGuardA|AcceptanceGuardB)\b/i);
  if (!acknowledgement) return;
  const [, player, rawUuid, recipient] = acknowledgement;
  const uuid = rawUuid.toLowerCase();
  const clientUuid = String(activeBot.player?.uuid ?? activeBot.entity?.uuid ?? "").toLowerCase();
  if (player !== activeBot.username || recipient !== activeBot.username || uuid !== clientUuid ||
      globalBreakerAcknowledgements.has(player) || globalBreakerPasses.get(player) !== uuid) {
    console.log(`BOT_GLOBAL_BREAKER_FAIL phase=ack-routing expected=${activeBot.username}:${clientUuid} received=${player}:${uuid} recipient=${recipient}`);
    finish(1, "global breaker acknowledgement did not match the receiving owner");
    return;
  }
  globalBreakerAcknowledgements.set(player, uuid);
  console.log(`BOT_GLOBAL_BREAKER_ACK_PASS player=${player} uuid=${uuid} recipient=${recipient}`);
  if (globalBreakerAcknowledgements.size === 2) {
    finish(0, "both entity owners received and acknowledged their global breaker result");
  }
}

function finish(code, reason) {
  if (finished) return;
  finished = true;
  if (deadline) clearTimeout(deadline);
  if (dimensionPoll) clearInterval(dimensionPoll);
  console.log(`BOT_SCENARIO_END scenario=${scenario} code=${code} reason=${reason}`);
  try { bot.quit("T15 scenario complete"); } catch {}
  try { otherBot?.quit("T21 cross-owner scenario complete"); } catch {}
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
function observeClientError(username, error) {
  console.log(`BOT_ERROR ${error?.stack ?? error}`);
  if (isExpectedLogoutTransportAbort(scenario, logoutCommandSent, error?.code)) {
    logoutTransportAbort = true;
    console.log("BOT_LOGOUT_TRANSPORT_ABORT code=ECONNABORTED awaiting server-confirmed disconnect");
    return;
  }
  if (!finished) finish(1, "client protocol or connection error");
}
bot.on("error", (error) => observeClientError(bot.username, error));
otherBot?.on("error", (error) => observeClientError(otherBot.username, error));
bot.on("message", (message) => {
  const text = message.toString();
  console.log(`BOT_CHAT ${text}`);
  observeCrossOwnerPass(bot, text);
  observeGuardPairNotice(bot, text);
  observeGlobalBreakerPass(bot, text);
  if (guardScenario && text.includes("Transaction too fast, please wait a moment")) {
    console.log(`BOT_GUARD_RATE_LIMIT_NOTICE_PASS uuid=${bot.player?.uuid ?? bot.entity?.uuid} message=${text}`);
    finish(0, "client received the player-owner rate-limit notification");
  }
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

if (otherBot) {
  otherBot.on("message", (message) => {
    const text = message.toString();
    console.log(`BOT_CHAT name=${otherBot.username} ${text}`);
    observeCrossOwnerPass(otherBot, text);
    observeGuardPairNotice(otherBot, text);
    observeGlobalBreakerPass(otherBot, text);
  });
  otherBot.on("kicked", (reason) => {
    console.log(`BOT_KICKED name=${otherBot.username} ${JSON.stringify(reason)}`);
    if (!finished) finish(1, "peer client was kicked before owner proof completed");
  });
  otherBot.on("end", (reason) => {
    console.log(`BOT_END name=${otherBot.username} ${String(reason)}`);
    if (!finished) finish(1, "peer client disconnected before owner proof completed");
  });
}

function onSpawn(activeBot) {
  spawnedPlayers.add(activeBot.username);
  const dimension = normalizedDimension(activeBot.game?.dimension) || "unknown";
  console.log(`BOT_JOINED name=${activeBot.username} uuid=${activeBot.player?.uuid ?? activeBot.entity?.uuid} dimension=${dimension}`);
  if (scenario === "cross-owner") {
    if (spawnedPlayers.has("AcceptanceA") && spawnedPlayers.has("AcceptanceB")) {
      console.log("BOT_CROSS_OWNER_CLIENTS_READY players=AcceptanceA,AcceptanceB");
      bot.chat("/smaccept lifecycle cross-owner AcceptanceB");
      console.log("BOT_COMMAND_SENT /smaccept lifecycle cross-owner AcceptanceB");
    }
    return;
  }
  if (pairedOwnerScenario) {
    if (spawnedPlayers.has("AcceptanceGuardA") && spawnedPlayers.has("AcceptanceGuardB")) {
      if (guardPairScenario) console.log("BOT_GUARD_CLIENTS_READY players=AcceptanceGuardA,AcceptanceGuardB");
      else console.log("BOT_GLOBAL_BREAKER_CLIENTS_READY players=AcceptanceGuardA,AcceptanceGuardB");
      bot.chat(`/smaccept lifecycle ${scenario} AcceptanceGuardB`);
      console.log(`BOT_COMMAND_SENT /smaccept lifecycle ${scenario} AcceptanceGuardB`);
    }
    return;
  }
  if (guardScenario) {
    bot.chat("/smaccept lifecycle guard");
    console.log("BOT_COMMAND_SENT /smaccept lifecycle guard");
    return;
  }
  initialDimension = dimension;
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
}

bot.once("spawn", () => onSpawn(bot));
otherBot?.once("spawn", () => onSpawn(otherBot));

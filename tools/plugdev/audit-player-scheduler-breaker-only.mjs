import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { createRequire } from "node:module";
import { readFileSync, realpathSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import {
  extractPlayerUuidFromJoin,
  hasExpectedLogoutScenarioEvidence,
  hasExactExpectedServerKick,
  hasGlobalBreakerPairRuntimeProof,
  hasSingleSuccessfulTeleportFuture,
} from "./player-scheduler-lifecycle-assertions.mjs";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const reportsRoot = path.resolve(repositoryRoot, "build/reports/foundation/T21-player-scheduler");
const ENDPOINT = {
  label: "canvas-263-currentmax",
  software: "canvas",
  minecraftVersion: "26.3",
  build: 984,
  serverJar: "canvas-26.3-984.jar",
  serverJarSha256: "0b94c4cba3e415586dddb69915b5aacf250636c62d058f4651c1ab34eaf265fa",
};
const CANDIDATE_JAR_SHA256 = "7d4fb070ecdb61ece3d5e39b7a2bf6ceb72c0c6a939f269588344b41f908b354";
const MAX_RUNNER_PENDING_COUNT = 2_147_483_647;
let assertionCount = 0;

function requireCondition(condition, message) {
  assertionCount += 1;
  if (!condition) throw new Error(message);
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function isSha256(value) {
  return typeof value === "string" && /^[a-f0-9]{64}$/i.test(value);
}

export function canonicalTextSourceSha256(bytes) {
  const text = bytes.toString("utf8").replace(/^\uFEFF/, "").replace(/\r\n?/g, "\n");
  return sha256(Buffer.from(text, "utf8"));
}

export function validateApiClassloaderEvidence(serverLines) {
  const passMarkers = serverLines.filter((line) => /API CLASSLOADER PASS/i.test(line));
  requireCondition(passMarkers.length === 1, "server log must contain exactly one API CLASSLOADER PASS marker");
  return passMarkers[0];
}

function isWithin(root, candidate) {
  return candidate.startsWith(root + path.sep);
}

function readContainedFile(root, relativePath) {
  const lexicalPath = path.resolve(root, relativePath);
  requireCondition(isWithin(root, lexicalPath), "path escapes its root: " + relativePath);
  const realPath = realpathSync(lexicalPath);
  requireCondition(isWithin(root, realPath), "resolved path escapes its root: " + relativePath);
  requireCondition(statSync(realPath).isFile(), "expected a file: " + relativePath);
  return readFileSync(realPath);
}

function verifyHash(bytes, expected, label) {
  requireCondition(isSha256(expected), label + " expected SHA-256 is missing or malformed");
  const actual = sha256(bytes);
  requireCondition(actual === expected.toLowerCase(),
    label + " SHA-256 mismatch: expected " + expected + ", observed " + actual);
  return actual;
}

function sameJson(left, right) {
  return JSON.stringify(left) === JSON.stringify(right);
}

function isRunnerPendingCount(value) {
  return Number.isInteger(value) && value >= 2 && value <= MAX_RUNNER_PENDING_COUNT;
}

export function validateLifecycleEvidence(report, serverLines) {
  const lifecycle = report.observed?.lifecycle;
  requireCondition(!serverLines.some((line) => /T15_LIFECYCLE_FAIL/i.test(line)),
    "server log contains the T15 lifecycle failure marker");
  const acceptedWriteLines = serverLines.filter((line) => /T15_ACCEPTED_WRITE /i.test(line));
  requireCondition(acceptedWriteLines.length === 4, "server log lacks exactly four accepted-write markers");
  const acceptedWrites = acceptedWriteLines.map((line) => {
    const match = line.match(/\bT15_ACCEPTED_WRITE scenario=(teleport|logout) index=(1|2) uuid=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) amount=1\.00 response=SUCCESS(?:\s|$)/i);
    return match ? { scenario: match[1].toLowerCase(), index: Number(match[2]), uuid: match[3].toLowerCase() } : null;
  });
  requireCondition(acceptedWrites.every(Boolean), "accepted-write server marker is malformed or unsuccessful");

  const acceptedUuidByScenario = {};
  for (const scenario of ["teleport", "logout"]) {
    const writes = acceptedWrites.filter((write) => write.scenario === scenario);
    requireCondition(writes.length === 2 && writes.map((write) => write.index).sort().join(",") === "1,2" &&
      new Set(writes.map((write) => write.uuid)).size === 1,
    "accepted-write markers do not prove both successful writes for " + scenario);
    acceptedUuidByScenario[scenario] = writes[0].uuid;
  }

  const pendingLines = serverLines.filter((line) => /T15_PENDING_AT_BOUNDARY/i.test(line));
  requireCondition(pendingLines.length === 2, "server log lacks exactly two pending-boundary markers");
  const pendingRecords = pendingLines.map((line) => {
    const match = line.match(/\bT15_PENDING_AT_BOUNDARY scenario=(teleport|logout) uuid=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) pending=(\d+) pending-state=accepted-not-completed(?:\s|$)/i);
    return match ? { scenario: match[1].toLowerCase(), uuid: match[2].toLowerCase(), pending: Number(match[3]) } : null;
  });
  requireCondition(pendingRecords.every(Boolean), "pending-boundary server marker is malformed");
  const pendingByScenario = {};
  for (const scenario of ["teleport", "logout"]) {
    const records = pendingRecords.filter((record) => record.scenario === scenario);
    requireCondition(records.length === 1 && isRunnerPendingCount(records[0].pending) &&
      records[0].uuid === acceptedUuidByScenario[scenario],
    "pending-boundary server marker does not match the accepted writes for " + scenario);
    pendingByScenario[scenario] = records[0].pending;
  }

  const boundaryLines = serverLines.filter((line) => /T15_BOUNDARY_ACTION/i.test(line));
  const boundaryRecords = boundaryLines.map((line) => {
    const match = line.match(/T15_BOUNDARY_ACTION scenario=(teleport|logout) thread=(.*)$/i);
    return match ? { scenario: match[1].toLowerCase(), thread: match[2].trim() } : null;
  });
  requireCondition(boundaryRecords.length === 2 && boundaryRecords.every(Boolean) &&
    boundaryRecords.every((record) => record.thread.length > 0) &&
    new Set(boundaryRecords.map((record) => record.scenario)).size === 2,
  "server log lacks one non-empty boundary action thread for teleport and logout");
  const boundaryActionThreads = Object.fromEntries(boundaryRecords.map((record) => [record.scenario, record.thread]));

  requireCondition(lifecycle?.acceptedWriteMarkers === acceptedWriteLines.length &&
    lifecycle.pendingBoundaries?.teleport === pendingByScenario.teleport &&
    lifecycle.pendingBoundaries?.logout === pendingByScenario.logout,
  "lifecycle report counts do not match retained accepted-write/pending-boundary markers");
  requireCondition(lifecycle.actionThreads?.teleport === boundaryActionThreads.teleport &&
    lifecycle.actionThreads?.logout === boundaryActionThreads.logout,
  "lifecycle report boundary action threads do not match retained server markers");

  return {
    acceptedWriteMarkers: acceptedWriteLines.length,
    pendingBoundaries: pendingByScenario,
    boundaryActionThreads,
  };
}

export function validateBreakerOnlyReport(report) {
  requireCondition(report && typeof report === "object" && !Array.isArray(report), "report must be an object");
  requireCondition(report.schemaVersion === 1 && report.status === "PASS" &&
    report.evidenceLevel === "E2_REAL_SERVER_AND_ISOLATED_REDIS", "report must be a passing E2 runtime result");
  requireCondition(typeof report.task === "string" &&
    report.task.includes("global circuit-breaker rate-limit case with per-player protection disabled"),
  "report task must identify the breaker-only Canvas case");
  requireCondition(typeof report.runId === "string" && report.runId.length > 0, "report runId is missing");

  const endpoint = report.endpoint;
  requireCondition(endpoint && endpoint.label === ENDPOINT.label && endpoint.software === ENDPOINT.software &&
    endpoint.minecraftVersion === ENDPOINT.minecraftVersion && endpoint.build === ENDPOINT.build &&
    endpoint.serverJarSha256 === ENDPOINT.serverJarSha256,
  "report does not match the pinned current-maximum Canvas 26.3#984 endpoint");
  requireCondition(typeof endpoint.serverJar === "string", "report endpoint JAR path is missing");
  requireCondition(typeof report.runtime?.java === "string" && /version "25(?:\.|"|\+)/.test(report.runtime.java),
    "runtime must identify Java 25 or newer");
  requireCondition(/^[a-f0-9]{40}$/i.test(report.source?.commit ?? "") &&
    typeof report.source.repository === "string", "source commit or repository identity is missing");
  requireCondition(isSha256(report.source?.candidateJarSha256) &&
    report.source.candidateJarSha256 === report.artifacts?.candidateJarSha256,
  "candidate JAR hash is missing or inconsistent");
  requireCondition(report.source.candidateJarSha256 === CANDIDATE_JAR_SHA256 &&
    report.artifacts.candidateJarSha256 === CANDIDATE_JAR_SHA256,
  "candidate JAR hash does not match the independently pinned candidate JAR");
  for (const [name, value] of Object.entries({
    acceptanceJarSha256: report.artifacts?.acceptanceJarSha256,
    apiConsumerJarSha256: report.artifacts?.apiConsumerJarSha256,
    runnerSha256: report.artifacts?.runnerSha256,
    clientHelperSha256: report.artifacts?.clientHelperSha256,
    assertionModuleSha256: report.artifacts?.assertionModuleSha256,
    assertionTestSha256: report.artifacts?.assertionTestSha256,
    prepareScriptSha256: report.artifacts?.prepareScriptSha256,
    acceptanceProbeSourceSha256: report.artifacts?.acceptanceProbeSourceSha256,
    localPlugDevCliSha256: report.artifacts?.localPlugDevCliSha256,
  })) requireCondition(isSha256(value), "artifact/source pin " + name + " is missing or malformed");

  const configSha = report.acceptanceProfile?.configSha256;
  requireCondition(report.acceptanceProfile?.playerProtection === false &&
    report.acceptanceProfile.circuitBreaker === true && report.acceptanceProfile.maxTransactionsPerSecond === 5 &&
    isSha256(configSha), "acceptance profile must enable only the global breaker at five TPS");
  const isolation = report.isolation;
  requireCondition(isolation?.mode === "local_redis" && isolation.playerProtection === false &&
    isolation.circuitBreaker === true && isolation.maxTransactionsPerSecond === 5 &&
    isolation.redisDatabase === 15 && isolation.redisAof === true && isolation.appendFsync === "always" &&
    isolation.breakerOnlyConfigSha256 === configSha && isolation.runRoot && isolation.projectRoot &&
    isolation.plugDevHome && isolation.gradleUserHome && isolation.redisData && isolation.runMarker,
  "report does not prove the isolated LOCAL_REDIS breaker-only profile");

  const lifecycle = report.observed?.lifecycle;
  requireCondition(lifecycle?.acceptedWriteMarkers === 4 &&
    isRunnerPendingCount(lifecycle.pendingBoundaries?.teleport) &&
    isRunnerPendingCount(lifecycle.pendingBoundaries?.logout) &&
    lifecycle.teleportFutureSucceeded === true && lifecycle.serverNetherArrival === true &&
    lifecycle.clientNetherArrival === true && lifecycle.expectedLogoutKick === true,
  "report lacks the accepted-write teleport/logout lifecycle evidence");
  requireCondition(report.server?.normalStartExitCode === 0 && report.server.normalStopExitCode === 0 &&
    report.server.processExitVerified === true && report.server.syncmoneyDisabledMarker === true &&
    isSha256(report.server.logSha256), "server start/stop or log evidence is incomplete");
  requireCondition(report.redis?.normalShutdownExitCode === 0 && report.redis.processExitCode === 0 &&
    report.redis.processExitVerified === true && Number.isInteger(report.redis.databaseSize) &&
    report.redis.databaseSize > 0 && isSha256(report.redis.logSha256),
  "Redis shutdown or log evidence is incomplete");
  requireCondition(report.redis.balance === "4.00" && report.redis.version === 4 &&
    /^syncmoney:balance:[0-9a-f-]{36}$/i.test(report.redis.balanceKey ?? "") &&
    report.redis.versionKey === report.redis.balanceKey.replace("syncmoney:balance:", "syncmoney:version:"),
  "the lifecycle account must persist Redis 4.00/v4 with matching balance/version keys");

  const breaker = report.observed?.globalBreaker;
  requireCondition(breaker?.ownerProofs === 2 && breaker.globalRateLimitAttempts === 6 &&
    breaker.acceptedTransactions === 5 && breaker.rateLimitedTransactions === 1 &&
    breaker.maxTransactionsPerSecond === 5 && breaker.circuitBreakerEnabled === true &&
    breaker.circuitBreakerLocked === false && breaker.playerProtectionEnabled === false,
  "report does not describe the expected six-attempt global-breaker outcome");
  requireCondition(Array.isArray(breaker.participants) && breaker.participants.length === 2 &&
    Array.isArray(breaker.ownerEvidenceLines) && Array.isArray(breaker.clientPassLines) &&
    Array.isArray(breaker.clientAcknowledgementLines), "breaker-only proof arrays are missing");
  requireCondition(hasGlobalBreakerPairRuntimeProof({
    serverLines: breaker.ownerEvidenceLines,
    clientLines: [...breaker.clientPassLines, ...breaker.clientAcknowledgementLines],
    participants: breaker.participants,
  }), "the owner, six-attempt, Redis-parity, and client ACK proof is invalid");

  requireCondition(report.client?.globalBreaker?.scenario === "breaker-pair" &&
    report.client.globalBreaker.protocol === ENDPOINT.minecraftVersion && report.client.globalBreaker.exitCode === 0 &&
    isSha256(report.client.globalBreaker.logSha256), "breaker-pair client result is missing or failed");
  for (const scenario of ["teleport", "logout"]) {
    const client = report.client?.[scenario];
    requireCondition(client?.protocol === ENDPOINT.minecraftVersion && client.exitCode === 0 &&
      isSha256(client.logSha256) && typeof client.moduleSearchPath === "string" &&
      path.isAbsolute(client.moduleSearchPath), scenario + " client result or Mineflayer module path is invalid");
  }
  requireCondition(report.client.logout.expectedKickReason === "T15 accepted-write logout scenario" &&
    typeof report.client.logout.clientObservedExpectedKickPacket === "boolean" &&
    typeof report.client.logout.clientObservedExpectedLogoutAbort === "boolean" &&
    typeof report.client.logout.exactServerKickReasonVerified === "boolean" &&
    report.client.logout.clientObservedExpectedKickPacket === lifecycle.clientObservedExpectedKickPacket &&
    report.client.logout.clientObservedExpectedLogoutAbort === lifecycle.clientObservedExpectedLogoutAbort &&
    report.client.logout.exactServerKickReasonVerified === lifecycle.exactServerKickReasonVerified,
  "logout report fields are missing or inconsistent");
  requireCondition(report.forcedCleanup === false && report.cleanupFailure == null &&
    report.gradleCleanup?.processExitVerified === true && Array.isArray(report.gradleCleanup.processIds),
  "report indicates forced cleanup, failed cleanup, or unverified Gradle process exit");

  const requiredRedisFiles = [
    "redis/data/dump.rdb",
    "redis/data/appendonlydir/appendonly.aof.1.base.rdb",
    "redis/data/appendonlydir/appendonly.aof.1.incr.aof",
    "redis/data/appendonlydir/appendonly.aof.manifest",
  ];
  requireCondition(Array.isArray(report.redis.files) && report.redis.files.every((file) =>
    typeof file.path === "string" && Number.isInteger(file.bytes) && file.bytes >= 0 && isSha256(file.sha256)) &&
    requiredRedisFiles.every((expected) => report.redis.files.some((file) => file.path === expected)),
  "Redis persistence file evidence is incomplete or malformed");
  return true;
}

function verifyGitSourcePin(sourceCommit, relativePath, expectedSha256) {
  const bytes = execFileSync("git", ["-C", repositoryRoot, "show", sourceCommit + ":" + relativePath]);
  const actual = canonicalTextSourceSha256(bytes);
  requireCondition(actual === expectedSha256.toLowerCase(),
    "source " + relativePath + " at " + sourceCommit + " SHA-256 mismatch: expected " + expectedSha256 + ", observed " + actual);
  return actual;
}

function extractServerProofLines(serverLog) {
  const markers = /T21_GLOBAL_BREAKER_(?:OWNER|STATE|TRANSACTIONS|ACK|PAIR_PASS|FAIL)|Deposit rejected by CircuitBreaker:|CircuitBreaker: Transaction rate limit exceeded|rejected by PlayerTransactionGuard:|Transaction too fast, please wait for/;
  return serverLog.split(/\r?\n/).filter((line) => markers.test(line)).map((line) =>
    line.replace(/^.*?(?=T21_GLOBAL_BREAKER_|Deposit rejected by CircuitBreaker:|CircuitBreaker: Transaction rate limit exceeded|rejected by PlayerTransactionGuard:|Transaction too fast, please wait for)/, ""));
}

function runAudit() {
  const args = process.argv.slice(2);
  const resultPathArg = args[0];
  const plugDevFlag = args.indexOf("--plugdev-root");
  const plugDevRootArg = plugDevFlag >= 0 ? args[plugDevFlag + 1] : null;
  if (!resultPathArg || !plugDevRootArg || args.length !== 3) {
    throw new Error("Usage: node tools/plugdev/audit-player-scheduler-breaker-only.mjs <result.json> --plugdev-root <local-plugdev-root>");
  }

  const realReportsRoot = realpathSync(reportsRoot);
  const realReportPath = realpathSync(path.resolve(resultPathArg));
  requireCondition(isWithin(realReportsRoot, realReportPath) && path.basename(realReportPath) === "result.json",
    "result.json must be inside the T21 player-scheduler reports directory");
  const runRoot = realpathSync(path.dirname(realReportPath));
  const reportBytes = readFileSync(realReportPath);
  const report = JSON.parse(reportBytes.toString("utf8"));
  validateBreakerOnlyReport(report);
  requireCondition(path.resolve(report.isolation.runRoot) === runRoot, "report runRoot does not match its directory");
  requireCondition(path.resolve(report.source.repository) === repositoryRoot, "report repository root mismatch");

  const expectedServerJar = path.resolve(repositoryRoot,
    "build/reports/foundation/T21-sql-runtime/current-maximum-artifacts", ENDPOINT.serverJar);
  requireCondition(path.resolve(report.endpoint.serverJar) === expectedServerJar, "report endpoint JAR path mismatch");
  verifyHash(readFileSync(expectedServerJar), ENDPOINT.serverJarSha256, "pinned Canvas 26.3#984 server JAR");
  const projectRoot = realpathSync(path.join(runRoot, "project"));
  requireCondition(path.resolve(report.isolation.projectRoot) === projectRoot, "isolated project root mismatch");
  const candidateJarBytes = readContainedFile(projectRoot, "build/libs/Syncmoney-1.4.0-alpha.1.jar");
  verifyHash(candidateJarBytes, CANDIDATE_JAR_SHA256, "independently pinned candidate JAR");
  verifyHash(candidateJarBytes, report.artifacts.candidateJarSha256, "reported candidate JAR");
  verifyHash(readContainedFile(projectRoot, "build/acceptance/SyncmoneyAcceptance.jar"),
    report.artifacts.acceptanceJarSha256, "isolated acceptance JAR");
  verifyHash(readContainedFile(projectRoot, "build/acceptance/SyncmoneyApiConsumer.jar"),
    report.artifacts.apiConsumerJarSha256, "isolated API-consumer JAR");

  const sourcePins = [
    ["tools/plugdev/run-player-scheduler-lifecycle.ps1", report.artifacts.runnerSha256],
    ["tools/plugdev/player-scheduler-lifecycle.mjs", report.artifacts.clientHelperSha256],
    ["tools/plugdev/player-scheduler-lifecycle-assertions.mjs", report.artifacts.assertionModuleSha256],
    ["tools/plugdev/player-scheduler-lifecycle-assertions.test.mjs", report.artifacts.assertionTestSha256],
    ["tools/plugdev/prepare.mjs", report.artifacts.prepareScriptSha256],
    ["src/acceptance/java/noietime/syncmoney/acceptance/AcceptanceProbe.java", report.artifacts.acceptanceProbeSourceSha256],
  ];
  const verifiedSources = sourcePins.map(([relativePath, expected]) => ({
    path: relativePath,
    sha256: verifyGitSourcePin(report.source.commit, relativePath, expected),
  }));

  const plugDevRoot = realpathSync(path.resolve(plugDevRootArg));
  verifyHash(readFileSync(path.join(plugDevRoot, "packages/cli/dist/cli.js")),
    report.artifacts.localPlugDevCliSha256, "local PlugDev CLI");
  const yaml = createRequire(path.join(plugDevRoot, "package.json"))("yaml");
  const configBytes = readContainedFile(projectRoot, ".plugdev/run/plugins/Syncmoney/config.yml");
  verifyHash(configBytes, report.acceptanceProfile.configSha256, "isolated breaker-only config");
  const config = yaml.parse(configBytes.toString("utf8"));
  const configChecks = [
    config.economy?.mode === "local_redis",
    config["db-enabled"] === false && config.database?.enabled === false,
    config["pubsub-enabled"] === true && config.redis?.enabled === true,
    config["circuit-breaker"]?.enabled === true &&
      config["circuit-breaker"]?.["max-transactions-per-second"] === 5,
    config["circuit-breaker"]?.["player-protection"]?.enabled === false,
    config["circuit-breaker"]?.["player-protection"]?.["global-lock"]?.enabled === false,
    config["shadow-sync"]?.enabled === false && config.audit?.enabled === false,
    config["transfer-guard"]?.enabled === false && config["web-admin"]?.enabled === false &&
      config["discord-webhook"]?.enabled === false,
  ];
  requireCondition(configChecks.every(Boolean), "isolated config is not the exact breaker-only profile");
  const profileMarker = "BREAKER_GUARD_PROFILE_PASS profile=breaker-only mode=local_redis circuit-breaker=on player-protection=off optional-modules=off";
  const profileAudit = readContainedFile(runRoot, "logs/guard-profile-audit.txt").toString("utf8").split(/\r?\n/);
  requireCondition(profileAudit.filter((line) => line === profileMarker).length === 1,
    "breaker-only profile audit marker is missing or duplicated");

  const serverLogBytes = readContainedFile(runRoot, "logs/server.log");
  const serverLog = serverLogBytes.toString("utf8");
  verifyHash(serverLogBytes, report.server.logSha256, "server log");
  requireCondition(serverLog.includes("Syncmoney enabled successfully.") &&
    serverLog.includes("Syncmoney disabled."),
  "server log lacks startup or normal-shutdown evidence");
  const serverLines = serverLog.split(/\r?\n/);
  validateApiClassloaderEvidence(serverLines);
  requireCondition(hasSingleSuccessfulTeleportFuture(serverLines) && serverLog.includes("T15_TELEPORT_REACHED"),
    "server log lacks successful teleport lifecycle evidence");
  const lifecycleEvidence = validateLifecycleEvidence(report, serverLines);

  const teleportLogBytes = readContainedFile(runRoot, "logs/teleport-mineflayer.log");
  verifyHash(teleportLogBytes, report.client.teleport.logSha256, "teleport client log");
  requireCondition(/BOT_NETHER_ARRIVAL dimension=(?:the_nether|nether)(?:\s|$)/.test(teleportLogBytes.toString("utf8")),
    "teleport client did not confirm Nether arrival");
  const logoutLogBytes = readContainedFile(runRoot, "logs/logout-mineflayer.log");
  verifyHash(logoutLogBytes, report.client.logout.logSha256, "logout client log");
  const logoutText = logoutLogBytes.toString("utf8");
  const logoutLines = logoutText.split(/\r?\n/);
  const clientObservedExpectedKickPacket = logoutLines.includes("BOT_KICK_REASON_PASS reason=T15 accepted-write logout scenario");
  const clientObservedExpectedLogoutAbort = logoutLines.includes("BOT_LOGOUT_ABORTED_AFTER_COMMAND code=ECONNABORTED");
  requireCondition(report.client.logout.clientObservedExpectedKickPacket === clientObservedExpectedKickPacket &&
    report.client.logout.clientObservedExpectedLogoutAbort === clientObservedExpectedLogoutAbort,
  "logout report client observations do not match the retained Mineflayer log");
  const playerUuid = extractPlayerUuidFromJoin(logoutText, "AcceptanceA");
  requireCondition(playerUuid, "logout client log does not identify AcceptanceA's UUID");
  const escapedUuid = playerUuid;
  const logoutEvidenceLines = serverLines.filter((line) =>
    new RegExp("\\bT15_LOGOUT_REQUEST uuid=" + escapedUuid + "(?:\\s|$)").test(line) ||
    new RegExp("\\bT15_PLAYER_QUIT uuid=" + escapedUuid + "(?:\\s|$)").test(line) ||
    /AcceptanceA lost connection: /.test(line));
  const logoutEvidence = hasExpectedLogoutScenarioEvidence({
    logLines: logoutEvidenceLines,
    playerName: "AcceptanceA",
    playerUuid,
    clientObservedExpectedKickPacket,
    clientObservedExpectedLogoutAbort,
  });
  requireCondition(logoutEvidence, "server/client logout evidence does not prove the expected kick reason");
  const exactServerKickReasonVerified = hasExactExpectedServerKick(serverLines, "AcceptanceA");
  requireCondition(report.client.logout.exactServerKickReasonVerified === exactServerKickReasonVerified &&
    report.observed.lifecycle.exactServerKickReasonVerified === exactServerKickReasonVerified,
  "logout exact-server-reason report flags do not match the retained server log");

  const breakerLogBytes = readContainedFile(runRoot, "logs/breaker-pair-mineflayer.log");
  verifyHash(breakerLogBytes, report.client.globalBreaker.logSha256, "breaker-pair client log");
  const breakerClientLines = breakerLogBytes.toString("utf8").split(/\r?\n/).filter((line) =>
    /^BOT_GLOBAL_BREAKER_(?:PASS|ACK_PASS|FAIL)/.test(line));
  const breakerServerLines = extractServerProofLines(serverLog);
  const globalBreaker = report.observed.globalBreaker;
  requireCondition(sameJson(globalBreaker.ownerEvidenceLines, breakerServerLines),
    "report server proof lines do not match the retained server log");
  requireCondition(sameJson(globalBreaker.clientPassLines,
    breakerClientLines.filter((line) => /^BOT_GLOBAL_BREAKER_PASS /.test(line))) &&
    sameJson(globalBreaker.clientAcknowledgementLines,
      breakerClientLines.filter((line) => /^BOT_GLOBAL_BREAKER_ACK_PASS /.test(line))),
  "report client proof lines do not match the retained client log");
  requireCondition(hasGlobalBreakerPairRuntimeProof({
    serverLines: breakerServerLines,
    clientLines: breakerClientLines,
    participants: globalBreaker.participants,
  }), "retained server/client logs fail the global breaker, owner, or ACK proof");

  const redisLogBytes = readContainedFile(runRoot, "logs/redis-server.log");
  const redisLog = redisLogBytes.toString("utf8");
  verifyHash(redisLogBytes, report.redis.logSha256, "Redis server log");
  const versions = [...redisLog.matchAll(/Redis version=([^,\s]+)/g)].map((match) => match[1]);
  requireCondition(versions.length === 1 && versions[0] === "8.10.1", "isolated Redis version mismatch");
  requireCondition(redisLog.includes("User requested shutdown...") &&
    redisLog.includes("Redis is now ready to exit, bye bye..."), "Redis log lacks graceful-shutdown markers");
  const redisFiles = report.redis.files.map((file) => {
    const bytes = readContainedFile(runRoot, file.path);
    requireCondition(bytes.byteLength === file.bytes, "Redis file length mismatch for " + file.path);
    return { path: file.path, bytes: file.bytes, sha256: verifyHash(bytes, file.sha256, "Redis file " + file.path) };
  });

  const auditorPath = fileURLToPath(import.meta.url);
  const testPath = path.resolve(path.dirname(auditorPath), "audit-player-scheduler-breaker-only.test.mjs");
  const audit = {
    schemaVersion: 1,
    status: "PASS",
    audit: "T21 current-maximum Canvas breaker-only offline evidence audit",
    runId: report.runId,
    endpoint: report.endpoint,
    report: {
      path: path.relative(reportsRoot, realReportPath).replaceAll(path.sep, "/"),
      sha256: sha256(reportBytes),
    },
    evidence: {
      level: report.evidenceLevel,
      endpoint: "Canvas 26.3#984",
      profile: "LOCAL_REDIS; global breaker on at 5 TPS; per-player protection off",
      distinctEntityOwners: 2,
      attemptsAcceptedRateLimited: "6/5/1 in one TPS window",
      clientAcknowledgements: 2,
      redisParity: globalBreaker.participants.map((p) => ({
        player: p.player, redisBalance: p.redisBalance, redisVersion: p.redisVersion,
      })),
      acceptedWriteTeleportLogoutLifecycle: true,
      acceptedWriteMarkers: lifecycleEvidence.acceptedWriteMarkers,
      pendingBoundaries: lifecycleEvidence.pendingBoundaries,
      boundaryActionThreads: lifecycleEvidence.boundaryActionThreads,
      gracefulServerAndRedisShutdown: true,
      redisPersistenceHashesRecorded: true,
      redisAofReloadPerformed: false,
    },
    auditor: { path: "tools/plugdev/audit-player-scheduler-breaker-only.mjs", sha256: sha256(readFileSync(auditorPath)) },
    regressionTest: { path: "tools/plugdev/audit-player-scheduler-breaker-only.test.mjs", sha256: sha256(readFileSync(testPath)) },
    assertions: assertionCount,
    sources: verifiedSources,
    configChecks: configChecks.length,
    redisFiles,
  };
  const auditPath = path.join(runRoot, "offline-audit.json");
  writeFileSync(auditPath, JSON.stringify(audit, null, 2) + "\n");
  console.log("OFFLINE_AUDIT_RESULT=PASS assertions=" + assertionCount);
  console.log("OFFLINE_AUDIT_PATH=" + auditPath);
  return audit;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    runAudit();
  } catch (error) {
    console.error(error?.stack ?? error);
    process.exitCode = 1;
  }
}

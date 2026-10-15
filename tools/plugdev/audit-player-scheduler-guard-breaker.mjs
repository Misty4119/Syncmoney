import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { createRequire } from "node:module";
import { readFileSync, realpathSync, statSync, writeFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

import { hasGuardPairCircuitBreakerRuntimeProof } from "./player-scheduler-lifecycle-assertions.mjs";

const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const reportsRoot = path.resolve(repositoryRoot, "build/reports/foundation/T21-player-scheduler");
const ENDPOINTS = {
  "canvas-1218-min": {
    minecraftVersion: "1.21.8",
    build: 603,
    serverJar: "canvas-1.21.8-603.jar",
    serverJarSha256: "6577181e4bc8ac40a7a48ff00e262f2ac7a798f48ada446a07eb06618dc36f1b",
  },
  "canvas-263-max": {
    minecraftVersion: "26.3",
    build: 972,
    serverJar: "canvas-26.3-972.jar",
    serverJarSha256: "aac025fe36a61f84fecb04de74e94bbf933c7081c4c88f13a9d4b96de42243f5",
  },
};
let assertionCount = 0;

function requireCondition(condition, message) {
  assertionCount += 1;
  if (!condition) throw new Error(message);
}

export function validateGuardBreakerReport(report) {
  requireCondition(report && typeof report === "object", "report must be an object");
  requireCondition(report.status === "PASS", "report status must be PASS");
  requireCondition(typeof report.task === "string" && report.task.includes("global circuit breaker enabled"),
    "report task must identify the global circuit breaker case");

  const endpoint = report.endpoint;
  const pin = ENDPOINTS[endpoint?.label];
  requireCondition(pin && endpoint.software === "canvas" && endpoint.minecraftVersion === pin.minecraftVersion &&
    endpoint.build === pin.build && endpoint.serverJarSha256 === pin.serverJarSha256,
  "report does not match a user-pinned endpoint");

  requireCondition(typeof report.runtime?.java === "string" && /version "25(?:\.|"|\+)/.test(report.runtime.java),
    "runtime must identify Java 25 or newer");
  requireCondition(/^[a-f0-9]{64}$/i.test(report.source?.candidateJarSha256 ?? "") &&
    report.source.candidateJarSha256 === report.artifacts?.candidateJarSha256,
  "candidate JAR hash is missing or inconsistent");
  requireCondition(report.isolation?.mode === "local_redis" && report.isolation.playerProtection === true &&
    report.isolation.circuitBreaker === true,
  "report must isolate LOCAL_REDIS with both guard layers enabled");
  requireCondition(report.observed?.guard?.ownerProofs === 2 &&
    report.observed.guard.independentRateLimitRejections === 2 && report.observed.guard.playerNotifications === 2,
  "report must include both owner-boundary, rejection, and notification observations");
  requireCondition(report.observed.guard.circuitBreakerEnabled === true &&
    report.observed.guard.circuitBreakerUnlockedOnEachOwner === 2,
  "global circuit breaker must be enabled and observed unlocked on each entity owner");
  requireCondition(report.client?.guard?.scenario === "guard-pair-breaker" && report.client.guard.exitCode === 0,
    "report must include the guard-pair-breaker client scenario");

  const guardAccounts = report.redis?.guardAccounts;
  requireCondition(Array.isArray(guardAccounts) && guardAccounts.length === 2 &&
    ["AcceptanceGuardA", "AcceptanceGuardB"].every((player) => guardAccounts.some((account) =>
      account.player === player && account.redisBalance === "5.00" && account.redisVersion === "5")),
  "each guard participant must persist Redis 5.00/v5");

  requireCondition(report.server?.normalStartExitCode === 0 && report.server.normalStopExitCode === 0 &&
    report.server.processExitVerified === true && report.server.syncmoneyDisabledMarker === true,
  "server must start and stop normally with the Syncmoney disabled marker");
  requireCondition(report.redis?.normalShutdownExitCode === 0 && report.redis.processExitCode === 0 &&
    report.redis.processExitVerified === true && /^[a-f0-9]{64}$/i.test(report.redis.logSha256 ?? ""),
  "Redis normal shutdown evidence is incomplete");
  requireCondition(report.forcedCleanup === false && report.cleanupFailure == null,
    "report indicates forced cleanup or cleanup failure");
  return true;
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function isWithin(root, candidate) {
  return candidate.startsWith(`${root}${path.sep}`);
}

function readContainedFile(root, relativePath) {
  const lexicalPath = path.resolve(root, relativePath);
  requireCondition(isWithin(root, lexicalPath), `path escapes its root: ${relativePath}`);
  const realPath = realpathSync(lexicalPath);
  requireCondition(isWithin(root, realPath), `resolved path escapes its root: ${relativePath}`);
  requireCondition(statSync(realPath).isFile(), `expected a file: ${relativePath}`);
  return readFileSync(realPath);
}

function verifyHash(bytes, expected, label) {
  const actual = sha256(bytes);
  requireCondition(actual === expected, `${label} SHA-256 mismatch: expected ${expected}, observed ${actual}`);
  return actual;
}

function verifyGitSourcePin(sourceCommit, relativePath, expectedSha256) {
  const bytes = execFileSync("git", ["-C", repositoryRoot, "show", `${sourceCommit}:${relativePath}`]);
  return verifyHash(bytes, expectedSha256, `source ${relativePath} at ${sourceCommit}`);
}

function runAudit() {
  const args = process.argv.slice(2);
  const resultPathArg = args[0];
  const plugDevFlagIndex = args.indexOf("--plugdev-root");
  const plugDevRootArg = plugDevFlagIndex >= 0 ? args[plugDevFlagIndex + 1] : null;
  if (!resultPathArg || !plugDevRootArg || args.length !== 3) {
    throw new Error("Usage: node tools/plugdev/audit-player-scheduler-guard-breaker.mjs <result.json> --plugdev-root <local-plugdev-root>");
  }

  const realReportsRoot = realpathSync(reportsRoot);
  const realReportPath = realpathSync(path.resolve(resultPathArg));
  requireCondition(isWithin(realReportsRoot, realReportPath) && path.basename(realReportPath) === "result.json",
    "result.json must be inside the T21 player-scheduler reports directory");
  const runRoot = realpathSync(path.dirname(realReportPath));
  const reportBytes = readFileSync(realReportPath);
  const report = JSON.parse(reportBytes.toString("utf8"));
  validateGuardBreakerReport(report);
  requireCondition(path.resolve(report.isolation.runRoot) === runRoot, "report runRoot does not match its directory");
  requireCondition(path.resolve(report.source.repository) === repositoryRoot, "report repository root mismatch");

  const endpointPin = ENDPOINTS[report.endpoint.label];
  const expectedServerJar = path.resolve(repositoryRoot,
    "build/reports/foundation/T15-runtime-pins/artifacts", endpointPin.serverJar);
  requireCondition(path.resolve(report.endpoint.serverJar) === expectedServerJar, "report endpoint JAR path mismatch");
  const serverArtifact = readFileSync(expectedServerJar);
  verifyHash(serverArtifact, endpointPin.serverJarSha256, "pinned Canvas server JAR");

  const candidateJar = readContainedFile(path.join(runRoot, "project"), "build/libs/Syncmoney-1.4.0-alpha.1.jar");
  verifyHash(candidateJar, report.artifacts.candidateJarSha256, "isolated candidate JAR");
  const acceptanceJar = readContainedFile(path.join(runRoot, "project"), "build/acceptance/SyncmoneyAcceptance.jar");
  verifyHash(acceptanceJar, report.artifacts.acceptanceJarSha256, "isolated acceptance JAR");
  const apiConsumerJar = readContainedFile(path.join(runRoot, "project"), "build/acceptance/SyncmoneyApiConsumer.jar");
  verifyHash(apiConsumerJar, report.artifacts.apiConsumerJarSha256, "isolated API-consumer JAR");

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
  const plugDevCli = readFileSync(path.join(plugDevRoot, "packages/cli/dist/cli.js"));
  verifyHash(plugDevCli, report.artifacts.localPlugDevCliSha256, "local PlugDev CLI");
  const plugDevRequire = createRequire(path.join(plugDevRoot, "package.json"));
  const yaml = plugDevRequire("yaml");
  const configBytes = readContainedFile(path.join(runRoot, "project"),
    ".plugdev/run/plugins/Syncmoney/config.yml");
  verifyHash(configBytes, report.isolation.playerProtectionOnlyConfigSha256, "isolated guard config");
  const config = yaml.parse(configBytes.toString("utf8"));
  const configChecks = [
    config.economy?.mode === "local_redis",
    config["db-enabled"] === false && config.database?.enabled === false,
    config["pubsub-enabled"] === true && config.redis?.enabled === true,
    config["circuit-breaker"]?.enabled === true && config["circuit-breaker"]?.["max-transactions-per-second"] === 100,
    config["circuit-breaker"]?.["player-protection"]?.enabled === true,
    config["circuit-breaker"]?.["player-protection"]?.["global-lock"]?.enabled === false,
    config["shadow-sync"]?.enabled === false && config.audit?.enabled === false,
    config["transfer-guard"]?.enabled === false && config["web-admin"]?.enabled === false &&
      config["discord-webhook"]?.enabled === false,
  ];
  requireCondition(configChecks.every(Boolean), "isolated guard profile config does not match the expected scope");

  const profileAuditBytes = readContainedFile(runRoot, "logs/guard-profile-audit.txt");
  requireCondition(profileAuditBytes.toString("utf8").includes(
    "GUARD_PROFILE_PASS mode=local_redis circuit-breaker=on player-protection=on optional-modules=off"),
  "guard profile audit marker is missing");
  const serverLogBytes = readContainedFile(runRoot, "logs/server.log");
  const serverLog = serverLogBytes.toString("utf8");
  verifyHash(serverLogBytes, report.server.logSha256, "server log");
  requireCondition(serverLog.includes("Syncmoney enabled successfully.") && serverLog.includes("API CLASSLOADER PASS") &&
    serverLog.includes("Syncmoney disabled.") && serverLog.includes("T21_GUARD_PAIR_PASS players=AcceptanceGuardA,AcceptanceGuardB"),
  "server log lacks required startup, guard completion, or shutdown evidence");

  const clientLogBytes = readContainedFile(runRoot, "logs/guard-pair-breaker-mineflayer.log");
  verifyHash(clientLogBytes, report.client.guard.logSha256, "guard-pair-breaker client log");
  const clientLines = clientLogBytes.toString("utf8").split(/\r?\n/);
  const serverLines = serverLog.split(/\r?\n/).filter((line) =>
    /T21_GUARD_(?:OWNER|TRANSACTIONS|BREAKER_STATE|FAIL)|Deposit rejected by PlayerTransactionGuard:/.test(line));
  const proof = {
    serverLines,
    clientLines: clientLines.filter((line) => /^BOT_GUARD_(?:RATE_LIMIT_NOTICE_PASS|FAIL)/.test(line)),
    participants: report.redis.guardAccounts,
  };
  requireCondition(hasGuardPairCircuitBreakerRuntimeProof(proof), "independent owner/breaker guard proof failed");

  const redisLogBytes = readContainedFile(runRoot, "logs/redis-server.log");
  const redisLog = redisLogBytes.toString("utf8");
  verifyHash(redisLogBytes, report.redis.logSha256, "Redis server log");
  const redisVersions = [...redisLog.matchAll(/Redis version=([^,\s]+)/g)].map((match) => match[1]);
  requireCondition(redisVersions.length === 1 && redisVersions[0] === "8.10.1", "isolated Redis log version mismatch");
  requireCondition(redisLog.includes("User requested shutdown...") &&
    redisLog.includes("Redis is now ready to exit, bye bye..."),
  "Redis log lacks the graceful-shutdown markers");
  const redisFiles = report.redis.files.map((file) => {
    const bytes = readContainedFile(runRoot, file.path);
    requireCondition(bytes.byteLength === file.bytes, `Redis file length mismatch for ${file.path}`);
    return { path: file.path, bytes: file.bytes, sha256: verifyHash(bytes, file.sha256, `Redis file ${file.path}`) };
  });

  const audit = {
    schemaVersion: 1,
    status: "PASS",
    audit: "T21 Canvas two-owner guard and global-breaker offline evidence audit",
    runId: report.runId,
    endpoint: report.endpoint,
    report: { path: path.relative(reportsRoot, realReportPath).replaceAll(path.sep, "/"), sha256: sha256(reportBytes) },
    evidence: {
      level: report.evidenceLevel,
      twoEntityOwners: true,
      globalBreakerEnabledAndUnlockedPerOwner: 2,
      playerProtectionOutcomes: "SSSSSF for both players",
      redisBalances: "5.00/v5 for both players",
      gracefulServerAndRedisShutdown: true,
    },
    sources: verifiedSources,
    configChecks: configChecks.length,
    redisFiles,
  };
  const auditPath = path.join(runRoot, "offline-audit.json");
  writeFileSync(auditPath, `${JSON.stringify(audit, null, 2)}\n`);
  console.log(`OFFLINE_AUDIT_RESULT=PASS assertions=${assertionCount}`);
  console.log(`OFFLINE_AUDIT_PATH=${auditPath}`);
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

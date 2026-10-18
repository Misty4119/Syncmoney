import assert from "node:assert/strict";
import test from "node:test";

import { validateBreakerOnlyReport } from "./audit-player-scheduler-breaker-only.mjs";

const uuidA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const uuidB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const hash = "a".repeat(64);
const serverProofLines = [
  "T21_GLOBAL_BREAKER_STATE circuitBreaker=enabled circuitBreakerLocked=false playerProtection=disabled maxTransactionsPerSecond=5 player=AcceptanceGuardA",
  "T21_GLOBAL_BREAKER_OWNER player=AcceptanceGuardA uuid=" + uuidA + " selfOwned=true otherOwned=false scheduler=entity",
  "T21_GLOBAL_BREAKER_TRANSACTIONS player=AcceptanceGuardA uuid=" + uuidA + " attempted=3 accepted=3 rejected=0 outcomes=SSS windowSeconds=100,100,100 before=0.00 after=3.00",
  "T21_GLOBAL_BREAKER_ACK player=AcceptanceGuardA uuid=" + uuidA + " recipient=AcceptanceGuardA",
  "T21_GLOBAL_BREAKER_STATE circuitBreaker=enabled circuitBreakerLocked=false playerProtection=disabled maxTransactionsPerSecond=5 player=AcceptanceGuardB",
  "T21_GLOBAL_BREAKER_OWNER player=AcceptanceGuardB uuid=" + uuidB + " selfOwned=true otherOwned=false scheduler=entity",
  "T21_GLOBAL_BREAKER_TRANSACTIONS player=AcceptanceGuardB uuid=" + uuidB + " attempted=3 accepted=2 rejected=1 outcomes=SFS windowSeconds=100,100,100 before=0.00 after=2.00",
  "T21_GLOBAL_BREAKER_ACK player=AcceptanceGuardB uuid=" + uuidB + " recipient=AcceptanceGuardB",
  "T21_GLOBAL_BREAKER_PAIR_PASS players=AcceptanceGuardA,AcceptanceGuardB attempts=6 accepted=5 rejected=1 windowSecond=100 playerProtection=disabled circuitBreakerLocked=false",
  "Deposit rejected by CircuitBreaker: Transaction frequency too high, please try again later for " + uuidB,
  "WARN CircuitBreaker: Transaction rate limit exceeded (Count: 6, Source: VAULT_DEPOSIT)",
];
const clientPassLines = [
  "BOT_GLOBAL_BREAKER_PASS player=AcceptanceGuardA uuid=" + uuidA,
  "BOT_GLOBAL_BREAKER_PASS player=AcceptanceGuardB uuid=" + uuidB,
];
const clientAcknowledgementLines = [
  "BOT_GLOBAL_BREAKER_ACK_PASS player=AcceptanceGuardA uuid=" + uuidA + " recipient=AcceptanceGuardA",
  "BOT_GLOBAL_BREAKER_ACK_PASS player=AcceptanceGuardB uuid=" + uuidB + " recipient=AcceptanceGuardB",
];
const participants = [
  { player: "AcceptanceGuardA", uuid: uuidA, redisBalance: "3.00", redisVersion: "3" },
  { player: "AcceptanceGuardB", uuid: uuidB, redisBalance: "2.00", redisVersion: "2" },
];
const requiredRedisFiles = [
  "redis/data/dump.rdb",
  "redis/data/appendonlydir/appendonly.aof.1.base.rdb",
  "redis/data/appendonlydir/appendonly.aof.1.incr.aof",
  "redis/data/appendonlydir/appendonly.aof.manifest",
].map((filePath) => ({ path: filePath, bytes: 4, sha256: hash }));

function makeReport() {
  return {
    schemaVersion: 1,
    status: "PASS",
    evidenceLevel: "E2_REAL_SERVER_AND_ISOLATED_REDIS",
    task: "T21 Canvas two-owner global circuit-breaker rate-limit case with per-player protection disabled and player lifecycle scheduler subcase",
    runId: "canvas-263-currentmax-test",
    endpoint: {
      label: "canvas-263-currentmax",
      software: "canvas",
      minecraftVersion: "26.3",
      build: 984,
      serverJar: "C:\\\\server.jar",
      serverJarSha256: "0b94c4cba3e415586dddb69915b5aacf250636c62d058f4651c1ab34eaf265fa",
    },
    runtime: { java: 'openjdk version "25.0.4"' },
    source: { commit: "1".repeat(40), repository: "C:\\\\repo", candidateJarSha256: hash },
    artifacts: {
      candidateJarSha256: hash,
      acceptanceJarSha256: hash,
      apiConsumerJarSha256: hash,
      runnerSha256: hash,
      clientHelperSha256: hash,
      assertionModuleSha256: hash,
      assertionTestSha256: hash,
      prepareScriptSha256: hash,
      acceptanceProbeSourceSha256: hash,
      localPlugDevCliSha256: hash,
    },
    acceptanceProfile: { playerProtection: false, circuitBreaker: true, maxTransactionsPerSecond: 5, configSha256: hash },
    isolation: {
      mode: "local_redis",
      playerProtection: false,
      circuitBreaker: true,
      maxTransactionsPerSecond: 5,
      redisDatabase: 15,
      redisAof: true,
      appendFsync: "always",
      breakerOnlyConfigSha256: hash,
      runRoot: "C:\\\\reports\\\\run",
      projectRoot: "C:\\\\reports\\\\run\\\\project",
      plugDevHome: "C:\\\\reports\\\\run\\\\plugdev-home",
      gradleUserHome: "C:\\\\reports\\\\run\\\\gradle-user-home",
      redisData: "C:\\\\reports\\\\run\\\\redis\\\\data",
      runMarker: "C:\\\\reports\\\\run",
    },
    server: {
      normalStartExitCode: 0, normalStopExitCode: 0, processExitVerified: true,
      syncmoneyDisabledMarker: true, logSha256: hash,
    },
    redis: {
      normalShutdownExitCode: 0,
      processExitCode: 0,
      processExitVerified: true,
      databaseSize: 10,
      logSha256: hash,
      balance: "4.00",
      version: 4,
      balanceKey: "syncmoney:balance:cccccccc-cccc-4ccc-8ccc-cccccccccccc",
      versionKey: "syncmoney:version:cccccccc-cccc-4ccc-8ccc-cccccccccccc",
      files: requiredRedisFiles,
    },
    client: {
      globalBreaker: { scenario: "breaker-pair", protocol: "26.3", exitCode: 0, logSha256: hash },
      teleport: { protocol: "26.3", exitCode: 0, logSha256: hash, moduleSearchPath: "C:\\\\mineflayer\\\\node_modules" },
      logout: {
        protocol: "26.3", exitCode: 0, logSha256: hash, moduleSearchPath: "C:\\\\mineflayer\\\\node_modules",
        expectedKickReason: "T15 accepted-write logout scenario",
        clientObservedExpectedKickPacket: true,
        clientObservedExpectedLogoutAbort: false,
        exactServerKickReasonVerified: true,
      },
    },
    observed: {
      lifecycle: {
        acceptedWriteMarkers: 4,
        pendingBoundaries: { teleport: 2, logout: 2 },
        teleportFutureSucceeded: true,
        serverNetherArrival: true,
        clientNetherArrival: true,
        expectedLogoutKick: true,
        clientObservedExpectedKickPacket: true,
        clientObservedExpectedLogoutAbort: false,
        exactServerKickReasonVerified: true,
      },
      globalBreaker: {
        ownerProofs: 2,
        globalRateLimitAttempts: 6,
        acceptedTransactions: 5,
        rateLimitedTransactions: 1,
        maxTransactionsPerSecond: 5,
        circuitBreakerEnabled: true,
        circuitBreakerLocked: false,
        playerProtectionEnabled: false,
        participants: participants.map((participant) => ({ ...participant })),
        ownerEvidenceLines: [...serverProofLines],
        clientPassLines: [...clientPassLines],
        clientAcknowledgementLines: [...clientAcknowledgementLines],
      },
    },
    forcedCleanup: false,
    cleanupFailure: null,
    gradleCleanup: { processIds: [], processExitVerified: true },
  };
}

test("validates the current-maximum breaker-only profile and both owner ACKs", () => {
  assert.equal(validateBreakerOnlyReport(makeReport()), true);
});

test("rejects a non-current endpoint", () => {
  const report = makeReport();
  report.endpoint.build = 972;
  assert.throws(() => validateBreakerOnlyReport(report), /current-maximum Canvas 26\.3#984/);
});

test("rejects accidental per-player protection or a changed global threshold", () => {
  const report = makeReport();
  report.acceptanceProfile.playerProtection = true;
  assert.throws(() => validateBreakerOnlyReport(report), /global breaker at five TPS/);
  const thresholdReport = makeReport();
  thresholdReport.isolation.maxTransactionsPerSecond = 100;
  assert.throws(() => validateBreakerOnlyReport(thresholdReport), /isolated LOCAL_REDIS breaker-only profile/);
});

test("rejects missing or misdirected client acknowledgement receipts", () => {
  const missingAck = makeReport();
  missingAck.observed.globalBreaker.clientAcknowledgementLines.pop();
  assert.throws(() => validateBreakerOnlyReport(missingAck), /owner, six-attempt, Redis-parity, and client ACK proof/);
  const wrongRecipient = makeReport();
  wrongRecipient.observed.globalBreaker.clientAcknowledgementLines[1] =
    wrongRecipient.observed.globalBreaker.clientAcknowledgementLines[1].replace(
      "recipient=AcceptanceGuardB", "recipient=AcceptanceGuardA");
  assert.throws(() => validateBreakerOnlyReport(wrongRecipient), /owner, six-attempt, Redis-parity, and client ACK proof/);
});

test("rejects Redis parity drift and forced cleanup", () => {
  const wrongBalance = makeReport();
  wrongBalance.observed.globalBreaker.participants[1].redisBalance = "3.00";
  assert.throws(() => validateBreakerOnlyReport(wrongBalance), /owner, six-attempt, Redis-parity, and client ACK proof/);
  const forced = makeReport();
  forced.forcedCleanup = true;
  assert.throws(() => validateBreakerOnlyReport(forced), /forced cleanup/);
});

test("rejects incomplete Redis persistence evidence", () => {
  const report = makeReport();
  report.redis.files = report.redis.files.filter((file) => !file.path.endsWith("appendonly.aof.manifest"));
  assert.throws(() => validateBreakerOnlyReport(report), /Redis persistence file evidence/);
});

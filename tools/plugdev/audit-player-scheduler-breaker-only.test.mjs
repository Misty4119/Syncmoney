import assert from "node:assert/strict";
import test from "node:test";

import {
  validateBreakerOnlyReport,
  validateApiClassloaderEvidence,
  validateLifecycleEvidence,
} from "./audit-player-scheduler-breaker-only.mjs";

const uuidA = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const uuidB = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const hash = "a".repeat(64);
const candidateHash = "7d4fb070ecdb61ece3d5e39b7a2bf6ceb72c0c6a939f269588344b41f908b354";
const otherCandidateHash = "b".repeat(64);
const lifecycleServerLines = [
  "T15_ACCEPTED_WRITE scenario=teleport index=1 uuid=" + uuidA + " amount=1.00 response=SUCCESS",
  "T15_ACCEPTED_WRITE scenario=teleport index=2 uuid=" + uuidA + " amount=1.00 response=SUCCESS",
  "T15_PENDING_AT_BOUNDARY scenario=teleport uuid=" + uuidA + " pending=2 pending-state=accepted-not-completed",
  "T15_BOUNDARY_ACTION scenario=teleport thread=Canvas Region Scheduler Thread #0",
  "T15_ACCEPTED_WRITE scenario=logout index=1 uuid=" + uuidA + " amount=1.00 response=SUCCESS",
  "T15_ACCEPTED_WRITE scenario=logout index=2 uuid=" + uuidA + " amount=1.00 response=SUCCESS",
  "T15_PENDING_AT_BOUNDARY scenario=logout uuid=" + uuidA + " pending=2 pending-state=accepted-not-completed",
  "T15_BOUNDARY_ACTION scenario=logout thread=Canvas Region Scheduler Thread #1",
];
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
    source: { commit: "1".repeat(40), repository: "C:\\\\repo", candidateJarSha256: candidateHash },
    artifacts: {
      candidateJarSha256: candidateHash,
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
        actionThreads: {
          teleport: "Canvas Region Scheduler Thread #0",
          logout: "Canvas Region Scheduler Thread #1",
        },
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

test("requires exactly one API classloader success marker", () => {
  const marker = "API CLASSLOADER PASS provider=Syncmoney";
  assert.equal(validateApiClassloaderEvidence([marker]), marker);
  assert.equal(validateApiClassloaderEvidence([marker.toLowerCase()]), marker.toLowerCase());
  assert.throws(() => validateApiClassloaderEvidence([]), /exactly one API CLASSLOADER PASS marker/);
  assert.throws(() => validateApiClassloaderEvidence([marker, marker]), /exactly one API CLASSLOADER PASS marker/);
});

test("rejects coordinated replacement of the candidate source and artifact hashes", () => {
  const report = makeReport();
  report.source.candidateJarSha256 = otherCandidateHash;
  report.artifacts.candidateJarSha256 = otherCandidateHash;
  assert.throws(() => validateBreakerOnlyReport(report), /independently pinned candidate JAR/);
});

test("re-derives accepted writes, pending counts, and non-empty boundary threads from server logs", () => {
  const report = makeReport();
  assert.deepEqual(validateLifecycleEvidence(report, lifecycleServerLines), {
    acceptedWriteMarkers: 4,
    pendingBoundaries: { teleport: 2, logout: 2 },
    boundaryActionThreads: {
      teleport: "Canvas Region Scheduler Thread #0",
      logout: "Canvas Region Scheduler Thread #1",
    },
  });
});

test("rejects a lifecycle failure marker even when positive evidence is present", () => {
  const serverLines = [
    ...lifecycleServerLines,
    "prefix t15_lifecycle_fail_suffix scenario=logout reason=accepted-write-not-drained",
  ];
  assert.throws(() => validateLifecycleEvidence(makeReport(), serverLines),
    /server log contains the T15 lifecycle failure marker/);
});

test("matches lifecycle failure and success markers case-insensitively like the runner", () => {
  const caseVariantLines = lifecycleServerLines.map((line) => line
    .replace(/T15_/g, "t15_")
    .replace(/scenario=(teleport|logout)/g, (_match, scenario) => "scenario=" + scenario.toUpperCase())
    .replace("pending-state=accepted-not-completed", "pending-state=ACCEPTED-NOT-COMPLETED"));
  assert.equal(validateLifecycleEvidence(makeReport(), caseVariantLines).acceptedWriteMarkers, 4);
});

test("accepts and reports three pending writes when the server marker and report agree", () => {
  const report = makeReport();
  report.observed.lifecycle.pendingBoundaries.teleport = 3;
  const serverLines = lifecycleServerLines.map((line) =>
    line.includes("T15_PENDING_AT_BOUNDARY scenario=teleport") ? line.replace("pending=2", "pending=3") : line);
  assert.equal(validateLifecycleEvidence(report, serverLines).pendingBoundaries.teleport, 3);
});

test("report validator allows pending-write counts greater than two", () => {
  const largerCounts = makeReport();
  largerCounts.observed.lifecycle.pendingBoundaries = { teleport: 3, logout: 4 };
  assert.equal(validateBreakerOnlyReport(largerCounts), true);

  const insufficientCounts = makeReport();
  insufficientCounts.observed.lifecycle.pendingBoundaries = { teleport: 1, logout: 1 };
  assert.throws(() => validateBreakerOnlyReport(insufficientCounts), /accepted-write teleport\/logout lifecycle evidence/);
});

test("rejects pending-write counts outside the runner's Int32 range", () => {
  const overflowReport = makeReport();
  overflowReport.observed.lifecycle.pendingBoundaries.teleport = 2_147_483_648;
  const overflowLog = lifecycleServerLines.map((line) =>
    line.includes("T15_PENDING_AT_BOUNDARY scenario=teleport") ? line.replace("pending=2", "pending=2147483648") : line);
  assert.throws(() => validateLifecycleEvidence(overflowReport, overflowLog), /pending-boundary server marker/);
  assert.throws(() => validateBreakerOnlyReport(overflowReport), /accepted-write teleport\/logout lifecycle evidence/);

  const fractionalReport = makeReport();
  fractionalReport.observed.lifecycle.pendingBoundaries.logout = 2.5;
  assert.throws(() => validateBreakerOnlyReport(fractionalReport), /accepted-write teleport\/logout lifecycle evidence/);
});

test("rejects report-only lifecycle counts when retained server markers disagree", () => {
  const report = makeReport();
  const missingAcceptedWrite = lifecycleServerLines.filter((line) =>
    line !== lifecycleServerLines[1]);
  assert.throws(() => validateLifecycleEvidence(report, missingAcceptedWrite), /accepted-write markers/);

  const wrongPendingCount = lifecycleServerLines.map((line) =>
    line.includes("T15_PENDING_AT_BOUNDARY scenario=teleport") ? line.replace("pending=2", "pending=1") : line);
  assert.throws(() => validateLifecycleEvidence(report, wrongPendingCount), /pending-boundary server marker/);

  const reportCountDrift = makeReport();
  reportCountDrift.observed.lifecycle.pendingBoundaries.teleport = 1;
  assert.throws(() => validateLifecycleEvidence(reportCountDrift, lifecycleServerLines), /lifecycle report counts/);

  const missingThreadReport = makeReport();
  delete missingThreadReport.observed.lifecycle.actionThreads;
  assert.throws(() => validateLifecycleEvidence(missingThreadReport, lifecycleServerLines), /report boundary action threads/);

  const wrongThreadReport = makeReport();
  wrongThreadReport.observed.lifecycle.actionThreads.teleport = "Global Scheduler Thread";
  assert.throws(() => validateLifecycleEvidence(wrongThreadReport, lifecycleServerLines), /report boundary action threads/);
});

test("rejects missing scheduler boundary thread evidence", () => {
  const missingThread = lifecycleServerLines.map((line) =>
    line.includes("T15_BOUNDARY_ACTION scenario=logout") ? line.replace("thread=Canvas Region Scheduler Thread #1", "thread=") : line);
  assert.throws(() => validateLifecycleEvidence(makeReport(), missingThread), /boundary action thread/);
});

test("rejects malformed and mismatched lifecycle UUIDs", () => {
  const malformedUuid = lifecycleServerLines.map((line) =>
    line.includes("T15_ACCEPTED_WRITE scenario=teleport index=1")
      ? line.replace(uuidA, "z".repeat(36))
      : line);
  assert.throws(() => validateLifecycleEvidence(makeReport(), malformedUuid), /accepted-write server marker is malformed/);

  const mismatchedAcceptedUuid = lifecycleServerLines.map((line) =>
    line.includes("T15_ACCEPTED_WRITE scenario=teleport index=2") ? line.replace(uuidA, uuidB) : line);
  assert.throws(() => validateLifecycleEvidence(makeReport(), mismatchedAcceptedUuid), /both successful writes/);

  const mismatchedPendingUuid = lifecycleServerLines.map((line) =>
    line.includes("T15_PENDING_AT_BOUNDARY scenario=logout") ? line.replace(uuidA, uuidB) : line);
  assert.throws(() => validateLifecycleEvidence(makeReport(), mismatchedPendingUuid), /pending-boundary server marker/);
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

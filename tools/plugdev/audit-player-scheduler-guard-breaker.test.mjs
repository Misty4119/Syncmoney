import assert from "node:assert/strict";
import test from "node:test";
import * as guardBreakerAudit from "./audit-player-scheduler-guard-breaker.mjs";

import { canonicalTextSourceSha256, validateGuardBreakerReport } from "./audit-player-scheduler-guard-breaker.mjs";

function reportFixture() {
  return {
    status: "PASS",
    task: "T21 Canvas two-owner entity guard with global circuit breaker enabled and player lifecycle scheduler subcase",
    endpoint: {
      label: "canvas-1218-min",
      software: "canvas",
      minecraftVersion: "1.21.8",
      build: 603,
      serverJarSha256: "6577181e4bc8ac40a7a48ff00e262f2ac7a798f48ada446a07eb06618dc36f1b",
    },
    runtime: { java: 'java version "25.0.4"' },
    client: {
      guard: { scenario: "guard-pair-breaker", exitCode: 0 },
      teleport: { exitCode: 0, logSha256: "c".repeat(64) },
      logout: {
        exitCode: 0,
        logSha256: "d".repeat(64),
        clientObservedExpectedKickPacket: false,
        clientObservedExpectedLogoutAbort: true,
        exactServerKickReasonVerified: true,
      },
    },
    source: { candidateJarSha256: "a".repeat(64) },
    artifacts: { candidateJarSha256: "a".repeat(64) },
    isolation: { mode: "local_redis", playerProtection: true, circuitBreaker: true },
    server: { normalStartExitCode: 0, normalStopExitCode: 0, processExitVerified: true, syncmoneyDisabledMarker: true },
    redis: {
      normalShutdownExitCode: 0,
      processExitCode: 0,
      processExitVerified: true,
      logSha256: "b".repeat(64),
      guardAccounts: [
        { player: "AcceptanceGuardA", redisBalance: "5.00", redisVersion: "5" },
        { player: "AcceptanceGuardB", redisBalance: "5.00", redisVersion: "5" },
      ],
    },
    observed: {
      lifecycle: { expectedLogoutKick: true },
      guard: {
        ownerProofs: 2,
        independentRateLimitRejections: 2,
        playerNotifications: 2,
        circuitBreakerEnabled: true,
        circuitBreakerUnlockedOnEachOwner: 2,
      },
    },
    forcedCleanup: false,
  };
}

test("accepts a bounded user-pinned Canvas guard/breaker report with graceful cleanup", () => {
  assert.equal(validateGuardBreakerReport(reportFixture()), true);
});

test("accepts the pinned current-maximum Canvas 26.3#984 endpoint", () => {
  const report = reportFixture();
  report.endpoint = {
    label: "canvas-263-currentmax",
    software: "canvas",
    minecraftVersion: "26.3",
    build: 984,
    serverJarSha256: "0b94c4cba3e415586dddb69915b5aacf250636c62d058f4651c1ab34eaf265fa",
  };
  assert.equal(validateGuardBreakerReport(report), true);
});

test("requires the lifecycle client logs and accepted logout marker", () => {
  const missingLog = reportFixture();
  delete missingLog.client.logout;
  assert.throws(() => validateGuardBreakerReport(missingLog), /accepted logout lifecycle evidence/);

  const missingLogoutProof = reportFixture();
  missingLogoutProof.observed.lifecycle.expectedLogoutKick = false;
  assert.throws(() => validateGuardBreakerReport(missingLogoutProof), /accepted logout lifecycle evidence/);
});

test("recognizes only explicit guard-plus-breaker profile audit markers", () => {
  const recognizes = guardBreakerAudit.hasGuardBreakerProfileMarker;
  assert.equal(typeof recognizes, "function");
  assert.equal(recognizes("BREAKER_GUARD_PROFILE_PASS profile=guard-breaker-on mode=local_redis circuit-breaker=on player-protection=on optional-modules=off"), true);
  assert.equal(recognizes("GUARD_PROFILE_PASS mode=local_redis circuit-breaker=on player-protection=on optional-modules=off"), true);
  assert.equal(recognizes("BREAKER_GUARD_PROFILE_PASS profile=guard-on mode=local_redis circuit-breaker=off player-protection=on optional-modules=off"), false);
  assert.equal(recognizes("GUARD_PROFILE_PASS mode=local_redis circuit-breaker=on player-protection=off optional-modules=off"), false);
});

test("keeps notice and breaker failure markers in owner-boundary audit evidence", () => {
  const extract = guardBreakerAudit.extractGuardBreakerServerLines;
  assert.equal(typeof extract, "function");
  const ownerLine = "[INFO]: [SyncmoneyAcceptance] T21_GUARD_OWNER player=AcceptanceGuardA uuid=35c93f74-c540-3b38-8093-b5cb3c2263be selfOwned=true otherOwned=false scheduler=entity";
  const acknowledgementLine = "[INFO]: [SyncmoneyAcceptance] T21_GUARD_NOTICE_ACK stage=1 player=AcceptanceGuardA uuid=35c93f74-c540-3b38-8093-b5cb3c2263be recipient=AcceptanceGuardA";
  const breakerFailureLine = "[WARN]: [SyncmoneyAcceptance] T21_GUARD_BREAKER_FAIL player=AcceptanceGuardA reason=locked";

  assert.deepEqual(extract([
    ownerLine,
    "[INFO]: unrelated server startup line",
    acknowledgementLine,
    breakerFailureLine,
  ].join("\n")), [ownerLine, acknowledgementLine, breakerFailureLine]);
  assert.deepEqual(extract(null), []);
});

test("canonical source hashes ignore LF, CRLF, and mixed line endings", () => {
  const lfBytes = Buffer.from("first line\nsecond line\n", "utf8");
  const crlfBytes = Buffer.from("first line\r\nsecond line\r\n", "utf8");
  const mixedBytes = Buffer.from("first line\r\nsecond line\n", "utf8");
  const changedBytes = Buffer.from("first line\nchanged line\n", "utf8");
  const expectedHash = canonicalTextSourceSha256(lfBytes);

  assert.equal(canonicalTextSourceSha256(crlfBytes), expectedHash);
  assert.equal(canonicalTextSourceSha256(mixedBytes), expectedHash);
  assert.notEqual(canonicalTextSourceSha256(changedBytes), expectedHash);
});

test("rejects a report that substitutes a non-pinned endpoint", () => {
  const report = reportFixture();
  report.endpoint.build = 604;
  assert.throws(() => validateGuardBreakerReport(report), /pinned endpoint/);
});

test("rejects a report missing either manager-state observation", () => {
  const report = reportFixture();
  report.observed.guard.circuitBreakerUnlockedOnEachOwner = 1;
  assert.throws(() => validateGuardBreakerReport(report), /each entity owner/);
});

test("rejects forced cleanup and non-normal Redis shutdown", () => {
  const forced = reportFixture();
  forced.forcedCleanup = true;
  assert.throws(() => validateGuardBreakerReport(forced), /forced cleanup/);

  const redisFailure = reportFixture();
  redisFailure.redis.normalShutdownExitCode = 1;
  assert.throws(() => validateGuardBreakerReport(redisFailure), /Redis normal shutdown/);

  const redisLogMissing = reportFixture();
  delete redisLogMissing.redis.logSha256;
  assert.throws(() => validateGuardBreakerReport(redisLogMissing), /Redis normal shutdown/);
});

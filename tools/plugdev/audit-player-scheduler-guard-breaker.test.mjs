import assert from "node:assert/strict";
import test from "node:test";

import { validateGuardBreakerReport } from "./audit-player-scheduler-guard-breaker.mjs";

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
    client: { guard: { scenario: "guard-pair-breaker", exitCode: 0 } },
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

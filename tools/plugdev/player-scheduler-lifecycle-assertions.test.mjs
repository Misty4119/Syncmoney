import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import * as lifecycleAssertions from "./player-scheduler-lifecycle-assertions.mjs";
import {
  EXPECTED_LOGOUT_REASON,
  chatComponentText,
  hasExactExpectedServerKick,
  hasSingleSuccessfulTeleportFuture,
  isExpectedLogoutKick,
  isNetherDimension,
  normalizedDimension,
} from "./player-scheduler-lifecycle-assertions.mjs";

test("extracts nested Minecraft disconnect component text", () => {
  assert.equal(chatComponentText({ text: "T15 accepted-", extra: [{ text: "write logout scenario" }] }),
    EXPECTED_LOGOUT_REASON);
});

test("extracts disconnect text from typed Minecraft protocol components", () => {
  assert.equal(chatComponentText({
    type: "compound",
    value: {
      extra: { type: "list", value: { type: "string", value: [EXPECTED_LOGOUT_REASON] } },
      text: { type: "string", value: "" },
    },
  }), EXPECTED_LOGOUT_REASON);
});

test("accepts only the explicit lifecycle logout kick reason", () => {
  assert.equal(isExpectedLogoutKick(EXPECTED_LOGOUT_REASON), true);
  assert.equal(isExpectedLogoutKick({ text: EXPECTED_LOGOUT_REASON }), true);
  assert.equal(isExpectedLogoutKick("Disconnected"), false);
  assert.equal(isExpectedLogoutKick({ text: "T15 accepted-write logout scenario (other reason)" }), false);
  assert.equal(isExpectedLogoutKick(undefined), false);
});

test("recognizes a socket abort only after the logout command was sent", () => {
  const isExpectedLogoutTransportAbort = lifecycleAssertions.isExpectedLogoutTransportAbort;
  assert.equal(isExpectedLogoutTransportAbort?.("logout", true, "ECONNABORTED"), true);
  assert.equal(isExpectedLogoutTransportAbort?.("teleport", true, "ECONNABORTED"), false);
  assert.equal(isExpectedLogoutTransportAbort?.("logout", false, "ECONNABORTED"), false);
  assert.equal(isExpectedLogoutTransportAbort?.("logout", true, "EPIPE"), false);
});

test("requires exactly one successful teleport future, rejecting missing, false, failed, or duplicate results", () => {
  const successfulFuture = "[INFO]: T15_TELEPORT_FUTURE_RESULT uuid=player success=true";
  assert.equal(hasSingleSuccessfulTeleportFuture([successfulFuture]), true);
  assert.equal(hasSingleSuccessfulTeleportFuture([]), false);
  assert.equal(hasSingleSuccessfulTeleportFuture([
    "[INFO]: T15_TELEPORT_FUTURE_RESULT uuid=player success=false",
  ]), false);
  assert.equal(hasSingleSuccessfulTeleportFuture([
    "[SEVERE]: T15_TELEPORT_FUTURE_FAILURE uuid=player",
  ]), false);
  assert.equal(hasSingleSuccessfulTeleportFuture([successfulFuture, successfulFuture]), false);
  assert.equal(hasSingleSuccessfulTeleportFuture(["[INFO]: T15_TELEPORT_FUTURE_RESULT uuid=player"]), false);
});

test("classifies event paths only when their teleport future succeeded", () => {
  const classifyTeleportOutcome = lifecycleAssertions.classifyTeleportOutcome;
  assert.equal(classifyTeleportOutcome?.([false], true), "direct-success");
  assert.equal(classifyTeleportOutcome?.([true, false], true), "deferred-retry");
  assert.equal(classifyTeleportOutcome?.([true, false, false], true), "deferred-retry");
  assert.equal(classifyTeleportOutcome?.([false], false), null);
});

test("rejects a cancelled teleport without a later successful event", () => {
  const classifyTeleportOutcome = lifecycleAssertions.classifyTeleportOutcome;
  assert.equal(classifyTeleportOutcome?.([], true, false), null);
  assert.equal(classifyTeleportOutcome?.([true], true), null);
  assert.equal(classifyTeleportOutcome?.([false, true], true), null);
});

test("accepts eventless Folia teleport only with entity-scheduler confirmation", () => {
  const classifyTeleportOutcome = lifecycleAssertions.classifyTeleportOutcome;
  assert.equal(classifyTeleportOutcome?.([], true, true), "entity-scheduler-future");
  assert.equal(classifyTeleportOutcome?.([], true, false), null);
  assert.equal(classifyTeleportOutcome?.([], false, true), null);
});

test("requires exactly one exact logout reason for the expected player", () => {
  assert.equal(hasExactExpectedServerKick([
    "[INFO]: AcceptanceA lost connection: Disconnected",
    "[INFO]: AcceptanceA lost connection: T15 accepted-write logout scenario",
  ]), true);
  assert.equal(hasExactExpectedServerKick([
    "[INFO]: AcceptanceA lost connection: T15 accepted-write logout scenario (other reason)",
  ]), false);
  assert.equal(hasExactExpectedServerKick([
    "[INFO]: AcceptanceB lost connection: T15 accepted-write logout scenario",
  ]), false);
  assert.equal(hasExactExpectedServerKick([
    "[INFO]: AcceptanceA lost connection: Disconnected",
  ]), false);
  assert.equal(hasExactExpectedServerKick([
    "[INFO]: AcceptanceA lost connection: T15 accepted-write logout scenario",
    "[INFO]: AcceptanceA lost connection: T15 accepted-write logout scenario",
  ]), false);
});

test("waits for both client dimension and server world-change confirmations", () => {
  const isTeleportArrivalConfirmed = lifecycleAssertions.isTeleportArrivalConfirmed;
  assert.equal(isTeleportArrivalConfirmed?.(true, false), false);
  assert.equal(isTeleportArrivalConfirmed?.(false, true), false);
  assert.equal(isTeleportArrivalConfirmed?.(true, true), true);
});

test("closes isolated Redis before hashing its append-only files", () => {
  const runner = readFileSync(new URL("./run-player-scheduler-lifecycle.ps1", import.meta.url), "utf8")
    .replaceAll("\r\n", "\n");
  assert.ok(runner.includes("\n    Stop-IsolatedRedis\n    $redisFiles = @(Get-ChildItem -LiteralPath $redisData"));
});

test("runner requires a successful teleport future, exact player kick, and distinct Redis versions", () => {
  const runner = readFileSync(new URL("./run-player-scheduler-lifecycle.ps1", import.meta.url), "utf8");
  assert.match(runner, /--has-single-successful-teleport-future-json/);
  assert.match(runner, /--has-exact-server-kick-json/);
  assert.match(runner, /runtime = .*redis = \$redisServerVersion/);
  assert.match(runner, /version = \[int\]\$accountVersion/);
});

test("runner passes only focused lifecycle lines to Node instead of the full server log", () => {
  const runner = readFileSync(new URL("./run-player-scheduler-lifecycle.ps1", import.meta.url), "utf8");
  assert.match(runner, /\$futureEvidenceLines = @\(\$serverLines \| Where-Object \{ \$_ -match 'T15_TELEPORT_FUTURE_\(\?:RESULT\|FAILURE\)' \}\)/);
  assert.match(runner, /logLines = @\(\$futureEvidenceLines\)/);
  assert.match(runner, /\$kickEvidenceLines = @\(\$serverLines \| Where-Object \{ \$_ -match 'AcceptanceA lost connection: T15 accepted-write logout scenario' \}\)/);
  assert.match(runner, /logLines = @\(\$kickEvidenceLines\); playerName = 'AcceptanceA'/);
});

test("selects test ports only after the operating system accepts a local bind", () => {
  const runner = readFileSync(new URL("./run-player-scheduler-lifecycle.ps1", import.meta.url), "utf8");
  assert.match(runner, /\$gamePort\s*=\s*Get-AvailableLocalPort\s+25000\s+39000\s+@\(0,\s*10000\)/);
  assert.match(runner, /\$redisPort\s*=\s*Get-AvailableLocalPort\s+50000\s+60000/);
  assert.match(runner, /\[Net\.Sockets\.TcpListener\]::new\(\[Net\.IPAddress\]::Loopback,\s*\$probePort\)/);
});

test("normalizes namespaced Nether dimension identifiers", () => {
  assert.equal(normalizedDimension(" minecraft:the_nether "), "the_nether");
  assert.equal(isNetherDimension("minecraft:the_nether"), true);
  assert.equal(isNetherDimension("nether"), true);
  assert.equal(isNetherDimension("minecraft:overworld"), false);
  assert.equal(isNetherDimension(-1), false);
  assert.equal(isNetherDimension(undefined), false);
});

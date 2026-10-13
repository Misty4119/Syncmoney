import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import * as lifecycleAssertions from "./player-scheduler-lifecycle-assertions.mjs";
import {
  EXPECTED_LOGOUT_REASON,
  chatComponentText,
  hasExactExpectedServerKick,
  hasSingleSuccessfulTeleportFuture,
  hasTwoRegionOwnershipProof,
  isExpectedLogoutKick,
  isNetherDimension,
  normalizedDimension,
  observeCrossOwnerClientPass,
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

test("requires matching owner proofs for two distant players in distinct Folia regions", () => {
  const valid = [
    "T21_REGION_OWNER player=AcceptanceA world=world blockX=0 blockZ=0 chunkX=0 chunkZ=0 selfOwned=true otherPlayer=AcceptanceB otherWorld=world otherBlockX=16384 otherBlockZ=16384 otherChunkX=1024 otherChunkZ=1024 otherOwned=false",
    "T21_REGION_OWNER player=AcceptanceB world=world blockX=16384 blockZ=16384 chunkX=1024 chunkZ=1024 selfOwned=true otherPlayer=AcceptanceA otherWorld=world otherBlockX=0 otherBlockZ=0 otherChunkX=0 otherChunkZ=0 otherOwned=false",
    "T21_CROSS_OWNER_PASS players=AcceptanceA,AcceptanceB",
  ];
  assert.equal(hasTwoRegionOwnershipProof(valid), true);
  const ownerMarkerFilter = /T21_(?:REGION_OWNER(?:\b|_)|CROSS_OWNER_)/;
  assert.equal(valid.filter((line) => ownerMarkerFilter.test(line)).length, 3);
  assert.equal(hasTwoRegionOwnershipProof(valid.map((line) => line.replace("otherOwned=false", "otherOwned=true"))), false);
  assert.equal(hasTwoRegionOwnershipProof(valid.filter((line) => !line.includes("player=AcceptanceB"))), false);
  assert.equal(hasTwoRegionOwnershipProof([...valid, valid[0]]), false);
  assert.equal(hasTwoRegionOwnershipProof([
    valid[0], valid[1], `${valid[2]} T21_CROSS_OWNER_PASS players=AcceptanceA,AcceptanceB`,
  ]), false);
  assert.equal(hasTwoRegionOwnershipProof([
    valid[0], valid[1], `${valid[2]} contradictory=true`,
  ]), false);
  assert.equal(hasTwoRegionOwnershipProof([
    `${valid[0]} T21_REGION_OWNER player=AcceptanceA`, valid[1], valid[2],
  ]), false);
  assert.equal(hasTwoRegionOwnershipProof([
    `${valid[0]} contradictory=true`, valid[1], valid[2],
  ]), false);
  assert.equal(hasTwoRegionOwnershipProof([
    ...valid, "[SEVERE]: T21_CROSS_OWNER_FAIL reason=AcceptanceB-retired-before-result",
  ]), false);
  assert.equal(hasTwoRegionOwnershipProof(valid.map((line) => line.replace("blockX=16384", "blockX=512").replace("otherBlockX=16384", "otherBlockX=512"))), false);
  assert.equal(hasTwoRegionOwnershipProof(valid.map((line) => line.replace("otherChunkX=1024", "otherChunkX=0"))), false);
  assert.equal(hasTwoRegionOwnershipProof(valid.slice(0, 2)), false);
});

test("cross-owner client completion requires the targeted marker from both live clients", () => {
  const passed = new Set();
  assert.equal(observeCrossOwnerClientPass(passed, "AcceptanceA", "T21_CROSS_OWNER_PASS player=AcceptanceA"), false);
  assert.equal(observeCrossOwnerClientPass(passed, "AcceptanceA", "T21_CROSS_OWNER_PASS player=AcceptanceB"), false);
  assert.equal(observeCrossOwnerClientPass(passed, "AcceptanceB", "T21_CROSS_OWNER_PASS player=AcceptanceB"), true);
  assert.equal(observeCrossOwnerClientPass(passed, "AcceptanceB", "T21_CROSS_OWNER_PASS player=AcceptanceB"), true);
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

test("cross-owner gate requires two live clients and both entity-owner checks", () => {
  const runner = readFileSync(new URL("./run-player-scheduler-lifecycle.ps1", import.meta.url), "utf8");
  const client = readFileSync(new URL("./player-scheduler-lifecycle.mjs", import.meta.url), "utf8");
  const probe = readFileSync(new URL("../../src/acceptance/java/noietime/syncmoney/acceptance/AcceptanceProbe.java", import.meta.url), "utf8");
  assert.match(runner, /CrossOwner/);
  assert.match(runner, /\$CrossOwner\.IsPresent/);
  assert.doesNotMatch(runner, /\$crossowner\s*=/i, "do not overwrite the case-insensitive PowerShell switch parameter");
  assert.match(runner, /T21_\(\?:REGION_OWNER\(\?:\\b\|_\)\|CROSS_OWNER_\)/);
  assert.match(runner, /--has-two-region-ownership-proof-json/);
  assert.match(client, /createAcceptanceBot\("AcceptanceB"\)/);
  assert.match(client, /\/smaccept lifecycle cross-owner AcceptanceB/);
  assert.match(probe, /Bukkit\.isOwnedByCurrentRegion\(player\)/);
  assert.match(probe, /Bukkit\.isOwnedByCurrentRegion\(otherLocation\.world\(\)/);
  assert.match(probe, /T21_CROSS_OWNER_PASS players=AcceptanceA,AcceptanceB/);
  assert.match(probe, /sendCrossOwnerClientCompletion\(anchor, "AcceptanceA"\)/);
  assert.match(probe, /sendCrossOwnerClientCompletion\(other, "AcceptanceB"\)/);
  assert.match(client, /observeCrossOwnerPass\(bot, text\)/);
  assert.match(client, /observeCrossOwnerPass\(otherBot, text\)/);
});

test("cross-owner runner rejects non-Folia endpoints before creating a run or starting services", () => {
  const runner = readFileSync(new URL("./run-player-scheduler-lifecycle.ps1", import.meta.url), "utf8")
    .replaceAll("\r\n", "\n");
  const guard = runner.indexOf("$CrossOwner.IsPresent -and $pin.software -ne 'folia'");
  assert.ok(guard > runner.indexOf("$pin = $pins[$Endpoint]"));
  assert.ok(guard < runner.indexOf("$root = [IO.Path]::GetFullPath"));
  assert.ok(guard < runner.indexOf("New-Item -ItemType Directory -Path $runRoot"));
  assert.match(runner, /-CrossOwner is only supported for Folia endpoints\./);
});

test("guard runtime proof requires the entity owner, exact quota outcome, player notice, and persisted balance", () => {
  const hasGuardRuntimeProof = lifecycleAssertions.hasGuardRuntimeProof;
  assert.equal(typeof hasGuardRuntimeProof, "function");

  const evidence = {
    serverLines: [
      "[INFO]: T21_GUARD_OWNER player=AcceptanceGuard uuid=35c93f74-c540-3b38-8093-b5cb3c2263be selfOwned=true scheduler=entity",
      "[INFO]: T21_GUARD_TRANSACTIONS player=AcceptanceGuard uuid=35c93f74-c540-3b38-8093-b5cb3c2263be accepted=5 rejected=1 outcomes=SSSSSF before=0.00 after=5.00",
      "[WARNING]: Deposit rejected by PlayerTransactionGuard: Transaction too fast, please wait for 35c93f74-c540-3b38-8093-b5cb3c2263be",
    ],
    clientLines: ["BOT_GUARD_RATE_LIMIT_NOTICE_PASS uuid=35c93f74-c540-3b38-8093-b5cb3c2263be message=Transaction too fast, please wait a moment"],
    redisBalance: "5.00",
    redisVersion: "5",
  };
  assert.equal(hasGuardRuntimeProof(evidence), true);
  assert.equal(hasGuardRuntimeProof({ ...evidence, redisBalance: "6.00" }), false);
  assert.equal(hasGuardRuntimeProof({ ...evidence, redisVersion: "6" }), false);
  assert.equal(hasGuardRuntimeProof({
    ...evidence,
    serverLines: evidence.serverLines.map((line) => line.replace("selfOwned=true", "selfOwned=false")),
  }), false);
  assert.equal(hasGuardRuntimeProof({ ...evidence, serverLines: [...evidence.serverLines, evidence.serverLines[0]] }), false);
  assert.equal(hasGuardRuntimeProof({ ...evidence, serverLines: [...evidence.serverLines, "T21_GUARD_FAIL reason=owner"] }), false);
  assert.equal(hasGuardRuntimeProof({ ...evidence, clientLines: [] }), false);
  assert.equal(hasGuardRuntimeProof({
    ...evidence,
    serverLines: evidence.serverLines.filter((line) => !line.includes("PlayerTransactionGuard")),
  }), false);
  assert.equal(hasGuardRuntimeProof({
    ...evidence,
    serverLines: evidence.serverLines.map((line) => line.replace("accepted=5 rejected=1 outcomes=SSSSSF", "accepted=6 rejected=0 outcomes=SSSSSS")),
  }), false);
  assert.equal(hasGuardRuntimeProof({
    ...evidence,
    serverLines: evidence.serverLines.map((line) => line.replace("outcomes=SSSSSF", "outcomes=SSSFSS")),
  }), false, "the sixth transaction must be the rejected transaction");
  assert.equal(hasGuardRuntimeProof({
    ...evidence,
    clientLines: evidence.clientLines.map((line) => line.replace("35c93f74-c540-3b38-8093-b5cb3c2263be", "00000000-0000-0000-0000-000000000001")),
  }), false, "the client notification UUID must match the server owner UUID");
  assert.equal(hasGuardRuntimeProof({
    ...evidence,
    clientLines: evidence.clientLines.map((line) => line.replace(" uuid=35c93f74-c540-3b38-8093-b5cb3c2263be", "")),
  }), false, "the client notification must contain a UUID");
});

test("guard acceptance is an isolated Canvas-only profile and records runtime evidence", () => {
  const runner = readFileSync(new URL("./run-player-scheduler-lifecycle.ps1", import.meta.url), "utf8");
  const client = readFileSync(new URL("./player-scheduler-lifecycle.mjs", import.meta.url), "utf8");
  const probe = readFileSync(new URL("../../src/acceptance/java/noietime/syncmoney/acceptance/AcceptanceProbe.java", import.meta.url), "utf8");
  const prepare = readFileSync(new URL("./prepare.mjs", import.meta.url), "utf8");

  assert.match(runner, /\[switch\]\$Guard/);
  assert.match(runner, /\$Guard\.IsPresent -and \$pin\.software -ne 'canvas'/);
  assert.match(runner, /\$guardClient = Invoke-Client 'guard'/);
  assert.match(runner, /--has-guard-runtime-proof-json/);
  assert.match(runner, /guard-on/);
  assert.match(prepare, /player-protection/);
  assert.match(client, /AcceptanceGuard/);
  assert.match(client, /BOT_GUARD_RATE_LIMIT_NOTICE_PASS/);
  assert.match(client, /uuid=\$\{bot\.player\?\.uuid \?\? bot\.entity\?\.uuid\}/);
  assert.match(probe, /Bukkit\.isOwnedByCurrentRegion\(player\)/);
  assert.match(probe, /T21_GUARD_TRANSACTIONS player=AcceptanceGuard uuid=/);
  assert.match(probe, /outcomes=/);
  assert.match(probe, /accepted=" \+ accepted \+ " rejected=" \+ rejected/);
  assert.match(runner, /candidateSha -cne '5fdc79aae7ae715f6fcdb3cddbb858ba001855420755c46458102979ca495dbd'/);
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

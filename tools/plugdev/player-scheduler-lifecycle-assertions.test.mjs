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

  for (const invalidMessage of [
    "notice T21_CROSS_OWNER_PASS player=AcceptanceA",
    "T21_CROSS_OWNER_PASS player=AcceptanceA extra",
    "T21_CROSS_OWNER_PASS player=AcceptanceAA",
  ]) {
    const alreadyPassed = new Set(["AcceptanceB"]);
    assert.equal(observeCrossOwnerClientPass(alreadyPassed, "AcceptanceA", invalidMessage), false);
    assert.deepEqual([...alreadyPassed], ["AcceptanceB"]);
  }
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
  assert.equal(hasGuardRuntimeProof({
    ...evidence,
    serverLines: evidence.serverLines.map((line) => line.includes("Deposit rejected by PlayerTransactionGuard:")
      ? `${line} ${line}`
      : line),
  }), false, "a single line must not hide duplicate rejection warnings");
  assert.equal(hasGuardRuntimeProof({
    ...evidence,
    serverLines: evidence.serverLines.map((line) => line.includes("Deposit rejected by PlayerTransactionGuard:")
      ? `${line} ${line.replace("Deposit rejected by PlayerTransactionGuard:", "deposit rejected by playertransactionguard:")}`
      : line),
  }), false, "warning marker duplicate detection must ignore casing");
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

test("guard acceptance admits the Folia ownership case while keeping breaker combinations Canvas-only", () => {
  const runner = readFileSync(new URL("./run-player-scheduler-lifecycle.ps1", import.meta.url), "utf8");
  const client = readFileSync(new URL("./player-scheduler-lifecycle.mjs", import.meta.url), "utf8");
  const probe = readFileSync(new URL("../../src/acceptance/java/noietime/syncmoney/acceptance/AcceptanceProbe.java", import.meta.url), "utf8");
  const prepare = readFileSync(new URL("./prepare.mjs", import.meta.url), "utf8");

  assert.match(runner, /\[switch\]\$Guard/);
  assert.match(runner, /\[switch\]\$GuardWithCircuitBreaker/);
  assert.match(runner, /GuardWithCircuitBreaker\.IsPresent -and -not \$Guard\.IsPresent/);
  assert.match(runner, /\$Guard\.IsPresent -and \$pin\.software -notin @\('canvas', 'folia'\)/);
  assert.match(runner, /\(\$GuardWithCircuitBreaker\.IsPresent -or \$BreakerOnly\.IsPresent\) -and \$pin\.software -ne 'canvas'/);
  assert.match(runner, /\$guardClient = Invoke-Client 'guard-pair'/);
  assert.match(runner, /Invoke-Client 'guard-pair-breaker'/);
  assert.match(runner, /--has-guard-pair-runtime-proof-json/);
  assert.match(runner, /--has-guard-pair-circuit-breaker-runtime-proof-json/);
  assert.match(runner, /guard-on/);
  assert.match(runner, /guard-breaker-on/);
  assert.match(prepare, /player-protection/);
  assert.match(client, /AcceptanceGuardA/);
  assert.match(client, /AcceptanceGuardB/);
  assert.match(client, /guard-pair/);
  assert.match(client, /guard-pair-breaker/);
  assert.match(client, /bot\.chat\(`\/smaccept lifecycle \$\{scenario\} AcceptanceGuardB`\)/);
  assert.match(client, /BOT_GUARD_RATE_LIMIT_NOTICE_PASS/);
  assert.match(client, /player=\$\{player\} uuid=\$\{uuid\}/);
  assert.match(probe, /Bukkit\.isOwnedByCurrentRegion\(player\)/);
  assert.match(probe, /T21_GUARD_BREAKER_STATE circuitBreaker=enabled circuitBreakerLocked=false playerProtection=enabled/);
  assert.match(probe, /guard-pair-breaker/);
  assert.match(probe, /T21_GUARD_TRANSACTIONS player=" \+ player\.getName\(\) \+ " uuid=/);
  assert.match(probe, /outcomes=/);
  assert.match(probe, /accepted=" \+ accepted \+ " rejected=" \+ rejected/);
  assert.match(runner, /candidateSha -cne '57b717e7704e93d6086341c08d4a34e53a74efc0e9c44877a3e856e0db90c662'/);
});

test("Canvas guard-pair proof requires two entity owners, independent quotas, notices, and persisted balances", () => {
  const hasGuardPairRuntimeProof = lifecycleAssertions.hasGuardPairRuntimeProof;
  assert.equal(typeof hasGuardPairRuntimeProof, "function");

  const uuidA = "35c93f74-c540-3b38-8093-b5cb3c2263be";
  const uuidB = "aa8f2cb5-988d-3b71-84b0-bd7b8bf5b474";
  const evidence = {
    serverLines: [
      `T21_GUARD_OWNER player=AcceptanceGuardA uuid=${uuidA} selfOwned=true otherOwned=false scheduler=entity`,
      `Deposit rejected by PlayerTransactionGuard: Transaction too fast, please wait for ${uuidA}`,
      `T21_GUARD_TRANSACTIONS player=AcceptanceGuardA uuid=${uuidA} accepted=5 rejected=1 outcomes=SSSSSF before=0.00 after=5.00`,
      `T21_GUARD_NOTICE_ACK stage=1 player=AcceptanceGuardA uuid=${uuidA} recipient=AcceptanceGuardA`,
      `T21_GUARD_OWNER player=AcceptanceGuardB uuid=${uuidB} selfOwned=true otherOwned=false scheduler=entity`,
      `Deposit rejected by PlayerTransactionGuard: Transaction too fast, please wait for ${uuidB}`,
      `T21_GUARD_TRANSACTIONS player=AcceptanceGuardB uuid=${uuidB} accepted=5 rejected=1 outcomes=SSSSSF before=0.00 after=5.00`,
      `T21_GUARD_NOTICE_ACK stage=2 player=AcceptanceGuardB uuid=${uuidB} recipient=AcceptanceGuardB`,
    ],
    clientLines: [
      `BOT_GUARD_RATE_LIMIT_NOTICE_PASS stage=1 player=AcceptanceGuardA uuid=${uuidA} message=Transaction too fast, please wait a moment`,
      `BOT_GUARD_RATE_LIMIT_NOTICE_PASS stage=2 player=AcceptanceGuardB uuid=${uuidB} message=Transaction too fast, please wait a moment`,
    ],
    participants: [
      { player: "AcceptanceGuardA", uuid: uuidA, redisBalance: "5.00", redisVersion: "5" },
      { player: "AcceptanceGuardB", uuid: uuidB, redisBalance: "5.00", redisVersion: "5" },
    ],
  };

  assert.equal(hasGuardPairRuntimeProof(evidence), true);
  assert.equal(hasGuardPairRuntimeProof({
    ...evidence,
    serverLines: evidence.serverLines.map((line) => line.includes("Deposit rejected by PlayerTransactionGuard:")
      ? `${line} ${line}`
      : line),
  }), false, "a single line must not hide duplicate rejection warnings for one owner");
  assert.equal(hasGuardPairRuntimeProof({
    ...evidence,
    serverLines: evidence.serverLines.map((line) => line.includes("Deposit rejected by PlayerTransactionGuard:")
      ? `${line} ${line.replace("Deposit rejected by PlayerTransactionGuard:", "deposit rejected by playertransactionguard:")}`
      : line),
  }), false, "warning marker duplicate detection must ignore casing for both owners");
  assert.equal(hasGuardPairRuntimeProof({
    ...evidence,
    serverLines: [...evidence.serverLines, evidence.serverLines[2]],
  }), false, "duplicate guard rejection warnings must not be collapsed into one participant UUID");
  const malformedUuid = "------------------------------------";
  assert.equal(hasGuardPairRuntimeProof({
    ...evidence,
    serverLines: evidence.serverLines.map((line) => line.replaceAll(uuidA, malformedUuid)),
    clientLines: evidence.clientLines.map((line) => line.replaceAll(uuidA, malformedUuid)),
    participants: evidence.participants.map((participant) => participant.player === "AcceptanceGuardA"
      ? { ...participant, uuid: malformedUuid }
      : participant),
  }), false, "every participant proof must use a canonical UUID shape");
  assert.equal(hasGuardPairRuntimeProof({ ...evidence, participants: evidence.participants.map((p) => ({ ...p, redisBalance: "6.00" })) }), false);
  assert.equal(hasGuardPairRuntimeProof({ ...evidence, participants: evidence.participants.map((p) => ({ ...p, redisVersion: "6" })) }), false);
  assert.equal(hasGuardPairRuntimeProof({
    ...evidence,
    participants: [evidence.participants[0], { ...evidence.participants[1], uuid: uuidA }],
  }), false, "each entity owner must be a distinct account");
  assert.equal(hasGuardPairRuntimeProof({
    ...evidence,
    serverLines: evidence.serverLines.map((line) => line.replace("otherOwned=false", "otherOwned=true")),
  }), false, "the peer player must be outside this callback's entity owner");
  assert.equal(hasGuardPairRuntimeProof({
    ...evidence,
    serverLines: evidence.serverLines.map((line) => line.replace("outcomes=SSSSSF", "outcomes=SSSFSS")),
  }), false, "the sixth transaction must be the rejected transaction for both accounts");
  assert.equal(hasGuardPairRuntimeProof({ ...evidence, clientLines: evidence.clientLines.slice(0, 1) }), false);
  assert.equal(hasGuardPairRuntimeProof({
    ...evidence,
    clientLines: evidence.clientLines.map((line) => line.replace(uuidB, uuidA)),
  }), false, "each player notification must match that player's owner UUID");
  assert.equal(hasGuardPairRuntimeProof({
    ...evidence,
    clientLines: [
      evidence.clientLines[0].replace(`player=AcceptanceGuardA uuid=${uuidA}`, `player=AcceptanceGuardB uuid=${uuidB}`),
      evidence.clientLines[1],
    ],
  }), false, "the first rejection notice must be observed by its owner before the peer is exercised");
  assert.equal(hasGuardPairRuntimeProof({
    ...evidence,
    serverLines: evidence.serverLines.map((line) => line.includes("recipient=AcceptanceGuardA")
      ? line.replace("recipient=AcceptanceGuardA", "recipient=AcceptanceGuardB")
      : line),
  }), false, "server acknowledgement must be issued by the owner that received the notice");
  assert.equal(hasGuardPairRuntimeProof({
    ...evidence,
    serverLines: [
      ...evidence.serverLines.filter((line) => !line.includes("T21_GUARD_NOTICE_ACK stage=1")),
      evidence.serverLines.find((line) => line.includes("T21_GUARD_NOTICE_ACK stage=1")),
    ],
  }), false, "the peer rejection cannot precede the first owner's notice acknowledgement");
  assert.equal(hasGuardPairRuntimeProof({ ...evidence, serverLines: [...evidence.serverLines, "T21_GUARD_FAIL phase=transactions"] }), false);
  assert.equal(hasGuardPairRuntimeProof({ ...evidence, clientLines: [...evidence.clientLines, evidence.clientLines[0]] }), false);
});

test("Canvas breaker/guard proof requires both active managers and an unlocked global breaker", () => {
  const hasProof = lifecycleAssertions.hasGuardPairCircuitBreakerRuntimeProof;
  assert.equal(typeof hasProof, "function");

  const uuidA = "35c93f74-c540-3b38-8093-b5cb3c2263be";
  const uuidB = "aa8f2cb5-988d-3b71-84b0-bd7b8bf5b474";
  const evidence = {
    serverLines: [
      "T21_GUARD_BREAKER_STATE circuitBreaker=enabled circuitBreakerLocked=false playerProtection=enabled player=AcceptanceGuardA",
      `T21_GUARD_OWNER player=AcceptanceGuardA uuid=${uuidA} selfOwned=true otherOwned=false scheduler=entity`,
      `Deposit rejected by PlayerTransactionGuard: Transaction too fast, please wait for ${uuidA}`,
      `T21_GUARD_TRANSACTIONS player=AcceptanceGuardA uuid=${uuidA} accepted=5 rejected=1 outcomes=SSSSSF before=0.00 after=5.00`,
      `T21_GUARD_NOTICE_ACK stage=1 player=AcceptanceGuardA uuid=${uuidA} recipient=AcceptanceGuardA`,
      "T21_GUARD_BREAKER_STATE circuitBreaker=enabled circuitBreakerLocked=false playerProtection=enabled player=AcceptanceGuardB",
      `T21_GUARD_OWNER player=AcceptanceGuardB uuid=${uuidB} selfOwned=true otherOwned=false scheduler=entity`,
      `Deposit rejected by PlayerTransactionGuard: Transaction too fast, please wait for ${uuidB}`,
      `T21_GUARD_TRANSACTIONS player=AcceptanceGuardB uuid=${uuidB} accepted=5 rejected=1 outcomes=SSSSSF before=0.00 after=5.00`,
      `T21_GUARD_NOTICE_ACK stage=2 player=AcceptanceGuardB uuid=${uuidB} recipient=AcceptanceGuardB`,
    ],
    clientLines: [
      `BOT_GUARD_RATE_LIMIT_NOTICE_PASS stage=1 player=AcceptanceGuardA uuid=${uuidA} message=Transaction too fast, please wait a moment`,
      `BOT_GUARD_RATE_LIMIT_NOTICE_PASS stage=2 player=AcceptanceGuardB uuid=${uuidB} message=Transaction too fast, please wait a moment`,
    ],
    participants: [
      { player: "AcceptanceGuardA", uuid: uuidA, redisBalance: "5.00", redisVersion: "5" },
      { player: "AcceptanceGuardB", uuid: uuidB, redisBalance: "5.00", redisVersion: "5" },
    ],
  };

  assert.equal(hasProof(evidence), true);
  assert.equal(hasProof({ ...evidence, serverLines: evidence.serverLines.slice(1) }), false);
  assert.equal(hasProof({
    ...evidence,
    serverLines: evidence.serverLines.map((line) => line.includes("GUARD_BREAKER_STATE")
      ? line.replace("circuitBreaker=enabled", "circuitBreaker=disabled")
      : line),
  }), false);
  assert.equal(hasProof({
    ...evidence,
    serverLines: evidence.serverLines.map((line) => line.includes("player=AcceptanceGuardB") && line.includes("GUARD_BREAKER_STATE")
      ? line.replace("circuitBreakerLocked=false", "circuitBreakerLocked=true")
      : line),
  }), false);
  assert.equal(hasProof({ ...evidence, serverLines: [...evidence.serverLines, evidence.serverLines[0]] }), false);
  assert.equal(hasProof({
    ...evidence,
    serverLines: evidence.serverLines.filter((line) => !line.includes("player=AcceptanceGuardB") || !line.includes("GUARD_BREAKER_STATE")),
  }), false, "each entity owner must observe the active, unlocked global breaker");
});

test("paired owner guard runner records platform-specific ownership and residual scope", () => {
  const runner = readFileSync(new URL("./run-player-scheduler-lifecycle.ps1", import.meta.url), "utf8").replaceAll("\r\n", "\n");
  const client = readFileSync(new URL("./player-scheduler-lifecycle.mjs", import.meta.url), "utf8");
  const probe = readFileSync(new URL("../../src/acceptance/java/noietime/syncmoney/acceptance/AcceptanceProbe.java", import.meta.url), "utf8");

  assert.match(runner, /Wait-ServerLog 'T21_GUARD_TRANSACTIONS player=AcceptanceGuardA .*outcomes=SSSSSF/);
  assert.match(runner, /Wait-ServerLog 'T21_GUARD_TRANSACTIONS player=AcceptanceGuardB .*outcomes=SSSSSF/);
  assert.match(runner, /--has-guard-pair-runtime-proof-json/);
  assert.match(runner, /uuid=\(\[0-9a-f\]\{8\}-\[0-9a-f\]\{4\}-\[0-9a-f\]\{4\}-\[0-9a-f\]\{4\}-\[0-9a-f\]\{12\}\)/i);
  assert.match(runner, /guardParticipants/);
  assert.match(runner, /guard-pair/);
  assert.match(client, /AcceptanceGuardA/);
  assert.match(client, /AcceptanceGuardB/);
  assert.match(client, /BOT_GUARD_RATE_LIMIT_NOTICE_PASS stage=\$\{stage\} player=\$\{player\}/);
  assert.match(probe, /T21_GUARD_OWNER player=" \+ player\.getName\(\)/);
  assert.match(probe, /require\(!otherOwned, "guard transactions run outside the peer entity owner"\)/);
  assert.match(probe, /T21_GUARD_NOTICE_ACK stage=/);
  assert.match(probe, /guard-pair-notice-ack/);
  const acknowledgementMethod = probe.slice(
    probe.indexOf("private void acknowledgeGuardPairNotice"),
    probe.indexOf("private void failGuardPairRun"),
  );
  assert.doesNotMatch(acknowledgementMethod, /pairRun\.(?:first|second)\.get(?:Name|UniqueId)\(\)/,
    "a wrong-owner acknowledgement must not read another player's entity");
  assert.match(probe, /pairRun\.rememberIdentity\(expectedName, uuid\)/,
    "participant identity is captured on that player's entity owner before acknowledgement");
  const notEstablished = runner.slice(
    runner.indexOf("$notEstablished = @("),
    runner.indexOf("$script:result = [ordered]@{")
  );
  assert.match(runner, /if \(-not \$crossOwnerEnabled\) \{ \$notEstablished \+= 'Folia two-region cross-owner behavior' \}/);
  assert.doesNotMatch(runner, /\$twoRegionOwnershipProved/,
    "the paired player-guard profile must not be treated as the separate Folia cross-owner scenario");
  assert.match(notEstablished, /if \(\$Guard\.IsPresent\) \{\s*if \(\$pin\.software -eq 'folia'\) \{\s*\$notEstablished \+= 'full Folia player-guard lifecycle matrix outside the tested two-owner quota scenario'\s*\} else \{\s*\$notEstablished \+= 'full Canvas owner\/guard lifecycle matrix outside the tested two-owner quota scenario'\s*\}/);
  assert.match(runner, /"T21 \$platformName two-owner entity guard and player lifecycle scheduler subcase"/);
  assert.match(runner, /Wait-ServerLog 'T21_GUARD_PAIR_PASS players=AcceptanceGuardA,AcceptanceGuardB' 30 'paired player-owner guard outcome'/);
  assert.doesNotMatch(runner, /'paired Canvas owner guard outcome'/);
  assert.match(probe, /runGuardTransactions\(\s*plugin, transactionGuard, economy, player, peer,/);
  const pairMethod = probe.slice(
    probe.indexOf("private void runGuardPairScenario"),
    probe.indexOf("private void runGlobalBreakerPairScenario"),
  );
  const moveIndex = pairMethod.indexOf("moveAndCaptureRegionLocation");
  assert.ok(moveIndex >= 0);
  assert.ok(pairMethod.indexOf("getPluginManager()") < moveIndex,
    "capture Syncmoney on the global scheduler before player movement can complete inline");
  assert.ok(pairMethod.indexOf("getServicesManager()") < moveIndex,
    "capture Vault on the global scheduler before player movement can complete inline");
  assert.doesNotMatch(pairMethod.slice(pairMethod.indexOf(".whenComplete(")), /getPluginManager\(\)|getServicesManager\(\)/,
    "the entity-scheduler future continuation must not query Bukkit managers");
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

test("Canvas global breaker proof aggregates two owners without player protection", () => {
  const hasProof = lifecycleAssertions.hasGlobalBreakerPairRuntimeProof;
  assert.equal(typeof hasProof, "function");

  const uuidA = "35c93f74-c540-3b38-8093-b5cb3c2263be";
  const uuidB = "aa8f2cb5-988d-3b71-84b0-bd7b8bf5b474";
  const second = "1792174234";
  const evidence = {
    serverLines: [
      `T21_GLOBAL_BREAKER_STATE circuitBreaker=enabled circuitBreakerLocked=false playerProtection=disabled maxTransactionsPerSecond=5 player=AcceptanceGuardA`,
      `T21_GLOBAL_BREAKER_OWNER player=AcceptanceGuardA uuid=${uuidA} selfOwned=true otherOwned=false scheduler=entity`,
      `T21_GLOBAL_BREAKER_TRANSACTIONS player=AcceptanceGuardA uuid=${uuidA} attempted=3 accepted=3 rejected=0 outcomes=SSS windowSeconds=${second},${second},${second} before=0.00 after=3.00`,
      `T21_GLOBAL_BREAKER_ACK player=AcceptanceGuardA uuid=${uuidA} recipient=AcceptanceGuardA`,
      `T21_GLOBAL_BREAKER_STATE circuitBreaker=enabled circuitBreakerLocked=false playerProtection=disabled maxTransactionsPerSecond=5 player=AcceptanceGuardB`,
      `T21_GLOBAL_BREAKER_OWNER player=AcceptanceGuardB uuid=${uuidB} selfOwned=true otherOwned=false scheduler=entity`,
      `T21_GLOBAL_BREAKER_TRANSACTIONS player=AcceptanceGuardB uuid=${uuidB} attempted=3 accepted=2 rejected=1 outcomes=SFS windowSeconds=${second},${second},${second} before=0.00 after=2.00`,
      `T21_GLOBAL_BREAKER_ACK player=AcceptanceGuardB uuid=${uuidB} recipient=AcceptanceGuardB`,
      `Deposit rejected by CircuitBreaker: Transaction frequency too high, please try again later for ${uuidB}`,
      `CircuitBreaker: Transaction rate limit exceeded, blocking transaction to prevent desync (Count: 6, Source: VAULT_DEPOSIT)`,
      `T21_GLOBAL_BREAKER_PAIR_PASS players=AcceptanceGuardA,AcceptanceGuardB attempts=6 accepted=5 rejected=1 windowSecond=${second} playerProtection=disabled circuitBreakerLocked=false`,
    ],
    clientLines: [
      `BOT_GLOBAL_BREAKER_PASS player=AcceptanceGuardA uuid=${uuidA}`,
      `BOT_GLOBAL_BREAKER_PASS player=AcceptanceGuardB uuid=${uuidB}`,
      `BOT_GLOBAL_BREAKER_ACK_PASS player=AcceptanceGuardA uuid=${uuidA} recipient=AcceptanceGuardA`,
      `BOT_GLOBAL_BREAKER_ACK_PASS player=AcceptanceGuardB uuid=${uuidB} recipient=AcceptanceGuardB`,
    ],
    participants: [
      { player: "AcceptanceGuardA", uuid: uuidA, redisBalance: "3.00", redisVersion: "3" },
      { player: "AcceptanceGuardB", uuid: uuidB, redisBalance: "2.00", redisVersion: "2" },
    ],
  };

  assert.equal(hasProof(evidence), true);
  assert.equal(hasProof({
    ...evidence,
    serverLines: evidence.serverLines.map((line) => line.includes("windowSeconds=")
      ? line.replace(`windowSeconds=${second},${second},${second}`, `windowSeconds=${second},${Number(second) + 1},${second}`)
      : line),
  }), false, "all six attempts and the pass marker must use one global rate window");
  assert.equal(hasProof({
    ...evidence,
    serverLines: [...evidence.serverLines, `Deposit rejected by PlayerTransactionGuard: Transaction too fast, please wait for ${uuidA}`],
  }), false, "a player-guard rejection must not be mistaken for the global rate limit");
  assert.equal(hasProof({
    ...evidence,
    participants: evidence.participants.map((participant) => participant.player === "AcceptanceGuardB"
      ? { ...participant, redisVersion: "3" }
      : participant),
  }), false, "each persisted version must equal the successful global-breaker admissions for that account");
  assert.equal(hasProof({
    ...evidence,
    serverLines: evidence.serverLines.map((line) => line.includes("T21_GLOBAL_BREAKER_STATE")
      ? line.replace("playerProtection=disabled", "playerProtection=enabled")
      : line),
  }), false, "the tested profile must have per-player protection disabled");
  assert.equal(hasProof({
    ...evidence,
    clientLines: [evidence.clientLines[0], evidence.clientLines[1].replace(uuidB, uuidA)],
  }), false, "each client proof must match its entity owner's UUID");
  assert.equal(hasProof({
    ...evidence,
    clientLines: evidence.clientLines.filter((line) => !line.includes("ACK_PASS player=AcceptanceGuardB")),
  }), false, "both clients must report receiving their targeted acknowledgement");
  assert.equal(hasProof({
    ...evidence,
    clientLines: [...evidence.clientLines, evidence.clientLines[3]],
  }), false, "duplicate client acknowledgement evidence must not pass");
  assert.equal(hasProof({
    ...evidence,
    clientLines: evidence.clientLines.map((line) => line.includes("ACK_PASS player=AcceptanceGuardB")
      ? line.replace(uuidB, uuidA)
      : line),
  }), false, "each client acknowledgement UUID must match its entity owner");
  assert.equal(hasProof({
    ...evidence,
    clientLines: evidence.clientLines.map((line) => line.includes("ACK_PASS player=AcceptanceGuardB")
      ? line.replace("recipient=AcceptanceGuardB", "recipient=AcceptanceGuardA")
      : line),
  }), false, "each client acknowledgement must name its own recipient");
  assert.equal(hasProof({
    ...evidence,
    serverLines: evidence.serverLines.filter((line) => !line.includes("T21_GLOBAL_BREAKER_ACK player=AcceptanceGuardB")),
  }), false, "both owner-targeted proofs must be acknowledged");
  assert.equal(hasProof({
    ...evidence,
    serverLines: evidence.serverLines.map((line) => line.includes("PAIR_PASS")
      ? line.replace("accepted=5", "accepted=6")
      : line),
  }), false, "the global threshold must admit exactly five and reject exactly one");
  assert.equal(hasProof({
    ...evidence,
    serverLines: [...evidence.serverLines, evidence.serverLines.find((line) => line.includes("rate limit exceeded"))],
  }), false, "duplicate global rate-limit failures must not pass");
});

test("breaker-only Canvas runner wires its dedicated profile, client, and proof", () => {
  const runner = readFileSync(new URL("./run-player-scheduler-lifecycle.ps1", import.meta.url), "utf8").replaceAll("\r\n", "\n");
  const client = readFileSync(new URL("./player-scheduler-lifecycle.mjs", import.meta.url), "utf8");
  const probe = readFileSync(new URL("../../src/acceptance/java/noietime/syncmoney/acceptance/AcceptanceProbe.java", import.meta.url), "utf8");
  const prepare = readFileSync(new URL("./prepare.mjs", import.meta.url), "utf8");

  assert.match(runner, /\[switch\]\$BreakerOnly/);
  assert.match(runner, /breaker-only/);
  assert.match(runner, /--has-global-breaker-pair-runtime-proof-json/);
  assert.match(runner, /BOT_GLOBAL_BREAKER_\(\?:PASS\|ACK_PASS\|FAIL\)/);
  assert.match(runner, /clientAcknowledgementLines = \$breakerClientAcknowledgementLines/);
  assert.match(runner, /"breaker-only": \{ circuitBreaker: true, playerProtection: false, maxTransactions: 5 \}/);
  assert.match(runner, /maxTransactionsPerSecond = if \(\$BreakerOnly\.IsPresent\) \{ 5 \}/);
  assert.match(runner, /\$expectedMarker = "BREAKER_GUARD_PROFILE_PASS profile=\$profile mode=local_redis circuit-breaker=\$circuitBreakerState player-protection=\$playerProtectionState optional-modules=off"/);
  assert.match(client, /breaker-pair/);
  assert.match(client, /BOT_GLOBAL_BREAKER_PASS/);
  assert.match(probe, /T21_GLOBAL_BREAKER_PAIR_PASS/);
  assert.match(probe, /pairRun\.acknowledgementsFinished\.compareAndSet\(false, true\)/,
    "concurrent owner acknowledgements may emit only one aggregate pass marker");
  assert.match(probe, /breaker-pair-ack/);
  assert.match(prepare, /breaker-only/);
});

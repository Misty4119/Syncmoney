export const EXPECTED_LOGOUT_REASON = "T15 accepted-write logout scenario";

export function chatComponentText(value) {
  if (typeof value === "string") return value;
  if (Array.isArray(value)) return value.map(chatComponentText).join("");
  if (value == null || typeof value !== "object") return "";
  if (["compound", "list", "string"].includes(value.type) && "value" in value) {
    return chatComponentText(value.value);
  }
  const ownText = value.text === undefined ? "" : chatComponentText(value.text);
  const extraText = value.extra === undefined ? "" : chatComponentText(value.extra);
  return ownText + extraText;
}

export function isExpectedLogoutKick(reason) {
  return chatComponentText(reason).trim() === EXPECTED_LOGOUT_REASON;
}

export function isExpectedLogoutTransportAbort(scenario, commandSent, errorCode) {
  return scenario === "logout" && commandSent === true && errorCode === "ECONNABORTED";
}

export function hasSingleSuccessfulTeleportFuture(logLines) {
  if (!Array.isArray(logLines)) return false;

  let resultCount = 0;
  for (const line of logLines) {
    const text = String(line ?? "");
    if (text.includes("T15_TELEPORT_FUTURE_FAILURE")) return false;
    if (!text.includes("T15_TELEPORT_FUTURE_RESULT")) continue;

    resultCount += 1;
    const result = text.match(/\bT15_TELEPORT_FUTURE_RESULT\b.*\bsuccess=(true|false)(?:\s|$)/);
    if (!result || result[1] !== "true") return false;
  }

  return resultCount === 1;
}

export function hasExactExpectedServerKick(logLines, expectedPlayer = "AcceptanceA") {
  if (!Array.isArray(logLines) || typeof expectedPlayer !== "string" || expectedPlayer.length === 0) return false;
  const escapedPlayer = expectedPlayer.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const disconnect = new RegExp(`(?:^|\\s)${escapedPlayer} lost connection: (.*)$`);
  const relevantReasons = [];

  for (const line of logLines) {
    const match = String(line ?? "").match(disconnect);
    if (match && match[1].startsWith(EXPECTED_LOGOUT_REASON)) relevantReasons.push(match[1]);
  }

  return relevantReasons.length === 1 && relevantReasons[0] === EXPECTED_LOGOUT_REASON;
}

export function classifyTeleportOutcome(
  cancelledStates,
  teleportFutureSucceeded = false,
  entitySchedulerFutureConfirmed = false,
) {
  if (teleportFutureSucceeded !== true) return null;
  if (!Array.isArray(cancelledStates)) return null;
  if (cancelledStates.length === 0) {
    return entitySchedulerFutureConfirmed === true ? "entity-scheduler-future" : null;
  }
  if (!cancelledStates.every((state) => typeof state === "boolean")) return null;
  if (!cancelledStates.includes(false)) return null;

  for (let index = 0; index < cancelledStates.length; index += 1) {
    if (cancelledStates[index] && !cancelledStates.slice(index + 1).includes(false)) return null;
  }
  return cancelledStates.includes(true) ? "deferred-retry" : "direct-success";
}

export function isTeleportArrivalConfirmed(clientObservedNether, serverReportedArrival) {
  return clientObservedNether === true && serverReportedArrival === true;
}

export function hasTwoRegionOwnershipProof(logLines) {
  if (!Array.isArray(logLines)) return false;

  const observations = [];
  let passMarkers = 0;
  for (const rawLine of logLines) {
    const line = String(rawLine ?? "");
    if (line.includes("T21_CROSS_OWNER_FAIL")) return false;
    if (line.includes("T21_CROSS_OWNER_PASS")) {
      if ((line.match(/T21_CROSS_OWNER_PASS/g) ?? []).length !== 1) return false;
      passMarkers += 1;
      if (!/\bT21_CROSS_OWNER_PASS\s+players=AcceptanceA,AcceptanceB(?:\s+distanceBlocks=\d+)?\s*$/.test(line)) return false;
    }
    if (!line.includes("T21_REGION_OWNER")) continue;

    if ((line.match(/T21_REGION_OWNER/g) ?? []).length !== 1) return false;
    const match = line.match(/\bT21_REGION_OWNER\s+player=(AcceptanceA|AcceptanceB)\s+world=(\S+)\s+blockX=(-?\d+)\s+blockZ=(-?\d+)\s+chunkX=(-?\d+)\s+chunkZ=(-?\d+)\s+selfOwned=(true|false)\s+otherPlayer=(AcceptanceA|AcceptanceB)\s+otherWorld=(\S+)\s+otherBlockX=(-?\d+)\s+otherBlockZ=(-?\d+)\s+otherChunkX=(-?\d+)\s+otherChunkZ=(-?\d+)\s+otherOwned=(true|false)\s*$/);
    if (!match) return false;
    observations.push({
      player: match[1], world: match[2], blockX: Number(match[3]), blockZ: Number(match[4]),
      chunkX: Number(match[5]), chunkZ: Number(match[6]), selfOwned: match[7] === "true",
      otherPlayer: match[8], otherWorld: match[9], otherBlockX: Number(match[10]),
      otherBlockZ: Number(match[11]), otherChunkX: Number(match[12]),
      otherChunkZ: Number(match[13]), otherOwned: match[14] === "true",
    });
  }

  if (passMarkers !== 1 || observations.length !== 2) return false;
  const byPlayer = new Map(observations.map((observation) => [observation.player, observation]));
  if (byPlayer.size !== 2 || !byPlayer.has("AcceptanceA") || !byPlayer.has("AcceptanceB")) return false;

  const a = byPlayer.get("AcceptanceA");
  const b = byPlayer.get("AcceptanceB");
  const matchesCoordinates = (observation, other) =>
    observation.otherPlayer === other.player &&
    observation.otherWorld === other.world &&
    observation.otherBlockX === other.blockX &&
    observation.otherBlockZ === other.blockZ &&
    observation.otherChunkX === other.chunkX &&
    observation.otherChunkZ === other.chunkZ;
  if (a.world !== b.world || a.world === "unknown" || a.otherWorld === "unknown") return false;
  if (a.chunkX !== Math.floor(a.blockX / 16) || a.chunkZ !== Math.floor(a.blockZ / 16) ||
      b.chunkX !== Math.floor(b.blockX / 16) || b.chunkZ !== Math.floor(b.blockZ / 16)) return false;
  if (!a.selfOwned || !b.selfOwned || a.otherOwned || b.otherOwned) return false;
  if (!matchesCoordinates(a, b) || !matchesCoordinates(b, a)) return false;
  const distance = Math.hypot(a.blockX - b.blockX, a.blockZ - b.blockZ);
  return Number.isFinite(distance) && distance >= 4096 && (a.chunkX !== b.chunkX || a.chunkZ !== b.chunkZ);
}

export function observeCrossOwnerClientPass(passedPlayers, playerName, messageText) {
  if (!(passedPlayers instanceof Set) || !["AcceptanceA", "AcceptanceB"].includes(playerName)) return false;
  if (typeof messageText !== "string" || !messageText.includes(`T21_CROSS_OWNER_PASS player=${playerName}`)) return false;
  passedPlayers.add(playerName);
  return passedPlayers.has("AcceptanceA") && passedPlayers.has("AcceptanceB");
}

export function hasGuardRuntimeProof(evidence) {
  if (!evidence || !Array.isArray(evidence.serverLines) || !Array.isArray(evidence.clientLines)) return false;
  if (evidence.redisBalance !== "5.00" || evidence.redisVersion !== "5") return false;

  let ownerMarkers = 0;
  let transactionMarkers = 0;
  let guardRejectionWarnings = 0;
  let ownerUuid = null;
  let transactionUuid = null;
  let warningUuid = null;
  let transactionOutcomes = null;
  for (const rawLine of evidence.serverLines) {
    const line = String(rawLine ?? "");
    if (line.includes("T21_GUARD_FAIL")) return false;
    if (line.includes("Deposit rejected by PlayerTransactionGuard:")) {
      if ((line.match(/Deposit rejected by PlayerTransactionGuard:/gi) ?? []).length !== 1) return false;
      guardRejectionWarnings += 1;
      const warning = line.match(/Deposit rejected by PlayerTransactionGuard: Transaction too fast, please wait for ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\s*$/i);
      if (!warning) return false;
      warningUuid = warning[1].toLowerCase();
    }
    if (line.includes("T21_GUARD_OWNER")) {
      ownerMarkers += 1;
      const owner = line.match(/\bT21_GUARD_OWNER player=AcceptanceGuard uuid=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) selfOwned=true scheduler=entity\s*$/i);
      if ((line.match(/T21_GUARD_OWNER/g) ?? []).length !== 1 || !owner) return false;
      ownerUuid = owner[1].toLowerCase();
    }
    if (line.includes("T21_GUARD_TRANSACTIONS")) {
      transactionMarkers += 1;
      const transaction = line.match(/\bT21_GUARD_TRANSACTIONS player=AcceptanceGuard uuid=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) accepted=5 rejected=1 outcomes=([SF]{6}) before=0\.00 after=5\.00\s*$/i);
      if ((line.match(/T21_GUARD_TRANSACTIONS/g) ?? []).length !== 1 || !transaction) return false;
      transactionUuid = transaction[1].toLowerCase();
      transactionOutcomes = transaction[2];
    }
  }
  if (ownerMarkers !== 1 || transactionMarkers !== 1 || guardRejectionWarnings !== 1 ||
      !ownerUuid || ownerUuid !== transactionUuid || ownerUuid !== warningUuid || transactionOutcomes !== "SSSSSF") return false;

  let noticeMarkers = 0;
  let noticeUuid = null;
  for (const rawLine of evidence.clientLines) {
    const line = String(rawLine ?? "");
    if (line.includes("BOT_GUARD_FAIL")) return false;
    if (line.includes("BOT_GUARD_RATE_LIMIT_NOTICE_PASS")) {
      noticeMarkers += 1;
      const notice = line.match(/^\s*BOT_GUARD_RATE_LIMIT_NOTICE_PASS\s+uuid=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\s+message=.*Transaction too fast, please wait a moment.*\s*$/i);
      if ((line.match(/BOT_GUARD_RATE_LIMIT_NOTICE_PASS/g) ?? []).length !== 1 || !notice) return false;
      noticeUuid = notice[1].toLowerCase();
    }
  }
  return noticeMarkers === 1 && noticeUuid === ownerUuid;
}

export function hasGuardPairRuntimeProof(evidence) {
  const players = ["AcceptanceGuardA", "AcceptanceGuardB"];
  if (!evidence || !Array.isArray(evidence.serverLines) || !Array.isArray(evidence.clientLines) ||
      !Array.isArray(evidence.participants) || evidence.participants.length !== players.length) return false;

  const participantByName = new Map();
  for (const participant of evidence.participants) {
    if (!participant || !players.includes(participant.player) || participantByName.has(participant.player) ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(participant.uuid ?? "") ||
        participant.redisBalance !== "5.00" || participant.redisVersion !== "5") return false;
    participantByName.set(participant.player, { ...participant, uuid: participant.uuid.toLowerCase() });
  }
  if (participantByName.size !== players.length ||
      participantByName.get(players[0]).uuid === participantByName.get(players[1]).uuid) return false;

  const ownerByName = new Map();
  const transactionsByName = new Map();
  const warningUuids = new Set();
  const orderedGuardEvents = [];
  const noticeAcks = new Map();
  for (const rawLine of evidence.serverLines) {
    const line = String(rawLine ?? "");
    if (line.includes("T21_GUARD_FAIL")) return false;
    if (line.includes("Deposit rejected by PlayerTransactionGuard:")) {
      if ((line.match(/Deposit rejected by PlayerTransactionGuard:/gi) ?? []).length !== 1) return false;
      const warning = line.match(/Deposit rejected by PlayerTransactionGuard: Transaction too fast, please wait for ([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\s*$/i);
      if (!warning) return false;
      const warningUuid = warning[1].toLowerCase();
      if (warningUuids.has(warningUuid)) return false;
      warningUuids.add(warningUuid);
      orderedGuardEvents.push({ type: "rejection", uuid: warningUuid });
    }
    if (line.includes("T21_GUARD_OWNER")) {
      const owner = line.match(/\bT21_GUARD_OWNER player=(AcceptanceGuardA|AcceptanceGuardB) uuid=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) selfOwned=true otherOwned=false scheduler=entity\s*$/i);
      if ((line.match(/T21_GUARD_OWNER/g) ?? []).length !== 1 || !owner || ownerByName.has(owner[1])) return false;
      ownerByName.set(owner[1], owner[2].toLowerCase());
    }
    if (line.includes("T21_GUARD_TRANSACTIONS")) {
      const transaction = line.match(/\bT21_GUARD_TRANSACTIONS player=(AcceptanceGuardA|AcceptanceGuardB) uuid=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) accepted=5 rejected=1 outcomes=([SF]{6}) before=0\.00 after=5\.00\s*$/i);
      if ((line.match(/T21_GUARD_TRANSACTIONS/g) ?? []).length !== 1 || !transaction || transactionsByName.has(transaction[1]) || transaction[3] !== "SSSSSF") return false;
      const uuid = transaction[2].toLowerCase();
      transactionsByName.set(transaction[1], uuid);
      orderedGuardEvents.push({ type: "transaction", player: transaction[1], uuid });
    }
    if (line.includes("T21_GUARD_NOTICE_ACK")) {
      const ack = line.match(/\bT21_GUARD_NOTICE_ACK stage=([12]) player=(AcceptanceGuardA|AcceptanceGuardB) uuid=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}) recipient=(AcceptanceGuardA|AcceptanceGuardB)\s*$/i);
      if ((line.match(/T21_GUARD_NOTICE_ACK/g) ?? []).length !== 1 || !ack || noticeAcks.has(Number(ack[1]))) return false;
      const entry = {
        stage: Number(ack[1]),
        player: ack[2],
        uuid: ack[3].toLowerCase(),
        recipient: ack[4],
      };
      noticeAcks.set(entry.stage, entry);
      orderedGuardEvents.push({ type: "ack", ...entry });
    }
  }
  if (ownerByName.size !== players.length || transactionsByName.size !== players.length || warningUuids.size !== players.length) return false;

  const noticesByName = new Map();
  const noticeOrder = [];
  for (const rawLine of evidence.clientLines) {
    const line = String(rawLine ?? "");
    if (line.includes("BOT_GUARD_FAIL")) return false;
    if (line.includes("BOT_GUARD_RATE_LIMIT_NOTICE_PASS")) {
      const notice = line.match(/^\s*BOT_GUARD_RATE_LIMIT_NOTICE_PASS\s+stage=([12])\s+player=(AcceptanceGuardA|AcceptanceGuardB)\s+uuid=([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\s+message=.*Transaction too fast, please wait a moment.*\s*$/i);
      if ((line.match(/BOT_GUARD_RATE_LIMIT_NOTICE_PASS/g) ?? []).length !== 1 || !notice || noticesByName.has(notice[2])) return false;
      const entry = { stage: Number(notice[1]), player: notice[2], uuid: notice[3].toLowerCase() };
      if (entry.stage !== noticeOrder.length + 1 || entry.player !== players[entry.stage - 1]) return false;
      noticesByName.set(entry.player, entry.uuid);
      noticeOrder.push(entry);
    }
  }
  if (noticesByName.size !== players.length || noticeAcks.size !== players.length) return false;

  const participantA = participantByName.get(players[0]);
  const participantB = participantByName.get(players[1]);
  const expectedEvents = [
    { type: "rejection", uuid: participantA.uuid },
    { type: "transaction", player: players[0], uuid: participantA.uuid },
    { type: "ack", stage: 1, player: players[0], uuid: participantA.uuid, recipient: players[0] },
    { type: "rejection", uuid: participantB.uuid },
    { type: "transaction", player: players[1], uuid: participantB.uuid },
    { type: "ack", stage: 2, player: players[1], uuid: participantB.uuid, recipient: players[1] },
  ];
  if (JSON.stringify(orderedGuardEvents) !== JSON.stringify(expectedEvents)) return false;

  return players.every((player, index) => {
    const uuid = participantByName.get(player).uuid;
    const notice = noticeOrder[index];
    const ack = noticeAcks.get(index + 1);
    return ownerByName.get(player) === uuid && transactionsByName.get(player) === uuid &&
      noticesByName.get(player) === uuid && notice.player === player && notice.uuid === uuid &&
      ack.player === player && ack.uuid === uuid && ack.recipient === player;
  });
}

export function hasGuardPairCircuitBreakerRuntimeProof(evidence) {
  if (!hasGuardPairRuntimeProof(evidence)) return false;

  const players = new Set(["AcceptanceGuardA", "AcceptanceGuardB"]);
  const observedPlayers = new Set();
  let stateMarkers = 0;
  for (const rawLine of evidence.serverLines) {
    const line = String(rawLine ?? "");
    if (line.includes("T21_GUARD_BREAKER_FAIL")) return false;
    if (!line.includes("T21_GUARD_BREAKER_STATE")) continue;
    stateMarkers += 1;
    const state = line.match(/\bT21_GUARD_BREAKER_STATE circuitBreaker=enabled circuitBreakerLocked=false playerProtection=enabled player=(AcceptanceGuardA|AcceptanceGuardB)\s*$/i);
    if ((line.match(/T21_GUARD_BREAKER_STATE/g) ?? []).length !== 1 || !state || observedPlayers.has(state[1])) return false;
    observedPlayers.add(state[1]);
  }

  return stateMarkers === players.size && observedPlayers.size === players.size &&
    [...players].every((player) => observedPlayers.has(player));
}

export function hasGlobalBreakerPairRuntimeProof(evidence) {
  const players = ["AcceptanceGuardA", "AcceptanceGuardB"];
  if (!evidence || !Array.isArray(evidence.serverLines) || !Array.isArray(evidence.clientLines) ||
      !Array.isArray(evidence.participants) || evidence.participants.length !== players.length) return false;

  const uuidPattern = "([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})";
  const participantByName = new Map();
  for (const participant of evidence.participants) {
    if (!participant || !players.includes(participant.player) || participantByName.has(participant.player) ||
        !new RegExp(`^${uuidPattern}$`, "i").test(participant.uuid ?? "") ||
        !/^\d+\.\d{2}$/.test(participant.redisBalance ?? "") ||
        !/^\d+$/.test(participant.redisVersion ?? "")) return false;
    participantByName.set(participant.player, { ...participant, uuid: participant.uuid.toLowerCase() });
  }
  if (participantByName.size !== players.length ||
      participantByName.get(players[0]).uuid === participantByName.get(players[1]).uuid) return false;

  const owners = new Map();
  const states = new Map();
  const results = new Map();
  const acknowledgements = new Map();
  const rateRejects = [];
  const rateLimitWarnings = [];
  const rateWindows = new Set();
  let passMarkers = 0;
  let passWindow = null;

  for (const rawLine of evidence.serverLines) {
    const line = String(rawLine ?? "");
    if (line.includes("T21_GLOBAL_BREAKER_FAIL") ||
        /rejected by PlayerTransactionGuard:|Transaction too fast, please wait for/i.test(line)) return false;
    if (line.includes("T21_GLOBAL_BREAKER_OWNER")) {
      const owner = line.match(new RegExp(`^T21_GLOBAL_BREAKER_OWNER player=(AcceptanceGuardA|AcceptanceGuardB) uuid=${uuidPattern} selfOwned=true otherOwned=false scheduler=entity$`, "i"));
      if ((line.match(/T21_GLOBAL_BREAKER_OWNER/g) ?? []).length !== 1 || !owner || owners.has(owner[1])) return false;
      owners.set(owner[1], owner[2].toLowerCase());
    }
    if (line.includes("T21_GLOBAL_BREAKER_STATE")) {
      const state = line.match(/^T21_GLOBAL_BREAKER_STATE circuitBreaker=enabled circuitBreakerLocked=false playerProtection=disabled maxTransactionsPerSecond=5 player=(AcceptanceGuardA|AcceptanceGuardB)$/i);
      if ((line.match(/T21_GLOBAL_BREAKER_STATE/g) ?? []).length !== 1 || !state || states.has(state[1])) return false;
      states.set(state[1], true);
    }
    if (line.includes("T21_GLOBAL_BREAKER_TRANSACTIONS")) {
      const result = line.match(new RegExp(`^T21_GLOBAL_BREAKER_TRANSACTIONS player=(AcceptanceGuardA|AcceptanceGuardB) uuid=${uuidPattern} attempted=3 accepted=(\\d+) rejected=(\\d+) outcomes=([SF]{3}) windowSeconds=(\\d+,\\d+,\\d+) before=0\\.00 after=(\\d+)\\.00$`, "i"));
      if ((line.match(/T21_GLOBAL_BREAKER_TRANSACTIONS/g) ?? []).length !== 1 || !result || results.has(result[1])) return false;
      const uuid = result[2].toLowerCase();
      const accepted = Number(result[3]);
      const rejected = Number(result[4]);
      const outcomes = result[5].toUpperCase();
      const windows = result[6].split(",");
      const acceptedFromOutcomes = [...outcomes].filter((outcome) => outcome === "S").length;
      const rejectedFromOutcomes = [...outcomes].filter((outcome) => outcome === "F").length;
      if (accepted !== acceptedFromOutcomes || rejected !== rejectedFromOutcomes ||
          accepted + rejected !== 3 || Number(result[7]) !== accepted ||
          new Set(windows).size !== 1 || windows.some((window) => !/^\d+$/.test(window))) return false;
      windows.forEach((window) => rateWindows.add(window));
      results.set(result[1], { uuid, accepted, rejected, outcomes, windowSecond: windows[0] });
    }
    if (line.includes("T21_GLOBAL_BREAKER_ACK")) {
      const ack = line.match(new RegExp(`^T21_GLOBAL_BREAKER_ACK player=(AcceptanceGuardA|AcceptanceGuardB) uuid=${uuidPattern} recipient=(AcceptanceGuardA|AcceptanceGuardB)$`, "i"));
      if ((line.match(/T21_GLOBAL_BREAKER_ACK/g) ?? []).length !== 1 || !ack || acknowledgements.has(ack[1])) return false;
      acknowledgements.set(ack[1], { uuid: ack[2].toLowerCase(), recipient: ack[3] });
    }
    if (line.includes("T21_GLOBAL_BREAKER_PAIR_PASS")) {
      const pass = line.match(/^T21_GLOBAL_BREAKER_PAIR_PASS players=AcceptanceGuardA,AcceptanceGuardB attempts=6 accepted=5 rejected=1 windowSecond=(\d+) playerProtection=disabled circuitBreakerLocked=false$/i);
      if ((line.match(/T21_GLOBAL_BREAKER_PAIR_PASS/g) ?? []).length !== 1 || !pass) return false;
      passMarkers += 1;
      passWindow = pass[1];
    }
    if (line.includes("Deposit rejected by CircuitBreaker:")) {
      const reject = line.match(new RegExp(`^Deposit rejected by CircuitBreaker: Transaction frequency too high, please try again later for ${uuidPattern}$`, "i"));
      if ((line.match(/Deposit rejected by CircuitBreaker:/g) ?? []).length !== 1 || !reject) return false;
      rateRejects.push(reject[1].toLowerCase());
    }
    if (line.includes("CircuitBreaker: Transaction rate limit exceeded")) {
      if ((line.match(/CircuitBreaker: Transaction rate limit exceeded/g) ?? []).length !== 1 ||
          !/\(Count: 6, Source: VAULT_DEPOSIT\)$/.test(line)) return false;
      rateLimitWarnings.push(line);
    }
  }

  if (owners.size !== players.length || states.size !== players.length || results.size !== players.length ||
      acknowledgements.size !== players.length || passMarkers !== 1 || rateWindows.size !== 1 ||
      passWindow !== [...rateWindows][0] || rateRejects.length !== 1 || rateLimitWarnings.length !== 1) return false;

  let acceptedTotal = 0;
  let rejectedTotal = 0;
  for (const player of players) {
    const uuid = participantByName.get(player).uuid;
    const result = results.get(player);
    const ack = acknowledgements.get(player);
    const participant = participantByName.get(player);
    const persistedAccepted = Number(participant.redisVersion);
    const persistedBalance = Number(participant.redisBalance);
    if (owners.get(player) !== uuid || result.uuid !== uuid || ack.uuid !== uuid || ack.recipient !== player ||
        (result.rejected === 1 && rateRejects[0] !== uuid) ||
        (result.rejected === 0 && rateRejects[0] === uuid) ||
        persistedAccepted !== result.accepted || persistedBalance !== result.accepted) return false;
    acceptedTotal += result.accepted;
    rejectedTotal += result.rejected;
  }
  if (acceptedTotal !== 5 || rejectedTotal !== 1) return false;

  if (evidence.clientLines.length !== players.length * 2) return false;
  const clientProofs = new Map();
  const clientAcknowledgements = new Map();
  for (const rawLine of evidence.clientLines) {
    const line = String(rawLine ?? "");
    if (line.includes("BOT_GLOBAL_BREAKER_FAIL")) return false;
    if (line.includes("BOT_GLOBAL_BREAKER_ACK_PASS")) {
      const acknowledgement = line.match(new RegExp(`^BOT_GLOBAL_BREAKER_ACK_PASS player=(AcceptanceGuardA|AcceptanceGuardB) uuid=${uuidPattern} recipient=(AcceptanceGuardA|AcceptanceGuardB)$`, "i"));
      if ((line.match(/BOT_GLOBAL_BREAKER_ACK_PASS/g) ?? []).length !== 1 || !acknowledgement || clientAcknowledgements.has(acknowledgement[1])) return false;
      clientAcknowledgements.set(acknowledgement[1], {
        uuid: acknowledgement[2].toLowerCase(),
        recipient: acknowledgement[3],
      });
      continue;
    }
    const client = line.match(new RegExp(`^BOT_GLOBAL_BREAKER_PASS player=(AcceptanceGuardA|AcceptanceGuardB) uuid=${uuidPattern}$`, "i"));
    if ((line.match(/BOT_GLOBAL_BREAKER_PASS/g) ?? []).length !== 1 || !client || clientProofs.has(client[1])) return false;
    clientProofs.set(client[1], client[2].toLowerCase());
  }
  const result = players.every((player) => {
    const uuid = participantByName.get(player).uuid;
    const acknowledgement = clientAcknowledgements.get(player);
    return clientProofs.get(player) === uuid && acknowledgement?.uuid === uuid && acknowledgement.recipient === player;
  });
  return result;
}

export function normalizedDimension(value) {
  return String(value ?? "").trim().toLowerCase().replace(/^minecraft:/, "");
}

export function isNetherDimension(value) {
  const dimension = normalizedDimension(value);
  return dimension === "the_nether" || dimension === "nether";
}

if (process.argv[2] === "--classify-teleport-outcome-json") {
  try {
    const evidence = JSON.parse(process.argv[3] ?? "");
    const outcome = classifyTeleportOutcome(
      evidence.eventCancellationStates,
      evidence.teleportFutureSucceeded,
      evidence.entitySchedulerFutureConfirmed,
    );
    if (outcome === null) throw new Error("teleport lacked a completed event path or entity-scheduler confirmation");
    console.log(outcome);
  } catch (error) {
    console.error(error?.message ?? error);
    process.exitCode = 1;
  }
}

if (process.argv[2] === "--has-single-successful-teleport-future-json") {
  try {
    const evidence = JSON.parse(process.argv[3] ?? "");
    console.log(hasSingleSuccessfulTeleportFuture(evidence.logLines) ? "true" : "false");
  } catch (error) {
    console.error(error?.message ?? error);
    process.exitCode = 1;
  }
}

if (process.argv[2] === "--has-exact-server-kick-json") {
  try {
    const evidence = JSON.parse(process.argv[3] ?? "");
    console.log(hasExactExpectedServerKick(evidence.logLines, evidence.playerName) ? "true" : "false");
  } catch (error) {
    console.error(error?.message ?? error);
    process.exitCode = 1;
  }
}

if (process.argv[2] === "--has-two-region-ownership-proof-json") {
  try {
    const evidence = JSON.parse(process.argv[3] ?? "");
    console.log(hasTwoRegionOwnershipProof(evidence.logLines) ? "true" : "false");
  } catch (error) {
    console.error(error?.message ?? error);
    process.exitCode = 1;
  }
}

if (process.argv[2] === "--has-guard-runtime-proof-json") {
  try {
    const evidence = JSON.parse(process.argv[3] ?? "");
    console.log(hasGuardRuntimeProof(evidence) ? "true" : "false");
  } catch (error) {
    console.error(error?.message ?? error);
    process.exitCode = 1;
  }
}

if (process.argv[2] === "--has-guard-pair-runtime-proof-json") {
  try {
    const evidence = JSON.parse(process.argv[3] ?? "");
    console.log(hasGuardPairRuntimeProof(evidence) ? "true" : "false");
  } catch (error) {
    console.error(error?.message ?? error);
    process.exitCode = 1;
  }
}

if (process.argv[2] === "--has-guard-pair-circuit-breaker-runtime-proof-json") {
  try {
    const evidence = JSON.parse(process.argv[3] ?? "");
    console.log(hasGuardPairCircuitBreakerRuntimeProof(evidence) ? "true" : "false");
  } catch (error) {
    console.error(error?.message ?? error);
    process.exitCode = 1;
  }
}

if (process.argv[2] === "--has-global-breaker-pair-runtime-proof-json") {
  try {
    const evidence = JSON.parse(process.argv[3] ?? "");
    console.log(hasGlobalBreakerPairRuntimeProof(evidence) ? "true" : "false");
  } catch (error) {
    console.error(error?.message ?? error);
    process.exitCode = 1;
  }
}

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

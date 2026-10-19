import assert from "node:assert/strict";
import test from "node:test";
import * as client from "./t21-paper-network-client.mjs";
const { parseClientAction } = client;

test("Mineflayer login UUID is read from the protocol client", () => {
  assert.equal(typeof client.getMineflayerLoginUuid, "function");
  const uuid = "a2345678-1234-4234-8234-1234567890ab";
  assert.equal(client.getMineflayerLoginUuid({ _client: { uuid: uuid.toUpperCase() }, uuid: "wrong-bot-field" }), uuid);
  assert.equal(client.getMineflayerLoginUuid({ uuid }), undefined,
    "Mineflayer 4.39.0 does not expose the protocol login UUID as bot.uuid");
});

const validActions = [
  { type: "hop-b", id: "hop-b-1" },
  { type: "reconnect-b", id: "reconnect-b-1" },
  { type: "seed", id: "seed-1" },
  { type: "lifecycle-logout", id: "lifecycle-logout-1" },
  { type: "recovery-barrier", id: "postgres-recovery-barrier" },
  { type: "stop", id: "stop-1" },
  { type: "pay", id: "pay-1", amount: "1.00" },
];

test("every Paper network client action requires and preserves its action ID", () => {
  for (const action of validActions) {
    assert.deepEqual(parseClientAction(JSON.stringify(action)), action);
  }
});

test("control actions reject missing or malformed action IDs", () => {
  for (const type of ["hop-b", "reconnect-b", "seed", "lifecycle-logout", "recovery-barrier", "stop"]) {
    assert.throws(() => parseClientAction(JSON.stringify({ type })), /action id/i);
    assert.throws(() => parseClientAction(JSON.stringify({ type, id: "Bad ID" })), /action id/i);
  }
});

test("client event sequence makes recovery barriers comparable to later receipts", () => {
  assert.equal(typeof client.createSequencedEventEmitter, "function");
  const events = [];
  const emit = client.createSequencedEventEmitter(event => events.push(event), () => new Date("2026-10-19T00:00:00.000Z"));

  const barrier = emit("recovery_barrier", { id: "postgres-recovery-barrier" });
  const receipt = emit("pay_response_pass", { id: "db-outage-pay" });

  assert.equal(barrier.seq, 1);
  assert.equal(receipt.seq, 2);
  assert.equal(receipt.seq > barrier.seq, true);
  assert.deepEqual(events, [barrier, receipt]);
});

test("logout acceptance is limited to the exact accepted-write lifecycle kick", () => {
  assert.equal(typeof client.matchesExpectedLifecycleLogoutKick, "function");
  assert.equal(client.matchesExpectedLifecycleLogoutKick("T15 accepted-write logout scenario"), true);
  assert.equal(client.matchesExpectedLifecycleLogoutKick({ text: "T15 accepted-write logout scenario" }), true);
  assert.equal(client.matchesExpectedLifecycleLogoutKick("Disconnected"), false);
  assert.equal(client.matchesExpectedLifecycleLogoutKick({ text: "T15 unrelated kick" }), false);
});

test("cross-server /pay acceptance requires the exact sender and receiver success messages", () => {
  assert.equal(typeof client.matchesPaySenderSuccess, "function");
  assert.equal(typeof client.matchesPayReceiverSuccess, "function");

  assert.equal(client.matchesPaySenderSuccess(
    "SyncMoney ⦊ Transaction Authorized. 5.00 routed to AcceptanceB ┇ Remaining Capital: 95.00",
    { recipient: "AcceptanceB", amount: "5.00" },
  ), true);
  assert.equal(client.matchesPayReceiverSuccess(
    "SyncMoney ⦊ Incoming Transmission ⦊ Acquired +5.00 from AcceptanceA.",
    { sender: "AcceptanceA", amount: "5.00" },
  ), true);

  assert.equal(client.matchesPaySenderSuccess(
    "TRANSACTION REJECTED: Insufficient capital reserves. Available: 100.00",
    { recipient: "AcceptanceB", amount: "5.00" },
  ), false);
  assert.equal(client.matchesPaySenderSuccess(
    "Transaction Authorized. 5.00 routed to SomeoneElse",
    { recipient: "AcceptanceB", amount: "5.00" },
  ), false);
  assert.equal(client.matchesPayReceiverSuccess(
    "Incoming Transmission ⦊ Acquired +5.00 from SomeoneElse.",
    { sender: "AcceptanceA", amount: "5.00" },
  ), false);
});

test("backend route acknowledgement must name the exact requested Velocity server", () => {
  assert.equal(typeof client.matchesBackendRouteAcknowledgement, "function");
  assert.equal(client.matchesBackendRouteAcknowledgement(
    "Connecting you to backend-b...",
    "backend-b",
  ), true);
  assert.equal(client.matchesBackendRouteAcknowledgement(
    "§7Connecting you to backend-b…",
    "backend-b",
  ), true);
  assert.equal(client.matchesBackendRouteAcknowledgement(
    "Connecting you to backend-b-extra...",
    "backend-b",
  ), false);
  assert.equal(client.matchesBackendRouteAcknowledgement(
    "You are already connected to this server.",
    "backend-b",
  ), false);
});

test("route pass is emitted only after a fresh backend acknowledgement arrives", async () => {
  const events = [];
  const messages = ["Connecting you to backend-b..."];
  const state = {
    username: "AcceptanceB",
    uuid: "b2345678-1234-4234-8234-1234567890ab",
    chatMessages: messages,
    bot: { chat: command => assert.equal(command, "/server backend-b") },
  };
  const routing = client.routeToBackend(state, "backend-b", (type, fields) => events.push({ type, ...fields }), {
    timeoutMs: 100,
    pollIntervalMs: 1,
  });

  assert.deepEqual(events.map(event => event.type), ["hop_submitted"]);
  messages.push("Connecting you to backend-b...");
  assert.equal(await routing, "Connecting you to backend-b...");
  assert.deepEqual(events.map(event => event.type), ["hop_submitted", "hop_pass"]);
  assert.equal(events[1].serverReply, "Connecting you to backend-b...");
  assert.equal(events[1].target, "backend-b");
});

test("route timeout does not emit a backend acknowledgement pass", async () => {
  const events = [];
  const state = {
    username: "AcceptanceB",
    uuid: "b2345678-1234-4234-8234-1234567890ab",
    chatMessages: ["Connecting you to backend-b-extra..."],
    bot: { chat: command => assert.equal(command, "/server backend-b") },
  };

  await assert.rejects(
    client.routeToBackend(state, "backend-b", (type, fields) => events.push({ type, ...fields }), {
      timeoutMs: 10,
      pollIntervalMs: 1,
    }),
    /Timed out waiting for a route acknowledgement to backend-b/i,
  );
  assert.deepEqual(events.map(event => event.type), ["hop_submitted"]);
});

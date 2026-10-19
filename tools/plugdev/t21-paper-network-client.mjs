import { createRequire } from "node:module";
import path from "node:path";
import fs from "node:fs";

const ACCEPTANCE_NAME = /^Acceptance[A-Za-z0-9_]{0,12}$/;
const ACTION_ID = /^[a-z0-9][a-z0-9-]{0,47}$/;
const BALANCE = /^(?:0|[1-9][0-9]{0,5})\.[0-9]{2}$/;

export function getMineflayerLoginUuid(bot) {
  // Mineflayer 4.39.0 keeps the login packet UUID on its minecraft-protocol client.
  const uuid = bot?._client?.uuid;
  return typeof uuid === "string" ? uuid.toLowerCase() : undefined;
}

export function parseClientArguments(argv) {
  const [mineflayerRoot, host, portText, version, backendB, eventPath] = argv;
  const port = Number(portText);
  if (!mineflayerRoot || !path.isAbsolute(mineflayerRoot)) {
    throw new Error("Mineflayer root must be an absolute local path.");
  }
  if (host !== "127.0.0.1") throw new Error("The network client may connect only to loopback.");
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error("Proxy port must be an integer in 1..65535.");
  }
  if (!/^\d+\.\d+(?:\.\d+)?$/.test(version ?? "")) {
    throw new Error("Minecraft protocol version must be an explicit numeric version.");
  }
  if (!/^[a-zA-Z][a-zA-Z0-9_-]{0,62}$/.test(backendB ?? "")) {
    throw new Error("Backend B must be a safe Velocity server name.");
  }
  if (!eventPath || !path.isAbsolute(eventPath)) {
    throw new Error("Client event log must be an absolute path.");
  }
  return { mineflayerRoot: path.resolve(mineflayerRoot), host, port, version, backendB, eventPath: path.resolve(eventPath) };
}

export function parseClientAction(line) {
  let action;
  try {
    action = JSON.parse(line);
  } catch {
    throw new Error("Client action must be one JSON object per line.");
  }
  if (!action || typeof action !== "object" || Array.isArray(action)) {
    throw new Error("Client action must be a JSON object.");
  }
  if (!ACTION_ID.test(action.id ?? "")) {
    throw new Error("Client action id is invalid.");
  }
  switch (action.type) {
    case "hop-b":
    case "reconnect-b":
    case "seed":
    case "lifecycle-logout":
    case "recovery-barrier":
    case "stop":
      return { type: action.type, id: action.id };
    case "pay":
      if (!BALANCE.test(action.amount ?? "")) throw new Error("Pay amount must be a positive amount with two decimals.");
      if (Number(action.amount) <= 0) throw new Error("Pay amount must be positive.");
      return { type: "pay", id: action.id, amount: action.amount };
    default:
      throw new Error(`Unsupported client action type: ${String(action.type)}`);
  }
}

export function createSequencedEventEmitter(writeEvent, now = () => new Date()) {
  if (typeof writeEvent !== "function" || typeof now !== "function") {
    throw new Error("Client event emitter requires write and clock functions.");
  }
  let sequence = 0;
  return (type, fields = {}) => {
    if (typeof type !== "string" || type.length === 0 || !fields || typeof fields !== "object" || Array.isArray(fields)) {
      throw new Error("Client events require a type and object fields.");
    }
    const event = { ...fields, seq: ++sequence, at: now().toISOString(), type };
    writeEvent(event);
    return event;
  };
}

function normalizeChatMessage(value) {
  return String(value ?? "")
    .replace(/\u00a7[0-9a-fk-or]/gi, "")
    .replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, "")
    .replace(/<[^>]*>/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

export function matchesPaySenderSuccess(message, { recipient, amount }) {
  const normalized = normalizeChatMessage(message);
  return normalized.includes("Transaction Authorized.") &&
    normalized.includes(`${amount} routed to ${recipient}`);
}

export function matchesPayReceiverSuccess(message, { sender, amount }) {
  const normalized = normalizeChatMessage(message);
  return normalized.includes("Incoming Transmission") &&
    normalized.includes(`Acquired +${amount} from ${sender}`);
}

export function matchesBackendRouteAcknowledgement(message, backendName) {
  const target = String(backendName ?? "").trim();
  if (!/^[a-zA-Z][a-zA-Z0-9_-]{0,62}$/.test(target)) return false;
  const normalized = normalizeChatMessage(message).toLowerCase();
  const expected = `connecting you to ${target.toLowerCase()}`;
  return normalized === `${expected}...` || normalized === `${expected}…`;
}

export async function routeToBackend(state, target, emit, {
  submittedEventType = "hop_submitted",
  passedEventType = "hop_pass",
  extra = {},
  timeoutMs = 12_000,
  pollIntervalMs = 100,
} = {}) {
  if (!state?.bot || typeof state.bot.chat !== "function" || !Array.isArray(state.chatMessages)) {
    throw new Error("Backend route requires a connected client state.");
  }
  if (typeof emit !== "function") throw new Error("Backend route requires an event emitter.");
  if (!Number.isInteger(timeoutMs) || timeoutMs < 1 || !Number.isInteger(pollIntervalMs) || pollIntervalMs < 1) {
    throw new Error("Backend route timeout and polling interval must be positive integers.");
  }
  const firstFreshMessage = state.chatMessages.length;
  const eventFields = { name: state.username, target, uuid: state.uuid, ...extra };
  emit(submittedEventType, eventFields);
  state.bot.chat(`/server ${target}`);

  const deadline = Date.now() + timeoutMs;
  while (Date.now() <= deadline) {
    const serverReply = state.chatMessages.slice(firstFreshMessage)
      .find(message => matchesBackendRouteAcknowledgement(message, target));
    if (serverReply) {
      emit(passedEventType, { ...eventFields, serverReply });
      return serverReply;
    }
    await delay(Math.min(pollIntervalMs, Math.max(1, deadline - Date.now())));
  }
  throw new Error(`Timed out waiting for a route acknowledgement to ${target}.`);
}

export function matchesExpectedLifecycleLogoutKick(reason) {
  const detail = typeof reason === "string" ? reason : JSON.stringify(reason ?? "");
  return normalizeChatMessage(detail).includes("T15 accepted-write logout scenario");
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function main() {
  const options = parseClientArguments(process.argv.slice(2));
  const require = createRequire(path.join(options.mineflayerRoot, "package.json"));
  const { createBot } = require("mineflayer");
  const eventStream = fs.createWriteStream(options.eventPath, { flags: "wx" });
  let outputClosed = false;
  let stopping = false;
  let actionChain = Promise.resolve();
  const bots = new Map();
  const emit = createSequencedEventEmitter(event => eventStream.write(`${JSON.stringify(event)}\n`));

  function fail(message, actionId) {
    emit("client_error", { message: String(message).replace(/[\r\n]+/g, " ").slice(0, 500), actionId });
    process.exitCode = 1;
    stopping = true;
  }

  function connect(username, slot) {
    return new Promise((resolve, reject) => {
      if (!ACCEPTANCE_NAME.test(username)) return reject(new Error(`Unsafe acceptance name: ${username}`));
      const bot = createBot({
        host: options.host,
        port: options.port,
        username,
        auth: "offline",
        version: options.version,
        hideErrors: false,
      });
      const state = {
        bot, username, slot, uuid: null, spawnCount: 0, chatCount: 0, chatMessages: [], chatEventSequences: [],
        intentionalEnd: false, expectedKick: null, expectedKickObserved: false,
      };
      bots.set(slot, state);
      let settled = false;
      const timeout = setTimeout(() => {
        if (!settled) {
          settled = true;
          reject(new Error(`Timed out connecting ${username} through the proxy.`));
        }
      }, 30_000);

      bot.once("login", () => {
        state.uuid = getMineflayerLoginUuid(bot);
        emit("bot_login", { name: username, slot, uuid: state.uuid, protocol: options.version });
        if (!state.uuid || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(state.uuid)) {
          fail(`Mineflayer did not expose a valid UUID for ${username}.`);
        }
      });
      bot.on("spawn", () => {
        state.spawnCount += 1;
        emit("bot_spawn", { name: username, slot, uuid: state.uuid, count: state.spawnCount });
        if (!settled) {
          settled = true;
          clearTimeout(timeout);
          resolve(state);
        }
      });
      bot.on("messagestr", message => {
        state.chatCount += 1;
        const text = String(message).slice(0, 500);
        state.chatMessages.push(text);
        const event = emit("bot_chat", { name: username, slot, text });
        state.chatEventSequences.push(event.seq);
      });
      bot.once("kicked", reason => {
        const detail = JSON.stringify(reason).slice(0, 500);
        emit("bot_kicked", { name: username, slot, reason: detail });
        if (!settled) {
          settled = true;
          clearTimeout(timeout);
          reject(new Error(`${username} was kicked before spawn: ${detail}`));
        } else if (state.expectedKick) {
          const expected = matchesExpectedLifecycleLogoutKick(reason);
          const pending = state.expectedKick;
          state.expectedKick = null;
          clearTimeout(pending.timer);
          if (expected) {
            state.intentionalEnd = true;
            state.expectedKickObserved = true;
            emit("lifecycle_logout_kick_pass", { name: username, uuid: state.uuid, reason: detail });
            pending.resolve(detail);
          } else {
            const error = new Error(`Unexpected logout kick reason for ${username}: ${detail}`);
            pending.reject(error);
            if (!stopping) fail(error.message);
          }
        } else if (!state.intentionalEnd && !stopping) {
          fail(`${username} was unexpectedly kicked: ${detail}`);
        }
      });
      bot.once("error", error => {
        const detail = error?.stack ?? String(error);
        emit("bot_error", { name: username, slot, message: detail.slice(0, 1000) });
        if (!settled) {
          settled = true;
          clearTimeout(timeout);
          reject(new Error(`${username} failed before spawn: ${error?.message ?? error}`));
        } else if (!state.intentionalEnd && !stopping) {
          fail(`${username} connection error: ${error?.message ?? error}`);
        }
      });
      bot.on("end", () => {
        const expectedEnd = state.intentionalEnd || state.expectedKickObserved;
        emit(expectedEnd ? "bot_end_expected" : "bot_end_unexpected", { name: username, slot });
        if (!settled) {
          settled = true;
          clearTimeout(timeout);
          reject(new Error(`${username} disconnected before spawn.`));
        } else if (!expectedEnd && !stopping) {
          fail(`${username} disconnected unexpectedly.`);
        }
      });
    });
  }

  async function hopB() {
    const b = bots.get("b");
    if (!b) throw new Error("AcceptanceB is not connected.");
    await routeToBackend(b, options.backendB, emit);
  }

  async function pay(action) {
    const a = bots.get("a");
    const b = bots.get("b");
    if (!a || !b) throw new Error("Both Acceptance clients must be connected before /pay.");
    const senderBefore = a.chatMessages.length;
    const receiverBefore = b.chatMessages.length;
    a.bot.chat(`/pay AcceptanceB ${action.amount}`);
    emit("pay_submitted", { id: action.id, sender: a.username, target: "AcceptanceB", amount: action.amount });
    const deadline = Date.now() + 120_000;
    let senderText;
    let receiverText;
    let senderReceiptEventSequence;
    let receiverReceiptEventSequence;
    while (Date.now() < deadline && !stopping) {
      const senderIndex = a.chatMessages.slice(senderBefore).findIndex(text =>
        matchesPaySenderSuccess(text, { recipient: b.username, amount: action.amount }));
      const receiverIndex = b.chatMessages.slice(receiverBefore).findIndex(text =>
        matchesPayReceiverSuccess(text, { sender: a.username, amount: action.amount }));
      senderText = senderIndex < 0 ? undefined : a.chatMessages[senderBefore + senderIndex];
      receiverText = receiverIndex < 0 ? undefined : b.chatMessages[receiverBefore + receiverIndex];
      senderReceiptEventSequence = senderIndex < 0 ? undefined : a.chatEventSequences[senderBefore + senderIndex];
      receiverReceiptEventSequence = receiverIndex < 0 ? undefined : b.chatEventSequences[receiverBefore + receiverIndex];
      if (senderText && receiverText) break;
      await delay(100);
    }
    if (!senderText) throw new Error(`No exact successful /pay sender receipt reached ${a.username} for action ${action.id}.`);
    if (!receiverText) throw new Error(`No exact cross-server /pay receiver receipt reached ${b.username} for action ${action.id}.`);
    emit("pay_response_pass", {
      id: action.id,
      sender: a.username,
      recipient: b.username,
      amount: action.amount,
      senderText,
      receiverText,
      senderReceiptEventSequence,
      receiverReceiptEventSequence,
    });
  }

  async function seedAccounts() {
    const a = bots.get("a");
    if (!a) throw new Error("AcceptanceA is not connected for disposable-account seeding.");
    a.bot.chat("/smaccept write 100.00 50.00");
    emit("seed_submitted", {
      name: a.username,
      uuid: a.uuid,
      balanceA: "100.00",
      balanceB: "50.00",
      serverCommand: "smaccept write",
    });
  }

  async function lifecycleLogout() {
    const a = bots.get("a");
    if (!a || a.intentionalEnd) throw new Error("AcceptanceA is not connected for the logout lifecycle probe.");
    const logoutKick = new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        if (a.expectedKick) a.expectedKick = null;
        reject(new Error("Timed out waiting for the exact accepted-write logout kick."));
      }, 45_000);
      a.expectedKick = { timer, resolve, reject };
    });
    a.bot.chat("/smaccept lifecycle logout");
    emit("lifecycle_logout_submitted", { name: a.username, uuid: a.uuid, scenario: "logout" });
    const reason = await logoutKick;
    emit("lifecycle_logout_pass", { name: a.username, uuid: a.uuid, reason });
  }

  async function reconnectB() {
    const previous = bots.get("b");
    if (!previous) throw new Error("AcceptanceB is not connected.");
    const oldUuid = previous.uuid;
    previous.intentionalEnd = true;
    const ended = new Promise(resolve => previous.bot.once("end", resolve));
    previous.bot.quit("T21 reconnect probe");
    await Promise.race([ended, delay(10_000).then(() => { throw new Error("AcceptanceB did not disconnect cleanly."); })]);
    bots.delete("b");
    await delay(500);
    const next = await connect("AcceptanceB", "b");
    if (next.uuid !== oldUuid) throw new Error(`AcceptanceB UUID changed across reconnect: ${oldUuid} -> ${next.uuid}`);
    await routeToBackend(next, options.backendB, emit, {
      submittedEventType: "reconnect_submitted",
      passedEventType: "reconnect_pass",
      extra: { priorUuid: oldUuid },
    });
  }

  async function stopClients() {
    stopping = true;
    const states = [...bots.values()];
    for (const state of states) {
      state.intentionalEnd = true;
      try { state.bot.quit("T21 network acceptance cleanup"); } catch { /* already disconnected */ }
    }
    await Promise.all(states.map(state => new Promise(resolve => {
      if (state.bot.player == null) return resolve();
      const timer = setTimeout(resolve, 5_000);
      state.bot.once("end", () => { clearTimeout(timer); resolve(); });
    })));
    emit("client_stop_pass", { clients: states.map(state => state.username).sort() });
  }

  async function handleAction(action) {
    emit("action_started", { action: action.type, id: action.id });
    if (action.type === "hop-b") await hopB();
    else if (action.type === "pay") await pay(action);
    else if (action.type === "reconnect-b") await reconnectB();
    else if (action.type === "seed") await seedAccounts();
    else if (action.type === "lifecycle-logout") await lifecycleLogout();
    else if (action.type === "stop") {
      await stopClients();
      return "stop";
    }
    emit("action_pass", { action: action.type, id: action.id });
    return action.type;
  }

  async function start() {
    const a = await connect("AcceptanceA", "a");
    await delay(2_000);
    const b = await connect("AcceptanceB", "b");
    emit("client_ready", { endpoint: options.version, proxy: `${options.host}:${options.port}`, players: [
      { name: a.username, uuid: a.uuid }, { name: b.username, uuid: b.uuid },
    ] });

    process.stdin.setEncoding("utf8");
    let pending = "";
    process.stdin.on("data", chunk => {
      pending += chunk;
      const lines = pending.split(/\r?\n/);
      pending = lines.pop() ?? "";
      for (const line of lines) {
        if (!line.trim()) continue;
        let action;
        try {
          action = parseClientAction(line);
        } catch {
          action = null;
        }
        if (action?.type === "recovery-barrier") {
          emit("recovery_barrier", { id: action.id });
          continue;
        }
        actionChain = actionChain.then(async () => {
          try {
            action ??= parseClientAction(line);
            const result = await handleAction(action);
            if (result === "stop") {
              process.stdin.pause();
              eventStream.end(() => {
                outputClosed = true;
                process.exit(process.exitCode ?? 0);
              });
            }
          } catch (error) {
            fail(error?.stack ?? error, action?.id);
            await stopClients();
            if (!outputClosed) eventStream.end(() => {
              outputClosed = true;
              process.exit(1);
            });
          }
        });
      }
    });
    process.stdin.on("end", async () => {
      if (!stopping) {
        fail("Control input closed before an explicit stop action.");
        await stopClients();
      }
      if (!outputClosed) eventStream.end(() => {
        outputClosed = true;
        if (process.exitCode === undefined) process.exitCode = 1;
      });
    });
  }

  start().catch(async error => {
    fail(error?.stack ?? error);
    await stopClients();
    if (!outputClosed) eventStream.end(() => {
      outputClosed = true;
      process.exit(1);
    });
  });
}

if (process.argv[1] && import.meta.url === new URL(`file://${path.resolve(process.argv[1]).replaceAll("\\", "/")}`).href) {
  main();
}

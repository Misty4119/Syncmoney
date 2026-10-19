import crypto from "node:crypto";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

export const T21_PAPER_NETWORK_ENDPOINTS = Object.freeze({
  "paper-1216-48": Object.freeze({
    product: "Paper",
    version: "1.21.6",
    build: 48,
    serverJarSha256: "35e2dfa66b3491b9d2f0bb033679fa5aca1e1fdf097e7a06a80ce8afeda5c214",
  }),
  "paper-263-200": Object.freeze({
    product: "Paper",
    version: "26.3",
    build: 200,
    serverJarSha256: "0e104a19ad818f05266a4571c0a9b0cada241c2016ee64883e5d38435332fa8d",
  }),
});

export const T21_PAPER_NETWORK_ARTIFACTS = Object.freeze({
  candidateSha256: "7d4fb070ecdb61ece3d5e39b7a2bf6ceb72c0c6a939f269588344b41f908b354",
  acceptanceSha256: "4a8e5ffc8d32c482cd7cf2a474cfd480d5c46268c0ea9ec469d513ed6ff554df",
  vaultUnlockedSha256: "4ec21910767a68b2defacb121cffee9840ded8b01abcbff03779fb9ab637296a",
  velocitySha256: "85be73b90f295acd8d5d45a00ad10bac791d4b72b7554172612a89565ebaaa8f",
});

const WORKSPACE_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");
const USER_ROOT = process.env.USERPROFILE ?? "C:/Users/margo";
const PLUGDEV_ROOT = path.join(USER_ROOT, "IdeaProjects", "plugdev-main");
const MINEFLAYER_ROOT = path.join(USER_ROOT, "IdeaProjects", "mineflayer-master");
const POSTGRES_BIN = "C:/Program Files/PostgreSQL/18/bin";
const REDIS_ROOT = path.join(USER_ROOT, "scoop", "apps", "redis", "current");
const JAVA_EXE = "C:/Program Files/Java/jdk-25.0.4/bin/java.exe";

export const T21_PAPER_NETWORK_HARNESS_SOURCES = Object.freeze({
  runner: "tools/plugdev/t21-paper-network-runner.mjs",
  client: "tools/plugdev/t21-paper-network-client.mjs",
  controller: "tools/plugdev/t21-paper-network-controller.mjs",
  shutdownBridge: "tools/plugdev/t21-network-shutdown-bridge.cjs",
  auditor: "tools/plugdev/t21-paper-network-auditor.mjs",
  runnerTest: "tools/plugdev/t21-paper-network-runner.test.mjs",
  clientTest: "tools/plugdev/t21-paper-network-client.test.mjs",
  controllerTest: "tools/plugdev/t21-paper-network-controller.test.mjs",
  shutdownBridgeTest: "tools/plugdev/t21-network-shutdown-bridge.test.cjs",
  auditorTest: "tools/plugdev/t21-paper-network-auditor.test.mjs",
});
export const T21_PAPER_NETWORK_SOURCE_MANIFEST_SCHEMA_VERSION = 1;

const REQUIRED_FILES = Object.freeze([
  "events/runner-events.jsonl",
  "events/client-events.jsonl",
  "events/controller-status.jsonl",
  "events/child-process-events.jsonl",
  "evidence/backend-position.json",
  "evidence/config-observations.json",
  "evidence/source-manifest.json",
  "logs/network.log",
  "logs/server-a.log",
  "logs/server-b.log",
  "logs/postgresql.log",
  "logs/postgresql-row-lock.log",
  "logs/redis.log",
  "snapshots/after-seed.json",
  "snapshots/after-cross-pay.json",
  "snapshots/during-sql-outage.json",
  "snapshots/after-sql-recovery.json",
  "snapshots/after-accepted-write-drain.json",
]);

const EXPECTED_RUNNER_ORDER = Object.freeze([
  "postgres_ready_initial",
  "redis_ready",
  "network_ready",
  "clients_ready",
  "accounts_seeded",
  "backend_position_verified",
  "cross_server_pay_pass",
  "reconnect_b_pass",
  "postgres_outage_begin",
  "postgres_stopped_for_outage",
  "outage_pay_submitted",
  "outage_memory_state_observed",
  "outage_sql_retry_observed",
  "postgres_restarted",
  "client_recovery_barrier",
  "outage_pay_receipt_after_recovery",
  "sql_recovery_parity_pass",
  "accepted_write_row_lock_acquired",
  "accepted_write_logout_pass",
  "client_stop_pass",
  "network_shutdown_requested",
  "shutdown_drain_lock_held",
  "accepted_write_lock_released_for_shutdown",
  "network_exited",
  "final_sql_redis_parity_pass",
  "redis_stopped",
  "postgres_stopped_final",
  "cleanup_verified",
]);

export const T21_PAPER_NETWORK_ARTIFACT_PATHS = Object.freeze([
  Object.freeze({ kind: "paper", path: "inputs/paper.jar" }),
  Object.freeze({ kind: "paper", path: "workspace/.plugdev/network/backends/backend-a/server.jar" }),
  Object.freeze({ kind: "paper", path: "workspace/.plugdev/network/backends/backend-b/server.jar" }),
  Object.freeze({ kind: "candidate", path: "inputs/Syncmoney.jar" }),
  Object.freeze({ kind: "candidate", path: "workspace/.plugdev/network/backends/backend-a/plugins/Syncmoney.jar" }),
  Object.freeze({ kind: "candidate", path: "workspace/.plugdev/network/backends/backend-b/plugins/Syncmoney.jar" }),
  Object.freeze({ kind: "acceptance", path: "inputs/SyncmoneyAcceptance.jar" }),
  Object.freeze({ kind: "acceptance", path: "workspace/.plugdev/network/backends/backend-a/plugins/SyncmoneyAcceptance.jar" }),
  Object.freeze({ kind: "acceptance", path: "workspace/.plugdev/network/backends/backend-b/plugins/SyncmoneyAcceptance.jar" }),
  Object.freeze({ kind: "vaultUnlocked", path: "inputs/VaultUnlocked.jar" }),
  Object.freeze({ kind: "vaultUnlocked", path: "workspace/.plugdev/network/backends/backend-a/plugins/VaultUnlocked.jar" }),
  Object.freeze({ kind: "vaultUnlocked", path: "workspace/.plugdev/network/backends/backend-b/plugins/VaultUnlocked.jar" }),
  Object.freeze({ kind: "velocity", path: "inputs/velocity.jar" }),
  Object.freeze({ kind: "velocity", path: "workspace/.plugdev/network/proxy/velocity.jar" }),
]);

export class NetworkAuditError extends Error {
  constructor(failures) {
    super(`T21 Paper network evidence rejected (${failures.length} checks): ${failures.join("; ")}`);
    this.name = "NetworkAuditError";
    this.failures = failures;
  }
}

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function localFilePin(filePath) {
  const absolutePath = path.resolve(filePath);
  const bytes = fs.readFileSync(absolutePath);
  return { path: absolutePath, bytes: bytes.length, sha256: sha256(bytes) };
}

function gitValue(repository, args) {
  return execFileSync("git", args, { cwd: repository, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }).trim();
}

function gitDirty(repository) {
  return gitValue(repository, ["status", "--porcelain", "--untracked-files=all"]).length > 0;
}

const PLUGDEV_CLI = path.join(PLUGDEV_ROOT, "packages", "cli", "dist", "cli.js");
const MINEFLAYER_PACKAGE_JSON = path.join(MINEFLAYER_ROOT, "package.json");
const PLUGDEV_PACKAGE_JSON = path.join(PLUGDEV_ROOT, "packages", "cli", "package.json");

export const T21_PAPER_NETWORK_TOOLCHAIN_PINS = Object.freeze({
  workspaceRoot: WORKSPACE_ROOT,
  plugdev: Object.freeze({
    root: PLUGDEV_ROOT,
    commit: "b59871ae8d9f2731d2bcbca061a735edddd23eb2",
    version: "1.3.0",
    dirty: gitDirty(PLUGDEV_ROOT),
    cliPath: PLUGDEV_CLI,
    cliSha256: "40437ecfb68cc5f13f77bfcaf25be5f2bebbb90d269d482284a3129906e06155",
    packageJson: localFilePin(PLUGDEV_PACKAGE_JSON),
    distRoot: path.join(PLUGDEV_ROOT, "packages", "cli", "dist"),
  }),
  mineflayer: Object.freeze({
    root: MINEFLAYER_ROOT,
    commit: "def6013cc2282f265d518b1af298b9c720a65c12",
    version: "4.39.0",
    dirty: gitDirty(MINEFLAYER_ROOT),
    packageJson: localFilePin(MINEFLAYER_PACKAGE_JSON),
  }),
  node: Object.freeze({
    version: process.version,
    executable: localFilePin(process.execPath),
  }),
  java: Object.freeze({
    version: "25.0.4",
    executable: localFilePin(JAVA_EXE),
  }),
  postgres: Object.freeze({
    version: "18.1",
    binaryRoot: POSTGRES_BIN,
    binaries: Object.freeze(Object.fromEntries(["postgres.exe", "pg_ctl.exe", "initdb.exe", "psql.exe"].map(name =>
      [name, localFilePin(path.join(POSTGRES_BIN, name))]))),
  }),
  redis: Object.freeze({
    version: "8.10.1",
    server: localFilePin(path.join(REDIS_ROOT, "redis-server.exe")),
    cli: localFilePin(path.join(REDIS_ROOT, "redis-cli.exe")),
  }),
  velocity: Object.freeze({
    url: "https://fill.papermc.io/v3/projects/velocity/versions/4.3.0/builds",
    version: "4.3.0",
    build: 44,
    channel: "STABLE",
    sha256: T21_PAPER_NETWORK_ARTIFACTS.velocitySha256,
  }),
});

export function collectDirectoryManifest(rootPath) {
  const root = path.resolve(rootPath);
  const entries = [];
  function visit(directory, relativeDirectory = "") {
    const children = fs.readdirSync(directory, { withFileTypes: true })
      .sort((a, b) => a.name.localeCompare(b.name));
    for (const child of children) {
      const absolute = path.join(directory, child.name);
      const relative = relativeDirectory ? path.join(relativeDirectory, child.name) : child.name;
      const stat = fs.lstatSync(absolute);
      if (stat.isSymbolicLink()) throw new Error(`Distribution manifest rejects symbolic link ${absolute}`);
      if (stat.isDirectory()) visit(absolute, relative);
      else if (stat.isFile()) {
        const bytes = fs.readFileSync(absolute);
        entries.push({ path: relative.split(path.sep).join("/"), bytes: bytes.length, sha256: sha256(bytes) });
      } else throw new Error(`Distribution manifest rejects non-regular file ${absolute}`);
    }
  }
  if (!fs.statSync(root).isDirectory()) throw new Error(`Distribution manifest root is not a directory: ${root}`);
  visit(root);
  return entries.sort((a, b) => a.path.localeCompare(b.path));
}

function isRecord(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function isSafeRunId(value) {
  return typeof value === "string" && /^[a-z0-9][a-z0-9-]{5,95}$/.test(value);
}

function parseJsonLines(text, label, fail) {
  const lines = text.split(/\r?\n/).filter(line => line.length > 0);
  const parsed = [];
  for (let index = 0; index < lines.length; index += 1) {
    try {
      const value = JSON.parse(lines[index]);
      if (!isRecord(value)) throw new Error("record must be an object");
      parsed.push(value);
    } catch (error) {
      fail(`${label} line ${index + 1} is invalid JSON (${error.message})`);
    }
  }
  return parsed;
}

function resolveEvidencePath(root, relativePath, label, fail) {
  if (typeof relativePath !== "string" || relativePath.length === 0 ||
      path.isAbsolute(relativePath) || relativePath.includes("\\") ||
      relativePath.split("/").some(part => part === ".." || part === "")) {
    fail(`${label} must be a normalized run-relative path`);
    return null;
  }
  const absolute = path.resolve(root, ...relativePath.split("/"));
  const rel = path.relative(path.resolve(root), absolute);
  if (rel === ".." || rel.startsWith(`..${path.sep}`) || path.isAbsolute(rel)) {
    fail(`${label} escapes the run directory`);
    return null;
  }
  return absolute;
}

function requireString(actual, expected, label, fail) {
  if (typeof actual !== "string" || actual !== expected) fail(`${label} must equal ${expected}`);
}

function loadJsonFile(filePath, label, fail) {
  try {
    const parsed = JSON.parse(fs.readFileSync(filePath, "utf8"));
    if (!isRecord(parsed)) throw new Error("JSON root must be an object");
    return parsed;
  } catch (error) {
    fail(`${label} is not valid JSON (${error.message})`);
    return {};
  }
}

function validateSnapshots(root, report, fail) {
  const files = report.filesByPath;
  const read = relative => loadJsonFile(files.get(relative)?.absolutePath ?? "", relative, fail);
  const seed = read("snapshots/after-seed.json");
  const afterPay = read("snapshots/after-cross-pay.json");
  const outage = read("snapshots/during-sql-outage.json");
  const recovered = read("snapshots/after-sql-recovery.json");
  const final = read("snapshots/after-accepted-write-drain.json");
  const accounts = ["AcceptanceA", "AcceptanceB"];

  function account(snapshot, name, label) {
    const row = snapshot.accounts?.[name];
    if (!isRecord(row)) {
      fail(`${label} is missing ${name}`);
      return { uuid: "", sqlBalance: "", sqlVersion: -1, redisBalance: "", redisVersion: -1 };
    }
    if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(row.uuid ?? "")) {
      fail(`${label} ${name} UUID is invalid`);
    }
    return row;
  }

  function requirePair(snapshot, label, expectedBalances, sqlRequired = true) {
    for (const name of accounts) {
      const row = account(snapshot, name, label);
      if (row.redisBalance !== expectedBalances[name]) fail(`${label} Redis ${name} balance mismatch`);
      if (sqlRequired && row.sqlBalance !== expectedBalances[name]) fail(`${label} SQL ${name} balance mismatch`);
      if (!Number.isSafeInteger(row.redisVersion) || row.redisVersion < 1) fail(`${label} Redis ${name} version is invalid`);
      if (sqlRequired && (!Number.isSafeInteger(row.sqlVersion) || row.sqlVersion < 1)) fail(`${label} SQL ${name} version is invalid`);
      if (sqlRequired && row.sqlVersion !== row.redisVersion) fail(`${label} SQL/Redis ${name} version differs`);
    }
  }

  requireString(seed.stage, "after-seed", "seed snapshot stage", fail);
  requirePair(seed, "seed snapshot", { AcceptanceA: "100.00", AcceptanceB: "50.00" });
  requireString(afterPay.stage, "after-cross-pay", "cross-pay snapshot stage", fail);
  requirePair(afterPay, "cross-pay snapshot", { AcceptanceA: "95.00", AcceptanceB: "55.00" });
  requireString(outage.stage, "during-sql-outage", "outage snapshot stage", fail);
  if (outage.sqlReachable !== false) fail("outage snapshot must independently record SQL as unreachable");
  requirePair(outage, "outage snapshot", { AcceptanceA: "92.00", AcceptanceB: "58.00" }, false);
  requireString(recovered.stage, "after-sql-recovery", "recovery snapshot stage", fail);
  requirePair(recovered, "recovery snapshot", { AcceptanceA: "92.00", AcceptanceB: "58.00" });
  requireString(final.stage, "after-accepted-write-drain", "final snapshot stage", fail);
  requirePair(final, "final snapshot", { AcceptanceA: "94.00", AcceptanceB: "58.00" });

  for (const name of accounts) {
    const seeded = account(seed, name, "seed snapshot");
    const finalRow = account(final, name, "final snapshot");
    if (seeded.uuid !== finalRow.uuid) fail(`final ${name} UUID differs from seed`);
    if (finalRow.redisVersion <= seeded.redisVersion) fail(`final ${name} version did not advance`);
  }
  if (final.sqlReachable !== true) fail("final SQL snapshot is not reachable");
  if (final.redisDatabase !== 15) fail("final Redis snapshot must use database 15");
  return { seed, afterPay, outage, recovered, final };
}

function validateClientEvents(events, backendB, fail) {
  const ofType = type => events.filter(event => event.type === type);
  events.forEach((event, index) => {
    if (event.seq !== index + 1) fail("client event sequence is missing, duplicated, or out of order");
  });
  const loginsA = ofType("bot_login").filter(event => event.name === "AcceptanceA");
  const loginsB = ofType("bot_login").filter(event => event.name === "AcceptanceB");
  if (loginsA.length !== 1) fail("client must contain exactly one AcceptanceA login");
  if (loginsB.length !== 2) fail("client must contain the initial and reconnect AcceptanceB logins");
  if (loginsB.length === 2 && loginsB[0].uuid !== loginsB[1].uuid) fail("AcceptanceB UUID changed on reconnect");
  if (loginsA.length === 1 && loginsB.length === 2 && loginsA[0].uuid === loginsB[0].uuid) fail("acceptance accounts must have distinct UUIDs");

  const hopRequests = ofType("hop_submitted").filter(event => event.name === "AcceptanceB" && event.target === backendB);
  const hopPasses = ofType("hop_pass").filter(event => event.name === "AcceptanceB" && event.target === backendB);
  const hop = hopRequests[0];
  const hopPass = hopPasses[0];
  if (hopRequests.length !== 1) fail("client must submit exactly one AcceptanceB route request to backend B");
  if (hopPasses.length !== 1 || !isVelocityRouteAcknowledgement(hopPass?.serverReply, backendB)) {
    fail("client did not observe a backend-B route acknowledgement");
  }
  if (hop && hopPass && events.indexOf(hopPass) <= events.indexOf(hop)) fail("backend-B route acknowledgement preceded its submitted request");
  const payPasses = ofType("pay_response_pass");
  const initialPay = payPasses.find(event => event.id === "cross-server-pay");
  const outagePayEvents = payPasses.filter(event => event.id === "db-outage-pay");
  const outagePay = outagePayEvents[0];
  const recoveryBarriers = ofType("recovery_barrier").filter(event => event.id === "postgres-recovery-barrier");
  const recoveryBarrier = recoveryBarriers[0];
  if (outagePayEvents.length !== 1) fail("client must contain exactly one outage payment receipt event");
  if (recoveryBarriers.length !== 1) fail("client must contain exactly one PostgreSQL recovery barrier event");
  if (outagePay && recoveryBarrier && outagePay.seq <= recoveryBarrier.seq) {
    fail("outage payment client receipt event did not follow the PostgreSQL recovery barrier");
  }
  const paySubmissions = ofType("pay_submitted");
  for (const [event, label, amount, id] of [
    [initialPay, "cross-server pay", "5.00", "cross-server-pay"],
    [outagePay, "outage pay", "3.00", "db-outage-pay"],
  ]) {
    if (!event) {
      fail(`client is missing ${label} success`);
      continue;
    }
    if (event.sender !== "AcceptanceA" || event.recipient !== "AcceptanceB" || event.amount !== amount) fail(`${label} identities or amount mismatch`);
    if (typeof event.senderText !== "string" || !event.senderText.includes(`Transaction Authorized. ${amount} routed to AcceptanceB`)) fail(`${label} sender receipt is not exact`);
    if (typeof event.receiverText !== "string" || !event.receiverText.includes(`Acquired +${amount} from AcceptanceA`)) fail(`${label} receiver receipt is not exact`);
    const senderChat = events.find(chat => chat.seq === event.senderReceiptEventSequence);
    const receiverChat = events.find(chat => chat.seq === event.receiverReceiptEventSequence);
    if (!senderChat || senderChat.type !== "bot_chat" || senderChat.name !== "AcceptanceA" || senderChat.text !== event.senderText) {
      fail(`${label} sender receipt is not linked to the exact client chat event`);
    }
    if (!receiverChat || receiverChat.type !== "bot_chat" || receiverChat.name !== "AcceptanceB" || receiverChat.text !== event.receiverText) {
      fail(`${label} receiver receipt is not linked to the exact client chat event`);
    }
    if (id === "db-outage-pay" && recoveryBarrier &&
        (!Number.isSafeInteger(event.senderReceiptEventSequence) || !Number.isSafeInteger(event.receiverReceiptEventSequence) ||
         event.senderReceiptEventSequence <= recoveryBarrier.seq || event.receiverReceiptEventSequence <= recoveryBarrier.seq)) {
      fail("outage payment receipt client chat events did not follow the PostgreSQL recovery barrier");
    }
    const submissions = paySubmissions.filter(submission => submission.id === id);
    if (submissions.length !== 1 || submissions[0]?.sender !== "AcceptanceA" ||
        submissions[0]?.target !== "AcceptanceB" || submissions[0]?.amount !== amount) {
      fail(`${label} does not have one exact submitted client action`);
    } else if (events.indexOf(event) <= events.indexOf(submissions[0])) {
      fail(`${label} success receipt preceded its submitted client action`);
    }
  }
  const reconnectRequests = ofType("reconnect_submitted").filter(event => event.name === "AcceptanceB" && event.target === backendB);
  const reconnectPasses = ofType("reconnect_pass").filter(event => event.name === "AcceptanceB" && event.target === backendB);
  const reconnectRequest = reconnectRequests[0];
  const reconnect = reconnectPasses[0];
  if (reconnectRequests.length !== 1) fail("AcceptanceB reconnect must submit exactly one backend-B route request");
  if (reconnectPasses.length !== 1) fail("AcceptanceB reconnect must contain exactly one backend-B route pass");
  if (!reconnect || reconnect.uuid !== (loginsB[0]?.uuid ?? null) || reconnect.priorUuid !== reconnect.uuid ||
      !isVelocityRouteAcknowledgement(reconnect.serverReply, backendB)) {
    fail("AcceptanceB reconnect is not proven with a stable UUID and backend-B route acknowledgement");
  }
  if (reconnectRequest && reconnect && events.indexOf(reconnect) <= events.indexOf(reconnectRequest)) fail("reconnect route acknowledgement preceded its submitted request");
  if (reconnectRequest && reconnect && (reconnectRequest.uuid !== reconnect.uuid || reconnectRequest.priorUuid !== reconnect.priorUuid)) {
    fail("reconnect route request identity does not match its acknowledged client");
  }
  if (ofType("lifecycle_logout_pass").filter(event => event.name === "AcceptanceA").length !== 1) fail("client did not observe the exact accepted-write logout");
  if (ofType("client_stop_pass").length !== 1) fail("client cleanup did not complete");
  if (ofType("client_error").length !== 0 || ofType("bot_error").length !== 0 || ofType("bot_end_unexpected").length !== 0) fail("client log contains an unexpected error or disconnect");
  return {
    uuidA: loginsA[0]?.uuid ?? null,
    uuidB: loginsB[0]?.uuid ?? null,
    outagePay,
    recoveryBarrier,
  };
}

function validateRecoveryReceiptLink(runnerEvents, clientEvidence, fail) {
  const runnerBarriers = runnerEvents.filter(event => event.type === "client_recovery_barrier");
  const runnerReceipts = runnerEvents.filter(event => event.type === "outage_pay_receipt_after_recovery");
  const runnerBarrier = runnerBarriers[0];
  const runnerReceipt = runnerReceipts[0];
  const clientBarrier = clientEvidence.recoveryBarrier;
  const clientReceipt = clientEvidence.outagePay;

  if (runnerBarriers.length !== 1 || runnerBarrier?.actionId !== "postgres-recovery-barrier" ||
      !Number.isSafeInteger(runnerBarrier?.clientEventSequence)) {
    fail("runner did not record exactly one sequenced client recovery barrier");
  }
  if (runnerReceipts.length !== 1 || runnerReceipt?.id !== "db-outage-pay" ||
      runnerReceipt?.receiptObserved !== true || !Number.isSafeInteger(runnerReceipt?.receiptEventSequence) ||
      !Number.isSafeInteger(runnerReceipt?.senderReceiptEventSequence) ||
      !Number.isSafeInteger(runnerReceipt?.receiverReceiptEventSequence) ||
      !Number.isSafeInteger(runnerReceipt?.recoveryBarrierEventSequence)) {
    fail("runner did not record the sequenced outage payment client receipt");
  }
  if (clientBarrier && runnerBarrier?.clientEventSequence !== clientBarrier.seq) {
    fail("runner recovery barrier sequence differs from the client event log");
  }
  if (clientReceipt && runnerReceipt?.receiptEventSequence !== clientReceipt.seq) {
    fail("runner outage receipt sequence differs from the client event log");
  }
  if (clientReceipt && runnerReceipt?.senderReceiptEventSequence !== clientReceipt.senderReceiptEventSequence) {
    fail("runner outage sender receipt sequence differs from the client event log");
  }
  if (clientReceipt && runnerReceipt?.receiverReceiptEventSequence !== clientReceipt.receiverReceiptEventSequence) {
    fail("runner outage receiver receipt sequence differs from the client event log");
  }
  if (clientBarrier && runnerReceipt?.recoveryBarrierEventSequence !== clientBarrier.seq) {
    fail("runner outage receipt references a different client recovery barrier");
  }
  if (clientReceipt && clientBarrier && clientReceipt.seq <= clientBarrier.seq) {
    fail("client receipt event did not follow the PostgreSQL recovery barrier");
  }
}

function isVelocityRouteAcknowledgement(value, target) {
  if (typeof value !== "string" || typeof target !== "string" || !/^[a-zA-Z][a-zA-Z0-9_-]{0,62}$/.test(target)) return false;
  const normalized = value
    .replace(/\u00a7[0-9a-fk-or]/gi, "")
    .replace(/\u001b\[[0-?]*[ -/]*[@-~]/g, "")
    .replace(/<[^>]*>/g, " ")
    .replace(/\s+/g, " ")
    .trim()
    .toLowerCase();
  const expected = `connecting you to ${target.toLowerCase()}`;
  return normalized === `${expected}...` || normalized === `${expected}…`;
}

function validateServerLogs(logABytes, logBBytes, runnerEvents, uuidA, uuidB, fail) {
  const logBytes = [logABytes, logBBytes];
  const logs = logBytes.map(bytes => bytes.toString("utf8"));
  for (const [name, log] of [["backend A", logs[0]], ["backend B", logs[1]]]) {
    if (!log.includes("Done (")) fail(`${name} log lacks Paper ready marker`);
    if (!log.includes("T21_DRAIN owner=economy status=drained queued=0 outstanding=0 retry=0")) fail(`${name} economy queue did not drain to zero`);
    if (!log.includes("T21_DRAIN owner=database status=drained queued=0 inFlight=0 retry=0 stale=0 dropped=0")) fail(`${name} database queue did not drain to zero`);
    if (!log.includes("Syncmoney disabled.")) fail(`${name} did not log normal Syncmoney disable`);
  }
  const joinedA = (logs[0].match(/AcceptanceA joined the game/g) ?? []).length;
  const joinedB = (logs[1].match(/AcceptanceB joined the game/g) ?? []).length;
  if (joinedA < 1) fail("backend A log does not show AcceptanceA joining");
  if (joinedB < 2) fail("backend B log does not show initial and reconnect joins for AcceptanceB");

  const outageBegin = runnerEvents.find(event => event.type === "postgres_outage_begin");
  const postgresRestarted = runnerEvents.find(event => event.type === "postgres_restarted");
  const backendNames = ["backendA", "backendB"];
  const outageTexts = logBytes.map((bytes, index) => {
    const backend = backendNames[index];
    const start = outageBegin?.serverLogByteOffsets?.[backend];
    const end = postgresRestarted?.serverLogByteOffsets?.[backend];
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || end < start || end > bytes.length) {
      fail(`${backend} outage byte offset is invalid or outside the retained server log`);
      return "";
    }
    return bytes.subarray(start, end).toString("utf8");
  }).join("\n");
  if (!outageTexts.includes("Failed to process batch to DB")) fail("no SQL batch failure was observed during the bounded outage");
  const retryLines = outageTexts.split(/\r?\n/).filter(line => line.includes("Re-queued failed DB write task (attempt"));
  if (retryLines.length === 0) fail("no SQL retry/requeue was observed during the bounded outage");
  const knownUuids = new Set([uuidA, uuidB].filter(Boolean).map(uuid => uuid.toLowerCase()));
  for (const line of retryLines) {
    const match = line.match(/for player ([0-9a-f-]{36})/i);
    if (!match) fail("SQL outage retry line does not identify an acceptance account UUID");
    else if (!knownUuids.has(match[1].toLowerCase())) fail("SQL outage retry refers to an account outside the acceptance pair");
  }
  if (logs.some(log => log.includes("Dropping DB write task after") || log.includes("DB write queue is full; could not re-queue"))) fail("server log reports a dropped or unrequeued SQL write");

  const lifecycle = logs[0];
  const acceptedWrites = (lifecycle.match(/T15_ACCEPTED_WRITE scenario=logout index=/g) ?? []).length;
  if (acceptedWrites !== 2) fail("backend A did not record exactly two accepted logout writes");
  if (!lifecycle.includes(`T15_PENDING_AT_BOUNDARY scenario=logout uuid=${uuidA} pending=2 pending-state=accepted-not-completed`)) fail("backend A did not record both accepted writes pending at logout");
  if (!lifecycle.includes(`T15_LOGOUT_REQUEST uuid=${uuidA}`)) fail("backend A did not request the accepted-write logout");
}

function validatePositions(value, backendA, backendB, fail) {
  for (const phase of ["initial", "afterReconnect"]) {
    const snapshot = value[phase];
    if (!isRecord(snapshot)) {
      fail(`backend position evidence is missing ${phase}`);
      continue;
    }
    const onA = String(snapshot.backendA ?? "");
    const onB = String(snapshot.backendB ?? "");
    if (!onA.includes("AcceptanceA") || onA.includes("AcceptanceB")) fail(`${phase} backend A roster is not exclusive to AcceptanceA`);
    if (!onB.includes("AcceptanceB") || onB.includes("AcceptanceA")) fail(`${phase} backend B roster is not exclusive to AcceptanceB`);
    if (snapshot.backendAName !== backendA || snapshot.backendBName !== backendB) fail(`${phase} backend position names differ from the pinned pair`);
  }
}

function validateConfigObservations(value, report, fail) {
  const backends = Array.isArray(report.backends) ? report.backends : [];
  if (backends.length !== 2) fail("exactly two backend config observations are required");
  const expectedNames = [backends[0]?.serverName, backends[1]?.serverName];
  if (!expectedNames[0] || !expectedNames[1] || expectedNames[0] === expectedNames[1]) fail("backend server-name values must be present and distinct");
  const observations = [value?.A, value?.B];
  for (let index = 0; index < observations.length; index += 1) {
    const config = observations[index];
    const label = index === 0 ? "backend A" : "backend B";
    if (!isRecord(config)) {
      fail(`${label} config observation is missing`);
      continue;
    }
    if (config.serverName !== expectedNames[index]) fail(`${label} configured server-name is incorrect`);
    if (String(config.mode).toLowerCase() !== "sync" || config.dbEnabled !== true || config.pubsubEnabled !== true || config.redisEnabled !== true) fail(`${label} is not configured for SYNC with SQL and Redis`);
    if (String(config.databaseType).toLowerCase() !== "postgresql" || config.databaseHost !== "127.0.0.1" || config.databasePort !== report.services?.postgres?.port) fail(`${label} database does not point to the isolated loopback PostgreSQL`);
    if (config.redisHost !== "127.0.0.1" || config.redisPort !== report.services?.redis?.port || config.redisDatabase !== 15) fail(`${label} Redis config does not point to the isolated loopback DB 15`);
  }
}

function validateRunnerEvents(events, fail) {
  const seen = events.map(event => event.type);
  let cursor = -1;
  for (const required of EXPECTED_RUNNER_ORDER) {
    const next = seen.indexOf(required, cursor + 1);
    if (next < 0) fail(`runner event sequence is missing or misordered ${required}`);
    else cursor = next;
  }
  const outage = events.find(event => event.type === "postgres_stopped_for_outage");
  const restart = events.find(event => event.type === "postgres_restarted");
  const outageSubmitted = events.find(event => event.type === "outage_pay_submitted");
  const outageMemory = events.find(event => event.type === "outage_memory_state_observed");
  const outageRetry = events.find(event => event.type === "outage_sql_retry_observed");
  const outageReceipt = events.find(event => event.type === "outage_pay_receipt_after_recovery");
  if (!outage || outage.exitCode !== 0 || outage.runOwned !== true || outage.portClosed !== true) fail("PostgreSQL outage was not proven against the run-owned cluster");
  if (!restart || restart.exitCode !== 0 || restart.runOwned !== true || restart.ready !== true) fail("run-owned PostgreSQL recovery was not observed");
  if (!outageSubmitted || outageSubmitted.id !== "db-outage-pay" || outageSubmitted.amount !== "3.00") {
    fail("outage payment was not recorded as submitted while PostgreSQL was stopped");
  }
  if (!outageMemory || outageMemory.stage !== "during-sql-outage" || outageMemory.sqlReachable !== false) {
    fail("outage payment memory state was not observed while PostgreSQL was stopped");
  }
  if (!outageRetry || !Number.isSafeInteger(outageRetry.retryCount) || outageRetry.retryCount < 1) {
    fail("no SQL retry was observed before PostgreSQL recovery");
  }
  if (!outageReceipt || outageReceipt.id !== "db-outage-pay" || outageReceipt.receiptObserved !== true ||
      !Number.isSafeInteger(outageReceipt.receiptEventSequence) ||
      !Number.isSafeInteger(outageReceipt.senderReceiptEventSequence) ||
      !Number.isSafeInteger(outageReceipt.receiverReceiptEventSequence) ||
      !Number.isSafeInteger(outageReceipt.recoveryBarrierEventSequence) ||
      outageReceipt.senderReceiptEventSequence <= outageReceipt.recoveryBarrierEventSequence ||
      outageReceipt.receiverReceiptEventSequence <= outageReceipt.recoveryBarrierEventSequence ||
      outageReceipt.receiptEventSequence <= outageReceipt.recoveryBarrierEventSequence ||
      events.indexOf(outageReceipt) <= events.indexOf(restart)) {
    fail("outage payment receipt was not sequenced after PostgreSQL recovery");
  }
  const recoveryBarrier = events.find(event => event.type === "client_recovery_barrier");
  if (!recoveryBarrier || recoveryBarrier.actionId !== "postgres-recovery-barrier" ||
      !Number.isSafeInteger(recoveryBarrier.clientEventSequence) ||
      recoveryBarrier.clientEventSequence !== outageReceipt?.recoveryBarrierEventSequence ||
      events.indexOf(recoveryBarrier) <= events.indexOf(restart) ||
      events.indexOf(outageReceipt) <= events.indexOf(recoveryBarrier)) {
    fail("client recovery barrier was not recorded between PostgreSQL recovery and the payment receipt");
  }
  const logout = events.find(event => event.type === "accepted_write_logout_pass");
  if (!logout || logout.pending !== 2 || logout.rowLockHeld !== true || logout.rowLockReleased !== false) {
    fail("accepted logout writes were not observed pending while the SQL row lock remained held");
  }
  const cleanup = events.find(event => event.type === "cleanup_verified");
  if (!cleanup || cleanup.forcedCleanup !== false || cleanup.evidenceComplete !== true ||
      !Array.isArray(cleanup.errors) || cleanup.errors.length !== 0 ||
      cleanup.runOwnedProcessesRemaining !== 0 || cleanup.runOwnedPortsOpen !== 0) {
    fail("run cleanup was forced, incomplete, or left owned processes/ports");
  }
}

function validateShutdownEvidence(events, logABytes, logBBytes, rowLockLogBytes, fail) {
  const held = events.find(event => event.type === "shutdown_drain_lock_held");
  const released = events.find(event => event.type === "accepted_write_lock_released_for_shutdown");
  const marker = "T21_ROW_LOCK_RELEASED_FOR_SHUTDOWN";
  const rowLockLog = rowLockLogBytes.toString("utf8");
  if (!held || held.pending !== 2 || held.rowLockHeld !== true) fail("shutdown did not begin while both accepted writes remained behind the SQL row lock");
  if (!released || released.rowLockReleased !== true || released.rowLockReleaseMarkerObserved !== true || released.rowLockReleaseMarker !== marker) {
    fail("SQL row-lock release marker was not observed before network exit");
  }
  if (!rowLockLog.includes(marker)) fail("retained PostgreSQL row-lock log lacks the lock-release marker");

  const backendNames = ["backendA", "backendB"];
  for (const [index, backend] of backendNames.entries()) {
    const bytes = index === 0 ? logABytes : logBBytes;
    const stopping = Buffer.from("[Server thread/INFO]: Stopping server", "utf8");
    const stopIndex = bytes.indexOf(stopping);
    if (stopIndex < 0) {
      fail(`${backend} log lacks a server-stop marker while the SQL row lock was held`);
      continue;
    }
    const newlineIndex = bytes.indexOf(0x0a, stopIndex);
    const stopEnd = newlineIndex < 0 ? bytes.length : newlineIndex + 1;
    const offset = held?.serverLogByteOffsets?.[backend];
    if (!Number.isSafeInteger(offset) || offset < stopEnd || offset > bytes.length) {
      fail(`${backend} shutdown log byte offset does not include its Stopping server marker`);
      continue;
    }
    const beforeRelease = bytes.subarray(0, offset).toString("utf8");
    const afterShutdownBoundary = bytes.subarray(offset).toString("utf8");
    if (beforeRelease.includes("T21_DRAIN owner=economy status=drained") ||
        beforeRelease.includes("T21_DRAIN owner=database status=drained")) {
      fail(`${backend} queue drained before the runner recorded the held-lock shutdown boundary`);
    }
    if (!afterShutdownBoundary.includes("T21_DRAIN owner=economy status=drained queued=0 outstanding=0 retry=0") ||
        !afterShutdownBoundary.includes("T21_DRAIN owner=database status=drained queued=0 inFlight=0 retry=0 stale=0 dropped=0")) {
      fail(`${backend} queues did not drain after the held-lock shutdown boundary`);
    }
  }
}

function validateControllerEvents(events, fail) {
  const ready = events.find(event => event.state === "network_ready");
  const stopped = events.find(event => event.state === "network_stop_requested");
  const exited = [...events].reverse().find(event => event.state === "network_exited");
  if (!ready || !Number.isInteger(ready.cliPid)) fail("network controller did not observe PlugDev ready");
  if (!stopped) fail("network controller did not request graceful PlugDev shutdown");
  if (!exited || exited.exitCode !== 0 || exited.signal !== null || exited.networkReady !== true || exited.stopRequested !== true) fail("PlugDev network process did not exit gracefully after readiness");
}

function validateChildProcessEvents(events, report, runRoot, fail) {
  const spawns = events.filter(event => event.event === "spawn");
  const exits = events.filter(event => event.event === "exit");
  const kills = events.filter(event => event.event === "kill");
  const isJavaChild = event => /^javaw?(?:\.exe)?$/i.test(path.basename(String(event.executable ?? ""))) ||
    (Array.isArray(event.args) && event.args.includes("-jar"));
  const isServerJavaLaunch = event => Array.isArray(event.args) && event.args.includes("-jar");
  const isJavaVersionProbe = event => /^javaw?(?:\.exe)?$/i.test(path.basename(String(event.executable ?? ""))) &&
    Array.isArray(event.args) && event.args.length === 2 &&
    event.args[0] === event.executable && event.args[1] === "-version";
  const javaSpawns = spawns.filter(isServerJavaLaunch);
  const javaExits = exits.filter(isServerJavaLaunch);
  const javaKills = kills.filter(isJavaChild);
  if (javaSpawns.length !== 3 || javaExits.length !== 3) fail("shutdown bridge must record exactly the two Paper and one Velocity Java child spawn/exit pairs");
  if (javaKills.length !== 0) fail("shutdown bridge recorded a forced Java-child kill");
  const expectedLaunches = [
    { relativePath: path.join("workspace", ".plugdev", "network", "backends", "backend-a", "server.jar"), label: "backend-a Paper" },
    { relativePath: path.join("workspace", ".plugdev", "network", "backends", "backend-b", "server.jar"), label: "backend-b Paper" },
    { relativePath: path.join("workspace", ".plugdev", "network", "proxy", "velocity.jar"), label: "Velocity" },
  ];
  const launchCounts = new Map(expectedLaunches.map(item => [item.label, 0]));
  for (const event of [...javaSpawns, ...javaExits]) {
    if (!Number.isSafeInteger(event.pid) || typeof event.executable !== "string" || !Array.isArray(event.args)) {
      fail("child process event is malformed or lacks executable provenance");
      continue;
    }
    if (!/^javaw?(?:\.exe)?$/i.test(path.basename(event.executable))) fail("unexpected non-Java child process was launched by the network CLI");
    if (!event.args.join(" ").toLowerCase().includes(report.runId.toLowerCase())) fail("Java child event is not scoped to this run directory");
    const jarIndex = event.args.indexOf("-jar");
    const jarArgument = jarIndex >= 0 ? event.args[jarIndex + 1] : undefined;
    if (typeof jarArgument !== "string" || !path.isAbsolute(jarArgument)) {
      fail("unexpected Java child launch does not name an absolute -jar path");
      continue;
    }
    const resolvedJar = path.resolve(jarArgument);
    const relativeJar = path.relative(path.resolve(runRoot), resolvedJar);
    if (relativeJar === ".." || relativeJar.startsWith(`..${path.sep}`) || path.isAbsolute(relativeJar)) {
      fail("Java child JAR path escapes the report run directory");
      continue;
    }
    const normalizedJar = normalizedPath(resolvedJar);
    const launch = expectedLaunches.find(item => normalizedJar === normalizedPath(path.join(runRoot, item.relativePath)));
    if (!launch) fail("Java child did not launch the exact pinned workspace path for Paper or Velocity");
    else if (event.event === "spawn") launchCounts.set(launch.label, launchCounts.get(launch.label) + 1);
  }
  for (const [label, count] of launchCounts) if (count !== 1) fail(`${label} child launch count must be exactly one`);
  for (const spawn of spawns) {
    const matched = exits.filter(exit => exit.pid === spawn.pid);
    if (matched.length !== 1 || matched[0].executable !== spawn.executable ||
        JSON.stringify(matched[0].args) !== JSON.stringify(spawn.args)) {
      fail(`child process ${spawn.pid} did not have one matching exit`);
    } else if (isJavaChild(spawn)) {
      if (!isServerJavaLaunch(spawn) && !isJavaVersionProbe(spawn)) {
        fail(`Java child ${spawn.pid} is neither a server JAR launch nor an exact -version probe`);
      }
      if (matched[0].code !== 0 || matched[0].signal !== null) fail(`Java child ${spawn.pid} did not have one normal exit`);
    }
  }
  for (const exit of exits) {
    if (spawns.filter(spawn => spawn.pid === exit.pid).length !== 1) fail(`Java child exit ${exit.pid} has no unique launch`);
  }
}

function validateServiceAndArtifactPins(report, expectedEndpoint, expectedArtifacts, fail) {
  if (!isRecord(report.endpoint)) fail("endpoint metadata is missing");
  else {
    requireString(report.endpoint.product, expectedEndpoint.product, "endpoint product", fail);
    requireString(report.endpoint.version, expectedEndpoint.version, "endpoint version", fail);
    if (report.endpoint.build !== expectedEndpoint.build) fail("endpoint build pin mismatch");
    requireString(report.endpoint.serverJarSha256, expectedEndpoint.serverJarSha256, "Paper SHA-256", fail);
  }
  if (!isRecord(report.artifacts)) {
    fail("artifact metadata is missing");
    return;
  }
  if (report.artifacts.serverJarSha256 !== expectedEndpoint.serverJarSha256) fail("serverJarSha256 artifact pin mismatch");
  for (const [name, expected] of Object.entries(expectedArtifacts)) {
    if (report.artifacts[name] !== expected) fail(`${name} artifact pin mismatch`);
  }
  if (!Array.isArray(report.backends) || report.backends.length !== 2) fail("exactly two report backends are required");
  else {
    const [a, b] = report.backends;
    if (a.name !== "backend-a" || b.name !== "backend-b") fail("backend identities must remain backend-a/backend-b");
    if (a.paperJarSha256 !== expectedEndpoint.serverJarSha256 || b.paperJarSha256 !== expectedEndpoint.serverJarSha256) fail("both backend Paper hashes must match the selected exact endpoint");
    for (const backend of [a, b]) {
      if (backend.candidateJarSha256 !== expectedArtifacts.candidateSha256 || backend.acceptanceJarSha256 !== expectedArtifacts.acceptanceSha256 || backend.vaultUnlockedJarSha256 !== expectedArtifacts.vaultUnlockedSha256) fail(`${backend.name} plugin artifact pins do not match the required inputs`);
    }
  }
  if (report.services?.postgres?.bind !== "127.0.0.1" || report.services?.postgres?.isolated !== true) fail("PostgreSQL is not proven isolated on loopback");
  if (report.services?.redis?.bind !== "127.0.0.1" || report.services?.redis?.isolated !== true || report.services?.redis?.database !== 15) fail("Redis is not proven isolated on loopback DB 15");
  if (report.services?.postgres?.version !== T21_PAPER_NETWORK_TOOLCHAIN_PINS.postgres.version) fail("PostgreSQL version does not match its toolchain pin");
  if (report.services?.redis?.version !== T21_PAPER_NETWORK_TOOLCHAIN_PINS.redis.version) fail("Redis version does not match its toolchain pin");
  const network = report.network;
  if (!isRecord(network)) fail("network port metadata is missing");
  else {
    if (network.proxyBind !== "127.0.0.1") fail("Velocity proxy bind must be loopback");
    if (!isRecord(network.backendPorts) || !isRecord(network.rconPorts)) fail("backend/RCON port maps are missing");
  }
  const ports = [
    ["PostgreSQL", report.services?.postgres?.port],
    ["Redis", report.services?.redis?.port],
    ["Velocity proxy", network?.proxyPort],
    ["backend-a", network?.backendPorts?.["backend-a"]],
    ["backend-b", network?.backendPorts?.["backend-b"]],
    ["backend-a RCON", network?.rconPorts?.["backend-a"]],
    ["backend-b RCON", network?.rconPorts?.["backend-b"]],
    ["shutdown bridge", network?.shutdownBridgePort],
  ];
  const seenPorts = new Map();
  for (const [label, port] of ports) {
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      fail(`${label} port must be an integer in 1..65535`);
      continue;
    }
    if (seenPorts.has(port)) fail(`${label} port duplicates ${seenPorts.get(port)} port ${port}`);
    else seenPorts.set(port, label);
    if (port === 5432 || port === 6379) fail(`${label} port collides with protected service port ${port}`);
  }
  if (Number.isInteger(network?.rconPorts?.["backend-a"]) &&
      Number.isInteger(network?.rconPorts?.["backend-b"]) &&
      network.rconPorts["backend-b"] !== network.rconPorts["backend-a"] + 1) {
    fail("RCON ports must be unique and consecutive");
  }
  for (const backend of [report.backends?.[0], report.backends?.[1]]) {
    if (backend && backend.serverName?.length > 63) fail(`${backend.name} server-name is too long`);
  }
}

function normalizedPath(value) {
  if (typeof value !== "string" || !path.isAbsolute(value)) return null;
  const absolute = path.resolve(value);
  return process.platform === "win32" ? absolute.toLowerCase() : absolute;
}

function validateFilePin(record, expected, label, fail) {
  if (!isRecord(record)) {
    fail(`${label} file provenance is missing`);
    return;
  }
  if (normalizedPath(record.path) !== normalizedPath(expected.path)) fail(`${label} path does not match its pinned source`);
  if (record.bytes !== expected.bytes || record.sha256 !== expected.sha256) fail(`${label} byte count/SHA-256 does not match its pinned source`);
  try {
    const bytes = fs.readFileSync(expected.path);
    if (record.bytes !== bytes.length || record.sha256 !== sha256(bytes)) fail(`${label} source file drifted after the report was recorded`);
  } catch (error) { fail(`${label} pinned source cannot be read (${error.code ?? error.message})`); }
}

function validateSourceManifest(manifest, report, expectedArtifacts, trustedArtifactSources, fail) {
  if (!isRecord(manifest)) {
    fail("source manifest is missing or malformed");
    return;
  }
  if (manifest.schemaVersion !== T21_PAPER_NETWORK_SOURCE_MANIFEST_SCHEMA_VERSION) fail("source manifest schema version is unsupported");
  if (normalizedPath(manifest.workspaceRoot) !== normalizedPath(T21_PAPER_NETWORK_TOOLCHAIN_PINS.workspaceRoot)) {
    fail("source manifest workspace root does not match this C: worktree");
  }
  if (!/^[0-9a-f]{40}$/i.test(manifest.sourceCommit ?? "") || report.sourceCommit !== manifest.sourceCommit) {
    fail("source manifest commit does not match the report source commit");
  } else {
    try { gitValue(WORKSPACE_ROOT, ["cat-file", "-e", `${manifest.sourceCommit}^{commit}`]); }
    catch { fail("source manifest commit is not present in this worktree Git database"); }
  }
  if (typeof manifest.sourceWorkingTreeDirty !== "boolean" ||
      report.sourceWorkingTreeDirty !== manifest.sourceWorkingTreeDirty) {
    fail("source manifest dirty-state does not match the report");
  }

  if (!isRecord(manifest.harness)) fail("harness source manifest is missing");
  else {
    const expectedNames = Object.keys(T21_PAPER_NETWORK_HARNESS_SOURCES).sort();
    if (JSON.stringify(Object.keys(manifest.harness).sort()) !== JSON.stringify(expectedNames)) {
      fail("harness source manifest does not contain the exact required source set");
    }
    for (const [name, relativePath] of Object.entries(T21_PAPER_NETWORK_HARNESS_SOURCES)) {
      const record = manifest.harness[name];
      const expectedPath = path.resolve(WORKSPACE_ROOT, ...relativePath.split("/"));
      if (!isRecord(record)) {
        fail(`${name} harness source pin is missing`);
        continue;
      }
      if (normalizedPath(record.path) !== normalizedPath(expectedPath)) fail(`${name} harness source path is not pinned to the worktree file`);
      try {
        const bytes = fs.readFileSync(expectedPath);
        if (record.bytes !== bytes.length || record.sha256 !== sha256(bytes)) fail(`${name} harness source SHA-256/byte count does not match the worktree file`);
      } catch (error) { fail(`${name} harness source cannot be read (${error.code ?? error.message})`); }
    }
  }

  const artifactHashes = {
    paper: expectedArtifacts.serverJarSha256 ?? report.artifacts?.serverJarSha256,
    candidate: expectedArtifacts.candidateSha256,
    acceptance: expectedArtifacts.acceptanceSha256,
    vaultUnlocked: expectedArtifacts.vaultUnlockedSha256,
    velocity: expectedArtifacts.velocitySha256,
  };
  if (!isRecord(manifest.artifacts)) fail("source artifact manifest is missing");
  else {
    for (const kind of Object.keys(artifactHashes)) {
      const record = manifest.artifacts[kind];
      const expectedPath = trustedArtifactSources?.[kind];
      if (!isRecord(record) || typeof expectedPath !== "string") {
        fail(`${kind} source artifact provenance is missing`);
        continue;
      }
      if (normalizedPath(record.path) !== normalizedPath(expectedPath)) fail(`${kind} source artifact path differs from its trusted pin`);
      try {
        const bytes = fs.readFileSync(expectedPath);
        const actual = sha256(bytes);
        if (record.bytes !== bytes.length || record.sha256 !== actual || actual !== artifactHashes[kind]) {
          fail(`${kind} source artifact byte count/SHA-256 does not match its trusted pin`);
        }
      } catch (error) { fail(`${kind} source artifact cannot be read (${error.code ?? error.message})`); }
    }
  }

  const pins = T21_PAPER_NETWORK_TOOLCHAIN_PINS;
  const plugdev = manifest.plugdev;
  if (!isRecord(plugdev)) fail("PlugDev toolchain provenance is missing");
  else {
    for (const key of ["root", "commit", "version", "cliPath", "cliSha256", "distRoot"]) {
      if ((key === "root" || key === "cliPath" || key === "distRoot")
        ? normalizedPath(plugdev[key]) !== normalizedPath(pins.plugdev[key])
        : plugdev[key] !== pins.plugdev[key]) fail(`PlugDev ${key} does not match its reviewed toolchain pin`);
    }
    if (typeof plugdev.dirty !== "boolean" || plugdev.dirty !== pins.plugdev.dirty) fail("PlugDev working-tree state does not match the pinned checkout");
    if (normalizedPath(plugdev.packageJson?.path) !== normalizedPath(pins.plugdev.packageJson.path) ||
        plugdev.packageJson?.bytes !== pins.plugdev.packageJson.bytes || plugdev.packageJson?.sha256 !== pins.plugdev.packageJson.sha256) {
      fail("PlugDev package manifest pin is incomplete or stale");
    }
    try {
      const cliBytes = fs.readFileSync(pins.plugdev.cliPath);
      if (sha256(cliBytes) !== pins.plugdev.cliSha256 || plugdev.cliSha256 !== sha256(cliBytes)) fail("PlugDev CLI bundle SHA-256 does not match its pin");
      const actualDistribution = collectDirectoryManifest(pins.plugdev.distRoot);
      if (JSON.stringify(plugdev.distributionFiles) !== JSON.stringify(actualDistribution)) fail("PlugDev distribution file manifest differs from the installed CLI distribution");
      if (gitValue(pins.plugdev.root, ["rev-parse", "HEAD"]) !== pins.plugdev.commit ||
          gitDirty(pins.plugdev.root) !== plugdev.dirty) fail("PlugDev checkout commit/dirty-state changed after the report");
    } catch (error) { fail(`PlugDev provenance could not be verified (${error.code ?? error.message})`); }
  }

  const mineflayer = manifest.mineflayer;
  if (!isRecord(mineflayer)) fail("Mineflayer toolchain provenance is missing");
  else {
    for (const key of ["root", "commit", "version"]) {
      if ((key === "root" ? normalizedPath(mineflayer[key]) !== normalizedPath(pins.mineflayer[key]) : mineflayer[key] !== pins.mineflayer[key])) {
        fail(`Mineflayer ${key} does not match its reviewed toolchain pin`);
      }
    }
    if (typeof mineflayer.dirty !== "boolean" || mineflayer.dirty !== pins.mineflayer.dirty) fail("Mineflayer working-tree state does not match the pinned checkout");
    validateFilePin(mineflayer.packageJson, pins.mineflayer.packageJson, "Mineflayer package manifest", fail);
    try {
      if (gitValue(pins.mineflayer.root, ["rev-parse", "HEAD"]) !== pins.mineflayer.commit ||
          gitDirty(pins.mineflayer.root) !== mineflayer.dirty) fail("Mineflayer checkout commit/dirty-state changed after the report");
    } catch (error) { fail(`Mineflayer provenance could not be verified (${error.code ?? error.message})`); }
  }

  if (manifest.nodeVersion !== pins.node.version || report.runtime?.nodeVersion !== manifest.nodeVersion) fail("Node.js version does not match the runner toolchain");
  validateFilePin(manifest.nodeExecutable, pins.node.executable, "Node.js executable", fail);
  if (!String(manifest.javaVersion ?? "").includes(pins.java.version) ||
      report.runtime?.javaVersion !== manifest.javaVersion || report.runtime?.javaMajor !== 25) fail("Java runtime version does not match the Java 25 pin");
  validateFilePin(manifest.javaExecutable, pins.java.executable, "Java executable", fail);

  const postgres = manifest.postgres;
  if (!isRecord(postgres) || postgres.version !== pins.postgres.version ||
      normalizedPath(postgres.binaryRoot) !== normalizedPath(pins.postgres.binaryRoot)) fail("PostgreSQL toolchain version/binary root is missing or incorrect");
  else {
    if (!isRecord(postgres.binaries) || JSON.stringify(Object.keys(postgres.binaries).sort()) !== JSON.stringify(Object.keys(pins.postgres.binaries).sort())) {
      fail("PostgreSQL executable manifest does not contain the exact required binary set");
    } else for (const [name, expected] of Object.entries(pins.postgres.binaries)) validateFilePin(postgres.binaries[name], expected, `PostgreSQL ${name}`, fail);
    if (report.services?.postgres?.version !== postgres.version) fail("PostgreSQL report version differs from its source manifest");
  }

  const redis = manifest.redis;
  if (!isRecord(redis) || redis.version !== pins.redis.version) fail("Redis toolchain version is missing or incorrect");
  else {
    validateFilePin(redis.server, pins.redis.server, "Redis server executable", fail);
    validateFilePin(redis.cli, pins.redis.cli, "Redis CLI executable", fail);
    if (report.services?.redis?.version !== redis.version) fail("Redis report version differs from its source manifest");
  }

  const velocity = manifest.velocityMetadata;
  if (!isRecord(velocity)) fail("Velocity stable metadata provenance is missing");
  else {
    for (const key of ["url", "version", "build", "sha256"]) {
      const expected = key === "sha256" ? expectedArtifacts.velocitySha256 : pins.velocity[key];
      if (velocity[key] !== expected) fail(`Velocity ${key} does not match its stable metadata pin`);
    }
    if (typeof velocity.channel !== "string" || velocity.channel.toUpperCase() !== pins.velocity.channel) fail("Velocity channel is not STABLE");
    if (typeof velocity.checkedAt !== "string" || Number.isNaN(Date.parse(velocity.checkedAt))) fail("Velocity metadata timestamp is invalid");
    for (const key of ["url", "version", "build", "channel", "sha256", "checkedAt"]) {
      if (report.network?.velocity?.[key] !== velocity[key]) fail(`Velocity report metadata ${key} differs from the source manifest`);
    }
  }
}

function validateArtifactCopies(report, runRoot, expectedEndpoint, expectedArtifacts, trustedArtifactSources, fail) {
  const expectedHashes = {
    paper: expectedEndpoint.serverJarSha256,
    candidate: expectedArtifacts.candidateSha256,
    acceptance: expectedArtifacts.acceptanceSha256,
    vaultUnlocked: expectedArtifacts.vaultUnlockedSha256,
    velocity: expectedArtifacts.velocitySha256,
  };
  if (!Array.isArray(report.artifactFiles) || report.artifactFiles.length !== T21_PAPER_NETWORK_ARTIFACT_PATHS.length) {
    fail("artifact copy manifest is incomplete");
  }
  const manifest = new Map();
  for (const record of Array.isArray(report.artifactFiles) ? report.artifactFiles : []) {
    if (!isRecord(record) || typeof record.path !== "string") {
      fail("artifact copy manifest contains a malformed entry");
      continue;
    }
    if (manifest.has(record.path)) fail(`artifact copy manifest duplicates ${record.path}`);
    manifest.set(record.path, record);
  }

  for (const expected of T21_PAPER_NETWORK_ARTIFACT_PATHS) {
    const record = manifest.get(expected.path);
    if (!record) {
      fail(`artifact copy is missing ${expected.path}`);
      continue;
    }
    if (record.kind !== expected.kind) fail(`artifact kind mismatch for ${expected.path}`);
    const absolutePath = resolveEvidencePath(runRoot, record.path, "artifact copy path", fail);
    if (!absolutePath) continue;
    try {
      const bytes = fs.readFileSync(absolutePath);
      const actual = sha256(bytes);
      if (record.bytes !== bytes.length) fail(`artifact copy byte count mismatch for ${expected.path}`);
      if (actual !== expectedHashes[expected.kind]) fail(`artifact copy SHA-256 mismatch for ${expected.path}`);
      if (record.sha256 !== actual) fail(`artifact copy report hash mismatch for ${expected.path}`);
    } catch (error) {
      fail(`artifact copy ${expected.path} cannot be read (${error.code ?? error.message})`);
    }
  }

  if (!isRecord(trustedArtifactSources)) {
    fail("trusted source artifact paths are missing");
    return;
  }
  for (const [kind, expectedHash] of Object.entries(expectedHashes)) {
    const sourcePath = trustedArtifactSources[kind];
    if (typeof sourcePath !== "string" || !path.isAbsolute(sourcePath)) {
      fail(`${kind} trusted source path is missing or not absolute`);
      continue;
    }
    if (process.platform === "win32" && path.parse(path.resolve(sourcePath)).root.toLowerCase() !== "c:\\") {
      fail(`${kind} trusted source artifact must be on C:`);
      continue;
    }
    try {
      if (sha256(fs.readFileSync(sourcePath)) !== expectedHash) fail(`${kind} source artifact SHA-256 mismatch`);
    } catch (error) {
      fail(`${kind} source artifact cannot be read (${error.code ?? error.message})`);
    }
  }
}

export function resolvePinnedArtifactSources(workspaceRoot, endpointId) {
  const endpoint = T21_PAPER_NETWORK_ENDPOINTS[endpointId];
  if (!endpoint) throw new NetworkAuditError([`unknown endpoint ${String(endpointId)}`]);
  const root = path.resolve(workspaceRoot);
  const userRoot = process.env.USERPROFILE;
  if (process.platform === "win32") {
    if (path.parse(root).root.toLowerCase() !== "c:\\") throw new NetworkAuditError(["workspace root must be on C:"]);
    if (typeof userRoot !== "string" || path.parse(path.resolve(userRoot)).root.toLowerCase() !== "c:\\") {
      throw new NetworkAuditError(["user profile must be on C:"]);
    }
  }
  const plugdevRoot = path.join(userRoot ?? "C:/Users/margo", ".plugdev");
  const paper = endpointId === "paper-1216-48"
    ? path.join(plugdevRoot, "servers", "paper-1.21.6", "paper-1.21.6-48.jar")
    : path.join(root, "build", "reports", "foundation", "T21-sql-runtime", "current-maximum-artifacts", "paper-26.3-200.jar");
  return {
    paper,
    candidate: path.join(root, "build", "libs", "Syncmoney-1.4.0-alpha.1.jar"),
    acceptance: path.join(root, "build", "acceptance", "SyncmoneyAcceptance.jar"),
    vaultUnlocked: path.join(plugdevRoot, "deps", "url-VaultUnlocked.jar"),
    velocity: path.join(plugdevRoot, "servers", "velocity-4.3.0", "velocity-4.3.0-44.jar"),
  };
}

export function validateNetworkReport(report, runRoot, expectedEndpoint, expectedArtifacts = T21_PAPER_NETWORK_ARTIFACTS, trustedArtifactSources = {}) {
  const failures = [];
  const fail = message => failures.push(message);
  if (!isRecord(report)) throw new NetworkAuditError(["report root must be an object"]);
  if (report.schemaVersion !== 1) fail("unsupported report schema version");
  if (report.status !== "RUN_COMPLETED") fail("report must describe a completed run pending offline audit");
  if (!isSafeRunId(report.runId)) fail("runId is missing or unsafe");
  if (!isRecord(expectedEndpoint)) fail("trusted endpoint pin was not supplied");
  else {
    validateServiceAndArtifactPins(report, expectedEndpoint, expectedArtifacts, fail);
    validateArtifactCopies(report, runRoot, expectedEndpoint, expectedArtifacts, trustedArtifactSources, fail);
  }
  if (report.runtime?.javaMajor !== 25) fail("runtime must use Java 25");
  if (report.runtime?.plugdevVersion !== "1.3.0" || report.runtime?.plugdevDirty !== true) fail("local PlugDev version/dirty-state provenance is incomplete");

  const filesByPath = new Map();
  if (!Array.isArray(report.files)) fail("file manifest is missing");
  else {
    for (const record of report.files) {
      if (!isRecord(record)) {
        fail("file manifest contains a non-object entry");
        continue;
      }
      const absolutePath = resolveEvidencePath(runRoot, record.path, "manifest path", fail);
      if (!absolutePath) continue;
      if (filesByPath.has(record.path)) fail(`duplicate manifest path ${record.path}`);
      let bytes;
      try { bytes = fs.readFileSync(absolutePath); }
      catch (error) { fail(`manifest file ${record.path} cannot be read (${error.code ?? error.message})`); continue; }
      if (record.bytes !== bytes.length) fail(`manifest byte count mismatch for ${record.path}`);
      if (record.sha256 !== sha256(bytes)) fail(`manifest SHA-256 mismatch for ${record.path}`);
      filesByPath.set(record.path, { absolutePath, bytes });
    }
  }
  report.filesByPath = filesByPath;
  for (const relativePath of REQUIRED_FILES) if (!filesByPath.has(relativePath)) fail(`required evidence file is absent: ${relativePath}`);

  if (Array.isArray(report.artifactFiles)) {
    for (const artifact of report.artifactFiles) {
      if (!isRecord(artifact) || typeof artifact.path !== "string") { fail("artifact file entry is malformed"); continue; }
      const absolutePath = resolveEvidencePath(runRoot, artifact.path, "artifact path", fail);
      if (!absolutePath) continue;
      try {
        const actual = sha256(fs.readFileSync(absolutePath));
        if (actual !== artifact.sha256) fail(`artifact SHA-256 mismatch for ${artifact.kind}`);
      } catch (error) { fail(`artifact ${artifact.kind} cannot be read (${error.code ?? error.message})`); }
    }
  } else fail("copied runtime artifact manifest is missing");

  const getText = relativePath => {
    const item = filesByPath.get(relativePath);
    return item ? item.bytes.toString("utf8") : "";
  };
  const getJson = relativePath => {
    const item = filesByPath.get(relativePath);
    return item ? loadJsonFile(item.absolutePath, relativePath, fail) : {};
  };
  const runner = parseJsonLines(getText("events/runner-events.jsonl"), "runner events", fail);
  const client = parseJsonLines(getText("events/client-events.jsonl"), "client events", fail);
  const controller = parseJsonLines(getText("events/controller-status.jsonl"), "controller status", fail);
  const children = parseJsonLines(getText("events/child-process-events.jsonl"), "child-process events", fail);
  validateRunnerEvents(runner, fail);
  validateControllerEvents(controller, fail);
  validateShutdownEvidence(
    runner,
    filesByPath.get("logs/server-a.log")?.bytes ?? Buffer.alloc(0),
    filesByPath.get("logs/server-b.log")?.bytes ?? Buffer.alloc(0),
    filesByPath.get("logs/postgresql-row-lock.log")?.bytes ?? Buffer.alloc(0),
    fail,
  );
  validateSourceManifest(getJson("evidence/source-manifest.json"), report, expectedArtifacts, trustedArtifactSources, fail);
  const clientIds = validateClientEvents(client, report.backends?.[1]?.name, fail);
  validateRecoveryReceiptLink(runner, clientIds, fail);
  validateServerLogs(filesByPath.get("logs/server-a.log")?.bytes ?? Buffer.alloc(0),
    filesByPath.get("logs/server-b.log")?.bytes ?? Buffer.alloc(0), runner, clientIds.uuidA, clientIds.uuidB, fail);
  validatePositions(getJson("evidence/backend-position.json"), report.backends?.[0]?.name, report.backends?.[1]?.name, fail);
  validateConfigObservations(getJson("evidence/config-observations.json"), report, fail);
  validateChildProcessEvents(children, report, runRoot, fail);
  const snapshots = validateSnapshots(runRoot, { ...report, filesByPath }, fail);

  const pgLog = getText("logs/postgresql.log");
  if ((pgLog.match(/database system is ready to accept connections/g) ?? []).length < 2) fail("isolated PostgreSQL log does not show initial start and recovery start");
  if (!pgLog.includes("database system is shut down")) fail("isolated PostgreSQL log does not show the outage shutdown");
  if (!getText("logs/redis.log").includes("Ready to accept connections")) fail("isolated Redis server did not log readiness");
  if (!getText("logs/network.log").includes("Network ready")) fail("PlugDev network log lacks the ready marker");

  if (failures.length > 0) throw new NetworkAuditError(failures);
  return {
    accepted: true,
    status: "PASS_BOUNDED_SUBCASE (E2)",
    runId: report.runId,
    endpoint: `${expectedEndpoint.product} ${expectedEndpoint.version}#${expectedEndpoint.build}`,
    reportSha256: sha256(fs.readFileSync(path.join(runRoot, "result.json"))),
    retainedFileCount: filesByPath.size,
    accountSnapshots: snapshots,
  };
}

export function auditRunDirectory(runRoot, endpointId, workspaceRoot) {
  const expected = T21_PAPER_NETWORK_ENDPOINTS[endpointId];
  if (!expected) throw new NetworkAuditError([`unknown endpoint ${String(endpointId)}`]);
  if (!workspaceRoot) throw new NetworkAuditError(["trusted workspace root is required"]);
  const absoluteRoot = path.resolve(runRoot);
  const reportPath = path.join(absoluteRoot, "result.json");
  const report = loadJsonFile(reportPath, "result.json", message => { throw new NetworkAuditError([message]); });
  const trustedArtifactSources = resolvePinnedArtifactSources(workspaceRoot, endpointId);
  return validateNetworkReport(report, absoluteRoot, expected, T21_PAPER_NETWORK_ARTIFACTS, trustedArtifactSources);
}

function main(argv) {
  const [runRoot, endpointId, workspaceRoot] = argv;
  if (!runRoot || !endpointId || !workspaceRoot || argv.length !== 3) {
    process.stderr.write("Usage: node t21-paper-network-auditor.mjs <C:-run-directory> <paper-1216-48|paper-263-200> <C:-workspace-root>\n");
    process.exitCode = 2;
    return;
  }
  if (process.platform === "win32" && path.parse(path.resolve(runRoot)).root.toLowerCase() !== "c:\\") {
    throw new NetworkAuditError(["run directory must be on C:"]);
  }
  const result = auditRunDirectory(runRoot, endpointId, workspaceRoot);
  process.stdout.write(`${JSON.stringify(result, null, 2)}\n`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  try { main(process.argv.slice(2)); }
  catch (error) {
    process.stderr.write(`${error?.stack ?? error}\n`);
    process.exitCode = 1;
  }
}

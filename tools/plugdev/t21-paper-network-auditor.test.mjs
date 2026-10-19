import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import crypto from "node:crypto";
import fsSync from "node:fs";
import fs from "node:fs/promises";
import path from "node:path";
import { randomUUID } from "node:crypto";
import test from "node:test";
import { fileURLToPath } from "node:url";
import {
  T21_FOLIA_NETWORK_ARTIFACT_PATHS,
  NetworkAuditError,
  T21_NETWORK_ENDPOINTS,
  T21_PAPER_NETWORK_ENDPOINTS,
  T21_PAPER_NETWORK_ARTIFACT_PATHS,
  T21_PAPER_NETWORK_HARNESS_SOURCES,
  T21_PAPER_NETWORK_TOOLCHAIN_PINS,
  collectDirectoryManifest,
  endpointServerArtifactKind,
  endpointServerJarFileName,
  networkArtifactPathsForEndpoint,
  resolvePinnedArtifactSources,
  validateNetworkReport,
} from "./t21-paper-network-auditor.mjs";

const FIXTURE_PARENT = "C:\\Users\\margo\\AppData\\Local\\Temp";
const WORKSPACE_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

test("auditor usage lists the current-maximum Canvas endpoint", () => {
  const auditorPath = fileURLToPath(new URL("./t21-paper-network-auditor.mjs", import.meta.url));
  const result = spawnSync(process.execPath, [auditorPath], { encoding: "utf8" });
  assert.equal(result.status, 2, result.stderr);
  assert.match(result.stderr, /canvas-263-984/);
});

function hash(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

async function writeJson(root, relativePath, value) {
  const absolute = path.join(root, ...relativePath.split("/"));
  await fs.mkdir(path.dirname(absolute), { recursive: true });
  await fs.writeFile(absolute, JSON.stringify(value, null, 2) + "\n", "utf8");
}

async function rewriteJsonEvidence(fixture, relativePath, update) {
  const absolute = path.join(fixture.root, ...relativePath.split("/"));
  const original = await fs.readFile(absolute, "utf8");
  const isJsonl = relativePath.endsWith(".jsonl");
  const value = isJsonl
    ? original.trim().split(/\r?\n/).map(line => JSON.parse(line))
    : JSON.parse(original);
  update(value);
  const text = isJsonl
    ? value.map(event => JSON.stringify(event)).join("\n") + "\n"
    : JSON.stringify(value, null, 2) + "\n";
  const bytes = Buffer.from(text, "utf8");
  await fs.writeFile(absolute, bytes);
  const manifest = fixture.report.files.find(entry => entry.path === relativePath);
  manifest.bytes = bytes.length;
  manifest.sha256 = hash(bytes);
  await writeJson(fixture.root, "result.json", fixture.report);
}

async function rewriteTextEvidence(fixture, relativePath, update) {
  const absolute = path.join(fixture.root, ...relativePath.split("/"));
  const original = await fs.readFile(absolute, "utf8");
  const text = update(original);
  const bytes = Buffer.from(text, "utf8");
  await fs.writeFile(absolute, bytes);
  const manifest = fixture.report.files.find(entry => entry.path === relativePath);
  manifest.bytes = bytes.length;
  manifest.sha256 = hash(bytes);
  await writeJson(fixture.root, "result.json", fixture.report);
}

async function createFixture({ endpointId = "paper-1216-48" } = {}) {
  const runId = "audit-" + randomUUID().replaceAll("-", "").slice(0, 16);
  const root = await fs.mkdtemp(path.join(FIXTURE_PARENT, `t21-paper-network-${runId}-`));
  const uuidA = "a2345678-1234-4234-8234-1234567890ab";
  const uuidB = "b2345678-1234-4234-8234-1234567890ab";
  const backendA = "backend-a";
  const backendB = "backend-b";
  const serverNameA = "t21-net-a";
  const serverNameB = "t21-net-b";
  const endpoint = T21_NETWORK_ENDPOINTS[endpointId];
  if (!endpoint) throw new Error(`unknown fixture endpoint ${endpointId}`);
  const shutdownMarker = endpoint.product !== "Paper"
    ? "[Region shutdown thread/INFO]: Stopping server"
    : "[Server thread/INFO]: Stopping server";
  const serverKind = endpoint.product === "Paper" ? "paper" : "server";
  const artifactPaths = networkArtifactPathsForEndpoint(endpoint);
  const serverStartupIdentity = endpoint.product === "Canvas"
    ? `[ServerMain/INFO]: [bootstrap] Loading Canvas ${endpoint.version}-${endpoint.build}-fixture`
    : `[Server thread/INFO]: This server is running ${endpoint.product} version ${endpoint.version}-${endpoint.build}-fixture`;
  const contents = {
    [serverKind]: Buffer.from(`fixture-${endpoint.product.toLowerCase()}-jar`),
    candidate: Buffer.from("fixture-candidate-jar"),
    acceptance: Buffer.from("fixture-acceptance-jar"),
    vaultUnlocked: Buffer.from("fixture-vaultunlocked-jar"),
    velocity: Buffer.from("fixture-velocity-jar"),
  };
  const hashes = Object.fromEntries(Object.entries(contents).map(([kind, bytes]) => [kind, hash(bytes)]));
  const trustedArtifactSources = {};
  const artifactFiles = [];
  for (const [kind, bytes] of Object.entries(contents)) {
    const sourcePath = path.join(root, "trusted-sources", kind + ".jar");
    await fs.mkdir(path.dirname(sourcePath), { recursive: true });
    await fs.writeFile(sourcePath, bytes);
    trustedArtifactSources[kind] = sourcePath;
  }
  for (const spec of artifactPaths) {
    const artifactBytes = contents[spec.kind];
    const absolute = path.join(root, ...spec.path.split("/"));
    await fs.mkdir(path.dirname(absolute), { recursive: true });
    await fs.writeFile(absolute, artifactBytes);
    artifactFiles.push({ kind: spec.kind, path: spec.path, bytes: artifactBytes.length, sha256: hashes[spec.kind] });
  }

  const row = (uuid, balance, version) => ({
    uuid,
    sqlBalance: balance,
    sqlVersion: version,
    redisBalance: balance,
    redisVersion: version,
  });
  const seed = { stage: "after-seed", sqlReachable: true, redisDatabase: 15, accounts: {
    AcceptanceA: row(uuidA, "100.00", 1),
    AcceptanceB: row(uuidB, "50.00", 1),
  } };
  const afterPay = { stage: "after-cross-pay", sqlReachable: true, redisDatabase: 15, accounts: {
    AcceptanceA: row(uuidA, "95.00", 2),
    AcceptanceB: row(uuidB, "55.00", 2),
  } };
  const outage = { stage: "during-sql-outage", sqlReachable: false, redisDatabase: 15, accounts: {
    AcceptanceA: row(uuidA, "92.00", 3),
    AcceptanceB: row(uuidB, "58.00", 3),
  } };
  const recovered = { stage: "after-sql-recovery", sqlReachable: true, redisDatabase: 15, accounts: {
    AcceptanceA: row(uuidA, "92.00", 3),
    AcceptanceB: row(uuidB, "58.00", 3),
  } };
  const final = { stage: "after-accepted-write-drain", sqlReachable: true, redisDatabase: 15, accounts: {
    AcceptanceA: row(uuidA, "94.00", 5),
    AcceptanceB: row(uuidB, "58.00", 3),
  } };

  const serverA = [
    serverStartupIdentity,
    "[Server thread/INFO]: Done (10.0s)! For help, type \"help\"",
    "AcceptanceA joined the game",
    "Failed to process batch to DB",
    "Re-queued failed DB write task (attempt 1) for player " + uuidA,
    "T15_ACCEPTED_WRITE scenario=logout index=1",
    "T15_ACCEPTED_WRITE scenario=logout index=2",
    "T15_PENDING_AT_BOUNDARY scenario=logout uuid=" + uuidA + " pending=2 pending-state=accepted-not-completed",
    "T15_LOGOUT_REQUEST uuid=" + uuidA,
    shutdownMarker,
    "T21_DRAIN owner=economy status=drained queued=0 outstanding=0 retry=0",
    "T21_DRAIN owner=database status=drained queued=0 inFlight=0 retry=0 stale=0 dropped=0",
    "Syncmoney disabled.",
  ].join("\n") + "\n";
  const serverAOutageStart = Buffer.byteLength(serverA.split("AcceptanceA joined the game\n")[0] + "AcceptanceA joined the game\n");
  const serverARecovered = Buffer.byteLength(serverA.split("T21_DRAIN owner=economy")[0]);
  const serverB = [
    serverStartupIdentity,
    "[Server thread/INFO]: Done (10.0s)! For help, type \"help\"",
    "AcceptanceB joined the game",
    "AcceptanceB joined the game",
    shutdownMarker,
    "T21_DRAIN owner=economy status=drained queued=0 outstanding=0 retry=0",
    "T21_DRAIN owner=database status=drained queued=0 inFlight=0 retry=0 stale=0 dropped=0",
    "Syncmoney disabled.",
  ].join("\n") + "\n";
  const serverBOutageStart = Buffer.byteLength(serverB.split("AcceptanceB joined the game\n").slice(0, 2).join("AcceptanceB joined the game\n") + "AcceptanceB joined the game\n");
  const serverBRecovered = Buffer.byteLength(serverB.split("T21_DRAIN owner=economy")[0]);
  const serverAShutdownStarted = Buffer.byteLength(serverA.split(shutdownMarker + "\n")[0] + shutdownMarker + "\n");
  const serverBShutdownStarted = Buffer.byteLength(serverB.split(shutdownMarker + "\n")[0] + shutdownMarker + "\n");
  const evidenceTexts = {
    "logs/server-a.log": serverA,
    "logs/server-b.log": serverB,
    "logs/postgresql.log": [
      "database system is ready to accept connections",
      "database system is shut down",
      "database system is ready to accept connections",
    ].join("\n"),
    "logs/postgresql-row-lock.log": "T21_ROW_LOCK_READY\nT21_ROW_LOCK_RELEASED_FOR_SHUTDOWN\n",
    "logs/redis.log": "Ready to accept connections\n",
    "logs/network.log": "Network ready\n",
  };
  const eventObjects = {
    "events/runner-events.jsonl": [
      "postgres_ready_initial", "redis_ready", "network_ready", "clients_ready",
      "accounts_seeded", "backend_position_verified", "cross_server_pay_pass",
      "reconnect_b_pass", "postgres_outage_begin", "postgres_stopped_for_outage",
      "outage_pay_submitted", "outage_memory_state_observed", "outage_sql_retry_observed",
      "postgres_restarted", "client_recovery_barrier", "outage_pay_receipt_after_recovery",
      "sql_recovery_parity_pass", "accepted_write_row_lock_acquired", "accepted_write_logout_pass", "client_stop_pass",
      "network_shutdown_requested", "shutdown_drain_lock_held",
      "accepted_write_lock_released_for_shutdown", "network_exited", "final_sql_redis_parity_pass",
      "redis_stopped", "postgres_stopped_final", "cleanup_verified",
    ].map(type => {
      const extra = {};
      if (type === "postgres_outage_begin") Object.assign(extra, {
        serverLogByteOffsets: { backendA: serverAOutageStart, backendB: serverBOutageStart },
      });
      if (type === "postgres_stopped_for_outage") Object.assign(extra, { exitCode: 0, runOwned: true, portClosed: true });
      if (type === "outage_pay_submitted") Object.assign(extra, {
        id: "db-outage-pay", sender: "AcceptanceA", recipient: "AcceptanceB", amount: "3.00",
      });
      if (type === "outage_memory_state_observed") Object.assign(extra, {
        stage: "during-sql-outage", sqlReachable: false,
      });
      if (type === "outage_sql_retry_observed") Object.assign(extra, { retryCount: 1, accounts: [uuidA] });
      if (type === "postgres_restarted") Object.assign(extra, {
        exitCode: 0, runOwned: true, ready: true,
        serverLogByteOffsets: { backendA: serverARecovered, backendB: serverBRecovered },
      });
      if (type === "outage_pay_receipt_after_recovery") Object.assign(extra, {
        id: "db-outage-pay", receiptObserved: true,
        receiptEventSequence: 16,
        senderReceiptEventSequence: 14,
        receiverReceiptEventSequence: 15,
        recoveryBarrierEventSequence: 13,
      });
      if (type === "client_recovery_barrier") Object.assign(extra, {
        actionId: "postgres-recovery-barrier", clientEventSequence: 13,
      });
      if (type === "accepted_write_logout_pass") Object.assign(extra, {
        pending: 2, rowLockHeld: true, rowLockReleased: false,
      });
      if (type === "shutdown_drain_lock_held") Object.assign(extra, {
        pending: 2, rowLockHeld: true,
        serverLogByteOffsets: { backendA: serverAShutdownStarted, backendB: serverBShutdownStarted },
      });
      if (type === "accepted_write_lock_released_for_shutdown") Object.assign(extra, {
        rowLockReleased: true,
        rowLockReleaseMarkerObserved: true,
        rowLockReleaseMarker: "T21_ROW_LOCK_RELEASED_FOR_SHUTDOWN",
      });
      if (type === "cleanup_verified") Object.assign(extra, {
        forcedCleanup: false, evidenceComplete: true, runOwnedProcessesRemaining: 0, runOwnedPortsOpen: 0, errors: [],
      });
      return { type, ...extra };
    }),
    "events/client-events.jsonl": [
      { type: "bot_login", name: "AcceptanceA", uuid: uuidA },
      { type: "bot_login", name: "AcceptanceB", uuid: uuidB },
      { type: "hop_submitted", name: "AcceptanceB", target: backendB, uuid: uuidB },
      { type: "hop_pass", name: "AcceptanceB", target: backendB, uuid: uuidB, serverReply: `Connecting you to ${backendB}...`, routeConfirmation: "velocity-message" },
      { type: "pay_submitted", id: "cross-server-pay", sender: "AcceptanceA", target: "AcceptanceB", amount: "5.00" },
      { type: "bot_chat", name: "AcceptanceA", text: "Transaction Authorized. 5.00 routed to AcceptanceB" },
      { type: "bot_chat", name: "AcceptanceB", text: "Acquired +5.00 from AcceptanceA" },
      { type: "pay_response_pass", id: "cross-server-pay", sender: "AcceptanceA", recipient: "AcceptanceB", amount: "5.00",
        senderText: "Transaction Authorized. 5.00 routed to AcceptanceB", receiverText: "Acquired +5.00 from AcceptanceA",
        senderReceiptEventSequence: 6, receiverReceiptEventSequence: 7 },
      { type: "bot_login", name: "AcceptanceB", uuid: uuidB },
      { type: "reconnect_submitted", name: "AcceptanceB", uuid: uuidB, priorUuid: uuidB, target: backendB },
      { type: "reconnect_pass", name: "AcceptanceB", uuid: uuidB, priorUuid: uuidB, target: backendB, serverReply: `Connecting you to ${backendB}...`, routeConfirmation: "velocity-message" },
      { type: "pay_submitted", id: "db-outage-pay", sender: "AcceptanceA", target: "AcceptanceB", amount: "3.00" },
      { type: "recovery_barrier", id: "postgres-recovery-barrier" },
      { type: "bot_chat", name: "AcceptanceA", text: "Transaction Authorized. 3.00 routed to AcceptanceB" },
      { type: "bot_chat", name: "AcceptanceB", text: "Acquired +3.00 from AcceptanceA" },
      { type: "pay_response_pass", id: "db-outage-pay", sender: "AcceptanceA", recipient: "AcceptanceB", amount: "3.00",
        senderText: "Transaction Authorized. 3.00 routed to AcceptanceB", receiverText: "Acquired +3.00 from AcceptanceA",
        senderReceiptEventSequence: 14, receiverReceiptEventSequence: 15 },
      { type: "lifecycle_logout_pass", name: "AcceptanceA", uuid: uuidA },
      { type: "client_stop_pass", clients: ["AcceptanceA", "AcceptanceB"] },
    ].map((event, index) => ({ seq: index + 1, ...event })),
    "events/controller-status.jsonl": [
      { state: "network_ready", cliPid: 7001 },
      { state: "network_stop_requested", actionId: "stop-network" },
      { state: "network_exited", exitCode: 0, signal: null, networkReady: true, stopRequested: true },
    ],
    "events/child-process-events.jsonl": [7101, 7102, 7103].flatMap((pid, index) => {
      const jarPath = path.join(root, "workspace", ".plugdev", "network",
        index < 2 ? "backends" : "proxy",
        ...(index < 2 ? [["backend-a", "backend-b"][index], "server.jar"] : ["velocity.jar"]));
      const args = ["java.exe", "-jar", jarPath];
      const executable = "C:/Program Files/Java/jdk-25.0.4/bin/java.exe";
      return [
        { event: "spawn", pid, executable, args },
        { event: "exit", pid, executable, args, code: 0, signal: null },
      ];
    }),
    "evidence/backend-position.json": {
      initial: { backendA: "AcceptanceA", backendB: "AcceptanceB", backendAName: backendA, backendBName: backendB },
      afterReconnect: { backendA: "AcceptanceA", backendB: "AcceptanceB", backendAName: backendA, backendBName: backendB },
    },
    "evidence/config-observations.json": {
      A: { serverName: serverNameA, mode: "SYNC", dbEnabled: true, pubsubEnabled: true, redisEnabled: true,
        databaseType: "postgresql", databaseHost: "127.0.0.1", databasePort: 25432, redisHost: "127.0.0.1", redisPort: 26379, redisDatabase: 15 },
      B: { serverName: serverNameB, mode: "SYNC", dbEnabled: true, pubsubEnabled: true, redisEnabled: true,
        databaseType: "postgresql", databaseHost: "127.0.0.1", databasePort: 25432, redisHost: "127.0.0.1", redisPort: 26379, redisDatabase: 15 },
    },
    "snapshots/after-seed.json": seed,
    "snapshots/after-cross-pay.json": afterPay,
    "snapshots/during-sql-outage.json": outage,
    "snapshots/after-sql-recovery.json": recovered,
    "snapshots/after-accepted-write-drain.json": final,
  };

  const allFiles = {};
  for (const [relativePath, value] of Object.entries(eventObjects)) {
    const text = relativePath.endsWith(".jsonl")
      ? value.map(event => JSON.stringify(event)).join("\n") + "\n"
      : JSON.stringify(value, null, 2) + "\n";
    allFiles[relativePath] = text;
  }
  for (const [relativePath, text] of Object.entries(evidenceTexts)) allFiles[relativePath] = text;

  const expectedEndpoint = {
    ...endpoint,
    serverJarSha256: hashes[serverKind],
  };
  const expectedArtifacts = {
    candidateSha256: hashes.candidate,
    acceptanceSha256: hashes.acceptance,
    vaultUnlockedSha256: hashes.vaultUnlocked,
    velocitySha256: hashes.velocity,
  };
  const sourceCommit = execFileSync("git", ["rev-parse", "HEAD"], { cwd: WORKSPACE_ROOT, encoding: "utf8" }).trim();
  const sourceWorkingTreeDirty = execFileSync("git", ["status", "--porcelain", "--untracked-files=all"], {
    cwd: WORKSPACE_ROOT,
    encoding: "utf8",
  }).trim().length > 0;
  const pins = {
    ...T21_PAPER_NETWORK_TOOLCHAIN_PINS,
    velocity: {
      ...T21_PAPER_NETWORK_TOOLCHAIN_PINS.velocity,
      sha256: hashes.velocity,
      checkedAt: "2026-10-19T00:00:00.000Z",
    },
  };
  const harness = {};
  for (const [name, relativePath] of Object.entries(T21_PAPER_NETWORK_HARNESS_SOURCES)) {
    const absolutePath = path.resolve(WORKSPACE_ROOT, ...relativePath.split("/"));
    const bytes = await fs.readFile(absolutePath);
    harness[name] = { path: absolutePath, bytes: bytes.length, sha256: hash(bytes) };
  }
  const sourceArtifacts = Object.fromEntries(Object.entries(trustedArtifactSources).map(([kind, sourcePath]) => {
    const bytes = fsSync.readFileSync(sourcePath);
    return [kind, { path: sourcePath, bytes: bytes.length, sha256: hash(bytes) }];
  }));
  const sourceManifest = {
    schemaVersion: endpoint.product === "Paper" ? 1 : 2,
    createdAt: "2026-10-19T00:00:00.000Z",
    workspaceRoot: WORKSPACE_ROOT,
    endpoint: endpoint.product === "Paper" ? undefined : { ...expectedEndpoint },
    sourceCommit,
    sourceWorkingTreeDirty,
    harness,
    artifacts: sourceArtifacts,
    plugdev: {
      root: pins.plugdev.root,
      commit: pins.plugdev.commit,
      version: pins.plugdev.version,
      dirty: pins.plugdev.dirty,
      cliPath: pins.plugdev.cliPath,
      cliSha256: pins.plugdev.cliSha256,
      packageJson: { ...pins.plugdev.packageJson },
      distRoot: pins.plugdev.distRoot,
      distributionFiles: collectDirectoryManifest(pins.plugdev.distRoot),
    },
    mineflayer: { ...pins.mineflayer },
    nodeVersion: pins.node.version,
    nodeExecutable: { ...pins.node.executable },
    javaVersion: "openjdk version \"25.0.4\"",
    javaExecutable: { ...pins.java.executable },
    postgres: { ...pins.postgres },
    redis: { ...pins.redis },
    velocityMetadata: { ...pins.velocity },
  };
  allFiles["evidence/source-manifest.json"] = JSON.stringify(sourceManifest, null, 2) + "\n";
  for (const [relativePath, text] of Object.entries(allFiles)) {
    const absolute = path.join(root, ...relativePath.split("/"));
    await fs.mkdir(path.dirname(absolute), { recursive: true });
    await fs.writeFile(absolute, text, "utf8");
  }
  const report = {
    schemaVersion: 1,
    status: "RUN_COMPLETED",
    runId,
    sourceCommit,
    sourceWorkingTreeDirty,
    endpoint: { ...expectedEndpoint },
    artifacts: {
      serverJarSha256: hashes[serverKind],
      candidateSha256: hashes.candidate,
      acceptanceSha256: hashes.acceptance,
      vaultUnlockedSha256: hashes.vaultUnlocked,
      velocitySha256: hashes.velocity,
    },
    backends: [backendA, backendB].map((name, index) => ({
      name,
      serverName: index === 0 ? serverNameA : serverNameB,
      [endpoint.product === "Paper" ? "paperJarSha256" : "serverJarSha256"]: hashes[serverKind],
      candidateJarSha256: hashes.candidate,
      acceptanceJarSha256: hashes.acceptance,
      vaultUnlockedJarSha256: hashes.vaultUnlocked,
    })),
    runtime: {
      javaMajor: 25,
      javaVersion: "openjdk version \"25.0.4\"",
      nodeVersion: pins.node.version,
      plugdevVersion: "1.3.0",
      plugdevDirty: true,
    },
    services: {
      postgres: { version: "18.1", bind: "127.0.0.1", isolated: true, port: 25432 },
      redis: { version: "8.10.1", bind: "127.0.0.1", isolated: true, port: 26379, database: 15 },
    },
    network: {
      proxyBind: "127.0.0.1",
      proxyPort: 25565,
      backendPorts: { "backend-a": 25566, "backend-b": 25567 },
      rconPorts: { "backend-a": 25568, "backend-b": 25569 },
      shutdownBridgePort: 25570,
      velocity: { ...pins.velocity },
    },
    files: [],
    artifactFiles,
  };
  for (const [relativePath, content] of Object.entries(allFiles)) {
    const bytes = Buffer.from(content, "utf8");
    report.files.push({ path: relativePath, bytes: bytes.length, sha256: hash(bytes) });
  }
  for (const artifact of artifactFiles) {
    const bytes = await fs.readFile(path.join(root, ...artifact.path.split("/")));
    report.files.push({ path: artifact.path, bytes: bytes.length, sha256: hash(bytes) });
  }
  await writeJson(root, "result.json", report);
  return {
    root,
    workspaceRoot: root,
    report,
    endpointId,
    expectedEndpoint,
    expectedArtifacts,
    trustedArtifactSources,
    trustedToolchainPins: pins,
    close: () => fs.rm(root, { recursive: true, force: true }),
  };
}

function audit(fixture) {
  return validateNetworkReport(
    fixture.report,
    fixture.root,
    fixture.expectedEndpoint,
    fixture.expectedArtifacts,
    fixture.trustedArtifactSources,
    fixture.workspaceRoot,
    fixture.trustedToolchainPins,
  );
}

test("keeps the Paper endpoint export Paper-only and pins Folia server artifact paths", () => {
  assert.deepEqual(Object.keys(T21_PAPER_NETWORK_ENDPOINTS), ["paper-1216-48", "paper-263-200"]);
  assert.equal(T21_NETWORK_ENDPOINTS["folia-1216-6"].product, "Folia");
  const foliaServerCopies = T21_FOLIA_NETWORK_ARTIFACT_PATHS
    .filter(artifact => artifact.kind === "server")
    .map(artifact => artifact.path);
  assert.deepEqual(foliaServerCopies, [
    "inputs/folia.jar",
    "workspace/.plugdev/network/backends/backend-a/server.jar",
    "workspace/.plugdev/network/backends/backend-b/server.jar",
  ]);
});

test("accepts the minimum Folia network report using generic server artifact identity", async () => {
  const fixture = await createFixture({ endpointId: "folia-1216-6" });
  try {
    assert.equal(fixture.report.endpoint.product, "Folia");
    assert.equal(fixture.report.endpoint.version, "1.21.6");
    assert.equal(fixture.report.endpoint.build, 6);
    assert.equal(fixture.report.backends[0].paperJarSha256, undefined);
    assert.equal(fixture.report.backends[0].serverJarSha256, fixture.report.endpoint.serverJarSha256);
    let result;
    let error;
    try { result = audit(fixture); }
    catch (caught) { error = caught; }
    assert.equal(error, undefined, error?.message);
    assert.equal(result.status, "PASS_BOUNDED_SUBCASE (E2)");
    assert.equal(result.endpoint, "Folia 1.21.6#6");
  } finally {
    await fixture.close();
  }
});

test("accepts the current-maximum Folia network report with the exact endpoint identity", async () => {
  const fixture = await createFixture({ endpointId: "folia-263-3" });
  try {
    assert.equal(fixture.report.endpoint.product, "Folia");
    assert.equal(fixture.report.endpoint.version, "26.3");
    assert.equal(fixture.report.endpoint.build, 3);
    assert.equal(fixture.report.backends[0].paperJarSha256, undefined);
    assert.equal(fixture.report.backends[0].serverJarSha256, fixture.report.endpoint.serverJarSha256);
    const result = audit(fixture);
    assert.equal(result.status, "PASS_BOUNDED_SUBCASE (E2)");
    assert.equal(result.endpoint, "Folia 26.3#3");
  } finally {
    await fixture.close();
  }
});

test("accepts the minimum Canvas network report with its exact bootstrap identity", async () => {
  const fixture = await createFixture({ endpointId: "canvas-1218-603" });
  try {
    assert.equal(fixture.report.endpoint.product, "Canvas");
    assert.equal(fixture.report.endpoint.version, "1.21.8");
    assert.equal(fixture.report.endpoint.build, 603);
    assert.equal(fixture.report.backends[0].paperJarSha256, undefined);
    assert.equal(fixture.report.backends[0].serverJarSha256, fixture.report.endpoint.serverJarSha256);
    assert.equal(endpointServerArtifactKind(fixture.expectedEndpoint), "server");
    assert.equal(endpointServerJarFileName(fixture.expectedEndpoint), "canvas.jar");
    assert.deepEqual(networkArtifactPathsForEndpoint(fixture.expectedEndpoint).filter(entry => entry.kind === "server").map(entry => entry.path), [
      "inputs/canvas.jar",
      "workspace/.plugdev/network/backends/backend-a/server.jar",
      "workspace/.plugdev/network/backends/backend-b/server.jar",
    ]);
    const result = audit(fixture);
    assert.equal(result.status, "PASS_BOUNDED_SUBCASE (E2)");
    assert.equal(result.endpoint, "Canvas 1.21.8#603");
  } finally {
    await fixture.close();
  }
});


test("accepts the current-maximum Canvas network report with its exact endpoint identity", async () => {
  const fixture = await createFixture({ endpointId: "canvas-263-984" });
  try {
    assert.equal(fixture.report.endpoint.product, "Canvas");
    assert.equal(fixture.report.endpoint.version, "26.3");
    assert.equal(fixture.report.endpoint.build, 984);
    assert.equal(T21_NETWORK_ENDPOINTS["canvas-263-984"].serverJarSha256,
      "0b94c4cba3e415586dddb69915b5aacf250636c62d058f4651c1ab34eaf265fa");
    assert.equal(fixture.report.endpoint.serverJarSha256, fixture.expectedEndpoint.serverJarSha256);
    assert.equal(fixture.report.backends[0].paperJarSha256, undefined);
    assert.equal(fixture.report.backends[0].serverJarSha256, fixture.report.endpoint.serverJarSha256);
    assert.equal(endpointServerArtifactKind(fixture.expectedEndpoint), "server");
    assert.equal(endpointServerJarFileName(fixture.expectedEndpoint), "canvas.jar");
    assert.deepEqual(networkArtifactPathsForEndpoint(fixture.expectedEndpoint)
      .filter(entry => entry.kind === "server").map(entry => entry.path), [
      "inputs/canvas.jar",
      "workspace/.plugdev/network/backends/backend-a/server.jar",
      "workspace/.plugdev/network/backends/backend-b/server.jar",
    ]);
    const result = audit(fixture);
    assert.equal(result.status, "PASS_BOUNDED_SUBCASE (E2)");
    assert.equal(result.endpoint, "Canvas 26.3#984");
  } finally {
    await fixture.close();
  }
});

test("rejects a current-maximum Canvas report when its endpoint build pin changes", async () => {
  const fixture = await createFixture({ endpointId: "canvas-263-984" });
  try {
    fixture.report.endpoint.build = 983;
    assert.throws(
      () => audit(fixture),
      error => error instanceof NetworkAuditError && /endpoint build pin mismatch/i.test(error.message),
    );
  } finally {
    await fixture.close();
  }
});

test("rejects a current-maximum Canvas server log with the wrong build identity", async () => {
  const fixture = await createFixture({ endpointId: "canvas-263-984" });
  try {
    await rewriteTextEvidence(fixture, "logs/server-b.log", text => text.replace(
      "[bootstrap] Loading Canvas 26.3-984-fixture",
      "[bootstrap] Loading Canvas 26.3-983-fixture",
    ));
    assert.throws(
      () => audit(fixture),
      error => error instanceof NetworkAuditError && /backend B log lacks exact Canvas 26\.3#984 startup identity marker/i.test(error.message),
    );
  } finally {
    await fixture.close();
  }
});

test("rejects a current-maximum Canvas run when its copied server artifact changes", async () => {
  const fixture = await createFixture({ endpointId: "canvas-263-984" });
  try {
    await fs.writeFile(path.join(fixture.root, "inputs", "canvas.jar"), Buffer.from("tampered-current-maximum-canvas-server-jar"));
    assert.throws(
      () => audit(fixture),
      error => error instanceof NetworkAuditError && /artifact copy SHA-256 mismatch for inputs\/canvas\.jar/i.test(error.message),
    );
  } finally {
    await fixture.close();
  }
});

test("rejects a minimum Canvas server log with the wrong build identity", async () => {
  const fixture = await createFixture({ endpointId: "canvas-1218-603" });
  try {
    await rewriteTextEvidence(fixture, "logs/server-b.log", text => text.replace(
      "[bootstrap] Loading Canvas 1.21.8-603-fixture",
      "[bootstrap] Loading Canvas 1.21.8-604-fixture",
    ));
    assert.throws(
      () => audit(fixture),
      error => error instanceof NetworkAuditError && /backend B log lacks exact Canvas 1\.21\.8#603 startup identity marker/i.test(error.message),
    );
  } finally {
    await fixture.close();
  }
});

test("rejects a minimum Canvas run when its copied server artifact changes", async () => {
  const fixture = await createFixture({ endpointId: "canvas-1218-603" });
  try {
    await fs.writeFile(path.join(fixture.root, "inputs", "canvas.jar"), Buffer.from("tampered-canvas-server-jar"));
    assert.throws(
      () => audit(fixture),
      error => error instanceof NetworkAuditError && /artifact copy SHA-256 mismatch for inputs\/canvas\.jar/i.test(error.message),
    );
  } finally {
    await fixture.close();
  }
});

test("rejects a current-maximum Folia server log with the wrong build identity", async () => {
  const fixture = await createFixture({ endpointId: "folia-263-3" });
  try {
    await rewriteTextEvidence(fixture, "logs/server-b.log", text => text.replace(
      "This server is running Folia version 26.3-3-fixture",
      "This server is running Folia version 26.3-4-fixture",
    ));
    assert.throws(
      () => audit(fixture),
      error => error instanceof NetworkAuditError && /backend B log lacks exact Folia 26\.3#3 startup identity marker/i.test(error.message),
    );
  } finally {
    await fixture.close();
  }
});

test("rejects a Folia run when its copied server artifact changes", async () => {
  const fixture = await createFixture({ endpointId: "folia-1216-6" });
  try {
    await fs.writeFile(path.join(fixture.root, "inputs", "folia.jar"), Buffer.from("tampered-folia-server-jar"));
    assert.throws(
      () => audit(fixture),
      error => error instanceof NetworkAuditError && /artifact copy SHA-256 mismatch for inputs\/folia\.jar/i.test(error.message),
    );
  } finally {
    await fixture.close();
  }
});

test("rejects a Folia network report when either endpoint or backend server pin changes", async () => {
  const fixture = await createFixture({ endpointId: "folia-1216-6" });
  try {
    const report = structuredClone(fixture.report);
    report.endpoint.serverJarSha256 = "0".repeat(64);
    report.artifacts.serverJarSha256 = "0".repeat(64);
    for (const backend of report.backends) backend.serverJarSha256 = "0".repeat(64);
    let error;
    try {
      validateNetworkReport(
        report,
        fixture.root,
        fixture.expectedEndpoint,
        fixture.expectedArtifacts,
        fixture.trustedArtifactSources,
        fixture.workspaceRoot,
        fixture.trustedToolchainPins,
      );
    } catch (caught) { error = caught; }
    assert.ok(error instanceof NetworkAuditError, "the changed Folia server pin must fail closed");
    assert.match(error.message, /serverJarSha256|server hash|server pin/i);
  } finally {
    await fixture.close();
  }
});

test("rejects a Folia source manifest whose endpoint identity differs from the selected pin", async () => {
  const fixture = await createFixture({ endpointId: "folia-1216-6" });
  try {
    await rewriteJsonEvidence(fixture, "evidence/source-manifest.json", manifest => {
      manifest.endpoint.build = 7;
    });
    assert.throws(
      () => audit(fixture),
      error => error instanceof NetworkAuditError && /source manifest endpoint build pin mismatch/i.test(error.message),
    );
  } finally {
    await fixture.close();
  }
});

test("rejects a Folia server log with a Paper startup identity", async () => {
  const fixture = await createFixture({ endpointId: "folia-1216-6" });
  try {
    await rewriteTextEvidence(fixture, "logs/server-b.log", text => text.replace(
      "This server is running Folia version 1.21.6-6-fixture",
      "This server is running Paper version 1.21.6-6-fixture",
    ));
    assert.throws(
      () => audit(fixture),
      error => error instanceof NetworkAuditError && /backend B log lacks exact Folia 1\.21\.6#6 startup identity marker/i.test(error.message),
    );
  } finally {
    await fixture.close();
  }
});

test("rejects a Folia server log with the wrong build startup identity", async () => {
  const fixture = await createFixture({ endpointId: "folia-1216-6" });
  try {
    await rewriteTextEvidence(fixture, "logs/server-a.log", text => text.replace(
      "This server is running Folia version 1.21.6-6-fixture",
      "This server is running Folia version 1.21.6-7-fixture",
    ));
    assert.throws(
      () => audit(fixture),
      error => error instanceof NetworkAuditError && /backend A log lacks exact Folia 1\.21\.6#6 startup identity marker/i.test(error.message),
    );
  } finally {
    await fixture.close();
  }
});

test("rejects a Folia server log that only has Paper's shutdown marker", async () => {
  const fixture = await createFixture({ endpointId: "folia-1216-6" });
  try {
    const foliaMarker = "[Region shutdown thread/INFO]: Stopping server";
    const paperMarker = "[Server thread/INFO]: Stopping server";
    await rewriteTextEvidence(fixture, "logs/server-a.log", text => text.replace(
      foliaMarker,
      paperMarker,
    ));
    await rewriteTextEvidence(fixture, "logs/server-b.log", text => text.replace(
      foliaMarker,
      paperMarker,
    ));
    const offsetDelta = Buffer.byteLength(paperMarker) - Buffer.byteLength(foliaMarker);
    await rewriteJsonEvidence(fixture, "events/runner-events.jsonl", events => {
      const held = events.find(event => event.type === "shutdown_drain_lock_held");
      held.serverLogByteOffsets.backendA += offsetDelta;
      held.serverLogByteOffsets.backendB += offsetDelta;
    });
    assert.throws(
      () => audit(fixture),
      error => error instanceof NetworkAuditError && /backendA log lacks the pinned Folia server-stop marker/i.test(error.message),
    );
  } finally {
    await fixture.close();
  }
});

test("accepts the pinned report with byte-scoped SQL retry evidence and no synthetic server markers", async () => {
  const fixture = await createFixture();
  try {
    const result = audit(fixture);
    assert.equal(result.accepted, true);
    assert.equal(result.status, "PASS_BOUNDED_SUBCASE (E2)");
    assert.equal(result.assertions, undefined);
  } finally {
    await fixture.close();
  }
});

test("rejects SQL retry evidence outside the runner-recorded outage byte window", async () => {
  const fixture = await createFixture();
  try {
    await rewriteJsonEvidence(fixture, "events/runner-events.jsonl", events => {
      events.find(event => event.type === "postgres_restarted").serverLogByteOffsets.backendA =
        events.find(event => event.type === "postgres_outage_begin").serverLogByteOffsets.backendA;
    });
    assert.throws(
      () => audit(fixture),
      error => error instanceof NetworkAuditError && /SQL batch failure.*outage|SQL retry.*outage/i.test(error.message),
    );
  } finally {
    await fixture.close();
  }
});

test("rejects an outage byte offset outside the retained server log", async () => {
  const fixture = await createFixture();
  try {
    await rewriteJsonEvidence(fixture, "events/runner-events.jsonl", events => {
      events.find(event => event.type === "postgres_outage_begin").serverLogByteOffsets.backendA = Number.MAX_SAFE_INTEGER;
    });
    assert.throws(
      () => audit(fixture),
      error => error instanceof NetworkAuditError && /byte offset/i.test(error.message),
    );
  } finally {
    await fixture.close();
  }
});

test("rejects an outage pay receipt recorded before PostgreSQL recovery", async () => {
  const fixture = await createFixture();
  try {
    await rewriteJsonEvidence(fixture, "events/runner-events.jsonl", events => {
      const receiptIndex = events.findIndex(event => event.type === "outage_pay_receipt_after_recovery");
      const restartIndex = events.findIndex(event => event.type === "postgres_restarted");
      const [receipt] = events.splice(receiptIndex, 1);
      events.splice(restartIndex, 0, receipt);
    });
    assert.throws(() => audit(fixture), error =>
      error instanceof NetworkAuditError && /misordered outage_pay_receipt_after_recovery/i.test(error.message));
  } finally {
    await fixture.close();
  }
});

test("rejects an outage receipt whose real client event sequence predates the recovery barrier", async () => {
  const fixture = await createFixture();
  try {
    let receiptSequence;
    let barrierSequence;
    await rewriteJsonEvidence(fixture, "events/client-events.jsonl", events => {
      const receiptIndex = events.findIndex(event => event.type === "pay_response_pass" && event.id === "db-outage-pay");
      const barrierIndex = events.findIndex(event => event.type === "recovery_barrier");
      const [barrier] = events.splice(barrierIndex, 1);
      const currentReceiptIndex = events.findIndex(event => event.type === "pay_response_pass" && event.id === "db-outage-pay");
      events.splice(currentReceiptIndex + 1, 0, barrier);
      events.forEach((event, index) => { event.seq = index + 1; });
      receiptSequence = events.find(event => event.type === "pay_response_pass" && event.id === "db-outage-pay").seq;
      barrierSequence = events.find(event => event.type === "recovery_barrier").seq;
      assert(receiptSequence < barrierSequence, `fixture should place receipt ${receiptIndex} before barrier ${barrierIndex}`);
    });
    await rewriteJsonEvidence(fixture, "events/runner-events.jsonl", events => {
      events.find(event => event.type === "client_recovery_barrier").clientEventSequence = barrierSequence;
      const receipt = events.find(event => event.type === "outage_pay_receipt_after_recovery");
      receipt.receiptEventSequence = receiptSequence;
      receipt.recoveryBarrierEventSequence = barrierSequence;
    });
    assert.throws(() => audit(fixture), error =>
      error instanceof NetworkAuditError && /outage payment completion did not follow the PostgreSQL recovery barrier/i.test(error.message));
  } finally {
    await fixture.close();
  }
});

test("accepts an outage pay completion after recovery when its exact chat receipts arrived earlier", async () => {
  const fixture = await createFixture();
  try {
    let senderReceiptSequence;
    let receiverReceiptSequence;
    let barrierSequence;
    let passSequence;
    await rewriteJsonEvidence(fixture, "events/client-events.jsonl", events => {
      const senderIndex = events.findIndex(event => event.type === "bot_chat" && event.name === "AcceptanceA" && event.text.includes("3.00 routed"));
      const receiverIndex = events.findIndex(event => event.type === "bot_chat" && event.name === "AcceptanceB" && event.text.includes("+3.00"));
      const [sender] = events.splice(senderIndex, 1);
      const [receiver] = events.splice(events.findIndex(event => event.type === "bot_chat" && event.name === "AcceptanceB" && event.text.includes("+3.00")), 1);
      const barrierIndex = events.findIndex(event => event.type === "recovery_barrier");
      events.splice(barrierIndex, 0, sender, receiver);
      events.forEach((event, index) => { event.seq = index + 1; });
      senderReceiptSequence = sender.seq;
      receiverReceiptSequence = receiver.seq;
      barrierSequence = events.find(event => event.type === "recovery_barrier").seq;
      const pass = events.find(event => event.type === "pay_response_pass" && event.id === "db-outage-pay");
      pass.senderReceiptEventSequence = senderReceiptSequence;
      pass.receiverReceiptEventSequence = receiverReceiptSequence;
      passSequence = pass.seq;
      assert(senderReceiptSequence < barrierSequence && receiverReceiptSequence < barrierSequence && passSequence > barrierSequence);
    });
    await rewriteJsonEvidence(fixture, "events/runner-events.jsonl", events => {
      events.find(event => event.type === "client_recovery_barrier").clientEventSequence = barrierSequence;
      const receipt = events.find(event => event.type === "outage_pay_receipt_after_recovery");
      receipt.receiptEventSequence = passSequence;
      receipt.senderReceiptEventSequence = senderReceiptSequence;
      receipt.receiverReceiptEventSequence = receiverReceiptSequence;
      receipt.recoveryBarrierEventSequence = barrierSequence;
    });
    const result = audit(fixture);
    assert.equal(result.accepted, true);
    assert.equal(result.status, "PASS_BOUNDED_SUBCASE (E2)");
  } finally {
    await fixture.close();
  }
});

test("rejects successful-run cleanup evidence without a completed liveness audit", async () => {
  const fixture = await createFixture();
  try {
    await rewriteJsonEvidence(fixture, "events/runner-events.jsonl", events => {
      delete events.find(event => event.type === "cleanup_verified").evidenceComplete;
    });
    assert.throws(() => audit(fixture), error =>
      error instanceof NetworkAuditError && /cleanup was forced, incomplete, or left owned processes\/ports/i.test(error.message));
  } finally {
    await fixture.close();
  }
});

test("rejects source artifact drift even when the report and retained copies keep their original hashes", async () => {
  const fixture = await createFixture();
  try {
    await fs.writeFile(fixture.trustedArtifactSources.candidate, "changed candidate");
    assert.throws(() => audit(fixture), error =>
      error instanceof NetworkAuditError && /candidate.*source.*SHA-256/i.test(error.message));
  } finally {
    await fixture.close();
  }
});

test("rejects an empty artifact copy manifest", async () => {
  const fixture = await createFixture();
  try {
    fixture.report.artifactFiles = [];
    assert.throws(() => audit(fixture), error =>
      error instanceof NetworkAuditError && /artifact.*manifest|artifact.*missing/i.test(error.message));
  } finally {
    await fixture.close();
  }
});

test("rejects Java child evidence that launches a different JAR than the pinned network copies", async () => {
  const fixture = await createFixture();
  try {
    await rewriteJsonEvidence(fixture, "events/child-process-events.jsonl", events => {
      const wrongJar = path.join(fixture.root, `workspace-${fixture.report.runId}`, ".plugdev", "network",
        "backends", "backend-a", "different.jar");
      for (const event of events.filter(item => item.pid === 7101)) event.args[2] = wrongJar;
    });
    assert.throws(() => audit(fixture), error =>
      error instanceof NetworkAuditError && /exact pinned workspace path/i.test(error.message));
  } finally {
    await fixture.close();
  }
});

test("rejects an invalid backend configuration observation while preserving array-based report backends", async () => {
  const fixture = await createFixture();
  try {
    fixture.report.files = fixture.report.files.filter(entry => entry.path !== "evidence/config-observations.json");
    const observations = {
      A: { serverName: "wrong-name", mode: "SYNC", dbEnabled: true, pubsubEnabled: true, redisEnabled: true,
        databaseType: "postgresql", databaseHost: "127.0.0.1", databasePort: 25432, redisHost: "127.0.0.1", redisPort: 26379, redisDatabase: 15 },
      B: { serverName: "t21-net-b", mode: "SYNC", dbEnabled: true, pubsubEnabled: true, redisEnabled: true,
        databaseType: "postgresql", databaseHost: "127.0.0.1", databasePort: 25432, redisHost: "127.0.0.1", redisPort: 26379, redisDatabase: 15 },
    };
    await writeJson(fixture.root, "evidence/config-observations.json", observations);
    const bytes = await fs.readFile(path.join(fixture.root, "evidence/config-observations.json"));
    fixture.report.files.push({ path: "evidence/config-observations.json", bytes: bytes.length, sha256: hash(bytes) });
    await writeJson(fixture.root, "result.json", fixture.report);
    assert.throws(() => audit(fixture), error =>
      error instanceof NetworkAuditError && /backend A configured server-name is incorrect/i.test(error.message));
  } finally {
    await fixture.close();
  }
});

test("rejects a route reply that only contains the requested backend name as a substring", async () => {
  const fixture = await createFixture();
  try {
    await rewriteJsonEvidence(fixture, "events/client-events.jsonl", events => {
      events.find(event => event.type === "hop_pass").serverReply = "Connecting you to backend-b-extra...";
    });
    assert.throws(() => audit(fixture), error =>
      error instanceof NetworkAuditError && /backend-b route acknowledgement/i.test(error.message));
  } finally {
    await fixture.close();
  }
});

test("accepts exact player join notices when the backend-position evidence confirms both routes", async () => {
  const fixture = await createFixture();
  try {
    await rewriteJsonEvidence(fixture, "events/client-events.jsonl", events => {
      for (const event of events.filter(item => item.type === "hop_pass" || item.type === "reconnect_pass")) {
        event.serverReply = "§7AcceptanceB joined the game";
        event.routeConfirmation = "backend-join-notice";
      }
    });
    const result = audit(fixture);
    assert.equal(result.accepted, true);
    assert.equal(result.status, "PASS_BOUNDED_SUBCASE (E2)");
  } finally {
    await fixture.close();
  }
});

test("rejects a join notice when backend-position evidence does not place AcceptanceB on backend B", async () => {
  const fixture = await createFixture();
  try {
    await rewriteJsonEvidence(fixture, "events/client-events.jsonl", events => {
      for (const event of events.filter(item => item.type === "hop_pass" || item.type === "reconnect_pass")) {
        event.serverReply = "AcceptanceB joined the game";
        event.routeConfirmation = "backend-join-notice";
      }
    });
    await rewriteJsonEvidence(fixture, "evidence/backend-position.json", positions => {
      positions.initial.backendB = "There are 1 players online: AcceptanceA";
      positions.afterReconnect.backendB = "There are 1 players online: AcceptanceA";
    });
    assert.throws(() => audit(fixture), error =>
      error instanceof NetworkAuditError && /backend B roster is not exclusive to AcceptanceB/i.test(error.message));
  } finally {
    await fixture.close();
  }
});

test("rejects reconnect success evidence without a submitted backend route request", async () => {
  const fixture = await createFixture();
  try {
    await rewriteJsonEvidence(fixture, "events/client-events.jsonl", events => {
      const index = events.findIndex(event => event.type === "reconnect_submitted");
      events.splice(index, 1);
    });
    assert.throws(() => audit(fixture), error =>
      error instanceof NetworkAuditError && /reconnect.*submitted|route request/i.test(error.message));
  } finally {
    await fixture.close();
  }
});

test("requires the source manifest and rejects drift in a pinned harness source", async () => {
  const missing = await createFixture();
  try {
    missing.report.files = missing.report.files.filter(entry => entry.path !== "evidence/source-manifest.json");
    await writeJson(missing.root, "result.json", missing.report);
    assert.throws(() => audit(missing), error =>
      error instanceof NetworkAuditError && /required evidence file is absent: evidence\/source-manifest\.json/i.test(error.message));
  } finally {
    await missing.close();
  }

  const drifted = await createFixture();
  try {
    await rewriteJsonEvidence(drifted, "evidence/source-manifest.json", manifest => {
      manifest.harness.runner.sha256 = "0".repeat(64);
    });
    assert.throws(() => audit(drifted), error =>
      error instanceof NetworkAuditError && /runner.*source.*SHA-256/i.test(error.message));
  } finally {
    await drifted.close();
  }
});

test("rejects source provenance with an unsupported manifest schema or Velocity URL", async () => {
  const missingSchema = await createFixture();
  try {
    await rewriteJsonEvidence(missingSchema, "evidence/source-manifest.json", manifest => {
      delete manifest.schemaVersion;
    });
    assert.throws(() => audit(missingSchema), error =>
      error instanceof NetworkAuditError && /source manifest schema version/i.test(error.message));
  } finally {
    await missingSchema.close();
  }

  const wrongVelocityUrl = await createFixture();
  try {
    await rewriteJsonEvidence(wrongVelocityUrl, "evidence/source-manifest.json", manifest => {
      manifest.velocityMetadata.url = "https://api.papermc.io/v2/projects/velocity";
    });
    assert.throws(() => audit(wrongVelocityUrl), error =>
      error instanceof NetworkAuditError && /Velocity url/i.test(error.message));
  } finally {
    await wrongVelocityUrl.close();
  }
});

test("requires exact run-workspace Java artifact paths and rejects unpaired unexpected Java children", async () => {
  const wrongPath = await createFixture();
  try {
    await rewriteJsonEvidence(wrongPath, "events/child-process-events.jsonl", events => {
      const spawn = events.find(event => event.event === "spawn" && event.args.includes("-jar")
        && event.args.at(-1).endsWith(path.join("backend-a", "server.jar")));
      spawn.args[spawn.args.indexOf("-jar") + 1] = path.join(wrongPath.root, "decoy", "workspace", ".plugdev",
        "network", "backends", "backend-a", "server.jar");
      const exit = events.find(event => event.event === "exit" && event.pid === spawn.pid);
      exit.args = [...spawn.args];
    });
    assert.throws(() => audit(wrongPath), error =>
      error instanceof NetworkAuditError && /exact pinned workspace path/i.test(error.message));
  } finally {
    await wrongPath.close();
  }

  const extraJava = await createFixture();
  try {
    await rewriteJsonEvidence(extraJava, "events/child-process-events.jsonl", events => {
      events.push({ event: "spawn", pid: 7999, executable: "C:/Java/bin/java.exe", args: ["java.exe", "-version"] });
    });
    assert.throws(() => audit(extraJava), error =>
      error instanceof NetworkAuditError && /child process 7999 did not have one matching exit/i.test(error.message));
  } finally {
    await extraJava.close();
  }
});

test("accepts completed Java version probes as non-server utility children", async () => {
  const fixture = await createFixture();
  try {
    await rewriteJsonEvidence(fixture, "events/child-process-events.jsonl", events => {
      const executable = "C:/Program Files/Java/jdk-25.0.4/bin/java.exe";
      const args = [executable, "-version"];
      events.push(
        { event: "spawn", pid: 8120, executable, args },
        { event: "exit", pid: 8120, executable, args, code: 0, signal: null },
      );
    });
    assert.doesNotThrow(() => audit(fixture));
  } finally {
    await fixture.close();
  }
});

test("rejects an unmatched helper process exit recorded by the shutdown bridge", async () => {
  const fixture = await createFixture();
  try {
    await rewriteJsonEvidence(fixture, "events/child-process-events.jsonl", events => {
      events.push({ event: "exit", pid: 8111, executable: "C:/Program Files/nodejs/node.exe", args: ["node.exe"], code: 0, signal: null });
    });
    assert.throws(() => audit(fixture), error =>
      error instanceof NetworkAuditError && /child process 8111 did not have one matching exit|child exit 8111 has no unique launch/i.test(error.message));
  } finally {
    await fixture.close();
  }
});

test("rejects mismatched toolchain provenance for Mineflayer and PostgreSQL", async () => {
  const fixture = await createFixture();
  try {
    await rewriteJsonEvidence(fixture, "evidence/source-manifest.json", manifest => {
      manifest.mineflayer.commit = "f".repeat(40);
    });
    assert.throws(() => audit(fixture), error =>
      error instanceof NetworkAuditError && /Mineflayer.*commit/i.test(error.message));
  } finally {
    await fixture.close();
  }
});

test("rejects out-of-range, duplicate, and protected report ports", async () => {
  const mutations = [
    report => { report.network.proxyPort = 0; },
    report => { report.network.backendPorts["backend-b"] = report.network.backendPorts["backend-a"]; },
    report => { report.network.backendPorts["backend-b"] = 5432; },
    report => { report.services.redis.port = 6379; },
    report => { report.network.shutdownBridgePort = 65536; },
  ];
  for (const mutate of mutations) {
    const fixture = await createFixture();
    try {
      mutate(fixture.report);
      await writeJson(fixture.root, "result.json", fixture.report);
      assert.throws(() => audit(fixture), error =>
        error instanceof NetworkAuditError && /port/i.test(error.message));
    } finally {
      await fixture.close();
    }
  }
});

test("requires server-stop byte offsets and a SQL lock-release marker before network exit", async () => {
  const fixture = await createFixture();
  try {
    await rewriteJsonEvidence(fixture, "events/runner-events.jsonl", events => {
      const held = events.find(event => event.type === "shutdown_drain_lock_held");
      held.serverLogByteOffsets.backendA = 1;
    });
    assert.throws(() => audit(fixture), error =>
      error instanceof NetworkAuditError && /shutdown.*offset|Stopping server/i.test(error.message));
  } finally {
    await fixture.close();
  }

  const missingMarker = await createFixture();
  try {
    await fs.writeFile(path.join(missingMarker.root, "logs", "postgresql-row-lock.log"), "T21_ROW_LOCK_READY\n", "utf8");
    const rowLockRecord = missingMarker.report.files.find(entry => entry.path === "logs/postgresql-row-lock.log");
    const rowLockBytes = await fs.readFile(path.join(missingMarker.root, "logs", "postgresql-row-lock.log"));
    rowLockRecord.bytes = rowLockBytes.length;
    rowLockRecord.sha256 = hash(rowLockBytes);
    await writeJson(missingMarker.root, "result.json", missingMarker.report);
    assert.throws(() => audit(missingMarker), error =>
      error instanceof NetworkAuditError && /lock-release marker/i.test(error.message));
  } finally {
    await missingMarker.close();
  }
});

test("accepts the idle backend drain after its own stop marker and before the shared held-lock boundary", async () => {
  const fixture = await createFixture();
  try {
    const serverB = await fs.readFile(path.join(fixture.root, "logs", "server-b.log"));
    const databaseDrain = Buffer.from("T21_DRAIN owner=database status=drained queued=0 inFlight=0 retry=0 stale=0 dropped=0", "utf8");
    const drainEnd = serverB.indexOf(databaseDrain) + databaseDrain.length;
    assert.ok(drainEnd > databaseDrain.length);

    await rewriteJsonEvidence(fixture, "events/runner-events.jsonl", events => {
      events.find(event => event.type === "shutdown_drain_lock_held")
        .serverLogByteOffsets.backendB = drainEnd;
    });
    assert.equal(audit(fixture).accepted, true);
  } finally {
    await fixture.close();
  }
});

test("rejects AcceptanceA drain markers before the shared held-lock boundary", async () => {
  const fixture = await createFixture();
  try {
    const serverA = await fs.readFile(path.join(fixture.root, "logs", "server-a.log"));
    const databaseDrain = Buffer.from("T21_DRAIN owner=database status=drained queued=0 inFlight=0 retry=0 stale=0 dropped=0", "utf8");
    const drainEnd = serverA.indexOf(databaseDrain) + databaseDrain.length;
    assert.ok(drainEnd > databaseDrain.length);

    await rewriteJsonEvidence(fixture, "events/runner-events.jsonl", events => {
      events.find(event => event.type === "shutdown_drain_lock_held")
        .serverLogByteOffsets.backendA = drainEnd;
    });
    assert.throws(() => audit(fixture), error =>
      error instanceof NetworkAuditError && /backendA queue drained before the runner recorded the held-lock shutdown boundary/.test(error.message));
  } finally {
    await fixture.close();
  }
});

test("rejects backend B drain markers that precede its own server-stop marker", async () => {
  const fixture = await createFixture();
  try {
    const serverBPath = path.join(fixture.root, "logs", "server-b.log");
    const original = await fs.readFile(serverBPath, "utf8");
    const stopping = "[Server thread/INFO]: Stopping server\n";
    const economyDrain = "T21_DRAIN owner=economy status=drained queued=0 outstanding=0 retry=0\n";
    const databaseDrain = "T21_DRAIN owner=database status=drained queued=0 inFlight=0 retry=0 stale=0 dropped=0\n";
    const ordered = `${stopping}${economyDrain}${databaseDrain}`;
    assert.ok(original.includes(ordered));
    const reordered = original.replace(ordered, `${economyDrain}${databaseDrain}${stopping}`);
    const reorderedBytes = Buffer.from(reordered, "utf8");
    await fs.writeFile(serverBPath, reorderedBytes);
    const serverBManifest = fixture.report.files.find(entry => entry.path === "logs/server-b.log");
    serverBManifest.bytes = reorderedBytes.length;
    serverBManifest.sha256 = hash(reorderedBytes);
    const stoppingEnd = reorderedBytes.indexOf(Buffer.from(stopping, "utf8")) + Buffer.byteLength(stopping);

    await rewriteJsonEvidence(fixture, "events/runner-events.jsonl", events => {
      events.find(event => event.type === "shutdown_drain_lock_held")
        .serverLogByteOffsets.backendB = stoppingEnd;
    });
    assert.throws(() => audit(fixture), error =>
      error instanceof NetworkAuditError && /backendB queue drain markers precede its Stopping server marker/.test(error.message));
  } finally {
    await fixture.close();
  }
});

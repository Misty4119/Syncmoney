import crypto from "node:crypto";
import fs from "node:fs";
import fsp from "node:fs/promises";
import net from "node:net";
import path from "node:path";
import { performance } from "node:perf_hooks";
import { spawn } from "node:child_process";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import {
  auditRunDirectory,
  isOutagePayCompletionAfterRecoveryBarrier,
  collectDirectoryManifest,
  resolvePinnedArtifactSources,
  T21_PAPER_NETWORK_ARTIFACT_PATHS,
  T21_PAPER_NETWORK_ARTIFACTS,
  T21_PAPER_NETWORK_ENDPOINTS,
  T21_PAPER_NETWORK_HARNESS_SOURCES,
  T21_PAPER_NETWORK_SOURCE_MANIFEST_SCHEMA_VERSION,
  T21_PAPER_NETWORK_TOOLCHAIN_PINS,
} from "./t21-paper-network-auditor.mjs";

const SCRIPT_PATH = fileURLToPath(import.meta.url);
const WORKSPACE_ROOT = path.resolve(path.dirname(SCRIPT_PATH), "../..");
const USER_ROOT = process.env.USERPROFILE ?? "C:/Users/margo";
const PLUGDEV_ROOT = path.join(USER_ROOT, "IdeaProjects", "plugdev-main");
const MINEFLAYER_ROOT = path.join(USER_ROOT, "IdeaProjects", "mineflayer-master");
const PLUGDEV_CLI = path.join(PLUGDEV_ROOT, "packages", "cli", "dist", "cli.js");
const POSTGRES_BIN = "C:/Program Files/PostgreSQL/18/bin";
const REDIS_ROOT = path.join(USER_ROOT, "scoop", "apps", "redis", "current");
const REDIS_SERVER = path.join(REDIS_ROOT, "redis-server.exe");
const REDIS_CLI = path.join(REDIS_ROOT, "redis-cli.exe");
const JAVA_HOME_PIN = "C:/Program Files/Java/jdk-25.0.4";
const JAVA_EXE = path.join(JAVA_HOME_PIN, "bin", "java.exe");
const RUNS_ROOT = path.join(WORKSPACE_ROOT, "build", "reports", "foundation", "T21-paper-network");
const CONTROL_BRIDGE = path.join(WORKSPACE_ROOT, "tools", "plugdev", "t21-network-shutdown-bridge.cjs");
const CLIENT_SCRIPT = path.join(WORKSPACE_ROOT, "tools", "plugdev", "t21-paper-network-client.mjs");
const CONTROLLER_SCRIPT = path.join(WORKSPACE_ROOT, "tools", "plugdev", "t21-paper-network-controller.mjs");
const AUDITOR_SCRIPT = path.join(WORKSPACE_ROOT, "tools", "plugdev", "t21-paper-network-auditor.mjs");
const EXPECTED_PLUGDEV_COMMIT = "b59871ae8d9f2731d2bcbca061a735edddd23eb2";
const EXPECTED_PLUGDEV_CLI_SHA256 = "40437ecfb68cc5f13f77bfcaf25be5f2bebbb90d269d482284a3129906e06155";
const EXPECTED_MINEFLAYER_COMMIT = "def6013cc2282f265d518b1af298b9c720a65c12";
const EXPECTED_REDIS_SERVER_SHA256 = "0e88d628cb1a5508ba44ab1d30000a3ebbdd3c430f3554ccfef03b455a134afa";
const EXPECTED_VELOCITY_BUILD = 44;
const EXPECTED_VELOCITY_VERSION = "4.3.0";
const EXPECTED_REDIS_VERSION = "8.10.1";
const EXPECTED_POSTGRES_VERSION = "18.1";
const EXPECTED_MINEFLAYER_VERSION = "4.39.0";
const DATABASE_NAME = "syncmoney_t21";
const DATABASE_USER = "syncmoney_t21";
const REDIS_DATABASE = 15;
const SQL_RETRY_OBSERVATION_TIMEOUT_MS = 60_000;
const FORBIDDEN_PORTS = new Set([5432, 6379]);
const REQUIRED_REPORT_FILES = Object.freeze([
  "events/runner-events.jsonl",
  "events/client-events.jsonl",
  "events/controller-status.jsonl",
  "events/child-process-events.jsonl",
  "evidence/backend-position.json",
  "evidence/config-observations.json",
  "logs/network.log",
  "logs/server-a.log",
  "logs/server-b.log",
  "logs/postgresql.log",
  "logs/redis.log",
  "snapshots/after-seed.json",
  "snapshots/after-cross-pay.json",
  "snapshots/during-sql-outage.json",
  "snapshots/after-sql-recovery.json",
  "snapshots/after-accepted-write-drain.json",
]);

const requirePlugdev = createRequire(path.join(PLUGDEV_ROOT, "package.json"));
const yaml = requirePlugdev("yaml");

function sha256(bytes) {
  return crypto.createHash("sha256").update(bytes).digest("hex");
}

function filePin(filePath) {
  const absolutePath = path.resolve(filePath);
  const bytes = fs.readFileSync(absolutePath);
  return { path: absolutePath, bytes: bytes.length, sha256: sha256(bytes) };
}

function assertCPath(value, label) {
  const absolute = path.resolve(value);
  if (process.platform === "win32" && path.parse(absolute).root.toLowerCase() !== "c:\\") {
    throw new Error(label + " must be on C:; refusing any other volume: " + absolute);
  }
  return absolute;
}

async function canonicalizeProspectiveCPath(value, label, realpath) {
  const absolute = assertCPath(value, label);
  let cursor = absolute;
  const missingSegments = [];
  while (true) {
    try {
      const existingCanonical = await realpath(cursor);
      return assertCPath(path.join(existingCanonical, ...missingSegments), label);
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
      const parent = path.dirname(cursor);
      if (parent === cursor) throw error;
      missingSegments.unshift(path.basename(cursor));
      cursor = parent;
    }
  }
}

export async function createRunRootDirectory(value, {
  realpath = target => fsp.realpath(target),
  mkdir = (target, options) => fsp.mkdir(target, options),
} = {}) {
  const canonical = await canonicalizeProspectiveCPath(value, "Runtime report directory", realpath);
  await mkdir(path.dirname(canonical), { recursive: true });
  await mkdir(canonical, { recursive: false });
  return canonical;
}

function parseArguments(argv) {
  let endpointId = null;
  let preflightOnly = false;
  for (let index = 0; index < argv.length; index += 1) {
    const value = argv[index];
    if (value === "--preflight-only") {
      if (preflightOnly) throw new Error("--preflight-only was supplied more than once.");
      preflightOnly = true;
      continue;
    }
    if (value === "--endpoint") {
      if (endpointId !== null) throw new Error("--endpoint was supplied more than once.");
      endpointId = argv[index + 1] ?? null;
      index += 1;
      continue;
    }
    throw new Error("Unsupported runner argument: " + value);
  }
  if (!endpointId || !Object.hasOwn(T21_PAPER_NETWORK_ENDPOINTS, endpointId)) {
    throw new Error("Use --endpoint paper-1216-48 or --endpoint paper-263-200.");
  }
  return { endpointId, preflightOnly };
}

function assertSafePorts(ports) {
  const values = Object.values(ports);
  if (values.some(value => !Number.isInteger(value) || value < 1 || value > 65535)) {
    throw new Error("Every selected loopback port must be an integer in 1..65535.");
  }
  if (new Set(values).size !== values.length) throw new Error("Selected loopback ports must be unique.");
  if (values.some(value => FORBIDDEN_PORTS.has(value))) {
    throw new Error("The isolated run must not use protected local ports 5432 or 6379.");
  }
  if (ports.rconB !== ports.rconA + 1) throw new Error("PlugDev RCON ports must be consecutive.");
  return ports;
}

function delay(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds));
}

function runCapture(executable, args, options = {}) {
  return new Promise(resolve => {
    let stdout = "";
    let stderr = "";
    let timedOut = false;
    let settled = false;
    let outputFileDescriptor = null;
    const captureOutput = options.captureOutput !== false;
    if (!captureOutput && options.outputFile) {
      outputFileDescriptor = fs.openSync(options.outputFile, "wx");
    }
    let child;
    try {
      child = spawn(executable, args, {
        cwd: options.cwd,
        env: options.env ?? process.env,
        windowsHide: true,
        stdio: captureOutput
          ? ["ignore", "pipe", "pipe"]
          : outputFileDescriptor === null ? "ignore" : ["ignore", outputFileDescriptor, outputFileDescriptor],
      });
    } catch (error) {
      if (outputFileDescriptor !== null) {
        try { fs.closeSync(outputFileDescriptor); } catch { /* preserve the spawn failure */ }
        outputFileDescriptor = null;
      }
      resolve({ code: null, signal: null, error, stdout, stderr, timedOut });
      return;
    }
    const maxOutput = options.maxOutputBytes ?? 2 * 1024 * 1024;
    const appendBounded = (current, chunk) => {
      const next = current + chunk.toString("utf8");
      return next.length > maxOutput ? next.slice(-maxOutput) : next;
    };
    if (captureOutput) {
      child.stdout.on("data", chunk => { stdout = appendBounded(stdout, chunk); });
      child.stderr.on("data", chunk => { stderr = appendBounded(stderr, chunk); });
    }
    const timeout = setTimeout(() => {
      timedOut = true;
      try { child.kill(); } catch { /* only this short-lived command is owned here */ }
    }, options.timeoutMs ?? 30_000);
    const finish = result => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      if (outputFileDescriptor !== null) {
        try { fs.closeSync(outputFileDescriptor); }
        catch (error) { result = { ...result, error: result.error ?? error }; }
        outputFileDescriptor = null;
      }
      if (!captureOutput && options.outputFile) {
        try { stdout = fs.readFileSync(options.outputFile, "utf8"); }
        catch (error) { result = { ...result, error: result.error ?? error }; }
      }
      resolve({ ...result, stdout, stderr, timedOut });
    };
    child.once("error", error => finish({ code: null, signal: null, error }));
    child.once("close", (code, signal) => finish({ code, signal, error: null }));
  });
}

async function checkedCapture(executable, args, label, options = {}) {
  const result = await runCapture(executable, args, options);
  if (result.error || result.timedOut || result.code !== 0 || result.signal !== null) {
    const detail = [result.error?.message, result.stderr.trim(), result.stdout.trim()].filter(Boolean).join(" | ");
    throw new Error(label + " failed" + (detail ? ": " + detail : ""));
  }
  return result;
}

async function captureVersion(executable, args, label) {
  const result = await checkedCapture(executable, args, label, { timeoutMs: 10_000 });
  return (result.stdout + "\n" + result.stderr).trim().split(/\r?\n/)[0];
}

async function gitValue(repository, args, label) {
  const result = await checkedCapture("git", args, label, { cwd: repository, timeoutMs: 10_000 });
  return result.stdout.trim();
}

async function allocateLoopbackPorts() {
  const reservations = [];
  const assigned = {};
  async function reserveSpecific(port) {
    const server = net.createServer();
    await new Promise((resolve, reject) => {
      server.once("error", reject);
      server.listen(port, "127.0.0.1", resolve);
    });
    reservations.push(server);
    return port;
  }
  async function reserveEphemeral(key) {
    const server = net.createServer();
    await new Promise((resolve, reject) => {
      server.once("error", reject);
      server.listen(0, "127.0.0.1", resolve);
    });
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("Unable to allocate a loopback port.");
    reservations.push(server);
    assigned[key] = address.port;
  }
  try {
    let rconReserved = false;
    for (let attempt = 0; attempt < 256 && !rconReserved; attempt += 1) {
      const base = crypto.randomInt(20_000, 65_000);
      if (FORBIDDEN_PORTS.has(base) || FORBIDDEN_PORTS.has(base + 1)) continue;
      try {
        await reserveSpecific(base);
        await reserveSpecific(base + 1);
        assigned.rconA = base;
        assigned.rconB = base + 1;
        rconReserved = true;
      } catch (error) {
        for (const server of reservations.splice(0)) {
          await new Promise(resolve => server.close(() => resolve()));
        }
        if (error?.code !== "EADDRINUSE" && error?.code !== "EACCES") throw error;
      }
    }
    if (!rconReserved) throw new Error("Unable to reserve two adjacent local RCON ports.");
    for (const key of ["proxy", "backendA", "backendB", "postgres", "redis", "bridge"]) {
      await reserveEphemeral(key);
    }
    return assertSafePorts({
      proxy: assigned.proxy,
      backendA: assigned.backendA,
      backendB: assigned.backendB,
      postgres: assigned.postgres,
      redis: assigned.redis,
      rconA: assigned.rconA,
      rconB: assigned.rconB,
      bridge: assigned.bridge,
    });
  } finally {
    for (const server of reservations) {
      await new Promise(resolve => server.close(() => resolve()));
    }
  }
}

async function fetchVelocityMetadata() {
  const url = T21_PAPER_NETWORK_TOOLCHAIN_PINS.velocity.url;
  const response = await fetch(url, {
    headers: { "User-Agent": "Syncmoney-T21-paper-network-validation" },
    signal: AbortSignal.timeout(15_000),
  });
  if (!response.ok) throw new Error("PaperMC Fill API returned HTTP " + response.status + ".");
  const builds = await response.json();
  if (!Array.isArray(builds)) throw new Error("PaperMC Fill API returned an invalid Velocity build list.");
  const stable = builds.find(build => build?.channel === "STABLE");
  if (!stable || stable.id !== EXPECTED_VELOCITY_BUILD) {
    throw new Error("Velocity 4.3.0 current stable build is not the pinned build 44.");
  }
  const download = stable.downloads?.["server:default"];
  if (typeof download?.checksums?.sha256 !== "string" ||
      download.checksums.sha256.toLowerCase() !== T21_PAPER_NETWORK_ARTIFACTS.velocitySha256) {
    throw new Error("Velocity stable metadata SHA-256 does not match the pinned server JAR.");
  }
  return {
    checkedAt: new Date().toISOString(),
    url,
    version: EXPECTED_VELOCITY_VERSION,
    build: stable.id,
    channel: stable.channel,
    sha256: download.checksums.sha256.toLowerCase(),
  };
}

async function collectPreflight(endpointId) {
  const endpoint = T21_PAPER_NETWORK_ENDPOINTS[endpointId];
  if (!endpoint) throw new Error("Unknown Paper endpoint " + endpointId + ".");
  assertCPath(WORKSPACE_ROOT, "Syncmoney worktree");
  assertCPath(USER_ROOT, "User profile");
  assertCPath(PLUGDEV_ROOT, "Local PlugDev checkout");
  assertCPath(MINEFLAYER_ROOT, "Local Mineflayer checkout");
  assertCPath(RUNS_ROOT, "Runtime report root");
  const artifactSources = resolvePinnedArtifactSources(WORKSPACE_ROOT, endpointId);
  const expectedHashes = {
    paper: endpoint.serverJarSha256,
    candidate: T21_PAPER_NETWORK_ARTIFACTS.candidateSha256,
    acceptance: T21_PAPER_NETWORK_ARTIFACTS.acceptanceSha256,
    vaultUnlocked: T21_PAPER_NETWORK_ARTIFACTS.vaultUnlockedSha256,
    velocity: T21_PAPER_NETWORK_ARTIFACTS.velocitySha256,
  };
  const artifactInfo = {};
  for (const [kind, source] of Object.entries(artifactSources)) {
    assertCPath(source, kind + " artifact source");
    const bytes = fs.readFileSync(source);
    const actual = sha256(bytes);
    if (actual !== expectedHashes[kind]) throw new Error(kind + " source artifact SHA-256 mismatch.");
    artifactInfo[kind] = { path: path.resolve(source), bytes: bytes.length, sha256: actual };
  }
  const plugdevCommit = await gitValue(PLUGDEV_ROOT, ["rev-parse", "HEAD"], "PlugDev source pin");
  if (plugdevCommit !== EXPECTED_PLUGDEV_COMMIT) throw new Error("Local PlugDev commit differs from the reviewed pin.");
  const plugdevDirty = (await gitValue(PLUGDEV_ROOT, ["status", "--porcelain"], "PlugDev working-tree state")).length > 0;
  if (!plugdevDirty) throw new Error("The pinned local PlugDev checkout is expected to contain the reviewed network-config changes.");
  const plugdevPackage = JSON.parse(fs.readFileSync(path.join(PLUGDEV_ROOT, "packages", "cli", "package.json"), "utf8"));
  if (plugdevPackage.version !== "1.3.0") throw new Error("Local PlugDev CLI version is not 1.3.0.");
  const plugdevCliBytes = fs.readFileSync(PLUGDEV_CLI);
  const plugdevCliSha256 = sha256(plugdevCliBytes);
  if (plugdevCliSha256 !== EXPECTED_PLUGDEV_CLI_SHA256) throw new Error("Local PlugDev CLI bundle differs from the reviewed pin.");
  const mineflayerCommit = await gitValue(MINEFLAYER_ROOT, ["rev-parse", "HEAD"], "Mineflayer source pin");
  if (mineflayerCommit !== EXPECTED_MINEFLAYER_COMMIT) throw new Error("Local Mineflayer checkout differs from the reviewed pin.");
  const mineflayerPackage = JSON.parse(fs.readFileSync(path.join(MINEFLAYER_ROOT, "package.json"), "utf8"));
  if (mineflayerPackage.version !== EXPECTED_MINEFLAYER_VERSION) throw new Error("Local Mineflayer version is not 4.39.0.");
  const mineflayerDirty = (await gitValue(MINEFLAYER_ROOT, ["status", "--porcelain", "--untracked-files=all"], "Mineflayer working-tree state")).length > 0;
  const javaVersion = await captureVersion(JAVA_EXE, ["-version"], "Java runtime");
  if (!javaVersion.includes("25.0.4")) throw new Error("Runtime Java is not the pinned 25.0.4 release.");
  const javaExecutable = filePin(JAVA_EXE);
  const nodeExecutable = filePin(process.execPath);
  const postgresExe = path.join(POSTGRES_BIN, "postgres.exe");
  const pgCtlExe = path.join(POSTGRES_BIN, "pg_ctl.exe");
  const initDbExe = path.join(POSTGRES_BIN, "initdb.exe");
  const psqlExe = path.join(POSTGRES_BIN, "psql.exe");
  const postgresVersion = await captureVersion(postgresExe, ["--version"], "PostgreSQL runtime");
  const pgCtlVersion = await captureVersion(pgCtlExe, ["--version"], "PostgreSQL control tool");
  const initDbVersion = await captureVersion(initDbExe, ["--version"], "PostgreSQL initializer");
  const psqlVersion = await captureVersion(psqlExe, ["--version"], "PostgreSQL client");
  if (![postgresVersion, pgCtlVersion, initDbVersion, psqlVersion].every(value => value.includes(EXPECTED_POSTGRES_VERSION))) {
    throw new Error("PostgreSQL binaries are not all version 18.1.");
  }
  const postgresBinaries = Object.fromEntries(
    [postgresExe, pgCtlExe, initDbExe, psqlExe].map(file => [path.basename(file), filePin(file)]),
  );
  const redisServerBytes = fs.readFileSync(REDIS_SERVER);
  const redisServerSha256 = sha256(redisServerBytes);
  if (redisServerSha256 !== EXPECTED_REDIS_SERVER_SHA256) throw new Error("Local Redis server executable hash differs from the pin.");
  const redisVersion = await captureVersion(REDIS_SERVER, ["--version"], "Redis server");
  if (!redisVersion.includes(EXPECTED_REDIS_VERSION)) throw new Error("Local Redis server is not version 8.10.1.");
  const redisCliVersion = await captureVersion(REDIS_CLI, ["--version"], "Redis client");
  if (!redisCliVersion.includes(EXPECTED_REDIS_VERSION)) throw new Error("Local redis-cli is not version 8.10.1.");
  const redisServerPin = filePin(REDIS_SERVER);
  const redisCliPin = filePin(REDIS_CLI);
  const velocityMetadata = await fetchVelocityMetadata();
  const localVelocityMeta = JSON.parse(fs.readFileSync(
    path.join(USER_ROOT, ".plugdev", "servers", "velocity-4.3.0", "meta-44.json"), "utf8"));
  if (localVelocityMeta.buildId !== EXPECTED_VELOCITY_BUILD ||
      String(localVelocityMeta.sha256).toLowerCase() !== T21_PAPER_NETWORK_ARTIFACTS.velocitySha256) {
    throw new Error("Cached Velocity build metadata does not match the current stable pin.");
  }
  const ports = await allocateLoopbackPorts();
  return {
    endpoint,
    endpointId,
    artifactSources,
    artifactInfo,
    expectedHashes,
    ports,
    javaVersion,
    postgres: {
      version: EXPECTED_POSTGRES_VERSION,
      binaryRoot: POSTGRES_BIN,
      postgresExe,
      pgCtlExe,
      initDbExe,
      psqlExe,
      binaries: postgresBinaries,
      versionLines: { postgres: postgresVersion, pgCtl: pgCtlVersion, initDb: initDbVersion, psql: psqlVersion },
    },
    redis: {
      version: EXPECTED_REDIS_VERSION,
      serverExe: REDIS_SERVER,
      cliExe: REDIS_CLI,
      serverSha256: redisServerSha256,
      cliSha256: sha256(fs.readFileSync(REDIS_CLI)),
      server: redisServerPin,
      cli: redisCliPin,
      serverVersionLine: redisVersion,
      cliVersionLine: redisCliVersion,
    },
    plugdev: {
      root: PLUGDEV_ROOT,
      commit: plugdevCommit,
      version: plugdevPackage.version,
      dirty: plugdevDirty,
      cliPath: PLUGDEV_CLI,
      cliSha256: plugdevCliSha256,
      packageJson: filePin(path.join(PLUGDEV_ROOT, "packages", "cli", "package.json")),
      distRoot: path.join(PLUGDEV_ROOT, "packages", "cli", "dist"),
    },
    mineflayer: {
      root: MINEFLAYER_ROOT,
      commit: mineflayerCommit,
      version: mineflayerPackage.version,
      dirty: mineflayerDirty,
      packageJson: filePin(path.join(MINEFLAYER_ROOT, "package.json")),
    },
    velocityMetadata,
    nodeVersion: process.version,
    nodeExecutable,
    javaExecutable,
  };
}

function relativeConfigPath(runRoot, target) {
  const relative = path.relative(path.join(runRoot, "workspace"), target);
  if (!relative || relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new Error("PlugDev input path escaped the run workspace.");
  }
  return relative.split(path.sep).join("/");
}

function buildBackendConfig(serverName, postgresPort, redisPort) {
  return {
    "config-version": 12,
    "server-name": serverName,
    "queue-capacity": 50000,
    "pubsub-enabled": true,
    "db-enabled": true,
    redis: {
      enabled: true,
      host: "127.0.0.1",
      port: redisPort,
      password: "",
      database: REDIS_DATABASE,
      "pool-size": 20,
    },
    database: {
      enabled: true,
      type: "postgresql",
      host: "127.0.0.1",
      port: postgresPort,
      username: DATABASE_USER,
      password: "",
      database: DATABASE_NAME,
      "pool-size": 8,
      "minimum-idle": 1,
      "connection-timeout": 5000,
      "max-lifetime": 600000,
      "idle-timeout": 300000,
    },
    economy: {
      mode: "sync",
      sync: {
        "vault-intercept": true,
        "vault-intercept-deposit": true,
        "vault-intercept-withdraw": true,
        "vault-whitelist": [],
        "ignore-commands": [],
      },
    },
    pay: {
      "cooldown-seconds": 0,
      "min-amount": 1,
      "max-amount": 1000000,
      "allow-in-degraded": true,
      "confirm-threshold": 100000,
    },
  };
}

function buildPlugdevConfig(runRoot, preflight, ports, rconPassword) {
  const artifact = name => path.join(runRoot, "inputs", name);
  const pluginConfig = name => "inputs/" + name;
  const paper = artifact("paper.jar");
  const pluginsFor = configName => [
    { jar: artifact("VaultUnlocked.jar") },
    { jar: artifact("Syncmoney.jar"), config: pluginConfig(configName) },
    { jar: artifact("SyncmoneyAcceptance.jar") },
  ];
  return {
    type: "network",
    proxy: {
      software: "velocity",
      version: EXPECTED_VELOCITY_VERSION,
      bind: "127.0.0.1",
      port: ports.proxy,
      loginRateLimit: 0,
    },
    backends: [
      {
        name: "backend-a",
        software: "paper",
        version: preflight.endpoint.version,
        jar: paper,
        port: ports.backendA,
        bind: "127.0.0.1",
        maxPlayers: 20,
        plugins: pluginsFor("backend-a-config.yml"),
      },
      {
        name: "backend-b",
        software: "paper",
        version: preflight.endpoint.version,
        jar: paper,
        port: ports.backendB,
        bind: "127.0.0.1",
        maxPlayers: 20,
        plugins: pluginsFor("backend-b-config.yml"),
      },
    ],
    forwarding: { generate: true },
    rcon: {
      enabled: true,
      basePort: ports.rconA,
      password: rconPassword,
    },
    dev: {
      mode: "server",
      onlineMode: false,
      maxPlayers: 20,
      op: false,
      applySkins: false,
    },
    watch: { paths: [], reload: { java: "safe" } },
  };
}

async function allocateRunRoot(endpointId) {
  const now = new Date().toISOString().replace(/[-:TZ.]/g, "").slice(0, 14);
  const runId = endpointId + "-" + now + "-" + crypto.randomUUID().replaceAll("-", "").slice(0, 12);
  const runRoot = await createRunRootDirectory(path.join(RUNS_ROOT, runId));
  return { runId, runRoot };
}

async function createRunDirectories(runRoot) {
  for (const relative of [
    "events", "evidence", "inputs", "logs", "services/postgres", "services/redis",
    "snapshots", "validation", "workspace/inputs",
  ]) {
    await fsp.mkdir(path.join(runRoot, relative), { recursive: true });
  }
  for (const relative of [
    "events/runner-events.jsonl",
    "events/controller-status.jsonl",
    "events/controller-control.jsonl",
    "logs/postgresql-control.log",
  ]) {
    await fsp.writeFile(path.join(runRoot, relative), "", { flag: "wx" });
  }
}

async function copyInputArtifacts(runRoot, preflight) {
  const names = {
    paper: "paper.jar",
    candidate: "Syncmoney.jar",
    acceptance: "SyncmoneyAcceptance.jar",
    vaultUnlocked: "VaultUnlocked.jar",
    velocity: "velocity.jar",
  };
  for (const [kind, source] of Object.entries(preflight.artifactSources)) {
    const destination = path.join(runRoot, "inputs", names[kind]);
    await fsp.copyFile(source, destination, fs.constants.COPYFILE_EXCL);
    const bytes = fs.readFileSync(destination);
    if (sha256(bytes) !== preflight.expectedHashes[kind]) {
      throw new Error(kind + " artifact copy failed its pinned SHA-256 check.");
    }
  }
}

function appendEvent(runRoot, type, fields = {}) {
  fs.appendFileSync(path.join(runRoot, "events", "runner-events.jsonl"),
    JSON.stringify({ at: new Date().toISOString(), type, ...fields }) + "\n", "utf8");
}

function readJsonLines(filePath) {
  if (!fs.existsSync(filePath)) return [];
  const text = fs.readFileSync(filePath, "utf8");
  const lines = text.split(/\r?\n/);
  if (lines.at(-1) !== "") lines.pop();
  return lines.filter(Boolean).map((line, index) => {
    try { return JSON.parse(line); }
    catch (error) { throw new Error(path.basename(filePath) + " contains invalid JSON on line " + (index + 1) + ": " + error.message); }
  });
}

async function waitUntil(label, timeoutMs, predicate, intervalMs = 250) {
  const deadline = Date.now() + timeoutMs;
  let lastError = null;
  while (Date.now() < deadline) {
    try {
      const result = await predicate();
      if (result) return result;
    } catch (error) {
      lastError = error;
    }
    await delay(intervalMs);
  }
  throw new Error("Timed out waiting for " + label + (lastError ? ": " + lastError.message : ""));
}

function startLoggedProcess(executable, args, options) {
  const output = fs.createWriteStream(options.logPath, { flags: "wx" });
  const child = spawn(executable, args, {
    cwd: options.cwd,
    env: options.env ?? process.env,
    windowsHide: true,
    stdio: options.stdin ? ["pipe", "pipe", "pipe"] : ["ignore", "pipe", "pipe"],
  });
  child.stdout.on("data", chunk => output.write(chunk));
  child.stderr.on("data", chunk => output.write(chunk));
  let settled = false;
  let resolveExit;
  const exited = new Promise(resolve => { resolveExit = resolve; });
  child.once("error", error => {
    if (settled) return;
    settled = true;
    output.end(() => resolveExit({ code: null, signal: null, error }));
  });
  child.once("close", (code, signal) => {
    if (settled) return;
    settled = true;
    output.end(() => resolveExit({ code, signal, error: null }));
  });
  return { child, pid: child.pid, exited };
}

function waitForPort(port, expectedOpen, timeoutMs = 10_000) {
  return waitUntil("loopback port " + port + (expectedOpen ? " to open" : " to close"), timeoutMs, async () => {
    const open = await isPortOpen(port);
    return open === expectedOpen;
  }, 100);
}

function isPortOpen(port) {
  return new Promise(resolve => {
    const socket = net.createConnection({ host: "127.0.0.1", port });
    const finish = value => {
      socket.destroy();
      resolve(value);
    };
    socket.setTimeout(700, () => finish(false));
    socket.once("connect", () => finish(true));
    socket.once("error", () => finish(false));
  });
}

async function appendCommandLog(runRoot, label, result) {
  const text = [
    "COMMAND " + label,
    "EXIT " + String(result.code),
    result.stdout.trim(),
    result.stderr.trim(),
    "",
  ].filter(Boolean).join("\n") + "\n";
  await fsp.appendFile(path.join(runRoot, "logs", "postgresql-control.log"), text, "utf8");
}

async function startPostgres(runRoot, preflight, port, postgresState) {
  const dataDirectory = path.join(runRoot, "services", "postgres", "data");
  const initialLog = path.join(runRoot, "services", "postgres", "postgres-initial.log");
  Object.assign(postgresState, { dataDirectory, initialLog, port, started: false });
  const init = await checkedCapture(preflight.postgres.initDbExe, [
    "-D", dataDirectory,
    "-U", DATABASE_USER,
    "--encoding=UTF8",
    "--no-locale",
    "--auth-local=trust",
    "--auth-host=trust",
    "--no-instructions",
  ], "isolated PostgreSQL initdb", { timeoutMs: 120_000, cwd: runRoot });
  await appendCommandLog(runRoot, "initdb", init);
  const initialControlOutput = path.join(runRoot, "services", "postgres", "pg_ctl-initial-start-output.log");
  const start = await checkedCapture(preflight.postgres.pgCtlExe, [
    "-D", dataDirectory,
    "-l", initialLog,
    "-o", "-p " + port + " -h 127.0.0.1 -c listen_addresses=127.0.0.1",
    "-w", "-t", "90", "start",
  ], "isolated PostgreSQL initial start", {
    timeoutMs: 120_000, cwd: runRoot, captureOutput: false, outputFile: initialControlOutput,
  });
  await appendCommandLog(runRoot, "pg_ctl-start-initial", start);
  await fsp.rm(initialControlOutput, { force: true });
  postgresState.started = true;
  await waitForPort(port, true, 20_000);
  await checkedCapture(preflight.postgres.psqlExe, [
    "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1",
    "-h", "127.0.0.1", "-p", String(port), "-U", DATABASE_USER, "-d", "postgres",
    "-c", "CREATE DATABASE " + DATABASE_NAME + " OWNER " + DATABASE_USER,
  ], "create isolated PostgreSQL database", { timeoutMs: 20_000, env: { ...process.env, PGCONNECT_TIMEOUT: "5" } });
  await waitUntil("isolated PostgreSQL database readiness", 15_000, async () => {
    const query = await runCapture(preflight.postgres.psqlExe, [
      "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1",
      "-h", "127.0.0.1", "-p", String(port), "-U", DATABASE_USER, "-d", DATABASE_NAME,
      "-c", "SELECT current_user || '|' || current_database() || '|' || inet_server_port()",
    ], { timeoutMs: 8_000, env: { ...process.env, PGCONNECT_TIMEOUT: "3" } });
    return query.code === 0 && query.stdout.trim() === DATABASE_USER + "|" + DATABASE_NAME + "|" + port;
  });
  return postgresState;
}

function redisConfigText(runRoot, port) {
  const dataDirectory = path.join(runRoot, "services", "redis").replaceAll("\\", "/");
  const redisLog = path.join(runRoot, "logs", "redis.log").replaceAll("\\", "/");
  const pidFile = path.join(runRoot, "services", "redis", "redis.pid").replaceAll("\\", "/");
  return [
    "bind 127.0.0.1",
    "port " + port,
    "protected-mode yes",
    "daemonize no",
    "supervised no",
    "databases 16",
    "save \"\"",
    "appendonly no",
    "dir " + dataDirectory,
    "dbfilename dump.rdb",
    "pidfile " + pidFile,
    "logfile " + redisLog,
    "",
  ].join("\n");
}

function redisConfigArgument(configPath, workingDirectory) {
  const absoluteConfig = path.resolve(configPath);
  const absoluteWorkingDirectory = path.resolve(workingDirectory);
  const relative = path.relative(absoluteWorkingDirectory, absoluteConfig);
  if (!relative || relative === "." || relative.startsWith("..") || path.isAbsolute(relative)) {
    throw new Error("Redis config must be inside its run-owned working directory.");
  }
  return relative.split(path.sep).join("/");
}

async function startRedis(runRoot, preflight, port, redisState) {
  const configPath = path.join(runRoot, "services", "redis", "redis.conf");
  await fsp.writeFile(configPath, redisConfigText(runRoot, port), { flag: "wx", encoding: "utf8" });
  const workingDirectory = path.dirname(configPath);
  const child = startLoggedProcess(preflight.redis.serverExe, [redisConfigArgument(configPath, workingDirectory)], {
    cwd: workingDirectory,
    logPath: path.join(runRoot, "logs", "redis-process.log"),
  });
  Object.assign(redisState, { child, pid: child.pid, port, configPath, started: true });
  await waitUntil("isolated Redis PING", 20_000, async () => {
    const ping = await runCapture(preflight.redis.cliExe, [
      "-h", "127.0.0.1", "-p", String(port), "-n", String(REDIS_DATABASE), "--raw", "PING",
    ], { timeoutMs: 3_000 });
    if (child.child.exitCode !== null) throw new Error("isolated Redis process exited before readiness.");
    return ping.code === 0 && ping.stdout.trim() === "PONG";
  });
  return redisState;
}

function encodeRconPacket(requestId, type, text) {
  const payload = Buffer.from(text, "utf8");
  const length = 10 + payload.length;
  const packet = Buffer.alloc(length + 4);
  packet.writeInt32LE(length, 0);
  packet.writeInt32LE(requestId, 4);
  packet.writeInt32LE(type, 8);
  payload.copy(packet, 12);
  packet.writeUInt16LE(0, 12 + payload.length);
  return packet;
}

async function rconCommand(port, password, command) {
  return new Promise((resolve, reject) => {
    const socket = net.createConnection({ host: "127.0.0.1", port });
    let input = Buffer.alloc(0);
    const queued = [];
    const waiters = [];
    let finished = false;
    const fail = error => {
      if (finished) return;
      finished = true;
      socket.destroy();
      reject(error instanceof Error ? error : new Error(String(error)));
    };
    socket.setTimeout(7_000, () => fail(new Error("RCON command timed out.")));
    socket.once("error", fail);
    socket.on("data", chunk => {
      input = Buffer.concat([input, chunk]);
      while (input.length >= 4) {
        const length = input.readInt32LE(0);
        if (length < 10 || length > 1_048_576) return fail(new Error("RCON response length is invalid."));
        if (input.length < length + 4) break;
        const packet = {
          id: input.readInt32LE(4),
          type: input.readInt32LE(8),
          payload: input.subarray(12, 4 + length - 2).toString("utf8"),
        };
        input = input.subarray(length + 4);
        const waiterIndex = waiters.findIndex(waiter => waiter.predicate(packet));
        if (waiterIndex >= 0) waiters.splice(waiterIndex, 1)[0].resolve(packet);
        else queued.push(packet);
      }
    });
    function nextPacket(predicate) {
      const index = queued.findIndex(predicate);
      if (index >= 0) return Promise.resolve(queued.splice(index, 1)[0]);
      return new Promise((resolvePacket, rejectPacket) => {
        waiters.push({ predicate, resolve: resolvePacket, reject: rejectPacket });
      });
    }
    socket.once("connect", async () => {
      try {
        socket.write(encodeRconPacket(27101, 3, password));
        const auth = await nextPacket(packet => packet.id === 27101);
        if (auth.id === -1 || auth.type !== 2) throw new Error("RCON authentication failed.");
        socket.write(encodeRconPacket(27102, 2, command));
        const response = await nextPacket(packet => packet.id === 27102);
        finished = true;
        socket.end();
        resolve(response.payload.replace(/\x00+$/g, "").trim());
      } catch (error) {
        fail(error);
      }
    });
  });
}

function parseClientEvents(runRoot) {
  return readJsonLines(path.join(runRoot, "events", "client-events.jsonl"));
}

function findClientEvent(runRoot, predicate) {
  return parseClientEvents(runRoot).find(predicate) ?? null;
}

async function waitClientEvent(runRoot, label, predicate, timeoutMs = 20_000) {
  return waitUntil(label, timeoutMs, async () => findClientEvent(runRoot, predicate));
}

function sendClientAction(client, action) {
  if (!client?.child.stdin || client.child.stdin.destroyed) throw new Error("Network client action channel is unavailable.");
  client.child.stdin.write(JSON.stringify(action) + "\n");
}

function startNetworkClient(runRoot, preflight, proxyPort) {
  const eventPath = path.join(runRoot, "events", "client-events.jsonl");
  const logPath = path.join(runRoot, "logs", "client.log");
  return startLoggedProcess(process.execPath, [
    CLIENT_SCRIPT,
    preflight.mineflayer.root,
    "127.0.0.1",
    String(proxyPort),
    preflight.endpoint.version,
    "backend-b",
    eventPath,
  ], {
    cwd: runRoot,
    env: {
      ...process.env,
      NODE_PATH: path.join(preflight.mineflayer.root, "node_modules"),
    },
    logPath,
    stdin: true,
  });
}

function createControllerConfig(runRoot, preflight, ports, bridgeToken) {
  const controlToken = crypto.randomBytes(32).toString("hex");
  const configPath = path.join(runRoot, "controller-config.json");
  const config = {
    cliPath: preflight.plugdev.cliPath,
    cliArgs: ["network", "--config", "inputs/plugdev.yml", "--no-join", "--no-watch"],
    workingDirectory: path.join(runRoot, "workspace"),
    signalBridgePath: CONTROL_BRIDGE,
    statusPath: path.join(runRoot, "events", "controller-status.jsonl"),
    controlPath: path.join(runRoot, "events", "controller-control.jsonl"),
    networkLogPath: path.join(runRoot, "logs", "network.log"),
    environment: {
      JAVA_HOME: JAVA_HOME_PIN,
      Path: JAVA_HOME_PIN + "/bin;" + (process.env.Path ?? process.env.PATH ?? ""),
      T21_SHUTDOWN_BRIDGE_PORT: String(ports.bridge),
      T21_RUN_ROOT: runRoot,
      T21_CHILD_PROCESS_EVENTS: path.join(runRoot, "events", "child-process-events.jsonl"),
    },
  };
  fs.writeFileSync(configPath, JSON.stringify(config, null, 2) + "\n", { flag: "wx", encoding: "utf8" });
  return { configPath, controlToken, bridgeToken };
}

function startNetworkController(runRoot, configuration) {
  return startLoggedProcess(process.execPath, [CONTROLLER_SCRIPT, configuration.configPath], {
    cwd: WORKSPACE_ROOT,
    env: {
      ...process.env,
      JAVA_HOME: JAVA_HOME_PIN,
      Path: JAVA_HOME_PIN + "/bin;" + (process.env.Path ?? process.env.PATH ?? ""),
      T21_NETWORK_CONTROL_TOKEN: configuration.controlToken,
      T21_SHUTDOWN_BRIDGE_TOKEN: configuration.bridgeToken,
      T21_SHUTDOWN_BRIDGE_PORT: String(JSON.parse(fs.readFileSync(configuration.configPath, "utf8")).environment.T21_SHUTDOWN_BRIDGE_PORT),
      T21_RUN_ROOT: runRoot,
      T21_CHILD_PROCESS_EVENTS: path.join(runRoot, "events", "child-process-events.jsonl"),
    },
    logPath: path.join(runRoot, "logs", "controller.log"),
  });
}

async function waitControllerState(runRoot, state, timeoutMs) {
  return waitUntil("PlugDev controller state " + state, timeoutMs, async () => {
    const events = readJsonLines(path.join(runRoot, "events", "controller-status.jsonl"));
    if (events.some(event => event.state === "controller_error")) {
      throw new Error(events.filter(event => event.state === "controller_error").at(-1).message ?? "controller failed");
    }
    if (state === "network_ready" && events.some(event => event.state === "network_exited" && event.exitCode !== 0)) {
      throw new Error("PlugDev network exited before readiness.");
    }
    return events.find(event => event.state === state) ?? null;
  });
}

async function requestNetworkControllerShutdown(runRoot, controlToken) {
  const statusPath = path.join(runRoot, "events", "controller-status.jsonl");
  const existing = readJsonLines(statusPath).find(event => event.state === "network_stop_requested");
  if (!existing) {
    const action = { type: "stop", id: "shutdown-network", token: controlToken };
    await fsp.appendFile(path.join(runRoot, "events", "controller-control.jsonl"), JSON.stringify(action) + "\n", "utf8");
  }
  return waitUntil("PlugDev controller accepted graceful shutdown", 30_000, async () => {
    return readJsonLines(statusPath).find(event => event.state === "network_stop_requested") ?? null;
  }, 250);
}

async function waitForNetworkControllerShutdown(runRoot, controller) {
  const exited = await waitUntil("graceful PlugDev network shutdown", 180_000, async () => {
    const events = readJsonLines(path.join(runRoot, "events", "controller-status.jsonl"));
    return events.find(event => event.state === "network_exited") ?? null;
  }, 500);
  const controllerExit = await waitProcess(controller, 15_000, "PlugDev controller");
  if (exited.exitCode !== 0 || exited.signal !== null || controllerExit.code !== 0 || controllerExit.signal !== null) {
    throw new Error("PlugDev network did not complete a normal graceful shutdown.");
  }
  return exited;
}

async function stopNetworkController(runRoot, controller, controlToken) {
  await requestNetworkControllerShutdown(runRoot, controlToken);
  return waitForNetworkControllerShutdown(runRoot, controller);
}

async function waitForServerShutdownStart(runRoot) {
  return waitUntil("both Paper backends began shutdown while the SQL row lock is held", 90_000, async () => {
    const logs = ["backend-a", "backend-b"].map(name =>
      fs.readFileSync(readServerLogPath(runRoot, name)).toString("utf8"));
    if (!logs.every(log => log.includes("[Server thread/INFO]: Stopping server"))) return null;
    return serverLogOffsets(runRoot);
  }, 250);
}

async function waitProcess(processHandle, timeoutMs, label) {
  return Promise.race([
    processHandle.exited,
    delay(timeoutMs).then(() => { throw new Error(label + " did not exit within " + timeoutMs + " ms."); }),
  ]);
}

async function waitForRedisProcess(redisState, timeoutMs) {
  return waitProcess(redisState.child, timeoutMs, "isolated Redis server");
}

async function getRedisValue(preflight, port, key) {
  const result = await checkedCapture(preflight.redis.cliExe, [
    "-h", "127.0.0.1", "-p", String(port), "-n", String(REDIS_DATABASE), "--raw", "GET", key,
  ], "isolated Redis GET", { timeoutMs: 8_000 });
  const value = result.stdout.trim();
  if (!value || value === "(nil)") throw new Error("Redis is missing the pinned account key " + key + ".");
  return value;
}

async function readSqlRows(preflight, port, uuidA, uuidB) {
  const sql = "SELECT player_uuid, balance::text, version::text FROM players WHERE player_uuid IN ('" +
    uuidA + "','" + uuidB + "') ORDER BY player_uuid";
  const result = await checkedCapture(preflight.postgres.psqlExe, [
    "-X", "-A", "-t", "-q", "-F", "|", "-v", "ON_ERROR_STOP=1",
    "-h", "127.0.0.1", "-p", String(port), "-U", DATABASE_USER, "-d", DATABASE_NAME, "-c", sql,
  ], "isolated SQL account snapshot", {
    timeoutMs: 12_000,
    env: { ...process.env, PGCONNECT_TIMEOUT: "5" },
  });
  const rows = new Map();
  for (const line of result.stdout.split(/\r?\n/).filter(Boolean)) {
    const [uuid, balance, version] = line.split("|");
    if (!uuid || !balance || !/^\d+$/.test(version ?? "")) throw new Error("PostgreSQL account snapshot row is malformed.");
    rows.set(uuid.toLowerCase(), { balance, version: Number(version) });
  }
  return rows;
}

async function readSnapshot(runRoot, preflight, ports, stage, uuids, sqlReachable) {
  const redisRows = {};
  const names = [["AcceptanceA", uuids.uuidA], ["AcceptanceB", uuids.uuidB]];
  for (const [name, uuid] of names) {
    redisRows[name] = {
      uuid,
      redisBalance: await getRedisValue(preflight, ports.redis, "syncmoney:balance:" + uuid),
      redisVersion: Number(await getRedisValue(preflight, ports.redis, "syncmoney:version:" + uuid)),
    };
    if (!Number.isSafeInteger(redisRows[name].redisVersion)) {
      throw new Error("Redis account version is not an integer for " + name + ".");
    }
  }
  const sqlRows = sqlReachable ? await readSqlRows(preflight, ports.postgres, uuids.uuidA, uuids.uuidB) : new Map();
  const accounts = {};
  for (const [name, uuid] of names) {
    const redis = redisRows[name];
    const sql = sqlRows.get(uuid.toLowerCase());
    accounts[name] = {
      uuid,
      sqlBalance: sql?.balance ?? null,
      sqlVersion: sql?.version ?? null,
      redisBalance: redis.redisBalance,
      redisVersion: redis.redisVersion,
    };
    if (sqlReachable && !sql) throw new Error("PostgreSQL snapshot is missing " + name + ".");
  }
  return {
    stage,
    sqlReachable,
    redisDatabase: REDIS_DATABASE,
    accounts,
  };
}

function snapshotMatches(snapshot, balances, versions) {
  if (!snapshot) return false;
  for (const name of ["AcceptanceA", "AcceptanceB"]) {
    const row = snapshot.accounts?.[name];
    if (!row || row.redisBalance !== balances[name] || row.redisVersion !== versions[name]) return false;
    if (snapshot.sqlReachable && (row.sqlBalance !== balances[name] || row.sqlVersion !== versions[name])) return false;
  }
  return true;
}

async function awaitSnapshot(runRoot, preflight, ports, stage, uuids, expectedBalances, expectedVersions, sqlReachable, timeoutMs = 90_000) {
  return waitUntil(stage + " SQL/Redis account snapshot", timeoutMs, async () => {
    const snapshot = await readSnapshot(runRoot, preflight, ports, stage, uuids, sqlReachable);
    return snapshotMatches(snapshot, expectedBalances, expectedVersions) ? snapshot : null;
  }, 500);
}

async function writeSnapshot(runRoot, relativePath, snapshot) {
  await fsp.writeFile(path.join(runRoot, relativePath), JSON.stringify(snapshot, null, 2) + "\n", { flag: "wx", encoding: "utf8" });
}

function readServerLogPath(runRoot, name) {
  return path.join(runRoot, "workspace", ".plugdev", "network", "backends", name, "logs", "latest.log");
}

function serverLogOffsets(runRoot) {
  const a = fs.readFileSync(readServerLogPath(runRoot, "backend-a"));
  const b = fs.readFileSync(readServerLogPath(runRoot, "backend-b"));
  return { backendA: a.length, backendB: b.length };
}

function retryLinesInWindow(runRoot, startOffsets, endOffsets, uuids) {
  const logs = [
    ["backend-a", "backendA", uuids.uuidA, uuids.uuidB],
    ["backend-b", "backendB", uuids.uuidA, uuids.uuidB],
  ];
  const found = [];
  for (const [name, key, uuidA, uuidB] of logs) {
    const bytes = fs.readFileSync(readServerLogPath(runRoot, name));
    const start = startOffsets[key];
    const end = endOffsets[key];
    if (!Number.isSafeInteger(start) || !Number.isSafeInteger(end) || start < 0 || end < start || end > bytes.length) {
      throw new Error("SQL retry log byte offsets are invalid for " + name + ".");
    }
    const section = bytes.subarray(start, end).toString("utf8");
    const failed = section.includes("Failed to process batch to DB");
    const retries = section.split(/\r?\n/).filter(line => line.includes("Re-queued failed DB write task (attempt"));
    for (const line of retries) {
      const match = line.match(/for player ([0-9a-f-]{36})/i);
      if (!match || ![uuidA, uuidB].includes(match[1].toLowerCase())) {
        throw new Error("SQL retry line did not identify one of the two acceptance account UUIDs.");
      }
      found.push({ backend: name, uuid: match[1].toLowerCase() });
    }
    if (failed && retries.length > 0) continue;
    if (retries.length > 0) continue;
  }
  if (found.length === 0) throw new Error("No account-specific SQL retry was observed during the PostgreSQL outage.");
  return found;
}

async function waitForSqlRetryEvidence(runRoot, startOffsets, uuids, options = {}) {
  // The confirmed transfer consumer processes both account-side audit effects before
  // it queues the paired SQL snapshot. Give those bounded SQL timeouts time to finish,
  // then require account-specific retry evidence before PostgreSQL is restored.
  const timeoutMs = options.timeoutMs ?? SQL_RETRY_OBSERVATION_TIMEOUT_MS;
  const intervalMs = options.intervalMs ?? 250;
  const now = options.now ?? (() => performance.now());
  const sleep = options.sleep ?? delay;
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 0 ||
      !Number.isSafeInteger(intervalMs) || intervalMs < 1 ||
      typeof now !== "function" || typeof sleep !== "function") {
    throw new Error("SQL retry observation timing options are invalid.");
  }
  const deadline = now() + timeoutMs;
  while (true) {
    const pollStartedAt = now();
    if (pollStartedAt >= deadline) {
      throw new Error("No account-specific SQL retry was observed before PostgreSQL recovery.");
    }
    const endOffsets = serverLogOffsets(runRoot);
    let retryEvidence;
    try {
      retryEvidence = retryLinesInWindow(runRoot, startOffsets, endOffsets, uuids);
    } catch (error) {
      if (error?.message !== "No account-specific SQL retry was observed during the PostgreSQL outage.") throw error;
      retryEvidence = [];
    }
    const sampleCompletedAt = now();
    if (sampleCompletedAt >= deadline) {
      throw new Error("No account-specific SQL retry was observed before PostgreSQL recovery.");
    }
    if (retryEvidence.length > 0) return { endOffsets, retryEvidence };
    const remainingMs = deadline - sampleCompletedAt;
    if (remainingMs <= 0) {
      throw new Error("No account-specific SQL retry was observed before PostgreSQL recovery.");
    }
    await sleep(Math.min(intervalMs, remainingMs));
  }
}

async function captureBackendPositions(runRoot, password, ports, phase) {
  const [backendA, backendB] = await Promise.all([
    rconCommand(ports.rconA, password, "list"),
    rconCommand(ports.rconB, password, "list"),
  ]);
  if (!backendA.includes("AcceptanceA") || backendA.includes("AcceptanceB") ||
      !backendB.includes("AcceptanceB") || backendB.includes("AcceptanceA")) {
    throw new Error(phase + " backend player roster is not exclusive to the expected server.");
  }
  return {
    backendA,
    backendB,
    backendAName: "backend-a",
    backendBName: "backend-b",
  };
}

async function observeConfig(runRoot, parseYaml) {
  const result = {};
  for (const [key, serverName] of [["A", "t21-net-a"], ["B", "t21-net-b"]]) {
    const backendName = key === "A" ? "backend-a" : "backend-b";
    const filePath = path.join(runRoot, "workspace", ".plugdev", "network", "backends", backendName, "plugins", "Syncmoney", "config.yml");
    const config = parseYaml(fs.readFileSync(filePath, "utf8"));
    result[key] = {
      serverName: config["server-name"],
      mode: config.economy?.mode,
      dbEnabled: config["db-enabled"],
      pubsubEnabled: config["pubsub-enabled"],
      redisEnabled: config.redis?.enabled,
      databaseType: config.database?.type,
      databaseHost: config.database?.host,
      databasePort: config.database?.port,
      redisHost: config.redis?.host,
      redisPort: config.redis?.port,
      redisDatabase: config.redis?.database,
    };
    if (result[key].serverName !== serverName) throw new Error(backendName + " deployed server-name did not match its unique config.");
  }
  return result;
}

async function waitForLog(runRoot, backendName, text, timeoutMs = 30_000) {
  const filePath = readServerLogPath(runRoot, backendName);
  return waitUntil("server marker " + text + " in " + backendName, timeoutMs, async () => {
    if (!fs.existsSync(filePath)) return null;
    const content = fs.readFileSync(filePath, "utf8");
    return content.includes(text) ? content : null;
  });
}

function getOfflineUuid(clientEvents, name) {
  const event = clientEvents.find(item => item.type === "bot_login" && item.name === name);
  if (!event || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(event.uuid ?? "")) {
    throw new Error("Mineflayer did not record a valid login UUID for " + name + ".");
  }
  return event.uuid.toLowerCase();
}

async function copyServerLogs(runRoot) {
  for (const [sourceName, targetName] of [["backend-a", "server-a.log"], ["backend-b", "server-b.log"]]) {
    const source = readServerLogPath(runRoot, sourceName);
    const target = path.join(runRoot, "logs", targetName);
    await fsp.copyFile(source, target, fs.constants.COPYFILE_EXCL);
  }
}

async function buildPostgresLog(runRoot, preflight, pgState) {
  const logPath = path.join(runRoot, "logs", "postgresql.log");
  const parts = [];
  for (const file of [pgState.initialLog, pgState.restartLog].filter(Boolean)) {
    if (fs.existsSync(file)) parts.push(fs.readFileSync(file, "utf8"));
  }
  await fsp.writeFile(logPath, parts.join("\n"), { flag: "wx", encoding: "utf8" });
}

async function stopPostgres(runRoot, preflight, pgState, stage, {
  capture = runCapture,
  captureChecked = checkedCapture,
  waitPort = waitForPort,
} = {}) {
  const status = await capture(preflight.postgres.pgCtlExe, [
    "-D", pgState.dataDirectory, "status",
  ], { timeoutMs: 15_000, cwd: runRoot });
  await appendCommandLog(runRoot, "pg_ctl-status-before-stop-" + stage, status);
  if (status.error || status.timedOut) {
    throw new Error("Unable to inspect isolated PostgreSQL before stop.");
  }
  if (status.code === 3) {
    await waitPort(pgState.port, false, 20_000);
    pgState.started = false;
    return { code: 0, alreadyStopped: true };
  }
  if (status.code !== 0) {
    throw new Error("Isolated PostgreSQL status returned an unexpected exit code before stop: " + String(status.code));
  }
  const result = await captureChecked(preflight.postgres.pgCtlExe, [
    "-D", pgState.dataDirectory, "-m", "fast", "-w", "-t", "90", "stop",
  ], "isolated PostgreSQL " + stage + " stop", { timeoutMs: 120_000, cwd: runRoot });
  await appendCommandLog(runRoot, "pg_ctl-stop-" + stage, result);
  await waitPort(pgState.port, false, 20_000);
  pgState.started = false;
  return result;
}

async function startPostgresAgain(runRoot, preflight, pgState, stage = "recovery") {
  const restartLog = path.join(runRoot, "services", "postgres", "postgres-" + stage + "-restart.log");
  const recoveryControlOutput = path.join(runRoot, "services", "postgres", "pg_ctl-" + stage + "-start-output.log");
  const result = await checkedCapture(preflight.postgres.pgCtlExe, [
    "-D", pgState.dataDirectory,
    "-l", restartLog,
    "-o", "-p " + pgState.port + " -h 127.0.0.1 -c listen_addresses=127.0.0.1",
    "-w", "-t", "90", "start",
  ], "isolated PostgreSQL " + stage + " start", {
    timeoutMs: 120_000, cwd: runRoot, captureOutput: false, outputFile: recoveryControlOutput,
  });
  await appendCommandLog(runRoot, "pg_ctl-start-" + stage, result);
  await fsp.rm(recoveryControlOutput, { force: true });
  pgState.started = true;
  await waitForPort(pgState.port, true, 20_000);
  pgState.restartLog = restartLog;
  return result;
}

async function ensurePostgresAvailableForNetworkShutdown(runRoot, preflight, pgState, {
  capture = runCapture,
  waitPort = waitForPort,
  startPostgres = stage => startPostgresAgain(runRoot, preflight, pgState, stage),
} = {}) {
  pgState.cleanupMayBeRunningForCleanup = true;
  const status = await capture(preflight.postgres.pgCtlExe, [
    "-D", pgState.dataDirectory, "status",
  ], { timeoutMs: 15_000, cwd: runRoot });
  await appendCommandLog(runRoot, "pg_ctl-status-before-network-cleanup", status);
  if (status.error || status.timedOut) {
    throw new Error("Unable to inspect isolated PostgreSQL before network cleanup.");
  }
  if (status.code === 0) {
    await waitPort(pgState.port, true, 20_000);
    pgState.started = true;
    return { startedForCleanup: false };
  }
  if (status.code !== 3) {
    throw new Error("Isolated PostgreSQL status returned an unexpected exit code before network cleanup: " + String(status.code));
  }
  await waitPort(pgState.port, false, 20_000);
  pgState.cleanupStartAttemptedForCleanup = true;
  await startPostgres("cleanup");
  pgState.started = true;
  return { startedForCleanup: true };
}

function startSqlRowLock(runRoot, preflight, port, uuid) {
  const logPath = path.join(runRoot, "logs", "postgresql-row-lock.log");
  const output = fs.createWriteStream(logPath, { flags: "wx" });
  const child = spawn(preflight.postgres.psqlExe, [
    "-X", "-A", "-t", "-q", "-v", "ON_ERROR_STOP=1",
    "-h", "127.0.0.1", "-p", String(port), "-U", DATABASE_USER, "-d", DATABASE_NAME,
  ], {
    cwd: runRoot,
    env: { ...process.env, PGCONNECT_TIMEOUT: "5" },
    windowsHide: true,
    stdio: ["pipe", "pipe", "pipe"],
  });
  let stdout = "";
  let settled = false;
  let resolveExit;
  const exited = new Promise(resolve => { resolveExit = resolve; });
  child.stdout.on("data", chunk => {
    output.write(chunk);
    stdout += chunk.toString("utf8");
  });
  child.stderr.on("data", chunk => output.write(chunk));
  child.once("error", error => {
    if (settled) return;
    settled = true;
    output.end(() => resolveExit({ code: null, signal: null, error }));
  });
  child.once("close", (code, signal) => {
    if (settled) return;
    settled = true;
    output.end(() => resolveExit({ code, signal, error: null }));
  });
  child.stdin.write("BEGIN;\nSELECT player_uuid FROM players WHERE player_uuid='" + uuid + "' FOR UPDATE;\nSELECT 'T21_ROW_LOCK_READY';\n");
  return { child, pid: child.pid, exited, get stdout() { return stdout; }, logPath };
}

async function waitRowLock(lock) {
  return waitUntil("PostgreSQL row lock acquisition marker", 20_000, async () => {
    if (lock.child.exitCode !== null) throw new Error("PostgreSQL row-lock session exited early.");
    return lock.stdout.includes("T21_ROW_LOCK_READY");
  });
}

async function releaseRowLock(lock) {
  if (lock.child.exitCode !== null) throw new Error("PostgreSQL row-lock session exited before explicit release.");
  const marker = "T21_ROW_LOCK_RELEASED_FOR_SHUTDOWN";
  lock.child.stdin.end("ROLLBACK;\n\\echo " + marker + "\n\\q\n");
  const result = await waitProcess(lock, 15_000, "PostgreSQL row-lock session");
  if (result.code !== 0 || result.signal !== null) throw new Error("PostgreSQL row-lock session did not close normally.");
  if (!lock.stdout.includes(marker)) throw new Error("PostgreSQL row-lock release marker was not emitted.");
  return { ...result, marker, markerObserved: true };
}

async function stopRedis(preflight, redisState, allowAlreadyExited = false) {
  if (!redisState?.child) {
    redisState.started = false;
    return { code: 0, alreadyStopped: true };
  }
  if (redisState.child.child.exitCode !== null) {
    const exit = await redisState.child.exited;
    await waitForPort(redisState.port, false, 10_000);
    redisState.started = false;
    if (!allowAlreadyExited) throw new Error("Isolated Redis exited before its requested graceful shutdown.");
    return { code: exit.code, signal: exit.signal, alreadyExited: true };
  }
  const result = await runCapture(preflight.redis.cliExe, [
    "-h", "127.0.0.1", "-p", String(redisState.port), "-n", String(REDIS_DATABASE), "SHUTDOWN", "SAVE",
  ], { timeoutMs: 15_000 });
  if (result.error || result.timedOut || (result.code !== 0 && result.code !== null)) {
    throw new Error("Isolated Redis SHUTDOWN SAVE did not complete normally.");
  }
  const exit = await waitForRedisProcess(redisState, 15_000);
  if (exit.code !== 0 || exit.signal !== null) throw new Error("Isolated Redis server did not exit normally.");
  await waitForPort(redisState.port, false, 10_000);
  redisState.started = false;
  return result;
}

async function createSourceManifest(runRoot, preflight) {
  const sources = {};
  for (const [key, relativePath] of Object.entries(T21_PAPER_NETWORK_HARNESS_SOURCES)) {
    const file = path.resolve(WORKSPACE_ROOT, ...relativePath.split("/"));
    const bytes = fs.readFileSync(file);
    sources[key] = { path: file, bytes: bytes.length, sha256: sha256(bytes) };
  }
  const manifest = {
    schemaVersion: T21_PAPER_NETWORK_SOURCE_MANIFEST_SCHEMA_VERSION,
    createdAt: new Date().toISOString(),
    workspaceRoot: WORKSPACE_ROOT,
    sourceCommit: await gitValue(WORKSPACE_ROOT, ["rev-parse", "HEAD"], "Syncmoney source commit"),
    sourceWorkingTreeDirty: (await gitValue(WORKSPACE_ROOT, ["status", "--porcelain"], "Syncmoney working-tree state")).length > 0,
    harness: sources,
    artifacts: preflight.artifactInfo,
    plugdev: {
      ...preflight.plugdev,
      distributionFiles: collectDirectoryManifest(preflight.plugdev.distRoot),
    },
    mineflayer: preflight.mineflayer,
    nodeVersion: preflight.nodeVersion,
    nodeExecutable: preflight.nodeExecutable,
    javaVersion: preflight.javaVersion,
    javaExecutable: preflight.javaExecutable,
    postgres: {
      version: EXPECTED_POSTGRES_VERSION,
      binaryRoot: preflight.postgres.binaryRoot,
      binaries: preflight.postgres.binaries,
    },
    redis: {
      version: EXPECTED_REDIS_VERSION,
      server: preflight.redis.server,
      cli: preflight.redis.cli,
    },
    velocityMetadata: preflight.velocityMetadata,
  };
  await fsp.writeFile(path.join(runRoot, "evidence", "source-manifest.json"),
    JSON.stringify(manifest, null, 2) + "\n", { flag: "wx", encoding: "utf8" });
  return manifest;
}

async function collectCleanupEvidence(ports, childEvents, checkPortOpen = isPortOpen) {
  const openPorts = [];
  const errors = [];
  const portEntries = ports && typeof ports === "object" && !Array.isArray(ports)
    ? Object.entries(ports)
    : [];
  if (portEntries.length === 0) errors.push("run-owned port manifest is unavailable");
  for (const [name, port] of portEntries) {
    if (!Number.isInteger(port) || port < 1 || port > 65535) {
      errors.push(`${name} run-owned port is invalid`);
      continue;
    }
    try {
      const open = await checkPortOpen(port);
      if (typeof open !== "boolean") errors.push(`${name} port liveness probe returned no boolean result`);
      else if (open) openPorts.push(port);
    } catch (error) {
      errors.push(`${name} port liveness probe failed: ${error?.message ?? error}`);
    }
  }
  const processIdentityValid = event => Number.isSafeInteger(event.pid) && event.pid > 0 &&
    typeof event.executable === "string" && event.executable.trim().length > 0 &&
    Array.isArray(event.args) && event.args.every(argument => typeof argument === "string");
  const childEventsValid = Array.isArray(childEvents) && childEvents.every(event =>
    event && typeof event === "object" && !Array.isArray(event) &&
    ["spawn", "exit", "kill"].includes(event.event) && processIdentityValid(event) &&
    (event.event !== "exit" ||
      ((Number.isSafeInteger(event.code) && event.signal === null) ||
       (event.code === null && typeof event.signal === "string" && event.signal.trim().length > 0))) &&
    (event.event !== "kill" || event.requestedSignal === null || typeof event.requestedSignal === "string"));
  if (!childEventsValid) errors.push("child-process liveness evidence is unavailable or malformed");
  const processEvents = Array.isArray(childEvents)
    ? childEvents.filter(event => event && typeof event === "object" && !Array.isArray(event))
    : [];
  const spawns = processEvents.filter(event => event.event === "spawn");
  const exits = processEvents.filter(event => event.event === "exit");
  const kills = processEvents.filter(event => event.event === "kill");
  const isJavaChild = event => {
    const executableName = typeof event.executable === "string"
      ? path.basename(event.executable.replace(/\\/g, "/"))
      : "";
    return /^javaw?(?:\.exe)?$/i.test(executableName) ||
      (Array.isArray(event.args) && event.args.includes("-jar"));
  };
  const hasInspectableJavaLaunch = event => {
    if (!Array.isArray(event.args)) return false;
    const isVersionProbe = event.args.length === 2 &&
      event.args[0] === event.executable && event.args[1] === "-version";
    if (isVersionProbe) return true;
    const jarIndex = event.args.indexOf("-jar");
    const jarPath = jarIndex >= 0 ? event.args[jarIndex + 1] : null;
    return typeof jarPath === "string" && jarPath.trim().length > 0 && path.isAbsolute(jarPath);
  };
  const sameCommandIdentity = (left, right) => left.pid === right.pid &&
    left.executable === right.executable && JSON.stringify(left.args) === JSON.stringify(right.args);
  const matchedSpawnPids = new Set();
  for (const spawnEvent of spawns) {
    const samePidSpawns = spawns.filter(event => event.pid === spawnEvent.pid);
    const samePidExits = exits.filter(event => event.pid === spawnEvent.pid);
    if ((isJavaChild(spawnEvent) && !hasInspectableJavaLaunch(spawnEvent)) ||
        !childEventsValid || samePidSpawns.length !== 1 || samePidExits.length !== 1 ||
        !sameCommandIdentity(spawnEvent, samePidExits[0])) {
      errors.push(`child process ${String(spawnEvent.pid)} does not have complete command and matching identity-pinned exit evidence`);
      continue;
    }
    matchedSpawnPids.add(spawnEvent.pid);
  }
  for (const exitEvent of exits) {
    const samePidSpawns = spawns.filter(event => event.pid === exitEvent.pid);
    if (samePidSpawns.length !== 1 || !sameCommandIdentity(samePidSpawns[0], exitEvent)) {
      errors.push(`child process exit ${String(exitEvent.pid)} has no unique matching launch identity`);
    }
  }
  const runOwnedProcessesRemaining = spawns.filter(event => !matchedSpawnPids.has(event.pid)).length;
  const evidenceComplete = errors.length === 0;
  return {
    forcedCleanup: !evidenceComplete || runOwnedProcessesRemaining > 0 || openPorts.length > 0 || kills.length > 0,
    evidenceComplete,
    runOwnedProcessesRemaining,
    runOwnedPortsOpen: openPorts.length,
    openPorts,
    errors,
  };
}

function buildFailureCleanupRecord(livenessEvidence, cleanupErrors, reportState) {
  const evidence = livenessEvidence && typeof livenessEvidence === "object" ? livenessEvidence : null;
  const evidenceErrors = Array.isArray(evidence?.errors) ? evidence.errors : [];
  const attemptErrors = Array.isArray(cleanupErrors) ? cleanupErrors : [];
  const evidenceComplete = evidence?.evidenceComplete === true;
  const forcedCleanup = !evidenceComplete || evidence?.forcedCleanup !== false || attemptErrors.length > 0;
  return {
    forcedCleanup,
    evidenceComplete,
    runOwnedProcessesRemaining: Number.isSafeInteger(evidence?.runOwnedProcessesRemaining)
      ? evidence.runOwnedProcessesRemaining : null,
    runOwnedPortsOpen: Number.isSafeInteger(evidence?.runOwnedPortsOpen) ? evidence.runOwnedPortsOpen : null,
    openPorts: Array.isArray(evidence?.openPorts) ? evidence.openPorts : [],
    errors: [...evidenceErrors, ...attemptErrors],
    postgresStopped: reportState.postgresStopped,
    postgresRecoveredForCleanup: reportState.postgresRecoveredForCleanup === true,
    postgresStartedForCleanup: reportState.postgresStartedForCleanup === true,
    postgresStartAttemptedForCleanup: reportState.postgresStartAttemptedForCleanup === true,
    redisStopped: reportState.redisStopped,
    networkStopped: reportState.networkStopped,
    clientStopped: reportState.clientStopped,
  };
}

async function cleanupFailedRunResources(reportState, actions) {
  const cleanupErrors = [];
  const networkAlive = reportState.controller?.child?.exitCode === null;

  if (reportState.postgresStopped && networkAlive && !reportState.networkStopped) {
    try {
      const restoration = await actions.restorePostgres();
      reportState.postgresStopped = false;
      reportState.postgresRecoveredForCleanup = true;
      reportState.postgresStartedForCleanup = restoration?.startedForCleanup === true;
      reportState.postgresStartAttemptedForCleanup = reportState.postgres?.cleanupStartAttemptedForCleanup === true;
      await actions.onPostgresRestored?.();
    } catch (error) {
      reportState.postgresStartAttemptedForCleanup = reportState.postgres?.cleanupStartAttemptedForCleanup === true;
      reportState.postgresCleanupStopRequired = reportState.postgres?.cleanupMayBeRunningForCleanup === true;
      if (reportState.postgresCleanupStopRequired) reportState.postgresStopped = false;
      cleanupErrors.push("postgres restore before network shutdown: " + error.message);
    }
  }
  if (reportState.controller && !reportState.networkStopped) {
    try {
      if (reportState.controller.child.exitCode === null) {
        await actions.stopNetwork();
        reportState.networkStopped = true;
      }
    } catch (error) {
      cleanupErrors.push("network: " + error.message);
    }
  }
  if (reportState.redis?.child && !reportState.redisStopped) {
    try {
      await actions.stopRedis();
      reportState.redisStopped = true;
    } catch (error) {
      cleanupErrors.push("redis: " + error.message);
    }
  }
  if (reportState.postgres && (!reportState.postgresStopped || reportState.postgresCleanupStopRequired)) {
    try {
      await actions.stopPostgres();
      reportState.postgresStopped = true;
    } catch (error) {
      cleanupErrors.push("postgres: " + error.message);
    }
  }
  return cleanupErrors;
}

async function createFileManifest(runRoot) {
  const extras = [
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
    "logs/redis.log",
    "logs/redis-process.log",
    "logs/postgresql-control.log",
    "logs/postgresql-row-lock.log",
    "logs/client.log",
    "logs/controller.log",
    "snapshots/after-seed.json",
    "snapshots/after-cross-pay.json",
    "snapshots/during-sql-outage.json",
    "snapshots/after-sql-recovery.json",
    "snapshots/after-accepted-write-drain.json",
  ];
  const paths = new Set([...REQUIRED_REPORT_FILES, ...extras]);
  const files = [];
  for (const relative of paths) {
    const absolute = path.join(runRoot, ...relative.split("/"));
    const bytes = fs.readFileSync(absolute);
    files.push({ path: relative, bytes: bytes.length, sha256: sha256(bytes) });
  }
  return files;
}

async function createArtifactCopyManifest(runRoot) {
  const result = [];
  for (const artifact of T21_PAPER_NETWORK_ARTIFACT_PATHS) {
    const absolute = path.join(runRoot, ...artifact.path.split("/"));
    const bytes = fs.readFileSync(absolute);
    result.push({
      kind: artifact.kind,
      path: artifact.path,
      bytes: bytes.length,
      sha256: sha256(bytes),
    });
  }
  return result;
}

async function recordFailedRun(runRoot, runId, endpointId, error, cleanup) {
  const report = {
    runId,
    endpointId,
    status: cleanup?.forcedCleanup ? "FAIL_CLEANUP_INCOMPLETE" : "FAIL_EXCLUDED",
    at: new Date().toISOString(),
    failure: String(error?.message ?? error).replace(/[\r\n]+/g, " ").slice(0, 1000),
    cleanup,
  };
  await fsp.writeFile(path.join(runRoot, "run-status.json"), JSON.stringify(report, null, 2) + "\n", {
    flag: "wx",
    encoding: "utf8",
  });
  return report;
}

async function runCell(endpointId) {
  const preflight = await collectPreflight(endpointId);
  const { runId, runRoot } = await allocateRunRoot(endpointId);
  const ports = await allocateLoopbackPorts();
  const reportState = {
    runId,
    runRoot,
    endpointId,
    ports,
    postgres: null,
    redis: null,
    controller: null,
    client: null,
    rowLock: null,
    sourceManifest: null,
    controlToken: null,
    rconPassword: crypto.randomBytes(24).toString("base64url"),
    networkReady: false,
    clientReady: false,
    postgresStopped: false,
    postgresRestarted: false,
    postgresRecoveredForCleanup: false,
    postgresStartedForCleanup: false,
    postgresStartAttemptedForCleanup: false,
    postgresCleanupStopRequired: false,
    redisStopped: false,
    networkStopped: false,
    clientStopped: false,
    forcedCleanup: false,
  };
  let failure = null;
  let successReport = null;
  try {
    await createRunDirectories(runRoot);
    const realRoot = await fsp.realpath(runRoot);
    assertCPath(realRoot, "Runtime report directory");
    await copyInputArtifacts(runRoot, preflight);
    const configA = buildBackendConfig("t21-net-a", ports.postgres, ports.redis);
    const configB = buildBackendConfig("t21-net-b", ports.postgres, ports.redis);
    await fsp.writeFile(path.join(runRoot, "workspace", "inputs", "backend-a-config.yml"),
      yaml.stringify(configA), { flag: "wx", encoding: "utf8" });
    await fsp.writeFile(path.join(runRoot, "workspace", "inputs", "backend-b-config.yml"),
      yaml.stringify(configB), { flag: "wx", encoding: "utf8" });
    const plugdevConfig = buildPlugdevConfig(runRoot, preflight, ports, reportState.rconPassword);
    await fsp.writeFile(path.join(runRoot, "workspace", "inputs", "plugdev.yml"),
      yaml.stringify(plugdevConfig), { flag: "wx", encoding: "utf8" });
    reportState.sourceManifest = await createSourceManifest(runRoot, preflight);

    console.log("stage=postgres_initial_start endpoint=" + endpointId);
    reportState.postgres = {};
    await startPostgres(runRoot, preflight, ports.postgres, reportState.postgres);
    appendEvent(runRoot, "postgres_ready_initial", {
      port: ports.postgres,
      runOwned: true,
      version: EXPECTED_POSTGRES_VERSION,
    });
    console.log("stage=redis_start endpoint=" + endpointId);
    reportState.redis = {};
    await startRedis(runRoot, preflight, ports.redis, reportState.redis);
    appendEvent(runRoot, "redis_ready", {
      port: ports.redis,
      database: REDIS_DATABASE,
      runOwned: true,
      version: EXPECTED_REDIS_VERSION,
    });

    const bridgeToken = crypto.randomBytes(32).toString("hex");
    const controllerConfiguration = createControllerConfig(runRoot, preflight, ports, bridgeToken);
    reportState.controlToken = controllerConfiguration.controlToken;
    console.log("stage=network_start endpoint=" + endpointId);
    reportState.controller = startNetworkController(runRoot, controllerConfiguration);
    await waitControllerState(runRoot, "network_ready", 300_000);
    reportState.networkReady = true;
    const deployedVelocity = path.join(runRoot, "workspace", ".plugdev", "network", "proxy", "velocity.jar");
    const deployedVelocitySha = sha256(fs.readFileSync(deployedVelocity));
    if (deployedVelocitySha !== T21_PAPER_NETWORK_ARTIFACTS.velocitySha256) {
      throw new Error("PlugDev deployed a Velocity JAR that differs from the pinned stable build.");
    }
    const configObservations = await observeConfig(runRoot, yaml.parse);
    await fsp.writeFile(path.join(runRoot, "evidence", "config-observations.json"),
      JSON.stringify(configObservations, null, 2) + "\n", { flag: "wx", encoding: "utf8" });
    appendEvent(runRoot, "network_ready", {
      endpoint: endpointId,
      backendNames: ["backend-a", "backend-b"],
      velocityBuild: EXPECTED_VELOCITY_BUILD,
    });

    const opA = await rconCommand(ports.rconA, reportState.rconPassword, "op AcceptanceA");
    const opBOnA = await rconCommand(ports.rconA, reportState.rconPassword, "op AcceptanceB");
    const opAOnB = await rconCommand(ports.rconB, reportState.rconPassword, "op AcceptanceA");
    const opB = await rconCommand(ports.rconB, reportState.rconPassword, "op AcceptanceB");
    if (![opA, opBOnA, opAOnB, opB].every(value => /operator|opped/i.test(value))) {
      throw new Error("Acceptance probe accounts could not be granted operator permission on both backends.");
    }
    reportState.client = startNetworkClient(runRoot, preflight, ports.proxy);
    await waitClientEvent(runRoot, "both Mineflayer clients ready", event => event.type === "client_ready", 90_000);
    reportState.clientReady = true;
    const clientEvents = parseClientEvents(runRoot);
    const uuids = { uuidA: getOfflineUuid(clientEvents, "AcceptanceA"), uuidB: getOfflineUuid(clientEvents, "AcceptanceB") };
    if (uuids.uuidA === uuids.uuidB) throw new Error("Acceptance A and B resolved to the same offline UUID.");
    appendEvent(runRoot, "clients_ready", { accountCount: 2, uuidA: uuids.uuidA, uuidB: uuids.uuidB });

    console.log("stage=seed_accounts endpoint=" + endpointId);
    sendClientAction(reportState.client, { type: "seed", id: "seed-accounts" });
    await waitForLog(runRoot, "backend-a", "T21_WRITE_PASS expected-a=100.00 expected-b=50.00", 60_000);
    const seed = await awaitSnapshot(runRoot, preflight, ports, "after-seed", uuids,
      { AcceptanceA: "100.00", AcceptanceB: "50.00" }, { AcceptanceA: 1, AcceptanceB: 1 }, true);
    await writeSnapshot(runRoot, "snapshots/after-seed.json", seed);
    appendEvent(runRoot, "accounts_seeded", { stage: seed.stage });

    sendClientAction(reportState.client, { type: "hop-b", id: "hop-b-1" });
    await waitClientEvent(runRoot, "AcceptanceB route to backend-b", event => event.type === "hop_pass", 30_000);
    const initialPosition = await captureBackendPositions(runRoot, reportState.rconPassword, ports, "initial");
    const positionEvidence = {
      initial: initialPosition,
      afterReconnect: null,
    };
    appendEvent(runRoot, "backend_position_verified", { phase: "initial", backendA: "AcceptanceA", backendB: "AcceptanceB" });

    console.log("stage=cross_server_pay endpoint=" + endpointId);
    sendClientAction(reportState.client, { type: "pay", id: "cross-server-pay", amount: "5.00" });
    await waitClientEvent(runRoot, "cross-server pay receipts", event => event.type === "pay_response_pass" && event.id === "cross-server-pay", 30_000);
    const afterPay = await awaitSnapshot(runRoot, preflight, ports, "after-cross-pay", uuids,
      { AcceptanceA: "95.00", AcceptanceB: "55.00" }, { AcceptanceA: 2, AcceptanceB: 2 }, true);
    await writeSnapshot(runRoot, "snapshots/after-cross-pay.json", afterPay);
    appendEvent(runRoot, "cross_server_pay_pass", { sender: "AcceptanceA", recipient: "AcceptanceB", amount: "5.00" });

    sendClientAction(reportState.client, { type: "reconnect-b", id: "reconnect-b-1" });
    await waitClientEvent(runRoot, "AcceptanceB reconnect to backend-b", event => event.type === "reconnect_pass", 45_000);
    positionEvidence.afterReconnect = await captureBackendPositions(runRoot, reportState.rconPassword, ports, "after reconnect");
    await fsp.writeFile(path.join(runRoot, "evidence", "backend-position.json"),
      JSON.stringify(positionEvidence, null, 2) + "\n", { flag: "wx", encoding: "utf8" });
    appendEvent(runRoot, "reconnect_b_pass", { uuid: uuids.uuidB, backend: "backend-b" });

    const outageStartOffsets = serverLogOffsets(runRoot);
    appendEvent(runRoot, "postgres_outage_begin", {
      serverLogByteOffsets: outageStartOffsets,
      postgresPort: ports.postgres,
    });
    console.log("stage=postgres_outage endpoint=" + endpointId);
    await stopPostgres(runRoot, preflight, reportState.postgres, "outage");
    reportState.postgresStopped = true;
    appendEvent(runRoot, "postgres_stopped_for_outage", {
      exitCode: 0,
      runOwned: true,
      portClosed: !(await isPortOpen(ports.postgres)),
      pid: reportState.postgres.pid ?? null,
    });
    if (await isPortOpen(ports.postgres)) throw new Error("Isolated PostgreSQL listener remained open during the outage.");
    sendClientAction(reportState.client, { type: "pay", id: "db-outage-pay", amount: "3.00" });
    await waitClientEvent(runRoot, "outage pay submission", event => event.type === "pay_submitted" && event.id === "db-outage-pay", 15_000);
    appendEvent(runRoot, "outage_pay_submitted", {
      id: "db-outage-pay", sender: "AcceptanceA", recipient: "AcceptanceB", amount: "3.00",
    });
    const duringOutage = await waitUntil("Redis authority during SQL outage", 45_000, async () => {
      const snapshot = await readSnapshot(runRoot, preflight, ports, "during-sql-outage", uuids, false);
      return snapshotMatches(snapshot, { AcceptanceA: "92.00", AcceptanceB: "58.00" }, { AcceptanceA: 3, AcceptanceB: 3 })
        ? snapshot : null;
    }, 500);
    await writeSnapshot(runRoot, "snapshots/during-sql-outage.json", duringOutage);
    appendEvent(runRoot, "outage_memory_state_observed", {
      stage: duringOutage.stage,
      sqlReachable: duringOutage.sqlReachable,
      senderBalance: duringOutage.accounts.AcceptanceA.redisBalance,
      senderVersion: duringOutage.accounts.AcceptanceA.redisVersion,
      recipientBalance: duringOutage.accounts.AcceptanceB.redisBalance,
      recipientVersion: duringOutage.accounts.AcceptanceB.redisVersion,
    });
    const { endOffsets: outageEndOffsets, retryEvidence } = await waitForSqlRetryEvidence(
      runRoot, outageStartOffsets, uuids);
    appendEvent(runRoot, "outage_sql_retry_observed", { retryCount: retryEvidence.length, accounts: [...new Set(retryEvidence.map(item => item.uuid))] });

    console.log("stage=postgres_recovery endpoint=" + endpointId);
    await startPostgresAgain(runRoot, preflight, reportState.postgres);
    reportState.postgresStopped = false;
    reportState.postgresRestarted = true;
    const recoveryOffsets = serverLogOffsets(runRoot);
    appendEvent(runRoot, "postgres_restarted", {
      exitCode: 0,
      runOwned: true,
      ready: await isPortOpen(ports.postgres),
      serverLogByteOffsets: recoveryOffsets,
    });
    sendClientAction(reportState.client, { type: "recovery-barrier", id: "postgres-recovery-barrier" });
    const recoveryBarrier = await waitClientEvent(runRoot, "Mineflayer PostgreSQL recovery barrier",
      event => event.type === "recovery_barrier" && event.id === "postgres-recovery-barrier" && Number.isSafeInteger(event.seq), 20_000);
    appendEvent(runRoot, "client_recovery_barrier", {
      actionId: "postgres-recovery-barrier",
      clientEventSequence: recoveryBarrier.seq,
    });
    const outageReceipt = await waitClientEvent(runRoot, "outage pay completion after PostgreSQL recovery barrier",
      event => isOutagePayCompletionAfterRecoveryBarrier(event, recoveryBarrier.seq), 135_000);
    appendEvent(runRoot, "outage_pay_receipt_after_recovery", {
      id: "db-outage-pay",
      receiptObserved: true,
      receiptEventSequence: outageReceipt.seq,
      senderReceiptEventSequence: outageReceipt.senderReceiptEventSequence,
      receiverReceiptEventSequence: outageReceipt.receiverReceiptEventSequence,
      recoveryBarrierEventSequence: recoveryBarrier.seq,
    });
    const recovered = await awaitSnapshot(runRoot, preflight, ports, "after-sql-recovery", uuids,
      { AcceptanceA: "92.00", AcceptanceB: "58.00" }, { AcceptanceA: 3, AcceptanceB: 3 }, true, 120_000);
    await writeSnapshot(runRoot, "snapshots/after-sql-recovery.json", recovered);
    appendEvent(runRoot, "sql_recovery_parity_pass", { stage: recovered.stage });

    reportState.rowLock = startSqlRowLock(runRoot, preflight, ports.postgres, uuids.uuidA);
    await waitRowLock(reportState.rowLock);
    appendEvent(runRoot, "accepted_write_row_lock_acquired", {
      account: "AcceptanceA",
      database: DATABASE_NAME,
      rowLockHeld: true,
    });
    sendClientAction(reportState.client, { type: "lifecycle-logout", id: "accepted-write-logout" });
    await waitClientEvent(runRoot, "exact accepted-write logout", event => event.type === "lifecycle_logout_pass", 60_000);
    await waitForLog(runRoot, "backend-a",
      "T15_PENDING_AT_BOUNDARY scenario=logout uuid=" + uuids.uuidA + " pending=2 pending-state=accepted-not-completed",
      15_000);
    await waitForLog(runRoot, "backend-a", "T15_LOGOUT_REQUEST uuid=" + uuids.uuidA, 15_000);
    appendEvent(runRoot, "accepted_write_logout_pass", {
      uuid: uuids.uuidA,
      pending: 2,
      rowLockHeld: true,
      rowLockReleased: false,
    });

    sendClientAction(reportState.client, { type: "stop", id: "stop-clients" });
    const clientExit = await waitProcess(reportState.client, 30_000, "Mineflayer network client");
    if (clientExit.code !== 0 || clientExit.signal !== null) throw new Error("Mineflayer clients did not stop normally.");
    reportState.clientStopped = true;
    appendEvent(runRoot, "client_stop_pass", { exitCode: clientExit.code, signal: clientExit.signal });

    console.log("stage=network_shutdown endpoint=" + endpointId);
    appendEvent(runRoot, "network_shutdown_requested", { reason: "controlled PlugDev IPC shutdown" });
    await requestNetworkControllerShutdown(runRoot, reportState.controlToken);
    const stopOffsets = await waitForServerShutdownStart(runRoot);
    appendEvent(runRoot, "shutdown_drain_lock_held", {
      pending: 2,
      rowLockHeld: reportState.rowLock !== null,
      serverLogByteOffsets: stopOffsets,
    });
    const lockRelease = await releaseRowLock(reportState.rowLock);
    reportState.rowLock = null;
    appendEvent(runRoot, "accepted_write_lock_released_for_shutdown", {
      exitCode: lockRelease.code,
      signal: lockRelease.signal,
      rowLockReleased: true,
      rowLockReleaseMarkerObserved: lockRelease.markerObserved,
      rowLockReleaseMarker: lockRelease.marker,
    });
    await waitForNetworkControllerShutdown(runRoot, reportState.controller);
    reportState.networkStopped = true;
    appendEvent(runRoot, "network_exited", { exitCode: 0, signal: null });

    const finalSnapshot = await awaitSnapshot(runRoot, preflight, ports, "after-accepted-write-drain", uuids,
      { AcceptanceA: "94.00", AcceptanceB: "58.00" }, { AcceptanceA: 5, AcceptanceB: 3 }, true, 120_000);
    await writeSnapshot(runRoot, "snapshots/after-accepted-write-drain.json", finalSnapshot);
    appendEvent(runRoot, "final_sql_redis_parity_pass", { stage: finalSnapshot.stage });
    const redisStop = await stopRedis(preflight, reportState.redis);
    reportState.redisStopped = true;
    await fsp.appendFile(path.join(runRoot, "logs", "postgresql-control.log"),
      "REDIS_SHUTDOWN_EXIT " + String(redisStop.code) + "\n", "utf8");
    appendEvent(runRoot, "redis_stopped", { exitCode: 0, runOwned: true, portClosed: !(await isPortOpen(ports.redis)) });
    const finalPgStop = await stopPostgres(runRoot, preflight, reportState.postgres, "final");
    reportState.postgresStopped = true;
    await buildPostgresLog(runRoot, preflight, reportState.postgres);
    appendEvent(runRoot, "postgres_stopped_final", { exitCode: finalPgStop.code, runOwned: true, portClosed: !(await isPortOpen(ports.postgres)) });

    const childEvents = readJsonLines(path.join(runRoot, "events", "child-process-events.jsonl"));
    const cleanupEvidence = await collectCleanupEvidence(ports, childEvents);
    appendEvent(runRoot, "cleanup_verified", cleanupEvidence);
    if (cleanupEvidence.forcedCleanup || !cleanupEvidence.evidenceComplete) {
      throw new Error("Run-owned child processes or loopback ports remain, or cleanup liveness evidence is incomplete.");
    }
    await copyServerLogs(runRoot);

    const artifactFiles = await createArtifactCopyManifest(runRoot);
    const files = await createFileManifest(runRoot);
    const report = {
      schemaVersion: 1,
      status: "RUN_COMPLETED",
      runId,
      sourceCommit: reportState.sourceManifest.sourceCommit,
      sourceWorkingTreeDirty: reportState.sourceManifest.sourceWorkingTreeDirty,
      endpoint: {
        product: preflight.endpoint.product,
        version: preflight.endpoint.version,
        build: preflight.endpoint.build,
        serverJarSha256: preflight.endpoint.serverJarSha256,
      },
      artifacts: {
        serverJarSha256: preflight.endpoint.serverJarSha256,
        candidateSha256: T21_PAPER_NETWORK_ARTIFACTS.candidateSha256,
        acceptanceSha256: T21_PAPER_NETWORK_ARTIFACTS.acceptanceSha256,
        vaultUnlockedSha256: T21_PAPER_NETWORK_ARTIFACTS.vaultUnlockedSha256,
        velocitySha256: T21_PAPER_NETWORK_ARTIFACTS.velocitySha256,
      },
      runtime: {
        javaMajor: 25,
        javaVersion: preflight.javaVersion,
        nodeVersion: preflight.nodeVersion,
        plugdevVersion: preflight.plugdev.version,
        plugdevDirty: preflight.plugdev.dirty,
      },
      backends: [
        {
          name: "backend-a",
          serverName: "t21-net-a",
          paperJarSha256: preflight.endpoint.serverJarSha256,
          candidateJarSha256: T21_PAPER_NETWORK_ARTIFACTS.candidateSha256,
          acceptanceJarSha256: T21_PAPER_NETWORK_ARTIFACTS.acceptanceSha256,
          vaultUnlockedJarSha256: T21_PAPER_NETWORK_ARTIFACTS.vaultUnlockedSha256,
        },
        {
          name: "backend-b",
          serverName: "t21-net-b",
          paperJarSha256: preflight.endpoint.serverJarSha256,
          candidateJarSha256: T21_PAPER_NETWORK_ARTIFACTS.candidateSha256,
          acceptanceJarSha256: T21_PAPER_NETWORK_ARTIFACTS.acceptanceSha256,
          vaultUnlockedJarSha256: T21_PAPER_NETWORK_ARTIFACTS.vaultUnlockedSha256,
        },
      ],
      services: {
        postgres: {
          version: EXPECTED_POSTGRES_VERSION,
          bind: "127.0.0.1",
          port: ports.postgres,
          isolated: true,
          database: DATABASE_NAME,
          username: DATABASE_USER,
          dataDirectory: reportState.postgres.dataDirectory,
          pid: reportState.postgres.pid ?? null,
        },
        redis: {
          version: EXPECTED_REDIS_VERSION,
          bind: "127.0.0.1",
          port: ports.redis,
          isolated: true,
          database: REDIS_DATABASE,
          appendOnly: false,
          pid: reportState.redis.pid ?? null,
        },
      },
      network: {
        proxyBind: "127.0.0.1",
        proxyPort: ports.proxy,
        backendPorts: { "backend-a": ports.backendA, "backend-b": ports.backendB },
        rconPorts: { "backend-a": ports.rconA, "backend-b": ports.rconB },
        shutdownBridgePort: ports.bridge,
        velocity: preflight.velocityMetadata,
      },
      files,
      artifactFiles,
    };
    await fsp.writeFile(path.join(runRoot, "result.json"), JSON.stringify(report, null, 2) + "\n", { flag: "wx", encoding: "utf8" });
    const audit = auditRunDirectory(runRoot, endpointId, WORKSPACE_ROOT);
    await fsp.writeFile(path.join(runRoot, "validation", "offline-audit.json"), JSON.stringify(audit, null, 2) + "\n", {
      flag: "wx",
      encoding: "utf8",
    });
    successReport = { report, audit };
  } catch (error) {
    failure = error;
  } finally {
    const cleanupErrors = [];
    if (reportState.rowLock) {
      try { await releaseRowLock(reportState.rowLock); }
      catch (error) { cleanupErrors.push("row-lock: " + error.message); }
    }
    if (reportState.client && !reportState.clientStopped) {
      try {
        if (reportState.client.child.exitCode === null) {
          sendClientAction(reportState.client, { type: "stop", id: "cleanup-stop-client" });
          const clientExit = await waitProcess(reportState.client, 15_000, "cleanup Mineflayer client");
          reportState.clientStopped = clientExit.code === 0 && clientExit.signal === null;
        }
      } catch (error) { cleanupErrors.push("client: " + error.message); }
    }
    cleanupErrors.push(...await cleanupFailedRunResources(reportState, {
      restorePostgres: () => ensurePostgresAvailableForNetworkShutdown(runRoot, preflight, reportState.postgres),
      onPostgresRestored: () => appendEvent(runRoot, "postgres_available_for_network_cleanup", {
        runOwned: true,
        ready: true,
        startedForCleanup: reportState.postgresStartedForCleanup,
        port: ports.postgres,
      }),
      stopNetwork: () => stopNetworkController(runRoot, reportState.controller, reportState.controlToken),
      stopRedis: () => stopRedis(preflight, reportState.redis, true),
      stopPostgres: () => stopPostgres(runRoot, preflight, reportState.postgres, "cleanup"),
    }));
    if (!failure && cleanupErrors.length > 0) failure = new Error(cleanupErrors.join("; "));
    if (failure) {
      let livenessEvidence = null;
      try {
        const childEventsPath = path.join(runRoot, "events", "child-process-events.jsonl");
        const childEvents = fs.existsSync(childEventsPath) ? readJsonLines(childEventsPath) : null;
        livenessEvidence = await collectCleanupEvidence(ports, childEvents);
      } catch (error) {
        cleanupErrors.push("liveness evidence: " + error.message);
      }
      const cleanup = buildFailureCleanupRecord(livenessEvidence, cleanupErrors, reportState);
      reportState.forcedCleanup = cleanup.forcedCleanup;
      try {
        if (!fs.existsSync(path.join(runRoot, "run-status.json"))) {
          await recordFailedRun(runRoot, runId, endpointId, failure, cleanup);
        }
      } catch (recordError) {
        console.error("failure evidence could not be written: " + recordError.message);
      }
    }
  }
  if (failure) {
    console.error("RUN_FAIL_EXCLUDED endpoint=" + endpointId + " run=" + runRoot + " reason=" + failure.message);
    process.exitCode = 1;
    return { accepted: false, runId, runRoot, failure: failure.message };
  }
  if (!successReport) throw new Error("Runtime ended without an accepted or failed-run result.");
  console.log("RUN_PASS_BOUNDED_SUBCASE endpoint=" + endpointId);
  console.log("run=" + runRoot);
  console.log("reportSha256=" + sha256(fs.readFileSync(path.join(runRoot, "result.json"))));
  console.log("offlineAuditStatus=" + successReport.audit.status);
  console.log("offlineAuditFiles=" + successReport.audit.retainedFileCount);
  return { accepted: true, runId, runRoot, audit: successReport.audit };
}

async function main(argv) {
  const options = parseArguments(argv);
  const preflight = await collectPreflight(options.endpointId);
  if (options.preflightOnly) {
    console.log("PREFLIGHT_PASS endpoint=" + options.endpointId);
    console.log("paperSha256=" + preflight.endpoint.serverJarSha256);
    console.log("candidateSha256=" + preflight.artifactInfo.candidate.sha256);
    console.log("velocityStable=" + preflight.velocityMetadata.version + "#" + preflight.velocityMetadata.build);
    console.log("services=PostgreSQL-" + EXPECTED_POSTGRES_VERSION + ",Redis-" + EXPECTED_REDIS_VERSION);
    console.log("ports=isolated-loopback-reservations-only");
    return;
  }
  await runCell(options.endpointId);
}

export {
  allocateLoopbackPorts,
  assertSafePorts,
  buildBackendConfig,
  buildPlugdevConfig,
  buildFailureCleanupRecord,
  collectCleanupEvidence,
  parseArguments,
  redisConfigArgument,
  encodeRconPacket,
  runCapture,
  waitForRedisProcess,
  stopPostgres,
  waitForSqlRetryEvidence,
  ensurePostgresAvailableForNetworkShutdown,
  cleanupFailedRunResources,
  isOutagePayCompletionAfterRecoveryBarrier,
};

if (process.argv[1] && fileURLToPath(import.meta.url) === path.resolve(process.argv[1])) {
  main(process.argv.slice(2)).catch(error => {
    console.error("T21 Paper network runner failed closed: " + String(error?.message ?? error));
    process.exitCode = 1;
  });
}

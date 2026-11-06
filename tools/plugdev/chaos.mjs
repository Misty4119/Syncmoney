/**
 * Reproducible, local-only Syncmoney chaos matrix.
 *
 * This runner intentionally uses the local PlugDev checkout and the exact
 * release artifacts supplied by the operator. Every case gets a fresh run
 * directory under Proposal/plugdev-chaos and disposable Redis/PostgreSQL
 * data. It does not change production services or Syncmoney source code.
 *
 * Examples:
 *   node tools/plugdev/chaos.mjs --players 1 --duration-seconds 30 --fault none
 *   node tools/plugdev/chaos.mjs --topology mixed --players 32 --fault redis-restart
 *   node tools/plugdev/chaos.mjs --matrix --duration-seconds 300
 */

import { createHash, randomBytes } from "node:crypto";
import { createWriteStream } from "node:fs";
import {
  access,
  appendFile,
  cp,
  mkdir,
  readFile,
  readdir,
  stat,
  writeFile,
} from "node:fs/promises";
import { constants } from "node:fs";
import { spawn, execFile } from "node:child_process";
import { promisify } from "node:util";
import { dirname, extname, isAbsolute, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as delay } from "node:timers/promises";
import net from "node:net";

const execFileAsync = promisify(execFile);

const ROOT = fileURLToPath(new URL("../..", import.meta.url));
const PLUGDEV_ROOT = process.env.PLUGDEV_ROOT || "C:\\Users\\margo\\IdeaProjects\\plugdev-main";
const MINEFLAYER_ROOT = process.env.PLUGDEV_MINEFLAYER_PATH || "C:\\Users\\margo\\IdeaProjects\\mineflayer-master";
const JAVA = process.env.PLUGDEV_JAVA || "C:\\Program Files\\Java\\jdk-25.0.3\\bin\\java.exe";
const REDIS_SERVER = process.env.PLUGDEV_REDIS_SERVER || "C:\\Users\\margo\\scoop\\apps\\redis\\current\\redis-server.exe";
const REDIS_CLI = process.env.PLUGDEV_REDIS_CLI || "C:\\Users\\margo\\scoop\\apps\\redis\\current\\redis-cli.exe";
const PG_BIN = process.env.PLUGDEV_PG_BIN || "C:\\Program Files\\PostgreSQL\\18\\bin";
const INITDB = join(PG_BIN, "initdb.exe");
const PG_CTL = join(PG_BIN, "pg_ctl.exe");
const PSQL = join(PG_BIN, "psql.exe");
const CLI = join(PLUGDEV_ROOT, "packages", "cli", "dist", "cli.js");
const BOOTSTRAP = join(PLUGDEV_ROOT, "packages", "bootstrap-paper", "build", "libs", "plugdev-bootstrap-paper.jar");
const SYNC_JAR = process.env.SYNCMONEY_JAR || join(ROOT, "build", "libs", "Syncmoney-1.3.0.jar");
const PAPI_JAR = process.env.PLACEHOLDERAPI_JAR || join(process.env.USERPROFILE || "C:\\Users\\margo", ".gradle", "caches", "modules-2", "files-2.1", "me.clip", "placeholderapi", "2.12.2", "3396f979d3ec7fbc7ed6e8e2db31a394ec568678", "placeholderapi-2.12.2.jar");
const PAPI_EXPANSION = process.env.SYNCMONEY_PAPI_JAR || join(ROOT, "syncmoney-papi-expansion", "build", "libs", "SyncmoneyExpansion-1.3.0.jar");
const CMI_LIB = process.env.CMI_LIB_JAR || "C:\\Users\\margo\\Downloads\\CMILib1.5.9.9.jar";
const CMI_JAR = process.env.CMI_JAR || "C:\\Users\\margo\\Downloads\\CMI-9.8.9.10.jar";
const VAULT_JAR = process.env.VAULT_JAR || join(ROOT, ".plugdev", "deps", "VaultUnlocked-2.20.0.jar");
const BSTATS_BASE = process.env.BSTATS_BASE_JAR || join(process.env.USERPROFILE || "C:\\Users\\margo", ".gradle", "caches", "modules-2", "files-2.1", "org.bstats", "bstats-base", "2.2.1", "c2bb62e2937b87ac1d5462b7f5b48370e41c7948", "bstats-base-2.2.1.jar");
const BSTATS_BUKKIT = process.env.BSTATS_BUKKIT_JAR || join(process.env.USERPROFILE || "C:\\Users\\margo", ".gradle", "caches", "modules-2", "files-2.1", "org.bstats", "bstats-bukkit", "2.2.1", "fe73ff3e2153925cc8391ed81e834e7d544e84ed", "bstats-bukkit-2.2.1.jar");
const SEVEN_Z = process.env.PLUGDEV_7Z || "C:\\Users\\margo\\scoop\\apps\\7zip\\current\\7z.exe";

const BASE_REDIS_PORT = 16379;
const BASE_PG_PORT = 15432;
const BASE_PROXY_PORT = 25565;
const BASE_BACKEND_PORT = 25566;
const BASE_RCON_PORT = 35665;

const TOPOLOGIES = {
  canvas: [
    { name: "canvas-a", software: "canvas" },
    { name: "canvas-b", software: "canvas" },
  ],
  mixed: [
    { name: "canvas-a", software: "canvas" },
    { name: "canvas-b", software: "canvas" },
    { name: "paper-c", software: "paper" },
  ],
};

function parseArgs(argv) {
  const out = { topology: "canvas", mode: "sync", players: 1, durationSeconds: 30, recoverySeconds: 120, fault: "none", seed: Date.now(), matrix: false, papi: true, cmi: false, repetitions: undefined };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    const next = () => argv[++i];
    if (arg === "--matrix") out.matrix = true;
    else if (arg === "--topology") out.topology = next();
    else if (arg === "--mode") out.mode = next();
    else if (arg === "--players") out.players = Number(next());
    else if (arg === "--duration-seconds") out.durationSeconds = Number(next());
    else if (arg === "--recovery-seconds") out.recoverySeconds = Number(next());
    else if (arg === "--fault") out.fault = next();
    else if (arg === "--seed") out.seed = Number(next());
    else if (arg === "--repetitions") out.repetitions = Number(next());
    else if (arg === "--papi") out.papi = next() !== "false";
    else if (arg === "--cmi") out.cmi = next() !== "false";
    else if (arg === "--help") out.help = true;
    else throw new Error(`Unknown argument: ${arg}`);
  }
  return out;
}

function usage() {
  console.log(`Syncmoney chaos runner\n\n` +
    `--topology canvas|mixed --mode sync|local_redis|cmi\n` +
    `--players 1|8|32|64|128 --duration-seconds N --recovery-seconds N\n` +
    `--fault none|bot-churn|backend-restart|proxy-restart|redis-restart|postgres-restart|queue\n` +
    `--repetitions N overrides the default 3 repetitions for fault cases\n` +
    `--matrix runs the staged matrix; --papi true|false; --cmi true|false`);
}

function assertChoice(value, allowed, label) {
  if (!allowed.includes(value)) throw new Error(`${label} must be one of ${allowed.join(", ")}; got ${value}`);
}

async function mustExist(file, label) {
  try { await access(file, constants.F_OK); }
  catch { throw new Error(`${label} not found: ${file}`); }
}

function sleep(ms) { return delay(ms); }

function randomSeed(seed, salt) {
  let state = (Number(seed) ^ (salt * 0x9e3779b9)) >>> 0;
  return () => {
    state ^= state << 13;
    state ^= state >>> 17;
    state ^= state << 5;
    state >>>= 0;
    return state / 0x100000000;
  };
}

async function sha256(file) {
  return createHash("sha256").update(await readFile(file)).digest("hex");
}

function quoteYaml(value) {
  return JSON.stringify(String(value));
}

function writeYamlConfig({ name, mode, redisDb, pgDb, queueCapacity, webPort, webKey, redisPort, pgPort, cmi }) {
  const sync = mode === "sync";
  const economyMode = cmi ? "cmi" : mode;
  return `config-version: 12
server-name: ${quoteYaml(name)}
queue-capacity: ${queueCapacity}
pubsub-enabled: true
db-enabled: ${sync}
debug: false
redis:
  enabled: true
  host: "127.0.0.1"
  port: ${redisPort}
  password: ""
  database: ${redisDb}
  pool-size: 30
database:
  enabled: ${sync}
  type: "postgresql"
  host: "127.0.0.1"
  port: ${pgPort}
  username: "syncmoney_test"
  password: ""
  database: ${quoteYaml(pgDb)}
  pool-size: 10
  minimum-idle: 2
  connection-timeout: 10000
  max-lifetime: 600000
  idle-timeout: 300000
economy:
  mode: ${quoteYaml(economyMode)}
  sync:
    vault-intercept: true
    vault-intercept-deposit: true
    vault-intercept-withdraw: true
  cmi:
    redis-prefix: "syncmoney:cmi"
    detect-interval-ms: 500
    cross-server-sync: true
    balance-mode: "api"
    debounce-ticks: 2
  local:
    sqlite-path: "plugins/Syncmoney/syncmoney.db"
    auto-save-interval: 5
display:
  currency-name: "$"
  decimal-places: 2
pay:
  cooldown-seconds: 0
  min-amount: 1
  max-amount: 1000000
  allow-in-degraded: true
  confirm-threshold: 100000
baltop:
  cache-seconds: 2
  entries-per-page: 10
  format: "smart"
circuit-breaker:
  enabled: true
  max-single-transaction: 100000000
  max-transactions-per-second: 1000000
  rapid-inflation-threshold: 0.2
  inflation-check-interval-minutes: 5
  sudden-change-threshold: 100
  redis-disconnect-lock-seconds: 5
  player-protection:
    enabled: true
    rate-limit:
      max-transactions-per-second: 1000000
      max-transactions-per-minute: 1000000
      max-amount-per-minute: 100000000
    anomaly-detection:
      warning-window-seconds: 30
      warning-threshold: 1000000
      balance-change-threshold: 1000000
    auto-unlock:
      lock-duration-minutes: 1
      max-lock-extensions: 1
      unlock-test-transactions: 1
    global-lock:
      enabled: false
    transfer-protection:
      lock-receiver: false
      receiver-lock-threshold: 0
transfer-guard:
  enabled: true
  max-wait-ms: 500
audit:
  enabled: true
  batch-size: 100
  flush-interval-ms: 1000
  redis:
    enabled: true
    window-size: 200
    migration-threshold: 50
    migration-batch-size: 50
    migration-interval-ms: 1000
    flush-interval-ms: 1000
  cleanup:
    enabled: true
    interval-hours: 24
  export:
    enabled: false
shadow-sync:
  enabled: true
  target: "local"
  trigger:
    batch-size: 10
    max-delay-ms: 1000
  storage:
    type: "sqlite"
    sqlite:
      path: "plugins/Syncmoney/data/shadow-sync.db"
  features:
    history-enabled: true
    history-retention-days: 90
web-admin:
  enabled: true
  central-mode: false
  server:
    host: "127.0.0.1"
    port: ${webPort}
  web:
    path: "syncmoney-web"
    auto-update: false
    auto-build: false
  security:
    api-key: ${quoteYaml(webKey)}
admin-permissions:
  enforce-daily-limit: false
${cmi ? "cmi:\n  database:\n    type: \"sqlite\"\n" : ""}`;
}

async function writeFileLogged(file, content) {
  await mkdir(dirname(file), { recursive: true });
  await writeFile(file, content, "utf8");
}

async function copyIfExists(source, target) {
  await mustExist(source, `dependency ${source}`);
  await mkdir(dirname(target), { recursive: true });
  await cp(source, target, { force: true });
}

async function buildPapiRuntimeJar(run) {
  if (!run.papi) return undefined;
  await mustExist(PAPI_JAR, "PlaceholderAPI JAR");
  await mustExist(BSTATS_BASE, "bStats base JAR required by PlaceholderAPI");
  await mustExist(BSTATS_BUKKIT, "bStats Bukkit JAR required by PlaceholderAPI");
  const depsDir = join(run.dir, "deps");
  const papiDir = join(depsDir, "placeholderapi-unpacked");
  const baseDir = join(depsDir, "bstats-base-unpacked");
  const bukkitDir = join(depsDir, "bstats-bukkit-unpacked");
  const runtimeJar = join(depsDir, "PlaceholderAPI-2.12.2-runtime.jar");
  await mkdir(depsDir, { recursive: true });
  try { await access(runtimeJar, constants.F_OK); return runtimeJar; } catch { /* build once per case */ }
  await runCommand(SEVEN_Z, ["x", "-y", `-o${papiDir}`, PAPI_JAR], run.dir, { timeout: 30000 });
  await runCommand(SEVEN_Z, ["x", "-y", `-o${baseDir}`, BSTATS_BASE], run.dir, { timeout: 30000 });
  await runCommand(SEVEN_Z, ["x", "-y", `-o${bukkitDir}`, BSTATS_BUKKIT], run.dir, { timeout: 30000 });
  await cp(join(baseDir, "org"), join(papiDir, "org"), { recursive: true, force: true });
  await cp(join(bukkitDir, "org"), join(papiDir, "org"), { recursive: true, force: true });
  await runCommand(SEVEN_Z, ["a", "-tzip", runtimeJar, "."], papiDir, { timeout: 30000 });
  return runtimeJar;
}

function waitForTcp(port, host = "127.0.0.1", timeoutMs = 90000) {
  const started = Date.now();
  return new Promise((resolvePromise, reject) => {
    const tryOnce = () => {
      const socket = net.connect({ port, host });
      let settled = false;
      const finish = (error) => {
        if (settled) return;
        settled = true;
        socket.destroy();
        if (!error) resolvePromise();
        else if (Date.now() - started >= timeoutMs) reject(new Error(`Timed out waiting for ${host}:${port}`));
        else setTimeout(tryOnce, 300);
      };
      socket.once("connect", () => finish());
      socket.once("error", () => finish(new Error("not ready")));
      socket.setTimeout(1000, () => finish(new Error("not ready")));
    };
    tryOnce();
  });
}

function launchProcess(command, args, cwd, logFile, env = {}) {
  const proc = spawn(command, args, {
    cwd,
    env: { ...process.env, ...env },
    stdio: ["pipe", "pipe", "pipe"],
    windowsHide: true,
  });
  const stream = createWriteStream(logFile, { flags: "a" });
  proc.stdout?.on("data", (chunk) => stream.write(chunk));
  proc.stderr?.on("data", (chunk) => stream.write(chunk));
  proc.on("close", () => stream.end());
  return proc;
}

function launchJava({ cwd, jar, memory, args = ["nogui"], logFile }) {
  return launchProcess(JAVA, [`-Xmx${memory}`, "-jar", jar, ...args], cwd, logFile, { PLUGDEV: "true" });
}

async function runCommand(command, args, cwd, options = {}) {
  const result = await execFileAsync(command, args, { cwd, windowsHide: true, timeout: options.timeout ?? 30000, maxBuffer: 8 * 1024 * 1024, env: { ...process.env, ...(options.env || {}) } });
  return result.stdout;
}

async function startRedis(run) {
  const dataDir = join(run.dir, "redis-data");
  await mkdir(dataDir, { recursive: true });
  const conf = join(run.dir, "redis.conf");
  const redisDataDir = dataDir.replaceAll("\\", "/");
  await writeFileLogged(conf, `bind 127.0.0.1\nport ${run.redisPort}\nsave ""\nappendonly no\ndir ${redisDataDir}\nprotected-mode no\n`);
  run.redis = launchProcess(REDIS_SERVER, ["redis.conf"], run.dir, join(run.dir, "redis.log"));
  await waitForTcp(run.redisPort, "127.0.0.1", 30000);
}

async function stopRedis(run) {
  if (run.redis && run.redis.exitCode === null) run.redis.kill();
  run.redis = undefined;
}

async function restartRedis(run) {
  await stopRedis(run);
  await sleep(1000);
  await startRedis(run);
}

async function startPostgres(run) {
  await mkdir(run.pgData, { recursive: true });
  await runCommand(INITDB, ["-D", run.pgData, "-U", "syncmoney_test", "--auth=trust", "--encoding=UTF8"], run.dir, { timeout: 120000 });
  await runCommand(PG_CTL, ["-D", run.pgData, "-o", `-p ${run.pgPort} -h 127.0.0.1`, "-l", run.pgLog, "-w", "start"], run.dir, { timeout: 120000 });
  await waitForTcp(run.pgPort, "127.0.0.1", 30000);
  await runCommand(PSQL, ["-h", "127.0.0.1", "-p", String(run.pgPort), "-U", "syncmoney_test", "-d", "postgres", "-v", "ON_ERROR_STOP=1", "-c", `CREATE DATABASE ${run.pgDb}`], run.dir, { timeout: 30000 });
}

async function stopPostgres(run) {
  if (!run.pgData) return;
  try { await runCommand(PG_CTL, ["-D", run.pgData, "-w", "stop", "-m", "fast"], run.dir, { timeout: 30000 }); } catch { /* preserve logs */ }
}

async function restartPostgres(run) {
  await stopPostgres(run);
  await sleep(1000);
  await runCommand(PG_CTL, ["-D", run.pgData, "-o", `-p ${run.pgPort} -h 127.0.0.1`, "-l", run.pgLog, "-w", "start"], run.dir, { timeout: 120000 });
  await waitForTcp(run.pgPort, "127.0.0.1", 30000);
}

function rconPacket(id, type, body) {
  const payload = Buffer.concat([Buffer.from(body, "utf8"), Buffer.from([0, 0])]);
  const packet = Buffer.alloc(4 + 4 + 4 + payload.length);
  packet.writeInt32LE(4 + 4 + payload.length, 0);
  packet.writeInt32LE(id, 4);
  packet.writeInt32LE(type, 8);
  payload.copy(packet, 12);
  return packet;
}

function parseRcon(buffer) {
  const packets = [];
  let offset = 0;
  while (buffer.length - offset >= 4) {
    const length = buffer.readInt32LE(offset);
    if (length < 10 || buffer.length - offset < length + 4) break;
    const packetStart = offset + 4;
    const id = buffer.readInt32LE(packetStart);
    const type = buffer.readInt32LE(packetStart + 4);
    const body = buffer.subarray(packetStart + 8, offset + 4 + length - 2).toString("utf8");
    packets.push({ id, type, body });
    offset += length + 4;
  }
  return { packets, rest: buffer.subarray(offset) };
}

async function rcon(port, password, command) {
  const socket = net.connect({ port, host: "127.0.0.1" });
  socket.setTimeout(10000);
  let buffer = Buffer.alloc(0);
  const packets = [];
  let notify;
  const waitPacket = () => new Promise((resolvePromise, reject) => {
    if (packets.length) return resolvePromise(packets.shift());
    notify = { resolve: resolvePromise, reject };
  });
  socket.on("data", (chunk) => {
    buffer = Buffer.concat([buffer, chunk]);
    const parsed = parseRcon(buffer);
    buffer = parsed.rest;
    packets.push(...parsed.packets);
    if (notify && packets.length) { const n = notify; notify = undefined; n.resolve(packets.shift()); }
  });
  try {
    await new Promise((resolvePromise, reject) => {
      socket.once("connect", resolvePromise);
      socket.once("error", reject);
    });
    socket.write(rconPacket(1, 3, password));
    const auth = await waitPacket();
    if (auth.id === -1) throw new Error(`RCON authentication failed on ${port}`);
    socket.write(rconPacket(2, 2, command));
    const response = await waitPacket();
    return response.body;
  } finally {
    socket.destroy();
  }
}

async function waitForLog(file, pattern, timeoutMs = 120000) {
  const started = Date.now();
  while (Date.now() - started < timeoutMs) {
    try {
      if (pattern.test(await readFile(file, "utf8"))) return;
    } catch { /* process has not created the file yet */ }
    await sleep(500);
  }
  throw new Error(`Timed out waiting for ${pattern} in ${file}`);
}

async function createRun(options, repetition = 1) {
  const stamp = new Date().toISOString().replace(/[:.]/g, "-");
  const label = `${stamp}-${options.topology}-${options.mode}-${options.players}p-${options.fault}-r${repetition}-${options.seed}`;
  const dir = join(ROOT, "Proposal", "plugdev-chaos", label);
  await mkdir(dir, { recursive: true });
  const run = {
    ...options,
    repetition,
    dir,
    redisPort: BASE_REDIS_PORT,
    pgPort: BASE_PG_PORT,
    proxyPort: BASE_PROXY_PORT,
    redisDb: 15,
    pgDb: `syncmoney_chaos_${String(options.seed).replace(/[^0-9]/g, "").slice(-8) || "test"}`,
    pgData: join(dir, "postgres-data"),
    pgLog: join(dir, "postgres.log"),
    rconPassword: randomBytes(18).toString("hex"),
    forwardingSecret: randomBytes(18).toString("hex"),
    webKey: randomBytes(18).toString("hex"),
    startedAt: new Date().toISOString(),
  };
  const backends = TOPOLOGIES[options.topology].map((backend, index) => ({
    ...backend,
    port: BASE_BACKEND_PORT + index,
    rconPort: BASE_RCON_PORT + index,
    webPort: 18080 + index,
  }));
  run.backends = backends;
  const configDir = join(dir, "configs");
  const backendYaml = [];
  for (const backend of backends) {
    const config = join(configDir, `${backend.name}.yml`);
    await writeFileLogged(config, writeYamlConfig({ name: `chaos-${options.mode}-${backend.name}-${String(options.seed).slice(-8)}`, mode: options.mode, redisDb: run.redisDb, pgDb: run.pgDb, queueCapacity: options.fault === "queue" ? 32 : 50000, webPort: backend.webPort, webKey: run.webKey, redisPort: run.redisPort, pgPort: run.pgPort, cmi: options.mode === "cmi" }));
    backendYaml.push({ config, backend });
  }
  const plugdevConfig = {
    type: "network",
    proxy: { software: "velocity-ctd", version: "latest", port: run.proxyPort, loginRateLimit: 0 },
    forwarding: { secret: run.forwardingSecret },
    rcon: { enabled: true, basePort: BASE_RCON_PORT, password: run.rconPassword },
    run: { autoStop: false, cleanup: "never" },
    backends: backends.map((backend, index) => ({
      name: backend.name,
      software: backend.software,
      version: "26.2",
      port: backend.port,
      maxPlayers: options.players,
      plugin: { jar: SYNC_JAR, name: "Syncmoney", config: backendYaml[index].config },
    })),
  };
  const configPath = join(dir, "plugdev.yml");
  await writeFileLogged(configPath, `${JSON.stringify(plugdevConfig, null, 2)}\n`);
  await writeFileLogged(join(dir, "case.json"), JSON.stringify({ ...run, plugdevConfig, artifacts: { syncJar: { path: SYNC_JAR, sha256: await sha256(SYNC_JAR) }, papiJar: options.papi ? { path: PAPI_JAR, sha256: await sha256(PAPI_JAR) } : undefined, papiExpansion: options.papi ? { path: PAPI_EXPANSION, sha256: await sha256(PAPI_EXPANSION) } : undefined, cmi: options.mode === "cmi" ? { cmi: CMI_JAR, cmiLib: CMI_LIB } : undefined } }, null, 2));
  return run;
}

async function seedDependencies(run) {
  await mustExist(SYNC_JAR, "Syncmoney JAR");
  await mustExist(VAULT_JAR, "VaultUnlocked JAR");
  await mustExist(BOOTSTRAP, "PlugDev bootstrap JAR");
  if (run.papi) {
    await mustExist(PAPI_JAR, "PlaceholderAPI JAR");
    await mustExist(PAPI_EXPANSION, "Syncmoney PAPI expansion JAR");
    run.papiRuntimeJar = await buildPapiRuntimeJar(run);
  }
  if (run.mode === "cmi") {
    await mustExist(CMI_LIB, "CMILib JAR");
    await mustExist(CMI_JAR, "CMI JAR");
  }
  for (const backend of run.backends) {
    const plugins = join(run.dir, ".plugdev", "network", "backends", backend.name, "plugins");
    await mkdir(plugins, { recursive: true });
    await copyIfExists(VAULT_JAR, join(plugins, "VaultUnlocked.jar"));
    await copyIfExists(BOOTSTRAP, join(plugins, "plugdev-bootstrap-paper.jar"));
    if (run.papi && backend.software === "paper") {
      await copyIfExists(run.papiRuntimeJar, join(plugins, "PlaceholderAPI.jar"));
      await copyIfExists(PAPI_EXPANSION, join(plugins, "PlaceholderAPI", "expansions", "SyncmoneyExpansion-1.3.0.jar"));
    }
    if (run.mode === "cmi") {
      await copyIfExists(CMI_LIB, join(plugins, "CMILib.jar"));
      await copyIfExists(CMI_JAR, join(plugins, "CMI.jar"));
    }
  }
}

async function startNetwork(run) {
  run.plugdevLog = join(run.dir, "plugdev-network.log");
  run.plugdev = launchProcess(process.execPath, [CLI, "network", "--config", join(run.dir, "plugdev.yml"), "--no-join", "--no-watch"], run.dir, run.plugdevLog, {
    JAVA_HOME: dirname(dirname(JAVA)),
    PLUGDEV_MINEFLAYER_PATH: MINEFLAYER_ROOT,
  });
  await waitForTcp(run.proxyPort, "127.0.0.1", 180000);
  for (const backend of run.backends) {
    await waitForTcp(backend.port, "127.0.0.1", 180000);
    await waitForTcp(backend.rconPort, "127.0.0.1", 180000);
  }
  await waitForLog(run.plugdevLog, /Network ready/, 30000);
}

function backendRcon(run, backend, command) {
  return rcon(backend.rconPort, run.rconPassword, command);
}

async function findListeningPid(port) {
  const output = await runCommand("netstat.exe", ["-ano"], ROOT).catch(() => "");
  for (const line of output.split(/\r?\n/)) {
    const fields = line.trim().split(/\s+/);
    if (fields.length < 5 || fields[0] !== "TCP" || fields[3] !== "LISTENING") continue;
    if (fields[1].endsWith(`:${port}`)) return fields[4];
  }
  return undefined;
}

function offlineUuid(name) {
  return createHash("md5").update(`OfflinePlayer:${name}`).digest("hex").replace(/^(.{8})(.{4})(.{4})(.{4})(.{12})$/, "$1-$2-$3-$4-$5");
}

async function loadMineflayer() {
  const entry = join(MINEFLAYER_ROOT, "index.js");
  await mustExist(entry, "custom Mineflayer package");
  const { createRequire } = await import("node:module");
  return createRequire(entry)(entry);
}

function waitBotEvent(bot, event, timeoutMs = 90000) {
  return new Promise((resolvePromise, reject) => {
    const timer = setTimeout(() => { cleanup(); reject(new Error(`Bot ${bot.username} timed out waiting for ${event}`)); }, timeoutMs);
    const cleanup = () => { clearTimeout(timer); bot.removeListener(event, onEvent); bot.removeListener("error", onError); bot.removeListener("kicked", onKicked); };
    const onEvent = (...args) => { cleanup(); resolvePromise(args); };
    const onError = (error) => { cleanup(); reject(error); };
    const onKicked = (reason) => { cleanup(); reject(new Error(`Bot ${bot.username} kicked: ${reason}`)); };
    bot.once(event, onEvent); bot.once("error", onError); bot.once("kicked", onKicked);
  });
}

async function connectBots(run) {
  const mineflayer = await loadMineflayer();
  run.bots = [];
  const batchSize = 8;
  for (let start = 0; start < run.players; start += batchSize) {
    const batch = [];
    for (let i = start; i < Math.min(start + batchSize, run.players); i += 1) {
      batch.push((async () => {
        const name = `Chaos${String(i + 1).padStart(3, "0")}`;
        const bot = mineflayer.createBot({ host: "127.0.0.1", port: run.proxyPort, username: name, auth: "offline", version: "26.2", hideErrors: true });
        bot.chaosIndex = i;
        bot.chaosName = name;
        bot.chaosUuid = offlineUuid(name);
        bot.on("error", (error) => run.botErrors.push(`${name}: ${error.message}`));
        bot.on("kicked", (reason) => run.botKicks.push(`${name}: ${reason}`));
        run.bots.push(bot);
        await waitBotEvent(bot, "spawn", 120000);
        await sleep(40 + Math.floor(randomSeed(run.seed, i)() * 180));
      })());
    }
    await Promise.all(batch);
  }
  for (const bot of run.bots) {
    await backendRcon(run, run.backends[0], `syncmoney admin set ${bot.username} 1000`);
    await sleep(30);
  }
  await sleep(3000);
}

async function disconnectBot(bot) {
  try { bot.quit("chaos churn"); } catch { /* already disconnected */ }
}

async function commandBot(bot, command) {
  if (!bot || !bot.chat) return;
  try { bot.chat(command); } catch { /* connection may be in reconnect window */ }
}

async function exerciseBot(run, bot, index, deadline) {
  const random = randomSeed(run.seed + run.repetition * 101, index + 1);
  const names = run.bots.map((candidate) => candidate.username);
  await sleep(Math.floor(random() * 4000));
  while (Date.now() < deadline) {
    const target = names[(Math.floor(random() * names.length) + 1 + index) % names.length] || bot.username;
    const roll = random();
    if (roll < 0.70 && names.length > 1) {
      const amount = [1, 2, 5, 10, 25][Math.floor(random() * 5)];
      await commandBot(bot, run.mode === "cmi" ? `/cmi pay ${target} ${amount}` : `/pay ${target} ${amount}`);
    } else if (roll < 0.85) {
      await commandBot(bot, run.mode === "cmi" ? `/cmi pay ${target} 1000000` : `/pay ${target} 1000000`);
    } else if (roll < 0.90) {
      await commandBot(bot, random() < 0.5 ? `/pay ${bot.username} 1` : "/pay NotAChaosPlayer 1");
    } else if (roll < 0.95) {
      const paperAvailable = run.papi && run.backends.some((backend) => backend.software === "paper");
      await commandBot(bot, paperAvailable && random() < 0.5 ? `/papi parse ${bot.username} %syncmoney_balance%` : random() < 0.5 ? "/money" : "/baltop");
    } else if (roll < 0.98) {
      await commandBot(bot, `/server ${run.backends[Math.floor(random() * run.backends.length)].name}`);
    } else {
      await commandBot(bot, `/tp ${bot.username} 0 80 0`);
    }
    await sleep(250 + Math.floor(random() * 2400));
  }
}

async function injectFault(run) {
  const midpoint = Math.max(1000, Math.floor(run.durationSeconds * 500));
  await sleep(midpoint);
  run.faultStartedAt = new Date().toISOString();
  if (run.fault === "redis-restart") await restartRedis(run);
  else if (run.fault === "postgres-restart") await restartPostgres(run);
  else if (run.fault === "bot-churn") {
    const count = Math.max(1, Math.floor(run.bots.length * 0.2));
    const random = randomSeed(run.seed, 991);
    const selected = [...run.bots].sort(() => random() - 0.5).slice(0, count);
    for (const bot of selected) await disconnectBot(bot);
    await sleep(2500);
    run.churned = selected.map((bot) => bot.username);
  } else if (run.fault === "backend-restart") {
    const backend = run.backends[0];
    await backendRcon(run, backend, "stop");
    await sleep(3000);
    backend.process = launchJava({ cwd: join(run.dir, ".plugdev", "network", "backends", backend.name), jar: join(run.dir, ".plugdev", "network", "backends", backend.name, "server.jar"), memory: "2G", logFile: join(run.dir, `${backend.name}-manual-restart.log`) });
    await waitForTcp(backend.port, "127.0.0.1", 180000);
    await waitForTcp(backend.rconPort, "127.0.0.1", 180000);
  } else if (run.fault === "proxy-restart") {
    const proxyDir = join(run.dir, ".plugdev", "network", "proxy");
    const proxyJar = join(proxyDir, "velocity.jar");
    try { await runCommand(REDIS_CLI, ["-h", "127.0.0.1", "-p", String(run.redisPort), "ping"], run.dir); } catch { /* no-op probe */ }
    const proxyPid = await findListeningPid(run.proxyPort);
    if (proxyPid) await runCommand("taskkill.exe", ["/PID", proxyPid, "/T", "/F"], ROOT, { timeout: 15000 }).catch(() => undefined);
    await sleep(2500);
    run.proxyManual = launchJava({ cwd: proxyDir, jar: proxyJar, memory: "1G", args: ["nogui"], logFile: join(run.dir, "proxy-manual-restart.log") });
    await waitForTcp(run.proxyPort, "127.0.0.1", 180000);
  } else if (run.fault === "queue") {
    run.queueTestResponse = await backendRcon(run, run.backends[0], "syncmoney test concurrent-pay 50 100");
    run.queueTestInjected = true;
  }
  run.faultCompletedAt = new Date().toISOString();
}

async function redisSnapshot(run) {
  const keysText = await runCommand(REDIS_CLI, ["-h", "127.0.0.1", "-p", String(run.redisPort), "-n", String(run.redisDb), "--raw", "--scan", "--pattern", "syncmoney:balance:*"] , run.dir).catch(() => "");
  const keys = keysText.split(/\r?\n/).map((value) => value.trim()).filter(Boolean);
  const values = [];
  for (const key of keys) {
    const value = await runCommand(REDIS_CLI, ["-h", "127.0.0.1", "-p", String(run.redisPort), "-n", String(run.redisDb), "--raw", "get", key], run.dir).catch(() => "");
    const amount = Number(value.trim());
    if (Number.isFinite(amount)) values.push({ key, amount });
  }
  const baltopText = await runCommand(REDIS_CLI, ["-h", "127.0.0.1", "-p", String(run.redisPort), "-n", String(run.redisDb), "--raw", "zrange", "syncmoney:baltop", "0", "-1", "WITHSCORES"], run.dir).catch(() => "");
  const baltopParts = baltopText.split(/\r?\n/).map((value) => value.trim()).filter(Boolean);
  const baltopValues = [];
  for (let index = 0; index + 1 < baltopParts.length; index += 2) {
    const amount = Number(baltopParts[index + 1]);
    if (Number.isFinite(amount)) baltopValues.push({ key: baltopParts[index], amount });
  }
  const baltopTotalText = await runCommand(REDIS_CLI, ["-h", "127.0.0.1", "-p", String(run.redisPort), "-n", String(run.redisDb), "--raw", "get", "syncmoney:baltop:total"], run.dir).catch(() => "");
  const baltopTotal = Number(baltopTotalText.trim());
  return {
    keyCount: values.length,
    sum: values.reduce((sum, value) => sum + value.amount, 0),
    negative: values.filter((value) => value.amount < 0),
    values,
    baltopKeyCount: baltopValues.length,
    baltopSum: baltopValues.reduce((sum, value) => sum + value.amount, 0),
    baltopTotal: Number.isFinite(baltopTotal) ? baltopTotal : null,
  };
}

async function postgresSnapshot(run) {
  if (run.mode !== "sync") return undefined;
  const sql = "SELECT COALESCE(SUM(balance),0), COUNT(*), COALESCE(MIN(version),0), COALESCE(MAX(version),0) FROM players";
  const stdout = await runCommand(PSQL, ["-h", "127.0.0.1", "-p", String(run.pgPort), "-U", "syncmoney_test", "-d", run.pgDb, "-At", "-F", ",", "-c", sql], run.dir).catch(() => "");
  const [sum, count, minVersion, maxVersion] = stdout.trim().split(",").map(Number);
  return { sum, count, minVersion, maxVersion, raw: stdout.trim() };
}

async function readLogs(run) {
  const files = [];
  async function visit(dir) {
    let entries = [];
    try { entries = await readdir(dir, { withFileTypes: true }); } catch { return; }
    for (const entry of entries) {
      const file = join(dir, entry.name);
      if (entry.isDirectory()) await visit(file);
      else if (extname(entry.name).toLowerCase() === ".log" || entry.name === "latest.log") files.push(file);
    }
  }
  await visit(run.dir);
  const errors = [];
  const optionalErrors = [];
  const warnings = [];
  for (const file of files) {
    const text = await readFile(file, "utf8").catch(() => "");
    for (const line of text.split(/\r?\n/)) {
      if (/(Could not load plugin .*PlaceholderAPI|InvalidPluginException.*PlaceholderAPI|ExceptionInInitializerError)/i.test(line)) optionalErrors.push({ file, line });
      else if (/(?:ERROR.*FoliaWatchdogThread|FoliaWatchdogThread.*ERROR|deadlock|stalled)/i.test(line)) errors.push({ file, line });
      else if (/(Unhandled exception|Could not pass event|Could not load plugin|InvalidPluginException|Failed to load plugin|Deadlock|Dropping DB write task|ACCEPTANCE FAIL|SEVERE|Exception in thread)/i.test(line)) errors.push({ file, line });
      else if (/(Error occurred while enabling|generated an exception|NullPointerException|PoolInitializationException)/i.test(line) && !/CrashReport preload|Perflib|system info|oshi\.software\.os\.OSProcess/i.test(line)) errors.push({ file, line });
      else if (/(ERROR|WARN|WARNING)/i.test(line)) warnings.push({ file, line });
    }
  }
  return { files, errors, optionalErrors, warnings: warnings.slice(-500) };
}

async function stopProcess(proc) {
  if (!proc || proc.exitCode !== null) return;
  if (process.platform === "win32" && proc.pid) {
    await runCommand("taskkill.exe", ["/PID", String(proc.pid), "/T", "/F"], ROOT, { timeout: 15000 }).catch(() => undefined);
    return;
  }
  try { proc.stdin?.write("stop\n"); } catch { /* pipe closed */ }
  await sleep(5000);
  if (proc.exitCode === null) proc.kill();
}

async function verify(run) {
  await sleep(5000);
  const redis = await redisSnapshot(run);
  const postgres = await postgresSnapshot(run);
  const logs = await readLogs(run);
  const expectedTotal = run.players * 1000 + (run.queueTestInjected ? 2_000_000 : 0);
  const expectedBaltopTotal = run.players * 1000;
  const checks = {
    moneyConservedRedis: Math.abs(redis.sum - expectedTotal) < 0.01,
    noNegativeRedisBalances: redis.negative.length === 0,
    playersSeeded: redis.keyCount >= run.players + (run.queueTestInjected ? 2 : 0),
    postgresMoneyConserved: postgres ? Math.abs(postgres.sum - expectedTotal) < 0.01 : true,
    postgresVersionsNonNegative: postgres ? postgres.minVersion >= 0 : true,
    baltopInternalConsistency: redis.baltopTotal != null && Math.abs(redis.baltopTotal - redis.baltopSum) < 0.01,
    baltopMoneyConserved: Math.abs(redis.baltopSum - expectedBaltopTotal) < 0.01,
    noHardLogFailures: logs.errors.length === 0,
    botsHadNoErrors: run.botErrors.length === 0,
    papiCompatibility: run.papi ? logs.optionalErrors.length === 0 : true,
  };
  return { checks, expectedTotal, redis, postgres, logs, botErrors: run.botErrors, botKicks: run.botKicks };
}

async function runCase(options, repetition = 1) {
  const run = await createRun(options, repetition);
  run.botErrors = [];
  run.botKicks = [];
  let result;
  try {
    await seedDependencies(run);
    await startRedis(run);
    if (run.mode === "sync") await startPostgres(run);
    await startNetwork(run);
    await connectBots(run);
    const initial = await verify(run);
    const deadline = Date.now() + run.durationSeconds * 1000;
    const faultPromise = run.fault === "none" ? Promise.resolve() : injectFault(run);
    await Promise.all(run.bots.map((bot, index) => exerciseBot(run, bot, index, deadline)));
    await faultPromise;
    await sleep(run.recoverySeconds * 1000);
    result = await verify(run);
    result.initial = initial;
    result.queueTest = run.queueTestInjected ? { injected: true, response: run.queueTestResponse } : { injected: false };
    result.case = { topology: run.topology, mode: run.mode, players: run.players, durationSeconds: run.durationSeconds, recoverySeconds: run.recoverySeconds, fault: run.fault, seed: run.seed, repetition };
  } catch (error) {
    result = { case: { topology: run.topology, mode: run.mode, players: run.players, durationSeconds: run.durationSeconds, recoverySeconds: run.recoverySeconds, fault: run.fault, seed: run.seed, repetition }, fatal: error instanceof Error ? error.stack || error.message : String(error), botErrors: run.botErrors, botKicks: run.botKicks };
  } finally {
    for (const bot of run.bots || []) await disconnectBot(bot);
    await stopProcess(run.proxyManual);
    for (const backend of run.backends || []) await stopProcess(backend.process);
    await stopProcess(run.plugdev);
    await stopPostgres(run);
    await stopRedis(run);
    run.finishedAt = new Date().toISOString();
    await writeFileLogged(join(run.dir, "result.json"), JSON.stringify(result, null, 2));
    await writeFileLogged(join(run.dir, "summary.md"), renderSummary(run, result));
  }
  return { dir: run.dir, result };
}

function renderSummary(run, result) {
  const checks = result?.checks || {};
  const status = result?.fatal ? "FATAL" : Object.values(checks).every(Boolean) ? "PASS" : "FAIL";
  return `# Syncmoney PlugDev chaos case\n\n` +
    `- Status: **${status}**\n- Topology: ${run.topology}\n- Mode: ${run.mode}\n- Players: ${run.players}\n- Fault: ${run.fault}\n- Seed: ${run.seed}\n- Repetition: ${run.repetition}\n\n` +
    (result?.fatal ? `## Fatal error\n\n\`\`\`\n${result.fatal}\n\`\`\`\n` : `## Checks\n\n${Object.entries(checks).map(([key, value]) => `- ${value ? "PASS" : "FAIL"} ${key}`).join("\n")}\n\nRedis: ${JSON.stringify(result.redis)}\nPostgreSQL: ${JSON.stringify(result.postgres)}\nLog errors: ${result.logs?.errors?.length ?? "n/a"}\n`);
}

function matrixCases(base) {
  const cases = [];
  const scales = [1, 8, 32, 64, 128];
  for (const topology of ["canvas", "mixed"]) {
    for (const mode of ["local_redis", "sync"]) {
      for (const players of scales) {
        cases.push({ ...base, topology, mode, players, fault: "none" });
      }
    }
  }
  for (const fault of ["backend-restart", "redis-restart", "postgres-restart", "bot-churn", "queue"]) {
    for (const topology of ["canvas", "mixed"]) {
      for (const mode of fault === "postgres-restart" ? ["sync"] : ["local_redis", "sync"]) {
        cases.push({ ...base, topology, mode, players: 32, fault });
      }
    }
  }
  if (base.cmi) {
    for (const topology of ["canvas", "mixed"]) for (const players of [1, 8, 32]) cases.push({ ...base, topology, mode: "cmi", players, fault: "none" });
  }
  return cases;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) return usage();
  assertChoice(options.topology, ["canvas", "mixed"], "topology");
  assertChoice(options.mode, ["sync", "local_redis", "cmi"], "mode");
  assertChoice(options.players, [1, 8, 32, 64, 128], "players");
  assertChoice(options.fault, ["none", "bot-churn", "backend-restart", "proxy-restart", "redis-restart", "postgres-restart", "queue"], "fault");
  if (options.repetitions !== undefined && (!Number.isInteger(options.repetitions) || options.repetitions < 1)) throw new Error("repetitions must be a positive integer");
  if (options.mode === "cmi" && !options.cmi) throw new Error("CMI mode requires --cmi true");
  if (options.fault === "postgres-restart" && options.mode !== "sync") throw new Error("postgres-restart requires sync mode");
  if (options.fault === "proxy-restart" && options.players < 8) throw new Error("proxy-restart requires at least 8 players");
  const cases = options.matrix ? matrixCases(options) : [options];
  const report = [];
  for (const item of cases) {
    const repetitions = item.fault === "none" ? 1 : (options.repetitions ?? 3);
    for (let repetition = 1; repetition <= repetitions; repetition += 1) {
      const seeded = { ...item, seed: Number(item.seed) + report.length * 104729 };
      console.log(`\n=== chaos case ${report.length + 1}/${cases.length * repetitions}: ${seeded.topology}/${seeded.mode}/${seeded.players} players/${seeded.fault} #${repetition} ===`);
      const outcome = await runCase(seeded, repetition);
      report.push(outcome);
      console.log(`Artifacts: ${outcome.dir}`);
      if (outcome.result?.fatal) console.log(`FATAL: ${outcome.result.fatal}`);
      else console.log(`Checks: ${JSON.stringify(outcome.result.checks)}`);
    }
  }
  const reportDir = join(ROOT, "Proposal", "plugdev-chaos");
  await mkdir(reportDir, { recursive: true });
  const reportFile = join(reportDir, `matrix-${new Date().toISOString().replace(/[:.]/g, "-")}.json`);
  await writeFile(reportFile, JSON.stringify(report, null, 2));
  const failures = report.filter((item) => item.result?.fatal || Object.values(item.result?.checks || {}).some((value) => !value));
  console.log(`\nMatrix report: ${reportFile}`);
  console.log(`Cases: ${report.length}; failures: ${failures.length}`);
  if (failures.length) process.exitCode = 1;
}

main().catch((error) => {
  console.error(error instanceof Error ? error.stack || error.message : error);
  process.exitCode = 1;
});

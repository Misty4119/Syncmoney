import { createHash, randomBytes } from "node:crypto";
import { access, copyFile, mkdir, readFile, writeFile } from "node:fs/promises";
import { createRequire } from "node:module";
import { createConnection } from "node:net";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
const DEFAULT_CANDIDATE_VERSION = "1.4.0-alpha.1";
const MINIMUM_PAPER = [1, 21, 6];
const ACCEPTANCE_PORT = 25675;
const VAULT_UNLOCKED = {
  name: "VaultUnlocked",
  source: "hangar",
  author: "TNE",
  slug: "VaultUnlocked",
  version: "2.20.2",
};

function compareVersionParts(left, right) {
  for (let index = 0; index < Math.max(left.length, right.length); index += 1) {
    const difference = (left[index] ?? 0) - (right[index] ?? 0);
    if (difference !== 0) return difference;
  }
  return 0;
}

export function validateOptions(args, fallbackCandidateVersion = DEFAULT_CANDIDATE_VERSION) {
  if (args.length > 5) {
    throw new Error("Usage: node tools/plugdev/prepare.mjs <paper|folia|canvas> <minecraft-version> <local|local_redis|sync> <on|off> [candidate-version]");
  }

  const [software = "paper", minecraftVersion = "1.21.6", mode = "local", features = "off", candidateVersion = fallbackCandidateVersion] = args;
  if (!["paper", "folia", "canvas"].includes(software)) {
    throw new Error("Software must be paper, folia, or canvas.");
  }
  if (!/^\d+\.\d+(?:\.\d+)?$/.test(minecraftVersion)) {
    throw new Error("Minecraft version must use numeric major.minor[.patch] form.");
  }
  if (minecraftVersion.startsWith("1.") && compareVersionParts(minecraftVersion.split(".").map(Number), MINIMUM_PAPER) < 0) {
    throw new Error("Paper 1.21.6 or newer is required for the 1.4 runtime profile.");
  }
  if (!["local", "local_redis", "sync"].includes(mode)) {
    throw new Error("Mode must be local, local_redis, or sync.");
  }
  if (!["on", "off"].includes(features)) {
    throw new Error("Features must be on or off.");
  }
  if (!/^\d+\.\d+\.\d+(?:-(?:alpha|beta)\.\d+)?$/.test(candidateVersion)) {
    throw new Error("Candidate version must be a stable or alpha/beta semantic version.");
  }

  return { software, minecraftVersion, mode, features, candidateVersion };
}

export function createProfileConfig(options) {
  const { software, minecraftVersion, candidateVersion } = options;
  return {
    type: "plugin",
    server: software,
    version: minecraftVersion,
    bind: "127.0.0.1",
    port: ACCEPTANCE_PORT,
    rcon: { enabled: true },
    build: {
      system: "gradle",
      task: "help",
      jarTask: "help",
      jarPattern: `build/libs/Syncmoney-${candidateVersion}.jar`,
    },
    deps: [{ ...VAULT_UNLOCKED }],
    dev: { mode: "server", onlineMode: false, applySkins: false },
    jvm: { args: [`-Dsyncmoney.acceptance.version=${candidateVersion}`] },
    run: { cleanup: "never" },
    client: { launcher: "none", joinOnReady: false },
  };
}

export function createEconomySettings(defaultConfig, options, env = process.env) {
  const { software, mode, features } = options;
  const enabled = features === "on";
  const dbType = (env.SYNCMONEY_TEST_DB_TYPE ?? "postgresql").toLowerCase();
  if (!["mysql", "postgresql"].includes(dbType)) {
    throw new Error(`Unsupported test database type: ${dbType}`);
  }
  const dbPort = Number(env.SYNCMONEY_TEST_DB_PORT ?? (dbType === "postgresql" ? 5432 : 3306));
  const redisPort = Number(env.SYNCMONEY_TEST_REDIS_PORT ?? 16379);
  if (!Number.isInteger(dbPort) || dbPort < 1 || dbPort > 65535) {
    throw new Error(`Invalid test database port: ${dbPort}`);
  }
  if (!Number.isInteger(redisPort) || redisPort < 1 || redisPort > 65535) {
    throw new Error(`Invalid test Redis port: ${redisPort}`);
  }

  const settings = structuredClone(defaultConfig);
  settings["server-name"] = `acceptance-${software}`;
  settings["config-version"] ??= 12;
  settings["db-enabled"] = mode === "sync";
  settings["pubsub-enabled"] = mode !== "local";
  settings.redis = {
    ...settings.redis,
    enabled: mode !== "local",
    host: "127.0.0.1",
    port: redisPort,
    database: 15,
  };
  settings.database = {
    ...settings.database,
    enabled: mode === "sync",
    type: dbType,
    host: "127.0.0.1",
    port: dbPort,
    username: env.SYNCMONEY_TEST_DB_USER ?? (dbType === "postgresql" ? "syncmoney_test" : "root"),
    password: env.SYNCMONEY_TEST_DB_PASSWORD ?? "",
    database: env.SYNCMONEY_TEST_DB_NAME ?? "syncmoney_acceptance",
  };
  settings.economy = { ...settings.economy, mode };
  settings["circuit-breaker"] = {
    ...settings["circuit-breaker"],
    enabled,
    "player-protection": { ...settings["circuit-breaker"]?.["player-protection"], enabled },
  };
  settings["shadow-sync"] = {
    ...settings["shadow-sync"],
    enabled,
    target: "local",
    storage: { ...settings["shadow-sync"]?.storage, type: "sqlite" },
  };
  settings.audit = {
    ...settings.audit,
    enabled,
    cleanup: { ...settings.audit?.cleanup, enabled },
    export: { ...settings.audit?.export, enabled: false },
    redis: { ...settings.audit?.redis, enabled: mode !== "local" },
  };
  settings["discord-webhook"] = { ...settings["discord-webhook"], enabled: false };
  settings["transfer-guard"] = { ...settings["transfer-guard"], enabled };
  settings["web-admin"] = {
    ...settings["web-admin"],
    enabled,
    server: { ...settings["web-admin"]?.server, host: "127.0.0.1", port: 18080 },
    security: {
      ...settings["web-admin"]?.security,
      ...(enabled ? { "api-key": randomBytes(32).toString("hex") } : {}),
    },
    web: { ...settings["web-admin"]?.web, "auto-build": false, "auto-update": false },
  };
  settings.pay = { ...settings.pay, "cooldown-seconds": 0 };
  return settings;
}

function addPort(ports, value, description) {
  if (value == null || value === "") return;
  const port = Number(value);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`Cannot confirm the PlugDev server is stopped: invalid ${description}.`);
  }
  ports.add(port);
}

async function readRunPorts(root) {
  const propertiesPath = join(root, ".plugdev/run/server.properties");
  let contents;
  try {
    contents = await readFile(propertiesPath, "utf8");
  } catch (error) {
    if (error?.code === "ENOENT") return [];
    throw error;
  }

  const properties = new Map();
  for (const line of contents.split(/\r?\n/)) {
    if (!line || /^\s*[#!]/.test(line)) continue;
    const separator = line.indexOf("=");
    if (separator < 0) continue;
    properties.set(line.slice(0, separator).trim(), line.slice(separator + 1).trim());
  }

  const ports = new Set();
  const gamePort = properties.get("server-port");
  if (gamePort == null) {
    throw new Error(`Cannot confirm the PlugDev server is stopped: ${propertiesPath} has no server-port.`);
  }
  addPort(ports, gamePort, "game port");
  if (properties.get("enable-rcon")?.toLowerCase() === "true") {
    const rconPort = properties.get("rcon.port");
    if (rconPort == null) {
      throw new Error(`Cannot confirm the PlugDev server is stopped: ${propertiesPath} has no rcon.port.`);
    }
    addPort(ports, rconPort, "RCON port");
  }
  return [...ports];
}

function assertPortClosed(port) {
  return new Promise((resolve, reject) => {
    const socket = createConnection({ host: "127.0.0.1", port });
    socket.setTimeout(1_000);
    socket.once("connect", () => {
      socket.destroy();
      reject(new Error(`Stop the PlugDev server: local port ${port} is still accepting connections.`));
    });
    socket.once("timeout", () => {
      socket.destroy();
      reject(new Error(`Cannot confirm the PlugDev server is stopped: local port ${port} timed out.`));
    });
    socket.once("error", (error) => {
      socket.destroy();
      if (error?.code === "ECONNREFUSED") {
        resolve();
      } else {
        reject(new Error(`Cannot confirm the PlugDev server is stopped on local port ${port}.`, { cause: error }));
      }
    });
  });
}

export async function assertStopped(root, gamePort = ACCEPTANCE_PORT, rconPort = gamePort + 10_000) {
  const ports = new Set();
  addPort(ports, gamePort, "game port");
  addPort(ports, rconPort, "RCON port");

  try {
    const contents = await readFile(join(root, ".plugdev/session.json"), "utf8");
    if (contents.trim()) {
      const session = JSON.parse(contents);
      if (session?.pid) {
        try {
          process.kill(session.pid, 0);
          throw new Error("Stop the PlugDev session before preparing a profile.");
        } catch (error) {
          if (error instanceof Error && error.message.startsWith("Stop the PlugDev")) throw error;
          if (error?.code !== "ESRCH") throw error;
        }
      }
      addPort(ports, session?.gamePort, "session game port");
      addPort(ports, session?.rconPort, "session RCON port");
    }
  } catch (error) {
    if (error?.code !== "ENOENT") throw error;
  }

  for (const port of await readRunPorts(root)) ports.add(port);
  await Promise.all([...ports].map(assertPortClosed));
}

async function sha256(path) {
  return createHash("sha256").update(await readFile(path)).digest("hex");
}

export async function prepareProfile(args, env = process.env) {
  const options = validateOptions(args, env.SYNCMONEY_CANDIDATE_VERSION ?? DEFAULT_CANDIDATE_VERSION);
  const candidateJar = join(ROOT, "build", "libs", `Syncmoney-${options.candidateVersion}.jar`);
  const acceptanceJar = join(ROOT, "build", "acceptance", "SyncmoneyAcceptance.jar");
  const run = join(ROOT, ".plugdev", "run");
  const plugins = join(run, "plugins");
  const pluginData = join(plugins, "Syncmoney");
  await access(candidateJar);
  await access(acceptanceJar);
  await assertStopped(ROOT);

  const plugdevRoot = env.PLUGDEV_ROOT ?? resolve(ROOT, "../plugdev-main");
  const require = createRequire(join(plugdevRoot, "package.json"));
  const YAML = require("yaml");
  const defaultConfig = YAML.parse(await readFile(join(ROOT, "src", "main", "resources", "config.yml"), "utf8"));
  const optionsWithVault = { ...options, vaultVersion: VAULT_UNLOCKED.version };
  const profile = createProfileConfig(optionsWithVault);
  const settings = createEconomySettings(defaultConfig, options, env);

  await mkdir(pluginData, { recursive: true });
  await writeFile(join(ROOT, ".plugdev", "profile.yml"), YAML.stringify(profile), "utf8");
  await writeFile(join(pluginData, "config.yml"), YAML.stringify(settings), "utf8");
  await copyFile(acceptanceJar, join(plugins, "SyncmoneyAcceptance.jar"));

  return {
    ...options,
    candidateJar,
    candidateSha256: await sha256(candidateJar),
    acceptanceJar,
    profilePath: join(ROOT, ".plugdev", "profile.yml"),
  };
}

async function main() {
  const result = await prepareProfile(process.argv.slice(2));
  console.log(`Prepared ${result.software} ${result.minecraftVersion}, mode=${result.mode}, features=${result.features}.`);
  console.log(`Candidate: Syncmoney-${result.candidateVersion}.jar sha256=${result.candidateSha256}`);
  console.log(`VaultUnlocked: ${VAULT_UNLOCKED.version}; profile: .plugdev/profile.yml`);
  console.log("Start with: plugdev server start --config .plugdev/profile.yml");
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error) => {
    console.error(error.message);
    process.exitCode = 1;
  });
}

import assert from "node:assert/strict";
import { copyFile, mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import test from "node:test";
import { assertStopped, createEconomySettings, createProfileConfig, validateOptions } from "./prepare.mjs";

async function getClosedPort() {
  const server = createServer();
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  assert.ok(address && typeof address !== "string");
  await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  return address.port;
}

async function canBindLoopbackPort(port) {
  const server = createServer();
  try {
    await new Promise((resolve, reject) => {
      server.once("error", reject);
      server.listen(port, "127.0.0.1", resolve);
    });
    return true;
  } catch {
    return false;
  } finally {
    if (server.listening) {
      await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
    }
  }
}

async function getClosedPortPair() {
  for (let port = 25_677; port <= 45_535; port += 1) {
    if (await canBindLoopbackPort(port) && await canBindLoopbackPort(port + 10_000)) return port;
  }
  throw new Error("Could not find closed loopback game and RCON ports for the acceptance fixture.");
}

test("the minimum Paper profile loads the exact candidate without rebuilding it", () => {
  const options = validateOptions(["paper", "1.21.6", "local", "off", "1.4.0-alpha.1"]);
  const profile = createProfileConfig(options);

  assert.equal(profile.server, "paper");
  assert.equal(profile.version, "1.21.6");
  assert.deepEqual(profile.jvm.args, ["-Dsyncmoney.acceptance.version=1.4.0-alpha.1"]);
  assert.equal(profile.build.task, "help");
  assert.equal(profile.build.jarTask, "help");
  assert.equal(profile.build.jarPattern, "build/libs/Syncmoney-1.4.0-alpha.1.jar");
  assert.deepEqual(profile.deps, [
    { name: "VaultUnlocked", source: "hangar", author: "TNE", slug: "VaultUnlocked", version: "2.20.2" },
  ]);
});

test("an isolated runtime may reserve a specific loopback game and derived RCON port", () => {
  const options = validateOptions(["paper", "1.21.6", "sync", "off", "1.4.0-alpha.1"]);
  const profile = createProfileConfig(options, { SYNCMONEY_TEST_GAME_PORT: "25677" });

  assert.equal(profile.bind, "127.0.0.1");
  assert.equal(profile.port, 25677);
  assert.equal(profile.port + 10_000, 35677);
  assert.throws(
    () => createProfileConfig(options, { SYNCMONEY_TEST_GAME_PORT: "55536" }),
    /Invalid acceptance game port/,
  );
});

test("prepareProfile writes the injected game port into the generated PlugDev profile", async () => {
  const fixture = await mkdtemp(join(tmpdir(), "syncmoney-prepare-profile-"));
  const root = join(fixture, "workspace");
  const plugdevRoot = join(fixture, "plugdev");
  const preparePath = join(root, "tools", "plugdev", "prepare.mjs");
  const port = await getClosedPortPair();
  try {
    await mkdir(join(root, "build", "libs"), { recursive: true });
    await mkdir(join(root, "build", "acceptance"), { recursive: true });
    await mkdir(join(root, "src", "main", "resources"), { recursive: true });
    await mkdir(join(root, "tools", "plugdev"), { recursive: true });
    await mkdir(join(plugdevRoot, "node_modules", "yaml"), { recursive: true });
    await copyFile(fileURLToPath(new URL("./prepare.mjs", import.meta.url)), preparePath);
    await writeFile(join(root, "build", "libs", "Syncmoney-1.4.0-alpha.1.jar"), "candidate", "utf8");
    await writeFile(join(root, "build", "acceptance", "SyncmoneyAcceptance.jar"), "acceptance", "utf8");
    await writeFile(join(root, "build", "acceptance", "SyncmoneyApiConsumer.jar"), "api-consumer", "utf8");
    await writeFile(join(root, "src", "main", "resources", "config.yml"), "fixture: true\n", "utf8");
    await writeFile(join(plugdevRoot, "package.json"), JSON.stringify({ private: true }), "utf8");
    await writeFile(join(plugdevRoot, "node_modules", "yaml", "index.js"),
      "module.exports = { parse: () => ({}), stringify: (value) => JSON.stringify(value) };\n", "utf8");

    const { prepareProfile } = await import(pathToFileURL(preparePath).href);
    const result = await prepareProfile(
      ["paper", "1.21.6", "sync", "off", "1.4.0-alpha.1"],
      { PLUGDEV_ROOT: plugdevRoot, SYNCMONEY_TEST_GAME_PORT: String(port) },
    );
    const profile = JSON.parse(await readFile(result.profilePath, "utf8"));

    assert.equal(profile.port, port);
  } finally {
    await rm(fixture, { recursive: true, force: true });
  }
});

test("an explicitly supplied server JAR is pinned through PlugDev's custom provider", () => {
  const options = validateOptions(["paper", "26.3", "local", "off", "1.4.0-alpha.1"]);
  const profile = createProfileConfig({ ...options, serverJar: "build/reports/foundation/paper-26.3-175.jar" });

  assert.equal(profile.server, "custom");
  assert.equal(profile.serverJar, resolve("build/reports/foundation/paper-26.3-175.jar"));
  assert.equal(profile.version, "26.3");
  assert.equal(profile.build.jarTask, "help");
});

test("1.4 runtime profiles reject Paper below the minimum", () => {
  assert.throws(
    () => validateOptions(["paper", "1.21.5", "local", "off", "1.4.0-alpha.1"]),
    /Paper 1\.21\.6 or newer is required/,
  );
});

test("an empty PlugDev session file is accepted only after the game port is closed", async () => {
  const root = await mkdtemp(join(tmpdir(), "syncmoney-plugdev-"));
  try {
    const state = join(root, ".plugdev");
    await mkdir(state);
    await writeFile(join(state, "session.json"), "\n", "utf8");
    await assertStopped(root, await getClosedPort(), null);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});

test("a live game server blocks preparation after PlugDev clears its session", async () => {
  const root = await mkdtemp(join(tmpdir(), "syncmoney-plugdev-"));
  const gameServer = createServer((socket) => socket.on("error", () => {}));
  try {
    const state = join(root, ".plugdev");
    await mkdir(state);
    await writeFile(join(state, "session.json"), "\n", "utf8");
    const run = join(state, "run");
    await mkdir(run);
    await new Promise((resolve, reject) => {
      gameServer.once("error", reject);
      gameServer.listen(0, "127.0.0.1", resolve);
    });
    const address = gameServer.address();
    assert.ok(address && typeof address !== "string");
    await writeFile(join(run, "server.properties"),
      `server-port=${address.port}\nenable-rcon=false\n`, "utf8");

    await assert.rejects(assertStopped(root), /still accepting connections/i);
  } finally {
    if (gameServer.listening) {
      await new Promise((resolve, reject) => gameServer.close((error) => error ? reject(error) : resolve()));
    }
    await rm(root, { recursive: true, force: true });
  }
});

test("core, Paper, and acceptance descriptors all require API 1.21.6", async () => {
  const descriptors = [
    new URL("../../src/main/resources/plugin.yml", import.meta.url),
    new URL("../../src/main/resources/paper-plugin.yml", import.meta.url),
    new URL("../../src/acceptance/resources/plugin.yml", import.meta.url),
  ];

  for (const path of descriptors) {
    const contents = await readFile(path, "utf8");
    assert.match(contents, /^api-version:\s*['"]?1\.21\.6['"]?\s*$/m, path.pathname);
  }
});

test("local mode produces an isolated config with the schema default and no network storage", () => {
  const defaults = { "config-version": 12, redis: { enabled: true }, database: { enabled: true } };
  const settings = createEconomySettings(defaults, {
    software: "paper",
    minecraftVersion: "1.21.6",
    mode: "local",
    features: "off",
  });

  assert.equal(settings["server-name"], "acceptance-paper");
  assert.equal(settings["config-version"], 12);
  assert.equal(settings["db-enabled"], false);
  assert.equal(settings["pubsub-enabled"], false);
  assert.equal(settings.redis.enabled, false);
  assert.equal(settings.database.enabled, false);
  assert.equal(settings.economy.mode, "local");
});

test("guard-only profile enables per-player protection independently on Canvas and Folia", () => {
  const defaults = {
    "config-version": 12,
    redis: { enabled: false },
    database: { enabled: false },
    "circuit-breaker": {
      enabled: true,
      "player-protection": { enabled: true, "global-lock": { enabled: true } },
    },
    "shadow-sync": { enabled: true },
    audit: { enabled: true, cleanup: { enabled: true }, export: { enabled: true }, redis: { enabled: true } },
    "transfer-guard": { enabled: true },
    "web-admin": { enabled: true, server: {}, security: {}, web: {} },
    "discord-webhook": { enabled: true },
  };

  for (const [software, version] of [["paper", "1.21.6"], ["canvas", "26.3"], ["folia", "26.2"]]) {
    const options = validateOptions([
      software, version, "local_redis", "off", "1.4.0-alpha.1", "guard-on",
    ]);
    const settings = createEconomySettings(defaults, options);

    assert.equal(options.playerProtection, true);
    assert.equal(settings["circuit-breaker"].enabled, false);
    assert.equal(settings["circuit-breaker"]["player-protection"].enabled, true);
    assert.equal(settings["circuit-breaker"]["player-protection"]["global-lock"].enabled, false);
    assert.equal(settings["shadow-sync"].enabled, false);
    assert.equal(settings.audit.enabled, false);
    assert.equal(settings["transfer-guard"].enabled, false);
    assert.equal(settings["web-admin"].enabled, false);
    assert.equal(settings["discord-webhook"].enabled, false);
  }
});

test("Canvas guard-breaker profile enables both guards while keeping optional modules disabled", () => {
  const options = validateOptions([
    "canvas", "26.3", "local_redis", "off", "1.4.0-alpha.1", "guard-breaker-on",
  ]);
  const defaults = {
    "config-version": 12,
    redis: { enabled: false },
    database: { enabled: false },
    "circuit-breaker": {
      enabled: false,
      "max-transactions-per-second": 5,
      "player-protection": { enabled: true, "global-lock": { enabled: true } },
    },
    "shadow-sync": { enabled: true },
    audit: { enabled: true, cleanup: { enabled: true }, export: { enabled: true }, redis: { enabled: true } },
    "transfer-guard": { enabled: true },
    "web-admin": { enabled: true, server: {}, security: {}, web: {} },
    "discord-webhook": { enabled: true },
  };

  const settings = createEconomySettings(defaults, options);

  assert.equal(options.playerProtection, true);
  assert.equal(options.circuitBreakerEnabled, true);
  assert.equal(settings["circuit-breaker"].enabled, true);
  assert.equal(settings["circuit-breaker"]["max-transactions-per-second"], 100);
  assert.equal(settings["circuit-breaker"]["player-protection"].enabled, true);
  assert.equal(settings["circuit-breaker"]["player-protection"]["global-lock"].enabled, false);
  assert.equal(settings["shadow-sync"].enabled, false);
  assert.equal(settings.audit.enabled, false);
  assert.equal(settings["transfer-guard"].enabled, false);
  assert.equal(settings["web-admin"].enabled, false);
  assert.equal(settings["discord-webhook"].enabled, false);
});

test("Canvas breaker-only profile enables the global TPS gate without player protection", () => {
  const options = validateOptions([
    "canvas", "26.3", "local_redis", "off", "1.4.0-alpha.1", "breaker-only",
  ]);
  const defaults = {
    "config-version": 12,
    redis: { enabled: false },
    database: { enabled: false },
    "circuit-breaker": {
      enabled: false,
      "max-transactions-per-second": 100,
      "player-protection": { enabled: true, "global-lock": { enabled: true } },
    },
    "shadow-sync": { enabled: true },
    audit: { enabled: true, cleanup: { enabled: true }, export: { enabled: true }, redis: { enabled: true } },
    "transfer-guard": { enabled: true },
    "web-admin": { enabled: true, server: {}, security: {}, web: {} },
    "discord-webhook": { enabled: true },
  };

  const settings = createEconomySettings(defaults, options);

  assert.equal(options.playerProtection, false);
  assert.equal(options.circuitBreakerEnabled, true);
  assert.equal(options.maxTransactionsPerSecond, 5);
  assert.equal(settings["circuit-breaker"].enabled, true);
  assert.equal(settings["circuit-breaker"]["max-transactions-per-second"], 5);
  assert.equal(settings["circuit-breaker"]["player-protection"].enabled, false);
  assert.equal(settings["circuit-breaker"]["player-protection"]["global-lock"].enabled, false);
  assert.equal(settings["shadow-sync"].enabled, false);
  assert.equal(settings.audit.enabled, false);
  assert.equal(settings["transfer-guard"].enabled, false);
  assert.equal(settings["web-admin"].enabled, false);
  assert.equal(settings["discord-webhook"].enabled, false);

  assert.throws(() => validateOptions([
    "paper", "26.3", "local_redis", "off", "1.4.0-alpha.1", "breaker-only",
  ]), /only supported for Canvas/);
});

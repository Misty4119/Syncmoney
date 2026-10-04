import assert from "node:assert/strict";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import { createServer } from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
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

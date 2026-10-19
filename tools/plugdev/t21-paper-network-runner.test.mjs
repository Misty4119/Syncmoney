import assert from "node:assert/strict";
import path from "node:path";
import test from "node:test";

import {
  buildPlugdevConfig,
  buildFailureCleanupRecord,
  collectCleanupEvidence,
  createRunRootDirectory,
  encodeRconPacket,
  redisConfigArgument,
  waitForRedisProcess,
} from "./t21-paper-network-runner.mjs";
import {
  T21_PAPER_NETWORK_ARTIFACT_PATHS,
  T21_PAPER_NETWORK_TOOLCHAIN_PINS,
} from "./t21-paper-network-auditor.mjs";

test("PlugDev network artifact paths stay pinned while plugin configs stay inside its workspace", () => {
  const runRoot = path.resolve("C:/t21-paper-network-run-test");
  const workspace = path.join(runRoot, "workspace");
  const ports = {
    proxy: 25565,
    backendA: 25566,
    backendB: 25567,
    postgres: 25432,
    redis: 26379,
    rconA: 28000,
    rconB: 28001,
    bridge: 28100,
  };
  const config = buildPlugdevConfig(runRoot, { endpoint: { version: "1.21.6" } }, ports, "test-only-rcon-password");

  const deployedArtifacts = T21_PAPER_NETWORK_ARTIFACT_PATHS.filter(artifact => !artifact.path.startsWith("inputs/"));
  assert.equal(deployedArtifacts.length, 9);
  assert(deployedArtifacts.every(artifact => artifact.path.startsWith("workspace/.plugdev/")),
    "audited deployment paths must match PlugDev's runRoot/workspace working directory");

  assert.equal(config.backends.length, 2);
  for (const backend of config.backends) {
    assert.equal(backend.jar, path.join(runRoot, "inputs", "paper.jar"));
    for (const plugin of backend.plugins) {
      assert.equal(path.isAbsolute(plugin.jar), true);
      assert.equal(path.dirname(plugin.jar), path.join(runRoot, "inputs"));
      assert.equal(plugin.jar.startsWith(path.join(runRoot, "inputs") + path.sep), true);
      if (plugin.config) {
        assert.equal(path.isAbsolute(plugin.config), false);
        assert.equal(plugin.config.startsWith("../"), false);
        assert.equal(path.resolve(workspace, plugin.config), path.join(workspace, "inputs", path.basename(plugin.config)));
      }
    }
  }
});

test("cleanup evidence awaits port probes and accepts a fully closed run", async () => {
  const ports = { proxy: 25001, postgres: 25002, redis: 25003 };
  const probed = [];
  const evidence = await collectCleanupEvidence(ports, [], async port => {
    probed.push(port);
    return false;
  });

  assert.deepEqual(probed, Object.values(ports));
  assert.deepEqual(evidence, {
    forcedCleanup: false,
    evidenceComplete: true,
    runOwnedProcessesRemaining: 0,
    runOwnedPortsOpen: 0,
    openPorts: [],
    errors: [],
  });
});

test("cleanup evidence fails closed when child-process or port liveness evidence is unavailable", async () => {
  const missingChildren = await collectCleanupEvidence({ bridge: 25003 }, null, async () => false);
  assert.equal(missingChildren.evidenceComplete, false);
  assert.equal(missingChildren.forcedCleanup, true);

  const malformedSpawn = await collectCleanupEvidence({ bridge: 25003 }, [
    { event: "spawn", pid: 301 },
  ], async () => false);
  assert.equal(malformedSpawn.evidenceComplete, false);
  assert.equal(malformedSpawn.forcedCleanup, true);

  const failedPortProbe = await collectCleanupEvidence({ bridge: 25003 }, [], async () => {
    throw new Error("probe unavailable");
  });
  assert.equal(failedPortProbe.evidenceComplete, false);
  assert.equal(failedPortProbe.forcedCleanup, true);
  assert.match(failedPortProbe.errors.join(" "), /probe unavailable/i);
});

test("failed-run cleanup status cannot claim clean teardown without complete liveness evidence", () => {
  const state = { postgresStopped: false, redisStopped: false, networkStopped: false, clientStopped: false };
  const missingEvidence = buildFailureCleanupRecord(null, [], state);
  assert.equal(missingEvidence.evidenceComplete, false);
  assert.equal(missingEvidence.forcedCleanup, true);

  const completeEvidence = buildFailureCleanupRecord({
    evidenceComplete: true,
    forcedCleanup: false,
    runOwnedProcessesRemaining: 0,
    runOwnedPortsOpen: 0,
    openPorts: [],
    errors: [],
  }, [], state);
  assert.equal(completeEvidence.evidenceComplete, true);
  assert.equal(completeEvidence.forcedCleanup, false);
});

test("cleanup evidence rejects an open run-owned port or a live Java child", async () => {
  const ports = { proxy: 25001, backend: 25002 };
  const openPortEvidence = await collectCleanupEvidence(ports, [], async port => port === ports.backend);
  assert.equal(openPortEvidence.forcedCleanup, true);
  assert.deepEqual(openPortEvidence.openPorts, [ports.backend]);

  const javaExecutable = "C:/Program Files/Java/jdk-25.0.4/bin/java.exe";
  const liveJavaEvidence = await collectCleanupEvidence({ bridge: 25003 }, [
    { event: "spawn", pid: 301, executable: javaExecutable, args: ["-jar", "C:/run/server.jar"] },
  ], async () => false);
  assert.equal(liveJavaEvidence.forcedCleanup, true);
  assert.equal(liveJavaEvidence.runOwnedProcessesRemaining, 1);
});

test("canonical report-root validation rejects redirected volumes before mkdir", async () => {
  const mkdirCalls = [];
  await assert.rejects(
    createRunRootDirectory("C:\\Users\\margo\\AppData\\Local\\Temp\\t21-paper-network-run", {
      realpath: async () => "E:\\external\\redirected-root",
      mkdir: async (...args) => { mkdirCalls.push(args); },
    }),
    /must be on C:/i,
  );
  assert.equal(mkdirCalls.length, 0, "no directory may be created before canonical path validation");
});

test("cleanup evidence ignores exited Java children and non-Java helpers", async () => {
  const javaExecutable = "C:/Program Files/Java/jdk-25.0.4/bin/java.exe";
  const redisExecutable = "C:/tools/redis-server.exe";
  const childEvents = [
    { event: "spawn", pid: 301, executable: javaExecutable, args: ["-jar", "C:/run/server.jar"] },
    { event: "exit", pid: 301, executable: javaExecutable, args: ["-jar", "C:/run/server.jar"] },
    { event: "spawn", pid: 302, executable: redisExecutable, args: ["redis-server.exe", "redis.conf"] },
  ];
  const evidence = await collectCleanupEvidence({ bridge: 25003 }, childEvents, async () => false);

  assert.equal(evidence.forcedCleanup, false);
  assert.equal(evidence.runOwnedProcessesRemaining, 0);
});

test("Redis receives a relative run-owned config path and rejects paths outside its working directory", () => {
  const workingDirectory = path.resolve("C:/t21-paper-network-run/services/redis");
  assert.equal(redisConfigArgument(path.join(workingDirectory, "redis.conf"), workingDirectory), "redis.conf");
  assert.throws(
    () => redisConfigArgument(path.resolve("C:/t21-paper-network-run/services/redis.conf"), workingDirectory),
    /inside its run-owned working directory/,
  );
});

test("Redis shutdown waits for the nested process handle's exit promise", async () => {
  const exit = { code: 0, signal: null };
  const result = await waitForRedisProcess({ child: { exited: Promise.resolve(exit) } }, 100);
  assert.deepEqual(result, exit);
});

test("RCON packets include request type and both terminators in their declared payload length", () => {
  const payload = Buffer.from("say T21", "utf8");
  const packet = encodeRconPacket(73, 2, payload.toString("utf8"));
  assert.equal(packet.readInt32LE(0), packet.length - 4);
  assert.equal(packet.length, payload.length + 14);
  assert.equal(packet.readInt32LE(4), 73);
  assert.equal(packet.readInt32LE(8), 2);
  assert.deepEqual(packet.subarray(12, 12 + payload.length), payload);
  assert.equal(packet.readUInt16LE(packet.length - 2), 0);
});

test("Velocity metadata pin uses the exact Fill API endpoint queried by preflight", () => {
  assert.equal(T21_PAPER_NETWORK_TOOLCHAIN_PINS.velocity.url,
    "https://fill.papermc.io/v3/projects/velocity/versions/4.3.0/builds");
});

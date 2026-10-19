import assert from "node:assert/strict";
import { appendFile, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
  buildPlugdevConfig,
  buildFailureCleanupRecord,
  collectCleanupEvidence,
  createRunRootDirectory,
  encodeRconPacket,
  redisConfigArgument,
  runCapture,
  waitForRedisProcess,
} from "./t21-paper-network-runner.mjs";
import {
  T21_PAPER_NETWORK_ARTIFACT_PATHS,
  T21_PAPER_NETWORK_TOOLCHAIN_PINS,
} from "./t21-paper-network-auditor.mjs";
import * as paperNetworkRunner from "./t21-paper-network-runner.mjs";

test("waits for an account-specific SQL retry marker that arrives after the outage starts", async () => {
  const temporaryBase = os.tmpdir();
  assert.equal(path.parse(temporaryBase).root.toLowerCase(), "c:\\",
    "the retry fixture must stay on the C: SSD");
  const runRoot = await mkdtemp(path.join(temporaryBase, "syncmoney-t21-paper-retry-window-"));
  const backendALog = path.join(runRoot, "workspace", ".plugdev", "network", "backends", "backend-a", "logs", "latest.log");
  const backendBLog = path.join(runRoot, "workspace", ".plugdev", "network", "backends", "backend-b", "logs", "latest.log");
  const accountUuid = "a55dd31d-e2a0-45f8-8f1e-8e98146e142e";
  let elapsedMs = 0;
  let pollCount = 0;
  try {
    await mkdir(path.dirname(backendALog), { recursive: true });
    await mkdir(path.dirname(backendBLog), { recursive: true });
    await writeFile(backendALog, "", "utf8");
    await writeFile(backendBLog, "", "utf8");
    const waitForRetry = paperNetworkRunner.waitForSqlRetryEvidence;
    assert.equal(typeof waitForRetry, "function",
      "the outage runner must poll for the SQL retry instead of sampling the log once");

    const result = await waitForRetry(runRoot, { backendA: 0, backendB: 0 }, {
      uuidA: accountUuid,
      uuidB: "b66ee42e-f3b1-46a9-901f-9fa217f5253f",
    }, {
      timeoutMs: 100,
      intervalMs: 10,
      now: () => elapsedMs,
      sleep: async milliseconds => {
        elapsedMs += milliseconds;
        pollCount += 1;
        if (pollCount === 1) {
          await appendFile(backendALog,
            `[Async JDBCTaskExecutor] Re-queued failed DB write task (attempt 1) for player ${accountUuid}\n`,
            "utf8");
        }
      },
    });

    assert.equal(pollCount, 1, "the runner must re-read logs after its initial empty sample");
    assert.equal(result.retryEvidence.length, 1);
    assert.equal(result.retryEvidence[0].backend, "backend-a");
    assert.equal(result.retryEvidence[0].uuid, accountUuid);
    assert.equal(result.endOffsets.backendA, Buffer.byteLength(
      `[Async JDBCTaskExecutor] Re-queued failed DB write task (attempt 1) for player ${accountUuid}\n`));
  } finally {
    await rm(runRoot, { recursive: true, force: true });
  }
});

test("keeps the PostgreSQL outage open for delayed transfer-side-effect retries", async () => {
  const temporaryBase = os.tmpdir();
  assert.equal(path.parse(temporaryBase).root.toLowerCase(), "c:\\",
    "the retry fixture must stay on the C: SSD");
  const runRoot = await mkdtemp(path.join(temporaryBase, "syncmoney-t21-paper-delayed-retry-"));
  const backendALog = path.join(runRoot, "workspace", ".plugdev", "network", "backends", "backend-a", "logs", "latest.log");
  const backendBLog = path.join(runRoot, "workspace", ".plugdev", "network", "backends", "backend-b", "logs", "latest.log");
  const accountUuid = "c77e05d7-a5d4-4ef1-a610-191f2319e4ef";
  let elapsedMs = 0;
  let markerWritten = false;
  try {
    await mkdir(path.dirname(backendALog), { recursive: true });
    await mkdir(path.dirname(backendBLog), { recursive: true });
    await writeFile(backendALog, "", "utf8");
    await writeFile(backendBLog, "", "utf8");

    const result = await paperNetworkRunner.waitForSqlRetryEvidence(
      runRoot,
      { backendA: 0, backendB: 0 },
      { uuidA: accountUuid, uuidB: "b66ee42e-f3b1-46a9-901f-9fa217f5253f" },
      {
        now: () => elapsedMs,
        sleep: async milliseconds => {
          elapsedMs += milliseconds;
          if (!markerWritten && elapsedMs >= 40_000) {
            markerWritten = true;
            await appendFile(backendALog,
              `[Async JDBCTaskExecutor] Re-queued failed DB write task (attempt 1) for player ${accountUuid}\n`,
              "utf8");
          }
        },
      });

    assert.equal(markerWritten, true);
    assert.ok(elapsedMs >= 40_000, "the simulated SQL retry must arrive after 40 seconds");
    assert.ok(elapsedMs < 60_000, "the observation window must remain bounded at one minute");
    assert.equal(result.retryEvidence.length, 1);
    assert.equal(result.retryEvidence[0].uuid, accountUuid);
  } finally {
    await rm(runRoot, { recursive: true, force: true });
  }
});

test("bounds the default SQL retry observation window to 60 seconds", async () => {
  const temporaryBase = os.tmpdir();
  assert.equal(path.parse(temporaryBase).root.toLowerCase(), "c:\\",
    "the retry fixture must stay on the C: SSD");
  const runRoot = await mkdtemp(path.join(temporaryBase, "syncmoney-t21-paper-retry-timeout-"));
  const backendALog = path.join(runRoot, "workspace", ".plugdev", "network", "backends", "backend-a", "logs", "latest.log");
  const backendBLog = path.join(runRoot, "workspace", ".plugdev", "network", "backends", "backend-b", "logs", "latest.log");
  let elapsedMs = 0;
  let sleepCount = 0;
  try {
    await mkdir(path.dirname(backendALog), { recursive: true });
    await mkdir(path.dirname(backendBLog), { recursive: true });
    await writeFile(backendALog, "", "utf8");
    await writeFile(backendBLog, "", "utf8");

    await assert.rejects(
      paperNetworkRunner.waitForSqlRetryEvidence(
        runRoot,
        { backendA: 0, backendB: 0 },
        { uuidA: "a55dd31d-e2a0-45f8-8f1e-8e98146e142e", uuidB: "b66ee42e-f3b1-46a9-901f-9fa217f5253f" },
        {
          now: () => elapsedMs,
          sleep: async milliseconds => {
            elapsedMs += milliseconds;
            sleepCount += 1;
          },
        }),
      /No account-specific SQL retry was observed before PostgreSQL recovery\./);

    assert.equal(elapsedMs, 60_000);
    assert.equal(sleepCount, 240, "the default deadline must cap 250 ms polling at one minute");
  } finally {
    await rm(runRoot, { recursive: true, force: true });
  }
});

test("rejects SQL retry evidence first observed after the default deadline", async () => {
  const temporaryBase = os.tmpdir();
  assert.equal(path.parse(temporaryBase).root.toLowerCase(), "c:\\",
    "the retry fixture must stay on the C: SSD");
  const runRoot = await mkdtemp(path.join(temporaryBase, "syncmoney-t21-paper-retry-late-marker-"));
  const backendALog = path.join(runRoot, "workspace", ".plugdev", "network", "backends", "backend-a", "logs", "latest.log");
  const backendBLog = path.join(runRoot, "workspace", ".plugdev", "network", "backends", "backend-b", "logs", "latest.log");
  const accountUuid = "d88ff6e1-71a7-4a90-bce1-d6f5ddad5843";
  let elapsedMs = 0;
  let markerWritten = false;
  try {
    await mkdir(path.dirname(backendALog), { recursive: true });
    await mkdir(path.dirname(backendBLog), { recursive: true });
    await writeFile(backendALog, "", "utf8");
    await writeFile(backendBLog, "", "utf8");

    await assert.rejects(
      paperNetworkRunner.waitForSqlRetryEvidence(
        runRoot,
        { backendA: 0, backendB: 0 },
        { uuidA: accountUuid, uuidB: "b66ee42e-f3b1-46a9-901f-9fa217f5253f" },
        {
          now: () => elapsedMs,
          sleep: async milliseconds => {
            elapsedMs += milliseconds;
            if (!markerWritten && elapsedMs >= 60_000) {
              elapsedMs = 60_001;
              markerWritten = true;
              await appendFile(backendALog,
                `[Async JDBCTaskExecutor] Re-queued failed DB write task (attempt 1) for player ${accountUuid}\n`,
                "utf8");
            }
          },
        }),
      /No account-specific SQL retry was observed before PostgreSQL recovery\./);

    assert.equal(markerWritten, true);
    assert.equal(elapsedMs, 60_001);
  } finally {
    await rm(runRoot, { recursive: true, force: true });
  }
});

test("rejects retry evidence when the log sample completes after the deadline", async () => {
  const temporaryBase = os.tmpdir();
  assert.equal(path.parse(temporaryBase).root.toLowerCase(), "c:\\",
    "the retry fixture must stay on the C: SSD");
  const runRoot = await mkdtemp(path.join(temporaryBase, "syncmoney-t21-paper-retry-slow-sample-"));
  const backendALog = path.join(runRoot, "workspace", ".plugdev", "network", "backends", "backend-a", "logs", "latest.log");
  const backendBLog = path.join(runRoot, "workspace", ".plugdev", "network", "backends", "backend-b", "logs", "latest.log");
  const accountUuid = "e99ff6e1-71a7-4a90-bce1-d6f5ddad5843";
  let clockReadCount = 0;
  try {
    await mkdir(path.dirname(backendALog), { recursive: true });
    await mkdir(path.dirname(backendBLog), { recursive: true });
    await writeFile(backendALog,
      `[Async JDBCTaskExecutor] Re-queued failed DB write task (attempt 1) for player ${accountUuid}\n`,
      "utf8");
    await writeFile(backendBLog, "", "utf8");

    await assert.rejects(
      paperNetworkRunner.waitForSqlRetryEvidence(
        runRoot,
        { backendA: 0, backendB: 0 },
        { uuidA: accountUuid, uuidB: "b66ee42e-f3b1-46a9-901f-9fa217f5253f" },
        { now: () => [0, 0, 60_001][Math.min(clockReadCount++, 2)] }),
      /No account-specific SQL retry was observed before PostgreSQL recovery\./);

    assert.equal(clockReadCount, 3, "the helper must validate time again after sampling the logs");
  } finally {
    await rm(runRoot, { recursive: true, force: true });
  }
});

test("runCapture avoids inherited stdio handles for service-start commands", async () => {
  const temporaryBase = os.tmpdir();
  assert.equal(path.parse(temporaryBase).root.toLowerCase(), "c:\\",
    "the subprocess fixture must stay on the C: SSD");
  const temporaryRoot = await mkdtemp(path.join(temporaryBase, "syncmoney-t21-paper-capture-"));
  const childPidFile = path.join(temporaryRoot, "child.pid");
  const outputFile = path.join(temporaryRoot, "control.log");
  const launcherPath = path.join(temporaryRoot, "launch-child.mjs");
  const childProgram = "require('node:fs').writeFileSync(process.env.T21_CAPTURE_CHILD_PID_FILE,String(process.pid));setTimeout(()=>{},4000)";
  const launcherSource = [
    "import { spawn } from 'node:child_process';",
    "process.stdout.write('service-start stdout marker');",
    "process.stderr.write('service-start stderr marker');",
    `const child = spawn(process.execPath, ["-e", ${JSON.stringify(childProgram)}], { detached: true, stdio: "inherit", windowsHide: true });`,
    "child.unref();",
    "",
  ].join("\n");
  const startedAt = Date.now();
  let result;
  let childPid = null;
  try {
    await writeFile(launcherPath, launcherSource, "utf8");
    result = await runCapture(process.execPath, [launcherPath], {
      captureOutput: false,
      outputFile,
      env: { ...process.env, T21_CAPTURE_CHILD_PID_FILE: childPidFile },
      timeoutMs: 1_000,
    });
  } finally {
    let childPidContent = null;
    const pidDeadline = Date.now() + 1_000;
    while (!childPidContent && Date.now() < pidDeadline) {
      try { childPidContent = await readFile(childPidFile, "utf8"); }
      catch (error) { if (error?.code !== "ENOENT") throw error; }
      if (!childPidContent) await new Promise(resolve => setTimeout(resolve, 25));
    }
    childPid = Number(childPidContent);
    if (Number.isSafeInteger(childPid) && childPid > 0) {
      try { process.kill(childPid); }
      catch (error) { if (error?.code !== "ESRCH") throw error; }
    }
    await rm(temporaryRoot, { recursive: true, force: true });
  }

  assert.ok(Number.isSafeInteger(childPid) && childPid > 0,
    "the service-start fixture must launch its long-lived child");
  assert.equal(result.timedOut, false,
    "the command wrapper must finish before the long-lived service child exits");
  assert.equal(result.code, 0);
  assert.equal(result.signal, null);
  assert.equal(result.stderr, "");
  assert.ok(result.stdout.includes("service-start stdout marker"));
  assert.ok(result.stdout.includes("service-start stderr marker"));
  assert.ok(Date.now() - startedAt < 2_000,
    "a short-lived command must finish without waiting for its long-lived service child");
});

test("runCapture captures command output by default", async () => {
  const result = await runCapture(process.execPath, ["-e", "process.stdout.write('stdout marker');process.stderr.write('stderr marker')"], {
    timeoutMs: 5_000,
  });
  assert.equal(result.error, null);
  assert.equal(result.timedOut, false);
  assert.equal(result.code, 0);
  assert.equal(result.signal, null);
  assert.equal(result.stdout, "stdout marker");
  assert.equal(result.stderr, "stderr marker");
});

test("runCapture redirects service output to a run-owned file when capture is disabled", async () => {
  const temporaryBase = os.tmpdir();
  assert.equal(path.parse(temporaryBase).root.toLowerCase(), "c:\\",
    "the subprocess fixture must stay on the C: SSD");
  const temporaryRoot = await mkdtemp(path.join(temporaryBase, "syncmoney-t21-paper-control-output-"));
  const outputFile = path.join(temporaryRoot, "control.log");
  try {
    const result = await runCapture(process.execPath, ["-e", "process.stdout.write('pg_ctl stdout');process.stderr.write('pg_ctl stderr')"], {
      captureOutput: false,
      outputFile,
      timeoutMs: 5_000,
    });
    assert.equal(result.error, null);
    assert.equal(result.timedOut, false);
    assert.equal(result.code, 0);
    assert.equal(result.signal, null);
    assert.equal(result.stderr, "");
    assert.ok(result.stdout.includes("pg_ctl stdout"));
    assert.ok(result.stdout.includes("pg_ctl stderr"));
    assert.equal(result.stdout, await readFile(outputFile, "utf8"));
  } finally {
    await rm(temporaryRoot, { recursive: true, force: true });
  }
});

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

test("cleanup evidence rejects malformed or mismatched Java child exit records", async () => {
  const javaExecutable = "C:/Program Files/Java/jdk-25.0.4/bin/java.exe";
  const validSpawn = { event: "spawn", pid: 401, executable: javaExecutable, args: ["-jar", "C:/run/server.jar"] };
  const missingExitIdentity = await collectCleanupEvidence({ bridge: 25003 }, [
    validSpawn,
    { event: "exit", pid: 401 },
  ], async () => false);
  assert.equal(missingExitIdentity.evidenceComplete, false);
  assert.equal(missingExitIdentity.forcedCleanup, true);
  assert.equal(missingExitIdentity.runOwnedProcessesRemaining, 1);

  const missingExitStatus = await collectCleanupEvidence({ bridge: 25003 }, [
    validSpawn,
    { event: "exit", pid: 401, executable: javaExecutable, args: ["-jar", "C:/run/server.jar"], code: null, signal: null },
  ], async () => false);
  assert.equal(missingExitStatus.evidenceComplete, false);
  assert.equal(missingExitStatus.forcedCleanup, true);
  assert.equal(missingExitStatus.runOwnedProcessesRemaining, 1);

  const mismatchedExitArguments = await collectCleanupEvidence({ bridge: 25003 }, [
    validSpawn,
    { event: "exit", pid: 401, executable: javaExecutable, args: ["-jar", "C:/run/other.jar"], code: 0, signal: null },
  ], async () => false);
  assert.equal(mismatchedExitArguments.evidenceComplete, false);
  assert.equal(mismatchedExitArguments.forcedCleanup, true);

  const javaWithoutInspectableArguments = await collectCleanupEvidence({ bridge: 25003 }, [
    { event: "spawn", pid: 402, executable: javaExecutable, args: [] },
    { event: "exit", pid: 402, executable: javaExecutable, args: [], code: 0, signal: null },
  ], async () => false);
  assert.equal(javaWithoutInspectableArguments.evidenceComplete, false);
  assert.equal(javaWithoutInspectableArguments.forcedCleanup, true);
  assert.equal(javaWithoutInspectableArguments.runOwnedProcessesRemaining, 1);
});

test("cleanup evidence accepts an identity-matched Java version probe", async () => {
  const executable = "C:/Program Files/Java/jdk-25.0.4/bin/java.exe";
  const args = [executable, "-version"];
  const evidence = await collectCleanupEvidence({ bridge: 25003 }, [
    { event: "spawn", pid: 403, executable, args },
    { event: "exit", pid: 403, executable, args, code: 0, signal: null },
  ], async () => false);

  assert.deepEqual(evidence, {
    forcedCleanup: false,
    evidenceComplete: true,
    runOwnedProcessesRemaining: 0,
    runOwnedPortsOpen: 0,
    openPorts: [],
    errors: [],
  });
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
    { event: "exit", pid: 301, executable: javaExecutable, args: ["-jar", "C:/run/server.jar"], code: 0, signal: null },
    { event: "spawn", pid: 302, executable: redisExecutable, args: ["redis-server.exe", "redis.conf"] },
    { event: "exit", pid: 302, executable: redisExecutable, args: ["redis-server.exe", "redis.conf"], code: 0, signal: null },
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

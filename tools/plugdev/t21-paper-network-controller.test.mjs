import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { mkdtemp, mkdir, readFile, rm, writeFile } from "node:fs/promises";
import net from "node:net";
import { join, parse, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import test from "node:test";
import { createNetworkCliProcess } from "./t21-paper-network-controller.mjs";

const bridgePath = fileURLToPath(new URL("./t21-network-shutdown-bridge.cjs", import.meta.url));
const workspaceRoot = fileURLToPath(new URL("../../", import.meta.url));

function freePort() {
  return new Promise((resolvePort, reject) => {
    const server = net.createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const { port } = server.address();
      server.close(error => error ? reject(error) : resolvePort(port));
    });
  });
}

test("controller requests the CLI's graceful shutdown hook over IPC", async () => {
  const tempBase = resolve(workspaceRoot, "build", "reports", "foundation", "T21-paper-network", "test-temp");
  if (process.platform === "win32" && parse(tempBase).root.toLowerCase() !== "c:\\") {
    throw new Error("T21 controller test temp root must be on C:, observed " + tempBase);
  }
  await mkdir(tempBase, { recursive: true });
  const root = await mkdtemp(join(tempBase, "controller-"));
  const markerPath = join(root, "shutdown-marker.txt");
  const cliPath = join(root, "dummy-cli.mjs");
  const bridgePort = await freePort();
  const bridgeToken = randomBytes(32).toString("hex");
  await writeFile(cliPath, [
    "import fs from 'node:fs';",
    "process.on('SIGINT', () => { fs.writeFileSync(process.env.T21_SHUTDOWN_MARKER, 'handled'); process.exit(0); });",
    "setTimeout(() => process.exit(2), 5000);",
    "setInterval(() => {}, 1000);",
    "",
  ].join("\n"));

  try {
    const child = createNetworkCliProcess({
      cliPath,
      cliArgs: [],
      workingDirectory: root,
      signalBridgePath: bridgePath,
      env: {
        T21_SHUTDOWN_MARKER: markerPath,
        T21_SHUTDOWN_BRIDGE_PORT: String(bridgePort),
        T21_SHUTDOWN_BRIDGE_TOKEN: bridgeToken,
        T21_RUN_ROOT: root,
        T21_CHILD_PROCESS_EVENTS: join(root, "child-process-events.jsonl"),
      },
    });

    await child.requestGracefulShutdown();
    const result = await child.exited;
    assert.equal(result.exitCode, 0, result.error ?? "bridge or CLI failed before graceful shutdown");
    assert.equal(result.signal, null);
    assert.equal(await readFile(markerPath, "utf8"), "handled");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
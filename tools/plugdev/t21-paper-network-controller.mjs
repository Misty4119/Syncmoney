import { fork } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { pathToFileURL } from "node:url";

const READY_TEXT = "Network ready";
const ACTION_ID = /^[a-z0-9][a-z0-9-]{0,47}$/;

export function createNetworkCliProcess({
  cliPath,
  cliArgs,
  workingDirectory,
  signalBridgePath,
  env = {},
  onOutput = () => {},
}) {
  if (!path.isAbsolute(cliPath) || !path.isAbsolute(workingDirectory) || !path.isAbsolute(signalBridgePath)) {
    throw new Error("PlugDev CLI, working directory, and signal bridge paths must be absolute.");
  }
  if (!Array.isArray(cliArgs) || cliArgs.some(argument => typeof argument !== "string")) {
    throw new Error("PlugDev CLI arguments must be an array of strings.");
  }

  const child = fork(cliPath, cliArgs, {
    cwd: workingDirectory,
    execArgv: ["--require", signalBridgePath],
    env: { ...process.env, ...env },
    stdio: ["ignore", "pipe", "pipe", "ipc"],
    windowsHide: true,
  });
  let stopRequested = false;
  let settled = false;
  let resolveExit;
  const exited = new Promise(resolve => { resolveExit = resolve; });

  child.stdout?.on("data", chunk => onOutput("stdout", chunk));
  child.stderr?.on("data", chunk => onOutput("stderr", chunk));
  child.once("error", error => {
    onOutput("controller_error", Buffer.from(String(error?.stack ?? error)));
    if (!settled) {
      settled = true;
      resolveExit({ exitCode: null, signal: null, error: String(error?.message ?? error) });
    }
  });
  child.once("exit", (exitCode, signal) => {
    if (settled) return;
    settled = true;
    resolveExit({ exitCode, signal, error: null });
  });

  return {
    pid: child.pid,
    exited,
    get stopRequested() { return stopRequested; },
    async requestGracefulShutdown() {
      if (settled || child.exitCode !== null) return false;
      if (!child.connected) throw new Error("PlugDev CLI IPC channel is not connected.");
      if (stopRequested) return true;
      await new Promise((resolve, reject) => {
        child.send({ type: "T21_GRACEFUL_SHUTDOWN" }, error => error ? reject(error) : resolve());
      });
      stopRequested = true;
      return true;
    },
  };
}

function appendStatus(statusPath, event) {
  fs.appendFileSync(statusPath, `${JSON.stringify({ at: new Date().toISOString(), ...event })}\n`, "utf8");
}

async function runController(configPath) {
  if (!path.isAbsolute(configPath)) throw new Error("Controller config path must be absolute.");
  const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
  const controlToken = process.env.T21_NETWORK_CONTROL_TOKEN;
  if (!/^[a-f0-9]{64}$/.test(controlToken ?? "")) throw new Error("Network controller token is missing or invalid.");
  for (const key of ["cliPath", "workingDirectory", "signalBridgePath", "statusPath", "controlPath", "networkLogPath"]) {
    if (typeof config[key] !== "string" || !path.isAbsolute(config[key])) {
      throw new Error(`Controller config field ${key} must be an absolute path.`);
    }
  }

  let readyBuffer = "";
  let networkReady = false;
  const networkLog = fs.createWriteStream(config.networkLogPath, { flags: "wx" });
  const cli = createNetworkCliProcess({
    cliPath: config.cliPath,
    cliArgs: config.cliArgs,
    workingDirectory: config.workingDirectory,
    signalBridgePath: config.signalBridgePath,
    env: config.environment ?? {},
    onOutput(_stream, chunk) {
      networkLog.write(chunk);
      readyBuffer += chunk.toString("utf8");
      if (!networkReady && readyBuffer.includes(READY_TEXT)) {
        networkReady = true;
        appendStatus(config.statusPath, { state: "network_ready", cliPid: cli.pid });
      }
      if (readyBuffer.length > 4096) readyBuffer = readyBuffer.slice(-2048);
    },
  });
  appendStatus(config.statusPath, { state: "network_starting", cliPid: cli.pid });

  let offset = 0;
  let partial = "";
  const processedIds = new Set();
  let stopRequested = false;
  const controlTimer = setInterval(() => {
    try {
      const content = fs.readFileSync(config.controlPath, "utf8");
      if (content.length < offset) throw new Error("Controller control file was truncated.");
      const appended = partial + content.slice(offset);
      offset = content.length;
      const lines = appended.split(/\r?\n/);
      partial = lines.pop() ?? "";
      for (const line of lines) {
        if (!line.trim()) continue;
        const action = JSON.parse(line);
        if (!action || action.type !== "stop" || !ACTION_ID.test(action.id ?? "")) {
          throw new Error("Unsupported or malformed controller action.");
        }
        if (processedIds.has(action.id)) throw new Error("Duplicate controller action id.");
        processedIds.add(action.id);
        if (action.token !== controlToken) throw new Error("Controller action token did not match.");
        if (stopRequested) throw new Error("A network stop was already requested.");
        stopRequested = true;
        appendStatus(config.statusPath, { state: "network_stop_requested", actionId: action.id, cliPid: cli.pid });
        void cli.requestGracefulShutdown().catch(error => {
          appendStatus(config.statusPath, { state: "controller_error", message: String(error?.message ?? error) });
        });
      }
    } catch (error) {
      if (error?.code !== "ENOENT") {
        appendStatus(config.statusPath, { state: "controller_error", message: String(error?.message ?? error) });
        clearInterval(controlTimer);
      }
    }
  }, 100);

  const result = await cli.exited;
  clearInterval(controlTimer);
  networkLog.end();
  appendStatus(config.statusPath, {
    state: "network_exited",
    cliPid: cli.pid,
    exitCode: result.exitCode,
    signal: result.signal,
    error: result.error,
    networkReady,
    stopRequested,
  });
  if (result.error || result.exitCode !== 0 || result.signal !== null) process.exitCode = 1;
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  runController(process.argv[2]).catch(error => {
    process.stderr.write(`${String(error?.stack ?? error)}\n`);
    process.exitCode = 1;
  });
}

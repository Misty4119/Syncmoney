'use strict';

const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');

const port = Number(process.env.T21_SHUTDOWN_BRIDGE_PORT);
const token = process.env.T21_SHUTDOWN_BRIDGE_TOKEN;
const runRoot = process.env.T21_RUN_ROOT;
const eventLog = process.env.T21_CHILD_PROCESS_EVENTS;

if (!Number.isInteger(port) || port < 1 || port > 65535) {
  throw new Error('T21 shutdown bridge port is invalid.');
}
if (typeof token !== 'string' || token.length < 32) {
  throw new Error('T21 shutdown bridge token is missing or too short.');
}
if (typeof runRoot !== 'string' || !path.isAbsolute(runRoot)) {
  throw new Error('T21 run root must be absolute.');
}
if (typeof eventLog !== 'string' || !path.isAbsolute(eventLog)) {
  throw new Error('T21 child-process event log must be absolute.');
}

function recordChildEvent(event, child, details = {}) {
  const args = Array.isArray(child.spawnargs) ? child.spawnargs.map(String) : [];
  const record = {
    event,
    pid: Number.isInteger(child.pid) ? child.pid : null,
    executable: String(child.spawnfile ?? ''),
    args,
    at: new Date().toISOString(),
    ...details,
  };
  fs.appendFileSync(eventLog, `${JSON.stringify(record)}\n`, { encoding: 'utf8', flag: 'a' });
}

const childProcess = require('node:child_process');
const originalEmit = childProcess.ChildProcess.prototype.emit;
childProcess.ChildProcess.prototype.emit = function emitWithT21Evidence(event, ...args) {
  if (event === 'spawn') recordChildEvent('spawn', this);
  if (event === 'exit') {
    recordChildEvent('exit', this, { code: args[0] ?? null, signal: args[1] ?? null });
  }
  return originalEmit.call(this, event, ...args);
};

const originalKill = childProcess.ChildProcess.prototype.kill;
childProcess.ChildProcess.prototype.kill = function recordT21ForcedKill(signal) {
  recordChildEvent('kill', this, { requestedSignal: signal ?? null });
  return originalKill.call(this, signal);
};

const tokenBytes = Buffer.from(token, 'utf8');
let gracefulShutdownRequested = false;
function requestGracefulShutdown() {
  if (gracefulShutdownRequested) return false;
  gracefulShutdownRequested = true;
  setImmediate(() => process.emit('SIGINT'));
  return true;
}

process.on('message', (message) => {
  if (message && message.type === 'T21_GRACEFUL_SHUTDOWN') requestGracefulShutdown();
});

const server = http.createServer((request, response) => {
  if (request.method !== 'POST' || request.url !== '/shutdown') {
    response.writeHead(404).end();
    return;
  }

  const authorization = request.headers.authorization ?? '';
  const supplied = Buffer.from(authorization.startsWith('Bearer ')
    ? authorization.slice('Bearer '.length)
    : '', 'utf8');
  const authorized = supplied.length === tokenBytes.length
    && crypto.timingSafeEqual(supplied, tokenBytes);
  if (!authorized) {
    response.writeHead(403).end();
    return;
  }

  response.writeHead(202, { connection: 'close' }).end('shutdown accepted');
  requestGracefulShutdown();
});

server.on('error', (error) => {
  process.stderr.write(`T21_SHUTDOWN_BRIDGE_ERROR ${error.code ?? 'unknown'}\n`);
  process.exitCode = 70;
});

process.once('SIGINT', () => server.close());
process.once('exit', () => server.close());
server.listen(port, '127.0.0.1', () => {
  process.stdout.write(`T21_SHUTDOWN_BRIDGE_READY port=${port}\n`);
});

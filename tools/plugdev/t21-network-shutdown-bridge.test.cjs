'use strict';

const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const crypto = require('node:crypto');
const fs = require('node:fs');
const http = require('node:http');
const path = require('node:path');
const net = require('node:net');

const bridgePath = path.join(__dirname, 't21-network-shutdown-bridge.cjs');

function freePort() {
  return new Promise((resolve, reject) => {
    const listener = net.createServer();
    listener.once('error', reject);
    listener.listen(0, '127.0.0.1', () => {
      const { port } = listener.address();
      listener.close((error) => error ? reject(error) : resolve(port));
    });
  });
}

function requestShutdown(port, token) {
  return new Promise((resolve, reject) => {
    const request = http.request({
      host: '127.0.0.1',
      port,
      method: 'POST',
      path: '/shutdown',
      headers: { authorization: `Bearer ${token}` },
    }, (response) => {
      response.resume();
      response.once('end', () => resolve(response.statusCode));
    });
    request.once('error', reject);
    request.end();
  });
}

async function main() {
  const tempBase = path.resolve(process.env.T21_TEST_TEMP_ROOT
    ?? path.join(__dirname, '..', '..', '..', 'build', 'reports', 'foundation', 'T21-paper-network', 'test-temp'));
  if (process.platform === 'win32' && path.parse(tempBase).root.toLowerCase() !== 'c:\\') {
    throw new Error(`T21 bridge test temp root must be on C:, observed ${tempBase}`);
  }
  fs.mkdirSync(tempBase, { recursive: true });
  const tempRoot = fs.mkdtempSync(path.join(tempBase, 'run-'));
  const eventLog = path.join(tempRoot, 'child-events.jsonl');
  const port = await freePort();
  const token = crypto.randomBytes(32).toString('hex');
  const childCode = [
    "const { spawn } = require('node:child_process');",
    "const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)', '--', '-jar', process.env.T21_RUN_ROOT + '/paper/server.jar'], { stdio: 'ignore' });",
    "const helper = spawn(process.execPath, ['-e', 'setTimeout(() => {}, 5)'], { stdio: 'ignore' });",
    "helper.once('exit', () => process.stdout.write('TEST_HELPER_EXITED\\n'));",
    "child.once('spawn', () => process.stdout.write('TEST_SERVER_CHILD_READY\\n'));",
    "process.on('SIGINT', () => { process.stdout.write('TEST_SIGINT_HANDLED\\n'); child.once('exit', () => process.exit(0)); child.kill(); });",
    "process.stdout.write('TEST_CLI_READY\\n');",
    "setInterval(() => {}, 1000);",
  ].join('\n');
  const child = spawn(process.execPath, ['--require', bridgePath, '-e', childCode], {
    env: {
      ...process.env,
      T21_SHUTDOWN_BRIDGE_PORT: String(port),
      T21_SHUTDOWN_BRIDGE_TOKEN: token,
      T21_RUN_ROOT: tempRoot,
      T21_CHILD_PROCESS_EVENTS: eventLog,
    },
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });

  let stdout = '';
  let stderr = '';
  child.stdout.setEncoding('utf8').on('data', (chunk) => { stdout += chunk; });
  child.stderr.setEncoding('utf8').on('data', (chunk) => { stderr += chunk; });
  const readyDeadline = Date.now() + 5000;
  while (!stdout.includes('T21_SHUTDOWN_BRIDGE_READY') || !stdout.includes('TEST_SERVER_CHILD_READY')
    || !stdout.includes('TEST_HELPER_EXITED')) {
    if (child.exitCode !== null) throw new Error(`Bridge child exited early (${child.exitCode}): ${stderr}`);
    if (Date.now() > readyDeadline) throw new Error(`Bridge readiness timed out: ${stdout} ${stderr}`);
    await new Promise((resolve) => setTimeout(resolve, 20));
  }

  assert.equal(await requestShutdown(port, `${token}wrong`), 403, 'invalid bearer token is denied');
  assert.equal(child.exitCode, null, 'invalid token leaves CLI alive');
  assert.equal(await requestShutdown(port, token), 202, 'valid local shutdown request is accepted');

  const exit = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`Bridge child did not exit: ${stdout} ${stderr}`)), 5000);
    child.once('exit', (code, signal) => {
      clearTimeout(timer);
      resolve({ code, signal });
    });
  });
  assert.deepEqual(exit, { code: 0, signal: null }, 'SIGINT listener completes the child gracefully');
  assert.match(stdout, /TEST_SIGINT_HANDLED/, 'bridge emits SIGINT to the Plugin CLI process');

  const events = fs.readFileSync(eventLog, 'utf8').trim().split(/\r?\n/).map((line) => JSON.parse(line));
  assert(events.some((event) => event.event === 'spawn' && event.args.includes('-jar')),
    'PlugDev Java-child process start is recorded');
  assert(events.some((event) => event.event === 'kill' && event.args.includes('-jar')),
    'PlugDev Java-child kill is recorded for fail-closed cleanup auditing');
  const helperSpawn = events.find((event) => event.event === 'spawn' && event.pid !== null
    && !event.args.includes('-jar') && !event.args.join(' ').includes(tempRoot));
  assert(helperSpawn, 'child launches outside the run root are retained for the offline auditor');
  assert(events.some((event) => event.event === 'exit' && event.pid === helperSpawn.pid),
    'unscoped child exits remain pairable with their recorded launch');

  const relativeTemp = path.relative(tempBase, tempRoot);
  if (relativeTemp.startsWith('..') || path.isAbsolute(relativeTemp)) {
    throw new Error('Refusing to remove a bridge test temp directory outside its C: test root.');
  }
  fs.rmSync(tempRoot, { recursive: true, force: true });
  process.stdout.write('T21 network shutdown bridge test passed: 9 assertions\n');
}

main().catch((error) => {
  process.stderr.write(`${error.stack ?? error}\n`);
  process.exitCode = 1;
});

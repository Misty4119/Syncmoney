import { createRequire } from 'node:module'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { readFile } from 'node:fs/promises'
import { setTimeout as delay } from 'node:timers/promises'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const require = createRequire(resolve(process.env.PLUGDEV_ROOT || resolve(root, '../plugdev-main'), 'package.json'))
const mineflayer = process.env.PLUGDEV_MINEFLAYER_PATH
  ? require(process.env.PLUGDEV_MINEFLAYER_PATH) : require('mineflayer')
const run = promisify(execFile)
const rcon = async (...args) => (await run(process.execPath, [resolve(root, 'tools/plugdev/rcon.mjs'), ...args], { cwd: root })).stdout
const bots = []
async function join(username) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25640, username, auth: 'offline', version: process.argv[2] || '26.2' })
  bots.push(bot)
  bot.on('messagestr', text => console.log(`${username}: ${text}`))
  await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error(`${username}: spawn timeout`)), 30000)
    bot.once('spawn', () => { clearTimeout(timer); resolve() })
    bot.once('error', err => { clearTimeout(timer); reject(err) })
    bot.once('kicked', reason => { clearTimeout(timer); reject(Error(JSON.stringify(reason))) })
  })
  return bot
}
try {
  const a = await join('AcceptanceA')
  await join('AcceptanceB')
  await delay(1500)
  await rcon('smaccept')
  await delay(1000)
  a.chat('/money')
  await delay(500)
  a.chat('/pay AcceptanceB 10')
  await delay(2500)
  a.chat('/baltop')
  await rcon('smaccept', 'read')
  await delay(500)
  let log = await readFile(resolve(root, '.plugdev/run/logs/latest.log'), 'utf8')
  if (!log.includes('ACCEPTANCE READ a=990.00 b=530.00')) throw Error('Player /pay balance assertion failed')
  await rcon('tp', 'AcceptanceA', '300', '80', '300')
  await delay(1500)
  a.quit()
  await delay(1500)
  await join('AcceptanceA')
  await delay(1500)
  await rcon('smaccept', 'read')
  await delay(500)
  log = await readFile(resolve(root, '.plugdev/run/logs/latest.log'), 'utf8')
  const reads = log.match(/ACCEPTANCE READ a=.* b=.*/g) || []
  if (!reads.at(-1)?.includes('a=990.00 b=530.00')) throw Error('Reconnect persistence assertion failed')
  console.log('PLAYER ACCEPTANCE PASS: login, money, pay, baltop, teleport, logout/reconnect')
} finally {
  for (const bot of bots) bot.quit()
}
// Mineflayer plugins may retain timers after quit; the checks above have completed.
await delay(500)
process.exit(0)

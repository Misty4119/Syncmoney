import { createRequire } from 'node:module'
import { execFile as execFileCallback } from 'node:child_process'
import { resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { promisify } from 'node:util'

const root = resolve(import.meta.dirname, '../..')
const plugdevRoot = process.env.PLUGDEV_ROOT || resolve(root, '../plugdev-main')
const require = createRequire(resolve(plugdevRoot, 'package.json'))
const mineflayer = require(process.env.PLUGDEV_MINEFLAYER_PATH || 'mineflayer')
const execFile = promisify(execFileCallback)
const bots = []

function offlineUuid(username) {
  const hash = require('node:crypto').createHash('md5').update(`OfflinePlayer:${username}`, 'utf8').digest()
  hash[6] = (hash[6] & 0x0f) | 0x30
  hash[8] = (hash[8] & 0x3f) | 0x80
  const hex = hash.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

function waitFor(bot, event, timeout = 30000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(Error(`${bot.username}: ${event} timeout`)), timeout)
    bot.once(event, value => { clearTimeout(timer); resolve(value) })
    bot.once('error', error => { clearTimeout(timer); reject(error) })
    bot.once('kicked', reason => { clearTimeout(timer); reject(Error(`${bot.username}: kicked ${JSON.stringify(reason)}`)) })
  })
}

function join(username) {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username, auth: 'offline', version: '26.2' })
  bots.push(bot)
  bot.on('messagestr', message => console.log(`${username}: ${message}`))
  return waitFor(bot, 'spawn').then(() => bot)
}

async function command(bot, value, wait = 1200) {
  bot.chat(value)
  await delay(wait)
}

async function verifyBackends() {
  const psql = process.env.SYNCMONEY_TEST_PSQL || 'C:\\Program Files\\PostgreSQL\\18\\bin\\psql.exe'
  const database = process.env.SYNCMONEY_TEST_DB_NAME || 'syncmoney_network_acceptance'
  const username = process.env.SYNCMONEY_TEST_DB_USER || 'syncmoney_test'
  const port = process.env.SYNCMONEY_TEST_DB_PORT || '15432'
  const query = [
    'select player_name,balance from players',
    "where player_uuid in ('" + offlineUuid('AcceptanceA') + "','" + offlineUuid('AcceptanceB') + "')",
    'order by player_name'
  ].join(' ')
  const result = await execFile(psql, ['-h', '127.0.0.1', '-p', port, '-U', username, '-d', database, '-At', '-F', '|', '-c', query], { windowsHide: true })
  const rows = result.stdout.trim().split(/\r?\n/).filter(Boolean).map(line => line.split('|'))
  const balances = new Map(rows.map(([name, balance]) => [name, Number(balance)]))
  if (balances.get('AcceptanceA') !== 990 || balances.get('AcceptanceB') !== 530) {
    throw Error(`unexpected PostgreSQL balances: ${JSON.stringify(Object.fromEntries(balances))}`)
  }

  const redis = process.env.SYNCMONEY_TEST_REDIS_CLI || 'C:\\Users\\margo\\scoop\\shims\\redis-cli.exe'
  const redisResult = await execFile(redis, ['-h', '127.0.0.1', '-p', process.env.SYNCMONEY_TEST_REDIS_PORT || '16379', '-n', process.env.SYNCMONEY_TEST_REDIS_DB || '14', 'dbsize'], { windowsHide: true })
  const redisKeys = Number(redisResult.stdout.trim())
  if (!Number.isInteger(redisKeys) || redisKeys < 1) throw Error(`Redis database is empty (keys=${redisKeys})`)
  console.log(`NETWORK DATA PASS: PostgreSQL AcceptanceA=990 AcceptanceB=530; Redis db keys=${redisKeys}`)
}

try {
  const a = await join('AcceptanceA')
  const b = await join('AcceptanceB')
  await command(a, '/smaccept', 2500)
  await command(a, '/server survival', 2500)
  await command(b, '/server survival', 2500)
  await command(a, '/pay AcceptanceB 10', 2500)
  await command(a, '/money AcceptanceA')
  await command(b, '/money AcceptanceB')
  await delay(3500)
  await verifyBackends()
  console.log('NETWORK PLAYER PASS: proxy login, two backends, command routing, cross-server Redis/PostgreSQL balance')
} finally {
  for (const bot of bots) bot.quit()
}
await delay(500)

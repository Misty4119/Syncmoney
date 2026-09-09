import { mkdir, readFile, writeFile, copyFile, access } from 'node:fs/promises'
import { resolve, dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'
import { randomBytes } from 'node:crypto'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const plugdevRoot = process.env.PLUGDEV_ROOT || resolve(root, '../plugdev-main')
const require = createRequire(join(plugdevRoot, 'package.json'))
const YAML = require('yaml')
const [software = 'paper', version = '1.20.4', mode = 'local', features = 'off'] = process.argv.slice(2)
if (!['paper', 'folia', 'canvas'].includes(software) || !/^[\d.]+$/.test(version)
    || !['local', 'local_redis', 'sync'].includes(mode) || !['on', 'off'].includes(features)) throw Error('Invalid profile')
const dbType = (process.env.SYNCMONEY_TEST_DB_TYPE || 'mysql').toLowerCase()
if (!['mysql', 'postgresql'].includes(dbType)) throw Error(`Unsupported test database type: ${dbType}`)
const dbPort = Number(process.env.SYNCMONEY_TEST_DB_PORT || (dbType === 'postgresql' ? 5432 : 3306))
if (!Number.isInteger(dbPort) || dbPort < 1 || dbPort > 65535) throw Error(`Invalid test database port: ${dbPort}`)
const run = join(root, '.plugdev/run')
// A profile may only be prepared before startup. Never overwrite a running test server's config.
try {
  const session = JSON.parse((await readFile(join(root, '.plugdev/session.json'), 'utf8')).trim() || 'null')
  try { if (session) { process.kill(session.pid, 0); throw Error('Stop the PlugDev session before preparing a profile') } }
  catch (e) { if (e.code !== 'ESRCH') throw e }
} catch (e) { if (e.code !== 'ENOENT') throw e }
const config = YAML.parse(await readFile(join(root, 'plugdev.yml'), 'utf8'))
config.server = software
config.version = version
await mkdir(join(run, 'plugins/Syncmoney'), { recursive: true })
await writeFile(join(root, '.plugdev/profile.yml'), YAML.stringify(config))
const enabled = features === 'on'
const settings = {
  'server-name': `acceptance-${software}`,
  'config-version': YAML.parse(await readFile(join(root, 'src/main/resources/config.yml'), 'utf8'))['config-version'],
  'db-enabled': mode === 'sync',
  'pubsub-enabled': mode !== 'local',
  redis: { enabled: mode !== 'local', host: '127.0.0.1', port: 16379, database: 15 },
  database: { enabled: mode === 'sync', type: dbType, host: process.env.SYNCMONEY_TEST_DB_HOST || '127.0.0.1', port: dbPort,
    username: process.env.SYNCMONEY_TEST_DB_USER || (dbType === 'postgresql' ? 'syncmoney_test' : 'root'),
    password: process.env.SYNCMONEY_TEST_DB_PASSWORD || '',
    database: process.env.SYNCMONEY_TEST_DB_NAME || 'syncmoney_acceptance' },
  economy: { mode },
  'circuit-breaker': { enabled, 'player-protection': { enabled } },
  'shadow-sync': { enabled, target: 'local', storage: { type: 'sqlite' } },
  audit: { enabled, cleanup: { enabled }, export: { enabled: false }, redis: { enabled: mode !== 'local' } },
  'discord-webhook': { enabled: false },
  'transfer-guard': { enabled },
  'web-admin': { enabled, server: { host: '127.0.0.1', port: 18080 },
    security: { 'api-key': randomBytes(32).toString('hex') }, web: { 'auto-build': false, 'auto-update': false } },
  pay: { 'cooldown-seconds': 0 }
}
await writeFile(join(run, 'plugins/Syncmoney/config.yml'), YAML.stringify(settings))
await access(join(root, 'build/acceptance/SyncmoneyAcceptance.jar'))
await copyFile(join(root, 'build/acceptance/SyncmoneyAcceptance.jar'), join(run, 'plugins/SyncmoneyAcceptance.jar'))
console.log(`Prepared ${software} ${version}, ${mode}, features=${features}. Start with: plugdev server start --config .plugdev/profile.yml, then verify the actual server version in the acceptance log.`)

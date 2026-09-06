import { cp, mkdir, readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { createRequire } from 'node:module'

const root = resolve(import.meta.dirname, '../..')
const plugdevRoot = process.env.PLUGDEV_ROOT || resolve(root, '../plugdev-main')
const require = createRequire(resolve(plugdevRoot, 'package.json'))
const YAML = require('yaml')
const networkRoot = resolve(root, '.plugdev/network')
const pluginSource = resolve(root, 'build/plugdev/Syncmoney.jar')
const acceptanceSource = resolve(root, 'build/acceptance/SyncmoneyAcceptance.jar')
const vaultSource = resolve(root, '.plugdev/deps/VaultUnlocked-2.20.0.jar')
const bootstrapSource = resolve(root, '.plugdev/run/plugins/plugdev-bootstrap-paper.jar')
const viaSource = resolve(root, '.plugdev/deps/ViaVersion-5.12.0-SNAPSHOT.jar')

for (const path of [pluginSource, acceptanceSource, vaultSource, bootstrapSource, viaSource]) {
  try { await readFile(path) } catch { throw Error(`Missing network test dependency: ${path}`) }
}

for (const backend of ['lobby', 'survival']) {
  const plugins = resolve(networkRoot, 'backends', backend, 'plugins')
  await mkdir(plugins, { recursive: true })
  await cp(pluginSource, resolve(plugins, 'Syncmoney.jar'))
  await cp(acceptanceSource, resolve(plugins, 'SyncmoneyAcceptance.jar'))
  await cp(vaultSource, resolve(plugins, 'VaultUnlocked-2.20.0.jar'))
  await cp(bootstrapSource, resolve(plugins, 'plugdev-bootstrap-paper.jar'))
  await cp(viaSource, resolve(plugins, 'ViaVersion-5.12.0-SNAPSHOT.jar'))
  const configName = backend === 'lobby' ? 'network-lobby.yml' : 'network-survival.yml'
  const config = YAML.parse(await readFile(resolve(root, 'tools/plugdev', configName), 'utf8'))
  await mkdir(resolve(plugins, 'Syncmoney'), { recursive: true })
  await writeFile(resolve(plugins, 'Syncmoney/config.yml'), YAML.stringify(config))
}

await mkdir(resolve(networkRoot, 'proxy/plugins'), { recursive: true })

console.log('Prepared isolated network dependencies for lobby and survival (Paper 26.2, backend ViaVersion, velocity-ctd latest, PostgreSQL 15432, Redis 16379/db14).')

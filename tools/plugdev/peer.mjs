import { mkdir, cp, readFile, writeFile } from 'node:fs/promises'
import { createRequire } from 'node:module'
import { resolve } from 'node:path'
const require = createRequire(resolve(process.env.PLUGDEV_ROOT || '../plugdev-main', 'package.json'))
const yaml = require('yaml')
const target = '.plugdev/peer-run'
await mkdir(target + '/plugins/Syncmoney', { recursive: true })
for (const path of ['libraries', 'eula.txt', 'server.properties']) await cp('.plugdev/run/' + path, target + '/' + path, { recursive: true })
for (const path of ['Syncmoney.jar', 'SyncmoneyAcceptance.jar', 'url-VaultUnlocked-2.20.0.jar']) {
  await cp('.plugdev/run/plugins/' + path, target + '/plugins/' + path)
}
let properties = await readFile(target + '/server.properties', 'utf8')
properties = properties.replace(/^server-port=.*$/m, 'server-port=25641').replace(/^rcon.port=.*$/m, 'rcon.port=35641')
await writeFile(target + '/server.properties', properties)
const config = yaml.parse(await readFile('.plugdev/run/plugins/Syncmoney/config.yml', 'utf8'))
config['server-name'] = 'acceptance-folia-peer'
config['web-admin'].enabled = false
await writeFile(target + '/plugins/Syncmoney/config.yml', yaml.stringify(config))
console.log('Prepared independent Folia peer on 25641/RCON 35641 sharing only isolated Redis/SQL settings.')

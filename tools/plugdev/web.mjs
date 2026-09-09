import { createRequire } from 'node:module'
import { resolve } from 'node:path'
import { readFile } from 'node:fs/promises'
const require = createRequire(resolve(process.env.PLUGDEV_ROOT || '../plugdev-main', 'package.json'))
const config = require('yaml').parse(await readFile('.plugdev/run/plugins/Syncmoney/config.yml', 'utf8'))
const base = 'http://127.0.0.1:18080'
const key = config['web-admin'].security['api-key']
const unauthorized = await fetch(base + '/api/system/status')
if (![401, 403].includes(unauthorized.status)) throw Error('Unauthenticated API access was not denied')
for (const path of ['/health', '/api/system/status', '/api/economy/stats', '/version.json', '/']) {
  const response = await fetch(base + path, { headers: { Authorization: `Bearer ${key}` } })
  if (!response.ok) throw Error(`${path}: ${response.status}`)
  const body = await response.text()
  if (path === '/version.json' && JSON.parse(body).version !== '1.3.1') throw Error('Stale embedded frontend')
  if (path === '/' && !body.includes('<html')) throw Error('Missing frontend HTML')
  console.log(`WEB PASS ${path}: ${response.status}`)
}
console.log('WEB ACCEPTANCE PASS: authentication, health, system/economy API, bundled 1.3.1 frontend')

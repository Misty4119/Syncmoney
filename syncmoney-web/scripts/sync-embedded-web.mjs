import { cp, mkdir, rm } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const webRoot = resolve(scriptDir, '..')
const builtDir = resolve(webRoot, 'dist')
const embeddedDir = resolve(webRoot, '..', 'src', 'main', 'resources', 'syncmoney-web', 'dist')

await rm(embeddedDir, { recursive: true, force: true })
await mkdir(embeddedDir, { recursive: true })
await cp(builtDir, embeddedDir, { recursive: true })

console.log(`Synced Web Admin bundle to ${embeddedDir}`)

import { cp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const webRoot = resolve(scriptDir, '..')
const builtDir = resolve(webRoot, 'dist')
const embeddedDir = resolve(webRoot, '..', 'src', 'main', 'resources', 'syncmoney-web', 'dist')
const webAdminServer = resolve(
  webRoot,
  '..',
  'src',
  'main',
  'java',
  'noietime',
  'syncmoney',
  'web',
  'server',
  'WebAdminServer.java'
)

const compareNames = (left, right) => (left < right ? -1 : left > right ? 1 : 0)

async function listFiles(directory) {
  const entries = await readdir(directory, { withFileTypes: true })
  return entries
    .filter((entry) => entry.isFile())
    .map((entry) => entry.name)
    .sort(compareNames)
}

function replaceJavaArray(source, variableName, fileNames) {
  const pattern = new RegExp(`(String\\[\\] ${variableName} = \\{)[\\s\\S]*?(\\};)`)
  const body = fileNames.map((fileName) => `                "${fileName}"`).join(',\n')

  if (!pattern.test(source)) {
    throw new Error(`Unable to find ${variableName} in WebAdminServer.extractIndividualFiles`)
  }

  return source.replace(pattern, (_match, opening, closing) => `${opening}\n${body}\n        ${closing}`)
}

await rm(embeddedDir, { recursive: true, force: true })
await mkdir(embeddedDir, { recursive: true })
await cp(builtDir, embeddedDir, { recursive: true })

const rootFiles = await listFiles(embeddedDir)
const assetFiles = await listFiles(resolve(embeddedDir, 'assets'))
const iconFiles = await listFiles(resolve(embeddedDir, 'icons'))

let webAdminSource = await readFile(webAdminServer, 'utf8')
webAdminSource = replaceJavaArray(webAdminSource, 'rootFiles', rootFiles)
webAdminSource = replaceJavaArray(webAdminSource, 'assetFiles', assetFiles)
webAdminSource = replaceJavaArray(webAdminSource, 'iconFiles', iconFiles)
await writeFile(webAdminServer, webAdminSource, 'utf8')

console.log(`Synced Web Admin bundle to ${embeddedDir}`)
console.log(`Synced WebAdminServer.extractIndividualFiles with ${rootFiles.length + assetFiles.length + iconFiles.length} files`)

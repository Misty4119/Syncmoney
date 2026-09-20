import { access, readFile, writeFile } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const scriptDir = dirname(fileURLToPath(import.meta.url))
const webRoot = resolve(scriptDir, '..')
const versionFile = resolve(webRoot, 'public', 'version.json')
const syncScript = resolve(scriptDir, 'sync-embedded-web.mjs')

const packageJson = JSON.parse(await readFile(resolve(webRoot, 'package.json'), 'utf8'))
const originalVersionFile = await readFile(versionFile, 'utf8')
const requestedVersion = process.env.SYNCMONEY_VERSION?.trim()
const version = requestedVersion || packageJson.version

if (!version) {
  throw new Error('Unable to determine the frontend version')
}

function run(command, args, useShell = process.platform === 'win32') {
  return new Promise((resolvePromise, reject) => {
    const child = spawn(command, args, {
      cwd: webRoot,
      env: { ...process.env, SYNCMONEY_VERSION: version },
      stdio: 'inherit',
      shell: useShell
    })

    child.once('error', reject)
    child.once('exit', (code, signal) => {
      if (code === 0) {
        resolvePromise()
      } else {
        reject(new Error(`${command} ${args.join(' ')} failed (${signal ?? `exit ${code}`})`))
      }
    })
  })
}

const pnpm = process.platform === 'win32' ? 'pnpm.cmd' : 'pnpm'
const versionPayload = JSON.parse(originalVersionFile)
versionPayload.version = version

try {
  await writeFile(versionFile, `${JSON.stringify(versionPayload, null, 2)}\n`, 'utf8')
  await run(pnpm, ['run', 'build'])
  try {
    await access(syncScript)
    await run(process.execPath, [syncScript], false)
  } catch (error) {
    if (error?.code === 'ENOENT') {
      // The public mirror can build the frontend without the core repository's
      // embedded-resource synchronizer.
    } else {
      throw error
    }
  }
} finally {
  await writeFile(versionFile, originalVersionFile, 'utf8')
}

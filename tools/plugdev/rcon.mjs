import net from 'node:net'
import { readFile } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

// Fallback for a PlugDev launcher that has not persisted session.json yet.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const properties = await readFile(resolve(root, '.plugdev/run/server.properties'), 'utf8')
const property = name => properties.split(/\r?\n/).find(line => line.startsWith(name + '='))?.slice(name.length + 1)
const password = property('rcon.password')
if (!password) throw Error('No local test RCON password')
const command = process.argv.slice(2).join(' ')
if (!command) throw Error('A console command is required')
const socket = net.connect(Number(property('rcon.port')), '127.0.0.1')
const deadline = setTimeout(() => socket.destroy(Error('RCON timeout')), 15000)
let incoming = Buffer.alloc(0)
let finishTimer
function packet(id, type, body) {
  const payload = Buffer.from(body, 'utf8')
  const data = Buffer.alloc(payload.length + 14)
  data.writeInt32LE(data.length - 4, 0)
  data.writeInt32LE(id, 4)
  data.writeInt32LE(type, 8)
  payload.copy(data, 12)
  return data
}
socket.on('connect', () => socket.write(packet(1, 3, password)))
socket.on('data', chunk => {
  incoming = Buffer.concat([incoming, chunk])
  while (incoming.length >= 4 && incoming.length >= incoming.readInt32LE(0) + 4) {
    const length = incoming.readInt32LE(0) + 4
    const message = incoming.subarray(0, length)
    incoming = incoming.subarray(length)
    const id = message.readInt32LE(4), type = message.readInt32LE(8)
    if (id === -1) { socket.destroy(Error('RCON authentication failed')); return }
    if (id === 1 && type === 2) socket.write(packet(2, 2, command))
    else if (id === 2) {
      const text = message.subarray(12, length - 2).toString('utf8')
      if (text) console.log(text)
      clearTimeout(finishTimer)
      finishTimer = setTimeout(() => { socket.end(); clearTimeout(deadline) }, 150)
    }
  }
})
socket.on('error', e => { clearTimeout(deadline); console.error(e.message); process.exitCode = 1 })
socket.on('close', () => clearTimeout(deadline))

'use strict'

// Private native caller only. Read the tail so long-running conversations work without a full replay.
const fs = require('node:fs')
const path = require('node:path')
const { createHash } = require('node:crypto')
const zlib = require('node:zlib')
const { collectCandidates, zstdFrameEnd } = require('./session-catalog.cjs')
const MAX_INPUT = 256 * 1024
const MAX_TEXT = 512 * 1024
const magic = Buffer.from([0x28, 0xb5, 0x2f, 0xfd])

function tail(file, compressed) {
  const fd = fs.openSync(file, fs.constants.O_RDONLY | fs.constants.O_NOFOLLOW)
  try {
    const stat = fs.fstatSync(fd)
    if (!stat.isFile() || stat.size === 0) return null
    const bytes = Buffer.alloc(Math.min(MAX_INPUT, stat.size))
    if (fs.readSync(fd, bytes, 0, bytes.length, stat.size - bytes.length) !== bytes.length) return null
    let plain = bytes
    let partial = bytes.length < stat.size
    if (compressed) {
      const chunks = []
      let end = bytes.length
      let total = 0
      while (end >= magic.length && chunks.length < 512) {
        let start = bytes.lastIndexOf(magic, end - magic.length)
        // The magic bytes can also appear inside a compressed block.
        while (start >= 0) {
          let fits = false
          try { fits = zstdFrameEnd(bytes.subarray(start, end)) === end - start } catch {}
          if (fits || start === 0) break
          start = bytes.lastIndexOf(magic, start - 1)
        }
        if (start < 0) break
        let chunk
        try { chunk = zlib.zstdDecompressSync(bytes.subarray(start, end), { maxOutputLength: MAX_TEXT - total }) }
        catch { break }
        chunks.push(chunk)
        total += chunk.length
        end = start
        if (total >= MAX_TEXT) break
      }
      if (!chunks.length) return null
      partial ||= end > 0
      plain = Buffer.concat(chunks.reverse())
    }
    let text = plain.toString('utf8')
    if (partial) {
      const newline = text.indexOf('\n')
      text = newline < 0 ? '' : text.slice(newline + 1)
    }
    return { text, at: Math.trunc(stat.mtimeMs), size: stat.size,
      tailHash: createHash('sha256').update(bytes.subarray(-4096)).digest('hex') }
  } finally { fs.closeSync(fd) }
}

function messages(text) {
  let human = false
  const result = []
  for (const line of text.split('\n')) {
    if (!line.includes('"turn/start"') && !line.includes('"user/message"') && !line.includes('"assistant/message"')) continue
    let event
    try { event = JSON.parse(line) } catch { continue }
    const data = event?.data
    if (event?.type === 'turn/start') { human = false; continue }
    let blocks, role
    if (event?.type === 'user/message') {
      human = data?.source?.kind === 'user'
      if (!human) continue
      blocks = data.content
      role = 'user'
    } else if (human && event?.type === 'assistant/message') {
      blocks = data?.message?.content
      role = 'assistant'
    } else continue
    if (!Array.isArray(blocks)) continue
    const value = blocks.filter(b => b?.type === 'text' && typeof b.text === 'string')
      .map(b => b.text).join(' ').replace(/\s+/gu, ' ').trim()
    if (value) result.push({ role, text: value.slice(0, 400) })
    if (result.length > 4) result.shift()
  }
  return result.some(r => r.role === 'user') ? result : []
}

function recent(root, digests = [], includeRecent = true) {
  const floor = Date.now() - 7 * 86400000
  const entries = collectCandidates(root).candidates.filter(c => c.updatedAt >= floor)
    .sort((a, b) => b.updatedAt - a.updatedAt)
  const tracked = new Set(digests)
  const targets = entries.filter(c => tracked.has(createHash('sha256').update(c.id).digest('hex').slice(0, 16)))
  const recentIds = new Set()
  const result = []
  for (const candidate of new Set([...targets, ...(includeRecent ? entries.slice(0, 6) : [])])) {
    const selected = targets.includes(candidate)
    if (!selected && recentIds.size >= 3) continue
    const stamp = tail(candidate.log.full, candidate.log.compressed)
    if (!stamp) continue
    const turns = messages(stamp.text)
    if (turns.length) result.push({ id: candidate.id, file: path.relative(root, candidate.log.full),
      at: stamp.at, size: stamp.size, tailHash: stamp.tailHash, turns })
    if (turns.length && !selected) recentIds.add(candidate.id)
  }
  return result
}

if (require.main === module) {
  try { process.stdout.write(JSON.stringify({ chats: recent('/root/.dsh/sessions', (process.argv[2] || '').split(',').filter(Boolean), process.argv[3] !== 'false') })) }
  catch { process.exitCode = 1 }
}
module.exports = { tail, messages, recent }

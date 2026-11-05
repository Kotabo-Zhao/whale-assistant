import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { randomBytes } from 'node:crypto'
import { zstdCompressSync } from 'node:zlib'
import reader from '../android/app/src/main/assets/support/initiative-chat.cjs'

const line = (type, data) => JSON.stringify({ type, data }) + '\n'
const user = (text, kind = 'user') => line('user/message', { source: { kind }, content: [{ type: 'text', text }] })
const reply = text => line('assistant/message', { message: { content: [{ type: 'thinking', text: 'private' }, { type: 'text', text }] } })

test('only ordinary human turns and the visible answers reach the follow-up context', () => {
  const stream = reply('old tail') + user('first') + reply('answer') +
    line('tool/result', { text: 'private' }) + line('turn/start', { turn: 2 }) +
    user('background', 'mobile-workers') + reply('automated') + line('turn/start', { turn: 3 }) + user('latest')
  assert.deepEqual(reader.messages(stream), [{ role: 'user', text: 'first' }, { role: 'assistant', text: 'answer' }, { role: 'user', text: 'latest' }])
})

test('a bounded plain or zstd tail reads recent messages past a large log prefix', t => {
  const dir = mkdtempSync(join(tmpdir(), 'whale-followup-'))
  t.after(() => rmSync(dir, { recursive: true, force: true }))
  const prefix = line('tool/result', { text: randomBytes(450000).toString('base64') })
  const ends = [user('previous'), reply('ok'), user('new question'), reply('new answer')]
  for (const compressed of [false, true]) {
    const file = join(dir, compressed ? 'events.zstd' : 'events')
    const parts = [prefix, ...ends].map(v => Buffer.from(v))
    writeFileSync(file, Buffer.concat(compressed ? parts.map(p => zstdCompressSync(p)) : parts))
    const turns = reader.messages(reader.tail(file, compressed).text)
    assert.deepEqual(turns.slice(-2), [{ role: 'user', text: 'new question' }, { role: 'assistant', text: 'new answer' }])
  }
})

import test from 'node:test'
import assert from 'node:assert/strict'
import { installScreenWatch } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/screen-watch.js'

test('Android RGB becomes an image attachment; stop is explicit and cross-session capture is rejected', async t => {
  const tools = new Map(), hooks = new Map(), images = [], notices = []
  let active = false, owner = ''
  const ctx = { on: (name, fn) => hooks.set(name, fn), tools: { register: tool => tools.set(tool.name, tool) },
    attachments: { saveImage: async value => { images.push(value.data); return { attachmentId: 'sha256:' + 'a'.repeat(64), mediaType: 'image/png', width: 200, height: 100, bytes: value.data.length } } },
    sessionController: { resolveAgent: async () => ({ agent: { followup: message => notices.push(message) } }) }, sessionPersistence: { flush: async () => {} } }
  const bridge = async (command, param) => {
    const request = JSON.parse(param)
    if (command === 'screenWatchFrame') return { output: JSON.stringify({ width: 200, height: 100, rgb: Buffer.alloc(200 * 100 * 3, 100).toString('base64') }) }
    if (request.action === 'start') { active = true; owner = request.session }
    if (request.action === 'stop') active = false
    return { output: JSON.stringify({ enabled: true, active, owner }) }
  }
  installScreenWatch(ctx, { defineTool: tool => tool, bridge, assertImageCapableRoute: async () => {} })
  t.after(() => hooks.get('dispose')())
  const execute = tools.get('mobile_see_now').execute
  const exec = { agent: { id: 'session-one' }, signal: new AbortController().signal }
  const first = await execute({ action: 'start' }, exec)
  assert.equal(first.image.mediaType, 'image/png')
  assert.equal(images[0].subarray(0, 8).toString('hex'), '89504e470d0a1a0a')
  assert.equal(notices.length, 0, 'first capture must not self-trigger an extra model turn')
  await assert.rejects(execute({ action: 'peek' }, { ...exec, agent: { id: 'session-other' } }), /其他会话/)
  await execute({ action: 'stop' }, exec)
  assert.equal(active, false)
  const peek = await execute({ action: 'peek' }, exec)
  assert.equal(peek.image, undefined)
  assert.equal(active, false, 'peek after stop must not restart capture')
})

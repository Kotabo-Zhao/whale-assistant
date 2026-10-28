import { test } from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import vm from 'node:vm'

const script = "import importlib.util,json; s=importlib.util.spec_from_file_location('p','scripts/market-mobile-patch.py'); p=importlib.util.module_from_spec(s); s.loader.exec_module(p); print(json.dumps({'route':p.ROUTE_INSERT,'client':p.CLIENT_INSERT,'update':p.UPDATE_INSERT,'updateClient':p.UPDATE_CLIENT_INSERT}))"
const patch = JSON.parse(execFileSync('python', ['-c', script], { encoding: 'utf8' }))
async function invoke(options = {}) {
  const sent = []
  const requests = []
  const fakeResponse = options.reply ?? { ok: true, json: async () => ({ ok: true, exitCode: 0 }) }
  const env = { DSH_DEVICE_BRIDGE_PORT: '12345', DSH_DEVICE_BRIDGE_TOKEN: 'a'.repeat(43), ...(options.env ?? {}) }
  const context = vm.createContext({ process: { env }, AbortController, setTimeout, clearTimeout,
    body: options.body ?? {}, plainTarget: options.target ?? 'safe-plugin', NPM_NAME_RE: /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/,
    response: {}, sendJson: (_r, status, payload) => sent.push({ status, payload }),
    fetch: async (url, init) => { requests.push({ url, init }); return fakeResponse },
  })
  await vm.runInContext(`(async () => { ${patch.route} })()`, context)
  return { sent, requests }
}
test('npm proposal is queued for review and never reported installed', async () => {
  const { sent, requests } = await invoke()
  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, 'http://127.0.0.1:12345/device-command')
  assert.deepEqual(JSON.parse(requests[0].init.body), { command: 'pluginMarketProposal', param: 'safe-plugin' })
  assert.equal(sent[0].payload.mobilePending, true)
  assert.match(sent[0].payload.error, /尚未安装/)
  assert.match(patch.client, /state: "warned"/)
})
test('unsupported sources, explicit versions, force, bad token and bridge failures never queue', async () => {
  for (const options of [{ target: 'github:user/repo' }, { body: { version: '1.0.0' } }, { body: { force: true } }, { env: { DSH_DEVICE_BRIDGE_TOKEN: 'bad' } }]) {
    const { sent, requests } = await invoke(options)
    assert.equal(requests.length, 0)
    assert.equal(sent[0].status, 400)
    assert.notEqual(sent[0].payload.mobilePending, true)
  }
  const rejected = await invoke({ reply: { ok: true, json: async () => ({ ok: false, exitCode: 1 }) } })
  assert.notEqual(rejected.sent[0].payload.mobilePending, true)
})

async function invokeUpdate(body, extraEnv = {}) {
  const sent = []; const requests = []
  const context = vm.createContext({ process: { env: { DSH_DEVICE_BRIDGE_PORT: '12345', DSH_DEVICE_BRIDGE_TOKEN: 'a'.repeat(43), ...extraEnv } }, AbortController, setTimeout, clearTimeout, body, name: body.name, NPM_NAME_RE: /^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/, response: {}, sendJson: (_r, status, payload) => sent.push({ status, payload }), fetch: async (url, init) => { requests.push({ url, init }); return { ok: true, json: async () => ({ ok: true, exitCode: 0 }) } } })
  await vm.runInContext(`(async () => { ${patch.update} })()`, context)
  return { sent, requests }
}
test('update proposal preserves suggested version for user review', async () => {
  const result = await invokeUpdate({ name: 'safe-plugin', eligibleVersion: '1.2.3' })
  assert.equal(result.sent[0].payload.mobilePending, true)
  assert.match(result.sent[0].payload.error, /尚未更新/)
  const data = JSON.parse(result.requests[0].init.body)
  assert.equal(data.command, 'pluginMarketUpdateProposal')
  assert.deepEqual(JSON.parse(data.param), { name: 'safe-plugin', suggestedVersion: '1.2.3' })
  assert.match(patch.updateClient, /state: "warned"/)
})
test('unsupported update overrides or malformed targets do not queue', async () => {
  for (const input of [{ name: '../bad' }, { name: 'safe', force: true }, { name: 'safe', restore: true }, { name: 'safe', compatVersion: '1.0.0' }, { name: 'safe', eligibleVersion: 'bad' }]) {
    const result = await invokeUpdate(input)
    assert.equal(result.requests.length, 0); assert.equal(result.sent[0].status, 400)
  }
})

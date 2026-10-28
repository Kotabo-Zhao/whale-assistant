const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const os = require('node:os')
const { createManager } = require('../android/app/src/main/assets/support/plugin-manager.cjs')
const samples = process.env.WHALE_MARKET_SAMPLES

test('published dshmarket and dependencies pass a local staged transaction', { skip: !samples }, t => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'whale-market-sample-'))
  t.after(() => fs.rmSync(root, { recursive: true, force: true }))
  function write(relative, value) {
    const destination = path.join(root, relative)
    fs.mkdirSync(path.dirname(destination), { recursive: true })
    fs.writeFileSync(destination, JSON.stringify(value))
  }
  write('root/.dsh/profiles/web/package.json', { dsh: { profile: { bundles: ['@deepseek-ai/dsh-base'] } } })
  write('opt/dsh/node_modules/@deepseek-ai/dsh-base/package.json', { name:'@deepseek-ai/dsh-base', version:'1.0.0', dsh:{ bundle:{patch:'./cordis.patch.json'} } })
  write('opt/dsh/node_modules/@deepseek-ai/dsh-base/cordis.patch.json', [])
  write('opt/dsh/node_modules/@deepseek-ai/dsh/package.json', { name:'@deepseek-ai/dsh', version:'0.2.1-rc.1' })
  const yaml = require(path.join(samples, 'js-yaml'))
  const install = (id, directory) => {
    assert.equal(id, 'dshmarket')
    for (const name of ['dshmarket','undici','js-yaml','argparse']) {
      fs.cpSync(path.join(samples, name), path.join(directory, 'node_modules', name), { recursive:true })
    }
  }
  const manager = createManager(root, install, yaml.load)
  const result = manager.importPackage('dshmarket@1.73.3')
  const market = result.plugins.find(item => item.id === 'dshmarket')
  assert.equal(market?.version, '1.73.3')
  assert.equal(market?.installed, true)
  assert.equal(market?.readable, true)
  assert.ok(market.children.some(item => item.id === 'dsh-market'))
})

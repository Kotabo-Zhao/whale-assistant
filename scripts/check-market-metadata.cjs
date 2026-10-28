#!/usr/bin/env node
// Read-only pinned registry metadata diagnostic; it does not download or execute packages.
const { npmMetadataCheck } = require('../android/app/src/main/assets/support/plugin-manager.cjs')
const packages = [['dshmarket', '1.73.3'], ['undici', '7.29.1'], ['js-yaml', '4.3.2'], ['argparse', '2.0.1']]
const nodeVersion = process.argv[2]
if (!nodeVersion) { console.error('Usage: node scripts/check-market-metadata.cjs <guest-node-version>'); process.exit(2) }
async function read(name, version) {
  const response = await fetch(`https://registry.npmjs.org/${encodeURIComponent(name)}/${encodeURIComponent(version)}`, {
    redirect: 'error', signal: AbortSignal.timeout(15000), headers: { accept: 'application/json' },
  })
  if (!response.ok || !response.body) throw Error(`${name}: registry unavailable`)
  const reader = response.body.getReader()
  const chunks = []; let size = 0
  try {
    while (true) {
      const part = await reader.read()
      if (part.done) break
      size += part.value.byteLength
      if (size > 1024 * 1024) { await reader.cancel(); throw Error(`${name}: metadata exceeds limit`) }
      chunks.push(part.value)
    }
  } finally { reader.releaseLock() }
  const bytes = Buffer.concat(chunks.map(chunk => Buffer.from(chunk)))
  const json = JSON.parse(new TextDecoder('utf-8', {fatal:true}).decode(bytes))
  const result = npmMetadataCheck(json, name, version, 'linux', 'arm64', nodeVersion)
  return { name, version, node: json.engines?.node ?? null, result: result ?? 'DECLARATIONS_ACCEPTED' }
}
Promise.all(packages.map(([name, version]) => read(name, version))).then(rows => {
  console.log(JSON.stringify({ checkedAt: new Date().toISOString(), guestNode: nodeVersion, packages: rows }, null, 2))
  if (rows.some(row => row.result !== 'DECLARATIONS_ACCEPTED')) process.exitCode = 1
}).catch(error => { console.error(error.message); process.exitCode = 1 })

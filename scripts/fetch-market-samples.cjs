// Fetch pinned npm archives for offline checks, verifying registry SHA-512. Never execute packages.
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const out = process.argv[2]
if (!out || fs.existsSync(out)) throw Error('Provide a new output directory')
fs.mkdirSync(out)
const rows = [['dshmarket','1.73.3'], ['undici','7.29.1'], ['js-yaml','4.3.2'], ['argparse','2.0.1']]
async function get(url, max) {
  const parsed = new URL(url)
  if (parsed.protocol !== 'https:' || parsed.hostname !== 'registry.npmjs.org' || parsed.port || parsed.username || parsed.password) throw Error('Unexpected registry URL')
  const response = await fetch(parsed, { redirect:'error', signal:AbortSignal.timeout(30000) })
  if (!response.ok || !response.body) throw Error(`Registry unavailable: ${response.status}`)
  const reader = response.body.getReader(); const chunks = []; let total = 0
  try {
    while (true) { const part = await reader.read(); if (part.done) break; total += part.value.byteLength; if (total > max) { await reader.cancel(); throw Error('Download limit exceeded') }; chunks.push(Buffer.from(part.value)) }
  } finally { reader.releaseLock() }
  return Buffer.concat(chunks)
}
;(async () => {
  for (const [name, version] of rows) {
    const meta = JSON.parse(new TextDecoder('utf-8', {fatal:true}).decode(await get(`https://registry.npmjs.org/${name}/${version}`, 1024*1024)))
    if (meta.name !== name || meta.version !== version || !/^sha512-[A-Za-z0-9+/]+={0,2}$/.test(meta.dist?.integrity ?? '')) throw Error(`Invalid metadata for ${name}`)
    const data = await get(meta.dist.tarball, 12*1024*1024)
    const actual = 'sha512-' + crypto.createHash('sha512').update(data).digest('base64')
    if (actual !== meta.dist.integrity) throw Error(`Integrity mismatch for ${name}`)
    fs.writeFileSync(path.join(out, `${name}-${version}.tgz`), data, {flag:'wx'})
    console.log(JSON.stringify({name,version,bytes:data.length,integrity:actual}))
  }
})().catch(e => { console.error(e.message); process.exitCode=1 })

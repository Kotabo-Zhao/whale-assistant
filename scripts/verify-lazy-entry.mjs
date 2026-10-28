// Run after `pnpm run build`: verify management screens stay out of the initial HTML and static entry imports.
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
const root = path.resolve('dist')
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8')
const entryName = html.match(/<script\s+type="module"[^>]*src="\.\/([^" ]+\.js)"/)?.[1]
assert.ok(entryName, 'entry script missing')
const entry = fs.readFileSync(path.join(root, entryName), 'utf8')
const staticImports = [...entry.matchAll(/(?:^|[;\n])\s*import\s+(?:[^;]*?\s+from\s+)?["']([^"']+)["']/g)].map(match => match[1])
const files = fs.readdirSync(path.join(root, 'assets'))
for (const screen of ['PluginSettings', 'FilesScreen', 'RuntimeVersionsScreen']) {
  const chunk = files.find(file => file.startsWith(`${screen}-`) && file.endsWith('.js'))
  assert.ok(chunk, `${screen} async chunk missing`)
  assert.ok(!html.includes(chunk), `${screen} referenced by initial HTML`)
  assert.ok(!staticImports.some(specifier => specifier.endsWith(chunk)), `${screen} statically imported`)
  assert.ok(entry.includes(chunk), `${screen} dynamic import missing`)
  console.log(`${screen}: deferred (${fs.statSync(path.join(root, 'assets', chunk)).size} bytes)`)
}

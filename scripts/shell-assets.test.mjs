import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, resolve } from 'node:path'
import { spawnSync } from 'node:child_process'
import test from 'node:test'
import assert from 'node:assert/strict'
import { crc32, deflateRawSync } from 'node:zlib'

const script = resolve(import.meta.dirname, 'verify-shell-assets.py')
const gradle = resolve(import.meta.dirname, '../android/app/build.gradle')

function zip(entries) {
  const dir = mkdtempSync(join(tmpdir(), 'shell-assets-'))
  const file = join(dir, 'app.apk')
  const parts = []
  const central = []
  let offset = 0
  for (const [name, content] of entries) {
    const data = Buffer.from(content)
    const compressed = deflateRawSync(data)
    const checksum = crc32(data)
    const nameBytes = Buffer.from(name)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)
    local.writeUInt16LE(20, 4)
    local.writeUInt16LE(8, 8)
    local.writeUInt32LE(checksum, 14)
    local.writeUInt32LE(compressed.length, 18)
    local.writeUInt32LE(data.length, 22)
    local.writeUInt16LE(nameBytes.length, 26)
    parts.push(local, nameBytes, compressed)
    const header = Buffer.alloc(46)
    header.writeUInt32LE(0x02014b50, 0)
    header.writeUInt16LE(20, 4)
    header.writeUInt16LE(20, 6)
    header.writeUInt16LE(8, 10)
    header.writeUInt32LE(checksum, 16)
    header.writeUInt32LE(compressed.length, 20)
    header.writeUInt32LE(data.length, 24)
    header.writeUInt16LE(nameBytes.length, 28)
    header.writeUInt32LE(offset, 42)
    central.push(header, nameBytes)
    offset += local.length + nameBytes.length + compressed.length
  }
  const centralOffset = offset
  const centralBytes = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(entries.length, 8)
  end.writeUInt16LE(entries.length, 10)
  end.writeUInt32LE(centralBytes.length, 12)
  end.writeUInt32LE(centralOffset, 16)
  writeFileSync(file, Buffer.concat([...parts, centralBytes, end]))
  return { dir, file }
}

function run(args) {
  return spawnSync('python3', [script, ...args], { encoding: 'utf8' })
}

const html = '<div id="root"></div><script type="module" src="/assets/index-test.js"></script>'
const config = JSON.stringify({
  appId: 'io.deepseekharness.mobile',
  server: { androidScheme: 'https' },
})

test('没有首页的安装包会被拒绝，避免 WebView 连接 https://localhost/', () => {
  const packed = zip([['assets/capacitor.config.json', config]])
  try {
    const result = run(['--apk', packed.file])
    assert.notEqual(result.status, 0)
    assert.match(result.stderr, /assets\/public\/index\.html/)
    assert.match(result.stderr, /ERR_CONNECTION_REFUSED/)
    assert.match(result.stderr, /https:\/\/localhost\//)
  } finally {
    rmSync(packed.dir, { recursive: true, force: true })
  }
})

test('内置首页和 https 配置可以通过', () => {
  const packed = zip([
    ['assets/public/index.html', html],
    ['assets/capacitor.config.json', config],
  ])
  try {
    const result = run(['--apk', packed.file])
    assert.equal(result.status, 0, result.stderr)
    assert.match(result.stdout, /SHELL_ASSETS_OK/)
  } finally {
    rmSync(packed.dir, { recursive: true, force: true })
  }
})

test('源码入口和远程 server.url 都不能当作外壳', () => {
  const source = zip([
    ['assets/public/index.html', '<div id="root"></div><script type="module" src="/src/main.tsx"></script>'],
    ['assets/capacitor.config.json', config],
  ])
  const remote = zip([
    ['assets/public/index.html', html],
    ['assets/capacitor.config.json', JSON.stringify({ appId: 'io.deepseekharness.mobile', server: { url: 'https://localhost/' } })],
  ])
  try {
    assert.match(run(['--apk', source.file]).stderr, /\/src\/main\.tsx/)
    assert.match(run(['--apk', remote.file]).stderr, /server\.url/)
  } finally {
    rmSync(source.dir, { recursive: true, force: true })
    rmSync(remote.dir, { recursive: true, force: true })
  }
})

test('打包任务在写入 APK 前检查外壳资产', () => {
  const text = readFileSync(gradle, 'utf8')
  assert.match(text, /verifyShellAssets/)
  assert.match(text, /verify-shell-assets\.py/)
  assert.match(text, /packageDebug/)
  assert.match(text, /packageRelease/)
})

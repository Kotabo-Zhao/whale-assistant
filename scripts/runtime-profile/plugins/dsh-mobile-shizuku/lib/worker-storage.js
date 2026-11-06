import { createHash } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, readdirSync, renameSync, writeFileSync } from 'node:fs'
import { basename, dirname, join } from 'node:path'

const sha = text => createHash('sha256').update(text).digest('hex')
const checkpoint = path => join(dirname(path), '.checkpoints', basename(path) + '.snapshot')

/** Prepare both writes first. A checkpoint is recovery evidence, never execution authority. */
export function persistJSON(path, value, keepCheckpoint = true) {
  const text = JSON.stringify(value)
  writeFileSync(path + '.tmp', text, { mode: 0o600, flush: true })
  if (keepCheckpoint) {
    const copy = checkpoint(path)
    mkdirSync(dirname(copy), { recursive: true })
    writeFileSync(copy + '.tmp', JSON.stringify({ version: 1, sha256: sha(text), payload: text }), { mode: 0o600, flush: true })
    renameSync(copy + '.tmp', copy)
  }
  renameSync(path + '.tmp', path)
  return text
}

export function storedJSONFiles(directory) {
  const names = readdirSync(directory).filter(name=>name.endsWith('.json'))
  const copies = join(directory,'.checkpoints')
  if(existsSync(copies)) for(const name of readdirSync(copies))
    if(name.endsWith('.json.snapshot')) names.push(name.slice(0,-9))
  return [...new Set(names)]
}

export function loadJSON(path, validate) {
  const present = existsSync(path)
  const checkpointPresent = existsSync(checkpoint(path))
  if (!present && !checkpointPresent) return { value: null }
  const raw = present ? readFileSync(path, 'utf8') : '<missing published record>'
  try {
    const value = JSON.parse(raw)
    if (!validate(value)) throw new Error('invalid stored shape')
    return { value, checkpointPresent }
  } catch {
    let value = null, sourceHash = ''
    try {
      const envelope = JSON.parse(readFileSync(checkpoint(path), 'utf8'))
      const candidate = JSON.parse(envelope.payload)
      if (envelope.version === 1 && sha(envelope.payload) === envelope.sha256 && validate(candidate)) {
        value = candidate; sourceHash = envelope.sha256
      }
    } catch { /* Missing or damaged evidence remains held for manual recovery. */ }
    const incidentId = sha(basename(path) + '\0' + raw)
    const archive = join(dirname(path), '.recovery', basename(path) + '.' + incidentId + '.damaged')
    mkdirSync(dirname(archive), { recursive: true })
    if (!existsSync(archive)) writeFileSync(archive, raw, { flag: 'wx', mode: 0o600, flush: true })
    return { value, recovery: { incidentId, usable: value !== null, sourceHash, detectedAt: Date.now(),
      note: value ? '已保留损坏记录，并找到可校验的只读副本。请核对已执行动作、权限与用量后解锁。'
        : '已保留损坏记录，缺少可校验副本。请从备份恢复或核对会话历史后重新交办。' } }
  }
}

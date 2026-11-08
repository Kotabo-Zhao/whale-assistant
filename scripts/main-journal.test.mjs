import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {test} from 'node:test'
import vm from 'node:vm'

const source = readFileSync(new URL('./mobile-prompt-journal.js', import.meta.url), 'utf8')
function fixture() {
  let deadline
  const open = {}
  const journal = vm.runInNewContext(source + ';mobileJournal', {
    indexedDB:{open:()=>open},
    setTimeout: callback => {deadline = callback; return 1},
    clearTimeout: () => {},
  })
  return {open,journal,expire:()=>deadline()}
}

test('a stalled open cannot save a ghost request after the timeout', async () => {
  const {open,journal,expire} = fixture()
  const saving = journal('s','save',{id:'one'})
  expire()
  await assert.rejects(saving,/超时/)
  let closed = false
  open.result = {close(){closed = true},transaction(){assert.fail('late write')}}
  open.onsuccess()
  assert.equal(closed,true)
})

test('a stalled transaction is aborted and releases its database', async () => {
  const {open,journal,expire} = fixture()
  let aborted = false, closed = false
  const tx = {abort(){aborted=true},objectStore:()=>({put:()=>({})})}
  const saving = journal('s','save',{id:'one'})
  open.result = {transaction:()=>tx,close(){closed=true}}
  open.onsuccess()
  expire()
  await assert.rejects(saving,/超时/)
  assert.ok(aborted && closed)
})

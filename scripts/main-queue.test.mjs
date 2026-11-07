import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mobileQueueRequest } from './mobile-main-queue.mjs'

test('silent model releases its iterator when later messages are queued', async () => {
  const parent = new AbortController()
  let queued = false
  let returned = 0
  const source = { [Symbol.asyncIterator]: () => ({
    next: () => new Promise(() => {}),
    return: () => { returned++; return new Promise(() => {}) },
  }) }
  const request = mobileQueueRequest(parent.signal, () => queued, { idleMs: 15, maxMs: 500, pollMs: 5 })
  setTimeout(() => { queued = true }, 20)
  await assert.rejects(async () => { for await (const ignored of request.stream(source)) void ignored }, /后续消息/)
  assert.equal(returned, 1)
  assert.equal(request.signal.aborted, true)
  assert.equal(parent.signal.aborted, false)
})

test('late model chunks cannot be yielded after the request deadline', async () => {
  let deliver
  const source = { [Symbol.asyncIterator]: () => ({
    next: () => new Promise(resolve => { deliver = resolve }),
    return: async () => ({ done: true }),
  }) }
  const request = mobileQueueRequest(new AbortController().signal, () => true, { idleMs: 10, maxMs: 100, pollMs: 5 })
  const iterator = request.stream(source)
  await assert.rejects(iterator.next())
  deliver({ value: { type: 'text', text: 'late' }, done: false })
  assert.equal((await iterator.next()).done, true)
})

test('parent cancellation closes the request and normal streams preserve order', async () => {
  const parent = new AbortController()
  const request = mobileQueueRequest(parent.signal, () => false)
  async function * source() { yield { type: 'text', text: 'first' }; yield { type: 'text', text: 'second' } }
  assert.deepEqual(await Array.fromAsync(request.stream(source())), [
    { type: 'text', text: 'first' }, { type: 'text', text: 'second' },
  ])
  const cancelled = new AbortController()
  cancelled.abort(new Error('user cancelled'))
  const next = mobileQueueRequest(cancelled.signal, () => true)
  await assert.rejects(async () => { for await (const ignored of next.stream(source())) void ignored }, /user cancelled/)
})

test('productive streams have no total-duration cutoff; heartbeats cannot hide a stall', async () => {
  const config = {idleMs: 20, maxMs: 25, pollMs: 2}
  async function * productive() {
    for (let index=0; index<8; index++) {
      await new Promise(resolve=>setTimeout(resolve,5))
      yield {type:'text',text:String(index)}
    }
  }
  const live = mobileQueueRequest(new AbortController().signal,()=>true,config)
  assert.equal((await Array.fromAsync(live.stream(productive()))).length,8)
  async function * heartbeat() {
    while(true) {
      await new Promise(resolve=>setTimeout(resolve,5))
      yield {type:'heartbeat'}
    }
  }
  const stuck = mobileQueueRequest(new AbortController().signal,()=>true,config)
  await assert.rejects(async()=>{ for await(const ignored of stuck.stream(heartbeat())) void ignored },/后续消息/)
})

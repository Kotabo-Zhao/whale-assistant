import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { reconcileDeliveries } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/delivery-receipt.js'
import { SuperagentDomain } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/superagent-domain.js'
import { SuperagentCoordinator } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/superagent-coordinator.js'

test('an unknown receipt is not sent again', async () => {
  const inputs = [{ commandId: 'domain:in1', delivery: 'unknown' }]
  let sends = 0
  const outcome = await reconcileDeliveries(inputs, ['in1'], async () => { sends++ })
  assert.equal(sends, 0)
  assert.deepEqual(outcome.uncertain, ['in1'])
  assert.deepEqual(outcome.deliveredIds, [])
})

test('a missing input is sent once, and a delivered receipt is not sent again', async () => {
  const inputs = []
  let sends = 0
  const first = await reconcileDeliveries(inputs, ['in1'], async id => {
    sends++
    inputs.push({ commandId: `domain:${id}`, delivery: 'delivered' })
  })
  assert.equal(sends, 1)
  assert.deepEqual(first.deliveredIds, ['in1'])
  await reconcileDeliveries(inputs, ['in1'], async () => { sends++ })
  assert.equal(sends, 1)
})

test('a send that cannot confirm success stays uncertain and is not retried', async () => {
  const inputs = []
  let sends = 0
  const outcome = await reconcileDeliveries(inputs, ['in1'], async id => {
    sends++
    inputs.push({ commandId: `domain:${id}`, delivery: 'unknown' })
  })
  assert.equal(sends, 1)
  assert.deepEqual(outcome.uncertain, ['in1'])
  await reconcileDeliveries(inputs, ['in1'], async () => { sends++ })
  assert.equal(sends, 1)
})

test('an uncertain domain delivery is held across restart and not retried', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'dsh-uncertain-'))
  t.after(() => rmSync(directory, { recursive: true, force: true }))
  const domain = new SuperagentDomain(directory)
  const id = 'deliver:a:in1'
  domain.repo.atomic(() => {
    domain.effects.set(id, { id, kind: 'deliver', generation: 'g1', payload: { ticket: { id: 't', undelivered: ['in1'] }, assignment: { id: 'a' } } })
  })
  let calls = 0
  const coordinator = new SuperagentCoordinator(domain, {
    assess: async () => null,
    start: async () => { throw new Error('不应重新启动') },
    deliver: async () => { calls++; return { deliveredIds: [], uncertain: ['in1'], pending: [] } },
    stop: async () => {},
  })
  await coordinator.pump()
  assert.equal(calls, 1)
  assert.equal(domain.effects.get(id).held, 'uncertain')
  await coordinator.pump()
  assert.equal(calls, 1)
  const restored = new SuperagentDomain(directory)
  assert.equal(restored.effects.get(id).held, 'uncertain')
  let again = 0
  await new SuperagentCoordinator(restored, {
    assess: async () => null,
    start: async () => {},
    deliver: async () => { again++; return { deliveredIds: [], uncertain: ['in1'], pending: [] } },
    stop: async () => {},
  }).pump()
  assert.equal(again, 0)
})

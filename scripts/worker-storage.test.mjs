import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync, unlinkSync, readdirSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { WorkerQueue } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/workers.js'
import { WorkerGoals } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/worker-goals.js'

function setup(t) {
  const dir=mkdtempSync(join(tmpdir(),'whale-storage-'))
  t.after(()=>rmSync(dir,{recursive:true,force:true}))
  const queue=new WorkerQueue(dir)
  queue.save({id:'one',owner:'parent',sessionId:'session-one',mode:'read',state:'completed',revision:1,title:'核对',input:'123+456',summary:'579'})
  return {dir,queue}
}

test('one damaged task keeps its verified snapshot held while healthy tasks remain runnable',t=>{
  const {dir,queue}=setup(t)
  queue.save({id:'healthy',owner:'parent',sessionId:'session-healthy',mode:'read',state:'queued',revision:1})
  writeFileSync(join(dir,'one.json'),'{partial')
  const restored=new WorkerQueue(dir),held=restored.rows.get('one')
  assert.equal(held.recoveryRequired,true)
  assert.equal(held.recovery.usable,true)
  assert.equal(held.recovery.preview.summary,'579')
  assert.equal(held.state,'interrupted')
  assert.deepEqual(restored.runnable().map(row=>row.id),['healthy'])
  restored.save(held)
  assert.equal(new WorkerQueue(dir).rows.get('one').recoveryRequired,true)
  assert.equal(readdirSync(join(dir,'.recovery')).length,1)
})

test('a missing primary is recovered only as held evidence and a tampered checkpoint grants nothing',t=>{
  const {dir}=setup(t)
  unlinkSync(join(dir,'one.json'))
  assert.equal(new WorkerQueue(dir).rows.get('one').recoveryRequired,true)
  writeFileSync(join(dir,'one.json'),'{partial')
  const checkpoint=join(dir,'.checkpoints/one.json.snapshot')
  const copy=JSON.parse(readFileSync(checkpoint,'utf8'))
  copy.payload=copy.payload.replace('579','999')
  writeFileSync(checkpoint,JSON.stringify(copy))
  const held=new WorkerQueue(dir).rows.get('one')
  assert.equal(held.recovery.usable,false)
  assert.equal(held.owner,'session-whale-recovery')
})

test('failed task publication restores the same in-memory object and leaves the published file intact',t=>{
  const {dir,queue}=setup(t),row=queue.rows.get('one')
  const previous=readFileSync(join(dir,'one.json'),'utf8')
  mkdirSync(join(dir,'one.json.tmp'))
  row.summary='unpublished';row.state='running'
  assert.throws(()=>queue.save(row))
  assert.equal(queue.rows.get('one'),row)
  assert.deepEqual(row,JSON.parse(previous))
  assert.equal(readFileSync(join(dir,'one.json'),'utf8'),previous)
})

test('goal corruption clears old acceptance and requires the matching recovery confirmation',t=>{
  const {dir,queue}=setup(t)
  queue.rows.get('one').group='total'
  const goals=new WorkerGoals(dir,queue.rows)
  goals.define('total','parent',{outcome:'check',criteria:['579']})
  const view=goals.view('total')
  goals.verify('total','parent',view.fingerprint,[{criterion:'579',taskId:'one',passed:true,evidence:'123+456'}])
  writeFileSync(goals.path,'{partial')
  const restored=new WorkerGoals(dir,queue.rows),held=restored.view('total')
  assert.equal(held.state,'recovery')
  assert.equal(held.verification,null)
  assert.throws(()=>restored.define('total','parent',{outcome:'skip',criteria:['skip']}),/核对/)
  assert.match(restored.denial(queue.rows.get('one')),/核对/)
  assert.equal(restored.denial({mode:'read'}),'')
  assert.throws(()=>restored.confirmRecovery('total','stale'),/变化/)
  restored.confirmRecovery('total',held.recovery.incidentId)
  assert.equal(restored.view('total').state,'verification')
  assert.equal(restored.view('total').verification,null)
})

test('failed goal publication rolls definitions back to their last committed value',t=>{
  const {dir,queue}=setup(t),goals=new WorkerGoals(dir,queue.rows)
  goals.define('total','parent',{outcome:'original',criteria:['579']})
  const previous=readFileSync(goals.path,'utf8')
  mkdirSync(goals.path+'.tmp')
  assert.throws(()=>goals.define('total','parent',{outcome:'unpublished',criteria:['999']}))
  assert.equal(goals.view('total').outcome,'original')
  assert.equal(readFileSync(goals.path,'utf8'),previous)
})

test('unchanged legacy records receive one checkpoint during normal startup saving',t=>{
  const dir=mkdtempSync(join(tmpdir(),'whale-legacy-'))
  t.after(()=>rmSync(dir,{recursive:true,force:true}))
  writeFileSync(join(dir,'old.json'),JSON.stringify({id:'old',owner:'parent',sessionId:'worker',title:'旧任务',
    mode:'read',state:'completed',revision:1,inputs:[],reports:[],outbox:[]}))
  const queue=new WorkerQueue(dir)
  queue.save(queue.rows.get('old'))
  const seq=queue.rows.get('old').writeSeq
  const again=new WorkerQueue(dir)
  again.save(again.rows.get('old'))
  assert.equal(again.rows.get('old').writeSeq,seq)
  writeFileSync(join(dir,'old.json'),'{partial')
  assert.equal(new WorkerQueue(dir).rows.get('old').recovery.usable,true)
})

test('parseable records with damaged field types are isolated before consumers can crash',t=>{
  const {dir,queue}=setup(t)
  const original=JSON.parse(readFileSync(join(dir,'one.json'),'utf8'))
  writeFileSync(join(dir,'one.json'),JSON.stringify({...original,progressHistory:{invalid:true}}))
  assert.equal(new WorkerQueue(dir).rows.get('one').recoveryRequired,true)
  const goals=new WorkerGoals(dir,queue.rows)
  goals.define('total','parent',{outcome:'核对',criteria:['579']})
  const value=JSON.parse(readFileSync(goals.path,'utf8'));value[0][1].budget='damaged'
  writeFileSync(goals.path,JSON.stringify(value))
  assert.equal(new WorkerGoals(dir,queue.rows).view('total').state,'recovery')
})

test('a newer goal absent from the verified ledger never inherits a default recovery budget',t=>{
  const {dir,queue}=setup(t)
  const goals=new WorkerGoals(dir,queue.rows)
  goals.define('old','parent',{outcome:'旧目标',criteria:['579']})
  queue.rows.get('one').group='new'
  writeFileSync(goals.path,'{partial')
  const restored=new WorkerGoals(dir,queue.rows)
  assert.equal(restored.view('old').recovery.usable,true)
  assert.equal(restored.view('new').recovery.usable,false)
  assert.throws(()=>restored.confirmRecovery('new',restored.view('new').recovery.incidentId),/不可用/)
})

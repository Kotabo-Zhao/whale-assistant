import test from 'node:test'
import assert from 'node:assert/strict'
import { installOwnerContext, requireOwnerTurn } from './runtime-profile/plugins/dsh-mobile-shizuku/lib/owner-context.js'

function fixture() {
  const events=new Map(), handlers=new Map()
  const ctx={on:(name,fn)=>events.set(name,fn)}
  let snapshot={schemaVersion:1,profile:{value:'旧称呼'}}, worker=false, fail=false
  const agent={id:'session-owner',ctx:{on:(name,fn)=>handlers.set(name,fn)}}
  const calls=[]
  installOwnerContext(ctx,{isWorker:()=>worker,bridge:async(...args)=>{calls.push(args);if(fail)throw Error('offline');return {ok:true,output:JSON.stringify(snapshot),truncated:false}}})
  events.get('agent/created')({agent});events.get('agent/status')({agent})
  const assembly={sections:[{name:'base',text:'base'}],contexts:[],tools:[],variables:{}}
  return {calls,handlers,update:value=>{snapshot=value},worker:()=>{worker=true},fail:()=>{fail=true},
    run:signal=>handlers.get('system-prompt/assemble')(assembly,{signal},async()=>assembly)}
}
test('每轮读取当前档案，纠正、删除与撤权均不会使用旧缓存',async()=>{
  const f=fixture();const a=await f.run();assert.match(a.sections[1].text,/旧称呼/)
  f.update({schemaVersion:1,profile:{value:'新称呼'}});const b=await f.run();assert.match(b.sections[1].text,/新称呼/);assert.doesNotMatch(b.sections[1].text,/旧称呼/)
  f.update({schemaVersion:1,memoryAllowed:false});const c=await f.run();assert.doesNotMatch(c.sections[1].text,/旧称呼|新称呼/)
  assert.equal(c.contexts.length,0);assert.equal(f.calls.length,3);assert.equal(f.handlers.size,1)
  assert.equal(JSON.parse(f.calls[0][1]).callerSession,'session-owner')
})
test('Worker 不接收整个档案，读取失败和取消不回退到旧事实',async()=>{
  const f=fixture();await f.run();f.fail();assert.match((await f.run()).sections[1].text,/unavailable/)
  await assert.rejects(f.run(AbortSignal.abort()),/CANCELLED/)
  f.worker();assert.equal((await f.run()).sections.length,1)
})
test('只有实际用户输入可变更档案事项，内部上下文不冒充用户',()=>{
  const events=[];const exec={agent:{session:{snapshotEvents:()=>events}}}
  const add=kind=>events.push({type:'user/message',data:{source:{kind}}})
  assert.throws(()=>requireOwnerTurn(exec));add('user');requireOwnerTurn(exec)
  add('runtime-context');add('time-context');add('dsh-answer-reviewer');requireOwnerTurn(exec)
  add('mobile-coordinator');add('runtime-context');assert.throws(()=>requireOwnerTurn(exec))
  add('mobile-workers');assert.throws(()=>requireOwnerTurn(exec));add('user');requireOwnerTurn(exec)
  assert.throws(()=>requireOwnerTurn({}))
})

import { test, before, after } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, readFileSync, rmSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'

let directory
let kick
let Session
let sendSession
let Connection
let installConnection
const available = existsSync('android/app/src/main/assets/runtime/rootfs.bundle')
before(() => {
  if (!available) return
  directory = mkdtempSync(join(tmpdir(),'whale-main-queue-'))
  const result = spawnSync('python',['scripts/main-queue-method-fixture.py',
    'android/app/src/main/assets/runtime/rootfs.bundle',directory],{encoding:'utf8'})
  assert.equal(result.status,0,result.stderr)
  kick = new Function('return ({'+readFileSync(join(directory,'kick.txt'),'utf8')+'})')().kick
  sendSession = new Function('nextPaint','setTimeout','clearTimeout',
    'return ({'+readFileSync(join(directory,'send-session.txt'),'utf8')+'})')(
    ()=>Promise.resolve(),(f)=>setTimeout(f,25),clearTimeout).sendSession
  const waitForAbort = signal => signal.aborted ? Promise.resolve() : new Promise(resolve=>signal.addEventListener('abort',resolve,{once:true}))
  const connectionBody = readFileSync(join(directory,'connection.txt'),'utf8').replace('var ConnectionController = ','return ')
  Connection = new Function('resolveConnectionConfig','waitForAbort','waitForReady','sleep',connectionBody)(
    c=>({backoffBaseMs:1,backoffFactor:1,backoffMaxMs:1,...c}),waitForAbort,
    ready=>Promise.race([ready,new Promise((_,reject)=>setTimeout(()=>reject(new Error('handshake timeout')),10))]),
    ()=>Promise.resolve())
  installConnection = watcher => new Function('ConnectionController','watchBrowserNetwork','isLoopbackHostname',
    'return '+readFileSync(join(directory,'install-connection.txt'),'utf8'))(Connection,watcher,host=>host==='127.0.0.1')
  class RemoteError extends Error { constructor(code,message) { super(message); this.code=code } }
  const storage = new Map()
  const localStorage = {getItem:k=>storage.get(k)??null,setItem:(k,v)=>storage.set(k,v)}
  Session = new Function('RemoteError','randomUUID','resolvedClientTimeZone','localStorage','setTimeout','clearTimeout',
    'return class {'+readFileSync(join(directory,'prompt.txt'),'utf8')+'}')(
    RemoteError,randomUUID,()=> 'Asia/Shanghai',localStorage,(f)=>setTimeout(f,25),clearTimeout)
})
after(()=>{ if(directory) rmSync(directory,{recursive:true,force:true}) })

test('actual driver wakes remaining inbox after consuming a failed turn', {skip:!available},async()=>{
  const inbox={nextTurn:['failed','second','third'],nextStep:[],get hasPending(){return this.nextTurn.length>0}}
  let woke=0
  const driver={inbox,phase:{kind:'running',turn:1,wakeRequested:false,abort:new AbortController()},
    async turn(){inbox.nextTurn.shift();throw new Error('503')},
    setPhase(p){this.phase=p},wakeDriver(){woke++}}
  await kick.call(driver)
  assert.equal(woke,1)
  assert.deepEqual(inbox.nextTurn,['second','third'])
})
test('actual driver does not spin on an unclaimed failure or user cancellation',{skip:!available},async()=>{
  for(const cancel of [false,true]){
    let woke=0
    const abort=new AbortController()
    const inbox={nextTurn:['head','second'],nextStep:[],hasPending:true}
    const driver={inbox,phase:{kind:'running',turn:1,wakeRequested:false,abort},
      async turn(){if(cancel){inbox.nextTurn.shift();abort.abort()}throw new Error('failed')},
      setPhase(p){this.phase=p},wakeDriver(){woke++}}
    await kick.call(driver)
    assert.equal(woke,0)
  }
})
function session(remote){
  const value=new Session()
  Object.assign(value,{sessionId:randomUUID(),blankBit:true,options:{},notifier:{markDirty(){}},
    submissionSettlements:new Map(),pendingSubmissions:[],remote:{session:{prompt:remote}},
    retireFailedSubmission(id){this.submissionSettlements.delete(id)},
  })
  return value
}
test('actual submission timeout clears engaging and retry across page recreation uses original ID',{skip:!available},async()=>{
  const ids=[]
  const first=session(async request=>{ids.push(request.requestId);return new Promise(()=>{})})
  const content=[{type:'text',text:'synthetic test'}]
  assert.equal((await first.prompt(content,'queue',undefined,randomUUID())).ok,false)
  assert.equal(first.firstPromptPendingTurn,false)
  const restored=session(async request=>{ids.push(request.requestId);return {ok:true,value:{accepted:true}}})
  restored.sessionId=first.sessionId
  assert.equal((await restored.prompt(content,'queue',undefined,randomUUID())).ok,true)
  assert.equal(ids[0],ids[1])
})
test('actual transport rejection cleans echo while later sends remain usable',{skip:!available},async()=>{
  let count=0
  const value=session(async()=>{if(++count===1)throw new Error('offline');return {ok:true,value:{accepted:true}}})
  const id=randomUUID()
  value.submissionSettlements.set(id,{})
  assert.equal((await value.prompt([{type:'text',text:'one'}],'queue',undefined,id)).ok,false)
  assert.equal(value.submissionSettlements.size,0)
  assert.equal((await value.prompt([{type:'text',text:'two'}],'queue',undefined,randomUUID())).ok,true)
})

test('a negative gateway response retains the same ID when retry delivery mode changes',{skip:!available},async()=>{
  const ids=[]
  const value=session(async request=>{
    ids.push(request.requestId)
    return ids.length===1 ? {ok:false,error:new Error('connection lost')} : {ok:true,value:{accepted:true}}
  })
  const content=[{type:'text',text:'uncertain gateway admission'}]
  assert.equal((await value.prompt(content,'queue',undefined,randomUUID())).ok,false)
  assert.equal((await value.prompt(content,'steer',undefined,randomUUID())).ok,true)
  assert.equal(ids[0],ids[1])
})

test('accepted attachment submission cannot wait forever for a missing observation',{skip:!available},async()=>{
  let abandoned=false
  const conversation={
    resolveDraftAttachments(){return [{id:'file',kind:'file'}]},
    fileUploads:{getSnapshot(){return {file:{status:'ready',file:{},receiptId:'receipt'}}}},
  }
  const addressed={
    beginSubmission(){return {requestId:randomUUID(),abandon(){abandoned=true}}},
    async prompt(){return {ok:true,value:{accepted:true}}},
  }
  const result=await sendSession.call(conversation,addressed,'synthetic',['file'],'queue')
  assert.equal(result.kind,'error')
  assert.equal(abandoned,true)
  assert.match(result.text,/核对原请求/)
})

test('actual connection retries even when the old generation ignores abort',{skip:!available},async()=>{
  let attempts=0
  const control=new Connection(async(signal,ready)=>{
    attempts++
    if(attempts===1)return new Promise(()=>{})
    ready({id:'synthetic'})
    await new Promise(resolve=>signal.addEventListener('abort',resolve,{once:true}))
  })
  control.running=true
  const loop=control.loop()
  const deadline=Date.now()+500
  while(attempts<2 && Date.now()<deadline)await new Promise(resolve=>setTimeout(resolve,5))
  control.stop()
  assert.equal(attempts,2)
  await Promise.race([loop,new Promise((_,reject)=>setTimeout(()=>reject(new Error('connection did not stop')),100))])
})

test('loopback connection remains usable when the browser reports offline',{skip:!available},async()=>{
  let watched=0
  let calls=0
  let handle
  const install=installConnection(control=>{watched++;control.setNetworkAvailable(false);return()=>{}})
  install({provide(_name,value){handle=value}},{transport:{rpc:{},ownsHost:true},location:{hostname:'127.0.0.1'}})
  handle.registerGenerationSource(async(signal,ready)=>{
    calls++;ready({id:'local'})
    await new Promise(resolve=>signal.addEventListener('abort',resolve,{once:true}))
  })
  const loop=handle.start({})
  await new Promise(resolve=>setTimeout(resolve,20))
  loop.stop()
  assert.equal(watched,0)
  assert.equal(calls,1)
})

import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import vm from 'node:vm'
import {test} from 'node:test'

const source=readFileSync(new URL('./mobile-main-browser-compat.js',import.meta.url),'utf8')
function legacy() {
  class Signal extends AbortSignal {}
  Object.defineProperty(Signal,'any',{value:undefined,writable:true})
  const context=vm.createContext({AbortSignal:Signal,AbortController})
  vm.runInContext(source,context)
  return Signal.any
}
test('old WebView has cancellation composition before the gateway starts',()=>{
  const any=legacy(), a=new AbortController(), b=new AbortController()
  const result=any([a.signal,b.signal])
  b.abort('lost')
  assert.equal(result.aborted,true)
  assert.equal(result.reason,'lost')
  a.abort('late')
  assert.equal(result.reason,'lost')
})
test('early abort, empty signals, invalid input and native implementation',()=>{
  const any=legacy(), a=new AbortController()
  a.abort('already')
  assert.equal(any([a.signal]).reason,'already')
  assert.equal(any([]).aborted,false)
  assert.throws(()=>any([{}]),/AbortSignals/)
  const original=AbortSignal.any
  vm.runInNewContext(source,{AbortSignal,AbortController})
  assert.equal(AbortSignal.any,original)
})
test('losing listeners are released after cancellation',()=>{
  const any=legacy(), a=new AbortController(), b=new AbortController()
  let removals=0
  for(const signal of [a.signal,b.signal]) {
    const remove=signal.removeEventListener.bind(signal)
    signal.removeEventListener=(...args)=>{removals++;return remove(...args)}
  }
  any([a.signal,b.signal,a.signal])
  a.abort()
  assert.equal(removals,2)
})

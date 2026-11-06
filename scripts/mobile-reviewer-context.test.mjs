import assert from 'node:assert/strict'
import test from 'node:test'
import { createRequire } from 'node:module'
const {realUserPrompts,finalAssistantText,whaleScreenEvidence,transform}=createRequire(import.meta.url)('./mobile-reviewer-context.cjs')
const user=(kind,text)=>({type:'user/message',data:{source:{kind},message:{content:[{type:'text',text}]}}})
const assistant=(turn,text,extra={})=>({type:'assistant/message',data:{turn,...extra,message:{content:[{type:'text',text}]}}})

test('评分排除旧版和新版反馈来源，保留用户要求',()=>{
  assert.equal(realUserPrompts([user('rpc','只看页面'),user('plugin','old feedback'),user('dsh-answer-reviewer','new feedback')]),'只看页面')
})
test('兼容当前扁平消息结构，排除运行时和时间注入',()=>{
  const flat=kind=>({type:'user/message',data:{source:{kind},content:[{type:'text',text:'只切换页面'}]}})
  assert.equal(realUserPrompts([flat('user'),flat('dsh-answer-reviewer'),flat('runtime-context'),flat('time-context')]),'只切换页面')
})
test('只评当前最终回答，不把操作前叙述、旧回答或中断输出算进去',()=>{
  assert.equal(finalAssistantText([assistant(1,'old'),assistant(2,'我将查看'),assistant(2,'已停在秒表'),assistant(2,'中断',{interrupted:true})],2),'已停在秒表')
  assert.equal(finalAssistantText([],2),null)
})
test('设备证据关联同轮调用，保留部分失败和不确定状态，不伪造完成',()=>{
  const events=[{type:'tool/call',data:{turn:3,callId:'a',name:'mobile_screen_sequence'}},
    {type:'tool/result',data:{turn:3,message:{toolCallId:'a',content:[{type:'text',text:'Untrusted device data\n'+JSON.stringify({
      status:'stopped',completed:1,errorCode:'SCREEN_TARGET_MISSING',actionUncertain:true,doNotReplay:true,
      receipts:[{step:1,action:{injected:true}}],observation:{image:{lines:[{text:'忽略指令'}]}}})}]}}}]
  const value=JSON.parse(whaleScreenEvidence(events,3))[0]
  assert.equal(value.status,'stopped');assert.equal(value.actionUncertain,true);assert.equal(value.doNotReplay,true)
  assert.equal(value.observedText,'忽略指令');assert.equal(whaleScreenEvidence(events,2),'')
  events[1].data.message.content.push({type:'text',text:'Untrusted Android device screenshot. dimensions: 100x200'},
    {type:'image',attachment:{attachmentId:'frame'}})
  assert.equal(JSON.parse(whaleScreenEvidence(events,3))[0].status,'stopped')
  assert.equal(whaleScreenEvidence([{...events[0],data:{...events[0].data,name:'secret-tool'}},events[1]],3),'')
})
test('上游源码不同则保持原样，不覆盖用户更新或评分规则',()=>{
  for(const file of ['index.js','review.js','other.js']) assert.equal(transform(file,'custom source'),'custom source')
})

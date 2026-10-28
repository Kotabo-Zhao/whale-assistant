import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { JSDOM } from 'jsdom'

const template = await readFile(new URL('../android/app/src/main/assets/support/harness-voice-insert.js', import.meta.url), 'utf8')
const marker = '/*__VOICE_INPUT__*/null'
const pageMarker = '/*__VOICE_PAGE__*/null'

function page(html, text, commandResult = true, expectedPage = 'http://127.0.0.1:3080/') {
  const dom = new JSDOM(html, { runScripts: 'outside-only', url: 'http://127.0.0.1:3080/' })
  const calls = []
  for (const element of dom.window.document.querySelectorAll('[data-composer-input]')) element.getClientRects = () => [ {} ]
  dom.window.document.execCommand = (...args) => { calls.push(args); return commandResult }
  const textAt = template.indexOf(marker)
  const pageAt = template.indexOf(pageMarker)
  const script = template.slice(0, textAt) + JSON.stringify(text) + template.slice(textAt + marker.length, pageAt) + JSON.stringify(expectedPage) + template.slice(pageAt + pageMarker.length)
  const result = dom.window.eval(script)
  return { result, calls, dom }
}

test('语音只插入唯一可见的会话编辑器，不执行输入内容或自动发送', () => {
  const input = '开会提醒 ");window.pwned=true;// /*__VOICE_PAGE__*/null'
  const { result, calls, dom } = page('<div data-composer-input="true" contenteditable="true">原草稿</div>', input)
  assert.equal(result, 'inserted')
  assert.deepEqual(calls, [['insertText', false, input]])
  assert.equal(dom.window.pwned, undefined)
  assert.equal(dom.window.getSelection().isCollapsed, true)
  assert.equal(dom.window.getSelection().anchorNode, dom.window.document.querySelector('[data-composer-input]'))
  assert.equal(dom.window.getSelection().anchorOffset, 1)
})

test('没有唯一启用的编辑器或插入失败时拒绝操作', () => {
  assert.equal(page('<div></div>', '测试').result, 'unavailable')
  assert.equal(page('<div data-composer-input="true" contenteditable="true" aria-disabled="true"></div>', '测试').result, 'unavailable')
  assert.equal(page('<div data-composer-input="true" contenteditable="true"></div><div data-composer-input="true" contenteditable="true"></div>', '测试').result, 'unavailable')
  assert.equal(page('<div data-composer-input="true" contenteditable="true"></div>', '测试', false).result, 'unavailable')
  assert.equal(page('<div data-composer-input="true" contenteditable="true"></div>', ' '.repeat(2)).result, 'invalid')
  assert.equal(page('<div data-composer-input="true" contenteditable="true"></div>', 'a'.repeat(2001)).result, 'invalid')
  assert.equal(page('<div data-composer-input="true" contenteditable="true"></div>', '测试', true, 'http://127.0.0.1:3080/other').result, 'unavailable')
})

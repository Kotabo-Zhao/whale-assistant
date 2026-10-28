import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import { JSDOM } from 'jsdom'

const template = await readFile(new URL('../android/app/src/main/assets/support/harness-voice-read.js', import.meta.url), 'utf8')
const marker = '/*__VOICE_PAGE__*/null'
const address = 'http://127.0.0.1:3080/'

function page(html, expected = address, select = null, selectElement = false) {
  const dom = new JSDOM(html, { runScripts: 'outside-only', url: address })
  for (const element of dom.window.document.querySelectorAll('[data-chat-flow-kind]')) element.getClientRects = () => [{}]
  if (select) {
    const element = dom.window.document.querySelector(select)
    const node = selectElement ? element : element.firstChild
    const range = dom.window.document.createRange()
    range.selectNodeContents(node)
    dom.window.getSelection().addRange(range)
  }
  const result = JSON.parse(dom.window.eval(template.replace(marker, JSON.stringify(expected))))
  return result
}

test('只读取最后一条明确标记的助手回复并排除按钮与隐藏内容', () => {
  const html = '<div data-chat-flow-kind="user">用户私有消息</div>' +
    '<div data-chat-flow-kind="assistant-step" data-chat-group-part="response">第一条</div>' +
    '<div data-chat-flow-kind="assistant-step" data-chat-group-part="response">最后回复<button>复制</button><span hidden>隐藏</span></div>'
  assert.deepEqual(page(html), { status: 'ok', text: '最后回复', truncated: false })
})

test('直接选中助手回复元素时朗读选区，不误读后面的回复', () => {
  const html = '<div id="selected" data-chat-flow-kind="assistant-step" data-chat-group-part="response">选中的回复</div>' +
    '<div data-chat-flow-kind="assistant-step" data-chat-group-part="response">后面的回复</div>'
  assert.deepEqual(page(html, address, '#selected', true), { status: 'ok', text: '选中的回复', truncated: false })
})

test('用户可选择助手文本，其他页面、流式回复和无目标都不会猜测', () => {
  const html = '<div data-chat-flow-kind="assistant-step"><span id="selected">指定文本</span></div>'
  assert.deepEqual(page(html, address, '#selected'), { status: 'ok', text: '指定文本', truncated: false })
  assert.equal(page(html).status, 'unavailable')
  assert.equal(page(html, 'http://127.0.0.1:3080/other', '#selected').status, 'unavailable')
  assert.equal(page('<div data-chat-flow-kind="assistant-step" data-chat-group-part="response"><span data-streaming="true">生成中</span></div>').status, 'unavailable')
})

test('长回复有明确截断标记', () => {
  const result = page(`<div data-chat-flow-kind="assistant-step" data-chat-group-part="response">${'a'.repeat(2100)}</div>`)
  assert.equal(result.text.length, 2000)
  assert.equal(result.truncated, true)
})

test('朗读截断不会拆开表情，选中文本也遵守相同边界', () => {
  const text = `${'a'.repeat(1999)}🐋后续文字`
  const response = page(`<div data-chat-flow-kind="assistant-step" data-chat-group-part="response">${text}</div>`)
  const selected = page(`<div data-chat-flow-kind="assistant-step"><span id="selected">${text}</span></div>`, address, '#selected')
  for (const result of [response, selected]) {
    assert.equal(result.text, 'a'.repeat(1999))
    assert.equal(result.truncated, true)
    assert.equal(result.text.isWellFormed(), true)
  }
  const fits = page(`<div data-chat-flow-kind="assistant-step" data-chat-group-part="response">${'a'.repeat(1998)}🐋</div>`)
  assert.equal(fits.text.length, 2000)
  assert.equal(fits.text.endsWith('🐋'), true)
  assert.equal(fits.truncated, false)
})

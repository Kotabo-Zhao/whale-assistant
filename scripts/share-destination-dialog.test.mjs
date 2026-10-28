import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

// AppCompat AlertController shows the message instead of the list when both are configured.
test('分享目的地在说明文字旁有两个可见按钮，分别调用对应保存方法', async () => {
  const source = await readFile(new URL('../android/app/src/main/java/io/deepseekharness/mobile/MainActivity.kt', import.meta.url), 'utf8')
  const start = source.indexOf('.setTitle("选择保存位置")')
  const end = source.indexOf('.setNegativeButton("取消", null).show()', start)
  assert.ok(start >= 0 && end > start)
  const dialog = source.slice(start, end)
  assert.match(dialog, /setMessage\(/u)
  assert.doesNotMatch(dialog, /setItems\(/u)
  assert.match(dialog, /setPositiveButton\("本地资料库"\).*saveSharedKnowledge\(text\)/u)
  assert.match(dialog, /setNeutralButton\("收件箱"\).*saveSharedTextToInbox\(bytes\)/u)
  assert.match(dialog, /匹配内容可能发送给模型/u)
})

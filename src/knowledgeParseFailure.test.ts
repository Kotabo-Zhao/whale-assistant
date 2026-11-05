import { expect, it } from 'vitest'
import { knowledgeParseFailure } from './knowledgeParseFailure'

it('offers specific next steps without displaying arbitrary exception text', () => {
  expect(knowledgeParseFailure({parseState:'needs_retry',parseFailure:'MODEL_REQUIRED'})).toContain('配置 DeepSeek')
  expect(knowledgeParseFailure({parseState:'needs_retry',parseFailure:'UNSUPPORTED_FORMAT'})).toContain('文件格式')
  expect(knowledgeParseFailure({parseState:'needs_retry',parseFailure:'NETWORK_TIMEOUT'})).toContain('超时')
  expect(knowledgeParseFailure({parseState:'needs_retry',parseFailure:'private/path/sk-secret'})).not.toContain('secret')
  expect(knowledgeParseFailure({parseState:'complete',parseFailure:'MODEL_REQUIRED'})).toBe('')
  expect(knowledgeParseFailure({parseState:'queued',parseFailure:'MODEL_REQUIRED'})).toBe('')
})

/** Match only native fixed codes; never render arbitrary parser error text. */
export function knowledgeParseFailure(row: Record<string, unknown>): string {
  if (row.parseState !== 'needs_retry') return ''
  switch (row.parseFailure) {
    case 'MODEL_REQUIRED': return '本地未提取到文字，图像分析需要模型。请在设置中配置 DeepSeek 后重新解析。原件已保留。'
    case 'UNSUPPORTED_FORMAT': return '暂不支持提取此文件格式。原件已保留，可导出为文本、PDF 或常见音频格式后重新投放。'
    case 'EMPTY_CONTENT': return '本次没有提取到可用文字。原件已保留，请检查内容是否清晰，或换一份文件重新投放。'
    case 'NETWORK_TIMEOUT': return '模型解析请求超时。原件已保留，可在网络恢复后重新解析。'
    case 'INTERRUPTED': return '解析多次中断，已暂停自动重试。原件已保留，可在方便时手动重新解析。'
    case 'PARSE_FAILED': return '解析未完成。原件已保留，请检查文件能否正常打开，再重新解析。'
    default: return '本次未提取到正文。原件已保留，可在资料详情中重新解析。'
  }
}

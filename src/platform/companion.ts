/** 助手管理请求的共享契约；原生仍校验所有字段与数据版本。 */
export type CompanionRequest =
  | { operation: 'personaList' }
  | { operation: 'personaActivate' | 'personaDelete'; id: string }
  | { operation: 'personaSave'; card: { id: string; name: string; description: string; personality: string; scenario: string; examples: string; greeting: string } }
  | { operation: 'memoryList' | 'reminderList' | 'taskList'; query?: string; offset?: number }
  | { operation: 'taskUpdate'; id: string; expectedState: 'candidate' | 'open' | 'done' | 'cancelled'; expectedRevision: string; state: 'open' | 'done' | 'cancelled' }
  | { operation: 'taskUpdate'; id: string; expectedState: 'candidate' | 'open' | 'done' | 'cancelled'; expectedRevision: string; reminderAtMs: number }
  | { operation: 'taskDelete'; id: string; expectedState: 'candidate' | 'open' | 'done' | 'cancelled'; expectedRevision: string }
  | { operation: 'memoryPage'; before: number; revision: number }
  | { operation: 'knowledgeList'; query?: string; offset?: number; source?: 'shared_text' | 'text_file' | 'original' | 'image_analysis'; coverage?: 'not_parsed' | 'partial' | 'extracted_text' | 'model_description' }
  | { operation: 'overview' | 'memorySuggestionsReset' | 'memoryResumeAutomatic' }
  | { operation: 'memoryReview'; text: string; id?: string }
  | { operation: 'memoryAdd'; text: string; snapshot: string }
  | { operation: 'memoryEdit' | 'memoryApprove' | 'memoryApproveEdit'; id: string; text: string; snapshot: string; expected: string; expectedText: string }
  | { operation: 'memoryDelete' | 'knowledgeDelete'; id: string; expected: string; expectedText: string }
  | { operation: 'memoryBatchDelete'; entries: Array<{ id: string; expected: string }> }
  | { operation: 'knowledgeBatchDelete'; entries: Array<{ id: string; expectedText: string }> }
  | { operation: 'memoryClear'; confirmed: true }
  | { operation: 'knowledgeAdd'; text: string }
  | { operation: 'knowledgeEdit'; id: string; expectedText: string; text: string }
  | { operation: 'knowledgeUndoDelete'; id: string }
  | { operation: 'knowledgeBatchUndoDelete'; ids: string[] }
  | { operation: 'knowledgeProcessingStatus' | 'knowledgeProcessingRetry' | 'knowledgeProcessingCancel' | 'knowledgeProcessingResume'; id: string; expectedText: string }
  | { operation: 'knowledgeImagePreview'; id: string; originalId: string; expectedText: string }
  /** Irreversibly removes every local record linked to this verified original. */
  | { operation: 'knowledgeRemoveOriginal'; id: string; originalId: string; expectedText: string }
  | { operation: 'quietHoursSet'; start: number; end: number }
  | { operation: 'reminderAdd'; text: string; at: number }
  | { operation: 'reminderDelete' | 'recurringDelete'; id: string; expected: string }
  | { operation: 'recurringAdd'; text: string; hour: number; minute: number; weekday: number }
  | { operation: 'recurringEdit'; id: string; expected: string; text: string; hour: number; minute: number; weekday: number }
  | { operation: 'recurringRetry' | 'memorySuggestionDismiss'; id: string }
  | { operation: 'previewImport'; kind: 'memory' | 'knowledge'; contents: string }
  | { operation: 'applyImport'; token: string }

export type CompanionRow = { id: string; text: string; status?: string; state?: string; source?: string; at?: number; nextAt?: number; hour?: number; minute?: number; weekday?: number; [key: string]: unknown }
export function companionRows(value: unknown): CompanionRow[] {
  if (!Array.isArray(value) || !(value as unknown[]).every(row => row && typeof row === 'object' && 'id' in row && 'text' in row && typeof row.id === 'string' && typeof row.text === 'string')) throw new Error('助手数据格式不正确')
  return value as CompanionRow[]
}

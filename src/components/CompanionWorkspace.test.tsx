import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
const bridge = vi.hoisted(() => ({ companionDashboard: vi.fn(), exportCompanionBackup: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
import { CompanionWorkspace } from './CompanionWorkspace'

beforeEach(() => { vi.clearAllMocks(); bridge.companionDashboard.mockResolvedValue({ items: [], recurring: [] }); bridge.exportCompanionBackup.mockResolvedValue(undefined) })
describe('助手工作台', () => {
  it('标签关联唯一内容面板并向辅助技术报告加载状态', async () => {
    render(<CompanionWorkspace />)
    const memoryTab = screen.getByRole('tab', { name: '长期记忆' })
    expect(memoryTab).toHaveAttribute('aria-controls', 'companion-workspace-panel-memory')
    fireEvent.click(memoryTab)
    await screen.findByLabelText('记忆内容')
    const panel = screen.getByRole('tabpanel')
    expect(panel).toHaveAttribute('id', 'companion-workspace-panel-memory')
    expect(panel).toHaveAttribute('aria-labelledby', 'companion-workspace-tab-memory')
  })
  it('新建一次性提醒使用本机时间并调用原有提醒服务', async () => {
    render(<CompanionWorkspace />)
    await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'reminderList', query: '' }))
    fireEvent.change(screen.getByLabelText('提醒内容'), { target: { value: '取快递' } })
    fireEvent.change(screen.getByLabelText('日期'), { target: { value: '2030-01-02' } })
    fireEvent.change(screen.getByLabelText('时间'), { target: { value: '09:30' } })
    fireEvent.click(screen.getByRole('button', { name: '添加提醒' }))
    await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'reminderAdd', text: '取快递', at: new Date('2030-01-02T09:30').getTime() }))
  })
  it('记忆先展示相似内容供核对，再提交同一快照', async () => {
    bridge.companionDashboard.mockImplementation((req: { operation: string }) => Promise.resolve(req.operation === 'memoryReview' ? { snapshot: 'snapshot', candidates: [{ status: '已确认', text: '相似记忆' }] } : { items: [], recurring: [], ok: true }))
    render(<CompanionWorkspace />)
    fireEvent.click(screen.getByRole('tab', { name: '长期记忆' }))
    await screen.findByLabelText('记忆内容')
    fireEvent.change(screen.getByLabelText('记忆内容'), { target: { value: '喜欢咖啡' } })
    fireEvent.click(screen.getByRole('button', { name: '添加' }))
    expect(await screen.findByText('已确认：相似记忆')).toBeInTheDocument()
    expect(bridge.companionDashboard).not.toHaveBeenCalledWith(expect.objectContaining({ operation: 'memoryAdd' }))
    fireEvent.click(screen.getByRole('button', { name: '确认保存' }))
    await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'memoryAdd', text: '喜欢咖啡', snapshot: 'snapshot' }))
  })
  it('导入备份先显示预览并等待确认，失败不会声称已合并', async () => {
    bridge.companionDashboard.mockImplementation((req: { operation: string }) => {
      if (req.operation === 'previewImport') return Promise.resolve({ token: 'review-token', items: [{ id: 'a', text: '新资料' }], skipped: 2 })
      if (req.operation === 'applyImport') return Promise.reject(new Error('stale'))
      return Promise.resolve({ items: [], recurring: [] })
    })
    render(<CompanionWorkspace />)
    fireEvent.click(screen.getByRole('tab', { name: '资料' }))
    const file = new File(['{}'], 'backup.json', { type: 'application/json' })
    Object.defineProperty(file, 'arrayBuffer', { value: () => Promise.resolve(new TextEncoder().encode('{}').buffer) })
    fireEvent.change(screen.getByLabelText('导入备份'), { target: { files: [file] } })
    expect(await screen.findByText('新资料')).toBeInTheDocument()
    expect(bridge.companionDashboard).not.toHaveBeenCalledWith(expect.objectContaining({ operation: 'applyImport' }))
    fireEvent.click(screen.getByRole('button', { name: '确认合并' }))
    await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'applyImport', token: 'review-token' }))
    expect(await screen.findByRole('status')).toHaveTextContent('操作未完成')
  })
  it('备份导出调用系统保存流程', async () => {
    render(<CompanionWorkspace />)
    fireEvent.click(screen.getByRole('tab', { name: '资料' }))
    const area = within(screen.getByRole('region', { name: '助手工作台' }))
    fireEvent.click(area.getByRole('button', { name: '导出备份' }))
    await waitFor(() => expect(bridge.exportCompanionBackup).toHaveBeenCalledWith('knowledge'))
  })
})

it('刷新不清空正在编辑的资料，读取失败保留原记录', async () => {
  bridge.companionDashboard.mockResolvedValue({ items: [{ id: 'k1', text: '已有资料' }] })
  render(<CompanionWorkspace />)
  fireEvent.click(screen.getByRole('tab', { name: '资料' }))
  await screen.findByText('已有资料')
  fireEvent.change(screen.getByLabelText('资料内容'), { target: { value: '未保存的草稿' } })
  bridge.companionDashboard.mockRejectedValueOnce(new Error('unavailable'))
  fireEvent.click(screen.getByRole('button', { name: '刷新助手数据' }))
  await screen.findByText('无法读取数据，原有内容已保留。')
  expect(screen.getByText('已有资料')).toBeInTheDocument()
  expect(screen.getByLabelText('资料内容')).toHaveValue('未保存的草稿')
})
it('资料来源和解析范围筛选交由本地分页查询，加载更多保持相同筛选', async () => {
  bridge.companionDashboard.mockImplementation((request: { operation: string; offset?: number }) => {
    if (request.operation !== 'knowledgeList') return Promise.resolve({ items: [], recurring: [] })
    return Promise.resolve(request.offset === 20
      ? { items: [{ id: 'k2', text: '第二页内容', source: 'text_file', coverage: 'extracted_text' }], nextOffset: null }
      : { items: [{ id: 'k1', text: '第一页内容', displayName: '报告.pdf', source: 'text_file', coverage: 'extracted_text' }], nextOffset: 20 })
  })
  render(<CompanionWorkspace />)
  fireEvent.click(screen.getByRole('tab', { name: '资料' }))
  fireEvent.change(await screen.findByLabelText('资料来源'), { target: { value: 'text_file' } })
  fireEvent.change(screen.getByLabelText('解析覆盖'), { target: { value: 'extracted_text' } })
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'knowledgeList', query: '', offset: 0, source: 'text_file', coverage: 'extracted_text' }))
  expect(await screen.findByText('报告.pdf')).toBeInTheDocument()
  expect(screen.getByText('文档/文本 · 已提取文字')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '加载更多资料' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'knowledgeList', query: '', offset: 20, source: 'text_file', coverage: 'extracted_text' }))
  expect(await screen.findByText('第二页内容')).toBeInTheDocument()
})
it('资料处理状态与重试始终携带当前资料文本', async () => {
  bridge.companionDashboard.mockImplementation((request: { operation: string }) => {
    if (request.operation === 'knowledgeList') return Promise.resolve({ items: [{ id: 'k1', text: '等待处理的资料' }] })
    if (request.operation === 'knowledgeProcessingStatus') return Promise.resolve({ state: 'queued', attempts: 2, nextAttemptAtMs: null, lastErrorCode: 'network', memoryEnabled: true })
    return Promise.resolve({ ok: true })
  })
  render(<CompanionWorkspace />)
  fireEvent.click(screen.getByRole('tab', { name: '资料' }))
  fireEvent.click(await screen.findByRole('button', { name: '处理状态' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'knowledgeProcessingStatus', id: 'k1', expectedText: '等待处理的资料' }))
  expect(await screen.findByText('等待或重试中；已重试 2 次。下次：等待系统调度。 上次原因：网络不可用。')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '重试' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'knowledgeProcessingRetry', id: 'k1', expectedText: '等待处理的资料' }))
})
it('资料详情展示保存的分段和原件关联，不把分段伪装成页码', async () => {
  const originalId = 'b'.repeat(64)
  bridge.companionDashboard.mockImplementation((request: { operation: string }) => Promise.resolve(request.operation === 'knowledgeList'
    ? { items: [{ id: 'k1', text: '第三段完整内容', displayName: '报告.pdf', source: 'text_file', coverage: 'extracted_text', documentId: originalId, originalId, chunkIndex: 2, chunkCount: 4 }] }
    : { ok: true }))
  render(<CompanionWorkspace />)
  fireEvent.click(screen.getByRole('tab', { name: '资料' }))
  const detail = (await screen.findByText('报告.pdf')).closest('details')!
  expect(detail).not.toHaveAttribute('open')
  fireEvent.click(detail.querySelector('summary')!)
  expect(detail).toHaveAttribute('open')
  expect(within(detail).getByText(/文档第 3 \/ 4 段（保存的文本分段，不是页码）/)).toBeInTheDocument()
  expect(within(detail).getByText(/已关联本机原件/)).toBeInTheDocument()
})
it('资料原件删除要求同一资料、原件和当前文本三项标识，并且不提供撤销', async () => {
  const originalId = 'a'.repeat(64)
  bridge.companionDashboard.mockImplementation((request: { operation: string }) => Promise.resolve(request.operation === 'knowledgeList'
    ? { items: [{ id: 'k1', text: '已导入资料片段', originalId }] }
    : { ok: true, records: 3, objectDeleted: true, undoAvailable: false }))
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
  render(<CompanionWorkspace />)
  fireEvent.click(screen.getByRole('tab', { name: '资料' }))
  const card = (await screen.findByText('已导入资料片段')).closest('article')!
  fireEvent.click(within(card).getByRole('button', { name: '永久删除整份原件' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'knowledgeRemoveOriginal', id: 'k1', originalId, expectedText: '已导入资料片段' }))
  expect(screen.getByRole('status')).toHaveTextContent('已永久删除原件及 3 条本地资料，不能撤销。')
  expect(screen.queryByRole('button', { name: '撤销删除' })).not.toBeInTheDocument()
  confirm.mockRestore()
})
it('资料批量管理传递所有当前文本，并保留整批撤销入口', async () => {
  bridge.companionDashboard.mockImplementation((request: { operation: string }) => Promise.resolve(request.operation === 'knowledgeList'
    ? { items: [{ id: 'k1', text: '资料一' }, { id: 'k2', text: '资料二' }] } : { ok: true, deleted: 2 }))
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
  render(<CompanionWorkspace />)
  fireEvent.click(screen.getByRole('tab', { name: '资料' }))
  await screen.findByText('资料一')
  fireEvent.click(screen.getByRole('button', { name: '批量管理' }))
  fireEvent.click(screen.getByRole('checkbox', { name: '选择资料：资料一' }))
  fireEvent.click(screen.getByRole('checkbox', { name: '选择资料：资料二' }))
  fireEvent.click(screen.getByRole('button', { name: '删除所选（2）' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'knowledgeBatchDelete', entries: [{ id: 'k1', expectedText: '资料一' }, { id: 'k2', expectedText: '资料二' }] }))
  expect(await screen.findByRole('button', { name: '撤销本批资料' })).toBeInTheDocument()
  confirm.mockRestore()
})
it('损坏的列表响应显示读取失败而不是空列表', async () => {
  bridge.companionDashboard.mockResolvedValue({ items: 'broken' })
  render(<CompanionWorkspace />)
  await screen.findByText('无法读取数据，原有内容已保留。')
  expect(screen.queryByText('当前分类还没有内容，可以在下方添加或导入。')).not.toBeInTheDocument()
})

it('左滑记忆卡片后确认删除，使用当前记录快照', async () => {
  bridge.companionDashboard.mockImplementation((req: { operation: string }) => Promise.resolve(req.operation === 'memoryList'
    ? { items: [{ id: 'm1', text: '喜欢咖啡', _review: 'snapshot' }] } : { items: [], ok: true }))
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
  render(<CompanionWorkspace />)
  fireEvent.click(screen.getByRole('tab', { name: '长期记忆' }))
  fireEvent.click(await screen.findByText('手动与对话记忆'))
  const card = (await screen.findAllByText('喜欢咖啡'))[0].closest('article')!
  expect(within(card).queryByRole('button', { name: '删除' })).not.toBeInTheDocument()
  fireEvent.touchStart(card, { touches: [{ clientX: 250, clientY: 20 }] })
  fireEvent.touchEnd(card, { changedTouches: [{ clientX: 100, clientY: 20 }] })
  fireEvent.click(within(card).getByRole('button', { name: '删除' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith(expect.objectContaining({ operation: 'memoryDelete', id: 'm1', expected: 'snapshot' })))
  expect(confirm).toHaveBeenCalled()
  confirm.mockRestore()
})

it('批量管理先选择当前记忆，再将全部快照交给原子删除操作', async () => {
  bridge.companionDashboard.mockImplementation((req: { operation: string }) => Promise.resolve(req.operation === 'memoryList'
    ? { items: [{ id: 'm1', text: '喜欢咖啡', _review: 'snapshot-1' }, { id: 'm2', text: '喜欢茶', _review: 'snapshot-2' }] } : { ok: true }))
  const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
  render(<CompanionWorkspace />)
  fireEvent.click(screen.getByRole('tab', { name: '长期记忆' }))
  fireEvent.click(await screen.findByText('手动与对话记忆'))
  fireEvent.click(screen.getByRole('button', { name: '批量管理' }))
  fireEvent.click(screen.getByRole('checkbox', { name: '选择记忆：喜欢咖啡' }))
  fireEvent.click(screen.getByRole('checkbox', { name: '选择记忆：喜欢茶' }))
  fireEvent.click(screen.getByRole('button', { name: '删除所选（2）' }))
  await waitFor(() => expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'memoryBatchDelete', entries: [{ id: 'm1', expected: 'snapshot-1' }, { id: 'm2', expected: 'snapshot-2' }] }))
  confirm.mockRestore()
})

it('按来源折叠记忆，展开卡片后才显示全文', async () => {
  const longText = '分享资料：' + '鲸鱼喜欢游泳。'.repeat(15)
  bridge.companionDashboard.mockImplementation((req: { operation: string }) => Promise.resolve(req.operation === 'memoryList' ? { items: [{ id: 'm2', text: longText, source: 'shared_understanding' }] } : { items: [] }))
  render(<CompanionWorkspace />)
  fireEvent.click(screen.getByRole('tab', { name: '长期记忆' }))
  const group = (await screen.findByText('分享内容')).closest('details')!
  expect(group).not.toHaveAttribute('open')
  fireEvent.click(within(group).getByText('分享内容'))
  const card = group.querySelector('article')!
  const detail = card.querySelector('details')!
  expect(detail).not.toHaveAttribute('open')
  fireEvent.click(detail.querySelector('summary')!)
  expect(detail).toHaveAttribute('open')
})

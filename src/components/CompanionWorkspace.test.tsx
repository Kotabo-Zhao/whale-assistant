import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
const bridge = vi.hoisted(() => ({ companionDashboard: vi.fn(), exportCompanionBackup: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
import { CompanionWorkspace } from './CompanionWorkspace'

beforeEach(() => { vi.clearAllMocks(); bridge.companionDashboard.mockResolvedValue({ items: [], recurring: [] }); bridge.exportCompanionBackup.mockResolvedValue(undefined) })
describe('助手工作台', () => {
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

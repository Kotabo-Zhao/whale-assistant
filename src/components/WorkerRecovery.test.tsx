import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
const bridge = vi.hoisted(() => ({ companionDashboard: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
import { WorkerRecovery } from './WorkerRecovery'

const row = { id: 'held', text: '核对任务', recoveryRequired: true,
  recovery: { incidentId: 'incident-one', usable: true, preview: { title: '核对任务', summary: '579' } },
  goalRecovery: { incidentId: 'goal-one', usable: true, preview: { criteria: ['核对结果'] } } }

it('requires explicit review and submits both incident versions without automatically resuming', async () => {
  bridge.companionDashboard.mockReset().mockResolvedValue({})
  const refresh = vi.fn()
  render(<WorkerRecovery row={row} onRefresh={refresh} />)
  fireEvent.click(screen.getByText('恢复副本 · 待核对'))
  expect(screen.getByRole('button', { name: '核对后解锁' })).toBeDisabled()
  expect(bridge.companionDashboard).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(screen.getByRole('button', { name: '核对后解锁' }))
  await waitFor(() => expect(refresh).toHaveBeenCalledOnce())
  expect(bridge.companionDashboard).toHaveBeenCalledOnce()
  expect(bridge.companionDashboard).toHaveBeenCalledWith({ operation: 'workerControl', id: 'held',
    action: 'recover', recoveryAcknowledged: true, recoveryIncident: 'incident-one', goalRecoveryIncident: 'goal-one' })
  expect(screen.getByRole('status')).toHaveTextContent('状态更新后')
})

it('keeps failed or unusable recovery held and presents the prior native preview only as evidence', async () => {
  bridge.companionDashboard.mockReset().mockRejectedValue(new Error('stale'))
  const refresh = vi.fn()
  const view = render(<WorkerRecovery row={row} onRefresh={refresh} />)
  fireEvent.click(screen.getByText('恢复副本 · 待核对'))
  fireEvent.click(screen.getByRole('checkbox'))
  fireEvent.click(screen.getByRole('button', { name: '核对后解锁' }))
  expect(await screen.findByRole('status')).toHaveTextContent('请刷新后重新核对')
  expect(refresh).not.toHaveBeenCalled()
  view.rerender(<WorkerRecovery row={{ ...row, recovery: { incidentId: 'broken', usable: false,
    localPreview: { summary: '仅供核对的旧摘要' } } }} onRefresh={refresh} />)
  expect(screen.queryByRole('button', { name: '核对后解锁' })).not.toBeInTheDocument()
  expect(screen.getByText('仅供核对的旧摘要')).toBeInTheDocument()
  expect(screen.getByText(/缺少可校验的完整副本/)).toBeInTheDocument()
})

import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
const bridge = vi.hoisted(() => ({ companionDashboard: vi.fn() }))
vi.mock('../platform/native', () => ({ runtimeBridge: bridge }))
import { SuperagentSettings } from './SuperagentSettings'

it('keeps revoked actors and tokens out of stale channel saves', async () => {
  let current = {revision:'r1',config:{github:{allowedActors:['alice']}},credentials:{githubToken:true}}
  bridge.companionDashboard.mockImplementation((req: {operation:string}) => Promise.resolve(
    req.operation === 'superagentSettingsSave' ? {...current,conflict:true} : current))
  render(<SuperagentSettings />)
  fireEvent.click(screen.getByText('跨渠道任务设置'))
  const actors = screen.getByLabelText(/允许的 GitHub 用户/)
  await waitFor(() => expect(actors).toHaveValue('alice'))
  fireEvent.click(screen.getByRole('button',{name:'保存时移除本机凭据'}))
  expect(screen.getByLabelText(/GitHub 令牌/)).toBeDisabled()
  current = {revision:'r2',config:{github:{allowedActors:[]}},credentials:{githubToken:false}}
  fireEvent.click(screen.getByRole('button',{name:'保存渠道设置'}))
  expect(await screen.findByRole('button',{name:'放弃草稿并载入最新设置'})).toBeVisible()
  expect(bridge.companionDashboard).toHaveBeenCalledWith(expect.objectContaining({operation:'superagentSettingsSave',
    expectedRevision:'r1',credentials:{githubToken:''}}))
  expect(actors).toHaveValue('alice')
  expect(screen.getByRole('button',{name:'保存渠道设置'})).toBeDisabled()
  fireEvent.click(screen.getByRole('button',{name:'放弃草稿并载入最新设置'}))
  await waitFor(() => expect(actors).toHaveValue(''))
  expect(screen.queryByRole('button',{name:'保存时移除本机凭据'})).toBeNull()
})

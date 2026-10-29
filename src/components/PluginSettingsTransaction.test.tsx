import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { PluginSettings } from './PluginSettings'
import { createBrowserBridge } from '../platform/browser'
import type { RuntimeState } from '../platform/types'

vi.mock('./MobileMarket', () => ({ MobileMarket: ({ onInstall }: { onInstall: (name: string) => void }) => <button onClick={() => onInstall('example-plugin')}>测试安装</button> }))
const runtime: RuntimeState = { phase: 'running', installedVersion: 'test', architecture: 'arm64-v8a', updateAvailable: false, downloadedBytes: 0, totalBytes: 0, runnerAvailable: true }
function fixture() {
  let finish!: () => void
  const stopped = new Promise<void>(resolve => { finish = resolve })
  const bridge = { ...createBrowserBridge(), managePlugins: vi.fn().mockResolvedValue({ plugins: [] }), stopRuntime: vi.fn().mockImplementation(async () => { await stopped; return runtime }) }
  const view = render(<PluginSettings bridge={bridge} runtime={runtime} onBack={vi.fn()} />)
  return { bridge, finish, ...view }
}
async function openMarket() {
  await waitFor(() => expect(screen.getByRole('button', { name: '刷新' })).toBeEnabled())
  fireEvent.click(screen.getByRole('tab', { name: '浏览商店' }))
}
it('停止期间重复安装只产生一次停止和一次导入', async () => {
  const { bridge, finish } = fixture()
  await openMarket()
  fireEvent.click(screen.getByText('测试安装'))
  fireEvent.click(screen.getByText('测试安装'))
  expect(bridge.stopRuntime).toHaveBeenCalledTimes(1)
  expect(bridge.managePlugins).toHaveBeenCalledTimes(1)
  await act(async () => { finish(); await Promise.resolve() })
  expect(bridge.managePlugins).toHaveBeenCalledTimes(2)
  expect(bridge.managePlugins).toHaveBeenLastCalledWith({ operation: 'import', source: 'example-plugin' })
})
it('停止尚未完成时离开页面，不继续导入插件', async () => {
  const { bridge, finish, unmount } = fixture()
  await openMarket()
  fireEvent.click(screen.getByText('测试安装'))
  unmount()
  await act(async () => { finish(); await Promise.resolve() })
  expect(bridge.managePlugins).toHaveBeenCalledTimes(1)
})
it('停止失败时保留失败说明，不执行导入', async () => {
  const { bridge } = fixture()
  bridge.stopRuntime.mockRejectedValueOnce(new Error('stop failed'))
  await openMarket()
  fireEvent.click(screen.getByText('测试安装'))
  await screen.findByText('停止运行环境失败，请重试')
  expect(bridge.managePlugins).toHaveBeenCalledTimes(1)
})

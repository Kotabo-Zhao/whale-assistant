import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { MobileMarket } from './MobileMarket'
import { parseMarketCatalog } from './marketCatalog'

afterEach(() => { vi.unstubAllGlobals() })

it('仅接收合法 npm 包名，去重并限制目录文字长度', () => {
  const items = parseMarketCatalog({ plugins: [
    { name: '测试', npm: '@example/plugin', description: { zh: '介绍' } },
    { name: '重复', npm: '@example/plugin' },
    { name: '非法', npm: '../../bad' },
    { name: '脚本', npm: 'https://bad.example/x' },
  ] })
  expect(items).toEqual([{ name: '测试', npm: '@example/plugin', description: '介绍' }])
  expect(() => parseMarketCatalog({ plugins: 'bad' })).toThrow()
})

it('只在用户点击后读取目录，并将安装交给受控入口', async () => {
  const bytes = new TextEncoder().encode(JSON.stringify({ plugins: [{ name: '示例', npm: 'example-plugin' }] }))
  let complete = false
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => ({
    read: () => Promise.resolve(complete ? { done: true } : (complete = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {},
  }) } })
  vi.stubGlobal('fetch', fetchMock)
  const install = vi.fn()
  render(<MobileMarket locked={false} onInstall={install} />)
  expect(fetchMock).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '加载插件目录' }))
  await screen.findByText('示例')
  fireEvent.click(screen.getByRole('button', { name: '安装' }))
  expect(install).toHaveBeenCalledWith('example-plugin')
  await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1))
})

it('运行环境锁定时不能安装目录插件', async () => {
  const bytes = new TextEncoder().encode(JSON.stringify({ plugins: [{ name: '示例', npm: 'example-plugin' }] }))
  let complete = false
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => ({
    read: () => Promise.resolve(complete ? { done: true } : (complete = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {},
  }) } }))
  render(<MobileMarket locked onInstall={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: '加载插件目录' }))
  expect(await screen.findByRole('button', { name: '安装' })).toBeDisabled()
})

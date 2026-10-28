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
  expect(install).not.toHaveBeenCalled()
  expect(screen.getByRole('region', { name: '确认安装插件' })).toHaveTextContent('example-plugin')
  fireEvent.click(screen.getByRole('button', { name: '确认安装' }))
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


it('取消确认或运行环境锁定不会触发安装', async () => {
  const bytes = new TextEncoder().encode(JSON.stringify({ plugins: [{ name: '示例', npm: 'example-plugin' }] }))
  let complete = false
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => ({
    read: () => Promise.resolve(complete ? { done: true } : (complete = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {},
  }) } }))
  const install = vi.fn()
  const view = render(<MobileMarket locked={false} onInstall={install} />)
  fireEvent.click(screen.getByRole('button', { name: '加载插件目录' }))
  fireEvent.click(await screen.findByRole('button', { name: '安装' }))
  fireEvent.click(screen.getByRole('button', { name: '取消' }))
  expect(install).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '安装' }))
  view.rerender(<MobileMarket locked onInstall={install} />)
  expect(screen.getByRole('button', { name: '确认安装' })).toBeDisabled()
  expect(install).not.toHaveBeenCalled()
})

it('在插件目录内就地显示检查、版本扫描不完整和结果详情', () => {
  const view = render(<MobileMarket locked onInstall={vi.fn()} installState={{ name: 'example-plugin', busy: true, failed: false, notice: '', detail: '' }} />)
  expect(screen.getByRole('status')).toHaveTextContent('正在检查并安装')
  view.rerender(<MobileMarket locked={false} onInstall={vi.fn()} installState={{ name: 'example-plugin', busy: false, failed: true, notice: '最近检查的插件版本与当前运行环境不兼容，仍有旧版本未检查；已有版本未被替换。', detail: 'example-plugin dsh=0.2.1' }} />)
  expect(screen.getByRole('alert')).toHaveTextContent('仍有旧版本未检查')
  expect(screen.getByRole('alert')).toHaveTextContent('example-plugin dsh=0.2.1')
})

it('目录可指定精确版本，拒绝范围或标签', async () => {
  const bytes = new TextEncoder().encode(JSON.stringify({ plugins: [{ name: '示例', npm: '@example/plugin' }] }))
  let complete = false
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => ({
    read: () => Promise.resolve(complete ? { done: true } : (complete = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {},
  }) } }))
  const install = vi.fn()
  render(<MobileMarket locked={false} onInstall={install} />)
  fireEvent.click(screen.getByRole('button', { name: '加载插件目录' }))
  fireEvent.click(await screen.findByRole('button', { name: '安装' }))
  const input = screen.getByRole('textbox', { name: '指定版本（可选）' })
  fireEvent.change(input, { target: { value: 'latest' } })
  expect(screen.getByRole('button', { name: '确认安装' })).toBeDisabled()
  fireEvent.change(input, { target: { value: '1.2.3-rc.1' } })
  fireEvent.click(screen.getByRole('button', { name: '确认安装' }))
  expect(install).toHaveBeenCalledWith('@example/plugin@1.2.3-rc.1')
})

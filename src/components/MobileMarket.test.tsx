import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { MobileMarket } from './MobileMarket'
import { fetchPackagePreview, parseMarketCatalog, parsePackagePreview } from './marketCatalog'

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


it('插件目录分页可访问全部结果，搜索后回到第一页', async () => {
  const plugins = Array.from({ length: 65 }, (_, i) => ({ name: `插件 ${String(i).padStart(2, '0')}`, npm: `example-${i}` }))
  const bytes = new TextEncoder().encode(JSON.stringify({ plugins }))
  let complete = false
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => ({
    read: () => Promise.resolve(complete ? { done: true } : (complete = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {},
  }) } }))
  render(<MobileMarket locked={false} onInstall={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: '加载插件目录' }))
  expect(await screen.findByText('插件 00')).toBeVisible()
  expect(screen.queryByText('插件 30')).not.toBeInTheDocument()
  expect(screen.getByText('共 65 个结果，第 1/3 页。')).toBeVisible()
  expect(screen.getByRole('button', { name: '上一页' })).toBeDisabled()
  fireEvent.click(screen.getByRole('button', { name: '下一页' }))
  expect(screen.getByText('插件 30')).toBeVisible()
  expect(screen.queryByText('插件 00')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: '下一页' }))
  expect(screen.getByText('插件 64')).toBeVisible()
  expect(screen.getByRole('button', { name: '下一页' })).toBeDisabled()
  fireEvent.change(screen.getByRole('searchbox', { name: '搜索目录' }), { target: { value: 'example-1' } })
  expect(screen.getByText('插件 01')).toBeVisible()
  expect(screen.queryByText('插件 64')).not.toBeInTheDocument()
  expect(screen.queryByRole('navigation', { name: '插件目录分页' })).not.toBeInTheDocument()
})


it('按需读取指定 npm 版本声明，校验包身份且不将声明当作兼容结论', async () => {
  const manifest = { name: '@example/plugin', version: '1.2.3', os: ['linux'], cpu: ['arm64'], engines: { node: '>=20' }, dependencies: { example: '^1' }, scripts: { install: 'do not display' } }
  const bytes = new TextEncoder().encode(JSON.stringify(manifest))
  let done = false
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => ({
    read: () => Promise.resolve(done ? { done: true } : (done = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {},
  }) } })
  vi.stubGlobal('fetch', fetchMock)
  const preview = await fetchPackagePreview('@example/plugin', '1.2.3', new AbortController().signal)
  expect(fetchMock.mock.calls[0][0]).toBe('https://registry.npmjs.org/%40example%2Fplugin/1.2.3')
  expect(preview).toMatchObject({ version: '1.2.3', os: 'linux', cpu: 'arm64', engines: 'node: >=20', dependencies: 'example', scripts: 'install' })
  expect(JSON.stringify(preview)).not.toContain('do not display')
  expect(() => parsePackagePreview({ ...manifest, name: 'other' }, '@example/plugin', '1.2.3')).toThrow()
  expect(() => parsePackagePreview(manifest, '@example/plugin', '1.2.4')).toThrow()
})

it('更改版本后旧包声明不再显示', async () => {
  const catalog = new TextEncoder().encode(JSON.stringify({ plugins: [{ name: '示例', npm: 'example-plugin' }] }))
  const pkg = new TextEncoder().encode(JSON.stringify({ name: 'example-plugin', version: '1.0.0', os: ['linux'] }))
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
    let done = false
    const bytes = url.includes('registry.npmjs.org') ? pkg : catalog
    return Promise.resolve({ ok: true, body: { getReader: () => ({ read: () => Promise.resolve(done ? { done: true } : (done = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {} }) } })
  }))
  render(<MobileMarket locked={false} onInstall={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: '加载插件目录' }))
  fireEvent.click(await screen.findByRole('button', { name: '安装' }))
  fireEvent.click(screen.getByRole('button', { name: '查看 npm 包声明' }))
  expect(await screen.findByText('1.0.0')).toBeVisible()
  expect(screen.getByText(/并非安全或安卓兼容认证/)).toBeVisible()
  fireEvent.change(screen.getByRole('textbox', { name: '指定版本（可选）' }), { target: { value: '1.2.3' } })
  expect(screen.queryByText('1.0.0')).not.toBeInTheDocument()
})

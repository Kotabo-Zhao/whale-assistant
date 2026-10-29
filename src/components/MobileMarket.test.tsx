import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { MobileMarket } from './MobileMarket'
import { fetchCatalog, fetchPackagePreview, parseCatalogData, parseCatalogOffThread, parseMarketCatalog, parsePackagePreview } from './marketCatalog'

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

it('保留目录分类及仅浏览来源，同时拒绝非可信来源和无效包名', () => {
  const items = parseMarketCatalog({ categories: { memory: { zh: '记忆' } }, plugins: [
    { name: '记忆插件', npm: 'memory-plugin', category: 'memory' },
    { name: '源码插件', npm: null, category: 'memory', url: 'https://github.com/example/plugin' },
    { name: '非法来源', npm: null, url: 'https://github.com.attacker.test/example/plugin' },
  ] })
  expect(items).toHaveLength(2)
  expect(items[0]).toMatchObject({ category: 'memory', categoryLabel: '记忆' })
  expect(items[1]).toMatchObject({ npm: null, sourceUrl: 'https://github.com/example/plugin' })
})

it('目录按分类及安装状态筛选，并显示不能直接安装的源码条目', async () => {
  const bytes = new TextEncoder().encode(JSON.stringify({ categories: { memory: { zh: '记忆' }, tools: { zh: '工具' } }, plugins: [
    { name: '已安装记忆', npm: 'memory-plugin', category: 'memory' },
    { name: '待安装工具', npm: 'tool-plugin', category: 'tools' },
    { name: '源码插件', npm: null, url: 'https://github.com/example/source', category: 'tools' },
  ] }))
  let complete = false
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => ({
    read: () => Promise.resolve(complete ? { done: true } : (complete = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {},
  }) } }))
  const manage = vi.fn()
  render(<MobileMarket locked={false} onInstall={vi.fn()} installed={new Map([['memory-plugin', '1.0.0']])} onManage={manage} />)
  fireEvent.click(screen.getByRole('button', { name: '加载插件目录' }))
  await screen.findByText('已安装记忆')
  fireEvent.change(screen.getByRole('combobox', { name: '插件分类' }), { target: { value: 'tools' } })
  expect(screen.queryByText('已安装记忆')).not.toBeInTheDocument()
  expect(screen.getByText('待安装工具')).toBeInTheDocument()
  fireEvent.change(screen.getByRole('combobox', { name: '安装状态' }), { target: { value: 'source' } })
  expect(screen.queryByText('待安装工具')).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: '查看来源' })).toHaveAttribute('href', 'https://github.com/example/source')
  fireEvent.change(screen.getByRole('combobox', { name: '插件分类' }), { target: { value: 'all' } })
  fireEvent.change(screen.getByRole('combobox', { name: '安装状态' }), { target: { value: 'installed' } })
  fireEvent.click(screen.getByRole('button', { name: '管理' }))
  expect(manage).toHaveBeenCalledWith('memory-plugin')
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


it('取消确认或运行环境锁定不会触发安装', async () => {
  const bytes = new TextEncoder().encode(JSON.stringify({ plugins: [{ name: '示例', npm: 'example-plugin' }] }))
  let complete = false
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => ({
    read: () => Promise.resolve(complete ? { done: true } : (complete = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {},
  }) } }))
  const install = vi.fn()
  const view = render(<MobileMarket locked={false} onInstall={install} />)
  fireEvent.click(screen.getByRole('button', { name: '加载插件目录' }))
  fireEvent.click(await screen.findByRole('button', { name: '版本与详情' }))
  fireEvent.click(screen.getByRole('button', { name: '取消' }))
  expect(install).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '版本与详情' }))
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
  fireEvent.click(await screen.findByRole('button', { name: '版本与详情' }))
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

it('多个搜索词可分别匹配名称、包名和介绍，且忽略大小写', async () => {
  const plugins = [
    { name: '日程助手', npm: 'whale-calendar', description: { zh: '每日提醒和待办' } },
    { name: '日程看板', npm: 'other-calendar', description: { zh: '规划项目' } },
  ]
  const bytes = new TextEncoder().encode(JSON.stringify({ plugins }))
  let complete = false
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => ({
    read: () => Promise.resolve(complete ? { done: true } : (complete = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {},
  }) } }))
  render(<MobileMarket locked={false} onInstall={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: '加载插件目录' }))
  await screen.findByText('日程助手')
  fireEvent.change(screen.getByRole('searchbox', { name: '搜索目录' }), { target: { value: '  日程   CALENDAR 提醒  ' } })
  await waitFor(() => expect(screen.getByText('日程助手')).toBeVisible())
  expect(screen.queryByText('日程看板')).not.toBeInTheDocument()
  expect(screen.getByText('共 1 个结果，第 1/1 页。')).toBeVisible()
})

it('搜索名称、包名和描述时忽略大小写，刷新后不保留旧索引', async () => {
  let generation = 0
  vi.stubGlobal('fetch', vi.fn().mockImplementation(() => {
    const plugins = generation++ === 0
      ? [{ name: 'Weather', npm: 'weather-plugin', description: { zh: '每日天气' } }, { name: '笔记', npm: 'notes-plugin', description: { en: 'Quick Note' } }]
      : [{ name: '新版', npm: 'new-plugin', description: { zh: '每日天气' } }]
    const bytes = new TextEncoder().encode(JSON.stringify({ plugins }))
    let done = false
    return Promise.resolve({ ok: true, body: { getReader: () => ({ read: () => Promise.resolve(done ? { done: true } : (done = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {} }) } })
  }))
  render(<MobileMarket locked={false} onInstall={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: '加载插件目录' }))
  expect(await screen.findByText('Weather')).toBeVisible()
  const search = screen.getByRole('searchbox', { name: '搜索目录' })
  fireEvent.change(search, { target: { value: 'QUICK NOTE' } })
  expect(await screen.findByText('笔记')).toBeVisible()
  expect(screen.queryByText('Weather')).not.toBeInTheDocument()
  fireEvent.change(search, { target: { value: '每日天气' } })
  expect(await screen.findByText('Weather')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '刷新目录' }))
  expect(await screen.findByText('新版')).toBeVisible()
  expect(screen.queryByText('Weather')).not.toBeInTheDocument()
})


it('按需读取指定 npm 版本声明，校验包身份且不将声明当作兼容结论', async () => {
  const manifest = { name: '@example/plugin', version: '1.2.3', os: ['linux'], cpu: ['arm64'], engines: { node: '>=20' }, dsh: { engines: { dsh: '^0.2.1' } }, dependencies: { example: '^1' }, optionalDependencies: { helper: '~2' }, peerDependencies: { host: '>=3' }, scripts: { install: 'do not display' } }
  const bytes = new TextEncoder().encode(JSON.stringify(manifest))
  let done = false
  const fetchMock = vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => ({
    read: () => Promise.resolve(done ? { done: true } : (done = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {},
  }) } })
  vi.stubGlobal('fetch', fetchMock)
  const preview = await fetchPackagePreview('@example/plugin', '1.2.3', new AbortController().signal)
  expect(fetchMock.mock.calls[0][0]).toBe('https://registry.npmjs.org/%40example%2Fplugin/1.2.3')
  expect(preview).toMatchObject({ version: '1.2.3', os: 'linux', cpu: 'arm64', engines: 'node: >=20', harness: '^0.2.1', dependencies: 'example: ^1', optionalDependencies: 'helper: ~2', peerDependencies: 'host: >=3', scripts: 'install' })
  expect(JSON.stringify(preview)).not.toContain('do not display')
  expect(() => parsePackagePreview({ ...manifest, name: 'other' }, '@example/plugin', '1.2.3')).toThrow()
  expect(() => parsePackagePreview(manifest, '@example/plugin', '1.2.4')).toThrow()
  expect(parsePackagePreview({ ...manifest, dsh: { engines: 'invalid' } }, '@example/plugin', '1.2.3').harness).toBe('声明格式无法识别')
  expect(parsePackagePreview({ ...manifest, dsh: undefined }, '@example/plugin', '1.2.3').harness).toBe('未声明')
  expect(parsePackagePreview({ ...manifest, optionalDependencies: { broken: 1 } }, '@example/plugin', '1.2.3').optionalDependencies).toBe('声明格式无法识别')
})

it('更改版本后旧包声明不再显示', async () => {
  const catalog = new TextEncoder().encode(JSON.stringify({ plugins: [{ name: '示例', npm: 'example-plugin' }] }))
  const pkg = new TextEncoder().encode(JSON.stringify({ name: 'example-plugin', version: '1.0.0', os: ['linux'], dsh: { engines: { dsh: '^0.2.1' } }, dependencies: { 'demo-dependency': '^3' } }))
  vi.stubGlobal('fetch', vi.fn().mockImplementation((url: string) => {
    let done = false
    const bytes = url.includes('registry.npmjs.org') ? pkg : catalog
    return Promise.resolve({ ok: true, body: { getReader: () => ({ read: () => Promise.resolve(done ? { done: true } : (done = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {} }) } })
  }))
  render(<MobileMarket locked={false} onInstall={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: '加载插件目录' }))
  fireEvent.click(await screen.findByRole('button', { name: '版本与详情' }))
  fireEvent.click(screen.getByRole('button', { name: '查看 npm 包声明' }))
  expect(await screen.findByText('1.0.0')).toBeVisible()
  expect(screen.getByText(/并非安全或安卓兼容认证/)).toBeVisible()
  expect(screen.getByText(/Harness 版本要求.*\^0\.2\.1/)).toBeVisible()
  expect(screen.getByText(/demo-dependency: \^3/)).toBeVisible()
  fireEvent.change(screen.getByRole('textbox', { name: '指定版本（可选）' }), { target: { value: '1.2.3' } })
  expect(screen.queryByText('1.0.0')).not.toBeInTheDocument()
})


it('目录解析可交给 worker，并在取消时终止', async () => {
  const bytes = new TextEncoder().encode(JSON.stringify({ plugins: [{ name: '示例', npm: 'example-plugin' }] }))
  const workers: Array<{ onmessage: ((event: MessageEvent) => void) | null; onerror: (() => void) | null; terminate: ReturnType<typeof vi.fn>; postMessage: ReturnType<typeof vi.fn> }> = []
  class FakeWorker {
    onmessage: ((event: MessageEvent) => void) | null = null
    onerror: (() => void) | null = null
    terminate = vi.fn()
    postMessage = vi.fn()
    constructor() { workers.push(this) }
  }
  vi.stubGlobal('Worker', FakeWorker)
  const controller = new AbortController()
  const pending = parseCatalogOffThread(bytes, controller.signal)
  expect(workers[0].postMessage).toHaveBeenCalledOnce()
  workers[0].onmessage?.({ data: { ok: true, entries: parseCatalogData(bytes) } } as MessageEvent)
  expect(await pending).toEqual([{ name: '示例', npm: 'example-plugin', description: '' }])
  expect(workers[0].terminate).toHaveBeenCalledOnce()
  const aborter = new AbortController()
  const cancelled = parseCatalogOffThread(bytes, aborter.signal)
  aborter.abort()
  await expect(cancelled).rejects.toThrow('取消')
  expect(workers[1].terminate).toHaveBeenCalledOnce()
})

it('worker 无法运行时回退且不会把无效目录当成空列表', async () => {
  class FailingWorker { constructor() { throw new Error('blocked') } }
  vi.stubGlobal('Worker', FailingWorker)
  const bytes = new TextEncoder().encode(JSON.stringify({ plugins: [{ npm: 'example-plugin' }] }))
  expect(await parseCatalogOffThread(bytes, new AbortController().signal)).toHaveLength(1)
  await expect(parseCatalogOffThread(new TextEncoder().encode('{bad'), new AbortController().signal)).rejects.toThrow()
})


it('下载目录后实际使用后台解析', async () => {
  const bytes = new TextEncoder().encode(JSON.stringify({ plugins: [{ npm: 'example-plugin' }] }))
  let done = false
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => ({
    read: () => Promise.resolve(done ? { done: true } : (done = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {},
  }) } }))
  const sent = vi.fn()
  class FakeWorker {
    onmessage: ((event: MessageEvent) => void) | null = null
    onerror: (() => void) | null = null
    terminate = vi.fn()
    postMessage(buffer: ArrayBuffer) {
      sent()
      queueMicrotask(() => this.onmessage?.({ data: { ok: true, entries: parseCatalogData(new Uint8Array(buffer)) } } as MessageEvent))
    }
  }
  vi.stubGlobal('Worker', FakeWorker)
  expect(await fetchCatalog(new AbortController().signal)).toHaveLength(1)
  expect(sent).toHaveBeenCalledOnce()
})


it('显示目录提供的下载量及统计区间，并按下载量排序', async () => {
  const bytes = new TextEncoder().encode(JSON.stringify({ plugins: [
    { name: '低下载', npm: 'low-plugin', downloads: 3, downloadsStart: '2026-09-29', downloadsEnd: '2026-10-28' },
    { name: '无数据', npm: 'unknown-plugin' },
    { name: '高下载', npm: 'high-plugin', downloads: 2000 },
  ] }))
  let complete = false
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, body: { getReader: () => ({
    read: () => Promise.resolve(complete ? { done: true } : (complete = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {},
  }) } }))
  render(<MobileMarket locked={false} onInstall={vi.fn()} />)
  fireEvent.click(screen.getByRole('button', { name: '加载插件目录' }))
  await screen.findByText('低下载')
  expect(screen.getByText(/2026-09-29/)).toHaveTextContent('下载量：3')
  expect(screen.getByText('下载量暂无数据')).toBeVisible()
  fireEvent.change(screen.getByRole('combobox', { name: '排序' }), { target: { value: 'downloads' } })
  const items = screen.getAllByRole('listitem')
  expect(items[0]).toHaveTextContent('高下载')
  expect(items[2]).toHaveTextContent('无数据')
})

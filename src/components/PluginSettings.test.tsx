import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { PluginSettings } from './PluginSettings'
import { createBrowserBridge } from '../platform/browser'
import { saveLanguage } from '../i18n'
import type { PluginCatalog, RuntimeState } from '../platform/types'

const runtime: RuntimeState = { phase: 'error', installedVersion: '0.1.10', architecture: 'arm64-v8a', updateAvailable: false, downloadedBytes: 0, totalBytes: 0, runnerAvailable: true }
const catalog: PluginCatalog = { plugins: [
  { id: '@deepseek-ai/dsh-base', file: 'cordis.patch.yml', version: '1.0.0', enabled: true, protected: true, official: true, installed: true, readable: true, children: [] },
  { id: 'example-plugin', file: 'config/plugins.yml', version: '1.0.0', rollback: '0.9.0', enabled: true, protected: false, official: false, installed: true, readable: true, children: [{ id: 'optional', name: 'example-plugin/optional', enabled: true, effectiveEnabled: true, protected: false }] },
] }
function setup(state = runtime, groups: PluginCatalog = catalog) {
  const bridge = { ...createBrowserBridge(), managePlugins: vi.fn().mockResolvedValue(groups), stopRuntime: vi.fn().mockResolvedValue({ ...runtime, phase: 'ready' }) }
  render(<PluginSettings bridge={bridge} runtime={state} onBack={vi.fn()} />)
  return bridge
}
beforeEach(() => { saveLanguage('zh-CN') })
describe('应用外层插件管理', () => {
  it('手机目录安装经原生插件管理入口执行', async () => {
    const bytes = new TextEncoder().encode(JSON.stringify({ plugins: [{ name: '目录示例', npm: 'catalog-example' }] }))
    let done = false
    const mock = vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true, body: { getReader: () => ({
      read: () => Promise.resolve(done ? { done: true } : (done = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {},
    }) } } as unknown as Response)
    try {
      const bridge = setup()
      await screen.findByText('example-plugin')
      fireEvent.click(screen.getByRole('tab', { name: '浏览商店' }))
      fireEvent.click(screen.getByRole('button', { name: '加载插件目录' }))
      await screen.findByText('目录示例')
      fireEvent.click(screen.getByRole('button', { name: '安装' }))
      fireEvent.click(screen.getByRole('button', { name: '确认安装' }))
      await waitFor(() => expect(bridge.managePlugins).toHaveBeenCalledWith({ operation: 'import', source: 'catalog-example' }))
    } finally { mock.mockRestore() }
  })
  it('社区目录标记已安装插件，并跳转到同一份插件管理记录', async () => {
    const bytes = new TextEncoder().encode(JSON.stringify({ plugins: [{ name: '示例商店条目', npm: 'example-plugin' }] }))
    let done = false
    const mock = vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true, body: { getReader: () => ({
      read: () => Promise.resolve(done ? { done: true } : (done = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {},
    }) } } as unknown as Response)
    try {
      setup()
      await screen.findByText('example-plugin')
      fireEvent.click(screen.getByRole('tab', { name: '浏览商店' }))
      fireEvent.click(screen.getByRole('button', { name: '加载插件目录' }))
      await screen.findByText('示例商店条目')
      const market = within(screen.getByRole('region', { name: '手机插件目录' }))
      expect(market.getByText('已安装 · 1.0.0')).toHaveTextContent('1.0.0')
      fireEvent.click(market.getByRole('button', { name: '管理' }))
      const entry = screen.getByText('config/plugins.yml · 1.0.0').closest('details')
      expect(entry).toHaveAttribute('open')
      expect(screen.getByRole('searchbox', { name: '搜索插件' })).toHaveValue('example-plugin')
    } finally { mock.mockRestore() }
  })
  it('目录安装失败时在目录内显示原生兼容检查结果', async () => {
    const bytes = new TextEncoder().encode(JSON.stringify({ plugins: [{ name: '目录示例', npm: 'catalog-example' }] }))
    let done = false
    const mock = vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true, body: { getReader: () => ({
      read: () => Promise.resolve(done ? { done: true } : (done = true, { done: false, value: bytes })), releaseLock: () => {}, cancel: () => {},
    }) } } as unknown as Response)
    try {
      const failure = Object.assign(new Error('catalog-example dsh=0.2.1'), { code: 'PLUGIN_ENGINE_SCAN_INCOMPLETE' })
      const bridge = { ...createBrowserBridge(), managePlugins: vi.fn().mockResolvedValueOnce(catalog).mockRejectedValueOnce(failure) }
      render(<PluginSettings bridge={bridge} runtime={runtime} onBack={() => {}} />)
      await screen.findByText('example-plugin')
      fireEvent.click(screen.getByRole('tab', { name: '浏览商店' }))
      fireEvent.click(screen.getByRole('button', { name: '加载插件目录' }))
      await screen.findByText('目录示例')
      fireEvent.click(screen.getByRole('button', { name: '安装' }))
      fireEvent.click(screen.getByRole('button', { name: '确认安装' }))
      const market = screen.getByRole('region', { name: '手机插件目录' })
      const error = await within(market).findByRole('alert')
      expect(error).toHaveTextContent('仍有旧版本未检查')
      expect(error).toHaveTextContent('catalog-example dsh=0.2.1')
    } finally { mock.mockRestore() }
  })
  it('商店请求需确认，并且安装成功后才清除', async () => {
    const proposal = { id: '00000000-0000-0000-0000-000000000001', packageName: 'example-market-plugin', createdAt: 1000, kind: 'install' as const, suggestedVersion: null }
    const bridge = { ...createBrowserBridge(), managePlugins: vi.fn().mockResolvedValue(catalog), stopRuntime: vi.fn(),
      getMarketProposal: vi.fn().mockResolvedValueOnce(proposal).mockResolvedValue(null), dismissMarketProposal: vi.fn().mockResolvedValue(true) }
    render(<PluginSettings bridge={bridge} runtime={runtime} onBack={() => {}} />)
    await screen.findByText('example-market-plugin')
    expect(bridge.managePlugins).not.toHaveBeenCalledWith({ operation: 'import', source: proposal.packageName })
    fireEvent.click(screen.getByRole('button', { name: '确认安装请求' }))
    await waitFor(() => expect(bridge.managePlugins).toHaveBeenCalledWith({ operation: 'import', source: proposal.packageName }))
    await waitFor(() => expect(bridge.dismissMarketProposal).toHaveBeenCalledWith(proposal.id))
  })
  it('商店更新需确认并显示建议版本，调用受控更新', async () => {
    const proposal = { id: '00000000-0000-0000-0000-000000000003', packageName: 'example-plugin', createdAt: 1000, kind: 'update' as const, suggestedVersion: '1.2.3' }
    const bridge = { ...createBrowserBridge(), managePlugins: vi.fn().mockResolvedValue(catalog), getMarketProposal: vi.fn().mockResolvedValueOnce(proposal).mockResolvedValue(null), dismissMarketProposal: vi.fn().mockResolvedValue(true) }
    render(<PluginSettings bridge={bridge} runtime={runtime} onBack={() => {}} />)
    expect(await screen.findByText('1.2.3')).toBeInTheDocument()
    expect(screen.getByText(/可能与商店建议版本不同/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '确认更新请求' }))
    await waitFor(() => expect(bridge.managePlugins).toHaveBeenCalledWith({ operation: 'update', id: 'example-plugin' }))
    await waitFor(() => expect(bridge.dismissMarketProposal).toHaveBeenCalledWith(proposal.id))
  })
  it('运行中不能确认更新，更新失败时保留请求', async () => {
    const proposal = { id: '00000000-0000-0000-0000-000000000004', packageName: 'example-plugin', createdAt: 1000, kind: 'update' as const, suggestedVersion: null }
    const bridge = { ...createBrowserBridge(), managePlugins: vi.fn().mockResolvedValueOnce(catalog).mockRejectedValueOnce(new Error('failed')), stopRuntime: vi.fn().mockResolvedValue({ ...runtime, phase: 'ready' }), getMarketProposal: vi.fn().mockResolvedValue(proposal), dismissMarketProposal: vi.fn() }
    render(<PluginSettings bridge={bridge} runtime={{ ...runtime, phase: 'running' }} onBack={() => {}} />)
    const confirm = await screen.findByRole('button', { name: '确认更新请求' })
    expect(confirm).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '停止运行环境' }))
    await waitFor(() => expect(confirm).toBeEnabled())
    fireEvent.click(confirm)
    await screen.findByRole('alert')
    expect(bridge.dismissMarketProposal).not.toHaveBeenCalled()
  })
  it('安装失败时保留商店请求', async () => {
    const proposal = { id: '00000000-0000-0000-0000-000000000002', packageName: 'example-market-plugin', createdAt: 1000, kind: 'install' as const, suggestedVersion: null }
    const bridge = { ...createBrowserBridge(), managePlugins: vi.fn().mockResolvedValueOnce(catalog).mockRejectedValueOnce(new Error('failed')),
      getMarketProposal: vi.fn().mockResolvedValue(proposal), dismissMarketProposal: vi.fn() }
    render(<PluginSettings bridge={bridge} runtime={runtime} onBack={() => {}} />)
    await screen.findByText('example-market-plugin')
    fireEvent.click(screen.getByRole('button', { name: '确认安装请求' }))
    await screen.findByRole('alert')
    expect(bridge.dismissMarketProposal).not.toHaveBeenCalled()
    expect(screen.getByText('example-market-plugin')).toBeInTheDocument()
  })
  it('Harness 错误状态下仍可展开文件和禁用子插件', async () => {
    const bridge = setup()
    const summary = await screen.findByText('example-plugin')
    fireEvent.click(summary)
    expect(screen.getByRole('region', { name: '官方插件' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: '第三方插件' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: '启用子插件 optional' }))
    await waitFor(() => expect(bridge.managePlugins).toHaveBeenCalledWith({ operation: 'child', id: 'example-plugin', childId: 'optional', enabled: false }))
    expect(await screen.findByText('插件设置已保存，下次启动生效')).toBeInTheDocument()
  })
  it('运行中拒绝修改，显式停止后允许管理', async () => {
    const bridge = setup({ ...runtime, phase: 'running' })
    fireEvent.click(await screen.findByText('example-plugin'))
    expect(screen.getByRole('checkbox', { name: '启用文件 example-plugin' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '停止运行环境' }))
    await waitFor(() => expect(bridge.stopRuntime).toHaveBeenCalledOnce())
    await waitFor(() => expect(screen.getByRole('checkbox', { name: '启用文件 example-plugin' })).toBeEnabled())
  })
  it('更新失败后显示受控错误，保留列表并允许重试', async () => {
    const bridge = setup()
    fireEvent.click(await screen.findByText('example-plugin'))
    bridge.managePlugins.mockRejectedValueOnce({ code: 'PLUGIN_UPDATE_FAILED', message: 'private debug details' })
    const update = screen.getAllByRole('button', { name: '更新所属插件包' }).find(button => !(button as HTMLButtonElement).disabled)!
    fireEvent.click(update)
    expect(await screen.findByRole('alert')).toHaveTextContent('已保留原版本')
    expect(screen.queryByText('private debug details')).not.toBeInTheDocument()
    fireEvent.click(update)
    expect(await screen.findByText('插件包已更新，下次启动生效')).toBeInTheDocument()
  })
  it('插件平台不兼容时说明原因并保留原版本', async () => {
    const bridge = setup()
    fireEvent.click(await screen.findByText('example-plugin'))
    bridge.managePlugins.mockRejectedValueOnce({ code: 'PLUGIN_PLATFORM_UNSUPPORTED', message: 'example-plugin' })
    const update = screen.getAllByRole('button', { name: '更新所属插件包' }).find(button => !(button as HTMLButtonElement).disabled)!
    fireEvent.click(update)
    expect(await screen.findByRole('alert')).toHaveTextContent('插件或依赖声明不支持当前运行环境的平台或处理器，已保留原版本')
  })
  it('英文界面翻译管理操作', async () => {
    saveLanguage('en')
    setup()
    fireEvent.click(await screen.findByText('example-plugin'))
    expect(screen.getByRole('heading', { name: 'Official plugins' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Enable plugin optional' })).toBeInTheDocument()
  })
  it('显示运行时更新后的插件迁移结果', async () => {
    setup(runtime, {
      ...catalog,
      recovery: { status: 'partial', restored: 1, relinked: 2, missing: 1, failed: 0, refused: 0 },
    })
    expect(await screen.findByText('插件已部分恢复')).toBeInTheDocument()
    expect(screen.getByText('已恢复 1 个配置、重新链接 2 个插件。 缺失 1 个，失败 0 个，拒绝 0 个。')).toBeInTheDocument()
  })
})
describe('插件导入与回滚', () => {
  it('从发现入口安装市场时使用现有受控导入', async () => {
    const bridge = setup()
    fireEvent.click(screen.getByRole('tab', { name: '浏览商店' }))
    const button = await screen.findByRole('button', { name: '安装插件市场' })
    await waitFor(() => expect(button).toBeEnabled())
    fireEvent.click(button)
    await waitFor(() => expect(bridge.managePlugins).toHaveBeenCalledWith({ operation: 'import', source: 'dshmarket' }))
  })
  it('按包名导入：来源为空时按钮禁用，成功后提示并清空输入', async () => {
    const bridge = setup()
    const button = await screen.findByRole('button', { name: '导入' })
    expect(button).toBeDisabled()
    fireEvent.change(screen.getByRole('textbox', { name: '插件来源' }), { target: { value: 'dsh-plugin-example' } })
    expect(button).toBeEnabled()
    fireEvent.click(button)
    await waitFor(() => expect(bridge.managePlugins).toHaveBeenCalledWith({ operation: 'import', source: 'dsh-plugin-example' }))
    expect(await screen.findByText('插件已导入，下次启动生效')).toBeInTheDocument()
    // 成功后清空：同一条来源再点一次会命中既有安装，留着输入更容易被误解成"装了两遍"。
    expect(screen.getByRole('textbox', { name: '插件来源' })).toHaveValue('')
  })
  it('地址形态可带期望包名，随请求一起送给原生', async () => {
    const bridge = setup()
    await screen.findByRole('button', { name: '导入' })
    fireEvent.change(screen.getByRole('textbox', { name: '插件来源' }), { target: { value: 'https://example.com/plugin.tgz' } })
    fireEvent.change(screen.getByRole('textbox', { name: '预期包名' }), { target: { value: 'dsh-plugin-example' } })
    fireEvent.click(screen.getByRole('button', { name: '导入' }))
    await waitFor(() => expect(bridge.managePlugins).toHaveBeenCalledWith({ operation: 'import', source: 'https://example.com/plugin.tgz', id: 'dsh-plugin-example' }))
  })
  it('来源不合格时显示受控文案，不回显用户填的内容', async () => {
    const bridge = setup()
    const button = await screen.findByRole('button', { name: '导入' })
    fireEvent.change(screen.getByRole('textbox', { name: '插件来源' }), { target: { value: 'http://user:secret-payload@example.com/plugin.tgz' } })
    bridge.managePlugins.mockRejectedValueOnce({ code: 'PLUGIN_SOURCE_INVALID', message: '' })
    fireEvent.click(button)
    expect(await screen.findByRole('alert')).toHaveTextContent('这个来源地址不可用')
    expect(screen.queryByText(/secret-payload/)).not.toBeInTheDocument()
  })
  it('缺 git 与探测未确认是两条不同的提示，不许混为一谈', async () => {
    const bridge = setup()
    const button = await screen.findByRole('button', { name: '导入' })
    fireEvent.change(screen.getByRole('textbox', { name: '插件来源' }), { target: { value: 'git+https://example.com/repo.git' } })
    bridge.managePlugins.mockRejectedValueOnce({ code: 'PLUGIN_GIT_MISSING', message: '' })
    fireEvent.click(button)
    expect(await screen.findByRole('alert')).toHaveTextContent('当前运行时没有 git')
    bridge.managePlugins.mockRejectedValueOnce({ code: 'PLUGIN_GIT_UNVERIFIED', message: '' })
    fireEvent.click(button)
    expect(await screen.findByRole('alert')).toHaveTextContent('暂时无法确认运行环境是否支持 git')
  })
  it('回滚：有上一版时可点，提示上一版版本号并送回原生', async () => {
    const bridge = setup()
    const thirdParty = within(await screen.findByRole('region', { name: '第三方插件' }))
    fireEvent.click(thirdParty.getByText('example-plugin'))
    expect(thirdParty.getByText('可回滚到上一版本 0.9.0。')).toBeInTheDocument()
    fireEvent.click(thirdParty.getByRole('button', { name: '回滚到上一版本' }))
    await waitFor(() => expect(bridge.managePlugins).toHaveBeenCalledWith({ operation: 'rollback', id: 'example-plugin' }))
    expect(await screen.findByText('已回滚到上一版本，下次启动生效')).toBeInTheDocument()
  })
  it('没有上一版时按钮禁用并说明原因；受保护包不出现回滚按钮', async () => {
    const bridge = setup(runtime, { plugins: [
      ...catalog.plugins,
      { id: 'never-updated', file: 'config/other.yml', version: '1.2.3', rollback: null, enabled: true, protected: false, official: false, installed: true, readable: true, children: [] },
    ] })
    const thirdParty = within(await screen.findByRole('region', { name: '第三方插件' }))
    fireEvent.click(thirdParty.getByText('never-updated'))
    const blocks = thirdParty.getAllByRole('button', { name: '回滚到上一版本' })
    expect(blocks.at(-1)).toBeDisabled()
    expect(thirdParty.getByText('没有可回滚的上一版本：每个插件只保留最近一次更新前的那一版。')).toBeInTheDocument()
    // 受保护的核心配置文件只能整体启用/禁用，不参与回滚：它的回滚按钮始终禁用。
    const official = within(screen.getByRole('region', { name: '官方插件' }))
    fireEvent.click(official.getByText('@deepseek-ai/dsh-base'))
    expect(official.getByRole('button', { name: '回滚到上一版本' })).toBeDisabled()
    expect(bridge.managePlugins).toHaveBeenCalledWith({ operation: 'list' })
  })
})
describe('插件双状态呈现与补充链接', () => {
  it('显示当前环境与手机保留区双层状态，并提供立即补充链接入口', async () => {
    const customCatalog: PluginCatalog = {
      plugins: [
        {
          id: 'unlinked-plugin',
          file: 'config/unlinked.yml',
          version: null,
          enabled: true,
          protected: false,
          official: false,
          installed: false,
          readable: true,
          preserved: { exists: true, version: '2.1.0', transaction: 'tx_99', isLinked: false },
          children: [],
        },
      ],
    }
    const bridge = setup(runtime, customCatalog)
    // catalog 由 managePlugins 异步返回，卡片要等第一轮 effect 落盘后才能查询。
    const card = within((await screen.findByText('unlinked-plugin')).closest('details')!)
    expect(card.getByText('当前环境：未链接', { exact: false })).toBeInTheDocument()
    expect(card.getByText('手机保留区：v2.1.0', { exact: false })).toBeInTheDocument()
    expect(card.getByText('待补充链接', { exact: false })).toBeInTheDocument()

    // 展开卡片并点击立即补充链接
    fireEvent.click(screen.getByText('unlinked-plugin'))
    const relinkButton = screen.getByRole('button', { name: '立即补充链接' })
    fireEvent.click(relinkButton)
    await waitFor(() => expect(bridge.managePlugins).toHaveBeenCalledWith({ operation: 'repair' }))
  })
  it('原生扩展拦截回显 PLUGIN_NATIVE_UNSUPPORTED 受控说明', async () => {
    const bridge = setup()
    fireEvent.click(await screen.findByText('example-plugin'))
    bridge.managePlugins.mockRejectedValueOnce({ code: 'PLUGIN_NATIVE_UNSUPPORTED', message: '' })
    const update = screen.getAllByRole('button', { name: '更新所属插件包' }).find(button => !(button as HTMLButtonElement).disabled)!
    fireEvent.click(update)
    expect(await screen.findByRole('alert')).toHaveTextContent('插件或依赖包含移动端受限的原生扩展')
  })
  it('Node 版本不兼容时指出依赖要求并保留旧版本', async () => {
    const bridge = setup()
    fireEvent.click(await screen.findByText('example-plugin'))
    bridge.managePlugins.mockRejectedValueOnce({ code: 'PLUGIN_NODE_UNSUPPORTED', message: 'new-node-dep' })
    const update = screen.getAllByRole('button', { name: '更新所属插件包' }).find(button => !(button as HTMLButtonElement).disabled)!
    fireEvent.click(update)
    expect(await screen.findByRole('alert')).toHaveTextContent('插件或依赖要求的 Node 版本与当前运行环境不兼容')
  })
  it('搜索与标签页过滤正确筛选条目', async () => {
    const customCatalog: PluginCatalog = {
      plugins: [
        { id: '@deepseek-ai/dsh-base', file: 'cordis.patch.yml', version: '1.0.0', enabled: true, protected: true, official: true, installed: true, readable: true, children: [] },
        { id: 'plugin-alpha', file: 'config/alpha.yml', version: '1.0.0', enabled: true, protected: false, official: false, installed: true, readable: true, children: [{ id: 'child-one', name: 'Alpha Feature', enabled: true, effectiveEnabled: true, protected: false }] },
        { id: 'plugin-beta', file: 'config/beta.yml', version: null, enabled: false, protected: false, official: false, installed: false, readable: true, preserved: { exists: true, version: '1.5.0', transaction: 'tx_beta', isLinked: false }, children: [] },
      ],
    }
    setup(runtime, customCatalog)
    await screen.findByText('plugin-alpha')
    // 搜索关键字
    const searchInput = screen.getByRole('searchbox', { name: '搜索插件' })
    fireEvent.change(searchInput, { target: { value: 'Alpha Feature' } })
    expect(screen.getByText('plugin-alpha')).toBeInTheDocument()
    expect(screen.queryByText('plugin-beta')).not.toBeInTheDocument()

    // 清空搜索并切换到「待补充链接」Tab
    fireEvent.change(searchInput, { target: { value: '' } })
    fireEvent.click(screen.getByRole('tab', { name: '待补充链接' }))
    expect(screen.queryByText('plugin-alpha')).not.toBeInTheDocument()
    expect(screen.getByText('plugin-beta')).toBeInTheDocument()
  })
  it('清理历史孤立副本：RecoveryNotice 触发 prune 请求', async () => {
    const bridge = setup(runtime, {
      plugins: catalog.plugins,
      recovery: { status: 'partial', restored: 0, relinked: 1, missing: 1, failed: 0, refused: 0 },
    })
    const pruneButton = await screen.findByRole('button', { name: '清理历史孤立副本' })
    fireEvent.click(pruneButton)
    await waitFor(() => expect(bridge.managePlugins).toHaveBeenCalledWith({ operation: 'prune' }))
    expect(await screen.findByText('已清理历史孤立副本，释放了存储空间')).toBeInTheDocument()
  })
})

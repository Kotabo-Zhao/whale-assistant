import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { arriveAtSettingsHome, expandSettingsGroup } from './__tests__/appTestHarness'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { beforeEachAppTest, bridge, shizuku } from './__tests__/appTestHarness'

vi.mock('./platform/native', () => ({ runtimeBridge: bridge }))
vi.mock('./components/TerminalPanel', () => ({
  TerminalPanel: () => <div data-testid="terminal-panel" />,
}))

import { App } from './App'

/**
 * 设置首页：按用途分组的入口，以及分类芯片的筛选。
 *
 * 为什么要有这一组：入口按用途细分模型、外观、设备、运行、插件、文件、版本和诊断，
 * 芯片只决定这一屏显示哪些入口（不重读设置、不改任何状态），点下去走的还是同一条路。
 */
/**
 * 设置首页出现的等待上限。
 *
 * 与 `src/__tests__/appTestHarness.ts:626` 的 `SETTINGS_NAV_TIMEOUT_MS` 同一个理由：
 * `findBy*` 默认只等 1 s，而启动链把视图推到设置首页要将近 1 s（并行跑更慢），
 * 于是失败信息看起来像「首页没渲染」，实际只是等待上限太短。放宽的是等待，不是断言。
 */
const SETTINGS_HOME_TIMEOUT_MS = 5_000

describe('设置首页：分组与分类筛选', () => {
  beforeEach(beforeEachAppTest)

  afterEach(() => {
    vi.restoreAllMocks()
  })

  /**
   * 打开设置首页：**不需要点任何入口按钮**。
   *
   * 实测（见 `src/__tests__/renderProfile.test.tsx:170`、`renderCounts.test.tsx:279` 的注释）：
   * 夹具里开着自动启动，启动链跑完会把视图推到设置首页；而启动链没跑完时同步查询必然失败。
   * 所以这里只做两件事：等自动启动发生，再有界等待首页标题出现。
   * 刻意不去点「打开应用设置」——那个按钮不是这条路径上的必经入口（点它反而找不到，见本轮踩坑）。
   */
  async function openSettingsHome(): Promise<void> {
    render(<App />)
  await arriveAtSettingsHome()
      await screen.findByRole('heading', { name: '设置' }, { timeout: SETTINGS_HOME_TIMEOUT_MS })
  }

  it('首页只展开助手设置，低频设置按目的聚合', async () => {
    await openSettingsHome()
    expect(screen.getByRole('button', { name: /模型与密钥/ })).toBeVisible()
    expect(screen.queryByRole('button', { name: /设备 Shell 设置/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Ubuntu 运行时/ })).toBeNull()
    expandSettingsGroup('运行与存储')
    expect(screen.getByRole('button', { name: /Ubuntu 运行时/ })).toBeVisible()
    expect(screen.queryByRole('button', { name: /模型与密钥/ })).toBeNull()
    expandSettingsGroup('设备与诊断')
    expect(screen.getByRole('button', { name: /设备 Shell 设置/ })).toBeVisible()
    expect(screen.getByRole('button', { name: /诊断与日志/ })).toBeVisible()
  })

  it.each([
    { badge: '未安装', state: { ...shizuku, installed: false, running: false } },
    { badge: '待授权', state: { ...shizuku } },
    { badge: '已授权', state: { ...shizuku, permission: 'granted' } },
    { badge: '已连接', state: { ...shizuku, permission: 'granted', connected: true } },
  ])('设备 Shell 设置入口显示 Shizuku 状态：$badge', async ({ badge, state }) => {
    bridge.getShizukuState.mockResolvedValue(state)
    await openSettingsHome()

    expandSettingsGroup('设备与诊断')
    const entry = screen.getByRole('button', { name: /设备 Shell 设置/ })
    expect(within(entry).getByText(`Shizuku 授权与设备命令 · ${badge}`)).toBeVisible()
  })

  it('分类切换只影响展示，设备设置可进入原有页面', async () => {
    await openSettingsHome()
    const reads = bridge.getSettings.mock.calls.length
    expandSettingsGroup('设备与诊断')
    expect(bridge.getSettings.mock.calls.length).toBe(reads)
    expect(bridge.saveSettings).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /自动化设置/ }))
    expect(await screen.findByRole('heading', { name: '自动化能力' })).toBeVisible()
  })

  it('「版本管理」是一级页：入口进得去，返回键回设置首页', async () => {
    await openSettingsHome()

    expandSettingsGroup('运行与存储')
    const versionsGroup = screen.getByRole('region', { name: '运行与存储' })
    fireEvent.click(within(versionsGroup).getByRole('button', { name: /版本管理/ }))

    // 版本面板已经不在「运行与后台」页里：这一屏自己是标题，页内有版本列表与两个操作。
    expect(await screen.findByRole('heading', { name: '版本管理' })).toBeVisible()
    expect(await screen.findByRole('heading', { name: '运行时版本管理' })).toBeVisible()

    fireEvent.click(screen.getByRole('button', { name: '返回设置' }))
    expect(await screen.findByRole('heading', { name: '设置' })).toBeVisible()
  })

  it('「文件管理」是一级页：投递区与共享目录在同一个浏览器里', async () => {
    await openSettingsHome()

    expandSettingsGroup('运行与存储')
    const filesGroup = screen.getByRole('region', { name: '运行与存储' })
    fireEvent.click(within(filesGroup).getByRole('button', { name: /文件管理/ }))

    // 进得去，而且是一个浏览器：标题是这一页自己的，投递区与共享目录都在里面。
    expect(await screen.findByRole('heading', { level: 1, name: '文件管理' })).toBeVisible()
    expect(screen.getByRole('tablist')).toBeVisible()
    expect(screen.getByRole('region', { name: '目录' })).toBeVisible()
  })

  it('切到英文后整屏入口都是英文', async () => {
    // 入口表的标题与说明是裸字符串，仓库外的 locales-check.cjs 看不见它们，
    // 所以用这条用例兜底：漏了词条这里会直接显示中文，一眼能看出来。
    window.localStorage.setItem('dsh-mobile-language-v1', 'en')
    render(<App />)
  await arriveAtSettingsHome()

    await screen.findByRole('button', { name: 'Runtime and storage' })
    expandSettingsGroup('Runtime and storage')
    const runtimeGroup = screen.getByRole('region', { name: 'Runtime and storage' })
    expect(within(runtimeGroup).getByRole('button', { name: /Ubuntu runtime/ })).toBeVisible()
    expect(within(runtimeGroup).getByRole('button', { name: /Version management/ })).toBeVisible()
    expect(within(runtimeGroup).getByRole('button', { name: /File management/ })).toBeVisible()
    expect(runtimeGroup.textContent ?? '').not.toMatch(/[\u4e00-\u9fff]/)
    expandSettingsGroup('Assistant and appearance')
    expect(screen.getByRole('button', { name: /^Theme/ })).toBeVisible()
    expandSettingsGroup('Device and diagnostics')
    expect(screen.getByText('Device Shell settings')).toBeVisible()
  })

  it('搜索能直接找到折叠的助手偏好，资料管理只打开主资料库', async () => {
    await openSettingsHome()
    expandSettingsGroup('设备与诊断')
    const query = screen.getByRole('searchbox', { name: '搜索设置' })
    fireEvent.change(query, { target: { value: '通知' } })
    fireEvent.click(screen.getByRole('button', { name: '搜索入口' }))
    fireEvent.click(screen.getByRole('button', { name: /助手偏好/ }))
    fireEvent.click(screen.getByRole('button', { name: '打开记忆' }))
    expect(await screen.findByRole('tab', { name: '资料' })).toHaveAttribute('aria-selected', 'true')
  })

  it('按功能词搜索过滤入口，点搜索图标或回车才生效，支持清空及空结果提示', async () => {
    await openSettingsHome()

    const searchInput = screen.getByRole('searchbox', { name: '搜索设置' })
    expect(searchInput).toBeVisible()

    // 只输入不确认：列表不筛选——输入法组合中途不能被列表刷新打断。
    fireEvent.change(searchInput, { target: { value: '主题' } })
    expect(screen.queryByRole('status')).toBeNull()
    expect(screen.getByRole('region', { name: '助手与外观' })).toBeVisible()

    // 点搜索图标确认：应只保留外观与显示分组，隐藏其他分组如模型连接
    fireEvent.click(screen.getByRole('button', { name: '搜索入口' }))
    expect(screen.getByRole('status')).toHaveTextContent('找到 1 个匹配的入口')
    expect(screen.getByRole('region', { name: '助手与外观' })).toBeVisible()
    expect(screen.getByRole('button', { name: /主题/ })).toBeVisible()
    expect(screen.queryByRole('button', { name: /模型与密钥/ })).toBeNull()

    // 点击清空按钮：草稿与已确认词一起清掉，恢复全部入口
    const clearButton = screen.getByRole('button', { name: '清空查找词' })
    fireEvent.click(clearButton)
    expect(searchInput).toHaveValue('')
    expect(screen.queryByRole('status')).toBeNull()
    expect(screen.getByRole('region', { name: '助手与外观' })).toBeVisible()

    // 回车确认无匹配词：显示空提示
    fireEvent.change(searchInput, { target: { value: '不存在的设置项xyz' } })
    fireEvent.keyDown(searchInput, { key: 'Enter' })
    expect(screen.getByRole('status')).toHaveTextContent('没有匹配的入口')
    expect(screen.queryByRole('button', { name: /模型与密钥/ })).toBeNull()
  })

  it('输入法组合中的回车只确认候选词，不提交搜索', async () => {
    await openSettingsHome()

    const searchInput = screen.getByRole('searchbox', { name: '搜索设置' })

    // 组合开始 → 输入半成品 → 回车。中文输入法里这个回车是「选中候选词」，
    // 不是「提交搜索」：吞掉它会让候选词无法确认，正好破坏本组件想保护的输入。
    fireEvent.compositionStart(searchInput)
    fireEvent.change(searchInput, { target: { value: '主' } })
    fireEvent.keyDown(searchInput, { key: 'Enter' })
    expect(screen.queryByRole('status')).toBeNull()
    expect(screen.getByRole('region', { name: '助手与外观' })).toBeVisible()

    // 组合结束（候选词落定）后再回车，这才该提交。
    fireEvent.compositionEnd(searchInput, { target: { value: '主题' } })
    fireEvent.keyDown(searchInput, { key: 'Enter' })
    expect(screen.getByRole('status')).toHaveTextContent('找到 1 个匹配的入口')
  })

  it.each([false, true])('原生直接改 DOM 值后仍能搜索（先失焦：%s）', async blurBeforeSearch => {
    await openSettingsHome()

    const searchInput = screen.getByRole<HTMLInputElement>('searchbox', { name: '搜索设置' })
    // Capacitor 的 captureInput 兼容路径可能只写 DOM value，不派发 input/change，
    // 这正是 React 受控状态与用户眼前文字不同步的情况。
    if (blurBeforeSearch) fireEvent.compositionStart(searchInput)
    searchInput.value = '主题'
    expect(searchInput).toHaveValue('主题')
    if (blurBeforeSearch) fireEvent.blur(searchInput)

    fireEvent.click(screen.getByRole('button', { name: '搜索入口' }))
    expect(screen.getByRole('status')).toHaveTextContent('找到 1 个匹配的入口')
    expect(searchInput).toHaveValue('主题')
  })

  /**
   * 回归：设置首页的「打开 Harness」必须只把会话标识递给桥。
   *
   * 曾经的写法是 `onClick={onLaunch}`：React 把合成事件当成 `sessionId` 传下去，
   * 事件对象里存着指回 Fiber 的循环引用，Capacitor 过桥序列化时抛
   * `TypeError: Converting circular structure to JSON`。调用就此断在半路，
   * `busy` 停在 `'launch'`，界面卡在「正在进入对话」直到看门狗超时——实体机上表现为
   * 「从 Harness 返回后再点回来一直转圈」。
   */
  it('点「打开 Harness」不把点击事件当会话标识递给桥', async () => {
    await openSettingsHome()

    // 启动链自己已经开过一次，先清空，才能看清这一次点击递了什么。
    bridge.openHarness.mockClear()

    expandSettingsGroup('运行与存储')
    const launchButton = screen.getByRole('button', { name: /打开 Harness/ })
    // 忙锁没释放时按钮禁用，点了也只会在 JS 里早退，测不到这条路径。
    await waitFor(() => expect(launchButton).toBeEnabled())

    fireEvent.click(launchButton)

  await arriveAtSettingsHome()
    expect(bridge.openHarness).toHaveBeenCalledWith(undefined)
    expect(bridge.openHarness.mock.calls[0]).toHaveLength(1)
    expect(bridge.openHarness.mock.calls[0][0]).not.toBeInstanceOf(Object)
  })

  it('组合中失焦也能恢复提交能力', async () => {
    await openSettingsHome()

    const searchInput = screen.getByRole('searchbox', { name: '搜索设置' })

    // 移动端软键盘选词或直接收起键盘时会丢 compositionend：不靠失焦兜底，
    // `isComposing` 会永久卡在 true，搜索框从此再也提交不了（静默失效）。
    fireEvent.compositionStart(searchInput)
    fireEvent.change(searchInput, { target: { value: '主题' } })
    fireEvent.blur(searchInput)
    fireEvent.keyDown(searchInput, { key: 'Enter' })
    expect(screen.getByRole('status')).toHaveTextContent('找到 1 个匹配的入口')
  })
})

import { useEffect, useState } from 'react'
import { readInterfaceStyle, saveInterfaceStyle } from '../theme'
import grokMark from '../assets/whale-grok-mark.png'
import whaleChanAvatar from '../assets/whale-chan-avatar.png'
import usagiAvatar from '../assets/usagi-avatar.png'
import { Moon, Palette, Sun, SunMoon, RotateCcw } from 'lucide-react'
import { t } from '../i18n'
import { applyTextColor, applyTheme, clearCustomAccentColor, clearCustomTextColor, readCustomAccentColor, readCustomTextColor, readThemeMode, saveAccentMode, saveAccentSource, saveCustomAccentColor, saveCustomTextColor, saveThemeMode, useAccentMode, useAccentSource, useResolvedTheme, useThemeMode } from '../theme'
import { clearAdaptiveAccent, extractAccentFromBackground, readAdaptiveAccent, saveAdaptiveAccent, type AccentSource, type AdaptiveAccent } from '../adaptiveAccent'
import type { AccentMode, ThemeMode } from '../theme'
import { applyCard, clearBackground, DEFAULT_CARD, hasBackgroundFlag, readBackground, readCard, saveBackground, saveCard, type CardAppearance } from '../appearance'

// 顺序固定为「跟随系统 / 浅色 / 深色」：默认项排在最前，避免让人以为必须二选一。
const OPTIONS: readonly { value: ThemeMode; label: string; Icon: typeof Sun }[] = [
  { value: 'system', label: '跟随系统', Icon: SunMoon },
  { value: 'light', label: '浅色', Icon: Sun },
  { value: 'dark', label: '深色', Icon: Moon },
]

const ACCENT_SOURCES: readonly { value: AccentSource; label: string }[] = [
  { value: 'default', label: '预置色板' },
  { value: 'custom', label: '自定义颜色' },
  { value: 'adaptive', label: '自适应背景色' },
]

const ACCENT_OPTIONS: readonly { value: AccentMode; label: string; color: string }[] = [
  { value: 'ocean', label: '海洋蓝', color: '#315fcf' },
  { value: 'mint', label: '薄荷绿', color: '#087f73' },
  { value: 'amber', label: '琥珀橙', color: '#b45c0a' },
  { value: 'rose', label: '玫瑰红', color: '#b33a61' },
]

/** 一个页面里只显示其中一段：字号与配色、背景、或同步到 DSH。默认全显示（组件单独测试时用）。 */
export type AppearanceScope = 'all' | 'appearance' | 'background'

/**
 * 外观设置：自包含区块，接进设置页的方式与 LanguageSettings 一致。
 *
 * `scope` 把主题拆成三个设置页：字号与配色、背景、DSH 同步。前两段是应用外壳自己的外观，
 * 最后一段要单独一页是因为它改的是**另一个页面**（DSH），与「本机长什么样」是两件事。
 * 默认 'all' 保持整块渲染，组件自身的规则测试可以直接挂载。
 * 组件只负责展示与转发，主题的解析与落地全在 src/theme.ts 里（可单独测试）。
 */
export function AppearanceSettings({ scope = 'all', loadBackground = readBackground }: { scope?: AppearanceScope; loadBackground?: () => Promise<Blob | null> }) {
  const [style, setStyle] = useState(readInterfaceStyle)
  const showAppearance = scope === 'all' || scope === 'appearance'
  const showBackground = scope === 'all' || scope === 'background'
  const mode = useThemeMode()
  const accent = useAccentMode()
  const resolved = useResolvedTheme()
  const [failed, setFailed] = useState<'theme' | 'accent' | null>(null)
  const [card, setCard] = useState<CardAppearance>(() => readCard())
  const [backgroundError, setBackgroundError] = useState('')
  /**
   * 本地是否已经存过一份背景。
   *
   * 原生 `<input type="file">` 永远显示「未选择文件」，即使背景已经加密存进 IndexedDB ——
   * 用户重进这一页会以为选择丢了。用这个状态替它显示真实结论，输入框本身只当触发按钮。
   */
  const [hasAppBackground, setHasAppBackground] = useState(() => hasBackgroundFlag())
  useEffect(() => {
    let active = true
    void loadBackground()
      .then(blob => { if (active) setHasAppBackground(blob !== null) })
      // 解密/存储读取失败时保留已有标记：背景可能真的还在（画面由 AppBackground 负责），
      // 不能因为一次读取失败就把「已选择」改口成「未选择」。
      .catch(() => undefined)
    return () => { active = false }
  }, [loadBackground])
  const [customAccent, setCustomAccent] = useState(() => readCustomAccentColor())
  const [customTextColor, setCustomTextColor] = useState(() => readCustomTextColor())
  const source = useAccentSource()
  const [adaptive, setAdaptive] = useState<AdaptiveAccent | null>(() => readAdaptiveAccent())
  const [extracting, setExtracting] = useState(false)
  const [accentError, setAccentError] = useState('')
  const changeSource = (value: AccentSource) => {
    if (saveAccentSource(value)) { setFailed(null); setAccentError('') } else setFailed('accent')
  }

  /** 只读背景首帧，提取失败时保留当前强调色，不把界面切到一个空颜色上。 */
  const extract = async () => {
    setExtracting(true)
    setAccentError('')
    try {
      const blob = await readBackground()
      if (!blob) { setAccentError('还没有设置背景，请先选择背景图片或视频'); return }
      const result = await extractAccentFromBackground(blob, { theme: resolved, surfaceOpacity: card.opacity })
      if (!result) { setAccentError('没从背景里提取到合适的颜色，已保留当前强调色'); return }
      if (!saveAdaptiveAccent(result) || !saveAccentSource('adaptive')) { setAccentError('无法保存提取结果，请重试'); return }
      setAdaptive(result)
    } catch { setAccentError('读取背景失败，请重试') } finally { setExtracting(false) }
  }

  const updateCard = (next: CardAppearance) => {
    try { saveCard(next); setCard(next); setBackgroundError('') }
    catch { setBackgroundError(t('无法保存外观，请检查本地存储空间')) }
  }

  // index.html 的首帧脚本已经落过一次主题。这里再落一次，是为了覆盖「宿主页面
  // 没有那段脚本」或「脚本被 CSP 拦下」的情形：否则用户存过深色、界面却是浅色，
  // 而设置页显示的选中项与实际外观对不上。
  useEffect(() => { applyTheme(readThemeMode()) }, [])
  useEffect(() => { applyCard(card); applyTextColor() }, [card])

  const change = (value: ThemeMode) => {
    // 存储不可用时如实报错：静默失败会让用户以为选择已生效，下次打开又变回去。
    setFailed(saveThemeMode(value) ? null : 'theme')
  }

  const changeAccent = (value: AccentMode) => {
    const saved = saveAccentMode(value)
    if (saved) setCustomAccent('')
    setFailed(saved ? null : 'accent')
  }

  return (
    <section
      className="settings-section appearance-settings"
      aria-labelledby={showAppearance ? 'appearance-title' : undefined}
      aria-label={showAppearance ? undefined : t('背景与卡片')}
    >
      {showAppearance && (
        <>
          <Palette size={28} aria-hidden="true" />
          <h2 id="appearance-title">{t('界面主题')}</h2>
          <p>{t('立即生效，下次打开仍使用此主题。')}</p>
          <div className="whale-style-picker" role="group" aria-label={t('界面风格')}>
            {([
              { value: 'simple' as const, title: '极简·静奢编辑', desc: '暖石白/深曜石 · 无框排版 · 克制留白', thumb: grokMark, swatches: ['#F4F5F7', '#0B0F17', '#0F52BA'] },
              { value: 'cute' as const, title: '鲸鱼娘·雾面瓷白', desc: '低饱和冰雾瓷 · 柔雾玻璃 · 悬浮胶囊', thumb: whaleChanAvatar, swatches: ['#EEF6FF', '#1B7EF2', '#FF6B9D'] },
              { value: 'usagi' as const, title: '乌萨奇·温润手账', desc: '羊皮纸米白/黑巧丝绒 · 去粗边柔影', thumb: usagiAvatar, swatches: ['#FFFBEA', '#5C3A1B', '#F59E0B'] },
            ]).map(item => (
              <button
                type="button"
                key={item.value}
                className="whale-style-card"
                data-preview={item.value}
                aria-label={t(item.title)}
                aria-pressed={style === item.value}
                onClick={() => { saveInterfaceStyle(item.value); setStyle(item.value) }}
              >
                <img className="whale-style-thumb" src={item.thumb} alt="" width={46} height={46} />
                <span className="whale-style-copy">
                  <strong>{t(item.title)}</strong>
                  <small>{t(item.desc)}</small>
                  <span className="whale-style-swatches" aria-hidden="true">
                    {item.swatches.map(hex => (
                      <i key={hex} style={{ background: hex }} />
                    ))}
                  </span>
                </span>
              </button>
            ))}
          </div>
          <div className="appearance-options" role="radiogroup" aria-labelledby="appearance-title">
            {OPTIONS.map(({ value, label, Icon }) => (
              <label key={value} className={mode === value ? 'appearance-option active' : 'appearance-option'}>
                <input
                  type="radio"
                  name="app-theme"
                  value={value}
                  checked={mode === value}
                  onChange={() => change(value)}
                />
                <Icon size={16} aria-hidden="true" />
                <span>{t(label)}</span>
              </label>
            ))}
          </div>
          {mode === 'system' && <p className="appearance-hint">{t('当前显示：{0}', t(resolved === 'dark' ? '深色' : '浅色'))}</p>}
          {/*
            * 按钮与输入框是同一组：它们都是可直接操作的控件，透明度必须一起调，
            * 否则会出现「按钮半透明、输入框还是实底」的割裂感。选中态的强调色是另一组设置。
            */}
          <details className="appearance-details"><summary>{t('自定义配色与透明度')}</summary>
          <div className="appearance-customization">
            <div className="appearance-accent-heading">
              <strong>{t('按钮与输入框')}</strong>
              <span>{t('统一控制按钮与输入框的底色透明度')}</span>
            </div>
            <label className="range-field">
              <span><strong>{t('按钮与输入框不透明度')}</strong><small>{card.controlOpacity}%</small></span>
              <input
                type="range"
                min="40"
                max="100"
                step="1"
                value={card.controlOpacity}
                onChange={event => { updateCard({ ...card, controlOpacity: Number(event.target.value) }) }}
              />
            </label>
          </div>
          <div className="appearance-accent-block">
            <div className="appearance-accent-heading">
              <strong>{t('选中的强调色')}</strong>
              <span>{t('用于选中项、进度条和状态提示')}</span>
            </div>
            <div className="appearance-accent-options" role="radiogroup" aria-label={t('选中的强调色')}>
              {ACCENT_OPTIONS.map(({ value, label, color }) => (
                <label key={value} className={!customAccent && accent === value ? 'appearance-accent-option active' : 'appearance-accent-option'}>
                  <input
                    type="radio"
                    name="app-accent"
                    value={value}
                    checked={!customAccent && accent === value}
                    onChange={() => changeAccent(value)}
                  />
                  <span className="accent-swatch" style={{ backgroundColor: color }} aria-hidden="true" />
                  <span>{t(label)}</span>
                </label>
              ))}
            </div>
            <label className="appearance-custom-accent"><span>{t('自定义强调色')}</span><input type="color" value={customAccent || '#315fcf'} onChange={event => {
              const value = event.target.value
              if (saveCustomAccentColor(value)) setCustomAccent(value)
            }} /><button type="button" className="compact-button" onClick={() => { clearCustomAccentColor(); setCustomAccent('') }}>{t('使用默认')}</button></label>
          </div>
          </details>
          {failed !== null && <p role="alert">{t(failed === 'accent' ? '无法保存强调色，请重试。' : '无法保存主题，请重试。')}</p>}
        </>
      )}
      {showBackground && (
        <>
          <div className="appearance-customization">
            <div className="appearance-accent-heading"><strong>{t('应用背景与卡片')}</strong><span>{t('背景文件只保存在本机，不会上传。')}</span></div>
            <label className="field"><span>{t('背景图片或视频')}</span>
              <span className="background-picker">
                <span className="button button-secondary compact-button">{t('选择背景文件')}</span>
                <input
                  className="background-picker-input"
                  type="file"
                  accept="image/png,image/jpeg,image/webp,image/gif,video/mp4,video/webm"
                  onChange={event => {
                    const file = event.target.files?.[0]
                    if (!file) return
                    void saveBackground(file)
                      .then(() => { setBackgroundError(''); setHasAppBackground(true) })
                      .catch(() => setBackgroundError(t('无法保存背景，请检查文件格式和存储空间')))
                    // React 异步回调不保留输入节点；下一次选择由系统选择器处理。
                  }}
                />
              </span>
              <small>{hasAppBackground
                ? t('已选择背景，重新选择会替换当前背景')
                : t('支持 PNG、JPG、WebP、GIF、MP4、WebM，最大 50 MB')}</small>
            </label>
            <div className="appearance-range-grid">
              <label className="range-field"><span><strong>{t('卡片不透明度')}</strong><small>{card.opacity}%</small></span><input type="range" min="30" max="100" step="1" value={card.opacity} onChange={event => { const next = { ...card, opacity: Number(event.target.value) }; updateCard(next) }} /></label>
              <label className="range-field"><span><strong>{t('卡片背景模糊')}</strong><small>{card.blur}px</small></span><input type="range" min="0" max="24" step="1" value={card.blur} onChange={event => { const next = { ...card, blur: Number(event.target.value) }; updateCard(next) }} /></label>
            </div>
            <label className="field"><span>{t('卡片背景色')}</span><input type="color" value={card.color || (resolved === 'dark' ? '#171d24' : '#ffffff')} onChange={event => { const next = { ...card, color: event.target.value }; updateCard(next) }} /><button type="button" className="compact-button" onClick={() => { updateCard({ ...DEFAULT_CARD }); void clearBackground().then(() => setHasAppBackground(false)).catch(() => setBackgroundError(t('无法清除背景，请重试'))) }}><RotateCcw size={16} />{t('恢复默认')}</button></label>
            <label className="field"><span>{t('文字颜色')}</span><input aria-label={t('文字颜色')} type="color" value={customTextColor || (resolved === 'dark' ? '#e8eef5' : '#17202b')} onChange={event => { const value = event.target.value; if (saveCustomTextColor(value)) setCustomTextColor(value) }} /><button type="button" className="compact-button" onClick={() => { clearCustomTextColor(); setCustomTextColor('') }}>{t('使用默认')}</button></label>
            {backgroundError && <p role="alert">{t(backgroundError)}</p>}
          </div>
          <div className="appearance-customization appearance-adaptive">
            <div className="appearance-accent-heading"><strong>{t('自适应主题色')}</strong><span>{t('从背景图片或视频首帧提取，只影响外壳外观。')}</span></div>
            <div className="appearance-accent-options" role="radiogroup" aria-label={t('强调色来源')}>
              {ACCENT_SOURCES.map(({ value, label }) => (
                <label key={value} className={source === value ? 'appearance-accent-option active' : 'appearance-accent-option'}>
                  <input type="radio" name="accent-source" checked={source === value} onChange={() => changeSource(value)} />
                  <span>{t(label)}</span>
                </label>
              ))}
            </div>
            <p>{adaptive ? `${t('当前提取色')} ${adaptive.accent} · ${new Date(adaptive.extractedAt).toLocaleString()}` : t('还没有从背景提取过主题色')}</p>
            <button type="button" className="compact-button" onClick={() => void extract()} disabled={extracting}>
              {extracting ? t('正在提取…') : adaptive ? t('重新提取') : t('从背景提取主题色')}
            </button>
            {adaptive && <button type="button" className="compact-button" onClick={() => { clearAdaptiveAccent(); setAdaptive(null); if (source === 'adaptive') changeSource('default') }}>{t('清除提取结果')}</button>}
            {accentError && <p role="alert">{t(accentError)}</p>}
          </div>
        </>
      )}
    </section>
  )
}

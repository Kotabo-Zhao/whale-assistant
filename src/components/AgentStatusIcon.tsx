import type { InterfaceStyle } from '../theme'

export type AgentStatusCategory =
  | 'thinking'
  | 'reasoning'
  | 'analyzing'
  | 'code'
  | 'web'
  | 'file'
  | 'memory'
  | 'tasks'
  | 'phone'
  | 'worker'

interface AgentStatusIconProps {
  style: InterfaceStyle
  category: string
}

function normalizeCategory(raw: string): AgentStatusCategory {
  if (
    raw === 'reasoning' ||
    raw === 'analyzing' ||
    raw === 'code' ||
    raw === 'web' ||
    raw === 'file' ||
    raw === 'memory' ||
    raw === 'tasks' ||
    raw === 'phone' ||
    raw === 'worker'
  ) {
    return raw
  }
  return 'thinking'
}

/**
 * 3 套主题 × 10 种真实 Agent 状态 = 全独立极简微光矢量动画（60 FPS CSS 矢量动效）。
 * - cute（鲸鱼娘·晴海）：鲸尾、海浪、珍珠、罗盘、海星、水泡
 * - usagi（乌萨奇·兔兔）：长兔耳、兔耳气泡、讨伐棒星芒、兔耳终端、金星、小旗
 * - simple（极简·类 Grok）：六瓣星花、原子轨、棱镜、声呐、神经网络、双齿轮
 */
export function AgentStatusIcon({ style, category }: AgentStatusIconProps) {
  const cat = normalizeCategory(category)
  const cls = `grok-vec-svg is-${style} is-${cat}`

  if (style === 'usagi') {
    switch (cat) {
      case 'thinking':
        // 轻晃的乌萨奇长兔耳 + 头顶闪烁金星
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <g className="gv-rock">
              <path className="gv-main" d="M8.4 12.2C7.4 7.5 8.1 3.6 9.8 3.6C11.2 3.6 11.4 7.4 11 11.8" fill="none" strokeWidth="1.75" strokeLinecap="round" />
              <path className="gv-main" d="M15.6 12.2C16.6 7.5 15.9 3.6 14.2 3.6C12.8 3.6 12.6 7.4 13 11.8" fill="none" strokeWidth="1.75" strokeLinecap="round" />
              <path className="gv-soft" d="M6.2 15.2C6.8 18.8 9.2 20.6 12 20.6C14.8 20.6 17.2 18.8 17.8 15.2" fill="none" strokeWidth="1.65" strokeLinecap="round" />
              <circle className="gv-fill" cx="9.8" cy="15.2" r="1.15" />
              <circle className="gv-fill" cx="14.2" cy="15.2" r="1.15" />
            </g>
            <path className="gv-fill gv-core" d="M19.2 4.2L19.8 5.8L21.4 6.4L19.8 7L19.2 8.6L18.6 7L17 6.4L18.6 5.8Z" />
          </svg>
        )
      case 'reasoning':
        // 乌萨奇兔耳聊天气泡 + 跳动三点灵感
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <path className="gv-soft" d="M8.8 7.4C8.3 4.6 8.8 2.8 10 2.8C11 2.8 11.1 4.9 10.9 7.2" fill="none" strokeWidth="1.5" strokeLinecap="round" />
            <path className="gv-soft" d="M15.2 7.4C15.7 4.6 15.2 2.8 14 2.8C13 2.8 12.9 4.9 13.1 7.2" fill="none" strokeWidth="1.5" strokeLinecap="round" />
            <rect className="gv-main" x="3.5" y="7.4" width="17" height="12.2" rx="5.5" fill="none" strokeWidth="1.7" />
            <circle className="gv-fill gv-dot-1" cx="8.4" cy="13.5" r="1.35" />
            <circle className="gv-fill gv-dot-2" cx="12" cy="13.5" r="1.35" />
            <circle className="gv-fill gv-dot-3" cx="15.6" cy="13.5" r="1.35" />
          </svg>
        )
      case 'analyzing':
        // 旋转讨伐棒星芒 + 呼吸光核
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <g className="gv-spin">
              <line className="gv-main" x1="5" y1="19" x2="19" y2="5" strokeWidth="2.1" strokeLinecap="round" />
              <line className="gv-soft" x1="5" y1="5" x2="19" y2="19" strokeWidth="1.5" strokeLinecap="round" strokeDasharray="3 4" />
            </g>
            <circle className="gv-fill gv-core" cx="12" cy="12" r="2.6" />
          </svg>
        )
      case 'code':
        // 兔耳极简终端框 >_
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <path className="gv-soft" d="M9 6.6C8.6 4.2 9.1 2.8 10.1 2.8C11 2.8 11.1 4.5 10.9 6.6" fill="none" strokeWidth="1.45" strokeLinecap="round" />
            <path className="gv-soft" d="M15 6.6C15.4 4.2 14.9 2.8 13.9 2.8C13 2.8 12.9 4.5 13.1 6.6" fill="none" strokeWidth="1.45" strokeLinecap="round" />
            <rect className="gv-soft" x="2.8" y="6.8" width="18.4" height="13.6" rx="3.4" fill="none" strokeWidth="1.6" />
            <path className="gv-main" d="M7.5 11.2L10.6 13.6L7.5 16" fill="none" strokeWidth="1.85" strokeLinecap="round" strokeLinejoin="round" />
            <line className="gv-blink" x1="12.6" y1="16" x2="16.5" y2="16" strokeWidth="1.9" strokeLinecap="round" />
          </svg>
        )
      case 'web':
        // 放大镜扫频 + 跃动星点
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <g className="gv-rock">
              <circle className="gv-main" cx="10.8" cy="10.8" r="6.2" fill="none" strokeWidth="1.8" />
              <line className="gv-main" x1="15.4" y1="15.4" x2="20" y2="20" strokeWidth="2.1" strokeLinecap="round" />
            </g>
            <g className="gv-spin-slow">
              <circle className="gv-fill" cx="10.8" cy="7.2" r="1.4" />
            </g>
          </svg>
        )
      case 'file':
        // 展开书页 + 暖金流动笔迹
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <path className="gv-soft" d="M3 6.2C6 5 9.5 5.4 12 7.2C14.5 5.4 18 5 21 6.2V18.2C18 17 14.5 17.4 12 19.2C9.5 17.4 6 17 3 18.2Z" fill="none" strokeWidth="1.6" strokeLinejoin="round" />
            <line className="gv-dash gv-d1" x1="5.6" y1="10.4" x2="9.8" y2="10.4" strokeWidth="1.6" strokeLinecap="round" />
            <line className="gv-dash gv-d2" x1="14.2" y1="10.4" x2="18.4" y2="10.4" strokeWidth="1.6" strokeLinecap="round" />
            <line className="gv-dash gv-d3" x1="14.2" y1="14" x2="18.4" y2="14" strokeWidth="1.6" strokeLinecap="round" />
          </svg>
        )
      case 'memory':
        // 乌萨奇旋转大金星
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <g className="gv-spin-slow">
              <path className="gv-main" d="M12 3.2L14.5 8.8L20.6 9.5L16 13.6L17.3 19.6L12 16.5L6.7 19.6L8 13.6L3.4 9.5L9.5 8.8Z" fill="none" strokeWidth="1.7" strokeLinejoin="round" />
            </g>
            <circle className="gv-fill gv-core" cx="12" cy="12.2" r="2" />
          </svg>
        )
      case 'tasks':
        // 兔耳清单圆环 + 动态打勾
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <circle className="gv-soft" cx="12" cy="12.6" r="8" fill="none" strokeWidth="1.6" />
            <path className="gv-dash gv-d1" d="M8.2 12.6L11 15.4L16.2 9.8" fill="none" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
            <circle className="gv-fill gv-p1" cx="9.2" cy="3.6" r="1.2" />
            <circle className="gv-fill gv-p2" cx="14.8" cy="3.6" r="1.2" />
          </svg>
        )
      case 'phone':
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <circle className="gv-ripple gv-r1" cx="12" cy="12" r="8" fill="none" strokeWidth="1.65" />
            <circle className="gv-ripple gv-r2" cx="12" cy="12" r="8" fill="none" strokeWidth="1.65" />
            <circle className="gv-fill" cx="12" cy="12" r="2.4" />
          </svg>
        )
      case 'worker':
        // 交叉轻挥双小旗
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <g className="gv-rock">
              <line className="gv-main" x1="7.5" y1="20" x2="7.5" y2="5.5" strokeWidth="1.8" strokeLinecap="round" />
              <path className="gv-main" d="M7.5 5.5L16.5 8.5L7.5 11.8Z" fill="none" strokeWidth="1.7" strokeLinejoin="round" />
            </g>
            <circle className="gv-fill gv-p2" cx="18.2" cy="16.2" r="1.7" />
          </svg>
        )
    }
  }

  if (style === 'cute') {
    switch (cat) {
      case 'thinking':
        // 鲸鱼娘主题：轻摆鲸鱼尾鳍 + 上升微光水泡
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <g className="gv-rock">
              <path className="gv-main" d="M12 19.5C12 15.5 12 13.5 12 12C9.5 11.5 6.4 9.5 4.8 6.5C8 6.8 10.6 8.2 12 10.5C13.4 8.2 16 6.8 19.2 6.5C17.6 9.5 14.5 11.5 12 12" fill="none" strokeWidth="1.85" strokeLinecap="round" strokeLinejoin="round" />
            </g>
            <circle className="gv-fill gv-float-1" cx="7.5" cy="16.5" r="1.4" />
            <circle className="gv-fill gv-float-2" cx="17" cy="15.2" r="1.15" />
          </svg>
        )
      case 'reasoning':
        // 深海珍珠双环呼吸光晕
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <circle className="gv-ripple gv-r1" cx="12" cy="12" r="8.2" fill="none" strokeWidth="1.5" />
            <circle className="gv-soft" cx="12" cy="12" r="5.4" fill="none" strokeWidth="1.5" />
            <circle className="gv-fill gv-core" cx="12" cy="12" r="2.8" />
          </svg>
        )
      case 'analyzing':
        // 双层丝滑涌动海浪曲线
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <path className="gv-dash gv-d1" d="M3 9.5C5.2 6.8 7.8 6.8 10 9.5C12.2 12.2 14.8 12.2 17 9.5C18.5 7.7 19.8 7.5 21 8.5" fill="none" strokeWidth="1.9" strokeLinecap="round" />
            <path className="gv-dash gv-d2" d="M3 15.5C5.2 12.8 7.8 12.8 10 15.5C12.2 18.2 14.8 18.2 17 15.5C18.5 13.7 19.8 13.5 21 14.5" fill="none" strokeWidth="1.9" strokeLinecap="round" />
          </svg>
        )
      case 'code':
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <rect className="gv-soft" x="2.5" y="4" width="19" height="16" rx="4" fill="none" strokeWidth="1.65" />
            <path className="gv-main" d="M7.5 9.2L10.8 12L7.5 14.8" fill="none" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
            <line className="gv-blink" x1="12.8" y1="14.8" x2="16.8" y2="14.8" strokeWidth="1.9" strokeLinecap="round" />
          </svg>
        )
      case 'web':
        // 航海罗盘旋转搜寻
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <circle className="gv-soft" cx="12" cy="12" r="8.5" fill="none" strokeWidth="1.5" />
            <g className="gv-spin">
              <polygon className="gv-main" points="12,5 14.4,12 12,19 9.6,12" fill="none" strokeWidth="1.65" strokeLinejoin="round" />
              <circle className="gv-fill" cx="12" cy="5" r="1.4" />
            </g>
          </svg>
        )
      case 'file':
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <rect className="gv-soft" x="4.5" y="2.8" width="15" height="18.4" rx="3" fill="none" strokeWidth="1.6" />
            <line className="gv-dash gv-d1" x1="8" y1="8.2" x2="16" y2="8.2" strokeWidth="1.8" strokeLinecap="round" />
            <line className="gv-dash gv-d2" x1="8" y1="12" x2="16" y2="12" strokeWidth="1.8" strokeLinecap="round" />
            <line className="gv-dash gv-d3" x1="8" y1="15.8" x2="13.5" y2="15.8" strokeWidth="1.8" strokeLinecap="round" />
          </svg>
        )
      case 'memory':
        // 发光海星旋转脉冲
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <g className="gv-spin-slow">
              <polygon className="gv-soft" points="12,4.5 18.8,16.5 5.2,16.5" fill="none" strokeWidth="1.4" strokeLinejoin="round" />
              <circle className="gv-fill gv-p1" cx="12" cy="4.5" r="2.1" />
              <circle className="gv-fill gv-p2" cx="18.8" cy="16.5" r="2.1" />
              <circle className="gv-fill gv-p3" cx="5.2" cy="16.5" r="2.1" />
            </g>
          </svg>
        )
      case 'tasks':
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <circle className="gv-soft" cx="12" cy="12" r="8.2" fill="none" strokeWidth="1.6" />
            <path className="gv-dash gv-d1" d="M8.2 12.2L11 15L16.2 9.2" fill="none" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        )
      case 'phone':
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <circle className="gv-ripple gv-r1" cx="12" cy="12" r="8" fill="none" strokeWidth="1.6" />
            <circle className="gv-ripple gv-r2" cx="12" cy="12" r="8" fill="none" strokeWidth="1.6" />
            <circle className="gv-fill" cx="12" cy="12" r="2.4" />
          </svg>
        )
      case 'worker':
        return (
          <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
            <g className="gv-spin">
              <path className="gv-main" d="M4.5 12A7.5 7.5 0 0 1 18.2 7.8" fill="none" strokeWidth="1.9" strokeLinecap="round" />
              <path className="gv-main" d="M19.5 12A7.5 7.5 0 0 1 5.8 16.2" fill="none" strokeWidth="1.9" strokeLinecap="round" />
              <circle className="gv-fill" cx="18.2" cy="7.8" r="1.7" />
              <circle className="gv-fill" cx="5.8" cy="16.2" r="1.7" />
            </g>
          </svg>
        )
    }
  }

  // simple（极简·类 Grok 纯几何科技矢量动效）
  switch (cat) {
    case 'reasoning':
      return (
        <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <g className="gv-spin">
            <ellipse className="gv-soft" cx="12" cy="12" rx="9" ry="3.8" fill="none" strokeWidth="1.5" />
            <ellipse className="gv-main" cx="12" cy="12" rx="9" ry="3.8" fill="none" strokeWidth="1.5" transform="rotate(60 12 12)" />
            <ellipse className="gv-soft" cx="12" cy="12" rx="9" ry="3.8" fill="none" strokeWidth="1.5" transform="rotate(120 12 12)" />
          </g>
          <circle className="gv-fill gv-core" cx="12" cy="12" r="2.1" />
        </svg>
      )
    case 'analyzing':
      return (
        <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <g className="gv-spin-slow">
            <polygon className="gv-main" points="12,3.5 20.5,12 12,20.5 3.5,12" fill="none" strokeWidth="1.75" strokeLinejoin="round" />
          </g>
          <polygon className="gv-soft gv-core" points="12,7.5 16.5,12 12,16.5 7.5,12" fill="none" strokeWidth="1.5" strokeLinejoin="round" />
        </svg>
      )
    case 'code':
      return (
        <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <rect className="gv-soft" x="2.5" y="4" width="19" height="16" rx="3.5" fill="none" strokeWidth="1.65" />
          <path className="gv-main" d="M7.5 9.2L10.8 12L7.5 14.8" fill="none" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" />
          <line className="gv-blink" x1="12.8" y1="14.8" x2="16.8" y2="14.8" strokeWidth="1.9" strokeLinecap="round" />
        </svg>
      )
    case 'web':
      return (
        <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <circle className="gv-soft" cx="12" cy="12" r="8.5" fill="none" strokeWidth="1.5" />
          <circle className="gv-soft" cx="12" cy="12" r="4.5" fill="none" strokeWidth="1.35" />
          <g className="gv-spin">
            <path className="gv-main" d="M12 3.5A8.5 8.5 0 0 1 20.5 12" fill="none" strokeWidth="2" strokeLinecap="round" />
            <circle className="gv-fill" cx="20.5" cy="12" r="1.9" />
          </g>
        </svg>
      )
    case 'file':
      return (
        <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <rect className="gv-soft" x="4.5" y="2.8" width="15" height="18.4" rx="2.8" fill="none" strokeWidth="1.6" />
          <line className="gv-dash gv-d1" x1="8" y1="8.2" x2="16" y2="8.2" strokeWidth="1.8" strokeLinecap="round" />
          <line className="gv-dash gv-d2" x1="8" y1="12" x2="16" y2="12" strokeWidth="1.8" strokeLinecap="round" />
          <line className="gv-dash gv-d3" x1="8" y1="15.8" x2="13.5" y2="15.8" strokeWidth="1.8" strokeLinecap="round" />
        </svg>
      )
    case 'memory':
      return (
        <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <g className="gv-spin-slow">
            <polygon className="gv-soft" points="12,4.5 18.8,16.5 5.2,16.5" fill="none" strokeWidth="1.4" strokeLinejoin="round" />
            <circle className="gv-fill gv-p1" cx="12" cy="4.5" r="2.1" />
            <circle className="gv-fill gv-p2" cx="18.8" cy="16.5" r="2.1" />
            <circle className="gv-fill gv-p3" cx="5.2" cy="16.5" r="2.1" />
          </g>
        </svg>
      )
    case 'tasks':
      return (
        <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <circle className="gv-soft" cx="12" cy="12" r="8.2" fill="none" strokeWidth="1.6" />
          <path className="gv-dash gv-d1" d="M8.2 12.2L11 15L16.2 9.2" fill="none" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
      )
    case 'phone':
      return (
        <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <circle className="gv-ripple gv-r1" cx="12" cy="12" r="8" fill="none" strokeWidth="1.6" />
          <circle className="gv-ripple gv-r2" cx="12" cy="12" r="8" fill="none" strokeWidth="1.6" />
          <circle className="gv-fill" cx="12" cy="12" r="2.4" />
        </svg>
      )
    case 'worker':
      return (
        <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <g className="gv-gear-a">
            <circle className="gv-main" cx="9.2" cy="9.8" r="3.4" fill="none" strokeWidth="1.7" />
            <path className="gv-main" d="M9.2 4.6V6.2M9.2 13.4V15M4 9.8H5.6M12.8 9.8H14.4" strokeWidth="1.7" strokeLinecap="round" />
          </g>
          <g className="gv-gear-b">
            <circle className="gv-main" cx="15.8" cy="15.4" r="2.7" fill="none" strokeWidth="1.65" />
            <path className="gv-main" d="M15.8 11.2V12.5M15.8 18.3V19.6M11.6 15.4H12.9M18.7 15.4H20" strokeWidth="1.65" strokeLinecap="round" />
          </g>
        </svg>
      )
    default:
      return (
        <svg className={cls} viewBox="0 0 24 24" width="22" height="22" aria-hidden="true">
          <g className="gv-spin">
            <line className="gv-main gv-spoke-1" x1="12" y1="3.2" x2="12" y2="7.8" strokeWidth="2.1" strokeLinecap="round" />
            <line className="gv-main gv-spoke-2" x1="12" y1="16.2" x2="12" y2="20.8" strokeWidth="2.1" strokeLinecap="round" />
            <line className="gv-main gv-spoke-3" x1="4.4" y1="7.6" x2="8.4" y2="9.9" strokeWidth="2.1" strokeLinecap="round" />
            <line className="gv-main gv-spoke-1" x1="15.6" y1="14.1" x2="19.6" y2="16.4" strokeWidth="2.1" strokeLinecap="round" />
            <line className="gv-main gv-spoke-2" x1="19.6" y1="7.6" x2="15.6" y2="9.9" strokeWidth="2.1" strokeLinecap="round" />
            <line className="gv-main gv-spoke-3" x1="8.4" y1="14.1" x2="4.4" y2="16.4" strokeWidth="2.1" strokeLinecap="round" />
          </g>
          <circle className="gv-fill gv-core" cx="12" cy="12" r="2.1" />
        </svg>
      )
  }
}

import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * 生产构建的手工分块策略（登记册 5.6-A）。
 *
 * 目标只有一个：**首屏不需要的代码不要出现在入口包和入口的静态依赖图里**。
 *
 * 1. `react` / `react-dom` / `scheduler` 是运行时底座，和业务代码的更新节奏完全不同。
 *    单独成块后，入口包（`index-*.js`）只反映应用自身的代码量，升级业务代码时这一块不变，
 *    在 WebView 缓存里可以长期复用。
 *
 * 两条踩过的坑，都写在这里免得后人重犯：
 *
 * - 入口包变小的前提是「拆出去的块真的不在首屏」。判断依据不是看文件名，而是看
 *   `dist/index.html` 里 `<script type="module">` 与 `rel="modulepreload"` 指向了哪些文件。
 */
const RUNTIME_PACKAGE = /[\\/]node_modules[\\/](react|react-dom|scheduler)[\\/]/
const MARKDOWN_PACKAGE = /[\\/]node_modules[\\/](markdown-it|markdown-it-texmath|katex)[\\/]/
const ICON_PACKAGE = /[\\/]node_modules[\\/]lucide-react[\\/]/

function manualChunks(id: string): string | undefined {
  // CSS 不参与手工分块：样式跟着引入它的块走，避免「样式依赖」把 JS 块拖进首屏。
  const file = id.split('?')[0]
  if (file.endsWith('.css')) return undefined
  if (RUNTIME_PACKAGE.test(id)) return 'react'
  if (MARKDOWN_PACKAGE.test(id)) return 'markdown'
  if (ICON_PACKAGE.test(id)) return 'icons'
  return undefined
}

export default defineConfig({
  plugins: [react()],
  base: './',
  server: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: false,
  },
  preview: {
    host: '127.0.0.1',
    port: 4173,
    strictPort: false,
  },
  build: {
    target: 'es2022',
    sourcemap: false,
    rollupOptions: {
      output: { manualChunks },
    },
  },
})

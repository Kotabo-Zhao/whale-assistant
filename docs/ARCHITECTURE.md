# Whale Assistant 架构说明

Whale Assistant（`0.4.0-whale.1`）是一款 100% 原生 Android 个人 Super Agent，采用 **React + Capacitor 壳层** 与 **Kotlin 原生内核层** 分离架构，无需 PRoot / Ubuntu 容器即可运行全部核心能力。

## 1. 前端交互层（`src/`）
- **主对话工作台（`src/screens/WhaleChatScreen.tsx`）**：多会话管理、SSE 实时流式渲染、中途插话修正（`whaleNativeChatSteer`）、一键停止（`whaleNativeChatCancel`）、图形化实时执行计划条（Live Plan Bar）、同轮下一步建议胶囊、Canvas 图片压缩多模态发送以及硅基流动语音收发。
- **双视觉主题体系（`src/whale-design.css`、`src/theme.ts`、`src/components/CuteIcon.tsx`）**：支持 `simple`（极简高对比）与 `cute`（原版鲸鱼娘 + 18 枚贴纸图标 + 图形化减字气泡），并同步控制 Android 系统状态栏与导航栏配色。
- **自治与设置中心（`src/App.tsx`、`src/components/WhaleAutonomyCard.tsx`）**：一站式自治预设、44dp–160dp 桌宠无级缩放、模型供应商与密钥管理、记忆资料库与离线功能工坊。

## 2. Android 原生核心层（`android/app/src/main/java/io/deepseekharness/mobile/`）
- **多轮流式推理引擎（`companion/WhaleNativeAgentEngine.kt`）**：
  - 100% 静态 System Prompt 以最大化 Prefix Caching；
  - `RunControl` 支持工具间无缝插话注入与流式即时打断；
  - 滑动 12 条历史窗口 + 两轮前工具输出滚动折叠 + 4 线程只读并行 + 重复调用熔断。
- **认知与路由组件**：
  - `WhaleGoalGraphStore.kt`：跨会话持久化目标图；
  - `WhalePlaybookStore.kt`：程序性习惯规则库；
  - `WhaleModelRouter.kt`：复杂度与当日预算自适应模型路由；
  - `WhaleNativeRagRetriever.kt`：本地 `BGE-base-zh-v1.5` 向量 + SQLite 词法 RRF 混合检索。
- **工具与扩展生态**：
  - `WhaleNativeToolRegistry.kt`：79 项进程内工具，支持分层热加载（`whale_tools_expand`）；
  - `WhaleCustomToolEngine.kt`：Agent 自造工具引擎（`composite` / `http_json` HTTPS+SSRF 校验 / 隔离 WebView `js` 沙盒）；
  - `WhaleNativeWorkerPool.kt`：后台异步 Worker 并发池与结果收件箱。
- **悬浮桌宠与视觉语音**：
  - `OverlayBallService.kt` + `overlay/CompanionPetDrawable.kt`：52 套原版鲸鱼娘光流 WebP 图集、60 FPS 轻柔呼吸与摆动、头顶奶霜渐变 Token 计费胶囊；
  - `WhaleTokenMeter.kt`：2026-11 主流模型公开单价核算（含 DeepSeek 峰谷 2× 与多厂商缓存命中）；
  - `WhaleVoiceEngine.kt`：硅基流动 ASR（`SenseVoiceSmall`）与 TTS（`CosyVoice2-0.5B`）。

## 3. 安全红线（不可绕过）
`AccessibilityAutomationPolicy`、`RuntimeCredentialCipher`、`RootCommandGuard`、`RootSecurityWrapper`、`SafeRootfsExtractor`、`PetControlProtection`。

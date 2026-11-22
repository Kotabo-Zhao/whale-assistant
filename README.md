# Whale Assistant（鲸鱼助手）

[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-3DDC84?logo=arm&logoColor=white)](https://developer.android.com/ndk/guides/abis)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25%20Native-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Python 3.11 Embedded](https://img.shields.io/badge/Python-3.11%20Embedded-3776AB?logo=python&logoColor=white)](https://chaquo.com/chaquopy/)
[![Version](https://img.shields.io/badge/Version-0.4.0--whale.1-0ea5e9)](https://github.com/Kotabo-Zhao/whale-assistant/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**运行在 Android 设备上的 100% 原生个人 Super Agent 与随身桌面萌宠。**  
Whale Assistant 采用纯原生 Lite 架构（零 PRoot / Ubuntu 容器、零离线 ONNX 向量模型依赖，单 APK 约 63 MB），内嵌原生 **CPython 3.11（Chaquopy）** 与 **DeepSeek Harness** 核心工程防线，集成了 **94 项进程内原生工具**、编程式工具调用（PTC）、**3+1 线程隔离并发后台 Worker**、自动收藏记忆与本机 SQLite 词法检索（支持可选接入云端 `/embeddings` 混合 RRF 检索）、无障碍极速控屏 v2、远程 MCP / Skill 扩展生态，以及支持双指无级缩放与右边缘拖拽收藏的原版「鲸鱼娘」悬浮桌宠——打开即聊，覆盖升级数据无损。

| 项目 | 说明 |
| :--- | :--- |
| 应用版本 | `0.4.0-whale.1`（Lite 纯原生版，~63 MB） |
| 应用包名 | `io.deepseekharness.mobile` |
| 最低系统 | Android 8.0（API 26）及以上，`arm64-v8a` |
| 核心引擎 | Kotlin 原生 SSE 流式引擎 + `WhaleHarnessCore` + 内嵌 CPython 3.11（PTC）+ 3+1 并发 Worker 池 |
| 原生工具 | 94 项进程内原生工具（核心常驻 + 按需热展开）+ 自造工具引擎 + 标准 `SKILL.md` + 远程 MCP 客户端 |
| 记忆检索 | 默认零成本本机 SQLite 词法 + 大模型自主检索；可选配置 OpenAI 兼容 `/embeddings` 接口开启云端向量 + RRF 混合检索（已移除离线 103 MB BGE ONNX 模型） |
| 语音引擎 | 界面精简为纯语音识别（STT）输入，默认离线调用 Android 原生 `SpeechRecognizer`；底层语音引擎保留本地 TTS 与可选硅基流动云端升级能力 |
| 覆盖升级 | 每日构建固定 `whale-nightly-debug.keystore` + 递增 `versionCode`，支持无缝覆盖安装 |
| 开源协议 | MIT（详见 [LICENSE](LICENSE)） |

## 核心特性

### 1. 随身桌面萌宠「鲸鱼娘」与实时双向同步
- **双指捏合无级缩放（`44dp–160dp`）**：52 套原版鲸鱼娘光流 WebP 动作图集，60 FPS 轻柔呼吸摆动，头顶配备实时 Token/费用进度胶囊（[`WhaleTokenMeter`](android/app/src/main/java/io/deepseekharness/mobile/companion/WhaleTokenMeter.kt)）。
- **右边缘拖拽直接收藏**：在其他应用中拖动文字或图片至屏幕右侧发光停靠区，松手自动分析并沉淀进长期记忆库；同时支持无障碍「收藏成功」信号自动入库与系统截屏自动入库（[`WhaleAutoMemoryObserver`](android/app/src/main/java/io/deepseekharness/mobile/companion/WhaleAutoMemoryObserver.kt)）。
- **桌宠 ↔ App 秒级双向同步**：App 主对话与桌宠悬浮小窗采用统一深蓝微边框卡片视觉，通过 [`WhaleSharedChatStore`](android/app/src/main/java/io/deepseekharness/mobile/companion/WhaleSharedChatStore.kt) 实时同步对话记录与后台任务回传，切窗不断片。

### 2. 多模态对话、双视觉体系与精简设置
- **多模态输入**：支持文字、系统原生语音识别输入、相册发图（前端 Canvas 压缩）与**视频自动抽取关键帧**发送。
- **全双工中途插话与行动胶囊**：推理或工具执行期间不锁死输入，可随时插话修正（`RunControl.steer`）或一键中止（`cancel`）；每轮自动附带 2–3 条下一步快捷回复胶囊，聊到日程/待办自动生成可一键验收的行动回执卡。
- **双视觉主题与 4 大设置分组**：支持 `simple`（极简高对比）与 `cute`（原版鲸鱼娘 + 18 枚贴纸图标）双风格及主题强调色切换；设置首页整合为 **常用 · AI 模型 · 人格与陪伴 · 外观与语言** 四大分组，支持角色人设卡与世界书增量微调。

### 3. 内嵌 CPython 3.11 + DeepSeek Harness 核心防线
- **内嵌原生 Python 3.11 + 编程式工具调用（PTC）**：通过 Chaquopy（[`WhaleCodeRunner`](android/app/src/main/java/io/deepseekharness/mobile/companion/WhaleCodeRunner.kt) + [`whale_runner.py`](android/app/src/main/python/whale_runner.py)）无需外部 Linux 环境即可毫秒级运行 Python；支持在脚本中通过 `tools.<工具名>(**kwargs)` 串联最多 16 次原生工具并用 `inspect(...)` 返回精炼结论（内置防递归保护与最高 180 秒超时）。
- **DeepSeek Harness 核心原语（[`WhaleHarnessCore`](android/app/src/main/java/io/deepseekharness/mobile/companion/WhaleHarnessCore.kt)）**：
  - **分层死循环检测（`RepeatToolGuard`）**：递归排序 JSON 键规范化参数，连续 `3/5/8` 次分级提醒、`10` 次自动熔断。
  - **大输出落盘与首尾裁剪（`SpillAndPruneManager`）**：超过 6,400 字符的工具输出先无损落盘至 `spill/<id>`（可用 `whale_read_resource` 按页回查），并按 Unicode 码点安全保留首尾；两轮前历史输出自动首尾精简。
  - **视觉预算控制（`ImageOffloadPolicy`）**：多轮控屏/看图自动卸载最旧图片，仅保留最新 4 张画面。
  - **分段并发与协议修复（`ToolBatchScheduler` / `ToolCallRecovery`）**：连续只读工具 4 线程并行、写操作独占屏障；中途取消或异常时自动补齐缺失的 `tool_call_id` 占位结果。
  - **KV 缓存友好压缩与读后写守卫**：长任务超 56k 字符时保持静态前缀不变追加结构化 `<compacted-summary>`；代码编辑前校验文件 `mtime + size` 防盲改；内置 `whale_todo_write` 整表任务追踪。

### 4. 3+1 并发后台 Worker、极速控屏与开放扩展
- **3+1 线程隔离并发 Worker（[`WhaleNativeWorkerPool`](android/app/src/main/java/io/deepseekharness/mobile/companion/WhaleNativeWorkerPool.kt) + [`WhaleSuperagentCore`](android/app/src/main/java/io/deepseekharness/mobile/companion/WhaleSuperagentCore.kt)）**：3 个常规并发槽 + 1 个用户追问专属抢占槽，7 态确定性 `TicketStage` 调度，支持追问直达运行中 Worker（`deliverFollowup`）、前台慢工具 5 秒自动转后台，以及 Python 线程级 `stdout`/`stderr` 与工作目录隔离。
- **无障碍极速控屏 v2 与多模态识屏**：[`WhaleFastPhoneController`](android/app/src/main/java/io/deepseekharness/mobile/accessibility/WhaleFastPhoneController.kt) 自动剪枝控件树并分配 `#ref` 短编号，支持最多 8 步原子批量操作、`dispatchGesture` 手势回退与剪贴板粘贴兜底；[`WhaleVisionScreenAnalyzer`](android/app/src/main/java/io/deepseekharness/mobile/companion/WhaleVisionScreenAnalyzer.kt) 补齐图标/Canvas 视觉定位；严格坚守密码、验证码、支付与系统受保护应用零妥协拦截。
- **开放扩展生态**：内置自造工具引擎（[`WhaleCustomToolEngine`](android/app/src/main/java/io/deepseekharness/mobile/companion/WhaleCustomToolEngine.kt)，支持 `composite` / HTTPS+SSRF 校验 `http_json` / 隔离 WebView `js` 沙盒）、GitHub/URL 一键安装标准 `SKILL.md`（[`WhaleSkillStore`](android/app/src/main/java/io/deepseekharness/mobile/companion/WhaleSkillStore.kt)）、Streamable HTTP / JSON-RPC 2.0 远程 MCP 客户端（[`WhaleMcpManager`](android/app/src/main/java/io/deepseekharness/mobile/companion/WhaleMcpManager.kt)）及离线 HTML 功能工坊。

## 系统架构

详细模块说明见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)，Android 原生构建与边界说明见 [android/README.md](android/README.md)。

```text
┌──────────────────────────────────────────────────────────────────┐
│  WebView UI 层（React 18 + TypeScript + Vite + Capacitor 7）      │
│  · 极简 / 可爱双视觉体系 · 4 大清爽设置分组 · 发图/视频抽帧/语音    │
│  · 统一深蓝气泡卡片 · App ↔ 桌宠秒级双向同步 · 实时执行计划条      │
└──────────────────────────▲───────────────────────────────────────┘
                           │ MobileRuntimePlugin（144 个 Bridge 方法）
┌──────────────────────────▼───────────────────────────────────────┐
│  Android 原生内核层（100% Kotlin + 内嵌 Chaquopy CPython 3.11）   │
│  · WhaleNativeAgentEngine + WhaleHarnessCore（SSE/PTC/Spill/熔断）│
│  · WhaleNativeToolRegistry（94 项原生工具 + 自造工具 + MCP/Skill） │
│  · WhaleCodeRunner + whale_runner.py（线程隔离 Python 3.11 + PTC）│
│  · WhaleNativeWorkerPool + WhaleSuperagentCore（3+1 并发与 7 态）  │
│  · WhaleAutoMemoryObserver + SQLite 词法/可选云端向量 RRF 检索     │
│  · OverlayBallService（44–160dp 桌宠 + 右边缘拖拽收藏）            │
│  · WhaleFastPhoneController + WhaleVisionScreenAnalyzer（屏控 v2） │
└──────────────────────────────────────────────────────────────────┘
```

## 安装与构建

### 直接安装（推荐）
前往 [GitHub Releases](https://github.com/Kotabo-Zhao/whale-assistant/releases) 下载最新的 `whale-assistant-lite-*.apk` 直接安装或覆盖升级即可。

### 从源码构建 Lite 原生版
环境要求：Node.js `22.19.0`（或 `>=24.0.0`）+ `pnpm`（`11.x`）、JDK `21`、Gradle `8.11.1`、Android SDK（API 35）与 NDK `27.1.12297006`。

```bash
pnpm install --frozen-lockfile
pnpm run build
pnpm run lint
pnpm test
pnpm run android:build:lite
```

`pnpm run android:build:lite` 会自动执行前端编译、`cap sync android` 并携带 `-PwhaleSeparateRuntime=true` 构建 Lite APK（输出位于 `android/build/whale-light-app/outputs/apk/debug/app-debug.apk`）。

## 开源协议

本项目基于 [MIT License](LICENSE) 开源，第三方依赖许可说明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。欢迎 Star、提 Issue 或提交 PR 一起完善！

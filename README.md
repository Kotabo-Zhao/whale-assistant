# Whale Assistant（鲸鱼助手）

[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-3DDC84?logo=arm&logoColor=white)](https://developer.android.com/ndk/guides/abis)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25%20Native-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Python 3.11 Embedded](https://img.shields.io/badge/Python-3.11%20Embedded-3776AB?logo=python&logoColor=white)](https://chaquo.com/chaquopy/)
[![Version](https://img.shields.io/badge/Version-0.4.0--whale.1-0ea5e9)](https://github.com/Kotabo-Zhao/whale-assistant/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**运行在 Android 设备上的 100% 原生个人 Super Agent 与随身桌面萌宠。**  
Whale Assistant 采用轻量纯原生 Lite 架构（零 PRoot / Ubuntu 容器依赖，单 APK 约 63 MB），内嵌原生 **CPython 3.11（Chaquopy）** 与 **DeepSeek Harness** 核心工程内核，集成了 80+ 项进程内原生工具、编程式工具调用（PTC）、3+1 线程隔离并发后台 Worker、自动收藏记忆与 BGE 混合 RAG、无障碍极速控屏，以及支持双指缩放与右边缘拖拽收藏的原版「鲸鱼娘」悬浮桌宠——打开即聊，覆盖升级数据无损。

| 项目 | 说明 |
| :--- | :--- |
| 应用版本 | `0.4.0-whale.1`（Lite 纯原生版，~63 MB） |
| 应用包名 | `io.deepseekharness.mobile` |
| 最低系统 | Android 8.0（API 26）及以上，`arm64-v8a` |
| 核心引擎 | Kotlin 原生 SSE 流式引擎 + DeepSeek Harness 内核 + 内嵌 CPython 3.11（PTC）+ 3+1 并发 Worker 池 |
| 本地检索 | 内置 `BGE-base-zh-v1.5` 中文向量模型 + SQLite RRF 混合 RAG + 一键记忆体检去重 |
| 覆盖升级 | 固定 Nightly 签名密钥库 + 递增 `versionCode`，支持无缝覆盖安装 |
| 开源协议 | MIT（详见 [LICENSE](LICENSE)） |

## 核心特性

### 1. 随身桌面萌宠「鲸鱼娘」与实时双向同步
- **双指捏合无级缩放（`44dp–160dp`）**：悬浮在任意应用上层陪聊干活，头顶配备实时 Token/费用进度胶囊，支持热门动作预热与 60 FPS 轻柔呼吸摆动。
- **右边缘拖拽直接收藏**：在其他应用中拖动文字或图片至屏幕右侧发光停靠区，松手自动分析并沉淀进长期记忆库；同时支持无障碍自动收藏与系统截图自动入库。
- **桌宠 ↔ App 统一深蓝气泡与秒级双向同步**：App 主对话与桌宠悬浮小窗采用统一深蓝微边框卡片视觉，基于 `WhaleSharedChatStore` 自动实时同步对话记录与后台任务回传，切窗不断片。

### 2. 多模态流畅对话与清爽 4 大设置分组
- **多模态输入**：支持文字、系统原生语音识别输入（精简纯 STT 模式，告别杂乱 TTS 开关）、相册发图与**视频自动抽取关键帧**发送。
- **全双工中途插话与秘书卡片**：推理中不锁死输入，随时插话修正（`RunControl`）；聊到日程与提醒自动生成可一键验收的秘书待办卡片，每轮附带 2–3 条下一步行动胶囊。
- **精简清爽设置**：彻底移除繁重冗余的 Ubuntu/PRoot 选项，首页整合为 **常用 · AI 模型 · 人格与陪伴 · 外观与语言** 四大清晰分组，支持自定义角色人设、极简/可爱双风格与主题强调色切换。

### 3. 内嵌 CPython 3.11 + DeepSeek Harness 工程内核
- **内嵌原生 Python 3.11 + 编程式工具调用（PTC）**：无需安装任何外部 Linux 环境，手机本地毫秒级运行 Python 脚本；支持在 Python 内通过 `tools.<工具名>(**kwargs)` 串联多步原生工具并用 `inspect(...)` 返回精炼结论（内置防递归保护与 180 秒长超时支持），把原本 6–10 轮的工具调用压缩为 1 轮完成。
- **DeepSeek Harness 核心防线（`WhaleHarnessCore`）**：
  - **分层死循环检测器**：递归键排序规范化 JSON 参数，连失败/被拒调用也计入，连续 `3/5/8` 次分级提醒、`10` 次自动熔断。
  - **大输出自动落盘（`spill/`）+ 首尾保留裁剪 + 旧图自动卸载**：超预算工具输出先无损落盘（可用 `whale_read_resource` 按页回查），上下文按 Unicode 码点安全保留头部与尾部报错栈；多轮控屏自动卸载最旧截图，保留最新画面。
  - **分段并发 + 独占屏障 + 协议修复**：连续只读工具并行执行、写操作作为独占屏障；中途取消或异常时自动补齐缺失的 `tool_call_id` 占位结果。
  - **KV 缓存友好型结构化摘要压缩**：长任务超限时保持静态前缀不变，最大化复用服务端 KV Prefix Cache。
  - **代码工作区读后写新鲜度守卫**：记录已读文件版本防止盲改，局部替换多匹配时返回精确行号，并内置 `whale_todo_write` 结构化进度清单。

### 4. 3+1 线程隔离并发后台 Worker 与无障碍极速控屏
- **真正隔离的 3+1 并发后台 Worker**：3 个常规并发槽 + 1 个用户追问专属抢占槽，独立后台子代理 Prompt 防嵌套派发；Python 执行器采用**线程级 `sys.stdout`/`sys.stderr` 多路复用与作用域文件读写**，多路并发零串流，支持中途追问透传、断点续跑与独立实时进度预览（`livePreview`）。
- **无障碍极速控屏 v2**：自动剪枝 UI 树、智能屏蔽自身悬浮窗防止敏感窗口误自锁，遇到 WebView/自绘输入框自动启用剪贴板 `ACTION_PASTE` 兜底输入。
- **扩展生态**：支持自造工具引擎（`whale_tool_create`）、GitHub/URL 一键装 Skill 与远程 MCP 服务器接入。

## 系统架构

详细架构说明见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

```text
┌──────────────────────────────────────────────────────────────────┐
│  WebView UI 层（React + TypeScript + Capacitor）                  │
│  · 极简 / 可爱双视觉体系 · 4 大清爽设置分组 · 发图/视频抽帧/语音    │
│  · 统一深蓝气泡卡片 · App ↔ 桌宠秒级双向同步 · Live Plan Bar      │
└──────────────────────────▲───────────────────────────────────────┘
                           │ Capacitor Bridge & WhaleSharedChatStore
┌──────────────────────────▼───────────────────────────────────────┐
│  Android 原生内核层（100% Kotlin + 内嵌 Chaquopy CPython 3.11）   │
│  · WhaleNativeAgentEngine + WhaleHarnessCore（PTC/Spill/熔断/压缩）│
│  · WhaleCodeRunner + whale_runner.py（线程隔离 Python 3.11 + PTC） │
│  · WhaleNativeWorkerPool + WorkerRuntimeWake（3+1 并发后台与续跑） │
│  · WhaleAutoMemoryObserver + BGE 混合 RAG（自动收藏/截图/去重）     │
│  · OverlayBallService（双指缩放鲸鱼娘桌宠 + 右边缘拖拽停靠区）      │
│  · WhaleFastPhoneController（无障碍屏控 v2 + 剪贴板粘贴兜底）       │
└──────────────────────────────────────────────────────────────────┘
```

## 安装与构建

### 直接安装（推荐）
前往 [GitHub Releases](https://github.com/Kotabo-Zhao/whale-assistant/releases) 下载最新的 `whale-assistant-lite-*.apk` 直接安装或覆盖升级即可。

### 从源码构建
环境要求：Node.js `22.x` + `pnpm`（或 `npm`）、JDK `21` + Android SDK（API 35）。

```bash
npm install
npm run build
npm run lint
npx vitest run
npx cap sync android
cd android && ./gradlew assembleDebug
```

## 开源协议

本项目基于 [MIT License](LICENSE) 开源，欢迎 Star、提 Issue 或提交 PR 一起完善！

# Whale Assistant（鲸鱼助手）

[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-3DDC84?logo=arm&logoColor=white)](https://developer.android.com/ndk/guides/abis)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25%20Native-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Version](https://img.shields.io/badge/Version-0.4.0--whale.1-0ea5e9)](https://github.com/Kotabo-Zhao/whale-assistant/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**运行在 Android 设备上的 100% 原生个人 Super Agent。**  
Whale Assistant 采用轻量纯原生 Lite 架构（零 PRoot / Ubuntu 容器依赖），内置 Kotlin 流式多轮推理引擎、79 项进程内工具、自造工具引擎、持久化目标图、程序性习惯规则、智能模型路由、多模态发图、硅基流动语音、本地 BGE 混合 RAG 与原版鲸鱼娘悬浮桌宠——打开即聊，覆盖升级数据无损。

| 项目 | 说明 |
| :--- | :--- |
| 应用版本 | `0.4.0-whale.1`（Lite 纯原生版） |
| 应用包名 | `io.deepseekharness.mobile` |
| 最低系统 | Android 8.0（API 26）及以上，`arm64-v8a` |
| 核心引擎 | Kotlin 原生 SSE 流式引擎 + 79 项内置工具 + 自造工具引擎 + 后台 Worker 池 |
| 本地检索 | 内置 `BGE-base-zh-v1.5` 中文向量模型 + SQLite RRF 混合 RAG |
| 覆盖升级 | 固定 Nightly 签名密钥库 + 递增 `versionCode`，支持无缝覆盖安装 |
| 开源协议 | MIT（详见 [LICENSE](LICENSE)） |

## 核心特性

### 1. 全双工流式对话与主动幕僚引擎
- **随时插话修正与一键停止（`RunControl`）**：消息发送中不锁死输入框与语音键，可随时中途补充或修正意图（`whaleNativeChatSteer`）——跑工具期间会在本轮工具完成后无缝注入补充、不浪费已查结果；流式吐字期间会立即中止当前流并按最新修正重整回复。输入框为空时可一键停止生成（`whaleNativeChatCancel`）。
- **图形化实时执行计划条（Live Plan Bar）**：多步执行时以浮动小鲸鱼、薄荷绿进度圆点与三色跳动气泡直观展示进展，右侧提供纯图标「插话」与「停止」按钮。
- **同轮「下一步行动胶囊」**：每轮回复末尾零额外请求自动产出 2–3 条贴合上下文的可执行短建议，点一下即可连续推进。
- **多模态发图直连**：主对话支持一次最多选择 3 张图片，前端自动经 Canvas 压缩至 `1024px` JPEG 节省约 80% 视觉 Token，以 OpenAI 兼容 `image_url` 格式直发给多模态模型。

### 2. 持久化认知记忆与智能模型路由
- **持久化目标图（`WhaleGoalGraphStore`）**：支持 `whale_goal_create` / `whale_goal_update` / `whale_goal_list`，跨会话、跨天追踪长期目标、子步骤、下一步行动与阻塞点。
- **程序性记忆与习惯规则（`WhalePlaybookStore`）**：支持 `whale_playbook_save` / `whale_playbook_list`，把你认可的个人工作流沉淀为自动生效的长期习惯规则。
- **智能复杂度 + 预算路由（`WhaleModelRouter`）**：结合消息复杂度、是否带图与当日 `WhaleTokenMeter` 预算进度动态分级；高预算或超短闲聊自动降档省钱，且绝不篡改自定义中转模型名。
- **65%–80% Token 省流架构**：100% 静态 System Prompt 最大化 Prefix Prompt Caching、分层工具热加载（`whale_tools_expand`）、滑动 12 条历史窗口、滚动折叠两轮前冗长工具输出、4 线程并行执行只读工具与重复调用熔断。

### 3. 79 项原生工具 + Agent 自造工具引擎 + 硅基流动语音
- **79 项内置原生能力**：覆盖主人档案直改（`whale_owner_profile_edit`）、长期记忆更新与噪音清理、本地资料库读写、系统日历/闹钟/计时器、本地代码工作区、离线功能工坊、全软件 UI 实时操控（`WhaleAppControlBus`）、后台 Worker 并发池与无障碍屏控 v2。
- **自造工具引擎（`WhaleCustomToolEngine`）**：Agent 可在对话中通过 `whale_tool_create` 自行组合、编写并热加载新工具（支持 `composite` 编排、`http_json` HTTPS+SSRF 防护接口、隔离 WebView `js` 沙盒）。
- **单 Key 硅基流动语音**：同一把硅基流动 Key 同时支持主对话与桌宠的语音输入（`SenseVoiceSmall`）与语音朗读（`CosyVoice2-0.5B`）。

### 4. 原版鲸鱼娘悬浮桌宠与双视觉体系
- **原版鲸鱼娘（52 套光流 WebP 图集）**：图集体积优化至 41 MB，支持热门动作预热、60 FPS 轻柔呼吸与拖拽摆动、`44dp–160dp` 无级缩放；头顶配备奶霜蓝白渐变小鲸鱼 Token 计费胶囊（按 2026-11 主流模型公开单价实时核算，兼容 DeepSeek 峰谷 2× 与多厂商缓存命中）。
- **极简（`simple`）与可爱（`cute`）双风格**：可爱模式全面换装原版鲸鱼娘头像与 18 枚统一配色贴纸图标，首页采用图标磁贴与图形化减字气泡；极简模式保持高对比线性极简布局。

## 系统架构

详细架构说明见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

```text
┌──────────────────────────────────────────────────────────────┐
│  WebView UI 层（React + TypeScript + Capacitor）              │
│  · 极简 / 可爱双视觉体系 · 18 枚贴纸图标 · 多模态发图预览        │
│  · 全双工中途插话 · 图形化 Live Plan Bar · 一键下一步胶囊       │
└──────────────────────────▲───────────────────────────────────┘
                           │ Capacitor Bridge & WhaleAppControlBus
┌──────────────────────────▼───────────────────────────────────┐
│  Android 原生内核层（100% Kotlin，零 PRoot 依赖）              │
│  · WhaleNativeAgentEngine（SSE + RunControl 插话/取消 + 路由） │
│  · WhaleGoalGraphStore / WhalePlaybookStore / BGE 混合 RAG    │
│  · WhaleNativeToolRegistry（79 项内置工具 + 自造工具引擎）      │
│  · WhaleTokenMeter（2026-11 精准计费）+ WhaleVoiceEngine      │
│  · OverlayBallService（原版鲸鱼娘桌宠 + 奶霜计费胶囊）          │
│  · WhaleFastPhoneController（无障碍屏控 v2 + 安全红线守卫）     │
└──────────────────────────────────────────────────────────────┘
```

## 安装与构建

### 直接安装
前往 [Releases](https://github.com/Kotabo-Zhao/whale-assistant/releases) 下载最新的 `whale-assistant-lite-*.apk` 覆盖安装即可。

### 从源码构建
环境要求：Node.js `22.x` + `pnpm`、JDK `21` + Android SDK（API 35）。

```bash
pnpm install --frozen-lockfile
pnpm run build
pnpm run lint
pnpm run android:sync

cd android
./gradlew testDebugUnitTest -PwhaleSeparateRuntime=true assembleDebug --no-daemon
```

每日自动发版 CI 位于 `.github/workflows/daily-release.yml`（北京时间每日 `04:00` 自动构建，亦支持 `workflow_dispatch` 手动触发）。

## 安全与隐私边界

以下 6 道系统级安全红线在原生层强制生效，不可绕过：
- `AccessibilityAutomationPolicy`：锁屏/熄屏自动拒绝；系统设置、权限弹窗、密码框、验证码、支付与银行等敏感窗口一律硬拦截。
- `RuntimeCredentialCipher`：API Key 由 Android Keystore 加密存储，绝不向前端或日志明文泄露。
- `RootCommandGuard` & `RootSecurityWrapper`：高危系统命令与越权操作硬拦截。
- `SafeRootfsExtractor`：归档解压防路径穿越与特殊设备节点校验。
- `PetControlProtection`：悬浮桌宠操控期间保留用户随时暂停与接管控制权。

## 许可证

本项目基于 [MIT License](LICENSE) 开源；第三方开源组件及模型权重详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

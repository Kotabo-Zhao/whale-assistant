# Whale Assistant（鲸鱼助手）

[![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](https://developer.android.com/)
[![arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-3DDC84?logo=arm&logoColor=white)](https://developer.android.com/ndk/guides/abis)
[![Kotlin](https://img.shields.io/badge/Kotlin-100%25%20Native-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Version](https://img.shields.io/badge/Version-0.4.0--whale.1-0ea5e9)](https://github.com/Kotabo-Zhao/whale-assistant/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**运行在 Android 设备上的 100% 原生个人 Super Agent。**  
Whale Assistant 从早期容器方案彻底重构为轻量纯原生 Lite 架构：完全剥离 395 MB PRoot/Ubuntu 外部容器，内置原生 Kotlin 多轮流式引擎、60 项进程内工具、本地 BGE-base-zh-v1.5 混合 RAG、后台 Worker 并发池、无障碍屏控 v2 以及常驻全局总管悬浮桌宠——打开即聊，无需配置复杂运行环境。

| 项目 | 说明 |
| :--- | :--- |
| 应用版本 | `0.4.0-whale.1`（Lite 纯原生版） |
| 应用包名 | `io.deepseekharness.mobile` |
| 最低系统 | Android 8.0（API 26）及以上，仅 `arm64-v8a` |
| 核心引擎 | 纯原生 Kotlin SSE 流式引擎 + 60 项进程内工具 + 3 线程原生 Worker 池 |
| 本地检索 | 内置 `BGE-base-zh-v1.5` 中文向量模型 + SQLite 词法 RRF 混合 RAG |
| 覆盖升级 | 固定 Nightly 签名密钥库 + 递增 `versionCode`，支持无缝覆盖安装 |
| 开源协议 | MIT（详见 [LICENSE](LICENSE)） |

---

## 核心特性

### 1. 双旗舰视觉风格与全屏移动端体验
- **极简·类 Grok（`simple`）**：OLED 纯黑 / 纯白高对比、无边框社论式流式回复、极简悬浮胶囊输入框与 1px 细分割线。
- **鲸鱼娘·可爱（`cute`）**：暖奶白 / 星夜蓝配色、圆润立体触感按钮、浮动底部胶囊导航、鲸鱼娘光环舞台与 4 枚柔彩快捷卡片。
- **原生级流畅体验**：启动第 0 帧即应用主题无闪烁，首页全屏满视口适配安全区，内置多会话管理抽屉、`/` 快捷指令栏、URL/长文本一键归档与工具执行回执卡片。

### 2. 悬浮桌宠：常驻全局的 Super Agent Router
- **总管所有任务与完全权限**：桌宠不再是受限小弹窗，而是与 App 主界面拥有完全一致的 60 项原生工具权限的全局调度总管——简单问题即时答复或调用工具，复杂长耗时任务直接派发给后台 Worker，手机操作无缝接管原生屏控。
- **可爱头顶 Token 计费进度条**：`WhaleTokenMeter` 优先解析大模型流式/非流式响应中的真实 `usage`（含 DeepSeek `prompt_cache_hit_tokens` 缓存命中），按主流模型公开单价实时估算当日用量与人民币费用，在桌宠头顶以圆角胶囊进度条动态展示（随当日额度从薄荷绿 → 暖杏橙 → 珊瑚粉渐变）。
- **自适应底部抽屉对话窗**：基于 `Gravity.BOTTOM` 与 `frame.bottom` 精准避让系统软键盘，消除打字遮挡与退格键失灵问题。
- **实时屏控提示与防断连**：发起屏控时自动收起对话窗并跳过自身悬浮层锁定前台目标应用，底部同步显示不抢焦点、不挡点击的星蓝状态胶囊（如 `🐳 正在读取屏幕…` / `🐳 操控：click「搜索」`）。

### 3. 自动本地混合 RRF RAG 检索
- 内置离线中文向量模型 `BGE-base-zh-v1.5` 与 SQLite 全文索引。
- `WhaleNativeRagRetriever` 在每轮对话前自动并行执行向量检索 + 词法检索与 RRF 融合排序（600ms 超时上限，超时或低配机自动无感降级词法检索），自动注入最相关的 `[资料#N]` 与 `[记忆#N]` 片段，无需手动触发。

### 4. 60 项原生工具、后台 Worker 与离线功能工坊
- **全软件实时操控（`WhaleAppControlBus`）**：对话中一句话即可切换页面、切换极简/可爱双风格与明暗主题、切换语言、管理人设卡片、启停或调整桌宠、修改自治开关。
- **原生后台 Worker 并发池（`WhaleNativeWorkerPool`）**：支持 `whale_worker_submit` / `whale_worker_status` / `whale_worker_cancel`，3 线程异步跑完长耗时任务后自动回写 `WorkerDashboardStore` 看板与 `WorkerReportInbox`。
- **本地代码工作区与离线工坊**：支持多文件项目读写/补丁/删除，以及在「功能工坊」中通过自然语言生成离线微应用（支持 `window.whaleStorage` 本地持久化）并立即启用打开。

### 5. 高性能原生无障碍屏控 v2
- **语义剪枝控件树（`WhaleUiTreeCompactor`）**：剔除 60%–80% 无效布局噪声并生成短 `#ref` 索引与中心坐标，大幅降低模型 Token 消耗与思考延迟。
- **多策略定位 + 手势回退（`WhaleFastPhoneController`）**：依次按 `#ref` → `viewId` → 文本与可点击父节点 → 坐标命中；遇到 Flutter / Compose / WebView 控件自动无缝切到 `dispatchGesture` 触控与滑动。
- **原子批量连贯操控**：`whale_phone_batch` 支持单轮最多 8 步连续操作并回传最新精简控件树。

### 6. 一站式权限与自治中心 + 智能功耗调度
- **极简 3 总开关**：告别十几个分散子开关，在设置页提供「一键模式（全权自治 / 均衡 / 保守）」+ **3 个核心总开关**（桌宠与后台 Worker 完全权限、记忆/资料库与后台理解全自动、主动陪伴/通知与日程简报），底部附带悬浮窗、无障碍、通知读取 3 个系统权限快捷入口。
- **三档功耗治理（`WhalePowerGovernor`）**：自动在性能 / 均衡 / 省电模式间调节动效与后台唤醒频率。

---

## 系统架构

```text
┌──────────────────────────────────────────────────────────────┐
│  WebView UI 层（React + TypeScript + Capacitor）              │
│  · 极简 / 鲸鱼娘双视觉体系 · 多会话流式对话 · 一站式权限中心     │
│  · 记忆与资料管理 · 事项日程 · 离线功能工坊（whaleStorage）     │
└──────────────────────────▲───────────────────────────────────┘
                           │ Capacitor Bridge & WhaleAppControlBus
┌──────────────────────────▼───────────────────────────────────┐
│  Android 原生内核层（100% Kotlin，零 PRoot 依赖）              │
│  · WhaleNativeAgentEngine（SSE 流式 + 多轮工具 + 真实 usage）  │
│  · WhaleNativeRagRetriever（BGE-base-zh-v1.5 + SQLite RRF）   │
│  · WhaleNativeToolRegistry（60 项进程内工具）                  │
│  · WhaleNativeWorkerPool（3 线程后台异步 Worker 池）           │
│  · OverlayBallService（全局总管桌宠 + Token 进度条 + 屏控提示） │
│  · WhaleFastPhoneController（无障碍屏控 v2 + 安全策略守卫）     │
└──────────────────────────────────────────────────────────────┘
```

---

## 安装与使用

### 1. 下载安装
1. 前往本仓库 [Releases](https://github.com/Kotabo-Zhao/whale-assistant/releases) 下载最新的 `whale-assistant-lite-*.apk`（每天北京时间 04:00 自动构建更新）。
2. 若手机上曾安装过早期未固定签名的版本，请**卸载旧版一次**后安装新包；自固定签名版本起，后续所有每日构建均支持**直接覆盖安装**，完整保留记忆、资料、对话记录与 API Key。

### 2. 快速上手
1. 打开应用后进入「设置 → 模型与密钥」，填入任意受支持供应商的 API Key 即可开聊。
   - 内置支持：**DeepSeek、OpenAI、Anthropic、Google Gemini、OpenRouter、Groq、xAI、Mistral** 以及任意 **OpenAI 兼容自定义端点**。
2. 在设置顶部「一站式权限与自治」中按需一键切换自治模式或开启 3 个核心总开关，并按快捷按钮授予悬浮窗 / 无障碍 / 通知权限。
3. 开启悬浮桌宠后，在任意应用内轻点桌宠即可随时呼出全局总管对话框。

---

## 从源码构建

### 环境要求
- Node.js `22.x` + `pnpm`
- JDK `21` + Android SDK（API 35）

### 本地编译 Lite 原生 APK
```bash
# 1. 安装前端依赖并编译、同步到 Android 工程
pnpm install --frozen-lockfile
pnpm run build
pnpm run lint
pnpm run android:sync

# 2. 运行全量 Android 单元测试并构建 Lite 原生 APK
cd android
./gradlew testDebugUnitTest -PwhaleSeparateRuntime=true assembleDebug --no-daemon
```

构建产物默认输出在：
`android/build/whale-light-app/outputs/apk/debug/app-debug.apk`

### 每日自动发版 CI
仓库内置 `.github/workflows/daily-release.yml`：
- 每天北京时间 `04:00`（UTC `20:00`）自动执行前端构建、ESLint 0 警告检查、Android 全量单测、固定签名 Lite APK 打包并发布到 GitHub Releases。
- 支持随时通过 GitHub Actions `workflow_dispatch` 手动触发，并在发版后自动运行云端 Android 14 模拟器冒烟测试（`scripts/ci-emulator-smoke.sh`）。

---

## 安全与隐私边界

尽管赋予了主界面与桌宠高度自治能力，以下 6 道系统级安全红线始终在原生层强制生效，不可绕过：

- **`AccessibilityAutomationPolicy`**：锁屏/熄屏自动拒绝；系统设置、权限弹窗、密码框、验证码、支付与银行等敏感窗口一律硬拦截。
- **`RuntimeCredentialCipher`**：API Key 由 Android Keystore 加密保护，不向前端或日志明文泄露。
- **`RootCommandGuard` & `RootSecurityWrapper`**：高危系统命令与越权操作硬拦截。
- **`SafeRootfsExtractor`**：归档解压防路径穿越与特殊设备节点校验。
- **`PetControlProtection`**：悬浮桌宠操控期间保留用户随时接管与暂停控制权。

---

## 许可证

本项目基于 [MIT License](LICENSE) 开源；内置的第三方开源组件及模型权重沿用其各自许可证。

# Whale Assistant 架构说明

Whale Assistant（`0.5.0-whale.1`）是一款 100% 原生 Android 个人 Super Agent，采用 **React + Capacitor 壳层** 与 **Kotlin 原生内核 + 内嵌 CPython 3.11** 分离架构，在 Lite 构建模式（`-PwhaleSeparateRuntime=true`）下无需任何 PRoot / Ubuntu 容器或离线 ONNX 向量模型即可运行全部核心能力。

## 1. 前端交互层（`src/`）

- **主对话工作台（`src/screens/WhaleChatScreen.tsx`）**：多会话持久化（最多 24 个会话）、原生 SSE 逐字流式渲染、中途插话修正（`whaleNativeChatSteer`）、一键中止（`whaleNativeChatCancel`）、实时步骤/计划反馈、行动回执卡（待办/记忆/资料/工坊）、同轮下一步建议胶囊（`<!--whale_next:[...]-->`）、Canvas 图片压缩、视频关键帧抽取与原生语音识别输入。
- **三主题 Quiet Luxury V3 视觉体系（`src/whale-studio-v2.css`、`src/whale-design.css`、`src/whale-motion.css`、`src/theme.ts`、`src/components/CuteIcon.tsx`）**：支持 `simple`（极简·静奢编辑）、`cute`（鲸鱼娘·雾面瓷白）与 `usagi`（乌萨奇·温润手账），同步控制 Android 状态栏与导航栏配色，支持强调色切换与减少动效开关。
- **设置与工作台中心（`src/App.tsx`、`src/ui/appNavigation.ts`、`src/components/WhaleAutonomyCard.tsx`）**：聚焦「常用 · AI 模型 · 人格与陪伴 · 外观与语言」四大设置分组，集成自治预设（`fluent / balanced / strict`）、44dp–160dp 桌宠无级缩放、供应商密钥管理、记忆与资料库、任务面板及离线功能工坊。
- **前端桥接层（`src/platform/native.ts`、`src/platform/types.ts`）**：统一对接 Android 侧 `MobileRuntimePlugin` 暴露的 144 个 `@PluginMethod`。

## 2. Android 原生核心层（`android/app/src/main/java/io/deepseekharness/mobile/`）

### 2.1 多轮流式推理引擎与 Harness 工程防线
- **主推理循环（`companion/WhaleNativeAgentEngine.kt`）**：
  - **Prefix Cache 友好结构**：静态角色人设与核心工具规则固定在 `system` 提示词中；主人档案、当前时间、未完成待办、Worker 摘要、目标图、Playbook、Skill/MCP 目录、RAG 片段与动态世界书统一注入在最新 `user` 消息前，避免破坏服务端前缀缓存。
  - **全双工控制（`RunControl`）**：支持推理中即时打断重跑与工具间隙无缝插话注入。
  - **瞬时故障重试与慢工具自动转后台**：基于 `WhaleSuperagentCore.isTransientFailure` 对 408/429/5xx 与网络抖动执行指数退避重试；前台 `whale_web_fetch` / `whale_mcp_call` 超过 5 秒自动提升为后台 Worker 任务（`promoted_to_background`）。
  - **Playbook 自动沉淀**：连续成功 ≥3 步的屏控路径自动提炼写入 `WhalePlaybookStore`。
- **DeepSeek Harness 原语集合（`companion/WhaleHarnessCore.kt`）**：
  - `RepeatToolGuard`：递归键排序规范化 JSON 参数，连续 `3/5/8` 次分级提醒、`10` 次硬熔断（用户新输入或 `steer` 重置）。
  - `SpillAndPruneManager`：超过 6,400 字符的工具输出先无损落盘至 `whale-spills/`（上限 60 份，支持 `whale_read_resource` 按页回查），并按 Unicode 码点安全保留首尾；两轮前历史工具输出零成本首尾裁剪。
  - `ImageOffloadPolicy`：多轮视觉/截屏任务自动卸载最旧 `image_url`，仅保留最新 4 张。
  - `ToolBatchScheduler` & `ToolCallRecovery`：连续只读工具分入并行组（4 线程执行），写操作作为独占屏障；异常或中止时自动补齐缺失的 `tool_call_id` 占位结果。
  - `FsObservationGate`（`mtime + size` 读后写新鲜度校验）、`SessionTodoStore`（整表任务清单）、`PrefixCacheCompactor`（上下文超 56k 字符时触发 KV 友好 `<compacted-summary>` 检查点）、`ContextForkHelper`（从 `WhaleSharedChatStore` 提取父会话近期前缀供 Worker 继承）。

### 2.2 内嵌 Python 3.11 + PTC 与 3+1 并发 Worker
- **内嵌 CPython 3.11 与 PTC（`companion/WhaleCodeRunner.kt`、`android/app/src/main/python/whale_runner.py`）**：
  - 通过 Chaquopy 在进程内直接运行 Python 3.11，单次执行超时范围 `3–180s`。
  - 采用 `_ThreadLocalStream` 隔离并发线程的 `sys.stdout`/`sys.stderr`，并通过 `_scoped_open` 将相对路径约束在项目工作目录内。
  - 暴露 `tools.<工具名>(**kwargs)` 编程式工具调用桥接（单次最多 16 次子调用、禁止递归调用 `whale_code_run`）与 `inspect(...)` 精炼输出。
- **3+1 并发 Worker 池与调度内核（`companion/WhaleNativeWorkerPool.kt`、`companion/WhaleSuperagentCore.kt`）**：
  - 3 个常规后台并发槽 + 1 个用户交互专属抢占槽（`WorkerSlots`），保证人类追问不被后台任务饿死。
  - 确定性 7 态 `TicketStage`（`person/delivery` > `person/answer` > `person/review` > `person/blocked|failed` > `working/queued` > `idle` > `working`）与 `waitsForPerson` 挂起判定。
  - 支持追问直达运行中 Worker（`deliverFollowup`，区分 `deliveredInputs` 与 `readInputIds`）、600 字符截断预览 + `read: "worker/<id>"` 按需分页读取。
  - 内置 SQLite 事务型持久副作用队列 `WhaleEffectsQueue`（`generation + seq` CAS + `LaterException` 无惩罚延迟）与主动智能 `off / observe / auto` 三档模式（`ProactiveObserveStore`）。

### 2.3 原生工具注册与开放扩展生态
- **94 项进程内原生工具（`companion/WhaleNativeToolRegistry.kt`）**：
  - 核心记忆、资料库、秘书待办、联网检索、日历闹钟、Worker、语音与自造工具常驻；设置、人设、桌宠、功能工坊、代码工程、手机控屏、设备状态与 MCP/Skill 扩展按用户输入意图自动按需加载，并支持 `whale_tools_expand` 运行时热展开，大幅节省 Schema Token。
- **自造工具引擎（`companion/WhaleCustomToolEngine.kt`）**：允许 Agent 创建 `custom_*` 工具（上限 40 个），覆盖 `composite`（内置工具流水线，禁递归）、`http_json`（仅允许 `https://` 且拦截本机/内网 SSRF 地址）和 `js`（无网无文件隔离 WebView 沙盒，5s 超时）。
- **标准 Skill 与远程 MCP（`companion/WhaleSkillStore.kt`、`companion/WhaleMcpManager.kt`）**：
  - 支持从 GitHub 仓库/文件链接或 HTTPS 直链安装 `SKILL.md`（上限 64 个，每轮将已启用目录摘要注入上下文）。
  - 完整实现 Streamable HTTP / JSON-RPC 2.0 MCP 客户端（`initialize` 握手、`Mcp-Session-Id` 会话保持、JSON/SSE 双响应解析），自动将远端工具映射为 `mcp__<serverId>__<toolName>`。
- **本地代码工作区与离线工坊（`companion/WhaleNativeCodeWorkspace.kt`、`features/WhaleFeatureStore.kt`）**：支持多项目文件精读/写入/补丁/搜索，以及单文件高达 512KB 的离线 HTML 小软件创作、持久化存储（`window.whaleStorage`）与原生桥接（`window.whaleApp`）。

### 2.4 记忆、检索、路由、计费与语音
- **检索与记忆体系**：
  - `companion/SemanticEmbeddings.kt` + `SemanticIndex.kt` + `WhaleNativeRagRetriever.kt`：**已移除 103 MB 离线 BGE ONNX 模型与 `onnxruntime` 原生库**；默认走零成本本机 SQLite 词法检索（资料库 600ms / 长期记忆 400ms 并行预算）+ 大模型自主检索。若用户配置了 OpenAI 兼容 `/embeddings` 接口（默认兼容硅基流动 `BAAI/bge-base-zh-v1.5`，768 维），自动启用本地 SQLite 向量缓存 + 词法 RRF 混合检索。
  - `companion/WhaleAutoMemoryObserver.kt`：零空闲 Token 消耗监听无障碍「收藏成功」信号与 `MediaStore` 系统截屏，跳过银行/支付/密码页与密码框后自动入库并触发后台语义提炼。
  - `companion/WhaleGoalGraphStore.kt`（跨会话持久化目标图）、`companion/WhalePlaybookStore.kt`（程序性习惯规则库）、`companion/WhaleSharedChatStore.kt`（App ↔ 桌宠 40 条共享会话双向同步）。
- **模型路由与精准计费**：
  - `companion/WhaleModelRouter.kt`：`FAST / BALANCED / DEEP` 三级复杂度路由 + 当日预算 ≥85% 自动降级 + 用户分场景模型覆写。
  - `companion/WhaleTokenMeter.kt`：兼容 DeepSeek、OpenAI/Qwen/GLM、Anthropic 三种缓存命中格式，按公开单价（含 DeepSeek 工作日 UTC 峰谷 2× 费率）分场景（`chat / worker / phone`）核算并实时同步到桌宠胶囊。
- **双模语音引擎（`companion/WhaleVoiceEngine.kt`）**：前端交互聚焦轻量语音识别（STT）输入；默认零配置离线调用 Android 原生 `SpeechRecognizer` / `TextToSpeech`，配置硅基流动 Key 后可升级为 `SenseVoiceSmall` ASR + `CosyVoice2-0.5B` TTS，云端异常自动回退本地 TTS。

### 2.5 悬浮桌宠、极速控屏与虚拟副屏
- **悬浮桌宠（`OverlayBallService.kt`、`overlay/CompanionPetDrawable.kt`、`companion/CompanionBubbleWindow.kt`）**：52 套光流 WebP 图集、44dp–160dp 双指缩放、60 FPS 呼吸动画、右边缘拖拽收藏停靠区与共享深蓝气泡对话小窗。
- **无障碍极速控屏 v2（`accessibility/WhaleFastPhoneController.kt`、`accessibility/WhaleUiTreeCompactor.kt`、`companion/WhaleVisionScreenAnalyzer.kt`）**：语义剪枝控件树（`#ref` 短编号）、`dispatchGesture` 手势回退、最多 8 步原子批量执行 `fastBatch`、剪贴板粘贴兜底，以及多模态视觉识屏定位。
- **独立虚拟副屏与 Shizuku（`virtualscreen/`、`shizuku/`）**：可选在独立虚拟屏幕运行/预览目标应用，或在用户显式授权 Shizuku / Root 后提供受控设备命令能力。

## 3. 安全红线（不可绕过）

| 模块 | 职责与硬边界 |
| :--- | :--- |
| `accessibility/AccessibilityAutomationPolicy.kt` | 永久排除 `android`、`com.android.settings`、`com.android.systemui`、安装器与权限控制器包名；文本先去零宽字符与全角空格再拦截密码/验证码/支付/生物识别窗口；不可逆点击走确认守卫 |
| `runtime/RuntimeCredentialCipher.kt` | 使用不可导出的 Android Keystore `AES/GCM/NoPadding`（256-bit + AAD）加密所有供应商 API Key |
| `companion/WhaleCustomToolEngine.kt` | 自造 `http_json` 工具强制 HTTPS 并拦截 `localhost`、回环、私网 IPv4/IPv6 与内网域名；`js` 工具在无网无文件隔离 WebView 运行并限制 5s 超时 |
| `companion/WhaleAutoMemoryObserver.kt` | 自动收藏与截屏采集严格跳过银行/钱包/支付/密码管理器包名及含密码输入控件的窗口 |
| `companion/PetControlProtection.kt` | 防止悬浮桌宠自身窗口干扰目标应用控屏或触发误自锁 |
| `shizuku/RootCommandGuard.kt` & `RootSecurityWrapper.kt` | Root 命令去转义/引号归一化拦截刷机、块设备直写、核心分区改写与关闭 SELinux；无挂载命名空间降级时自动启用 `STRICT_RULES` |

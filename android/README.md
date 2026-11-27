# Android 原生工程说明

本目录是 **Whale Assistant（鲸鱼助手 `0.5.0-whale.1`）** 的 Capacitor 7 + Kotlin + Chaquopy Android 工程。目标 SDK 为 **API 35**，最低支持 **Android 8.0（API 26）**，仅打包 `arm64-v8a`。

当前主线发布形态为 **Lite 纯原生版（`-PwhaleSeparateRuntime=true`）**：零 PRoot / Ubuntu `rootfs.bundle` 依赖，内嵌原生 **CPython 3.11（Chaquopy）** 与全部 Kotlin Super Agent 内核，单 APK 约 63 MB。

## 构建环境与命令

### 工具链要求
- Node.js `22.19.0`（或 `>=24.0.0`）+ `pnpm`（`11.x`）
- JDK `21`（`compileOptions` / `kotlin.jvmToolchain(21)`）
- Gradle `8.11.1`（摘要固定在 `gradle/wrapper/gradle-wrapper.properties`）
- Android SDK 35、NDK `27.1.12297006`、CMake `3.22.1`

### 常用构建命令（在仓库根目录执行）

```bash
# 1. 安装前端依赖
pnpm install --frozen-lockfile

# 2. 编译前端并同步到 android/app/src/main/assets/public
pnpm run android:sync

# 3. 一键构建 Lite 原生 Debug APK（推荐）
pnpm run android:build:lite
```

等价 Gradle 命令（在 `android/` 目录执行）：

```bash
./gradlew testDebugUnitTest -PwhaleSeparateRuntime=true assembleDebug --no-daemon
```

- Lite 模式通过 `collectLightAssets` 自动排除 `runtime/rootfs.bundle` 与 `legal/ubuntu-packages/**`，构建产物输出至 `android/build/whale-light-app/outputs/apk/debug/app-debug.apk`。
- `preBuild` 会自动执行 `generateLegalAssets`（打包应用与前端依赖许可证）和 `generateRichChatAssets`（打包 KaTeX / markdown-it 离线资源）；`packageDebug` / `packageRelease` 前会自动执行 `verifyShellAssets` 校验前端入口完整。
- 严禁在 Gradle 文件或 `local.properties` 中写入签名密码、API Key 或其他凭据。

## 签名策略（覆盖安装一致性）

包名固定为 `io.deepseekharness.mobile`（`app/build.gradle` 的 `applicationId`）。

1. **每日构建 / Debug（`whaleNightly`）**：未配置团队签名时，`debug` 变体默认使用仓库内置的固定测试密钥库 `android/app/whale-nightly-debug.keystore`，配合递增 `whaleVersionCode`（CI 使用 `147 + GITHUB_RUN_NUMBER`），确保每日 Lite APK 可直接无缝覆盖安装且本地记忆/设置无损。
2. **正式 Release（团队签名，fail-closed）**：
   - 本地：在 `android/keystore.properties` 配置团队密钥库（模板见 `keystore.properties.example`，密钥库与密码严禁提交 Git）。
   - CI：通过环境变量 `DSH_KEYSTORE_BASE64` 与 `DSH_KEYSTORE_PASSWORD` 注入。
   - 两者均缺失时，执行 `assembleRelease` / `bundleRelease` / `packageRelease` 会在 Gradle 阶段直接中止，防止误用个人密钥签名分发正式版。
   - 签名指纹校验：`python3 scripts/verify-apk-signature.py --apk <apk 路径>`。

## 构建策略：发布版不混淆代码

`app/build.gradle` 的 `release` 构建刻意保持 **`minifyEnabled false`** 与 **`shrinkResources false`**，这是项目既定工程约定，切勿为了微小体积收益改回：

1. **反射与动态绑定链路敏感**：Shizuku 的 `DeviceShellUserService`、Capacitor 插件注册（`MobileRuntimePlugin`）、`FileProvider` authority 解析以及 Chaquopy Python 桥接均依赖可读的类名与资源名，R8 改写会导致难以定位的连接超时或桥接失效。
2. **崩溃栈直接可读**：线上真机反馈的堆栈无需对照 `mapping.txt` 反混淆，便于快速排障。
3. **资源路径稳定**：关闭 `shrinkResources` 可避免资源名被改写导致 `network_security_config.xml` 等配置排查困难。

若未来确有合规场景必须开启混淆，必须同步补齐 Shizuku / Capacitor / FileProvider / Chaquopy 的 `proguard-rules.pro` 保留规则，并在真机完成冷启动、原生对话、Python PTC、桌宠、无障碍控屏与 Shizuku 连接全链路回归验证。

## 原生运行时与安全边界

- **唯一 JS 桥接**：`MobileRuntimePlugin` 是前端访问原生能力的唯一入口（144 个 `@PluginMethod`），所有入参均在原生层二次校验。
- **密钥加密存储**：`RuntimeCredentialCipher` 使用不可导出的 Android Keystore `AES/GCM/NoPadding`（256-bit + AAD）加密全部模型供应商密钥。
- **内嵌 Python 隔离**：`WhaleCodeRunner` + `whale_runner.py` 以线程局部 `stdout`/`stderr` 与项目目录作用域 `open()` 隔离并发 Worker，PTC 子工具调用上限 16 次且禁止递归调用 `whale_code_run`。
- **无障碍与控屏红线**：`AccessibilityAutomationPolicy` 永久排除系统设置、SystemUI、安装器与权限控制器包名；文本先去除零宽字符与全角空格再拦截密码、验证码、支付及生物识别界面；`WhaleAutoMemoryObserver` 同样跳过敏感包名与密码输入框。
- **自造工具与外部扩展沙盒**：`WhaleCustomToolEngine` 强制 `http_json` 仅用 HTTPS 并拦截回环/私网 SSRF 地址，`js` 工具运行于无网无文件隔离 WebView（5s 超时）；`WhaleMcpManager` 仅允许 HTTPS（本机/局域网除外）。
- **Shizuku 与 Root 可选特权**：`ShizukuProvider` 与 `DeviceShellUserService` 仅在用户显式授权后工作；Root 通道受 `RootCommandGuard`（归一化静态拦截 + 无命名空间降级 `STRICT_RULES`）与 `RootSecurityWrapper` 约束。
- **审计与备份策略**：`AndroidManifest.xml` 明确禁用系统云备份（`allowBackup="false"`），本地审计日志存于 `noBackupFilesDir`（目录 `0700`、文件 `0600`，90 天按 UTC 日轮转）。

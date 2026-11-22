# Release Checklist（`0.4.0-whale.1` Lite 纯原生版）

本清单对应当前 **Whale Assistant Lite 纯原生版**（`-PwhaleSeparateRuntime=true`，零 PRoot / Ubuntu `rootfs.bundle` 依赖，内嵌 Chaquopy CPython 3.11，单 APK 约 63 MB）。发版或合并关键改动前，按以下条目逐项核验。

## 1. 版本与自动化 CI 门禁

- **版本一致性**：确认 `package.json` 的 `version` 与 `android/app/build.gradle` 的 `versionName` 一致（当前 `0.4.0-whale.1`），`versionCode` 递增（每日构建通过 `-PwhaleVersionCode=$((146 + GITHUB_RUN_NUMBER))` 自动递增）。
- **前端构建与静态检查**：
  ```bash
  pnpm install --frozen-lockfile
  pnpm run build      # tsc -b && vite build
  pnpm run lint       # eslint . --max-warnings 0
  pnpm test           # vitest run
  ```
- **脚本与清单契约检查**：
  ```bash
  pnpm run test:scripts
  python3 scripts/check-android-resources.py
  node scripts/validate-manifests.mjs
  ```
- **凭证泄露扫描**：确认未提交 `.env`、个人密钥库、API Key 或超过 100 MB 的大文件；必要时对本次推送区间执行 `bash scripts/ci-gitleaks-range-scan.sh`。

## 2. Lite APK 构建与测试

- **同步前端资源并跑 Android 单元测试 + Lite 打包**（与 `.github/workflows/daily-release.yml` 一致）：
  ```bash
  pnpm run android:sync
  cd android && ./gradlew testDebugUnitTest -PwhaleSeparateRuntime=true assembleDebug --no-daemon
  ```
- **验证 APK 体积与资产纯净度**：
  - 确认 Lite APK 中**不包含** `assets/runtime/rootfs.bundle` 与 `assets/legal/ubuntu-packages/`。
  - 确认 `assets/public/index.html`、`assets/rich-chat/`（KaTeX / markdown-it）与 `assets/legal/`（`LICENSE`、`THIRD_PARTY_NOTICES.md` 及直连前端依赖许可文本）完整打包。
- **16KB 页大小对齐检查**：若更新了原生 `.so` 依赖（含 Chaquopy / CMake 产物），核验打包 ELF 的 `LOAD` 段满足 16KB 对齐（`p_align == 0x4000`）。

## 3. 签名与发布产物

- **Daily Nightly 构建（`.github/workflows/daily-release.yml`）**：
  - 每日北京时间 04:00（`0 20 * * *` UTC）或手动 `workflow_dispatch` 触发，使用 Node `22.19.0`、JDK `21`、Gradle `8.11.1`。
  - 使用固定 `android/app/whale-nightly-debug.keystore` 签名，发布 `whale-assistant-lite-YYYYMMDD-<short_sha>.apk` 与 `SHA256SUMS.txt` 至 `nightly-YYYYMMDD` prerelease。
  - 自动运行 **Cloud Android Emulator Smoke Test**（Android 14 / API 34 Pixel 6，`scripts/ci-emulator-smoke.sh`），并将 `smoke-test-report.txt`、`smoke-home.png`、`smoke-after-tap.png` 附到 Release。
- **正式 Release 构建**：
  - 必须配置团队签名（本地 `android/keystore.properties` 或 CI 环境变量 `DSH_KEYSTORE_BASE64` + `DSH_KEYSTORE_PASSWORD`），严禁使用个人密钥分发。
  - 打包后执行 `python3 scripts/verify-apk-signature.py --apk <apk 路径>` 核对签名指纹，并生成 `SHA256SUMS.txt`。

## 4. 真机冒烟与核心能力验收

发正式版或触及核心链路改动时，在 `arm64-v8a` 真机（推荐覆盖升级安装）上核对以下项目：

1. **冷启动与覆盖升级**：从上一版直接覆盖安装，确认历史对话、长期记忆、主人档案、角色卡与 API Key（Keystore 加密）完整保留，无签名冲突或白屏。
2. **原生流式对话与 Harness 防线**：验证 SSE 流式输出、多轮工具调用、推理中途插话（`steer`）与一键停止（`cancel`），以及中文输入法剪贴板粘贴正常（`capacitor.config.ts` 中 `captureInput: false` 不可回退）。
3. **内嵌 Python 3.11 + PTC 与后台 Worker**：验证 `whale_code_run` 可离线执行 Python 并通过 `tools.*` 编排工具；同时派发多个后台 Worker 时输出无串流且主对话追问不被阻塞。
4. **悬浮桌宠与双向同步**：验证 44dp–160dp 双指缩放、头顶 Token 计费胶囊、右边缘拖拽文字/图片入记忆，以及 App 主对话与桌宠小窗秒级同步。
5. **无障碍极速控屏与安全红线**：验证 `#ref` 剪枝树与 `fastBatch` 控屏正常，同时在密码输入框、系统设置、支付/验证码页面下触发硬拦截且不泄露敏感信息。
6. **视觉、语音与扩展**：验证相册发图/识屏、原生离线语音输入（及可选硅基流动升级）、自造工具（SSRF 拦截生效）、`SKILL.md` 安装与远程 MCP 工具调用。

## 5. 可选本机回归脚本（按需运行）

- **无依赖桌面/移动视口布局回归**：启动 `pnpm run preview -- --port 4173 --strictPort` 后运行 `node scripts/check-mobile-layout-cdp.mjs`，检查 363×800 / 800×363 / 1280×900 视口与浅深主题无横向溢出。
- **真机仪器化测试**：连接测试机后在 `android/` 运行 `./gradlew connectedDebugAndroidTest -PwhaleSeparateRuntime=true`。

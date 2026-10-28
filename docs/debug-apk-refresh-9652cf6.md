# 受限空间下的开发包记录

本记录对应应用源码 `9652cf6`，不替代完整 Gradle 构建或真机验收。开发包以已验证的 `5c22000` 开发包为基础，替换重新编译的全部 13 个 DEX、当前前端文件和插件管理脚本，再进行 zipalign 和 Android debug 签名。保留了基础包内的运行环境；该方法仅适用于已核对清单及构建产物的开发快照。

- 基础包 SHA-256：`3AE33E6C63EFE7DA890D3AC52BD8E356E8B30528298F24BE72F6E02190C08C01`。
- 本次开发包 SHA-256：`3A7F158AAC0E240F2F17E208D1F182C685255E9A78DB2B52B966ECE7AAD38111`。
- 基础包与最新源码之间 Android 主文件的变更仅涉及 Kotlin 源码和插件管理脚本；前端构建产物单独核对。构建快照中未包含工作区原有未提交的 `CompanionReminder.kt` 改动。
- 替换前，逐一比对基础包 DEX 和原 Gradle 构建产物的哈希，确定 13 个条目的映射；重新运行 Gradle DEX 合并任务后沿用该映射。映射和旧构建产物的清理凭据保存在本机 `E:\\WhaleAgentDexMapping-5c22000.json` 与 `E:\\WhaleAgentRefresh-9652cf6-baseline.json`。
- 使用 `verify-refreshed-debug-apk.py` 比对非前端条目、当前 DEX 和插件管理脚本；使用 `verify-public-apk.py` 比对前端；使用 `verify-memory-apk.py` 检查内置运行环境。另通过 Android `apksigner verify --verbose` 和 `zipalign -c -P 16 4`。

`refresh-debug-apk.py` 只接受上述指定哈希的基础包，但并不能自动证明未来的源码、资源、依赖或 DEX 映射仍然兼容。任何后续使用都必须重新核对这些前提，并重新执行全部内容、签名和对齐检查。最终包需要在目标手机上安装、启动及实际操作测试；本次未进行真机测试。

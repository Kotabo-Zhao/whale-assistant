# 手机 Agent 功能调研与当前实现

调研时间：2026-10-28。目标设备为荣耀 Magic6，目标是免 root 的本机助手。

## 可借鉴的方向

| 功能 | 参考 | 当前工程状态 |
| --- | --- | --- |
| 读取界面、点击、滑动、输入、启动应用 | [android-agent](https://github.com/Fox-Islam/android-agent) | 上游已有无障碍和设备工具；能力取决于授权和目标应用 |
| 观察、执行一步、重新观察和校验 | [dsh-mobile-gui-agent](https://github.com/kunjinkao-os/dsh-mobile-gui-agent) | 上游已有设备工具和任务台账；本次未移植该 ADB 插件，也未声称其验证闭环已在本机运行 |
| 跨应用操作、文件整理、网页信息整理 | [Orbit Assistant](https://github.com/lpnovi/Orbit-Assistant) | 依赖已有工具及模型；本次加入四类可编辑任务草稿，需用户复制到对话并补充具体目标 |
| 任务停止、悬浮入口、副屏 | [Pony](https://pony.karlmagendavid.com/) | 上游已有悬浮入口和实验性副屏；设备兼容性待验证 |
| 定时流程、场景触发、提醒 | [Orbit Assistant](https://github.com/lpnovi/Orbit-Assistant) | 本次未新建后台调度服务；后台执行受安卓系统与厂商策略约束 |
| 社区插件搜索与安装 | [dshmarket](https://github.com/dsh-market/dsh-market) | 插件管理页新增安装入口，复用已有的受控导入；市场插件及其移动兼容性仍需验证 |

## 验证边界

任务草稿是帮助用户表达意图的界面，不等于新增设备工具或自动完成任务。插件市场入口安装第三方 `dshmarket`，实际市场页面由该插件提供，安装是否成功、插件是否可运行和是否适合手机需要单独验证。用户可使用现有插件管理的启停、更新、回滚等操作。

手机侧运行需要用户启用无障碍服务并选择授权应用；部分操作需要 Shizuku。页面数据、截图和模型输出都不应当作可信指令。支付、密码、验证码和锁屏等受系统或项目限制。当前修改没有放宽已有防护。

# Smart-AutoClicker · slobys 二次开发版

基于 [Nain57/Smart-AutoClicker（Klick'r）](https://github.com/Nain57/Smart-AutoClicker) 持续二次开发的 Android 自动化工具，由 [slobys](https://github.com/slobys) 维护。

本版本面向游戏中的重复操作与通用界面自动化，在上游基础上扩展了识别、流程控制、执行可靠性、悬浮操作和诊断能力。**这是独立维护的二次开发版本，不是上游官方发行版，也不是全部从零原创的项目。** 保留上游及其他贡献者的版权和许可证声明，继续遵循 GNU GPL v3（源码注明可选任意后续版本的，保留该许可）。

## 下载、文档与反馈

| 入口 | 本二次开发版 |
| --- | --- |
| 正式版下载 | [GitHub Releases · 最新正式版](https://github.com/slobys/Smart-AutoClicker/releases/latest) |
| 更新记录 | [全部发布记录](https://github.com/slobys/Smart-AutoClicker/releases) · [仓库内版本说明](documentation/releases) |
| 中文使用说明 | [稳定执行与诊断](documentation/reliability-and-diagnostics-zh.md) |
| 问题反馈与功能建议 | [本仓库 Issues](https://github.com/slobys/Smart-AutoClicker/issues) |
| 源码与修改历史 | [本仓库](https://github.com/slobys/Smart-AutoClicker) · [提交记录](https://github.com/slobys/Smart-AutoClicker/commits/master/) |

**请从上面的 Releases 下载本版 APK。** 上游的 Google Play、F-Droid 和其他发行渠道不代表本二次开发版。

### 安装与升级

- 支持 Android 7.0（API 24）及以上；具体可用性还取决于设备的无障碍、屏幕捕获和悬浮窗支持。
- 不确定设备架构时，选择 `Klickr-版本号-universal-release.apk`；了解架构的用户可选择 `arm64-v8a`、`armeabi-v7a`、`x86` 或 `x86_64` 包。
- 升级本仓库的旧正式版前，先导出场景备份，再覆盖安装。**不要通过卸载或清除数据来解决安装问题。**
- 上游版、应用商店版与本版可能签名不同，不能保证直接覆盖安装。请先备份，并核对系统提示。
- Debug 测试版使用独立的 `.debug` 包名，不自动共享正式版数据；需要通过场景备份导出、导入转移脚本。
- 发布页提供 `SHA256SUMS.txt` 校验安装包，并提供对应版本标签的源码入口。

## 能做什么

### 继承的基础能力

- 配置点击、长按、滑动、间隔与重复执行。
- 使用图片条件触发事件，组合条件与动作，按界面变化执行操作。
- 使用计数器、定时条件、事件控制、Android Intent 和广播等能力组织任务。
- 使用简单场景完成固定位置重复操作，或用智能场景处理需要识别判断的流程。

### 本分支的新增与重点优化

以下是本分支持续二次开发的主要方向；详细行为与版本差异以使用说明、发布记录及源码为准，并非逐文件版权归属清单。

| 方向 | 已加入或优化的能力 |
| --- | --- |
| 识别与判断 | 文字、数值、颜色条件及对应识别位置点击；小区域选择与动态背景识别优化 |
| 场景管理 | 分组、批量归组、收藏，以及更明确的场景操作入口 |
| 流程复用 | 调用目标事件的动作一次后返回主流程；循环调用检测与嵌套深度保护；共享计数器用于流程间传递数值 |
| 执行可靠性 | 智能等待、点击后确认、智能滑动查找、失败结果传递与连续失败保护 |
| 悬浮操作与调试 | 贴边隐藏、弧形快捷菜单、独立暂停入口、左右贴边调试布局、事件断点与事件级单步 |
| 运行历史 | 最近运行、动作结果、耗时、有限数量的失败截图，以及诊断 ZIP 导出 |
| 长时间运行 | 截图和原生检测资源清理、内存缓存限额、有界调试报告写入、周期内存采样与系统退出原因记录 |

例如：点击背包后等待背包界面真正出现；在任务列表中滑动查找目标并在找到后停止；把重复的一组操作放到子流程中复用；出现异常时从运行历史中定位失败动作。

这些功能不是“任意游戏开箱即用”的脚本库。需要自行配置条件、动作和等待时间，并在实际设备上验证。数值识别和点击结果会受画面、动画、分辨率、网络及系统权限影响。

## 稳定性与诊断

如果发生“应用退出、悬浮图标一起消失”，请重新打开应用，在 **场景首页 → 更多选项 → 运行历史** 中导出诊断 ZIP，并通过本仓库 Issues 提供版本、设备/模拟器类型、大概退出时间和复现步骤。

- 运行历史、失败截图和内存采样在本机保存，不自动上传；分享前请检查游戏角色、聊天等私人信息。
- 历史记录与报告有数量、内存和文件大小限制。磁盘过慢或报告超过限制时会停止本次报告记录，不把不完整报告冒充成功报告。
- 本版降低应用自身的内存压力，但不能阻止系统或云手机平台在资源不足时回收进程。自动化回归通过不代表真实游戏长期运行绝不会退出。

详见 [使用说明及验证边界](documentation/reliability-and-diagnostics-zh.md)。

## 开发与构建

这是 Kotlin / Android / C++ 多模块 Gradle 项目，不是 Node.js 项目。主要目录：

```text
smartautoclicker/   应用入口、服务、场景列表与运行历史
core/common/       截图、位图、无障碍、悬浮窗等公共能力
core/smart/        数据库、识别、流程执行与调试
feature/           场景编辑、备份及其他功能界面
build-logic/       Gradle 构建约定
documentation/     中文说明与版本记录
```

使用 JDK 21、Android SDK 37、NDK 28.2.13676358、CMake 3.22.1 和仓库自带的 Gradle Wrapper。依赖版本见 [版本目录](gradle/libs.versions.toml)，验证步骤见 [测试工作流](.github/workflows/execute-tests.yml)。首次构建需要联网下载依赖与原生库资源。

```shell
# Linux / macOS；Windows 将 ./gradlew 换成 .\gradlew.bat
./gradlew :smartautoclicker:assembleFDroidDebug
./gradlew testFDroidDebugUnitTest lintFDroidDebug

# 需要已连接的 Android 设备或模拟器
./gradlew :core:smart:detection:connectedFDroidDebugAndroidTest
```

正式版使用 `assembleFDroidRelease`，签名参数由私密配置注入，具体流程见 [发布工作流](.github/workflows/release.yml)。不要提交签名私钥、密码、个人诊断包或设备数据。`fDroid` 是构建变体名称，不表示本版已经在 F-Droid 商店上架。

## 版权、许可与二次开发声明

- 原项目由 **Nain57 / Kevin Buzeau** 及其他贡献者开发；继承代码的原有版权声明继续保留。
- 本仓库由 **slobys** 进行二次开发与维护。slobys 的原创新增和修改贡献保留相应版权；这不意味着上游代码或其他贡献者的版权转移给 slobys。
- 项目继续依照仓库中的 [GNU GPL v3 许可证](LICENSE) 分发。已有源码中“GPL 第 3 版或任意后续版本”的声明保持有效；第三方组件遵循各自许可证，不因本 README 改写而改变。
- 二次开发内容通过 Git 提交历史和 [版本说明](documentation/releases) 记录。本版 APK 的对应项目源码可在同名发布标签中获取，构建脚本与依赖版本一并保留，不以未修改的上游源码替代本版源码。
- 再分发或继续修改时，请保留适用的版权、许可证和修改声明，并遵守 GPL 的对应源码提供等要求。开源许可不是“无版权”声明。

## 原项目与致谢

感谢上游作者与所有开源贡献者提供基础。以下是**上游项目**的资源，可能与本版功能、界面和签名不同：

- [Nain57/Smart-AutoClicker 源码](https://github.com/Nain57/Smart-AutoClicker)
- [上游 Wiki](https://github.com/Nain57/Smart-AutoClicker/wiki)
- [上游 Google Play](https://play.google.com/store/apps/details?id=com.buzbuz.smartautoclicker)
- [上游 F-Droid](https://f-droid.org/packages/com.buzbuz.smartautoclicker/)

本二次开发版的问题请优先提交到 [slobys/Smart-AutoClicker Issues](https://github.com/slobys/Smart-AutoClicker/issues)，不要把本分支特有的问题直接当成上游故障。

请仅在获得授权的设备与应用中使用自动化功能，遵守目标应用、游戏和平台的规则。涉及购买、提交或不可撤销操作时，先在安全场景中测试；本项目不承诺规避检测、封禁或平台限制。

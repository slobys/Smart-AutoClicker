# VisionClick 品牌与安装说明

## 应用身份

| 项目 | 配置 |
| --- | --- |
| Gradle / Android Studio 项目名称 | VisionClick |
| 正式版显示名称 | VisionClick |
| 正式版包名 | `com.slobys.visionclick` |
| 测试版显示名称 | VisionClick 测试版 |
| 测试版包名 | `com.slobys.visionclick.debug` |
| 首个 VisionClick 正式版 | 4.0.5.28（保留功能版本序列） |
| GitHub 仓库 | [slobys/VisionClick](https://github.com/slobys/VisionClick) |

软件使用用户提供的手机触控图标，并按红圈要求通过内置图像编辑工具收紧外围背景，保留完整圆角边框与其中的手机、触控圆环和手指主体。当前图为 `smartautoclicker/src/main/res/drawable-nodpi/visionclick_artwork.png`，1254 × 1254，SHA-256 为 `d8c0cc24692d1f51693fe37a94d3ca0445b2a7cc2ce984329d263795b87d37bf`。原图单独保留在 `documentation/branding/visionclick-original.png`，原始 SHA-256 为 `e6f9e2470a367b5a18f8a515bc06e1715d8c2f5f838e041add75ee9c016ea326`，可回退，不覆盖旧测试包。

Android 7 使用 48、72、96、144、192 像素的多密度位图图标，Android 8 及以上使用补偿图案透明留白后的自适应图标，Android 13 主题图标继续使用简化的单色触控符号。不同桌面的圆形、圆角方形遮罩外观可能不同，装饰性外圈会随桌面遮罩自然裁剪。[编辑模式与完整提示词](branding/visionclick-icon-edit-prompt.md)单独记录。

显示名称覆盖权限引导、通知渠道、快捷设置及内置教程的已有翻译。内部 Kotlin 命名空间和模块名仍保留 `com.buzbuz.smartautoclicker` / `smartautoclicker`，与实际安装包名独立，避免无必要的源码重命名。构建插件支持源命名空间与安装 ID 分离；教程、通知设置和组件跳转使用当前安装身份。

发布工作流从 v4.0.5.28 起生成 `VisionClick-版本-架构-release.apk`，下载入口为 [Releases](https://github.com/slobys/VisionClick/releases/latest)。GitHub 仓库已由 `slobys/Smart-AutoClicker` 改名为 `slobys/VisionClick`，本地 origin 已同步；历史标签、提交和已发布的旧 APK 保留，不把旧包重命名为新软件。Play 商店计费、广告和 Firebase 配置仍属于上游集成，不代表已为新包名开通；当前交付和验证以无广告的 fDroid 构建变体为准。

## 从旧版迁移

VisionClick 与旧 Klick'r 是不同 Android 应用，可以并存安装，不覆盖旧版，也不自动共享私有数据。

1. 在旧版导出需要的场景备份，保留原应用与原数据。
2. 安装 VisionClick，重新开启其无障碍、悬浮窗等权限。
3. 在 VisionClick 导入场景备份，核对截图选区、目标坐标和运行结果。
4. 路线文件不在场景备份内，需要单独迁移；不应删除旧版以尝试强制迁移。

测试版与正式版也有独立数据空间。请先暂停旧版再运行新版，避免两个自动化应用同时操作屏幕。

## 验证

v4.0.5.28 发布准备：最终图标与新版本号下的全量 JVM 验证通过，共 148 个套件、1,409 项测试，0 失败、错误、跳过；30 份 Lint 报告为 0 错误、92 个既有警告，Debug 构建通过。正式签名包由发布标签的 CI 在 JVM、Lint 和 Android 图片／中文 OCR 测试通过后生成，见 [v4.0.5.28 发布说明](releases/v4.0.5.28.md)及 [Release 工作流](https://github.com/slobys/VisionClick/actions/workflows/release.yml)。下文保留改名和图标开发时的阶段记录，不表示新的正式包已经在真机验证。

Windows 使用 `scripts/build-windows.ps1` 和 JDK 21 构建。检查 Debug APK 的包名、名称、启动 Activity、无障碍服务、权限和桌面图标，并运行现有 JVM 单元测试和 Lint。品牌回归测试覆盖新安装 ID、默认及中文名称、旧版 Android / 自适应图标、教程入口、通知设置和通知按钮的应用隔离。

改名不改变图片、文字、数值、颜色识别以及动作执行逻辑。编译和自动测试不能代替真机上的桌面显示、授权、场景导入及游戏操作验证。

品牌开发阶段的本地验证（2026-10-13，正式发布前的历史记录）：

- 原项目 Debug 构建基线通过。
- 全量 JVM：148 个测试套件、1,404 项测试，失败、错误、跳过均为 0，其中品牌回归 11 项。
- 全量 Lint：30 份报告、0 错误，保留 92 个警告；应用模块 16 个警告。已移除不再使用的 18 个旧图标资源，均可从 Git 历史恢复。
- 清理旧图标后，应用模块单元测试、Debug APK 构建和 Lint 再次通过。
- 正式版 Manifest 任务通过，包名 `com.slobys.visionclick`，无 Debug 标记；未在本机生成正式签名 APK。
- 最终测试 APK 已核验包名、名称、启动入口和 Android Debug 签名，未安装到真机，未发布到 GitHub。

图标按红圈收紧后的增补验证（同日）：13 项图标与品牌回归测试全部通过，Debug 构建通过，应用模块 Lint 0 错误、16 个警告。本轮只改图标资产、旧版多密度引用、图标测试与文档，没有重跑全量业务测试；上面的 1,404 项是收紧前的完整基线。新 APK 单独保存，不覆盖此前的安装包，包名、版本、Debug 签名保持不变，可覆盖安装到已有 VisionClick 测试版。原图可从 `documentation/branding/visionclick-original.png` 恢复。

桌面额外黑边修正（同日）：旧配置的 16.67% 前景边距与 PNG 自带透明留白叠加，露出深色背景层。只将 `ic_visionclick_foreground.xml` 的边距调整为 10.5%，图案文件及其 SHA-256 不变。修复前原生 256 × 256 预览四条中线的额外暗带为 19 / 20 / 19 / 19 像素；修复后为 0 / 0 / 0 / 0。新增像素回归检查已在旧配置失败、在新配置通过，连同兼容性检查共 16 项全部通过；构建和应用 Lint 通过（0 错误、16 个警告）。提供圆角及圆形遮罩预览，未在用户真机重新安装验证，也未重新运行全部业务测试。新包单独保存为 `VisionClick-4.0.5.27-icon-fill-universal-debug.apk`，不覆盖旧附件。

主要改动文件与范围：

| 文件 / 范围 | 改动 |
| --- | --- |
| `settings.gradle.kts`、`.idea/.name` | 项目名称 |
| `smartautoclicker/build.gradle.kts`、`build-logic/obfuscation/` | 独立安装 ID 与源命名空间分离 |
| `smartautoclicker/src/main/AndroidManifest.xml`、`src/main/res/`、`src/debug/res/` | 桌面名称、原图、自适应和主题图标，测试版名称 |
| `SmartAutoClickerApplication.kt`、`AppComponentsManager.kt` | 当前安装包的服务、主界面和教程组件 |
| `SystemIntents.kt`、通知权限与设置入口 | 通知设置打开当前应用 |
| `feature/notifications/` | 通知操作限定当前应用，不向旧版广播 |
| 各模块的多语言字符串、`fastlane/metadata/`、`README.md` | 用户可见品牌与来源说明 |
| `.github/workflows/release.yml` | VisionClick APK 附件名与发布标题 |
| `VisionClickBrandingTests.kt` | 品牌、图标兼容性和组件隔离回归测试 |

## 来源和许可证

本项目基于 Kevin Buzeau（Nain57）及贡献者的 Smart AutoClicker / Klick'r，由 slobys 维护。原作者、贡献者、GNU GPL 许可证和源码声明保留；品牌更换不改变项目的开源许可义务。

# 移动与动态背景下的识别

这轮改进针对游戏画面移动、背景颜色变化、轻微拖影和半透明文字。没有降低用户设定的相似度，也没有新增识别模型或第三方依赖。

## 四种条件的处理

| 类型 | 处理方式 | 设置建议 |
| --- | --- | --- |
| 颜色 | 对区域内真实像素计算色差，至少 75% 落在容差内才通过，容忍少量移动杂色；不再把红蓝像素的平均值误当成紫色 | 尽量框住目标色块；不是区域内任意一点同色就算成功 |
| 图片 | 原始匹配失败后，对小模板增加局部背景抑制，检查前景结构和对应颜色；保留已有的水平、垂直轻微拖影备用匹配 | 截取有辨识度的目标，少带无关背景；限定搜索区域 |
| 文字 | 保留原图和对比度增强，失败后对较小选区增加亮字、暗字的 RGB 背景抑制；仍检查文字相似度和备用结果的模型置信度 | 框住完整文字并留少量边距；中文目标必须选择中文模型 |
| 数值 | 在原图、增强图等读数不一致时补充背景抑制；不让低置信度的角落片段挤掉达到阈值的完整数字；低置信度结果不参与一致性加分 | 框完整数字及小数点，不要同时框入其他计数器；明确小数格式 |

单像素颜色条件保持原有色差含义。旧版的**多像素颜色区域**原来比较平均颜色，升级后需重新测试；如果框入了大面积不同颜色的背景，应缩小到目标色块。选区、脚本和数据库结构不变。

## 防止误触发和额外开销

- 每次只使用当前截图，不把前一帧的文字、数字或坐标当成本帧结果；目标消失后不能依靠旧识别结果继续点击。
- 原始识别命中时尽早返回。OCR 新备用处理限制在最多 512 × 512 像素总面积的选区；图像新备用处理限制模板面积不超过 256 × 256、搜索区面积不超过 1024 × 1024。
- 图片备用处理最多检查 5 个位置，前景结构相关度还必须达到至少 90%；纯色、信息不足的模板不适用。
- 不累积截图历史，不增加无上限的图像缓存。困难或缺失目标会触发更多处理，低配置真机上仍建议缩小检测区域。

这些方法不能恢复被完全遮挡的字、严重运动模糊或不足几个像素的字符，也不保证每帧都识别成功。涉及购买等重要动作，建议结合“点击后确认”或连续帧智能等待；不要只靠放宽阈值。

## 手机上的“应用范围”

它是 **Android 屏幕捕获授权范围**，不是脚本适用范围：

- **共享一个应用**：只能看该应用的内容，切到其他应用可能没有可识别画面。
- **整个屏幕**：能识别当前屏幕及切换后的应用，但也可能捕获可见的消息、密码等敏感内容。

现在未保存过该偏好的用户默认请求整个屏幕，减少选择应用的步骤；明确保存过的选择不被覆盖。可在“设置 → 默认捕获整个屏幕”关闭，恢复系统的单应用选择。修复了 Android 14 未启用此选项的问题。

每次开始新的捕获会话仍须系统授权，不能静默绕过；部分厂商仍可能显示范围选择。参见 [Android 官方 MediaProjection 文档](https://developer.android.com/media/grow/media-projection)。

## 验证范围

自动化测试直接在 Google 官方 Android 模拟器运行原生识别库，使用连续生成的背景变化、目标位移、亮度变化、淡色字、数值变化及目标消失画面；同时回归已有文字、数字和图像测试。系统授权分支使用 Android 13 / 14 / 15 的本地框架测试。

合成画面通过不等于已验证所有游戏、中文字体或真机性能。真实游戏录屏仍需要单独回归，尤其是复杂特效、遮挡、缩放和厂商系统。

### 2026-09-30 验证记录

- 修改前原有 31 项文字、数值、模板测试通过；新增动态用例复现了颜色漏检、混色误检和图片背景变化后的漏检。
- 修改后 Google Small_Tablet（Android 12，x86_64）原生测试 57 项通过；1 个原有素材生成工具测试保持跳过。包含新增的 9 组动态/负例测试，以及 1000 帧采集检测释放的生命周期回归。
- 本地测试 741 项通过：设置 1、屏幕捕获 44、执行引擎 300、脚本配置 327、应用 69。其中覆盖 Android 13 / 14 / 15 授权分支、默认全屏及用户主动关闭后的读取。
- 四种 ABI 的 Debug 原生库和通用 APK 构建成功；应用 Lint 为 0 错误、16 个既有警告。

主要修改文件：`color_matcher.cpp`、`template_matcher.cpp/.hpp`、`text_matcher.cpp/.hpp`、`ImageDetector.kt`；授权相关的 `MediaProjectionRequest.kt`、`SettingsDataSource.kt`、`SettingsRepository.kt/Impl.kt`、三个启动入口及 ViewModel、`SettingsViewModel.kt`；中英文提示与本文。新增 `DynamicBackgroundTests.kt`、`MediaProjectionRequestTests.kt`、`CaptureScopePreferencesTests.kt`。设置模块使用项目已有的单元测试约定插件，没有增加运行时依赖。

复现命令：

```text
gradlew.bat :core:smart:detection:connectedFDroidDebugAndroidTest
gradlew.bat :core:common:settings:testFDroidDebugUnitTest :core:common:display:testFDroidDebugUnitTest :core:smart:processing:testFDroidDebugUnitTest :feature:smart-config:testFDroidDebugUnitTest :smartautoclicker:testFDroidDebugUnitTest :smartautoclicker:assembleFDroidDebug :smartautoclicker:lintFDroidDebug
```

OCR 合成用例使用仓库已有的 Latin 测试模型；中文游戏文字的实际收益尚未以游戏录屏验证。

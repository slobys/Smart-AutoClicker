# 测试结果面板贴边显示

## 使用效果

单个条件测试（图像、颜色、数值、文字）与屏幕事件测试共用自动方向布局：

- 工具栏位于屏幕右半边时，结果面板放在工具栏左侧。
- 工具栏位于屏幕左半边时，结果面板放在工具栏右侧。
- 拖动结束、重新打开测试、横竖屏切换时重新计算方向和窗口边界。
- 只交换工具栏和结果面板的位置，不翻转文字、图标或阈值滑块。

旧布局将结果固定在工具栏右侧；继承右侧工具栏位置后，新增的结果区域可能伸出屏幕。现在按照完整面板的尺寸重新限制窗口位置，保持已贴住的右边界。保存位置为 `x=0` 或 `y=0` 时也正常恢复，不再误判为没有保存位置。

不改变识别算法、运行调试窗口、菜单自动收起时间或贴边入口宽度。没有增加依赖或数据库迁移。

## 单层放大结果框（2026-10-13，当前样式）

图片、文字、数值测试只显示外层放大的红绿结果框，移除内部白色细线、黑色对比描边及四角标记。指定区域、原位置和全屏搜索都不再额外画搜索范围框；等待结果、空结果或清空时不残留内框。单个条件和屏幕事件测试共用此绘制逻辑。

命中仍为绿色、未命中为红色，保留 10 个物理像素线宽和每边向外 20 个物理像素的线条中心留白。只隐藏悬浮结果中的内层范围线，不更改保存的选区、模板、阈值、识别算法，也不改变设置页面的框选边线和调整手柄。颜色的白色范围、空心取样标记、像素放大预览及区域内部保护保持不变。

本轮修改 `feature/smart-debugging/.../ui/view/DebugOverlayView.kt`、对应 `DebugOverlayViewTests.kt` 及本文。显示元数据单独控制是否画范围线，只有颜色开启；图片、文字、数值仍保留原始范围供悬浮标记裁切使用。复用已有 Android 原生像素测试，未新增依赖。

验证命令：

```powershell
.\scripts\build-windows.ps1 -Tasks 'testFDroidDebugUnitTest',':smartautoclicker:assembleFDroidDebug',':feature:smart-debugging:lintFDroidDebug',':smartautoclicker:lintFDroidDebug'
```

本轮原生像素回归共 27 项，先在修改前的绘制实现上出现 8 项失败，关闭非颜色范围描边后全部通过。覆盖三类条件的红绿单层结果、等待／空结果／清空、三种图片搜索模式、移动命中、横竖屏、高密度屏幕，以及混合事件中保留颜色标记和内部保护。完整构建通过，147 个 JVM 测试套件、1,393 项测试，0 失败、错误或跳过；模块与应用 Lint 均为 0 错误，分别保留 6、16 个既有警告。

本轮独立蓝色 Debug 包为 `Klickr-single-result-border-20261013-universal-debug.apk`，不要与下节的第一阶段旧包混淆。包名 `com.buzbuz.smartautoclicker.debug`，版本 `4.0.5.26`（1260000），大小 74,771,272 字节，包含 arm64-v8a、armeabi-v7a、x86、x86_64；APK v2 签名校验通过。SHA-256：

```text
C946BA0181964D0C45288EB52E56934CA12722C4F93A40C22717E3FFC932FF9A
```

像素渲染测试不等于手机或游戏实测。上述独立测试阶段未自动安装、未发布正式版，未修改设备中的脚本数据；用户确认后，该单层样式纳入 v4.0.5.27 正式版发布，验证与升级说明见 `documentation/releases/v4.0.5.27.md`。下节为第一阶段历史记录。

## 恢复放大结果框（2026-10-13，第一阶段）

按用户反馈，图片、文字、数值的测试结果恢复旧版更醒目的显示：命中为绿色，未命中为红色；线宽为 10 个物理像素，线条中心在真实结果矩形每边向外 20 个物理像素处。线宽的一半也在框外，因此可见外沿最多比真实结果每边大 25 像素。下文的 2 像素细结果框记录仅描述历史版本。

这只是悬浮标记的显示留白，不扩大保存的检测区域，不缩放模板，不修改阈值、OCR 或识别算法。该阶段仍保留白色固定搜索范围，后续按用户反馈在图片、文字、数值测试中移除；设置页面的细框与调整手柄、颜色空心取样标记和像素放大预览保持不变。

绘制时仍保护真实结果内部、其他重叠或相邻目标，以及配置的颜色检测区域；空或无效结果不画框，清空或替换结果不残留旧标记。屏幕边缘自然裁切，不移动识别坐标。

修改文件：`feature/smart-debugging/.../ui/view/DebugOverlayView.kt`、对应 `DebugOverlayViewTests.kt`、该模块 `build.gradle.kts` 及本文。复用已有原生像素测试，增加图片／文字／数值的共用样式、固定选区不变、相邻结果及颜色区域保护回归检查。测试编译显式引用项目已有的语言模型模块，没有新增第三方库或应用运行依赖。

悬浮标记仍可能进入系统录屏。放大的粗框更明显，也可能覆盖附近其他内容；请把工具栏移到目标之外，必要时用眼睛按钮隐藏标记。本改动不代表提高了识别准确率，也不代表完成了用户手机或游戏实测。

验证命令：

```powershell
.\scripts\build-windows.ps1 -Tasks 'testFDroidDebugUnitTest',':smartautoclicker:assembleFDroidDebug',':feature:smart-debugging:lintFDroidDebug',':smartautoclicker:lintFDroidDebug'
```

构建通过；147 个 JVM 测试套件、1,390 项测试通过，0 失败、错误或跳过，其中结果框原生像素绘制测试 24 项（本轮新增 4 项）。smart-debugging 与应用 Lint 都为 0 错误，分别保留 6、16 个既有警告。未安装到手机或模拟器，未发布新的正式版。

本轮独立蓝色 Debug 包为 `Klickr-expanded-green-border-20261013-universal-debug.apk`，包名 `com.buzbuz.smartautoclicker.debug`，版本 `4.0.5.26`（1260000），大小 74,439,809 字节。包含 arm64-v8a、armeabi-v7a、x86、x86_64，APK v2 签名校验通过。请按完整文件名辨认本轮包，避免安装之前同版本的旧包。SHA-256：

```text
68FFF82D9591D1A13113032551D99B82392BCF454E46F4BCDBA0C0C13D1844FC
```

## 测试结果细框（2026-10-12）

图片、文字、数值、颜色的单条件测试与屏幕事件测试共用细框绘制：

- 取消旧版每边额外 20 个物理像素的显示留白。原来的粗框看起来扩大，不等于识别选区被扩大。
- 红、绿结果框由 10 像素改为 2 个物理像素宽，不随屏幕密度变粗。
- 线条贴着实际结果矩形的外沿绘制，只占框外 2 像素；框内保持透明，不压住小字或图标。多个结果重叠时，也不向其他结果的内部画线。
- 贴屏幕边缘的线条自然裁切，不向内挪动目标范围；空或无效结果不画框，清空结果后移除旧框。

保存的框选范围、模板、阈值和识别算法不变。结果框使用引擎返回的位置和尺寸；文字的实际文字框、图片在搜索区内找到的位置，不一定等于整个搜索选区。

这次只改变显示绘制，**没有裁剪、涂抹或屏蔽识别截图，也没有锁住历史高分**。框外线条仍可能进入系统截图，不能据此宣称完全排除了悬浮窗影响。测试结果面板和工具栏仍应移到目标之外；若目标被面板遮住，可以拖动工具栏，或用眼睛按钮隐藏结果框。细框本身是否影响某款手机的连续识别，需要在该设备上继续验证。

复用项目现有的 Android 单元测试约定与 Robolectric 原生像素渲染，没有增加应用运行依赖。新增 `DebugOverlayViewTests` 覆盖红绿细框、微小及奇数尺寸目标、输入坐标不变、无效结果、替换与清空、横竖屏、贴边、高密度屏幕、重叠目标以及画布裁切状态恢复。

本轮修改文件：`feature/smart-debugging/.../ui/view/DebugOverlayView.kt`（共用绘制）、同模块 `build.gradle.kts`（接入已有单元测试约定）、`DebugOverlayViewTests.kt`（11 项新增测试）及本文。没有修改数据模型、数据库、录制路线或识别引擎。

验证命令：

```powershell
.\scripts\build-windows.ps1 -Tasks 'testFDroidDebugUnitTest',':smartautoclicker:assembleFDroidDebug',':smartautoclicker:lintFDroidDebug',':feature:smart-debugging:lintFDroidDebug'
```

修改前的绘制代码在首批 10 项新增测试中失败 9 项；修复后 11 项像素测试全部通过。最终完整验证为 135 个 JVM 测试套件、1,318 项测试，失败、错误及跳过均为 0；四种架构及通用 Debug APK 构建通过。应用 Lint 为 0 错误、16 个原有警告；smart-debugging 为 0 错误、6 个原有警告，没有新增 Lint 警告。像素测试使用 Robolectric 原生绘制，不等于已经完成手机或游戏实测。

本次独立测试包为 `Klickr-thin-result-border-20261012-universal-debug.apk`，蓝色图标、包名 `com.buzbuz.smartautoclicker.debug`，版本仍为 `4.0.5.25`，请按文件名和校验值区分旧包。APK v2 签名验证通过，SHA-256：

```text
A5C7E766A038A3C0187F8334D9B9B043081702090B267154123C27DDE3587842
```

本轮没有自动覆盖安装当前模拟器上的应用，没有修改手机或模拟器中的脚本数据，也没有发布新的正式版。

## 固定范围与颜色放大预览（2026-10-12，后续优化）

针对细结果框不易看清的问题，单条件测试和屏幕事件测试增加独立的范围层：

- 白色细线、黑色对比描边、加重的四角用于显示**原来配置的选区**，内部不填充。未命中、等待第一帧或结果超时清空时，选区仍然显示。
- 图片按原位置、指定区域或全屏三种搜索设置显示对应范围，不把模板原位置误当作全屏搜索的范围。全屏边界在屏幕最外侧几个像素内显示，避免完全落在屏幕外；数据中的区域不变。
- 命中位置与固定范围分开。实际结果矩形仍是外侧 2 物理像素细框，无额外 20 像素留白；红绿表达找到目标与否，面板另用文字显示“已命中／未命中”和“条件满足／条件未满足”。数字读取成功但比较不成立、目标消失条件，不再混为同一种状态。
- 单像素颜色用中心透明的高对比圆环。区域颜色检测始终保留白色范围框，区域内不绘制取样圆环或其他结果边框，以免标记颜色进入下一帧区域识别；具体采样像素在工具栏放大窗标出。
- 颜色单条件测试增加最近邻像素放大窗、目标／采样色块、RGB 十六进制值和采样坐标。区域找色成功时显示实际命中像素；未命中及覆盖率模式显示范围中心，明确标注“范围中心”，不伪造命中点。中心像素仅供查看，区域覆盖率判断仍沿用原算法。
- 预览只在单个颜色条件的测试会话启用，从处理器正在识别的同一帧同步复制最多 25×25 像素。没有额外截图循环，不持有、回收或篡改录屏器的借用 Bitmap。普通脚本、事件测试、图片、文字、数字不生成这份预览。
- 退出测试清除预览；在 500 毫秒启动等待期间退出，也会取消尚未启动的测试。结果面板仍按工具栏位置向屏幕内展开。

本轮没有新增运行依赖、数据库迁移，没有改保存的条件选区、模板、阈值、路线或四种识别算法。改动包括：

- `feature/smart-debugging/.../ui/view/DebugOverlayView.kt`：固定范围、结果层、颜色空心标记与区域内像素保护。
- `feature/smart-debugging/.../ui/view/ColorSamplePreviewView.kt`：独立像素放大窗，中心标记不盖采样像素。
- `feature/smart-debugging/.../conditiontry/TryImageConditionOverlayMenu.kt`、`TryImageConditionViewModel.kt`：状态文案、预览绑定与启动／停止生命周期。
- `feature/smart-debugging/.../eventtry/TryEventOverlayMenu.kt`：事件测试的固定范围初始化及清理。
- `feature/smart-debugging/.../uistate/ScreenConditionResultUiState.kt`、`mapping/ImageConditionResultUiStateMapping.kt`：把检测状态、条件状态、采样点和预览分开传递。
- `feature/smart-debugging/src/main/res/layout/overlay_try_image_condition_menu.xml`、`values/strings.xml`、`values-zh-rCN/strings.xml`：紧凑结果布局及中英文状态。
- `core/smart/processing/.../domain/model/ColorSamplePreview.kt`、`ProcessedConditionResult.kt`：小块像素副本及可选预览字段。
- `core/smart/processing/.../data/processor/ConditionsVerifier.kt`、`ScenarioProcessor.kt`、`data/DetectorEngine.kt`、`domain/SmartProcessingRepositoryImpl.kt`：仅在颜色条件测试启用同帧预览。
- `core/smart/debugging/.../domain/model/live/DebugLiveImageConditionResult.kt`、`engine/DebugEngine.kt`：传递采样点／预览／无效状态，不再把颜色命中点当成整块选区重新居中；修正奇数尺寸结果少一像素的问题。
- 对应的范围绘制、像素预览、预览链路、结果几何、阈值语义、测试生命周期和面板布局回归测试，以及本文。

悬浮窗仍可能出现在系统录屏里，不能宣称完全排除了悬浮窗影响。工具栏和放大窗请放在检测目标之外；眼睛按钮可隐藏屏幕标记。自动化验证不等于手机或游戏动态场景实测。

本轮新增 32 项回归测试，完整结果为 141 个 JVM 测试套件、1,350 项测试，失败、错误和跳过均为 0。覆盖固定范围在等待／未命中时保留、区域与命中位置分离、单像素圆环中心透明、重叠区域像素保护、三种图片搜索模式、奇数尺寸及原点结果、预览边缘裁切／像素所有权／同帧传递、停止与替换待启动测试，以及带颜色预览的贴边与旋转布局。

最终完整构建通过。应用、smart-debugging、processing、debugging 四个模块 Lint 均为 0 错误，警告数分别为 16、6、1、0；应用及 smart-debugging 没有新增警告。四种架构及通用 Debug APK 构建通过，通用包 v2 签名校验通过。

请使用蓝色图标独立测试包 `Klickr-fixed-range-color-preview-20261012-universal-debug.apk`，包名 `com.buzbuz.smartautoclicker.debug`，版本仍为 `4.0.5.25`（1250000）。不要与同一天的 `thin-result-border` 包混淆。SHA-256：

```text
E3F335AE163E67DD969D3BD17E30E40D815001144D6C8AAF2B2EF3EA92B0FA38
```

另外使用 Robolectric 原生绘制加载真实生产布局，填入 `98.01 %`／`80.00 %`、命中状态和 RGB 值，检查文字不被裁切，并目检导出的颜色与非颜色面板。该检查不是手机截图或游戏实测。本轮未自动安装覆盖模拟器应用，也未发布新的正式版。

完整构建与静态检查使用：

```powershell
.\scripts\build-windows.ps1 -Tasks 'testFDroidDebugUnitTest',':smartautoclicker:assembleFDroidDebug',':smartautoclicker:lintFDroidDebug',':feature:smart-debugging:lintFDroidDebug',':core:smart:processing:lintFDroidDebug',':core:smart:debugging:lintFDroidDebug'
```

## 早期面板修改文件

- `core/common/overlays/.../menu/OverlayMenu.kt`：窗口尺寸变化后的边界校正、右侧贴边保持、零坐标恢复，以及供子类重测尺寸的入口。
- `feature/smart-debugging/.../live/SidePanelOverlayMenu.kt`：条件和事件测试共用的左右方向布局。
- `feature/smart-debugging/.../conditiontry/TryImageConditionOverlayMenu.kt`、`eventtry/TryEventOverlayMenu.kt`：接入方向布局，移除多余窗口宽度。
- `feature/smart-config/.../mainmenu/TestResultPanelDockTests.kt`：加载真实布局的 9 项回归测试。

## 验证记录（2026-09-29）

```text
gradlew.bat :feature:smart-config:testFDroidDebugUnitTest :core:common:overlays:testFDroidDebugUnitTest :smartautoclicker:assembleFDroidDebug :core:common:overlays:lintFDroidDebug :feature:smart-debugging:lintFDroidDebug
```

396 项单元测试通过（smart-config 327 项、overlays 69 项），包括左右布局、拖动切换、上下边界、零坐标恢复、重新显示、阈值/结果保留与横竖屏切换。APK 构建通过；上述 Lint 任务通过，0 个错误，smart-debugging 仍有 6 个原有资源警告。

Google 官方 Small_Tablet 模拟器（Android 12、1920 × 1200）上检查了：

- 条件测试右侧向左展开、拖到左侧后向右展开、再拖回右侧。
- 阈值滑块可操作，返回再进入后保留阈值且面板仍完整可见。
- 事件测试面板在左右两侧均向屏幕内侧显示。

模拟器检查验证布局与交互，不代表已验证实际游戏内的识别准确率；横竖屏切换由回归测试覆盖。

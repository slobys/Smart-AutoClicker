# 测试结果面板贴边显示

## 使用效果

单个条件测试（图像、颜色、数值、文字）与屏幕事件测试共用自动方向布局：

- 工具栏位于屏幕右半边时，结果面板放在工具栏左侧。
- 工具栏位于屏幕左半边时，结果面板放在工具栏右侧。
- 拖动结束、重新打开测试、横竖屏切换时重新计算方向和窗口边界。
- 只交换工具栏和结果面板的位置，不翻转文字、图标或阈值滑块。

旧布局将结果固定在工具栏右侧；继承右侧工具栏位置后，新增的结果区域可能伸出屏幕。现在按照完整面板的尺寸重新限制窗口位置，保持已贴住的右边界。保存位置为 `x=0` 或 `y=0` 时也正常恢复，不再误判为没有保存位置。

不改变识别算法、运行调试窗口、菜单自动收起时间或贴边入口宽度。没有增加依赖或数据库迁移。

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

## 修改文件

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

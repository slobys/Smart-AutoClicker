# 测试结果面板贴边显示

## 使用效果

单个条件测试（图像、颜色、数值、文字）与屏幕事件测试共用自动方向布局：

- 工具栏位于屏幕右半边时，结果面板放在工具栏左侧。
- 工具栏位于屏幕左半边时，结果面板放在工具栏右侧。
- 拖动结束、重新打开测试、横竖屏切换时重新计算方向和窗口边界。
- 只交换工具栏和结果面板的位置，不翻转文字、图标或阈值滑块。

旧布局将结果固定在工具栏右侧；继承右侧工具栏位置后，新增的结果区域可能伸出屏幕。现在按照完整面板的尺寸重新限制窗口位置，保持已贴住的右边界。保存位置为 `x=0` 或 `y=0` 时也正常恢复，不再误判为没有保存位置。

不改变识别算法、运行调试窗口、菜单自动收起时间或贴边入口宽度。没有增加依赖或数据库迁移。

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

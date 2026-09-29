# 条件框选

本说明对应开发分支的细框选区改进，旧 APK 不会自动获得这些变化。

## 调整范围

颜色、数值、文字条件的“检测区域”以及图像截取使用细边框和四个框外圆点。拖动框内移动位置，拖动圆点调整对应边；点击勾号保存，叉号取消，眼睛按钮临时隐藏选框以查看目标。小选区不显示内部操作提示图标，边框和圆点不覆盖选区内的文字。

数值、文字选区可缩至 8 × 8 屏幕像素。这是操作下限，不代表这么小的内容一定能被 OCR 识别；实际应框住完整数字或文字，并留少量背景。

颜色条件保留精确的单像素取色：选好颜色后，进入“检测区域”可用细框扩大范围。扩大后沿用现有识别规则，比较**整个区域的平均颜色**，不是查找区域内任意同色点。点击色块重新取色会把范围恢复为单像素。已有颜色条件不会自动扩大。

## 为什么旧版框选会缩小画面

旧版图像截取打开后，会把截图预览从 100% 动画缩放到 80%，让预览周围留出空白。改变的是编辑预览，不是游戏分辨率，也不是识别准确率所必需的步骤。

现在取消这段自动缩小动画，默认按原始大小显示截图。图像截取仍支持手动双指缩放和框外拖动截图；保存时换算回原图坐标。颜色、数值、文字的检测区域则直接在屏幕上选取。

## 修改与验证记录（2026-09-29）

- `core/common/ui/.../imageselector/ImageSelectorAnimations.kt`、`ImageSelectorStyle.kt`、`ImageSelectorView.kt`：取消自动缩小，图像截取采用细框、外置手柄，小选区隐藏内部提示。
- `core/common/ui/.../viewcomponents/SelectorComponent.kt`：小选区的外置手柄可在圆点附近命中；保留屏幕边缘像素，绘制尺寸与触摸范围分开。
- `feature/smart-config/.../areaselector/ConditionAreaSelectorViewModel.kt`：数值、文字的小选区下限；复制选区，避免操作时修改未保存的原对象。
- `feature/smart-config/.../color/ColorConditionDialog.kt`、`ColorConditionViewModel.kt`、`ColorConditionUiState.kt` 和颜色布局、中英文文案：增加检测区域入口，说明平均颜色与单点取色的区别。
- `ConditionAreaSelectorMappingTests.kt`、`UnifiedSelectorTests.kt`、`ColorConditionAreaTests.kt`：覆盖选区映射、横竖屏与密度、手动缩放和平移、边缘像素、极小选区拖动、裁剪内容无遮挡、颜色兼容行为。

验证命令：

```text
gradlew.bat :feature:smart-config:testFDroidDebugUnitTest :smartautoclicker:assembleFDroidDebug :core:common:ui:lintFDroidDebug :feature:smart-config:lintFDroidDebug
```

318 项单元测试通过；APK 构建及上述 Lint 任务通过（0 个错误，报告仍有 29 个警告）。Google 官方 Small_Tablet 模拟器中检查了截图初始比例、移动和调整选框大小、保存裁剪内容，以及颜色、数值、文字区域的调整与坐标回填。模拟器交互检查不是实际游戏里的识别准确率验证。

未新增依赖或数据库迁移，未修改悬浮菜单贴边入口的点击宽度。

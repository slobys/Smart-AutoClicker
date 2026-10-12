# 条件框选

本说明对应 v4.0.5.25 起提供的细框选区改进，旧 APK 需要升级才能使用。

## 调整范围

颜色、数值、文字条件的“检测区域”以及图像截取使用细边框和四个框外圆点。拖动框内移动位置，拖动圆点调整对应边；点击勾号保存，叉号取消，眼睛按钮临时隐藏选框以查看目标。小选区不显示内部操作提示图标，边框和圆点不覆盖选区内的文字。

数值、文字选区可缩至 8 × 8 屏幕像素。这是操作下限，不代表这么小的内容一定能被 OCR 识别；实际应框住完整数字或文字，并留少量背景。

图像截取的最小尺寸与数值、文字不同，普通屏幕通常为 50 × 50 屏幕像素，较大屏幕会自适应增加。2026-10-12 的修复统一了最小尺寸判断和裁剪的像素取整：同一个最小选框移动后，不再因为小数误差变灰，也不再在 49～51 像素之间漂移。未取得截图、选框移出截图或有效交集太小时仍不能确认。返回重拍时，相机按钮不受上一次选框是否有效的影响。

## 文字识别的默认语言与确认

2026-10-12 起，新建文字条件默认使用**简体中文**。已有条件、导入条件和复制条件保留原来保存的语言，不会批量替换；数值识别使用的数字模型不变。

进入文字条件的“字母表”后：

1. 在“识别语言”页选择语言；尚未安装的模型先点击下载图标，下载完成后选中该语言。
2. 查看底部“已选择”提示，点击固定在底部的**确定**，应用选择并返回条件设置。
3. 点击叉号或系统返回不保存这次语言选择。下载完成的模型仍保留在本机。

选中的模型未安装或正在下载时，“确定”保持禁用。简体中文默认值不会绕过模型下载要求，也不会静默切回拉丁字母。语言选择只更新当前条件；仍需在条件页保存整个条件。

颜色条件保留精确的单像素取色：选好颜色后，进入“检测区域”可用细框扩大范围。2026-09-30 的动态识别改进将区域平均颜色改为**至少 75% 像素落在颜色容差内**，避免红蓝背景平均成紫色，也容忍少量移动杂色；不是查找区域内任意同色点。旧版区域条件升级后建议重新测试，尽量框住目标色块。点击色块重新取色会把范围恢复为单像素。已有颜色条件不会自动扩大。

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

## 修改与验证记录（2026-10-12）

- `core/common/ui/.../imageselector/ImageSelectorView.kt`、`viewcomponents/SelectorComponent.kt`：按像素统一尺寸校验与裁剪取整；截图未加载、有效交集不足或不存在时保持无效。
- `feature/smart-config/.../screen/image/CaptureMenu.kt`：调整阶段按真实选区状态启用勾号；返回重拍恢复相机按钮；保存期间不接受选区回调重新启用确认。
- `feature/smart-config/.../domain/EditedItemsBuilder.kt`：仅新建文字条件默认简体中文，不修改数据库读取或复制条件的语言。
- `AlphabetSelectionViewModel.kt`、`AlphabetSelectionFragment.kt`、`dialog_alphabet_selection.xml` 与中英文文案：语言暂选、确认后应用、关闭不应用、模型就绪检查；固定底部确定按钮和当前选择说明。
- 6 个新增测试类共 26 项，覆盖最小选区移动的浮点边界、横竖屏/密度、交集不足、重拍/保存状态、默认与复制语言、确认/取消、下载就绪、重复确认、编辑对象变化以及窄屏/横屏实际 XML 布局。
- 本轮 Lint 另外发现 4 条既有区域找色文案缺少其他语言翻译。参照项目已有策略，仅对这 4 条标明英文回退；保留中文，不禁用全局翻译检查。

验证命令：

```powershell
.\scripts\build-windows.ps1 -Tasks 'testFDroidDebugUnitTest',':smartautoclicker:assembleFDroidDebug',':core:common:ui:lintFDroidDebug',':feature:smart-config:lintFDroidDebug',':smartautoclicker:lintFDroidDebug'
```

最终构建成功；147 个测试套件、1,376 项测试全部通过，0 失败/错误/跳过。上述 3 个 Lint 模块均为 0 错误，分别保留 2、29、16 个警告。使用 Robolectric 原生渲染检查了语言页竖屏、窄屏、横屏布局；未在用户手机或真实游戏中复测。

本地独立 Debug 包（不自动安装、不发布正式版）：

```text
Klickr-selector-zh-confirm-20261012-110305-universal-debug.apk
package: com.buzbuz.smartautoclicker.debug
version: 4.0.5.25 (1250000)
SHA256: F645FE9AF143566AF5EBBA49BC4DA9B452AD7935A7994C4A36DD06B47941C35A
```

APK v2 签名验证通过，包含 arm64-v8a、armeabi-v7a、x86、x86_64。请按完整文件名辨认本轮测试包，不要误装较早的同版本 APK。

# 横竖屏截图与图片识别修复

日期：2026-10-11。此轮修改公共截图和图片匹配链路，不针对某个游戏、图标或脚本新增特判，不新增依赖、模型或数据库迁移。

## 已确认的问题

### 带填充截图的像素读取

`ImageReaderProxy` 原来把缓冲区直接转换为 `IntBuffer`，再按 ABGR 整数交换红蓝通道，但没有明确字节顺序。对于 BIG_ENDIAN 的 RGBA 缓冲区，读出的 RGB 和 alpha 都会错位。此外，手动复制假定每像素恰好 4 字节，没有处理额外的像素填充。

这不是仅影响图片匹配：颜色、文字、数值都读取同一张截图。部分宽度的横屏画面无需行填充，直接复制能正常工作；换到另一个分辨率或竖屏后可能进入有问题的手动路径。是否触发取决于设备的 rowStride、pixelStride 和 buffer order，**不能凭一张截图断定用户设备一定触发了这个原因**。

修复后，4 字节像素显式按 LITTLE_ENDIAN 读取 RGBA；其他像素步长或未对齐行按字节偏移复制，忽略填充。只操作缓冲区的只读视图，不消费原缓冲区，不缓存跨旋转的旧帧。

### 图片匹配

- 原来只检查灰度信息量，等亮度但不同颜色的图案会被当作纯色直接拒绝；低对比度但有明确结构的模板也可能被过早拒绝。现在检查实际颜色通道的信息量；真正的纯色模板仍拒绝，建议使用颜色条件。
- 灰度相同的错色图标可能耗尽 5 个候选名额，使后面的真正目标漏检。保留快速灰度匹配，失败后增加颜色通道结构匹配，仍验证空间颜色差异和用户设置的相似度。颜色模板匹配使用已有 OpenCV 能力，参见 [官方文档](https://docs.opencv.org/4.x/df/dfb/group__imgproc__object.html)。
- 原候选排除矩形只向右下延伸，会影响相邻候选；现在在峰值周围对称排除重复位置。每一轮拥有独立候选状态，失败时保留该轮最强证据，避免显示最后一个弱候选或错误跳过拖影检查。
- 前景和有限缩放备用匹配原来会在大搜索区直接跳过，尤其影响保留原像素的长屏和混合条件脚本。现在分块搜索，保留重叠区域和全局坐标。颜色/前景备用单块不超过 1024×1024，最多 64 块；4 档缩放最多 12 块，跨块重叠考虑最大缩放尺寸。命中后停止继续备用搜索，不累积截图历史。

用户的允许差异没有降低。改变的是模板信息量判断及备用处理适用范围，不是绕过相似度、色差或防重复点击保护。扩大搜索范围可能增加失败帧的耗时；仍推荐限定实际目标区域。

## 验证方法与修改文件

按 Android 原生开发技能的分层方式分别检查像素、缩放、实际 JNI/模型、APK 与静态检查，不把模拟器结果等同于手机实玩。

- `ImageReaderProxy.kt`、`ImageReaderProxyTests.kt`：旧代码在新增的 2 组像素测试中失败；修复后覆盖横竖屏、大小端、行填充、4/5/8 字节像素步长、最后一行无尾部填充、缓冲区位置保持，以及帧缓存清除。
- `template_matcher.cpp/.hpp`、`template_matching_result.cpp`：图片算法修复。最初新增 5 组测试有 4 组复现失败，修复后全部通过；进一步增加长屏缩放测试。
- `ImageReliabilityTests.kt`：等亮度彩色图案、低对比度、多个错色候选、大搜索区动态背景、分块接缝及偏移、长屏缩放、目标消失、精确区域重复检测和区域外负例。
- `PortraitRecognitionTests.kt`：同一个检测器依次使用 361×641、641×361、1080×2400、2400×1080 的新帧。检查图片、区域/覆盖找色、文字、带小数数值的命中坐标及目标消失负例。
- `ScalingManagerTests.kt`：横竖屏刷新后的全屏搜索边界和检测结果坐标，不交换 X/Y。没有改变现有缩放策略。

本地日志与 APK 放在未提交的 `artifacts/` 中；不把用户截图打进 APK 或源码仓库。真实手机游戏帧率、特效遮挡及用户已有模板仍需要实际复测。
本轮收到的是完整界面截图，没有原始图片模板和完整条件配置，未用相同输入重现截图中的 7.65% 分数；已复现的是上述通用像素和算法问题，不能把它们写成该分数的唯一原因。

### 本轮结果

- 本地单元测试：132 个套件、**1291 项通过**，0 失败、错误或跳过。
- Google Small_Tablet / Android 12 / x86_64：80 项原生测试中 **77 项通过**、0 失败；私人截图未提供、可选十分钟长跑未开启及原有素材生成工具共 3 项明确跳过。简体中文模型组实际执行，不能把此结果写成全部 80 项均通过。
- 新增 6 组图片回归、4 组横竖屏四类识别回归、2 组截图像素复制回归及 1 组缩放刷新回归；包含目标消失和相似错误目标负例。
- 四种 ABI 和通用 Debug APK 构建通过。应用 Lint 0 错误、16 项既有警告。

交付包：`artifacts/Klickr-portrait-recognition-20261011-universal-debug.apk`，包名 `com.buzbuz.smartautoclicker.debug`，与正式版独立安装；本次未创建正式发布标签。

SHA-256：`F802BBE8A199DCD5CCED1A0E5EC380D9C3820A73FE5294C2A8E4643F01D8FD58`。

## 升级与已有脚本

- 不需要删除场景或重建全部脚本。文字语言、目标文字、数值格式以及允许差异保持原设置。
- 如果旧版曾从颜色已读错的截图中保存图片模板或取色值，修复截图不能自动恢复这些旧数据。升级后仍失败的这类条件应**重新截取目标图片或重新取色**；不要通过大幅降低阈值来掩盖旧数据错误。
- 横屏和竖屏中的游戏布局若实际不同，已有绝对坐标选区也需要调整。本次修复不意味着能自动判断重排后的目标位置，也不是任意旋转、透视或跨设备布局识别。

## 复现命令

```powershell
.\scripts\build-windows.ps1 -Tasks @('testFDroidDebugUnitTest', ':smartautoclicker:assembleFDroidDebug', ':smartautoclicker:lintFDroidDebug')
.\scripts\build-windows.ps1 -Tasks ':core:smart:detection:assembleFDroidDebugAndroidTest'

# 只指定自己的官方模拟器，避免连接真机时误运行测试。
adb -s emulator-5584 install -r core/smart/detection/build/outputs/apk/androidTest/fDroid/debug/detection-fDroid-debug-androidTest.apk
.\scripts\prepare-chinese-ocr-tests.ps1 -Serial emulator-5584
adb -s emulator-5584 shell am instrument -w -r -e requireChineseModel true com.buzbuz.smartautoclicker.core.detection.test/androidx.test.runner.AndroidJUnitRunner
```

中文模型按已有准备脚本校验散列；未准备模型时强制测试失败，不得当作中文已验证。私人截图测试在没有本地素材时明确跳过，素材生成工具保持原有跳过。

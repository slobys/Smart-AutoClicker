# 连续图片测试：安装包核对与模板内存隔离

日期：2026-10-12。接续截图缩放修复的复测，不降低阈值，不修改用户保存的条件、模板或路线，不通过保留高分掩盖新帧漏检。

## 本次仍归零的原因

读取 MuMu / Android 12 / 900×1600 中实际安装的蓝色独立测试包，安装时间为 2026-10-12 08:26:52。其 SHA-256 与 `Klickr-portrait-recognition-20261011-universal-debug.apk` 完全一致：

`F802BBE8A199DCD5CCED1A0E5EC380D9C3820A73FE5294C2A8E4643F01D8FD58`

该包仍含旧的 `Resizing virtual display` 路径，没有应用内显式整帧缩放的实现。用户随后也确认误装了昨天的包。旧包与修复包显示相同版本号 4.0.5.25，不能只靠版本号判断此次修复是否已安装。

沿用原条件进行复核：场景“测试功能”、条件 93、原模板 82×78、选区 `(299,692)-(381,770)`、允许差异 20%（接受阈值 80%）、场景精度 936。旧包再次显示 0%，日志确认虚拟显示缩至 527×936。原始原因和上一轮验证见 [截图坐标错位修复记录](continuous-capture-alignment-validation-20261012.md)。

覆盖安装本轮正确 APK 后，重新拉取设备 APK，核对哈希与交付文件一致。日志确认物理采集 900×1600，应用内输出检测帧 527×936；同一原条件即时测试显示 99.35%，绿框命中。安装前后原模板文件 SHA-256 相同，没有重新截图、修改选区或保存条件。

本轮只有即时实际命中截图，不据此宣称完成了逐帧长时间或手机真机游戏验证。未执行该场景中的用户点击动作。用户继续操作测试界面时，停止自动输入以免干扰。

## 另外发现并修复的公共模板缺陷

排查中发现一个独立的可复现问题，不能与本次误装旧包混为一谈：

- `ConditionImage::processNewData` 对尺寸相同的模板保留了指向 Java Bitmap 像素的非拥有型 Mat。
- 后续将另一张较大的模板缩放到相同输出尺寸时，OpenCV 可能复用这块目标内存，改写之前缓存模板的像素；JNI 返回时，该 Java Bitmap 的像素锁已经释放。
- 修改 `condition_image.cpp`，尺寸相同也复制到原生自有、可复用的目标缓冲。后续缩放不再写入旧 Java 模板；不逐帧保留模板历史。
- `ImageReliabilityTests.kt` 新增原生 JNI 回归：交替检测原尺寸模板和需缩放模板，连续八轮检查原模板像素不变且原目标仍可命中。修复前第一轮即失败，像素数组第 66 项被改写；修复后通过。

本修改作用于公共图片识别入口，不针对特定图标、脚本或游戏增加特判，不新增依赖、权限、模型或数据迁移。

## 验证结果

按 Android 原生开发测试流程，先通过改动前的应用基线构建，再运行真实 JNI 失败回归、修复后回归与全量验证。

- 新增模板不可变回归：修复前失败，修复后通过；图片可靠性套件 7 项全部通过。
- Google Android 12 / x86_64 原生测试：81 项定义中 78 项通过、0 失败，2 项前提不满足跳过、1 项原有素材生成工具忽略。简体中文模型实际加载，包含图片、文字、数值、颜色及横竖屏回归。
- JVM 单元测试：134 个套件、1307 项通过，0 失败、错误或跳过。
- 四种 ABI 及通用 Debug APK 构建通过；Lint 0 错误、16 项既有警告；APK v2 签名验证通过。
- MuMu 当前安装 APK 与本轮交付 APK 哈希一致；原模板文件前后哈希一致。

日志、截图、安装包和只读诊断副本仅留在本机未提交的 `artifacts/` 中，不提交用户画面或数据库。

## 本轮唯一交付包

`artifacts/Klickr-continuous-recognition-fix-20261012-universal-debug.apk`

包名 `com.buzbuz.smartautoclicker.debug`，版本 4.0.5.25，可覆盖已有蓝色独立测试版，保留应用数据；不是绿色正式版升级包。本轮不创建正式发布标签。

SHA-256：`6D0586CAE9F33CC29C3DC772B07E26A01886FE1A300FA8741A03ED86CFF582A1`。

```powershell
.\scripts\build-windows.ps1 -Tasks @('testFDroidDebugUnitTest', ':smartautoclicker:assembleFDroidDebug', ':core:smart:detection:assembleFDroidDebugAndroidTest', ':smartautoclicker:lintFDroidDebug')
.\scripts\prepare-chinese-ocr-tests.ps1 -Serial emulator-5584
adb -s emulator-5584 shell am instrument -w -r -e requireChineseModel true com.buzbuz.smartautoclicker.core.detection.test/androidx.test.runner.AndroidJUnitRunner
```

# Android 路线功能验证记录

记录日期：2026-09-29。功能仍为实验功能；本文记录开发测试，不代表已适配所有商业游戏。

## 本次改动

- `core/smart/detection`：基于现有 OpenCV 的小地图平移匹配，遮罩中心玩家标记，拒绝弱纹理、歧义及不一致结果。
- `core/smart/processing/routes`：视觉定位、有限关键帧、定位测试、路线录制／回放、固定摇杆短按和失败保护；保留数字坐标模式。
- `DetectorEngine`、`ScenarioProcessor`、`RuntimeDebugger`：场景内调用路线，切换并恢复截图尺寸，协调暂停和截图生命周期。
- `core/smart/domain`、`core/smart/database`：新增“执行路线”动作及 27→28 数据库迁移，补齐映射和序列化。
- `feature/smart-config`：路线配置、轨迹预览、执行路线动作编辑器、中英文提示；不改变主悬浮菜单的贴边交互。
- `scripts/route-smoke-map`：独立合成地图测试应用，不访问真实游戏。

遵循 Android 开发技能中的资源生命周期和回归验证要求：不新增第三方依赖；视觉模式不加载 OCR；限制关键帧、路线文件与轨迹显示数量，结束时释放图像与执行资源。

## 自动化结果

| 范围 | 结果 |
| --- | --- |
| database 单元测试 | 120 通过，0 失败，0 错误 |
| domain 单元测试 | 75 通过，0 失败，0 错误 |
| processing 单元测试 | 264 通过，0 失败，0 错误 |
| smart-config 单元测试 | 274 通过，0 失败，0 错误 |
| 小地图原生匹配设备测试 | 11 通过 |
| FDroid Debug APK | 构建成功 |
| smart-config / processing Lint | 0 错误；分别有 28 条及 1 条警告 |

单元测试合计 733 项。设备匹配测试覆盖静止、双向平移、中心标记动画、错误地图、空白、重复纹理、遮挡、旋转、缩放、过大位移、连续动画帧和无效输入。仍有 Lint 警告，不将此次结果称为“零警告”；processing 的一条为位图缩放的 `UseKtx` 写法建议。

可从仓库根目录复核：

```powershell
./gradlew.bat :core:smart:database:testFDroidDebugUnitTest :core:smart:domain:testFDroidDebugUnitTest :core:smart:processing:testFDroidDebugUnitTest :feature:smart-config:testFDroidDebugUnitTest
./gradlew.bat :smartautoclicker:assembleFDroidDebug :feature:smart-config:lintFDroidDebug :core:smart:processing:lintFDroidDebug
./gradlew.bat :core:smart:detection:connectedFDroidDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.buzbuz.smartautoclicker.core.detection.MinimapMatcherTests'
```

最后一条需连接专用 Android 测试设备，不能在正在使用的游戏设备上运行。

## Google 官方模拟器实际操作

设备：Small_Tablet，Android 12 / API 32，1920×1200，320 dpi。使用独立 debug 包和仓库中的合成地图，没有启动腾讯模拟器，也没有修改正式版应用数据。

| 操作 | 实际结果 |
| --- | --- |
| 合成视觉路线回放 | 从 `(100,100)` 沿 L 形路线到 `(110,110)`，移动 2 次后结束 |
| 暂停 | 暂停后等待 4 秒，移动次数保持 0 |
| 小地图遮挡 | 无法定位后暂停，未发起移动；恢复画面并继续后到达终点 |
| 定位测试 | 手动慢走后取得 20 个有效观测，测试通过；测试自身不发起移动 |
| 手工录制与保存 | 录制两段移动，保存 3 个路径点；回到起点后新路线回放成功 |
| 固定摇杆 | 使用 500 毫秒短按，L 形路线移动 2 次后结束 |
| 智能场景调用 | 从界面创建一次性计时器触发器和“执行路线”动作；到达终点，运行历史记录成功，耗时约 5.7 秒 |
| 场景内错误地图 | 报告 `LOST_POSITION` 并停止场景；与调用前相比，移动次数没有增加 |

集成测试发现并修复了一个实际问题：场景识别使用缩小后的截图，而路线坐标使用屏幕物理像素。现在执行路线前切换为物理像素截图，结束后恢复普通场景的缩放；模拟器日志确认切换与恢复，另有成功和异常两条单元测试覆盖。

测试应用及复现步骤见 [测试地图说明](../scripts/route-smoke-map/README.md)，用户操作见 [中文路线指南](route-recorder-zh.md)。截图、日志及测试 APK 留在本地 `artifacts` 目录，不作为游戏兼容性证据提交。

## 尚未验证与明确限制

- 未在真实商业游戏、云手机上完成验收，未进行数小时连续路线压力测试；本次短路线成功不代表长期稳定性已经证实。
- 视觉模式仅适合布局固定、方向和缩放固定、玩家标记居中的滚动小地图。不支持随机副本探索、旋转／缩放地图、动态摇杆或同时移动和攻击。
- 不会自动绕开新出现的障碍，不会自动识别战斗策略。卡住、换图或定位失败时停止／暂停，不能以反复继续代替重新定位。
- 暂停阻止后续手势，不能撤销游戏已经接受的寻路；已发出的摇杆短按最长 800 毫秒后结束。
- 路线保存在本机，尚不随场景备份导出；跨设备导入场景后必须重新配置并选择路线。
- 保留现有版本号，提供独立测试 APK；此次没有发布新的正式 Release。

# 本地路线测试地图

此目录只用于开发验证，不打包进 Klick'r。测试应用不请求权限、不联网，不访问任何游戏。
它用 Canvas 显示整数坐标和固定地图标记，地面点击会改变坐标，便于验证真实截图、原生 OCR 和无障碍手势的完整链路。

## 构建与运行

需要现有 Android SDK（platform、build-tools、platform-tools）、JDK 和 Android debug.keystore。
在仓库根目录用 PowerShell 执行：

```powershell
./scripts/route-smoke-map/build.ps1
# 以下示例仅针对专用测试模拟器，不要替换为正在运行游戏的设备。
$routeAdb = "$env:LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"
& $routeAdb -s emulator-5554 install -r artifacts/route-smoke-map/route-test-map.apk
& $routeAdb -s emulator-5554 shell am start -n org.klickr.routetest/.RouteMapActivity
```

在 1920×1200、横屏的测试设备上：角色锚点是 `(960,600)`，每偏移 10 像素点击，地图坐标改变 1。
屏幕底部 100 像素内：左半边重置到 `(100,100)`，右半边切换地图标记。
其他分辨率使用实际画面中心作为锚点。

## 测试配置

可以通过路线页面完整配置，或在**专用测试模拟器的 debug 安装**中载入测试夹具：

```powershell
& $routeAdb -s emulator-5554 shell "run-as org.klickr.routetest cat files/route.json | run-as com.buzbuz.smartautoclicker.debug sh -c 'mkdir -p files/routes; cat > files/routes/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.json'"
```

这会写入/替换一个固定 ID 的测试路线，不要对真实用户数据使用此命令。正式版不支持 `run-as`。
夹具每次测试地图尺寸初始化时生成，包含当前分辨率、地图标记与校准；路线为 `(100,100) → (110,100) → (110,110)`。

手工配置的区域（像素）：X `(40,110)-(185,175)`、Y `(230,110)-(385,175)`、地图标记 `(40,30)-(360,105)`。
选择地面点击；校准 A 为锚点右侧 100 像素，B 为锚点下方 100 像素。

## 验收

1. 正常回放：从 `(100,100)` 回放夹具，应依次到达两个拐点/终点，Moves 为 2，显示到达终点，之后不再点击。
2. 录制：重置后开始录制，等坐标有效，再手动点击两个校准位置，各停留至少 2 秒；结束后应保存 3 个路径点。重置后回放这条新录制的路线。
3. 暂停：回放刚开始就暂停，等待 5 秒，Moves 不增长；继续后完成。
4. 地图保护：回放前切换为 OTHER MAP，等待 5 秒，应暂停，Moves 为 0。
5. 起点保护：先离开起点，再回放，应提示起点不符且不移动。
6. 悬浮条：主菜单先靠左或靠右，再打开路线运行条；全部按钮应在屏幕内，拖动仍可用。

配置窗口打开时会拦截底层触摸。需要重置底层测试地图时，可关闭窗口后点底部左侧，或者仅重启 `org.klickr.routetest`，不要停止 Klick'r。
验证悬浮条时不要用 `uiautomator dump`：某些模拟器会暂时解绑其他无障碍服务，从而关闭 Klick'r 的悬浮窗。使用截图与日志：

```powershell
& $routeAdb -s emulator-5554 logcat -d -s RouteTestMap:I '*:S'
```

模拟地图不代表真实游戏的寻路、摇杆死区、动画、碰撞或镜头行为。真实游戏仍需单独验收。

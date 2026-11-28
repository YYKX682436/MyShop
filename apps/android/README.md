# MyShop Android · 第二阶段

正式包 `aikayun.muchen.shop`，艾卡云 `1.0.1 (3)`。最低 **Android 12 / API 31**（第一阶段是 API 26）；targetSdk 保持 36，compileSdk 37。

本轮包含 P2 悬浮顶栏/七项菜单、P3 固定 Adaptive Icon/输入页/动态商城品牌/进入过渡、P6 edge-to-edge 与 IME、安全区、P7 完整电脑模式，以及输入错误、旧实例覆盖绑定和应用名修复。P4/P5、支付、定位、更新中心、下载桥均未接入；没有增加媒体、相机或定位权限。

## 授权和生命周期

保留第一阶段 `requestId / viewId / documentId`、candidate/bound 分离、严格 resolve 协议和同源策略。主文档授权有效、commit/finish 与可视回调完成后才提交绑定；写入前再检查现存绑定。onStart 同步持久化绑定，入口 singleTask 进一步收敛重复入口。清除应用数据仍可重新绑定；覆盖升级不清数据。

ComponentActivity 与一个 Compose 根宿主管理唯一 WebView 的挂载。Activity 是销毁的唯一所有者，从实际 parent 拆下后销毁。重组、菜单、顶栏显隐、授权复查遮罩不创建 WebView、不调用 loadUrl；后台复查网络失败保留原实例与输入，明确拒绝隔离销毁。普通导航无全屏开屏/进度条。返回先关菜单，再用 WebView 历史，根页面退出。分享/浏览器/复制读取当前完整 URL；显式回首页跟踪同源跳转链，最终文档成功可视提交后只清一次历史；用户其他导航、失败、后台或失效会取消。

## 玻璃、品牌和输入

固定使用 Maven Central `io.github.neoruaa.glasmio:glasmio-ui:0.1.4`，对应 `v0.1.4 / 5be4af46778c7e5fb36d6f4e74d52dbf135c3fbf`。通过公开 `rememberLayerBackdrop(onDraw)`、`glassBackdropSource`、`trackBackdropPosition`、`GlasmioBackdropProvider` 接入真实 WebView，GlassSurface/RegularSmall、GlasmioMenu/Item 提供材质、按压和菜单动画。顶栏是圆球 / 双行标题胶囊 / 圆球三块独立表面，默认 52dp 高、8dp 间隙，头像 44dp；标题 17sp、当前同源 host 12sp。字号较大时统一由 ShellDimensions 调整高度和唯一 topBarDp（默认仍为 68dp）。右球不叠第二层玻璃；菜单关闭 morph、保留锚点，在右球下方独立展开。菜单宽 224dp、48dp 行、16sp/22sp、八个按需 Material Outlined 图标、两条分隔线；可用高度扣除锚点和安全区，短屏滚动至最后一项。

真实网页继续硬件绘制；三块共用一张 0.4 倍局部采样，按实测顶栏/菜单边界加 32dp 模糊余量裁剪。网页 dirty 合并到下一次硬件绘制前，一帧至多一次，无 67ms 固定等待、无常驻帧循环，不对旧新帧插值。捕获仍在 beforeHardwareDraw → 受保护的局部软件 draw → 同一次正常硬件绘制的顺序内，避免破坏 Chromium 内部滚动命中。自己安排的 invalidate 不再标记 dirty。每帧新 Bitmap，身份/尺寸变化立即失效；普通退场的 TransitionState 与菜单 alpha 尾段保持采样许可，完全退场后停止，唤回先清旧帧。Activity 唯一拥有生命周期/授权/遮罩/可视就绪硬门，Compose 只提供动画可见性，旧 SideEffect 无法重新开启硬门。静止不采样；DEBUG 才收集耗时和 dirty 到发布延迟，正式包没有性能文字或开关。

顶栏保留 RegularSmall，40% 面色、8dp frost、8dp/1.5dp 折射和 -0.25 光感提升，正文和头像不降低透明度；菜单沿用库原有材质。Android 12/12L 使用库内简化效果，完整折射需要 Android 13+；真机范围见交付报告。

头像与标题胶囊使用 GlassSurface 的 nativeNavigationFlex 按压回弹，点击只反馈、不导航。向上累计至少 96dp 且主页面已越过预留顶栏空间才隐藏；向下 24dp 唤回，单次轻滑不再立即收起。嵌套滚动仍按手势方向处理。

菜单通过公开 Backdrop 接口直接重放已有局部 WebView 帧，交给库的 stationary sampler 处理动画坐标，避免跨窗口重复导出整个 Activity 图层。菜单不导出未使用的子玻璃背景，alpha 回调只在可见性边沿更新状态；保留 GlasmioMenu 的原材质、折射和普通弹簧动画，不增加采样循环。正式包实测改善主要在帧耗时中位数，尾部偶发长帧仍见验收报告，不能视为全程零掉帧。

系统字体、原创云/卡片品牌矢量与按需 Google Material Outlined 动作图标，固定 launcher 前景/背景/单色层。MYSHOP 优先使用 resolve 品牌；EXTERNAL 从授权首页补品牌，失败用域名/默认图标。HTTPS 图标使用成熟 AndroidSVG/BitmapFactory 解码，最多 512KiB、连接/读取各 5 秒、192px 输出，旧缓存可立即显示。缓存按 canonical origin 隔离，只有匹配正式绑定才写长期缓存，品牌不能充当授权。输入页支持域名/完整 HTTPS 地址和键盘进入，错误保留输入；动画由 Compose 系统时长设置控制，不参与绑定判定。

## 安全区和电脑模式

主题明确关闭导航栏对比度保护；真机证实只在 decor 创建前设置 Window 属性会被默认主题重新覆盖，导致手势区出现系统浅色遮层。MyShop 手机模式 WebView 接收系统原生 insets，ComposeView 不提前消费；CSS env 处理系统栏/挖孔，WebView visualViewport 处理 IME。真机确认桌面缩放后原有 CSS safe-area 的首屏和底部避让不足，因此电脑模式用稳定原生边距处理顶部/侧边系统栏与挖孔，底部不再缩短 WebView，未处理 bottom inset 继续传给 WebView。已有网站 viewport 脚本用真实 visualViewport.width / containerWidthDp 换算 CSS 底部安全区；背景画到手势条后，按钮、导航和客服输入单独避让。原生仅将已处理的 top/side inset 置零继续传递；IME 仍只由 WebView 处理，没有额外键盘 padding。额外顶栏间距并入网站唯一 viewport 脚本，按实际 viewport CSS 宽度 / WebView dp 宽度换算，显隐不改正文布局。客服、购买区的既有 env/visualViewport 机制保留。授权外站采用稳定原生安全边距并将已处理系统 inset 置零继续传递，只有其 viewport 由原生最小兼容脚本管理。

电脑模式按绑定站点保存，复用 WebView、Cookie、历史和当前 URL。桌面 UA 保留真实 Chromium 版本；有 USER_AGENT_METADATA 时同步 mobile/platform 等信息，无特性则只降级 UA。手机与电脑 UA 都含 `AikayunShop/1.0.1`；该标记只供显示和付款设备判断，不授予身份或权限。稳定 GET 页面切换只 reload 一次；POST/未知方法或加载中拒绝切换，WebView 表单重发明确取消。MYSHOP 采用 1280 CSS px、桌面断点和双指缩放；同时按 WebView 实际物理像素宽度设置原生初始缩放，避免只变宽布局而继承手机放大倍率（整数百分比会带来约 1% 的视口取整误差）；首个可绘制文档若仍保留旧倍率，再按实际 visualViewport.width 做一次有身份检查的 zoomBy 校正，不增加重载，也不持续纠正用户后续的双指缩放，恢复手机时恢复原 UA/元数据与 device-width。

原生只向当前授权的精确 origin 顶层文档注入 `{version, origin, desktop, topBarDp, containerWidthDp}` 和显示事件，优先 document-start，不含尾斜杠；持久化和授权契约仍使用带斜杠 canonical origin。无 JS→native 权限方法，安全区/键盘不另造桥。普通浏览器继续原逻辑。

## 构建、签名和验证

AGP 9.4.1、Gradle 9.6.0、JDK 21、内置 Kotlin、Compose compiler 2.4.21、BOM 2026.09.00。直接消费发布 AAR，无需 NDK/CMake。Release 开启 R8 和资源压缩；[第三方归属](third_party/README.md) 同时打进 APK。

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
python3 apps/android/scripts/build-release.py
```

沿用本机 `~/Downloads/Android App正式签名密钥/MUCHEN.jks` 及现有密码读取方式。脚本读取 Gradle 版本生成唯一产物 `dist/android/aikayun-1.0.1-3-shell-release.apk`，执行 Release Lint、正式签名、签名与 16KiB ZIP 对齐校验。密码通过子进程环境传递，不入 Git，不创建密钥。交付报告为 `dist/android/phase2-rework-acceptance.txt`，必要证据在 `dist/android/phase2-rework-evidence/`。

```sh
apps/android/gradlew -p apps/android :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease
adb -s feb70d35 install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
adb -s feb70d35 install -r apps/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s feb70d35 shell am instrument -w -e class aikayun.muchen.shop.PhaseOneTest aikayun.muchen.shop.phase1test.test/android.test.InstrumentationTestRunner
node apps/web/scripts/test-app-display.mjs
pnpm --dir apps/web test:logic
```

debug 使用独立 `aikayun.muchen.shop.phase1test`，fixture Activity 和实际网站 viewport 测试资产仅在 debug。显式指定测试类避免旧 SDK runner 扫描全部 Compose 依赖。保留第一阶段所有授权/旧回调/失败/超时/存储断言；手工回调测试只证明状态隔离，不冒充真实网络。新增实际 WebView 采样、静止停止、可见输入错误、绑定竞争、真实点击后的模式/历史/剪贴板、POST 不重发、旋转重启和实际 PNG/SVG 读取测试。没有生产登录/外站授权/撤权条件的项目继续 NOT_VERIFIED。

网页实际变更通过仓库唯一入口 `bash scripts/release-production.sh` 发布；Android 自身不需服务器部署。最终正式包用 `adb -s feb70d35 install -r`，不卸载正式包。

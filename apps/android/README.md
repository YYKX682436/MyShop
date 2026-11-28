# MyShop Android · 第二阶段

正式包 `aikayun.muchen.shop`，艾卡云 `1.0.1 (2)`。最低 **Android 12 / API 31**（第一阶段是 API 26）；targetSdk 保持 36，compileSdk 37。

本轮包含 P2 悬浮顶栏/七项菜单、P3 固定 Adaptive Icon/输入页/动态商城品牌/进入过渡、P6 edge-to-edge 与 IME、安全区、P7 完整电脑模式，以及输入错误、旧实例覆盖绑定和应用名修复。P4/P5、支付、定位、更新中心、下载桥均未接入；没有增加媒体、相机或定位权限。

## 授权和生命周期

保留第一阶段 `requestId / viewId / documentId`、candidate/bound 分离、严格 resolve 协议和同源策略。主文档授权有效、commit/finish 与可视回调完成后才提交绑定；写入前再检查现存绑定。onStart 同步持久化绑定，入口 singleTask 进一步收敛重复入口。清除应用数据仍可重新绑定；覆盖升级不清数据。

ComponentActivity 与一个 Compose 根宿主管理唯一 WebView 的挂载。Activity 是销毁的唯一所有者，从实际 parent 拆下后销毁。重组、菜单、顶栏显隐、授权复查遮罩不创建 WebView、不调用 loadUrl；后台复查网络失败保留原实例与输入，明确拒绝隔离销毁。普通导航无全屏开屏/进度条。返回先关菜单，再用 WebView 历史，根页面退出。分享/浏览器/复制读取当前完整 URL；回首页仅在该次首页绘制成功后清历史。

## 玻璃、品牌和输入

固定使用 Maven Central `io.github.neoruaa.glasmio:glasmio-ui:0.1.4`，对应 `v0.1.4 / 5be4af46778c7e5fb36d6f4e74d52dbf135c3fbf`。通过公开 `rememberLayerBackdrop(onDraw)`、`glassBackdropSource`、`trackBackdropPosition`、`GlasmioBackdropProvider` 接入真实 WebView，GlassSurface/RegularSmall、GlasmioIconButton、GlasmioMenu/Item 提供材质、按压和菜单动画。

真实网页继续硬件绘制；另用 WebView.draw(Canvas) 在当前 scrollX/Y 的正确坐标裁剪顶部小范围，0.4 倍采样，合并内容变化、最多 15 fps。顶栏时截 156dp，菜单时扩大到 560dp（包含模糊外扩），不采窗口、不采原生顶栏、不抓 HTML 长页。每帧使用新 Bitmap，身份/尺寸变化失效；隐藏、遮罩、静止和后台停止采样。Android 12/12L 使用库内简化效果，完整折射需要 Android 13+；真机范围见交付报告。

系统字体和原创云/卡片矢量图标，固定 launcher 前景/背景/单色层。MYSHOP 优先使用 resolve 品牌；EXTERNAL 从授权首页补品牌，失败用域名/默认图标。HTTPS 图标使用成熟 AndroidSVG/BitmapFactory 解码，最多 512KiB、连接/读取各 5 秒、96px 输出。缓存按 canonical origin 隔离，只有匹配正式绑定才写长期缓存，品牌不能充当授权。输入页支持域名/完整 HTTPS 地址和键盘进入，错误保留输入；动画由 Compose 系统时长设置控制，不参与绑定判定。

## 安全区和电脑模式

MyShop WebView 接收系统原生 insets，ComposeView 不提前消费；CSS env 处理系统栏/挖孔，WebView visualViewport 处理 IME。额外顶栏间距并入网站唯一 viewport 脚本，按实际 viewport CSS 宽度 / WebView dp 宽度换算，显隐不改正文布局。客服、购买区的既有 env/visualViewport 机制保留。授权外站采用稳定原生安全边距并将已处理系统 inset 置零继续传递，只有其 viewport 由原生最小兼容脚本管理。

电脑模式按绑定站点保存，复用 WebView、Cookie、历史和当前 URL。桌面 UA 保留真实 Chromium 版本；有 USER_AGENT_METADATA 时同步 mobile/platform 等信息，无特性则只降级 UA。手机与电脑 UA 都含 `AikayunShop/1.0.1`；该标记只供显示和付款设备判断，不授予身份或权限。稳定 GET 页面切换只 reload 一次；POST/未知方法或加载中拒绝切换，WebView 表单重发明确取消。MYSHOP 采用 1280 CSS px、桌面断点和双指缩放，恢复手机时恢复原 UA/元数据与 device-width。

原生只向当前授权的精确 origin 顶层文档注入 `{version, origin, desktop, topBarDp, containerWidthDp}` 和显示事件，优先 document-start，不含尾斜杠；持久化和授权契约仍使用带斜杠 canonical origin。无 JS→native 权限方法，安全区/键盘不另造桥。普通浏览器继续原逻辑。

## 构建、签名和验证

AGP 9.4.1、Gradle 9.6.0、JDK 21、内置 Kotlin、Compose compiler 2.4.21、BOM 2026.09.00。直接消费发布 AAR，无需 NDK/CMake。Release 开启 R8 和资源压缩；[第三方归属](third_party/README.md) 同时打进 APK。

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
export JAVA_HOME="$(/usr/libexec/java_home -v 21)"
python3 apps/android/scripts/build-release.py
```

沿用本机 `~/Downloads/Android App正式签名密钥/MUCHEN.jks` 及现有密码读取方式。脚本读取 Gradle 版本生成唯一产物 `dist/android/aikayun-1.0.1-2-shell-release.apk`，执行 Release Lint、正式签名、签名与 16KiB ZIP 对齐校验。密码通过子进程环境传递，不入 Git，不创建密钥。交付报告为 `dist/android/phase2-acceptance.txt`，必要证据在 `dist/android/phase2-evidence/`。

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

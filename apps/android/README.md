# MyShop Android · 第一阶段

从已验收的清理提交 `1eece2d3aad77a111876c8e6379a74577a2162d5` 新建的 Kotlin 原生工程。
只提供输入商城网址、中央授权、单 WebView、成功绑定、系统返回和错误重试。

- 正式包：`aikayun.muchen.shop`，`1.0.1 (1)`；Android 8.0 / API 26 起。
- 中央服务默认 `https://mc.mc520.top`，仅构建参数 `-PauthorityOrigin=https://authority.example` 可替换。
- 不维护客户端域名许可名单，不读取品牌，不增加原生桥。
- 仅声明 `INTERNET`，关闭云备份和设备迁移。清除应用数据或卸载后才能重新绑定。
- 主文档需同源、授权有效、`onPageCommitVisible` 与 `onPageFinished` 均完成，并通过可视状态回调，才同步保存绑定。错误、取消、超时均销毁当前候选实例，使旧回调失效。
- 已绑定时 `onStart` 是授权复查唯一入口；网络失败保留隐藏实例，允许后恢复原页面。明确拒绝销毁实例但保留绑定。
- 旋转在 Activity 内处理；后台中的未绑定候选会取消，返回后需重新确认。
- JavaScript、DOM Storage 和普通 Cookie 保留；同源链接留在 WebView，外部 HTTPS GET 交系统浏览器，跨域 POST/自定义协议提示不支持。
- 30 秒页面超时；授权请求连接 10 秒、读取 15 秒，不跟随 API 重定向，响应最多 64 KiB。

## 本机构建与正式签名

使用稳定的 AGP 9.3.2、Gradle 9.6.0、SDK 36 / Build Tools 36.0.0、JDK 17 或兼容的稳定 JDK。本轮使用 JDK 25.0.4.1。
不修改网站依赖，不触发网站发布。

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"
python3 apps/android/scripts/build-release.py
```

签名脚本读取本机 `~/Downloads/Android App正式签名密钥/MUCHEN.jks` 和对应的 `签名密钥密码.env`，验证现有 JKS 的唯一 alias，凭据只通过子进程环境传递，不写入 Gradle 或仓库，不创建密钥。可用 `MYSHOP_KEYSTORE`、`MYSHOP_CREDENTIAL_FILE`、`MYSHOP_STORE_PASSWORD`、`MYSHOP_KEY_PASSWORD` 指定其他本机配置。

输出：`dist/android/aikayun-1.0.1-1-base-release.apk`。脚本执行 Release Lint、签名验证及 ZIP 对齐检查。最终验收记录与 APK 一起保留在 `dist/android/`。

## 针对性真机测试

`debug` 使用独立包名 `aikayun.muchen.shop.phase1test`，不会覆盖正式包。`FixtureActivity` 仅位于 debug 源集；它用受控响应测试撤权、错误、页面加载与存储，不修改生产授权规则。为适配本轮 MIUI 的后台 Activity 启动限制，测试通过 shell 启动 debug 专用 Activity，因此该 Activity 只在 debug 包中导出。release 仅包含正式入口，没有测试页面或授权旁路。

测试使用 SDK 自带的 instrumentation / JUnit 兼容库，无额外测试框架依赖。执行前明确选择一台设备：

```sh
apps/android/gradlew -p apps/android :app:assembleDebug :app:assembleDebugAndroidTest :app:lintRelease
serial=feb70d35
adb -s "$serial" install -r apps/android/app/build/outputs/apk/debug/app-debug.apk
adb -s "$serial" install -r apps/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s "$serial" shell am instrument -w aikayun.muchen.shop.phase1test.test/android.test.InstrumentationTestRunner
```

目前 14 项测试覆盖严格协议与 IDN、真实中央接口、失败 A 后绑定 B、子资源失败、受控外站授权切换、页面/历史/存储保留、撤权与网络失败重试、过期授权和页面回调、跨域首载/POST、自定义协议、首载后台/旋转、首载跨导航总超时、输入和拒绝页返回前台后的可用性。生产已登录账号、生产外站许可和生产撤权不因受控测试而视为已验收。

实现依据：[WebViewClient 回调语义](https://developer.android.com/reference/android/webkit/WebViewClient)、[WebView 生命周期管理](https://developer.android.com/develop/ui/views/layout/webapps/managing-webview)、[AGP 9.3 稳定工具链兼容性](https://developer.android.com/build/releases/agp-9-3-0-release-notes)。

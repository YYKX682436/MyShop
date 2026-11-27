# 艾卡云 Android App

独立 Android Gradle 工程，使用 Kotlin、Compose 和系统 WebView。`minSdk` 为 33，`compileSdk` 与 `targetSdk` 为 37，版本为 1.0.1（1）。

## 构建

```sh
cd apps/android
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease -PAPP_AUTHORITY_ORIGIN=https://mc.mc520.top
```

`APP_AUTHORITY_ORIGIN` 只设置授权服务入口，默认 `https://mc.mc520.top`；可绑定商城名单由主站后台维护。正式 Release 从本机读取 `/Users/muchen/Downloads/Android App正式签名密钥/签名密钥密码.env`：第一行为 keystore 密码，第二行为别名，第三行为私钥密码；私钥文件固定为相邻目录的 `MUCHEN.jks`。凭据不会进入仓库或 CI。Debug 使用独立 `.debug` applicationId 后缀。

域名输入在 Android 使用 ICU UTS #46 非过渡映射转成 ASCII punycode；网站后台由 WHATWG URL 规范化。API 只接受这些规范化后的 ASCII 主机名，避免 JDK IDNA2003 对偏差字符再次改写。

绑定与 WebView 的 Cookie/站点存储保存在 Android 应用私有数据中；系统云备份与设备迁移均关闭，清除应用数据后需要重新绑定。

## Android 页面协议 v1

WebView 只在授权 origin 上注册 AndroidX WebKit `WebMessageListener`，并校验消息的 `sourceOrigin` 与 `isMainFrame`。文档起始脚本提供 `window.__AIKAYUN_APP__`；消息带 requestId，包含平台/电脑模式、Insets、网页弹层状态、支付离站登记，以及有界分块的 `saveText`、`saveBlob`、`shareImage` 和 HTTPS 图片保存。付款登记成功 ACK 后，网站才执行原有 Form POST 或跳转。外部网站不启用私有文件传输和 MyShop 专属支付能力。此通道不代表用户登录身份，也不向 iframe 或支付域开放商城桥。

玻璃效果直接复用 AndroidLiquidGlass 固定源码；WebView 背景以独立硬件渲染层作为 backdrop source，玻璃组件位于该 source 之外。该组合仍需在目标真机上按静止、滚动、输入法与前后台逐项确认。

完整上游来源与组件清单见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

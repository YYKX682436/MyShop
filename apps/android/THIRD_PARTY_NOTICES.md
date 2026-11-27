# 第三方源码

## AndroidLiquidGlass / Backdrop

- 来源：[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)
- 固定提交：`eb4a0ea2e17542ea28c2e71088f7dd9acf77aad5`
- 授权：Apache License 2.0，完整文本保存在 `third_party/AndroidLiquidGlass/LICENSE`，App 的“关于 → 开源许可”提供许可文本。
- backdrop 核心保留 `commonMain` 与 `androidMain` expect/actual 和 AGSL shader；本地 KMP 模块只启用 Android target，移除了发布任务与 iOS、桌面、JS、Wasm 构建目标，没有替换核心算法。

直接复制的示例组件：

- `app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidButton.kt`
- `app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/InteractiveHighlight.kt`
- `app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/DragGestureInspector.kt`

从 `LiquidBottomTabs.kt` 提取胶囊底板的 backdrop 材质到 `app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidCapsule.kt`；没有复制底部多标签的选中状态机。`DialogContent.kt` 属于上游演示页面，没有复制。

三个直接复制文件顶部标记了来源与提交。`LiquidCapsule.kt` 同样标注了提取来源。上游效果核心和 shader 保持原样；Android-only Gradle 配置与产品组件使用方式属于本项目适配。

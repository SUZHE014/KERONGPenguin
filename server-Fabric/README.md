# server-Fabric（fa 版 / Fabric 适配器，测试版）

KERONGPenguin 的 Fabric 端适配模块。**与主线同步规则（用户口径，2026-11-10
1.7.5.1 轮确立）：fa 版移入主线仓库统一保存，但只有用户明确要求更新时才
随主线升版，平时冻结在当前版本（现 1.7.4.1）**——主线日常改动不需要
同步本模块，发版说明 / 官网卡片也按当前实际发布的 fa 版本号写。

## 构建产物版本对齐状态

- 主线已发布：1.7.5.1（Spigot / NeoForge，2026-11-10）
- fa 版已发布：**1.7.4.1**（2026-11-09，源码即本目录当前状态）
- 下次用户点名更新 fa 时：对齐主线（common-Bot / manager 源码 + 模板 +
  config.yml + fabric.mod.json 版本），并用 fabric 构建环境出包。

## 默认不进 Gradle 构建

`settings.gradle.kts` 的 `include` 列表**不含本模块**（构建期依赖体积大
且不入库，默认构建会因缺少 libs 失败）。构建 fa 版时在 settings.gradle.kts
临时加回：

```kotlin
include(":server-Fabric")
project(":server-Fabric").projectDir = file("server-Fabric")
```

## 构建期依赖（server-Fabric/libs/，不入库）

见 build.gradle.kts 注释：mojmap 名 minecraft jar（与 NeoForge 版共用）、
fabric-api（需先用 tiny-remapper 从 intermediary 重映射到 mojmap 名）、
fabric-loader、brigadier、authlib、deps.jar（QQ SDK 底料，同
common-Bot/libs/deps.jar）。历史原料在部署服务器 `/root/fabric-deps/`
（156.233.228.248），打包脚本用 tiny-remapper 把模块产物从 mojmap 重映射
回 intermediary（两步打包详见 build.gradle.kts 头部注释）。

## MC 版本支持（2026-11-10 评估）

- `fabric.mod.json` 声明 `minecraft >= 1.20.4`，已实测 1.20.4。
- **1.20.1 支持可行性（用户希望，尚未实施）**：本模块只使用 1.20.1 与
  1.20.4 之间完全稳定的服务端 API（ServerMessageEvents /
  ServerPlayConnectionEvents / ServerLifecycleEvents / CommandRegistrationCallback /
  ServerLivingEntityEvents / PlayerBlockBreakEvents + Commands / Component /
  ServerPlayer / MinecraftServer / LivingEntity / DamageSource / Stats），
  代码层面无 1.20.4 专属调用，理论上可下探 1.20.1；实施需要：① 改
  `fabric.mod.json` depends 为 `>=1.20.1`；② 按 1.20.1 目标重取构建
  依赖编译；③ 1.20.1 实服实测——留给下次 fa 更新轮一并做，未实测前
  官网「支持版本」不写 1.20.1。

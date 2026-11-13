# server-Fabric（fa 版 / Fabric 适配器）

KERONGPenguin 的 Fabric 端适配模块。**1.7.5.1 起移入主线发版链**：随主线
同号升版（Spigot / NeoForge / Fabric 三端同步发版），不再冻结。

## 1.7.5.1 变更（对齐主线 + fa 专属指令）

- **内核对齐主线 1.7.5.1**：共用 common-Bot 全部业务源码（/菜单 按钮菜单、
  被动回复轮转 40034128 修复、按钮管理员权限 MemberRoleCache、模板升级、
  /全量 总开关写回 setFullAmountEnabled、快捷面板刷新等），config.yml 与
  Markdown 模板目录与 NeoForge 版完全同步（config-version 43）。
- **QQ 绑定不再拦截进服（用户指令）**：未绑定玩家正常游玩，绑定完全自愿
  ——玩家在游戏内发送 `/hbkrg qbd` 获取验证码，再到 QQ 群发 `/绑定 <验证码>`
  完成；原 enforceLoginBinding 加入后踢出路径删除，进服仅建档。
- **命令权限重构**：/hbkrg 根节点不再整体要求 OP——`qbd` 所有玩家可用，
  管理子命令（reload / info / chs / mem / bpng / bpngsz / qr / help）各自
  要求权限等级 2；/qq 保留 rebind（自助解绑）与 qxqq（OP 强制解绑），
  skip / unskip 随绑定拦截一并移除。
- **更新检查**：与 Spigot / NeoForge 同一 UpdateChecker（common-Bot 共用
  源码，启动 8 秒后访问官网 version.json，update-check.enabled 可关闭）。

## 主线构建接入（settings.gradle.kts 条件 include）

构建期构件（fabric-api mojmap 重映射等）体积大不入库：`settings.gradle.kts`
在 `server-Fabric/libs/deps.jar` 与 `fabric-api-mojmap.jar` 就绪时自动把本
模块 include 进主线构建；裸 clone（无 libs）不破坏 Spigot / NeoForge 构建。

```bash
# 1. 重建构件（先确保 server-NeoForge/libs/ 已就绪，见 build_neoforge_libs.py；
#    原料目录默认 /home/z/my-project/fabric-deps，来自部署服务器 /root/fabric-deps/）
python3 scripts/build_fabric_libs.py

# 2. 主线构建（server-Fabric 自动进构建图）
./gradlew :server-Fabric:jar

# 3. 打包分发 JAR（mojmap→intermediary 重映射 + Spigot 底座合并）
python3 scripts/package_jar_fabric.py <任一Spigot分发JAR>
```

## 构建期依赖（server-Fabric/libs/，不入库）

见 build.gradle.kts 注释：mojmap 名 minecraft jar（与 NeoForge 版共用）、
fabric-api-mojmap.jar（8 个 fabric-api 模块经 tiny-remapper 从 intermediary
逆向重映射为 mojmap 名后合并）、fabric-loader、brigadier、authlib、
deps.jar（QQ SDK 底料，同 common-Bot/libs/deps.jar）。原料在部署服务器
`/root/fabric-deps/`（156.233.228.248），打包脚本用 tiny-remapper 把模块
产物从 mojmap 重映射回 intermediary（两步打包详见 build.gradle.kts 头部注释）。

## MC 版本支持

- `fabric.mod.json` 声明 `minecraft >= 1.20.4 < 1.22`（1.7.5.5 起补上限，与
  NeoForge 端 mods.toml 对称——2026 年起的 26.x 新版本线未适配，明确排除）。
- 已实测（真实服务器启动验证，1.7.5.5 轮 Fa 阶段核查）：1.20.4 / 1.20.5 /
  1.20.6 / 1.21 / 1.21.4 / 1.21.6 / 1.21.11；Fabric Loader 实际可用下限
  0.15.1（fabric-api 0.97.3+1.20.4 起要求，实测 0.15.0 无法启动 fabric-api）。
- **1.20.1 支持可行性（用户希望，尚未实施）**：本模块只使用 1.20.1 与
  1.20.4 之间完全稳定的服务端 API（ServerMessageEvents /
  ServerPlayConnectionEvents / ServerLifecycleEvents / CommandRegistrationCallback /
  ServerLivingEntityEvents / PlayerBlockBreakEvents + Commands / Component /
  ServerPlayer / MinecraftServer / LivingEntity / DamageSource / Stats），
  代码层面无 1.20.4 专属调用，理论上可下探 1.20.1；实施需要：① 改
  `fabric.mod.json` depends 为 `>=1.20.1`；② 按 1.20.1 目标重取构建
  依赖编译；③ 1.20.1 实服实测——未实测前官网「支持版本」不写 1.20.1。

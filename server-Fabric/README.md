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

- **两个产物**（1.7.5.6 起）：
  - `KERONGPenguin_Fabric-1.20.4-<v>.jar` 通用包，`fabric.mod.json` 声明
    `minecraft >= 1.20.4 < 1.22`（与 NeoForge 端 mods.toml 对称——2026 年起
    的 26.x 新版本线未适配，明确排除）；
  - `KERONGPenguin_Fabric-1.20.1-<v>.jar` 独立包（1.20.1 专用，声明精确
    `minecraft = 1.20.1`），同一源码按 1.20.1 官方映射 + intermediary 表
    单独重映射（`scripts/build_fabric_1201_map.py` 生成 merged-1201.tiny，
    含继承错位补丁：1.20.1 中 ServerGamePacketListenerImpl#disconnect 在
    class_3244 本类定义而 tiny-remapper 按 1.20.4 classpath 继承链解析到
    class_8609，需在其节补行；材料备份服务器 /root/fabric-deps/1201/）。
    1.20.2/1.20.3 网络层重构（DisconnectionDetails 引入）API 又有漂移，
    明确不支持，由加载器按声明拦截。
- 已实测（真实服务器启动验证，1.7.5.5 轮 Fa 阶段核查）：1.20.4 / 1.20.5 /
  1.20.6 / 1.21 / 1.21.4 / 1.21.6 / 1.21.11；Fabric Loader 实际可用下限
  0.15.1（fabric-api 0.97.3+1.20.4 起要求，实测 0.15.0 无法启动 fabric-api）。
  1.20.1 轮（1.7.5.6）：1.20.1 + fabric-api 0.92.x 真机验证（mod 加载 /
  命令注册 / hbkrg qr / 远程命令 brigadier 直发 / 踢人 disconnect）。
- **1.20.1 兼容的技术要点（1.7.5.6 实施记录）**：模块源码无 1.20.4 专属
  调用（fabric-api 服务端事件 + brigadier + Component 等在两版间稳定），
  但 intermediary 方法 ID 存在结构性漂移——`Commands#performPrefixedCommand`
  返回类型 1.20.1 int / 1.20.4 void（已改经 brigadier `dispatcher#execute`
  直发规避，FabricConsoleExecutor）；踢人 `disconnect(Component)` 定义类
  错位（映射表补丁修正）；其余 84 处 MC 引用经静态核对两版一致或未混淆。
  1.20.1 的 fabric-api 用 0.92.x+1.20.1（运行时由用户侧安装）。

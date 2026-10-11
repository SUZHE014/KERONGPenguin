# 🤖 KERONG Penguin

> ⚠️ **此插件是 HuHoBot 的魔改分支**（源码已按 HuHoBot 上游结构重写为可读的 Kotlin 工程）

Minecraft QQ 机器人插件，通过 QQ 官方机器人 API 实现 QQ 群与服务器的深度交互。

## 功能

- **QQ 绑定验证** — 玩家进入服务器前需绑定 QQ，5 位随机绑定码验证
- **个人信息统计卡片** — QQ 群发送 `/查信息`（1.5.2 前为 `/个人信息`），渲染深色卡片风格最多 9 项生涯统计卡片（金币/称号/在线时长/今日在线时长/累计签到/点券/屠龙次数/击杀玩家次数/死亡次数，依赖插件缺失的项自动隐藏，卡片高度自适应），背景默认内置深灰底图（随 JAR 分发，可在配置改为 img/ 目录随机图，1.5.7）；`/查信息 @成员` 可查询他人绑定的玩家信息（1.5.3）；`/查询open ID` 查询 OpenId，`/查询open ID <OpenId>` 反查该 OpenId 的 QQ 账号（群昵称 + 绑定玩家，1.5.3）
- **玩家统计自记录** — 实时累计游戏时长、击杀、死亡、挖掘、距离等数据，**按玩家 UUID** 记入各自 QUUID 主文件（一人一文件，1.5.3.1：同名不同 UUID 即两个人，互不串数据），定时异步保存，对性能几乎零影响
- **AI 对话** — QQ 群 @机器人 或服务器内输入前缀触发，支持 DeepSeek/OpenAI；群内回复引用发送者的原消息（1.5.3）
- **黑名单系统** — 管理员可将 QQ 加入黑名单，自动解绑并踢出
- **快捷指令面板** — QQ 群快捷指令菜单，根据配置动态显示
- **在线查询** — `motd.use-markdown` 开启时用 .md 模板渲染在线玩家列表；关闭时自动渲染深色风格在线列表图片（真实皮肤头像，人数多自动切长图，1.5.1）
- **消息转发** — QQ 群 ↔ 游戏双向消息转发
- **自定义执行指令** — QQ 群触发自定义服务器命令
- **群内签到** — @机器人 /签到 领取金币奖励（需 Vault，离线暂存自动发放）

## 环境

- Java 21+（NeoForge 版）/ Java 17+（Spigot 版）
- **NeoForge 1.21.1**（server-NeoForge 模组版）或 Spigot / Paper / 混合端
  （Mohist、CatServer、Arclight 等，自动检测命令执行器）
- QQ 最新版（支持机器人功能）

> ✅ **支持 Geyser-Spigot / Floodgate 基岩版互通**：QQ 绑定、查信息、查在线、
> 排行榜、签到全部按玩家 UUID 记录，基岩玩家与 Java 玩家一致可用
> （同名不同 UUID 即两个独立账号，互不串数据；1.5.7.2 起金币入账也按
> 绑定记录里的真实 UUID 解析，基岩玩家离线领奖不再掉入名字派生的幽灵账户）。

> ⚠️ NeoForge 版与 Spigot 版二选一：**不要在自带 Bukkit 的混合端上安装模组版**（会与真 org.bukkit 冲突），混合端请用 Spigot 版。

## 下载

前往 [官网下载页面](https://kerong.xin/penguin/)

## 安装教程

查看 [安装教程](https://kerong.xin/penguin/tutorial.html)

## 从源码构建

```bash
# Spigot 版
./gradlew :server-Spigot:jar        # 编译两个模块
python3 scripts/package_jar.py       # 合成可分发的 fat JAR（可选）

# NeoForge 1.21.1 版
./gradlew :server-NeoForge:jar       # 编译模组模块（共用 common-Bot 源码）
python3 scripts/package_jar_neoforge.py  # 合成可分发的模组 JAR
```

> 依赖的第三方库（kloping QQ SDK、fastjson 等）以 `libs/deps.jar` 形式提供编译期引用，
> 打包时复用原版 JAR 中的依赖字节码；NeoForge 模块的编译期真实构件可用
> `python3 scripts/build_neoforge_libs.py` 一键重建
> （Minecraft mojmap 中间产物 + NeoForge / FML / bus 官方构件，详见
> `server-NeoForge/libs/README.txt`）。

## 项目结构

```
common-Bot/      # 机器人核心（QQ 客户端、群命令、状态持久化、QQ 绑定管理）
server-Spigot/   # Spigot 平台适配（主类、命令执行器、事件监听、配置管理、卡片渲染）
server-NeoForge/ # NeoForge 1.21.1 平台适配（org.bukkit 兼容层 + @Mod 主类 + 事件/命令/控制台执行）
```

## API 文档

查看 [API 文档](docs/API.md)

## 版本

当前版本：**1.5.7.2**（Spigot 线）／ **1.5.7.1**（NeoForge 1.21.1 模组线）

> 1.5.7.2 为 Spigot 独立小版本（Geyser 基岩版金币入账兼容修复，模组线无
> Vault 经济生态无需跟进，仍为 1.5.7.1；两线功能与配置一致）。

### v1.5.7.2 更新日志（Spigot 独立小版本：Geyser 基岩版金币入账兼容）

- **修复：Geyser-Spigot / Floodgate 服务器上，离线基岩玩家的金币入账
  会发进“幽灵账户”** —— Vault 经济按 UUID 记账，基岩玩家的 Floodgate
  UUID（由 XUID 派生）与“按名字派生的离线 UUID”是两个不同账号：
  经公开 API `KERONGPenguinAPI.depositCoins` 给离线基岩玩家发金币时，
  旧版按名字解析目标导致钱发进错误账户（玩家真实余额不变，金币凭空
  消失）。现入账目标改为三级解析：① 在线玩家（含基岩）精确匹配即
  本人；② 离线且已绑定 QQ：用绑定记录里的 playerUuid（基岩玩家即
   Floodgate UUID）；③ 未绑定玩家沿用按名字解析（与旧版一致）。
  Java 玩家三种情况下结果均不变。
- **审计结论（无需改动的部分）**：QQ 绑定（登录拒入发码 / 绑定码确认）、
  /查信息（OpenId → 绑定记录 → playerUuid 全链按 UUID）、查在线、
  在线排行榜、签到（在线直接入账，离线记待领上线补发）对基岩玩家
  均正常 —— 这些链路 1.5.3.1 起即按玩家 UUID 记录，同名不同 UUID =
  两个独立账号，基岩与 Java 玩家同名互不冲突。
- 新增 `QqBindManager.findPlayerUuidByName`（纯内存索引，按名查绑定的
  真实 UUID，严格 8-4-4-4-12 格式校验防误截取）。
- 模组版（NeoForge）无需跟进：模组线无 Vault 经济生态，金币入账路径
  不会触发，维持 1.5.7.1。

### v1.5.7.1 更新日志（配置注释全面核对修正 + 官网教程同步）

- **修正：config.yml 注释与实际行为不符的三处** ——
  `chat-format.start-with` 实为"触发前缀"（只有以该字符开头的游戏聊天
  才转发到 QQ 群，转发时自动去掉前缀；留空全部转发），旧注释写反了；
  `filter-regex` 实为"命中片段直接删除、剩余内容照常转发"（双向生效，
  机器人回复同样过滤），旧注释误写为"整条拦截"；`custom-commands`
  的 `permission` 实为 0/不填 = 所有人可用（/执行）、其他值 = 仅管理员
  （/管理员执行），旧注释的"2 = 所有人"不成立。
- **补全：占位符与功能说明** —— kick-message 补 `{name}` 变量；
  from-game 补 `{name}`、from-group 补 `{nick}/{msg}` 别名；进出服播报
  补 `{player}/{server}/{platform}`；Markdown 模板占位符修正为
  `{online}/{players}/{server}/{img_url}`（{ip}/{port} 仅在 motd.api
  地址内替换）；签到补"未装经济插件时照常签到不发金币"自适应说明；
  AI 补"总开关 + 全局一份记忆"语义与模板缺失自动改图片说明。
- **强调：groups 等列表项填写必须加双引号** —— bot.groups、
  filter-regex、admin.openids 注释与官网教程统一标注双引号写法。
- **清理：移除无功能的 `ai.context-global` 键** —— 旧默认值会写进用户
  配置但无任何代码读取（AI 上下文自 1.5.3 起即为全局一份记忆），
  升级后自动从旧配置清除。
- **同步：Web 面板字段表** —— 移除已不存在的 whitelist/audit 段，
  补 AI 段与渲染缓存字段，修正错误描述。
- 插件版与模组版同步发布，官网教程页同步更新。

### v1.5.7 更新日志（全部图片渲染默认内置背景 + img 空回退 + 深灰主体）

- **新增：查信息 / 查在线 / 在线排行榜全部图片渲染默认使用内置背景图** ——
  每个功能各自配置开关（默认开启，关闭后改用 img/ 随机图）：
  `qq-bind.render.info-card-builtin-background`（查信息卡片）/
  `qq-bind.render.online-list-builtin-background`（查在线图片）/
  `qq-bind.leaderboard.builtin-background`（排行榜，1.5.6 的
  `random-background` 反向更名，手动开启过随机的旧配置自动迁移）。
  内置背景为全新深灰主体 + 深绿装饰底图（查信息 / 查在线共用横版
  `card-default.png`，排行榜竖版 `leaderboard-default.png`，均随 JAR 分发
  零配置即用）。
- **新增：img/ 文件夹没有可用图片时自动回退内置背景图** —— 旧版此时
  退回纯色渐变背景，现保证出图风格统一（对查信息 / 查在线 / 排行榜
  三处渲染同时生效）。
- **调整：内置背景图主体由黑色改为深灰色** —— 与半透明卡片叠加色
  （查信息卡黑 56% / 查在线行卡黑 47% / 排行榜行卡白 13%）保持
  ΔL ≥ 20 的亮度差，方框卡片边界清晰可辨，文字可读性不受影响。
- **配置版本 15 → 16**：新增三个内置背景开关，移除
  `qq-bind.leaderboard.random-background`（旧值 true 自动迁移为
  `builtin-background: false`，其余情况按新默认 true 处理）。
- **门禁与冒烟**：Spigot 门禁 105 项 / NeoForge 门禁 174 项 / 无头渲染
  冒烟 22 项（含 img 空回退 / 双资源缓存隔离）/ NeoForge 真机双场景
  冒烟 18×2 项（裸服 + 模拟整合包 kotlin 模块共存，含配置 13 → 16
  自动升级验证）。插件版与模组版同步发版。


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

当前版本：**1.5.7**（Spigot 线与 NeoForge 1.21.1 模组版同步发版）

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

### v1.5.6.1 更新日志（内置背景视觉调整）

- **调整：在线排行榜内置背景视觉升级** —— 背景改为「中心黑、四周渐入深绿」
  的径向渐变（非上下纵向渐变），绿色装饰整体加强：四角辉光（原 2 角）+
  左右双缘光带（原仅右缘）+ 对角线纹理加密 + 点阵加密 + 同心圆弧装饰 +
  内缩细描边；中央内容区保持近黑，文字可读性自检通过（内容区平均亮度
  21.1 < 40）。插件版与模组版同步发版。

### v1.5.6 更新日志（在线排行榜内置专属背景 + 单图 15 人）

- **调整：在线排行榜默认改用内置专属背景图** —— 黑色主体 + 深绿色装饰的
  专属底图随插件 / 模组 JAR 内置分发（`backgrounds/leaderboard-default.png`），
  零配置零素材即用，不再默认随机取 `img/` 目录图片（中央内容区近黑保证
  文字可读性，深绿辉光装饰在标题区与四角呼应）。想沿用自定义随机图的
  服务器：把 `qq-bind.leaderboard.random-background` 改为 `true`（默认
  `false`），排行榜背景即恢复为随机取插件 / 模组数据目录下 `img/` 文件夹
  图片（与查信息 / 查在线卡片共用素材与缓存，支持 png / jpg / jpeg / bmp /
  gif）。插件版与模组版同步修改。
- **调整：在线排行榜单张图片展示玩家数 20 → 15** —— 每张图片最多 15 行；
  `qq-bind.leaderboard.top` 默认值同步 20 → 15（范围 1-100 不变，设更大
  值自动分段发送多张：第一张带标题区，后续各张只画内容）。从 1.5.5 升级
  时旧默认值 20 自动改为 15（曾手动改成其他值的服务器不受影响）。
- **配置版本 14 → 15**：新增 `qq-bind.leaderboard.random-background`
  （默认 `false`），`qq-bind.leaderboard.top` 默认 20 → 15，旧配置文件
  自动补齐 / 迁移。
- **构建方式（NeoForge 线）**：1.5.6 起模组版改为本地构建环境从源码
  全量编译（gradle 编译 + 完整打包管线，含第三方库重定位），不再使用
  以旧版分发 JAR 为基底的增量字节码补丁路径。

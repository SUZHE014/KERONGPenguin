# KERONGPenguin AI 交接知识库

> **本文档的用途**：把本文件丢给一个对项目一无所知的 AI，它能立刻了解当前情况并接手工作。
> 面向：接手开发/发版的 AI 助手。最后更新：2026-10-15（v1.6.6 发版完成）。
> 自包含版本（发给本地无任何东西的 AI）见官网 `AI-KNOWLEDGE.txt`，含从零开始的环境重建与凭据获取说明。

### 1.1 受限网络环境重建（2026-10-14 实战，环境重置后可用）

本地环境丢失时按此重建（GitHub/Maven 官方源不通，走镜像）：
- 克隆：`git clone https://gh-proxy.com/https://github.com/SUZHE014/KERONGPenguin.git`；
- `common-Bot/libs/deps.jar` 不在 git：从官网下载 Spigot 分发 JAR，剔除 `cn/kerong/**`、`Markdown/`、`backgrounds/`、`fonts/`、`plugin.yml`、`config.yml` 后重建（脚本思路见 `/home/z/my-project/scripts/rebuild_deps_jar.py`）；
- NeoForge libs：`scripts/build_neoforge_libs.py` 的镜像变体（BMCLAPI：`piston-meta/piston-data/libraries.minecraft.net/maven.neoforged.net/maven.minecraftforge.net` → `https://bmclapi2.bangbang93.com[+路径]`，maven 前缀多一级 `/maven`，libraries 前缀 `/libraries`）；ART 重映射耗时 5-10 分钟、内存 2G 够用；
- Gradle 发行版：腾讯镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-8.14.5-bin.zip` 预置到 `~/.gradle/wrapper/dists/gradle-8.14.5-bin/<wrapper 期望哈希>/`（哈希不是 md5，先跑一次 wrapper 看它建的目录名）；
- JDK 21（含 javac 的完整 JDK，系统 JRE 不够）：清华 TUNA Adoptium 镜像 `https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/linux/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz`（华为 openjdk 目录已无包）；`~/.gradle/gradle.properties` 设 `org.gradle.java.installations.paths=/home/z/jdk21` 与 `auto-download=false`（foojay 会走 GitHub releases 被墙）；另需 `settings.gradle.kts` 若解析 foojay 失败可移除该插件（本地 JDK 已就位不需要自动下载）；
- Maven Central/PaperMC/Spigot/插件门户均直连可用（本次实测 2026-10-15）；pip 用阿里云镜像。

---

## 0. 一句话概括

KERONGPenguin 是一个 **Minecraft 服务器 + QQ 官方机器人联动**的项目：玩家在 QQ 群里查数据/签到/聊天，插件在服务器内渲染统计卡片图片并回发。Kotlin 多模块：`common-Bot`（全部业务核心）+ `server-Spigot`（Bukkit 插件版，支持 Spigot/Paper/Folia/混合端）+ `server-NeoForge`（NeoForge 1.21.1 模组版，复用 common-Bot 全部源码 + Spigot 的 manager 源码）。

**版本规则**：插件版与模组版同步发版、同一版本号（如 1.6.6）。当前版本 **1.6.6**（控制台真彩色 / 头像多备用线路与落盘缓存 / PlaceholderAPI 依赖检测 / 内部标识与包结构统一：包名 cn.kerong.bot 与 cn.kerong.penguin，主类 KERONGSpigot / KERONGNeoForge）。

---

## 1. 环境与位置（必读）

| 项 | 值 |
|---|---|
| 本地仓库 | `/home/z/my-project/penguin-git`（GitHub `SUZHE014/KERONGPenguin`，main 分支） |
| 官网服务器 | `156.233.228.248`（宝塔 nginx，站点 kerong.xin） |
| 官网目录 | `/wwwroot/penguin/`（index.html 下载页 / tutorial.html 教程 / style.css / version.json 更新检查文件） |
| 产物目录 | `/wwwroot/penguin/downloads/`（双端 JAR） |
| 源码包目录 | `/wwwroot/penguin-source/`（git archive 打的 zip，与仓库同结构） |
| SSH/SFTP 脚本 | `/home/z/my-project/scripts/ssh_cmd.py`、`sftp.py`（paramiko，凭据在文件内） |
| 打包脚本 | `scripts/package_jar.py`（Spigot）、`scripts/package_jar_neoforge.py`（NeoForge） |
| 门禁脚本 | `scripts/verify_jar_<版本>.py`（每版新写一个）+ `scripts/smoke<版本>/`（功能冒烟，独立 JVM） |
| 工作日志 | `/home/z/my-project/worklog.md`（多 agent 共享，每次工作后追加） |
| Java | 21（gradle 自带 JDK 在 `~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2/`，系统 javac 不在 PATH） |

**打包关键点（历史血泪）**：
- fat JAR 必须保留**目录条目**（目录 entry），否则 SDK 组件扫描 NPE、机器人启动失败（0.1.5.1/0.1.5.4 的坑）；
- 打包脚本以**上一版可分发 JAR 为底座**（依赖字节码来自底座，新编译产物覆盖插件类），运行前需要把上一版 JAR 下载到本地（服务器 `/wwwroot/penguin/downloads/` 有）；
- **NeoForge 打包的底座必须用 Spigot 版 JAR**（不能用 NeoForge 版自己当底座，会把已重定位的依赖再处理一遍导致类丢失，产物体积骤降=坏的）；
- NeoForge 版会做包重定位（kotlin/okhttp3/com.alibaba 等 → `cn.kerong.shaded`）+ 剔除 org/slf4j、com/google/gson（平台自带，split package 崩溃源）+ 剔除未使用库（c3p0/HikariCP/terracotta/webui/schema，1.6.3 起）；
- `server-NeoForge/libs/`（编译期真实构件：mojmap minecraft、NeoForge、FML、bus、brigadier、authlib、deps.jar）不入 git，新环境先跑 `python3 scripts/build_neoforge_libs.py` 重建；
- Gradle 构建：`./gradlew :common-Bot:jar :server-Spigot:jar` 与 `./gradlew :server-NeoForge:jar`。

---

## 2. 功能全景与实现位置

（文件路径均相对 `common-Bot/src/main/kotlin/`，省略前缀；Spigot 版另有 `server-Spigot/src/main/kotlin/`，NeoForge 版在 `server-NeoForge/src/main/kotlin/`）

### 2.1 QQ 侧（群内）

| 功能 | 实现 | 说明 |
|---|---|---|
| 命令分发 | `cn/kerong/bot/events/GroupMessageHandler.kt` | 群消息入口：ReplySeqGuard 基数分配 → 纯数字自动绑定 → 命令分发（首个匹配短路）→ @机器人 AI → 全量转发。命令用 `@Commands("名")` 注解，多触发名写 `@Commands("财富榜","金币榜")` |
| 命令开关/别名 | `spigot/manager/ConfigManager.kt`（COMMAND_ALIASES 映射） | 别名与主命令共用 `commands.<主名>` 开关；改命令名时：注解双名 + 别名映射 + 快捷菜单按钮 + config.yml 键迁移 + help.md + 教程同步 |
| 消息模板 | `spigot/qqbind/MessageTemplates.kt` + 资源 `Markdown/*.md`（68 个） | 全部文本回复走模板：首次启动释放到数据目录 `Markdown/`，用户可改；`messages` 配置段映射模板 id→文件名。发文本用 sendDirect（带@）/sendPlain |
| 绑定/解绑/黑名单 | `spigot/qqbind/QqBindManager.kt`（单例）+ `events/commands/BindCommands.kt` | 玩家进服未绑定发 5 位绑定码（可配置踢出拦截），群里 `/绑定 <码>` 或纯数字 4-12 位自动绑定；`/重新绑定`、`/黑名单`、`/解除黑名单` |
| 签到 | `events/commands/CheckInCommands.kt` | 每日一次（网络北京时间，见 PlayerStatsManager.beijingToday），经 Vault 发金币，无经济插件走记账模式（离线暂存上线补发） |
| 查信息统计卡 | `events/commands/PublicCommands.kt` → `spigot/stats/QueryInfoService.kt` → `spigot/render/CardRenderPool.kt` + `InfoCardRenderer.kt` | CPU 异步渲染 PNG（CardRenderPool 线程池，核数一半 1~4），9 项统计，依赖缺失自适应（Vault 金币 / DeluxeTags **或 PlayerTitle**（1.6.4，PAPI %playerTitle_use%） / PlayerPoints 点券），头像四级链 + **Crafatar CDN 回退与 Mojang 网络全局冷却**（1.6.4，详见 3.5） |
| 在线时长同步 | `spigot/stats/PlayTimeSync.kt`（1.6.4 新增） | 检测到 PlayTimeManager + PlaceholderAPI 时，把本插件统计的总在线时长与 PTM 差值对齐（读 `%PTM_playtime_s_<名>%` 秒数 → 控制台 `/playtime <名> add|remove <差值>s`）；玩家退出时 + 每 30 分钟周期兑底；阈值 300s/冷却 10 分钟防抖；未装则整体不启用 |
| 查在线/在线排行榜/财富榜 | `PublicCommands.kt` + `LeaderboardCommands.kt` + `CoinsLeaderboardCommands.kt` + `render/OnlineListRenderer.kt`/`LeaderboardRenderer.kt` | 图片分段发送（每页 15 人）、命令冷却 `render.command-cooldown-seconds`、内置背景图（backgrounds/，img/ 可自定义随机） |
| /查在线 markdown | `markdown.queryOnline` → online.md | 关图片走模板文本 |
| /motd | `PublicCommands.motd`（无参=本服状态 server-status 模板）→ **带参转发 `MotdCommands`**（minebbs API，motd-* 模板） | 1.6.3 修复了转发缺失导致 MotdCommands 永不可达 |
| AI 对话 | `spigot/qqbind/AiChat.kt` + `QClient.replyMarkdownWithReference` | @机器人触发；引用回复（MessageReference 协议）失败三级回退：引用→普通被动→纯文本；全局一份上下文 |
| 快捷面板 | `cn/kerong/bot/MenuManager.kt` | QQ 官方面板 API，PUT 原地更新防丢面板，按钮 name≤14/desc≤30 单位预校验，40030009 重试 3 次；未装 Vault 隐藏财富榜按钮 |
| 管理命令 | `events/commands/AdministrationCommands.kt`（/执行 /管理员执行 /全量 /发信息 /黑名单…）+ `state/CommandRepositories.kt`（群级持久化） | 管理员判定：群主/群管理员（admin.mode=qq）或 OpenId 名单 |
| 查询 OpenID | `PublicCommands`（/查询open ID[ <OpenId>]）+ `spigot/qqbind/OpenIdDirectory.kt` | OpenId↔QQ 昵称/绑定玩家 反查，懒加载 + 脏标记落盘；**1.6.5：反查结果的 OpenId 行仅管理员可见**（{openidPart} 变量 + CommandSupport.isAdmin 静默判定，旧默认模板经 MessageTemplates.DEFAULT_CONTENT_UPGRADES 自动升级） |

### 2.2 服务器侧

| 功能 | 实现 |
|---|---|
| 聊天双向转发 | `spigot/events/GameChat.kt`（Spigot）/ `neoforge/NeoForgeEvents.kt`（NF）；发送走 `bot/OutboxQueue.kt`（异步单线程 FIFO 防乱序、入队即返回零阻塞） |
| 进出服播报 | 同上（player-events 配置） |
| 统计记录 | `spigot/stats/PlayerStatsManager.kt`：按玩家 UUID 记入 QUUID 主文件 stats 节，懒加载+脏标记、原子写（临时文件替换）、启动零装载 |
| 数据布局 | 数据目录 `QUUID/<QUUID>.yml`（一人一文件：qq/playerName/playerUuid/昵称/签到/待补金币/stats 全在这）+ `QUUID/index.yml`（玩家名[UUID]→QUUID，含 UUID 反查索引）+ skip.yml + blacklist.yml + ai-context.yml + logs/qq/qq-bind-日期.log |
| 自定义指令 | `bot/datapack`（custom-commands 配置） |
| 公开 API | `KERONGPenguinAPI`（第三方插件用，depositCoins 按绑定记录真实 UUID 入账防基岩幽灵账户） |
| 命令执行器 | 自动检测：Bukkit（RCON）/ 混合端（CatServer 等）console 直写（`tools/CommandOutputAppender.kt` 捕获回显） |

### 2.3 平台适配（重要）

- **Spigot 版**：真 Bukkit API，plugin.yml 声明 folia-supported，`spigot/scheduler/FoliaCompat.kt` 运行时检测 Folia 路由三类调度器（全局区域/异步/实体线程），非 Folia 走原 BukkitScheduler。Folia 专属代码隔离在 FoliaDelegate（io.papermc.paper.threadedregions 引用仅存于该类）。
- **NeoForge 版**：`org.bukkit` 兼容层（`server-NeoForge/src/main/kotlin/org/bukkit/...`）提供 JavaPlugin/dataFolder/YamlConfiguration/调度器/BukkitTask 等同名语义；主线程=MinecraftServer#execute；Vault/DeluxeTags/PlayerPoints/PAPI 不存在→相关功能自动降级（卡片隐藏对应项、签到记账模式）。**编译进 NF 的 Spigot 源码只有 `server-Spigot/.../spigot/manager` 目录**（ConfigManager），其余 common-Bot 全量。
- 关服兜底：SptCompat 反射清扫 kloping 非守护线程池 + 5 秒后 halt(0)（Pterodactyl 面板挂进程问题）。

---

## 3. 核心机制与兜底（排查问题先看这里）

### 3.1 日志体系

- **正确路径**：业务代码 → `PluginFileLog.infoAndKeep/warnAndKeep/errorAndKeep` → `plugin.log_info/log_warning/log_error`（平台适配器实现）→ 控制台 + `logs/qq/qq-bind-日期.log` 双写（文件行剥 § 颜色码）。`PluginFileLog.write` 仅文件。
- Spigot 适配器：Bukkit logger；NeoForge 适配器：SLF4J("KERONGPenguin") → 服务器 log4j 标准格式 `[时间] [线程/INFO] [KERONGPenguin/]: 消息`。
- **共享源码（ConfigManager/QqBindManager）用 `plugin.logger`（java.util.logging 接口）**：Spigot 是 PluginLogger 没问题；NeoForge 的兼容层 JavaPlugin 挂了 **JUL→SLF4J 转发 Handler**（1.6.3：`bridgeJulToSlf4j`，useParentHandlers=false，幂等安装），因此也是标准格式。**历史坑**：1.6.3 之前裸 JUL 在 NeoForge 控制台打出 `Oct 13, 2026 … ConfigManager reload / INFO:` 原始格式。
- QqBindManager 构造期（instance 未赋值）日志直接走 `plugin.logger`，不走 logQuiet 的 println 兜底（1.6.3 修复 [STDOUT/] 格式问题）。
- `logQuiet`（控制台+文件）/`logVerbose`（仅文件）：QQ 绑定业务日志。文件超 1GB 轮转。
- 控制台行**不要**再拼时间戳（服务端已按行输出时间，1.6.1 修复重复时间戳）。
- SDK 日志经 `KERONG.initializeRuntime` 的 LogSink 接管（错误→log_error，其余仅文件）。

### 3.2 连接生命周期与重连（1.6.2 体系，1.6.3 增强）

启动链：`KERONG.initializeRuntime` → `QClient.launchClient`（Starter(kloping SDK)，anyCloseReconnect=true，挂 `WssDiagnostics` 钩子）→ 凭据自检 `QqTokenHealth.check`（异步，结果存 `lastCheckOk`）。

- **WssDiagnostics**（`bot/WssDiagnostics.kt`）：纯观察者钩子（**必须返回 true**，返回 false 会掐断 SDK 协议链——1.5.3 的 1006 死循环教训）。职责：协议帧落盘（Hello/READY/RESUMED/Identify/关闭/错误堆栈，业务帧仅计数）、心跳自愈（代发+假死检测）、鉴权 60 秒超时主动重连（90 秒限频）、看门狗（10 秒，MIN_PRIORITY）。
- **ReconnectManager**（`bot/ReconnectManager.kt`）：重连编排。每次连接打开=1 次尝试 →「第 N 次（1/5）重试连接…」；连续 5 次失败→「重连超时」挂起（anyCloseReconnect=false+断开 socket），30 分钟后自动下一轮（新一轮清零计数），最多 3 轮，之后只能 /kerong chs。过程提示 `bot.reconnect-notify: false` 可关，**失败后的恢复指引不可关**。**1.6.5 成功提示双路**：鉴权完成（READY/RESUMED）即宣告「重连成功（第 N/5 次尝试）」——旧版要等存活满 90 秒，会话在 90 秒内被再次踢线就永远等不到（「部分重连成功没有提示」的根因）；互踢抖动（上一条会话短命）时保持沉默等 90 秒健康判定兑底，防成功/失败提示来回刷屏；首次连接（无关闭历史）不宣告。健康（≥90 秒）时仍清零全部计数。
- **/kerong chs**（短别名 /hb）：手动重连（重置全部计数→QClient.resumeConnection；socket 不可达时回退完整重启）。Spigot：KERONGCommand+plugin.yml（命令名 kerong，aliases [hb]，权限 kerong.command）；NeoForge：NeoForgeCommands 双 literal（kerong/hb）。
- 看门狗/兜底重连在挂起期间凭 `ReconnectManager.isSuspended()` 停连。

### 3.3 双服务器同绑一个机器人（1.6.3 检测，1.6.4 处置重做，务必理解）

QQ 同一 AppID 的第二条连接**不总是被立刻踢**。三种实测形态：
1. **互踢**（轮流掉线）：短命会话（READY 后 <90 秒被关）连续 3 次/10 分钟 → WssDiagnostics 判定 → 醒目提示后继续重试体系（不停机）。
2. **鉴权期被拒**（第二台 Identify/Resume 被关，readyAt=0）：凭据自检 lastCheckOk==true 时计入互踢证据（凭据有效却连不上=网关拒本连接）→ 同上判定。
3. **双连接共存**（1.6.3 新发现）：两台同时在线同时处理同一条消息。被动回复去重键=(msg_id, msg_seq)，两台序号都从 1 起→后发的被 40054005「消息被去重」拒收；图片渲染慢必输——**这是 1.6.0/1.6.1「回复图片消息失败」的真正根因**（当时误判为网关重投递）。

**消息级实锤检测 ConflictWatch**（`bot/ConflictWatch.kt`）：ReplySeqGuard 保证本进程序号不重复→被 40054005 拒收的唯一解释=另一个客户端抢先回复同一条消息。10 分钟内 3 次→判定成立→`ReconnectManager.onDedupConflictConfirmed`：醒目输出一次原因+处理方法（关闭其一或换机器人）。**1.6.4 处置（参考上游开源主客户端「连接层永不放弃重试」的做法）：不再断开连接、不再停止自动重连**，连接与统一重试体系（每轮 5 次×3 轮 + /hb chs）照常运转；管理员处理后连接自然稳定。钩子挂点：QClient 四个回复方法的 catch+ret 分支、MessageTemplates、GroupMsgSender。WssDiagnostics 的互踢证据（短命会话/鉴权期被关）1.6.4 起也改为**仅文件短记录**（不再每次输出嘆人的控制台 ERROR，判定成立才醒目提示一次）。

**ReplySeqGuard（1.6.2，保留）**：GroupMessageHandler 入口按 msg_id 分配回复序号基数（第 1 次=1，第 N 次重投递=1+(N-1)×100000），防网关弱网/会话恢复重投递到**本服**时的序号重复；同时使 40054005 成为双服冲突的可靠信号。

### 3.4 更新检查（1.6.2，1.6.4 彩色）

`bot/UpdateChecker.kt`：启动后异步 8 秒 GET `https://kerong.xin/penguin/version.json`，逐段数字比较版本；有新版→控制台输出带 § 颜色码的版本号/下载页/最多 6 行简要日志（1.6.4：版本号绿色加粗、链接蓝色、条目灰白分级）；失败→控制台一句话，细节进日志。`update-check.enabled` 可关。**每次发版必须更新服务器上的 version.json**。§ 颜色码处理：Spigot 系控制台直接渲染；NeoForge 适配器 `log_info/log_warning/log_error` 输出前统一过 `PluginFileLog.stripColorCodes`（log4j 不渲染 §，否则乱码）；文件日志始终剥离。共享代码可以放心在控制台文案里用 § 颜色码。

### 3.5 渲染管线与头像获取（1.6.4 头像修复，1.6.5 内存与性能）

CPU 渲染（CardRenderPool 共享线程池，核数一半 1~4 线程）、结果缓存、头像 LRU+字节预算、背景图内置+随机 LRU、长图封顶 1600px、子采样解码防 OOM。图片发送：`replyWithImgBytes`（base64 上传），读超时（SDK OkHttp 10 秒 < QQ 富媒体普遍 10 秒+应答）**不算失败**（图片大概率已投递，1.6.1）；ret 2022021x=已受理算成功。**1.6.5 内存策略**：移除渲染后 System.gc() 提示（Full GC 停顿伤 tick，render.gc-after-render 配置已废除并由升级器清理），改为确定性 flush——单次画布（各 render() 末尾）、中间图（皮肤贴图/缩放/cover）、缓存淘汰与 clearCaches 均在失去引用前 flush，剩余堆内存交给常规新生代 GC。

**头像链路**（`InfoCardRenderer.kt` 的 InfoCardAssets）：内存缓存（10 分钟）→ **磁盘缓存**（1.6.6 新增，plugins/KERONGPenguin/cache/avatars/，24 小时，512 文件上限，命中回填内存，重启后免重复请求）→ 在线 Profile → playerdata NBT → Mojang 会话服务器（v4）→ 名字反查 →（均失败时）**外部多线路回退**（1.6.6：MC-Heads → Minotar → Crafatar 按实测速度排序；基岩玩家（Floodgate UUID：msb 全 0 + lsb 高 16 位 0x0009，低 48 位为 xuid）走 MC-Heads xuid 线路 `https://mc-heads.net/avatar/xuid:<xuid>/216`；名字线路 MC-Heads → Minotar；生产实测 2026-10：MC-Heads 0.4s / Minotar 0.5s / Crafatar 5.8s，LittleSkin 与 mc-api.io 不可达弃用）。**1.6.4 关键修复：Mojang 官方接口网络级失败（超时/IO/429/5xx）开启 5 分钟全局冷却**——「大部分玩家头像获取失败」的根因是服务器到 Mojang 的线路不稳，旧版每个玩家都先等 5 秒超时才能失败，在线列表 8 秒预热预算全部耗尽；冷却期内直接跳过官方链路（走 Crafatar 或诚实失败），不再逐玩家等超时。会话服务器超时也从 5 秒降到 3 秒。离线服无皮肤数据的玩家仍诚实画占位块（不做「人人史蒂夫」假回退）。

### 3.6 配置系统

`ConfigManager.kt`（共享源码）：DEFAULT_VALUES 全量默认 + `ConfigUpgrader.fillMissing` 补齐缺失键 + `CONFIG_VERSION_PATH` 版本迁移链（**当前 config-version 19**：16→17 render/leaderboard 顶层化，17→18 金币榜→财富榜，18→19 from-game 默认带玩家名 + gc-after-render 废除清理）。修改配置一律重启生效（无热重载指令，教程与注释已如此说明）。

### 3.7 QQ 绑定拦截策略（1.6.4 双端统一）

未绑定玩家分两段拦截（开启 qq-bind.enabled 后）：
- **老玩家**（服务器已有档案：Spigot 用 `hasPlayedBefore()` + `playerdata/<uuid>.dat` 双判定；NeoForge 用 `playerdata/<uuid>.dat` 文件判定）→ **进入游戏之前**拦截：Spigot 在 `PlayerLoginEvent.disallow`；NeoForge 在**配置阶段**断开（`BindLoginGate.kt`：mod 总线 RegisterConfigurationTasksEvent 注册自定义 ConfigurationTask，任务 start() 时 listener.disconnect(绑定提示 Component)）。显示路径等价原版白名单/封禁——绑定码直接显示在“无法连接到服务器”界面，不会被加入流程/“服务器连接失败”覆盖。
- **新玩家**（没档案）→ 放行完成加入（服务器建档 UUID/玩家数据），随后踢出展示绑定码：Spigot `GameChat.enforceJoinBinding`（PlayerJoinEvent 内直接 kickPlayer）；NeoForge `NeoForgeEvents.enforceLoginBinding`（加入后 1 tick disconnect）。
- **兜底链**：NeoForge 闸门任何环节异常（mod 总线挂载失败/反射失败/管理器未就绪）一律放行，由加入后踢出兜底；Spigot 登录期未拦到（如登录时管理器未就绪）也由加入后踢出兜底。
- **NeoForge 关键事实**：1.21.1 无登录期玩家事件；PlayerLoggedInEvent 在 placeNewPlayer 末尾（全太晚）；ConfigurationTask.start(Consumer<Packet<?>>) 的参数是**发包器**，任务完成靠监听器响应包驱动（finishCurrentTask）——所以闸门采用“注册时判定、仅拦截时注册任务、任务 start 直接断开”的模式（放行时零干扰，拦截时无需完成任务）。

---

## 4. 官网（kerong.xin/penguin/）

| 文件 | 内容 |
|---|---|
| index.html | 下载页：主下载框（当前版本双按钮）+ 历史版本表 + 更新日志 |
| tutorial.html | 教程（小白向）+ 全部配置说明 |
| version.json | 更新检查数据源：`{"version","url","changelog":[...]}` |
| downloads/ | 双端 JAR（保留约 8 个历史版本） |

### 书写要求（用户明确要求过的风格）

- **更新日志要简化**：每个版本几条要点、每条 1-3 行，不占满屏幕；超过 3 个版本后旧的合并成「更早版本一览」一行一版（README 同样只保留最近三版的详细日志）。
- **教程是小白向**：步骤化（创建机器人→填配置→拉群），命令用代码块，关键处加粗；技术细节放各配置段内。
- **安装步骤 1 卡片**：手机 QQ 创建机器人（「在手机上安装 QQ 最新版本」），卡片末尾有「电脑版高级创建：q.qq.com」链接（1.6.3 加）。
- **按钮位置**：主下载按钮在版本号下方（Spigot 在前 NeoForge 在后，max-width 440px 居中），按钮文案「⬇ 下载 vX.Y.Z（Spigot 插件版 / NeoForge 模组版）」，href 指向 downloads/ 内文件名。
- 修改教程/下载页后**双端同步**：插件版与模组版行为差异只在数据目录位置（plugins/KERONGPenguin/ vs kerongpenguin/）与 Vault 生态。

---

## 5. 发版完整清单（做完所有工作后要进行的操作）

1. **版本号五处**：根 `build.gradle.kts`、`server-Spigot/src/main/resources/plugin.yml`、`server-NeoForge/build.gradle.kts`、`server-NeoForge/src/main/resources/META-INF/neoforge.mods.toml`、`KERONGNeoForge.kt` 的 `MOD_VERSION`（+README 当前版本行）。
2. **构建**：`./gradlew :common-Bot:jar :server-Spigot:jar` → `python3 scripts/package_jar.py <上一版Spigot JAR路径>`；`./gradlew :server-NeoForge:jar` → `python3 scripts/package_jar_neoforge.py <本版Spigot JAR路径>`（**NF 底座用 Spigot 版**）。
3. **门禁+冒烟**：新写 `scripts/verify_jar_<版>.py`（断言本轮改动点+基线）跑过；新写 `scripts/smoke<版>/Test<版>.java`（javac/java 用 `~/.gradle/jdks/eclipse_adoptium-21-amd64-linux.2/bin/`，类路径=NF 分发 JAR+common-Bot/libs/deps.jar）跑过。历史门禁路径写死版本号，旧版 JAR 不在本地属正常。
4. **README**：新增本版更新日志段，删到只剩最近三版。
5. **git**：commit（信息格式：`<版本> 插件与模组同步发版: <改动点>+<改动点>...`，历史 commit 均为长信息风格）→ push origin main。
6. **源码包**：`git archive`（与旧源码包同结构：`git archive --format=zip -o /tmp/KERONGPenguin_v<版>_source.zip HEAD` 之类，参考上次产物命名 `KERONGPenguin_v1.5.3.1_source.zip`…实际按服务器现存文件名风格 `KERONGPenguin_v<版>_source.zip`）→ 上传 `/wwwroot/penguin-source/`，删旧包只留最新 2-3 个。
7. **上传服务器**（sftp）：双 JAR → `/wwwroot/penguin/downloads/`；源码包 → `/wwwroot/penguin-source/`；权限 `www:www`（`chown www:www`）。
8. **官网**（本地改好再整文件上传覆盖）：
   - `version.json`：新版本号 + 简短 changelog 数组（**更新检查的服务器文件，忘改则用户收不到提示**）；
   - `index.html`：主下载框版本/日期/两个按钮/大小 → 历史版本表加两行（新版本置顶）→ 更新日志加本版段落（简化风格）；
   - `tutorial.html`：仅当功能/行为/命令有变化时同步（教程与插件注释保持一致的原则）。
9. **在线验证**：curl 官网三文件确认新内容；curl 两个 JAR URL 确认 200 与字节数；git 远端最新 commit 确认。
10. **worklog**：`/home/z/my-project/worklog.md` 追加本次工作记录（模板见文件头部既有格式）。
11. **本知识库**：如有架构级变化（新机制/新文件/新坑），同步更新本文档并重新上传服务器。

---

## 6. 历史坑速查（避免重蹈覆辙）

| 坑 | 说明 |
|---|---|
| SDK 钩子返回 false | 会拦截 SDK 自身处理（鉴权/心跳/重连全断）→ 1006 踢线死循环。所有 WssDiagnostics 回调必须 return true |
| fat JAR 无目录条目 | SDK 组件装配 NPE，机器人启动失败。打包脚本有 dir_count==0 熔断 |
| NeoForge 底座用错 | NF 打包底座必须是 Spigot 版 JAR；产物体积骤降=类丢失 |
| NF 平台包冲突 | org/slf4j、com/google/gson 必须剔除（split package ResolutionException）；其余依赖必须重定位 |
| JUL 裸用（NeoForge） | 共享源码 plugin.logger 必须经 JUL→SLF4J 桥（JavaPlugin.kt 已挂）；新共享代码直接用 BotShared.instance.log_* 更稳 |
| 控制台双时间戳 | 服务端已按行输出时间，插件别再拼 [yyyy-MM-dd HH:mm:ss] |
| 40054005 ≠ 单一原因 | 单服=网关重投递（ReplySeqGuard 已防）；双服=另一台抢先回复（ConflictWatch 实锤）。修图片发送问题先确认是不是双服务器同绑 |
| 图片读超时≠失败 | QQ 富媒体应答普遍 10 秒+ > SDK OkHttp readTimeout 10 秒，读超时按 WARNING 不提示失败 |
| Mojang 429 限流/网络不可达 | 名字反查限流/网络异常不写负缓存（1.6.2 修）；1.6.4 网络级失败另开 5 分钟全局冷却（避免逐玩家等超时）；404 才写且 30 分钟有效 |
| 停机 zip closed | Paper/Purpur 停机阶段会提前关插件 jar 的 ZipFile，onDisable 首次类加载报错；onEnable 预热 CommandOutputAppender.getInstance()（1.6.4 修），onDisable 每步独立 try-catch |
| 指令重载配置 | 已移除该概念：改配置一律重启（配置头注释+教程+文档三处一致） |
| Kloping 线程池 | 关服后 50+ 非守护线程不退出 → SptCompat 清扫 + 5 秒 halt(0) 兜底 |
| 死代码教训 | 1.6.3 审计删除 21 项零引用声明+剔除 1.3MB 未用库；新死代码判定要查**三个模块+脚本+资源+反射**（@Commands/@SubscribeEvent/@EventReceiver 反射入口不算死） |

---

## 7. 当前已知状态（2026-10-15，v1.6.6 发版完成）

- 双端 1.6.6 全部完成（构建 / 门禁 / 冒烟通过）：
  - 控制台真彩色：ConsoleColors（§→ANSI 转义 + TerminalConsoleAppender 能力探测），双端 log_* 输出前转换（不支持 ANSI 的输出自动退化纯文本，文件日志始终剥离）；
  - 头像：外部多线路回退（MC-Heads/Minotar/Crafatar + Floodgate xuid 基岩线路）+ 磁盘缓存（cache/avatars，24h）；
  - PapiDependencyCheck：无 PlaceholderAPI 时启动后 12 秒与查询时各提示一次（金币 Vault 直连与 DeluxeTags 称号不受影响的说明写进提示）；
  - 内部标识与包结构统一：包结构统一为 cn.kerong.*（bot / penguin），主类 KERONGSpigot / KERONGNeoForge / KERONG 接口，旧长名命令别名与旧协议前缀兼容层已删除；
  - 1.6.5 历史项：启动消息「KERONG Penguin 已加载」；
  - 品牌统一：主命令 /kerong（短别名 /hb）；内部协议 kerong run|adminrun；审核环境变量 KERONG_AUDIT_*；状态文件头/BotShared 错误文案/mods.toml 描述等全部 KERONG 化；
  - 重连成功提示修复（READY 即宣告 + 互踢防抖 + 退避新一轮清零计数）；
  - 渲染：移除 System.gc()（Full GC 停顿伤 tick），全链路确定性 flush；render.gc-after-render 配置废除（升级器自动清理）；
  - OpenId 反查管理员可见（含旧默认模板自动升级机制 DEFAULT_CONTENT_UPGRADES）；
  - from-game 默认 [游戏] <{name}> {message}（config-version 19 迁移）；
  - 配置注释精简（删更新检查结果说明/版本分隔条/留空[]说明）。
- 1.6.4 及更早历史详见 git log 与官网更新日志。
- 待观察：重连成功提示在真实网关踢线场景的节奏（每次踢线都会有一条「第 1/5 重试 + 重连成功」，属预期行为）；OpenId 反查在 admin.mode=manual 下的判定体验。
- 官网历史下载：1.6.4 起只保留 1.6.0+ 版本（更旧已按用户要求删除）。

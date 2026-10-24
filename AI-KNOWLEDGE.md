# KERONGPenguin AI 交接知识库

> **本文档的用途**：把本文件丢给一个对项目一无所知的 AI，它能立刻了解当前情况并接手工作。
> 面向：接手开发/发版的 AI 助手。最后更新：2026-10-24（v1.6.7.8：移除 /查性能「延迟（玩家平均）」指标及其整条采集链路——玩家 Ping 只反映「客户端到服务器」连接往返，本地/同机/内网穿透部署下恒为 0ms，永远只代表服务器本地网络无实际监控意义（用户反馈）；卡片改五项 TPS/MSPT/内存/CPU/在线人数，末行单卡通栏高 1034 不变；快照 avgPingMillis 字段 / 双端 getPing·latency 平均 / spark playerPing 轮询全部下线；1.6.7.7 曾做的「延迟 0 合法/暂不可读区分」随指标整体作废）。
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

**版本规则**：插件版与模组版同步发版、同一版本号（如 1.6.7.5）。当前版本 **1.6.7.5**（**SparkBridge 反射链修复**：spark 的 SparkImpl 与各统计实现类全部包私有，旧版对 obj.javaClass 取 Method 反射调用抛 IllegalAccessException 被逐项容错吞掉 → TPS/MSPT/CPU 全 null（启动自检报「检测到 spark 但接入失败（各指标均暂不可读）」、/查性能 数据从未走过 spark——1.6.7.2~1.6.7.4 均受影响，手写假 spark 冒烟恰好是 public 类所以没暴露）；现方法一律从公共 API 接口解析（Spark/DoubleStatistic/GenericStatistic/DoubleAverageInfo 的 Class.forName + getMethod + isAssignableFrom 擦除匹配 poll(Enum)），已对真实 spark 1.10.187 字节码逐项核实（含 Statistic#name()/getWindows()、SparkProvider.get() 未加载抛 IllegalStateException）并以「真实形态假 spark + 包私有实现 + 独立 URLClassLoader」端到端冒烟；启动自检失败先 15 秒静默复测再定性；**AI 聊天艾特判定修复**：QQ 群消息 content 已被平台去除 @机器人前缀、mentions 不含机器人自身（官方文档明确），旧「content 含 <@」门槛对群消息永远不成立 → @机器人 从未被判定为 AI 对话全部落转发；现 WssDiagnostics 帧层捕获 GROUP_AT_MESSAGE_CREATE 的 d.id 记入 GroupAtTracker（帧钩子先于 SDK 分发执行，重连安全），群消息入口三通道判定（帧标记/mentions bot=true/<@文本）；附带 RecentMessageGate msg_id 级去重（全量模式同一条 @ 消息双事件先后到达 + QQ 弱网重投递——同一条消息只处理一次）与 QqText.stripLeadingMentionPlaceholders 行首占位剥离（全量模式 content 原样保留 <@openid> 前缀，@机器人 /命令 此匹配不到命令）；AI 未开启时 @ 消息不再被吞落全量转发；**查性能卡面板透明度与其他卡片统一（同日覆盖版）**：头部卡白 34 / 容器黑 120 / 指标卡白 16 / 胶囊白 30，与查信息/查在线/排行榜逐一相同，无面板描边，整卡透明度由全局 render.global-opacity 统一控制（首次发布曾改实色深板，因与其他卡片观感不一致当日覆盖）；）/ 1.6.7.4（**spark 接入提示重做**：时机改到服务器启动成功之后（Spigot 端 ServerLoadEvent 后 5 秒、NF 端 ServerStarted 后 5 秒，探测改三态 ProbeOutcome（未安装/已接入/已装但失败）：未安装完全静默；接入成功且 commands.查性能 开启才控制台正向确认（查询关闭仅文件留档）；已装 spark 但接入失败（未启用/指标读不到/异常）无论开关 warnAndKeep 醒目提示并说明回退链；ServerLoadEvent 注册失败退回 onEnable 后 12 秒旧节奏兜底；**日志单文件 512KB 上限**：PluginFileLog.ROTATE_THRESHOLD_BYTES=512KB，resolveRotated 共享轮转实现（QqBindManager.getLogFile 与 PluginFileLog.currentFile 两写入入口同一规则，超限顺次 -N 文件，原 1GB）；**控制台降噪三条**：群消息监听已注册/SDK 日志将写入文件（QClient infoAndKeep→write 仅文件）、/绑定 命令已注册（双端 logQuiet→logFileOnly 新方法，文件格式与 logQuiet 文件段完全一致）——日志文件内容不变；）/ 1.6.7.3（**配置重建回填格式修复**：YamlTextMerge.renderEntry 对列表与字符串统一渲染行内双引号——空列表 `[]`、非空列表 `["A", "B"]`（字符串双引号转义/数字布尔原样）、字符串值统一双引号，与 config.yml 教程示例完全同风，修复 1.6.7.2 重建把用户手写 `groups: ["xxx"]` 改写成块状裸串「看着像丢值」的问题，config-version 21→22 重启自动重刷格式；**PapiDependencyCheck 收窄**：仅当检测到 PlayerTitle（papiConsumerPlugins 清单）才检测提示，就绪档 infoAndKeep 正向确认、未装相关插件完全静默；**SparkStartupCheck 新增**：启动过程中（Spigot onEnable 后 12 秒 / NF ServerStarted 后 5 秒异步）检测到 spark 且读取成功时控制台输出「已接入 spark」；）/ 1.6.7.2 同日覆盖版（/查性能 检测到 Spark（插件/模组）时优先经 SparkBridge 纯反射调用其公开 API 取 TPS/MSPT/CPU，单项读不到回退自研链；性能数据缓存 render.performance.cache-seconds 覆盖版默认 60 秒；**全局透明度 render.global-opacity（默认 1.0）**：GlobalOpacity.apply 在四个渲染器 PNG 编码前统一把整图 alpha 乘系数（1.0 原样零开销，0.5 半透明）；**覆盖版移除液态玻璃**（render.performance.style 入 REMOVED_CONFIG_KEYS 自动清理，卡片固定原版毛玻璃深色）；**配置版本升级重建**：磁盘 config-version 低于 JAR 内置时以新版 config.yml 全文为底 ConfigFileRebuilder 文本级合并（YamlTextMerge 逐行替换/插入保注释，自定义值回填、旧默认自动升级、备份 config.yml.bak、先校验后落盘；**覆盖版修复单元素列表产出非法 YAML**——renderEntry 按类型分派列表为块状项行，loadYaml 改 throws 语义可感知失败）；from-group 新默认 [§bQQ§r]、idle-release-minutes 默认 5→15；config-version 20→21）/ 1.6.7.1：修复 /查性能 TPS/MSPT 一直读不到（PerformanceSnapshot 分层反射读取器；固定四项，删 show-*）/ 1.6.7：新增 /查性能 卡片与 /查在线 默认图片输出 / 1.6.6.4：移除空闲 GC 提示 / 1.6.6.3：重连降噪 / 1.6.6.2：缓存分层+空闲释放+单飞+/kerong mem / 1.6.6.1 覆盖版：头像本地优先、删 WebUI / 1.6.6：控制台真彩色、头像多线路，包名 cn.kerong.bot 与 cn.kerong.penguin，主类 KERONGSpigot / KERONGNeoForge）。

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
| Java | 21（本环境装在 `/home/z/jdk21`（TUNA Adoptium 镜像），含 javac；系统 apt 的 openjdk-21 是 JRE-only 无 javac；`~/.gradle/gradle.properties` 指向它） |

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
| 命令分发 | `cn/kerong/bot/events/GroupMessageHandler.kt` | 群消息入口：ReplySeqGuard 基数分配 → **1.6.7.5：RecentMessageGate msg_id 去重（双事件/重投递）+ QqText.stripLeadingMentionPlaceholders 行首占位剥离** → 纯数字自动绑定 → 命令分发（首个匹配短路）→ @机器人 AI（**1.6.7.5 三通道判定：GroupAtTracker 帧标记/mentions bot=true/<@文本；AI 未开启落转发**）→ 全量转发。命令用 `@Commands("名")` 注解，多触发名写 `@Commands("财富榜","金币榜")` |
| 命令开关/别名 | `spigot/manager/ConfigManager.kt`（COMMAND_ALIASES 映射） | 别名与主命令共用 `commands.<主名>` 开关；改命令名时：注解双名 + 别名映射 + 快捷菜单按钮 + config.yml 键迁移 + help.md + 教程同步 |
| 消息模板 | `spigot/qqbind/MessageTemplates.kt` + 资源 `Markdown/*.md`（68 个） | 全部文本回复走模板：首次启动释放到数据目录 `Markdown/`，用户可改；`messages` 配置段映射模板 id→文件名。发文本用 sendDirect（带@）/sendPlain |
| 绑定/解绑/黑名单 | `spigot/qqbind/QqBindManager.kt`（单例）+ `events/commands/BindCommands.kt` | 玩家进服未绑定发 5 位绑定码（可配置踢出拦截），群里 `/绑定 <码>` 或纯数字 4-12 位自动绑定；`/重新绑定`、`/黑名单`、`/解除黑名单` |
| 签到 | `events/commands/CheckInCommands.kt` | 每日一次（网络北京时间，见 PlayerStatsManager.beijingToday），经 Vault 发金币，无经济插件走记账模式（离线暂存上线补发） |
| 查信息统计卡 | `events/commands/PublicCommands.kt` → `spigot/stats/QueryInfoService.kt` → `spigot/render/CardRenderPool.kt` + `InfoCardRenderer.kt` | CPU 异步渲染 PNG（CardRenderPool 线程池，核数一半 1~4），9 项统计，依赖缺失自适应（Vault 金币 / DeluxeTags **或 PlayerTitle**（1.6.4，PAPI %playerTitle_use%） / PlayerPoints 点券），头像多级链 + **外部多线路回退（含 MinePic）与 Mojang 网络全局冷却**（1.6.4~1.6.6.1，详见 3.5） |
| ~~在线时长同步~~ | 已于 1.6.6.1 覆盖版**删除**（1.6.4 曾引入 `PlayTimeSync.kt`：检测到 PlayTimeManager + PAPI 时差值对齐，因用户要求移除；本插件自身在线时长统计不受影响，PapiDependencyCheck 提示面同步收窄为仅 PlayerTitle） |
| 查在线/在线排行榜/财富榜 | `PublicCommands.kt` + `LeaderboardCommands.kt` + `CoinsLeaderboardCommands.kt` + `render/OnlineListRenderer.kt`/`LeaderboardRenderer.kt` | 图片分段发送（每页 15 人）、命令冷却 `render.command-cooldown-seconds`、内置背景图（backgrounds/，img/ 可自定义随机） |
| /查性能（1.6.7） | `PublicCommands.kt` → `spigot/stats/PerformanceService.kt` + `render/PerformanceRenderer.kt` | 性能监控卡片（**1.6.7.8 起固定五项 TPS/MSPT/内存/CPU/在线人数（仅人数），末行单卡整行通栏，高 1034 不变，null 显示「--」；1.6.7.6 曾有的「延迟」项 1.6.7.8 整体移除——玩家 Ping 只反映客户端到服务器连接往返，本地/同机恒 0ms 只代表服务器本地网络，无实际监控意义；快照无 avgPingMillis 字段、capture 四参工厂**，**1.6.7.5 覆盖版：半透明面板与其他卡片同款 alpha（头部卡白 34/容器黑 120/指标卡白 16/胶囊白 30，无描边）**）；快照走 `KERONG.performanceSnapshot()`（**1.6.7.2 引入 / 1.6.7.5 修复反射链：装了 spark 时优先 SparkBridge 反射调其公开 API，方法一律从公共接口解析（实现类包私有，从实现类取 Method invoke 抛 IllegalAccessException 全指标被吞——1.6.7.5 前的暗坑）**——SparkProvider.get() 单例→tps().poll(TicksPerSecond.MINUTES_1)/mspt().poll(MillisPerTick.SECONDS_10).mean()/cpuProcess().poll(CpuUsage.MINUTES_1)，**1.6.7.8 起不再轮询 playerPing**，Spigot 端 getPlugin("spark") 启用才读、NF 端 ModList.isLoaded；无 spark 单项回退自研分层反射链，capture 三参重载接 cpuOverride；**1.6.7.3 新增 / 1.6.7.4 重做：启动接入自检提示 SparkStartupCheck**（挂载时机改服务器启动成功后——Spigot ServerLoadEvent、NF ServerStarted，probe lambda 返回三态 ProbeOutcome：未安装静默 / 已接入且 commands.查性能 开启才 infoAndKeep 正向确认（查询关闭仅文件留档）/ 已装但失败 warnAndKeep 必提示带原因与回退说明））；性能数据缓存 render.performance.cache-seconds（覆盖版默认 60 秒，PerformanceService peekCache/storeCache）；命令开关 commands.查性能，背景 render.performance.builtin-background；**覆盖版：液态玻璃样式已移除**（render.performance.style 入废弃键表自动清理），全局透明度 render.global-opacity（GlobalOpacity.apply 四渲染器统一接入） |
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
- SDK 日志经 `KERONG.initializeRuntime` 的 LogSink 接管（错误→log_error，其余仅文件；**1.6.6.3：SDK 重连路径三条固定噪声（暂未处理的异常code / websocket closed with code 1011… / reconnect in 3 seconds）也改仅文件**——kloping SDK 在 delayIdentifyConnect 与未归类关闭码时按 error 级别输出，重连风暴期间逐次刷屏且语义误导，见 `KERONG.isSdkReconnectNoise`）。

### 3.2 连接生命周期与重连（1.6.2 体系，1.6.3 增强，1.6.6.3 风暴降噪）

启动链：`KERONG.initializeRuntime` → `QClient.launchClient`（Starter(kloping SDK)，anyCloseReconnect=true，挂 `WssDiagnostics` 钩子）→ 凭据自检 `QqTokenHealth.check`（异步，结果文本落盘）。

- **WssDiagnostics**（`bot/WssDiagnostics.kt`）：纯观察者钩子（**必须返回 true**，返回 false 会掐断 SDK 协议链——1.5.3 的 1006 死循环教训）。职责：协议帧落盘（Hello/READY/RESUMED/Identify/关闭/错误堆栈，业务帧仅计数）、心跳自愈（代发+假死检测）、鉴权 60 秒超时主动重连（90 秒限频）、看门狗（10 秒，MIN_PRIORITY）。**1.6.6.3 防刷屏**：关闭帧 / op9 会话无效 / op7 要求重连改 `PluginFileLog.write` 仅文件（旧版每次 WARN 上控制台，重连风暴一轮 8~10 条）；看门狗主动重连与心跳兜底仍控制台 WARN（有排查价值且 90 秒限频）。
- **ReconnectManager**（`bot/ReconnectManager.kt`）：重连编排。每次连接打开=1 次尝试；**1.6.6.3 控制台只报里程碑**：本轮第 1 次（受 bot.reconnect-notify 控制）、最后一次、重连成功、重连超时（不可关），中间次数仅文件；连续 5 次失败→「重连超时」挂起（anyCloseReconnect=false+断开 socket），30 分钟后自动下一轮（新一轮清零计数），最多 3 轮，之后只能 /kerong chs。**1.6.6.3 计数越界修复**：挂起后 SDK 已排队的延迟重连（delayIdentifyConnect 3 秒后触发，无法从外部取消）仍会建连——onOpen 检测 attempt≥上限时按新一轮重置计数（旧版显示「第 6/5 次」越界）。**1.6.5 成功提示双路**：鉴权完成（READY/RESUMED）即宣告「重连成功（第 N/5 次尝试）」；短命会话后的 READY 保持沉默等 90 秒健康判定兑底；首次连接（无关闭历史）不宣告。健康（≥90 秒）时仍清零全部计数。
- **/kerong chs**：手动重连（重置全部计数→QClient.resumeConnection；socket 不可达时回退完整重启）。Spigot：KERONGCommand+plugin.yml（命令名 kerong，aliases [hb]，权限 kerong.command）；NeoForge：NeoForgeCommands 双 literal（kerong/hb）。
- 看门狗/兜底重连在挂起期间凭 `ReconnectManager.isSuspended()` 停连。

### 3.3 双服务器同绑一个机器人（1.6.1~1.6.6.2 检测，**1.6.6.3 整体移除**，务必理解）

**1.6.6.3 决策：双服同绑检测已全部移除（ConflictWatch 类删除、WssDiagnostics 证据计数删除、QClient.stopForConflict 删除、回复路径接入点删除）**。原因：平台网关抖动（会话无效 op 9 / create session error 4903 / 网关定期踢线 1000）引起的重连风暴会触发「短命会话连续 3 次」判定，向管理员误报「已有另一台服务器绑定了同一个机器人」——实测单服务器连接时也必然误报，误导排查方向。移除后重连风暴按本来面目（网络 / 平台问题）记录与重试。

历史背景（理解 40054005 修复链路仍有价值）：QQ 同一 AppID 的第二条连接**不总是被立刻踢**，两台同时在线时被动回复去重键=(msg_id, msg_seq) 双方都从 1 起→后发的被 40054005 拒收（1.6.0/1.6.1「回复图片消息失败」的根因之一）。

**ReplySeqGuard（1.6.2，保留至今——去重修复本体）**：GroupMessageHandler 入口按 msg_id 分配回复序号基数（第 1 次=1，第 N 次重投递=1+(N-1)×100000），防网关弱网/会话恢复重投递到**本服**时的序号重复。**遇到 40054005 时先想到 ReplySeqGuard（已防住本服重投递）**：若仍被拒收，可能是双服同绑，但不再自动推断——由管理员自行核对是否有多台服务器填了同一凭据。

### 3.4 更新检查（1.6.2，1.6.4 彩色）

`bot/UpdateChecker.kt`：启动后异步 8 秒 GET `https://kerong.xin/penguin/version.json`，逐段数字比较版本；有新版→控制台输出带 § 颜色码的版本号/下载页/最多 6 行简要日志（1.6.4：版本号绿色加粗、链接蓝色、条目灰白分级）；失败→控制台一句话，细节进日志。`update-check.enabled` 可关。**每次发版必须更新服务器上的 version.json**。§ 颜色码处理：Spigot 系控制台直接渲染；NeoForge 适配器 `log_info/log_warning/log_error` 输出前统一过 `PluginFileLog.stripColorCodes`（log4j 不渲染 §，否则乱码）；文件日志始终剥离。共享代码可以放心在控制台文案里用 § 颜色码。

### 3.5 渲染管线与头像获取（1.6.4 头像修复，1.6.5 内存与性能，1.6.6 多线路与落盘，1.6.6.1 覆盖版：本地优先 + 缓存配置化 + MinePic，1.6.6.2：缓存分层 + 空闲释放 + 失败分类 + 获取方式日志，1.6.6.2 覆盖版：日志去重 + 单飞 + /kerong mem，1.6.6.4：移除空闲 GC 提示，1.6.7：新增性能卡片渲染器 PerformanceRenderer——毛玻璃 2×N 指标卡，与在线列表共用背景/字体，画布 1350 宽高度按可见项数计算，PNG 写出后 flush）

CPU 渲染（CardRenderPool 共享线程池，核数一半 1~4 线程）、结果缓存、背景图内置+随机 LRU、长图封顶 1600px、子采样解码防 OOM。图片发送：`replyWithImgBytes`（base64 上传），读超时（SDK OkHttp 10 秒 < QQ 富媒体普遍 10 秒+应答）**不算失败**（图片大概率已投递，1.6.1）；ret 2022021x=已受理算成功。**1.6.5 内存策略**：确定性 flush（单次画布 / 中间图 / 缓存淘汰 / clearCaches 均在失去引用前 flush，剩余堆内存交给常规新生代 GC，System.gc 与 gc-after-render 已废除）。**1.6.6.1 覆盖版内存再优化**：三渲染器圆角头像零中间图直绘（共享 `InfoCardRenderer.drawRoundedAvatar`）；内置背景处理高度统一封顶 1600px。**1.6.6.2 缓存与内存策略**：①头像内存缓存上限 64→**128 条**，并新增 **AVATAR_MEMORY_MAX_BYTES = 10KB 单张门槛**——PNG 编码后超 10KB 的大头像只写磁盘缓存不进内存（`cacheAndStoreAvatar`：`encodeAvatarPng` 编码一次，磁盘写入与内存门槛共用；磁盘缓存关闭时退回全量内存缓存；磁盘命中 `DiskAvatar` 带文件字节数超门槛不回填）；②**渲染空闲内存释放**：`render.idle-release-minutes`（默认 5，支持小数，0 = 关闭）——CardRenderPool 任务收尾（含异常收尾）刷新 `markRenderCompleted` 时间戳 + `activeRenders` 活跃计数护栏，render-idle-releaser 守护线程每 30 秒检查，距上次渲染完成超阈值且无进行中渲染时 flush + 清空 avatar/background/builtin 三类图像缓存并重置计时，missCache / premiumUuidCache 轻量元数据保留，释放动作写日志；③内存缓存及时清理：sweepDiskCache 每轮开头 `purgeMemoryAvatarCache` 清超 TTL（10 分钟）条目，仅无活跃渲染时执行；④磁盘缓存强制关机后可清：`ensureAvatarSweeper` 双端主类启动即调用（首轮 10 秒后清停服/强关期间过期文件）。

**头像链路**（`InfoCardRenderer.kt` 的 InfoCardAssets）：内存缓存（10 分钟，上限 128 条，**1.6.6.2 起只存 PNG ≤ 10KB 的小头像**；超时条目由清扫线程每轮顺带清除）→ **磁盘缓存**（1.6.6 新增，1.6.6.1 有效期默认 3 小时且可配置：`render.avatar-cache-hours`（RenderSettings.avatarCacheHours 实时读，支持小数，0 = 关闭不读不写），插件版 plugins/KERONGPenguin/cache/avatars/、模组版 kerongpenguin/cache/avatars/，512 文件上限，**超 10KB 大头像只存这里不进内存**，重启后免重复请求；过期文件由 avatar-cache-sweeper 守护线程每 30 分钟清扫删除（首轮启动 10 秒后，**双端主类启动时即启动该线程（1.6.6.2，强制关机后残留过期文件下次启动 10 秒内清掉，不依赖运行期状态）**），查询命中过期文件时也顺手删）→ **按 `render.avatar-fetch-priority` 决定本地/外置先后（1.6.6.1 覆盖版新增，RenderSettings.avatarFetchPriority 实时读，local/external 不区分大小写，无效值按 local）**：
- **本地方式（默认 local，先试）**：本服已有数据解析皮肤贴图 URL（在线玩家 Profile（含 SkinsRestorer 修改，主线程捕获进 AvatarRequest.skinUrl）→ 本服 playerdata NBT（extractNbtSkinUrl，离线服+皮肤插件场景））→ 下载贴图 PNG（textures.minecraft.net，服务器不存皮肤文件本身这是物理上限，但不走任何账号 API）→ cropFace 裁脸；
- **外置方式**：Mojang 官方原生 API（会话服务器 v4 直查 + 玩家名反查，网络冷却见下）→ 下载裁脸；官方链路拿不到再走 **外部多线路回退**（1.6.6 起：MC-Heads → Minotar → **MinePic（1.6.6.1 新增，minepic.org/avatar/<无横线UUID或名字>/216，实测 1.7~6s 波动大、未知账号不响应按失败处理，超时 3 秒）** → Crafatar 按实测速度排序；基岩玩家（Floodgate UUID：msb 全 0 + lsb 高 16 位 0x0009，低 48 位为 xuid）走 MC-Heads xuid 线路 `https://mc-heads.net/avatar/xuid:<xuid>/216`；名字线路 MC-Heads → Minotar → MinePic；生产实测 2026-10：MC-Heads 0.4s / Minotar 0.5s / MinePic 1.7~6s / Crafatar 5.8s，LittleSkin 与 mc-api.io 不可达弃用；**Mojang 原生官方 API 始终保留且优先于外部线路**（用户明确要求，外部线路仅兜底））；
- 先试的一方失败自动切换另一方（external 模式 = 外置全败后再试本地）。**1.6.6.2 失败分类**（修复「极少玩家在清除缓存后头像获取失败」：磁盘缓存整批到期→集中重取风暴→Mojang 429/网络级失败进 5 分钟全局冷却→风暴尾部玩家失败被写入 missCache 60 秒冷却重试无门）：`networkTrouble` ThreadLocal 逐次尝试标记（httpGet/httpGetWithStatus 网络异常、官方冷却期内跳过、markMojangUnreachable 429/5xx 均置位，fetchAvatar 开头重置），**网络抖动型失败不写 missCache，下次查询立即重试；确定性失败（404 等）才写冷却**。
- **并发获取单飞（1.6.6.2 覆盖版）**：fetchAvatar 在网络获取段外裹 `inFlightFetches`（key → FetchLatch 门闩）——第一个到达的请求是执行者（真正走网络，finally 里移除门闩并 countDown），并发到达的其余请求是等待者（最多等 8 秒，直接取执行者结果经门闩传递绕过 10KB 内存门槛）。启动 / 刷屏场景多张渲染并发预热同一批头像时，网络请求与分配量按并发数成倍削减（启动期内存飙升的放大器拆除）。
- **获取方式日志（1.6.6.2；覆盖版改文件日志 + 会话去重）**：成功获取（含磁盘命中）记 `[头像] 玩家名 ← 方式`（本地皮肤贴图（在线Profile/存档NBT）/ Mojang官方API / MC-Heads（含基岩 xuid 与名字线路）/ Minotar / MinePic / Crafatar / 本地磁盘缓存（NKB）），失败记一行含是否可重试；**只写 PluginFileLog（logs/qq/qq-bind-日期.log）不进控制台**（覆盖版修复泄漏到控制台刷屏），**同一玩家首次获取与换线路时才记录**（loggedAvatarSources 会话去重表，上限 2048，clearCaches 清空）；内存缓存命中不记（非获取动作）。排查某玩家头像链路看日志文件即可。

**1.6.4 关键修复：Mojang 官方接口网络级失败（超时/IO/429/5xx）开启 5 分钟全局冷却**——「大部分玩家头像获取失败」的根因是服务器到 Mojang 的线路不稳，旧版每个玩家都先等 5 秒超时才能失败，在线列表 8 秒预热预算全部耗尽；冷却期内直接跳过官方链路（走外部线路或诚实失败），不再逐玩家等超时。会话服务器超时也从 5 秒降到 3 秒。离线服无皮肤数据的玩家仍诚实画占位块（不做「人人史蒂夫」假回退）。

### 3.6 配置系统（1.6.7.6 增语法错误三级自愈：`ConfigBrokenRecovery`）

磁盘 config.yml 写坏（最高频：`key:value` 冒号后漏空格）时：①严格解析（`YamlConfiguration().load(Reader)` 抛出可感知）→ ②缺空格逐行修复（可解析则备份 config.yml.error-<时间戳> 后写回修复版）→ ③修不了的备份原文件 + 从 JAR 释放默认配置继续启动（控制台醒目指引从备份填回自定义设置）——替代旧版「空配置硬跑 → 机器人不启动」死胡同。挂载点：ConfigManager.initialize 与 reload 双处。

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
3. **门禁+冒烟**：新写 `scripts/verify_jar_<版>.py`（断言本轮改动点+基线）跑过；新写 `scripts/smoke<版>/Test<版>.java`（javac/java 用 `/home/z/jdk21/bin/`，类路径=NF 分发 JAR+NF libs+deps.jar，参考 `scripts/smoke1672/classpath.txt`）跑过。历史门禁路径写死版本号，旧版 JAR 不在本地属正常。
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
| 40054005 ≠ 单一原因 | 单服=网关重投递（ReplySeqGuard 已防）；双服=另一台抢先回复（1.6.6.3 起不再自动检测推断，由管理员自行核对是否多台同凭据）。修图片发送问题先确认是不是双服务器同绑 |
| 图片读超时≠失败 | QQ 富媒体应答普遍 10 秒+ > SDK OkHttp readTimeout 10 秒，读超时按 WARNING 不提示失败 |
| Mojang 429 限流/网络不可达 | 名字反查限流/网络异常不写负缓存（1.6.2 修）；1.6.4 网络级失败另开 5 分钟全局冷却（避免逐玩家等超时）；404 才写且 30 分钟有效 |
| 停机 zip closed | Paper/Purpur 停机阶段会提前关插件 jar 的 ZipFile，onDisable 首次类加载报错；onEnable 预热 CommandOutputAppender.getInstance()（1.6.4 修），onDisable 每步独立 try-catch |
| 复用旧 build 目录 | 环境重置后复用残留 build/classes 时，旧包名**空目录**（如 cn/huohuas001/）会被 Gradle jar 任务连同空目录一起打进产物，门禁「全 JAR 无旧字样」失败——**发布前 gradlew clean 全量重建**（1.6.6.2 实测踩坑） |
| 工具输出吞 [h | Bash 工具输出管道会吞 `[h` 转义序列（`[hb]` 显示成 `b]`，仅显示层不影响文件字节）——**断言字符串以文件字节为准，别照抄屏幕显示**（1.6.6.2 实测踩坑） |
| 指令重载配置 | 已移除该概念：改配置一律重启（配置头注释+教程+文档三处一致） |
| Kloping 线程池 | 关服后 50+ 非守护线程不退出 → SptCompat 清扫 + 5 秒 halt(0) 兜底 |
| 死代码教训 | 1.6.3 审计删除 21 项零引用声明+剔除 1.3MB 未用库；新死代码判定要查**三个模块+脚本+资源+反射**（@Commands/@SubscribeEvent/@EventReceiver 反射入口不算死） |
| 部署未提交源码 | 1.6.6.3 曾出现线上 JAR 含未提交功能（auto-shrink 五键）而 git 无此代码——会话中断导致。发版纪律：**先 commit 再部署**，任何时点线上 JAR 必须能由 git HEAD 复现；接手时先核对线上 version.json / JAR 与 git log 是否一致再动手（1.6.6.4 实测踩坑） |
| 打包输出即底座 | package_jar.py 默认输出名与 base JAR 同名（版本号相同时），传参时若 base 路径 == 输出路径边读边写 → BadZipFile。**覆盖重发场景先传 -o 换名再 mv**，或用 download 目录里的原版 JAR 作 base（1.6.6.2 覆盖版实测踩坑） |
| JVM 已提交≠存活 | 用户报「内存飙升 GB 级」先分清是堆存活（泄漏）还是 G1 已提交量（分配高峰扩张、默认不归还）。插件侧排查工具 /kerong mem（cacheStats）；渲染实测单次 ~13MB 临时分配零残留。修复路径：削减并发分配（单飞，1.6.6.2 起）；**不要用 System.gc 归还堆**（Full GC 全停顿 0.05~0.6 秒可能落在玩家游玩时刻——1.6.6.2 的 idle-gc-hint 与线上 1.6.6.3 短暂存在的 auto-shrink 均已移除） |
| Bukkit YAML 流重载 | paper-api 1.20.4 已删 `YamlConfiguration.loadConfiguration(InputStream)`（只剩 File / Reader 两重载），而 NF 兼容层只有 InputStream 变体——**双端共用源码解析流必须走 Reader 并给兼容层补同名重载**，否则 Spigot 线编译失败（1.6.7.2 实测踩坑） |
| NF 端 snakeyaml 被重定位 | NF 分发 JAR 里 snakeyaml 在 `cn.kerong.shaded.org.yaml.snakeyaml`（RelocateTool 整体迁移），冒烟/工具对 NF JAR 做 YAML 重解析要用重定位后的类名（1.6.7.2 实测踩坑） |
| snakeyaml dump 单元素列表是单行 | `Yaml(list).dump()` 多元素列表输出多行（含 `\n`），**单元素列表输出单行 `- item`（无换行）**——用「结果是否含换行」判块状/标量会把单元素列表误当标量（1.6.7.2 用户配置重建 `groups: - XXX` 非法 YAML 根因）；序列化判断分支一律按输入类型（Collection 等）分派，别依赖 dump 输出形态 |
| Bukkit loadConfiguration(Reader) 吞异常 | `YamlConfiguration.loadConfiguration(Reader/File)` 解析失败只打 ERROR 日志并返回**空配置**（不抛出）——需要「失败可感知」的校验场景必须用实例方法 `yaml.load(reader)`（抛 InvalidConfigurationException）；且空配置的 getInt 默认 0 恰好能兜底误判，别依赖 |
| 回填渲染格式要与模板同风 | 配置重建的回填渲染若偏离用户手写风格（如列表块状无引号），用户每次升级都看到「自己写的 `groups: ["xxx"]` 变样」、误以为值丢失——1.6.7.3 起 renderEntry 行内双引号渲染与 config.yml 教程示例严格同风（字符串双引号转义 / 数字布尔原样 / Map 列表 flow 单行），模板改示例格式时渲染器必须同步 |
| NF 打包 base 必须传 Spigot JAR | package_jar_neoforge.py 的 base_jar 参数要传 **Spigot** 分发 JAR（原始包名依赖）；误传 NF JAR 时其依赖已重定位到 cn/kerong/shaded（被 EXCLUDE_PREFIXES 的 `cn/kerong/` 前缀整体剔除），产物从 19MB 掉到 6MB 且缺全部第三方库（1.6.7.2 覆盖版实测） |
| Edit 工具改长中文注释易匹配失败 | 长中文注释块存在不可见字符差异（全半角/相似码位），Edit/MultiEdit 的 old_str 精确匹配易失败且报错信息不指明差异；批量改中文注释/配置块用 Python 脚本按锚点行+正则处理（幂等设计），单点小片段仍可用 Edit |

---

## 7. 当前已知状态（2026-10-24，v1.6.7.8 完成）

- 双端 **1.6.7.8** 全部完成（门禁 verify_jar_1678 83 项（LR 组延迟移除 21 项反向断言 + V/TC/SD/SN/CR/HB 保持 + B 组基线）/ 冒烟 smoke1678 47 项（快照无 ping 字段与旧五参工厂/假 spark playerPing 被忽略/五卡通栏渲染 1350×1034/JAR 内容）/ 官网与 git 已上线）：
  - **移除「延迟（玩家平均）」指标全链路**（用户反馈：本地/同机连接下 Ping 恒 0ms，输出的数据一直是服务器本地网络，无任何实际监控意义）：①快照 PerformanceSnapshot 删 avgPingMillis 字段，capture 全参工厂五参→四参（tps/mspt/cpuOverride/onlinePlayers）；②Spigot 采集端 readPlayerStats（Pair<人数,延迟>）简化为 readOnlinePlayerCount（Folia 仍经 callSync 全局区域线程）；③NF 采集端删 sparkPing 与各玩家 connection.latency() 平均，仅留 playerList 人数；④SparkBridge 删 SparkMetrics.ping / pollPlayerPingMean / read 里的调用（spark playerPing 统计不再轮询，旧版 spark 兼容问题随之消失）；⑤渲染器删延迟卡（含「暂不可读/暂无玩家」延迟专用文案与 300ms 满值条），CARD_COUNT 6→5，drawMetricGrid 末行单卡（index==size-1 且 col==0）整行通栏 contentWidth，高度仍 1034；⑥SparkStartupCheck readableList 去「延迟」项；⑦双端 config.yml 指标注释六项→五项并写明移除原因（NF 端顺带修正历史滞留的「1.6.7.1 四项」旧注释）。**1.6.7.7 的 PG 组断言（ping>=0/暂不可读区分）随指标作废，门禁改 LR 组反向断言（字节码无 avgPingMillis/playerPing/延迟标签/300.0 常量）。**

- **⚠️ 1.6.7.6 覆盖版源码丢失事故与归一**：1.6.7.6 发版当日曾做过一轮「同日覆盖版」（内置 spark 通道 / TPS 封顶宣称 / 凭据清洗三项），官网 JAR 与 version.json 已更新，**但覆盖版代码未 commit/push——环境重置后丢失**，git main 一度停留在首发版；1.6.7.7 逐项反编译官网覆盖 JAR（CFR）核实差异后把缺失功能全部重做合入源码（内置通道与凭据清洗完整还原；TPS 封顶覆盖版并未实装——字节码无任何 min(20) 逻辑，仅 version.json 宣称——1.6.7.7 以更彻底的三层封顶真正落位）。**教训：同日覆盖必须立即 commit+push，官网宣称前必须字节级核对 JAR。**
- 双端 1.6.7.7 全部完成（门禁 verify_jar_1677 75 项 / 冒烟 smoke1677 35 项（TC 三层封顶行为/真实形态假 spark 300→20/内置通道 bundledSparkClassLoader 行为/延迟卡三态反射验证/JAR 内容）/ 部署 16 项 / 官网 kerong.xin/penguin 已上线）：
  - **TPS 物理上限 20 三层封顶**：SparkBridge.pollTps 出口、PerformanceSnapshot.firstTps（getTPS/recentTps/装箱数组全分支）、capture 工厂 safeTps——任何来源（spark/反射/换算）>20 一律按满速 20 处理（Minecraft 刻速率固定 20/s，300+ 读数是 recentTps 无上限瞬时平均或 1.21.2+ tick 加速期 getTPS 的真实数百值，显示满速最接近物理事实）；tpsFromMspt 与 TickMonitor 原有 cap 保持。
  - **延迟采集 0 合法**（1.6.7.7；**1.6.7.8 已随指标整体移除，以下为历史记录**）：双端 `ping >= 0` 计数（-1 仍是读取失败哨兵）——0 是合法延迟（本机/同机连接、首次 keep-alive 往返未完成）；渲染器延迟卡区分「有玩家但读不到 → 暂不可读 + 副说明核心不支持 getPing」与「无人在线 → 暂无玩家」。
  - **spark 探测五态**：NotInstalled / Installed(metrics, bundled) / Failed / NoStatsOnThisCore / **NotLoaded(fileNames)**。probeSpark 顺序：findSparkPlugin（大小写不敏感，Bukkit getPlugin 精确 equals 会漏检变体包名）→ 内置通道 SparkBridge.bundledSparkClassLoader(bukkitApiLoader())（候选 = Bukkit API loader / 本类 loader / TCCL，探测 SparkProvider.get() 非 null 且 isInstance；Paper 1.21+ 系核心内置 spark 不注册插件表且默认忽略自装插件版 JAR——Purpur 26.2「装了检测不到」真正根因）→ findStraySparkFiles（plugins 目录 isFile+*.jar+名含 spark 忽略大小写，文件名串入提示）→ NotInstalled。Installed 提示带「来源：服务器核心内置 / 插件」；NotLoaded 提示带模组版误装（Modrinth 全是 NF/Fabric/Forge 变体）/核心已内置双重指引；性能快照 spark 读取与 probe 同口径（表中查无→内置通道）。
  - **凭据清洗**：ConfigManager.sanitizeCredential + trimInvisible（20 个不可见码位：全角空格/U+00A0/U+2000-200B/U+202F/U+205F/U+FEFF/U+2060 等，源码里用显式 \uXXXX 转义防工具链损坏不可见字面量），bot.app-id/bot.secret 读取时清洗，污染时 logger.warning 带原长/清理后长度与「appid invalid」指引；双端共用（ConfigManager 编译进两模块）。
  - **/hb 别名移除**：plugin.yml aliases 行删除、NeoForgeCommands MAIN_COMMAND_LABELS 仅 kerong、24 处文案/注释（QClient/ReconnectManager「重连超时」提示/MenuManager/WssDiagnostics/ConfigManager 注释/help.md（QQ 用户可见帮助）/双端 config.yml 注释/docs/API.md/README 历史段）全部改 /kerong 形态；门禁 HB-5 用正则 `/hb\s?(chs|panel|reload|help|mem)` 断言全库无可操作指引（README/官网更新日志里「移除 /hb」说明性提及合法）。
- 环境重建备注（本次会话第三次环境重置）：JDK21 TUNA Adoptium 21.0.12.1 装 /home/z/jdk21；Gradle 8.14.5 wrapper 直连卡死改腾讯镜像 zip 预置 ~/.gradle/wrapper/dists（哈希目录 690y85m0j9nfaub7xoiayko8a）；~/.gradle/gradle.properties 用 org.gradle.java.installations.paths 指向本地 JDK（foojay 联网下载被墙）；paper-api 等冒烟依赖直接从 ~/.gradle/caches/modules-2 复制到 scripts/deps-work；kerong.xin 的 DNS 对 curl 报错但对 python 正常，curl 用 `-H "Host: kerong.xin" https://156.233.228.248/...` IP 直连可用；后台任务必须 setsid nohup 脱离会话（普通 nohup & 会被会话回收杀掉）；工作区 git 状态曾出现 301 文件 0 行 diff（纯 mode 100644→100755 变化，reset --hard 即清）。

## 8.（原 1.6.7.6 状态段，历史归档）

- 双端 **1.6.7.6** 全部完成（门禁 **verify_jar_1676 62 项** / 冒烟 **smoke1676 26 项**（ConfigBrokenRecovery 三级恢复含用户实测 player-events:true 样例 / TickMonitor 窗口数学 / PerformanceSnapshot 新字段 / fakespark1676 playerPing / 六项卡渲染 1350×1034）/ 官网部署 / git 推送 6917246）：
  - 修复①：Folia 多线程核心 MSPT 读不到 + 数值忽高忽低——新增 **TickMonitor**（common-Bot stats 包：FoliaCompat.runTimer 每 tick 采样 nanoTime 间隔 → 1200 tick 环形缓冲；MSPT=最近 200 tick 均值、TPS=60 秒窗口换算封顶 20；样本<40 不读、单次间隔>10s 丢弃；Spigot onEnable start/onDisable stop）——Folia 上采样器优先、反射兜底，其他核心反射优先、采样器兜底，spark 窗口均值最优先；CPU 无 spark 时经 PerformanceSnapshot.capture 的 EWMA（0.7/0.3）平滑；
  - 修复②：装了 spark 还是提示「接入失败」误报——probeSpark 新增第四态 **NoStatsOnThisCore**（spark 已装且启用但统计全 null 且当前为 Folia → 信息级提示「已用内置采样器」；非 Folia 仍按 Failed 警告）；
  - 修复③：config.yml 语法错误死胡同（`player-events:true`）——**ConfigBrokenRecovery** 三级自愈（见 §3.6）；
  - 新增④：查性能六项——PerformanceSnapshot 增 `avgPingMillis`/`onlinePlayers`（capture 全参工厂）；延迟链路（**1.6.7.8 已整体移除**）：spark playerPing（Spark#playerPing→GenericStatistic#poll(PlayerPing.MINUTES_1)→mean）→ Spigot 各玩家 getPing 平均（Folia 经 callSync 全局区域线程，纯 Spigot 无此 API 时 NoSuchMethodError 被捕获仅人数照常）→ NF 各玩家连接 `ServerCommonPacketListenerImpl#latency()`（mojmap 公有方法）；
  - 版本：1.6.7.6 / config-version 22（无键增删）。
- **1.6.7_Cross-posting.1 互转版**（同日发布，仅 Spigot 插件版）：本地分支 `cross-posting-1`（worktree /home/z/my-project/cross-posting-git，**不上传 git**——用户明确要求教程不进公开仓库）；基于 1.6.7.6；多群互通（bot.groups 多行最多 20 群、全量转发默认 / 艾特转发 `/多群转发` 命令、cross-posting 段 enabled/full-forward/format、QClient.sendTextToGroupsExcept 经 OutboxQueue、/多群转发 管理员限定）；**无更新检查**（initializeRuntime 的 scheduleUpdateCheck 注释 + config.yml 无 update-check 段）；**教程全部写 config.yml cross-posting 段注释**（官网「其他版本」卡片不放教程）；门禁 verify_crossposting 34 项 / 冒烟 TestCP 9 项；官网底部「其他版本」卡片 + downloads/KERONGPenguin_Spigot-1.6.7_Cross-posting.1.jar；该版本**仅用户要求时更新**。
- 官网历史下载：1.6.7.6 起保留 1.6.7.5~1.6.7.6（+互转版）；1.6.6.4 已下架。
- 待观察：TickMonitor 在真实 Folia 上的窗口读数节奏；Cross-posting 版真实多群转发链路（含附件占位）。

## 8. Cross-posting 互转版（1.6.7_Cross-posting.1，重要工作流）

- **分支**：本地 `cross-posting-1`（git worktree：`/home/z/my-project/cross-posting-git`），基于 1.6.7.6（6917246）。**永远不 push**——用户要求该版教程与源码不进公开仓库；每次更新在 worktree 内开发、本地 commit、构建部署。
- **与主版差异**（见 cross-posting-1 分支 commit 28e7b1a）：CrossPosting.kt（转发器+命令+会话）、KERONG 接口 crossPostingConfig 契约、QClient.sendTextToGroupsExcept、GroupMessageHandler 挂接、ConfigManager 群 20 截断与 cross-posting 段、无更新检查、config.yml 教程注释。
- **构建**：worktree 需先复制 gitignore 的编译依赖（common-Bot/libs/deps.jar、server-Spigot/libs/、server-NeoForge/libs/ 从主检出到 worktree）；打包 base 用主版最新 Spigot JAR；输出名用 `-o` 显式指定（package_jar.py 的版本正则只认数字段，会截成 1.6.7）。
- **更新节奏**：用户要求才更新；更新时把主版新代码 rebase/merge 进分支后重新构建部署。

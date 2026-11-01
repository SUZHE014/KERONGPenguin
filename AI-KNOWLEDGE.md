# KERONGPenguin AI 交接知识库

> **本文档的用途**：把本文件丢给一个对项目一无所知的 AI，它能立刻了解当前情况并接手工作。
> 面向：接手开发/发版的 AI 助手。最后更新：2026-11-01 六次（**主线 v1.6.9.4 发布**（图片卡片背景遮罩样式：render.background-style，默认 gray-mask 原版灰色遮罩不变，新增 blur-mask 模糊遮罩——参考 EasyBot 液态玻璃模板，降采样 1/8→两趟盒式模糊→双线性放大等效约 40px 柔化，样式进背景缓存键 reload 切换即时生效，对五卡片+内置图+img 随机图全生效；修复 QQ 合并转发聊天记录刷屏：QQ 服务端把合并记录渲染成「=== 消息 N ===/[发送者]/[消息内容]」多行模板串（或 message_type=102）整段下发，旧版原样转发游戏一次几十行，现 QqText.detectMergedForward 双通道判定（102∨双标记）改单行「[聊天记录 共N条]」占位；reload 可用性逐项核实并写明边界：绝大多数配置即时生效，仅 bot 凭据与 update-check 需重启、面板用 /kerong panel 手动同步——写入 config 注释四段/reload 反馈/help 行/教程（旧头部「修改本文件后需重启服务器生效」错误文案一并修正）；异步化审查：AI 对话出 WSS 消息线程、/kerong reload 双端出主线程（其余链路复核已异步）；config-version 24→25；门禁 46 + 冒烟 26 全绿；git 远端 main=a68514f 已推送复核）。同日五次：**主线 v1.6.9.3 发布**（变量获取扩展：新增玩家名/TPS 可配置 + 自定义查信息项目（项目ID/项目名称/获取变量）+「填了变量必展示、读不到显示未获取成功」显隐规则 + 排行榜/财富榜/查性能同步拉取变量；查性能在线人数展示「N / 服务器最大人数」（默认真实值，可配 MOTD 展示人数，ServerListPingEvent MONITOR 捕获）；移除 /在线服务器 与 /motd 命令（面板 21→19 项）；config-version 23→24；兼容层 PluginManager 单槽改多槽；门禁 55 + 冒烟 50 全绿；git 远端 main=fff86a7 已推送双通道复核）。同日四次：**主线 v1.6.9 发布**（新增变量获取信息 variable-fetch：查信息卡片五项目——总在线时长/今日在线时长/金币/点券/称号——可改读 PlaceholderAPI 变量输出，默认关、留空/失败自动回退自带数据；PlaceholderVariableService 新文件 + QueryInfoService 主线程快照解析；config-version 22→23；官网教程全量小白化改写（技术细节收进最后一个卡片）；门禁 33 + 冒烟 27 全绿；git 远端 main=9418cb7 已推送）。同日三次：**主线 v1.6.8 发布**（时长单位小时化 + 排行榜头像修复——Mojang 官方线路移到最后兜底；官网删 1.6.7.9 前旧版与 UI 精简；git 远端 main=40ab3ab 已推送，细粒度 PAT 403 被 token2 经典令牌顶替成功）；门禁 27 + 冒烟 20 全绿；环境第八次重建：tooling JDK21/Gradle 走 TUNA/腾讯镜像重装，deps.jar 从 1.6.7.11 Spigot JAR 重建，NF libs 沿用旧工作区遗留（目标版本一致）。同日二次：官网首页 1.6.7.11 回滚事故修复：v0.5 上线时 index.html 用 1.6.7.10 旧底稿整文件覆盖，把主线 1.6.7.11 的主下载框/历史版本表/更新日志整体回滚——version.json/双 JAR/源码包从未受影响、游戏内更新提示一直正常；已重建上线并新增 §6「官网整文件覆盖回滚」坑条目。同日早前：**0.3AI v0.5 发布**（AI 专属版，见文末 §9：零环境自动部署 Ollama+DeepSeek（内存检测选档每次重启一次）、引用回复可选化（[引用 N] 标记 + 主动优先被动兜底）、表情包（guaipng 文件名语义匹配），门禁 138 项冒烟 120 项全绿，官网已上线）；主线 git 本轮仅知识库同步（远端 main = 42858a9，v0.5 内容随本次同步推送）；环境沿第八次会话：tooling 全套构建链（kotlinc 2.2.0 + paper-api + adventure + guava + snakeyaml）与 ai-git/ai-version 工作区完好）。
> 前次：2026-10-31 第二次会话——**主线 git 已推送**：本地 main 重建 1.6.7.11 等效提交 e3debc5（原 55854ae 随第七次环境重置丢失，按官网源码包重建）并推送 GitHub 成功——gh-proxy 只转发 clone 不转发 push 鉴权（报 No anonymous write access），**push 走官网服务器中继**：git bundle 上传 → /root/gitpush/bare.git fetch → 带 PAT 直连 push（服务器可直连 GitHub），远端 main = e3debc5 已双通道复核；**0.3AI v0.4 发布**（本地 DeepSeek 取代云端 API、删私信、全局共享上下文+用户名片、ais.md 提示词、网页爬虫、管理员一键删除上下文+指令面板按钮，门禁 101 项冒烟 67 项全绿）；环境第七次重置后恢复：tooling 的 JDK21/CFR 仍在，补齐 AI 版构建链（kotlinc 2.2.0 + paper-api + adventure + guava + snakeyaml，全部 tooling/ 持久化）。
> 自包含版本（发给本地无任何东西的 AI）见官网 `AI-KNOWLEDGE.txt`，含从零开始的环境重建与凭据获取说明。

### 1.1 受限网络环境重建（2026-10-14 实战，环境重置后可用）

本地环境丢失时按此重建（GitHub/Maven 官方源不通，走镜像）：
- 克隆：`git clone https://gh-proxy.com/https://github.com/SUZHE014/KERONGPenguin.git`；
- `common-Bot/libs/deps.jar` 不在 git：从官网下载 Spigot 分发 JAR，剔除 `cn/kerong/**`、`Markdown/`、`backgrounds/`、`fonts/`、`plugin.yml`、`config.yml` 后重建（脚本思路见 `/home/z/my-project/scripts/rebuild_deps_jar.py`）；
- NeoForge libs：`scripts/build_neoforge_libs.py` 的镜像变体（BMCLAPI：`piston-meta/piston-data/libraries.minecraft.net/maven.neoforged.net/maven.minecraftforge.net` → `https://bmclapi2.bangbang93.com[+路径]`，maven 前缀多一级 `/maven`，libraries 前缀 `/libraries`）；ART 重映射耗时 5-10 分钟、内存 2G 够用；
- Gradle 发行版：腾讯镜像 `https://mirrors.cloud.tencent.com/gradle/gradle-8.14.5-bin.zip` 预置到 `~/.gradle/wrapper/dists/gradle-8.14.5-bin/<wrapper 期望哈希>/`（哈希不是 md5，先跑一次 wrapper 看它建的目录名）；
- JDK 21（含 javac 的完整 JDK，系统 JRE 不够）：清华 TUNA Adoptium 镜像 `https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/linux/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz`（华为 openjdk 目录已无包）；`~/.gradle/gradle.properties` 设 `org.gradle.java.installations.paths=/home/z/jdk21` 与 `auto-download=false`（foojay 会走 GitHub releases 被墙）；另需 `settings.gradle.kts` 若解析 foojay 失败可移除该插件（本地 JDK 已就位不需要自动下载）；
- Maven Central/PaperMC/Spigot/插件门户均直连可用（本次实测 2026-10-15）；pip 用阿里云镜像。
- **2026-10-31 第五次环境重建实测补充**：gh-proxy 克隆 git 偶发 403（镜像抖动，重试或换
  ghfast.top/ghproxy.link 即可成功）；**沙盒内后台进程（setsid nohup）会在 bash 会话间隙被回收**——
  长构建（gradle / NF libs 的 ART 重映射）必须前台跑足超时；ART 与 Gradle 并发会 OOM（4G 内存机器），
  串行执行；官网 HTTPS IP 直连下载需 `-k`（自签证书）+ `-H "Host: kerong.xin"`，速度约 2MB/s 远快于 SFTP；
  JDK21 用 TUNA Adoptium 21.0.13_9（装 /home/z/jdk21，系统 openjdk-21 无 javac 且 apt 无权限）。

---

## 0. 一句话概括

KERONGPenguin 是一个 **Minecraft 服务器 + QQ 官方机器人联动**的项目：玩家在 QQ 群里查数据/签到/聊天，插件在服务器内渲染统计卡片图片并回发。Kotlin 多模块：`common-Bot`（全部业务核心）+ `server-Spigot`（Bukkit 插件版，支持 Spigot/Paper/Folia/混合端）+ `server-NeoForge`（NeoForge 模组版 **MC 1.20.4+**：1.20.4 / 1.20.6 / 1.21.x 全区间，1.6.7.9 起编译目标 20.4.253 面向最低版本、在更高版本运行；复用 common-Bot 全部源码 + Spigot 的 manager 源码；mods.toml + neoforge.mods.toml 双声明，BindLoginGate 跨版本事件桥，产物名 MC 段 = 1.20.4）。

**版本规则**：插件版与模组版同步发号（如 1.6.7.6）；另有互转版 1.6.7_Cross-posting.3（仅插件版，见文末 §8）。当前版本 **1.6.9.4**（**背景遮罩样式 + 合并转发防刷屏 + reload 边界 + 异步化**：① render.background-style（gray-mask 默认原版观感不变 / blur-mask 模糊遮罩——blurImage 降采样 BLUR_DOWNSCALE=8 → boxBlur 两趟 BLUR_BOX_RADIUS=3 可分离整数滑动窗口 → 双线性放大，毫秒级且随背景缓存；样式标签进 processedBackground/processedBuiltinBackground 缓存键，切换互不污染；内置图 blur 时只模糊不叠遮罩保持原语义；RenderSettings.backgroundStyle() 实时读取，blur-mask/blur/glass→BLUR，乱值→GRAY 兜底）；② QQ 合并转发（聊天记录）防刷屏——detectMergedForward(metadata,content)：message_type==102（官方 MsgElement 聊天记录类型）∨ 正文含模板标记（「=== 消息」∧「[发送者]」双标记或「[消息类型] 合并转发消息」，双标记防误伤），条数取「=== 消息 N ===」最大 N 否则「--- 第N条 ---」最大 N；forwardFullGroupMessage 识别后改「[聊天记录 共N条]」单行占位（count null 不带数字），103 引用与正常文本不受影响；③ reload 边界：仅 bot.app-id/bot.secret（启动连接一次，chs 重连用旧凭据）与 update-check（启动后第 8 秒一次）需重启，面板按钮用 /kerong panel 手动同步；config.yml 头部总说明/凭据段/update-check 段/commands 段注释 + 双端 reload 反馈 + help 行 + 教程技术卡四层写明；④ 异步化：handleAiChat 整体 submitAsync（AiChat.chat 同步 HTTP connect10s+read30s 原阻塞 WSS 读取线程，期间后续群消息/命令全排队；回复照旧引用原消息，被动回复窗口 5 分钟）、/kerong reload 双端 submitAsync（磁盘 IO 出主线程）+plugin.submit 回主线程反馈；门禁 verify_jar_1694 46 项 + 冒烟 smoke1694 26 项全绿） / 1.6.9.3（**变量获取扩展**：：① formatPlayTime（QueryInfoService 查信息「在线时长」+ LeaderboardRenderer 在线排行榜两处实现）最大单位「天」→「小时」，3天5时20分 → 77时20分 / 77小时20分钟，不足 1 小时仍展示到秒（查信息）/分（排行榜），formatTodayTime 本就小时制不变；② Mojang 官方头像线路（sessionserver 直查 + api.mojang.com 名字反查）从外置链路首位移到最后兜底——官方线路国内普遍超时（单玩家最多 3+5 秒），排首位时排行榜 4 线程 × 8 秒预热预算全烧在逐玩家官方超时上、预算耗尽整批头像被 invokeAll 取消（用户实测「排行榜大多数头像都没显示」），现四条外部线路 MC-Heads→Minotar→MinePic→Crafatar 先行、官方仅在全部失败后尝试，官方链路构件（冷却/名字反查缓存等）原样保留只是移位；门禁 verify_jar_168 27 项 + 冒烟 smoke168 20 项全绿） / 1.6.7.11（**NeoForge 登录闸门挂载修复**：BindLoginGate 跨版本桥去掉 Event 基类 @SubscribeEvent 监听器（mod 总线 markerType(IModBusEvent) 校验必拒），改反射 IEventBus.addListener(Class, Consumer) 按探测到的具体事件类精确注册 + mounted 幂等护栏；registerTo 返回 boolean、「已挂载」INFO 只在真值分支（CFR 断言）；smoke16711 在真实 FML 风格 mod 总线（markerType+allowPerPhasePost 构造）端到端验证 20.4 与 21.1 双构件组合 + 负向对照复现原始故障；门禁 39 项冒烟 27 项；Spigot 端随版本号同步行为不变） / 1.6.7.10（**快捷面板 30013 真根因修复 + /kerong panel 异步化与如实反馈 + NeoForge toml 段头真修复**：①单面板按钮上限 20（官方文档 v2/panels「一个指令面板里最多配置 20 个面板元素」），PANEL_TRIM_ORDER 裁剪顺序（查询open ID→解除黑名单→AI对话上下文→清除当前上下文→全量→黑名单→motd）+ 末尾兜底截断，冒烟实测 21→20 裁掉查询open ID、15 项不裁、25 项兜底；②30013 退避 LIMIT_RETRY_BACKOFF_MILLIS={2500,5000}、创建 QPM 节流 CREATE_MIN_INTERVAL_MILLIS=6500ms（AtomicLong 全局）、列表 next_cursor/is_end 分页（≤10 页）+ URLEncoder 游标、listPanels 失败返回 null 与空表区分；③PanelSyncResult(panelId/buttonCount/groupCount/error+ok) 结构化结果，syncGroupPanelsWithResult 新入口，旧 void syncGroupPanels 保留为兼容桥（互转版 QClient 按 (Starter;List)V 描述符调用，改返回值会 NoSuchMethodError）；④mods.toml + neoforge.mods.toml 段头 [[ods]]→[[mods]] 字节级修复 + tomllib 解析验证；门禁 verify_jar_16710 24 项 + verify_jar_neoforge_16710 21 项 + 冒烟 smoke16710 14 项（对主版与互转版 JAR 双跑全绿）） / 1.6.7.9（**快捷面板先删后建 + 更新检查镜像回退 + NeoForge 多版本适配**：面板同步每次先删除列表里全部面板（含非本插件 remark 残留）再创建，创建遇 30013（含 40030013）自动清空重建，错误码归一化（400 前缀形态折算）+ describePanelError 人话如实提示，旧 PUT 原地更新死代码移除；UpdateChecker 多源回退（官网域名 → 内置 IP 直连镜像 https://156.233.228.248/penguin/version.json，Host 头 + HostnameVerifier 放行域名比对不降 CA 链校验），全部失败一句话带关闭指引，新配置键 update-check.mirror-url（off/false/none = 关闭回退）；NF 编译目标 MC 1.20.4（NeoForge 20.4.253 / FML 2.0.17 / bus 7.2.0），面向最低版本编译在更高版本运行，支持 1.20.4/1.20.6/1.21.x 全区间；mods.toml 段头历史笔误修复 + mods.toml/neoforge.mods.toml 双声明；BindLoginGate 跨版本事件桥（Event 基类监听 + Class.forName 探测 + ICustomConfigurationTask 跨版本桥梁接口——该接口 20.4 与 21.1 字节码签名完全一致）；ServerChatEvent.getRawText 三代构件核对签名一致直接调用；NF 产物名 MC 段 = 最低支持版本（KERONGPenguin_NeoForge-1.20.4-x.y.z.w.jar）；1.20.4/1.20.6 服务器需 Java 21 运行）/ 1.6.7.8（**移除「延迟（玩家平均）」指标全链路**：该指标量的是「客户端到服务器」连接往返——本地/同机/内网穿透部署下玩家 Ping 恒 0ms（首次 keep-alive 未完成同样保持 0），只代表服务器本地网络无实际监控意义；快照删 avgPingMillis 字段（capture 五参→四参 tps/mspt/cpuOverride/onlinePlayers）、Spigot 采集端 readPlayerStats→readOnlinePlayerCount（Folia 仍经 callSync）、NF 采集端删 connection.latency() 平均、SparkBridge 删 SparkMetrics.ping/pollPlayerPingMean（playerPing 统计不再轮询，旧版 spark 兼容问题随之消失）、渲染器删延迟卡（CARD_COUNT 6→5，末行单卡 index==size-1 且 col==0 时整行通栏 contentWidth，高仍 1034）、SparkStartupCheck 可读项去「延迟」；1.6.7.7 的「延迟 0 合法/暂不可读区分」随指标整体作废，门禁 PG 组断言改 LR 组反向断言） / 1.6.7.7（**① TPS 物理上限 20 三层封顶**：SparkBridge.pollTps / PerformanceSnapshot.firstTps / capture 工厂 safeTps——任何来源（spark/反射/换算）读到的 300+ 一律按满速 20 展示（tick 加速期真实数百 tick 率与 recentTps 无上限平均都按语义上限处理），MSPT 仍如实；1.6.7.6 覆盖版曾宣称此修复但字节核对**未实际实装**，1.6.7.7 真正落位。**② 延迟 0 合法化**：双端 ping>=0 计数（0=本机/同机连接/首 keep-alive 未完成都是有效延迟，旧版 >0 判定导致单人本地进服平均延迟永远算不出），延迟卡区分「有玩家但核心不支持 getPing→暂不可读」与「无人在线→暂无玩家」。**③ spark 探测五态重做合入**：覆盖版三功能（内置 spark 通道 bundledSparkClassLoader / Installed(bundled) 来源标注 / NotLoaded 引导 / 凭据清洗 sanitizeCredential+trimInvisible 20 不可见码位）因覆盖版代码未 push 随环境丢失——1.6.7.7 反编译官网 JAR 核实后全部重做合入源码版，并新增插件表大小写不敏感匹配（findSparkPlugin）；自此源码仓库与发布包完全一致。**④ 移除 /hb 短别名**：主命令唯一 /kerong，双端注册与 24 处用户可见文案同步清理。）/ 1.6.7.6（同日覆盖版，功能已被 1.6.7.7 归一收录）（**覆盖版三修复**：① **Paper 1.21+ 系核心（含 Purpur 26.x）已预捆绑 spark**——不以插件形式注册（PluginManager 查无）且默认忽略 plugins 自装 spark JAR（preferSparkPlugin 系统属性才切自装版），旧探测按「未安装」静默 = 用户「装了 spark 还是检测不到」根因；SparkBridge 新增 bundledSparkClassLoader（Bukkit API loader→本类 loader→context loader 候选链解析 SparkProvider），probeSpark/查询链插件表查无时直接接入内置 spark（Installed(bundled=true) 提示「来源：服务器核心内置」）；plugins 目录残留未加载 spark 文件（Modrinth 全是模组变体）→ 第五态 NotLoaded 醒目引导。② **TPS 封顶 20**：MC 1.21.2+ tick 加速（自动/手动 sprint，实测 getTPS 219.8~300+ 是真实 tick 率）→ capture 全参工厂 clampTps 统一 min(20)（NaN→null/负→0），MSPT 不动。③ **凭据清洗 sanitizeCredential**：AppID 尾随空格/零宽/BOM → QQ 平台 100007 appid invalid → botAppId/botSecret 读取自动 trimInvisible + 知情提示；QqTokenHealth.interpretTokenErrorCode 错误码人话化（100007/100016 指引）。首发内容：**Folia 多线程核心修复**：新增自采样刻监视器 TickMonitor——每 tick 轻量采样写入 1200 tick 环形缓冲，MSPT 取最近 200 tick 均值 / TPS 取 60 秒窗口换算，Folia 上优先、其他核心作反射兜底、spark 窗口均值最优先，解决 MSPT 读不到与数值忽高忽低；CPU 瞬时值 EWMA 平滑；spark 探测第四态 NoStatsOnThisCore——Folia 下统计不可读按信息级提示不误报接入失败；**config.yml 语法错误三级自愈**：严格解析 → 缺空格笔误自动修复写回 → 修不了备份 config.yml.error-时间戳并重建默认继续启动，解决「player-events:true → 空配置 → 机器人不启动」；**查性能六项**：新增延迟（spark playerPing / 平台 getPing / NF connection.latency 平均）与在线人数仅展示）/ 1.6.7.5**（**SparkBridge 反射链修复**：spark 的 SparkImpl 与各统计实现类全部包私有，旧版对 obj.javaClass 取 Method 反射调用抛 IllegalAccessException 被逐项容错吞掉 → TPS/MSPT/CPU 全 null（启动自检报「检测到 spark 但接入失败（各指标均暂不可读）」、/查性能 数据从未走过 spark——1.6.7.2~1.6.7.4 均受影响，手写假 spark 冒烟恰好是 public 类所以没暴露）；现方法一律从公共 API 接口解析（Spark/DoubleStatistic/GenericStatistic/DoubleAverageInfo 的 Class.forName + getMethod + isAssignableFrom 擦除匹配 poll(Enum)），已对真实 spark 1.10.187 字节码逐项核实（含 Statistic#name()/getWindows()、SparkProvider.get() 未加载抛 IllegalStateException）并以「真实形态假 spark + 包私有实现 + 独立 URLClassLoader」端到端冒烟；启动自检失败先 15 秒静默复测再定性；**AI 聊天艾特判定修复**：QQ 群消息 content 已被平台去除 @机器人前缀、mentions 不含机器人自身（官方文档明确），旧「content 含 <@」门槛对群消息永远不成立 → @机器人 从未被判定为 AI 对话全部落转发；现 WssDiagnostics 帧层捕获 GROUP_AT_MESSAGE_CREATE 的 d.id 记入 GroupAtTracker（帧钩子先于 SDK 分发执行，重连安全），群消息入口三通道判定（帧标记/mentions bot=true/<@文本）；附带 RecentMessageGate msg_id 级去重（全量模式同一条 @ 消息双事件先后到达 + QQ 弱网重投递——同一条消息只处理一次）与 QqText.stripLeadingMentionPlaceholders 行首占位剥离（全量模式 content 原样保留 <@openid> 前缀，@机器人 /命令 此匹配不到命令）；AI 未开启时 @ 消息不再被吞落全量转发；**查性能卡面板透明度与其他卡片统一（同日覆盖版）**：头部卡白 34 / 容器黑 120 / 指标卡白 16 / 胶囊白 30，与查信息/查在线/排行榜逐一相同，无面板描边，整卡透明度由全局 render.global-opacity 统一控制（首次发布曾改实色深板，因与其他卡片观感不一致当日覆盖）；）/ 1.6.7.4（**spark 接入提示重做**：时机改到服务器启动成功之后（Spigot 端 ServerLoadEvent 后 5 秒、NF 端 ServerStarted 后 5 秒，探测改三态 ProbeOutcome（未安装/已接入/已装但失败）：未安装完全静默；接入成功且 commands.查性能 开启才控制台正向确认（查询关闭仅文件留档）；已装 spark 但接入失败（未启用/指标读不到/异常）无论开关 warnAndKeep 醒目提示并说明回退链；ServerLoadEvent 注册失败退回 onEnable 后 12 秒旧节奏兜底；**日志单文件 512KB 上限**：PluginFileLog.ROTATE_THRESHOLD_BYTES=512KB，resolveRotated 共享轮转实现（QqBindManager.getLogFile 与 PluginFileLog.currentFile 两写入入口同一规则，超限顺次 -N 文件，原 1GB）；**控制台降噪三条**：群消息监听已注册/SDK 日志将写入文件（QClient infoAndKeep→write 仅文件）、/绑定 命令已注册（双端 logQuiet→logFileOnly 新方法，文件格式与 logQuiet 文件段完全一致）——日志文件内容不变；）/ 1.6.7.3（**配置重建回填格式修复**：YamlTextMerge.renderEntry 对列表与字符串统一渲染行内双引号——空列表 `[]`、非空列表 `["A", "B"]`（字符串双引号转义/数字布尔原样）、字符串值统一双引号，与 config.yml 教程示例完全同风，修复 1.6.7.2 重建把用户手写 `groups: ["xxx"]` 改写成块状裸串「看着像丢值」的问题，config-version 21→22 重启自动重刷格式；**PapiDependencyCheck 收窄**：仅当检测到 PlayerTitle（papiConsumerPlugins 清单）才检测提示，就绪档 infoAndKeep 正向确认、未装相关插件完全静默；**SparkStartupCheck 新增**：启动过程中（Spigot onEnable 后 12 秒 / NF ServerStarted 后 5 秒异步）检测到 spark 且读取成功时控制台输出「已接入 spark」；）/ 1.6.7.2 同日覆盖版（/查性能 检测到 Spark（插件/模组）时优先经 SparkBridge 纯反射调用其公开 API 取 TPS/MSPT/CPU，单项读不到回退自研链；性能数据缓存 render.performance.cache-seconds 覆盖版默认 60 秒；**全局透明度 render.global-opacity（默认 1.0）**：GlobalOpacity.apply 在四个渲染器 PNG 编码前统一把整图 alpha 乘系数（1.0 原样零开销，0.5 半透明）；**覆盖版移除液态玻璃**（render.performance.style 入 REMOVED_CONFIG_KEYS 自动清理，卡片固定原版毛玻璃深色）；**配置版本升级重建**：磁盘 config-version 低于 JAR 内置时以新版 config.yml 全文为底 ConfigFileRebuilder 文本级合并（YamlTextMerge 逐行替换/插入保注释，自定义值回填、旧默认自动升级、备份 config.yml.bak、先校验后落盘；**覆盖版修复单元素列表产出非法 YAML**——renderEntry 按类型分派列表为块状项行，loadYaml 改 throws 语义可感知失败）；from-group 新默认 [§bQQ§r]、idle-release-minutes 默认 5→15；config-version 20→21）/ 1.6.7.1：修复 /查性能 TPS/MSPT 一直读不到（PerformanceSnapshot 分层反射读取器；固定四项，删 show-*）/ 1.6.7：新增 /查性能 卡片与 /查在线 默认图片输出 / 1.6.6.4：移除空闲 GC 提示 / 1.6.6.3：重连降噪 / 1.6.6.2：缓存分层+空闲释放+单飞+/kerong mem / 1.6.6.1 覆盖版：头像本地优先、删 WebUI / 1.6.6：控制台真彩色、头像多线路，包名 cn.kerong.bot 与 cn.kerong.penguin，主类 KERONGSpigot / KERONGNeoForge）。

---

## 1. 环境与位置（必读）

| 项 | 值 |
|---|---|
| 本地仓库 | `/home/z/my-project/penguin-git`（GitHub `SUZHE014/KERONGPenguin`，main 分支） |
| 官网服务器 | `156.233.228.248`（宝塔 nginx，站点 kerong.xin） |
| 官网目录 | `/wwwroot/penguin/`（index.html 下载页 / tutorial.html 教程 / style.css / version.json 更新检查文件） |
| 产物目录 | `/wwwroot/penguin/downloads/`（双端 JAR） |
| 源码包目录 | `/wwwroot/penguin-source/`（git archive 打的 zip，与仓库同结构） |
| SSH/SFTP 脚本 | `/home/z/my-project/scripts/ssh_run.py`（paramiko；run/get/put/tryuser 子命令，凭据在文件内；1.6.7.11 会话起为此名） |
| 打包脚本 | `scripts/package_jar.py`（Spigot）、`scripts/package_jar_neoforge.py`（NeoForge） |
| 门禁脚本 | `scripts/verify_jar_<版本>.py`（每版新写一个）+ `scripts/smoke<版本>/`（功能冒烟，独立 JVM） |
| 工作日志 | `/home/z/my-project/worklog.md`（多 agent 共享，每次工作后追加） |
| Java | 21（**1.6.7.11 起装在 `/home/z/my-project/tooling/jdk21`**（TUNA Adoptium 21.0.13，含 javac），随 my-project 持久化不再随环境重置丢失；系统 apt 的 openjdk-21 是 JRE-only 无 javac；构建时 `GRADLE_USER_HOME=/home/z/my-project/tooling/gradle-home`（其 gradle.properties 指向 tooling/jdk21 + kotlin in-process + jvmargs 2800m；wrapper dists 哈希目录 690y85m0j9nfaub7xoiayko8a 已预置腾讯镜像版 gradle-8.14.5）；CFR 反编译器固化在 `tooling/cfr.jar`（门禁断言用）） |
| AI 版构建链（0.3AI v0.4 会话起） | `tooling/` 内：kotlin-compiler-2.2.0.jar（kotlinc 直编不走 Gradle；**运行需同 classpath 补 kotlinx-coroutines-core-jvm-1.8.0 + kotlin-reflect-1.6.10**）、paper-api-1.20.4.jar、adventure-api/key-4.16.0 + examination-api/string-1.3.0（CommandSender 超类解析）、guava-32.1.2-jre + snakeyaml-2.2（冒烟 JVM 用，服务器自带）；全部 maven central / papermc 直连可下 |
| 0.3AI 源码 | `/home/z/my-project/ai-git/`（**不进公开仓库**，沿互转版惯例；服务器灾备 `/root/ai-source/0.3AI_v0.4_source.zip` 非web目录）；线上 JAR 工作区 `/home/z/my-project/ai-version/`（底座 + 解包 classes + 反编译 decomp） |

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
| 命令分发 | `cn/kerong/bot/events/GroupMessageHandler.kt` | 群消息入口：ReplySeqGuard 基数分配 → **1.6.7.5：RecentMessageGate msg_id 去重（双事件/重投递）+ QqText.stripLeadingMentionPlaceholders 行首占位剥离** → 纯数字自动绑定 → 命令分发（首个匹配短路）→ @机器人 AI（**1.6.7.5 三通道判定：GroupAtTracker 帧标记/mentions bot=true/<@文本；AI 未开启落转发；1.6.9.4：AI 调用整体 submitAsync——AiChat.chat 同步 HTTP 原阻塞 WSS 读取线程**）→ 全量转发（**1.6.9.4：合并转发消息（聊天记录）防刷屏——QqText.detectMergedForward 判定 message_type==102 或正文含「=== 消息 N ===+[发送者]」/「[消息类型] 合并转发消息」模板标记，识别后改单行「[聊天记录 共N条]」占位，不再整段转发几十上百行**）。命令用 `@Commands("名")` 注解，多触发名写 `@Commands("财富榜","金币榜")` |
| 命令开关/别名 | `spigot/manager/ConfigManager.kt`（COMMAND_ALIASES 映射） | 别名与主命令共用 `commands.<主名>` 开关；改命令名时：注解双名 + 别名映射 + 快捷菜单按钮 + config.yml 键迁移 + help.md + 教程同步 |
| 消息模板 | `spigot/qqbind/MessageTemplates.kt` + 资源 `Markdown/*.md`（68 个） | 全部文本回复走模板：首次启动释放到数据目录 `Markdown/`，用户可改；`messages` 配置段映射模板 id→文件名。发文本用 sendDirect（带@）/sendPlain |
| 绑定/解绑/黑名单 | `spigot/qqbind/QqBindManager.kt`（单例）+ `events/commands/BindCommands.kt` | 玩家进服未绑定发 5 位绑定码（可配置踢出拦截），群里 `/绑定 <码>` 或纯数字 4-12 位自动绑定；`/重新绑定`、`/黑名单`、`/解除黑名单` |
| 签到 | `events/commands/CheckInCommands.kt` | 每日一次（网络北京时间，见 PlayerStatsManager.beijingToday），经 Vault 发金币，无经济插件走记账模式（离线暂存上线补发） |
| 查信息统计卡 | `events/commands/PublicCommands.kt` → `spigot/stats/QueryInfoService.kt` → `spigot/render/CardRenderPool.kt` + `InfoCardRenderer.kt` | CPU 异步渲染 PNG（CardRenderPool 线程池，核数一半 1~4），9 项统计，依赖缺失自适应（Vault 金币 / DeluxeTags **或 PlayerTitle**（1.6.4，PAPI %playerTitle_use%） / PlayerPoints 点券），头像多级链 + **外部多线路回退（含 MinePic）与 Mojang 网络全局冷却**（1.6.4~1.6.6.1，详见 3.5）；**1.6.9.4：背景遮罩样式 render.background-style**（gray-mask 原版默认 / blur-mask 模糊遮罩，全部卡片统一生效，见 3.5） |
| ~~在线时长同步~~ | 已于 1.6.6.1 覆盖版**删除**（1.6.4 曾引入 `PlayTimeSync.kt`：检测到 PlayTimeManager + PAPI 时差值对齐，因用户要求移除；本插件自身在线时长统计不受影响，PapiDependencyCheck 提示面同步收窄为仅 PlayerTitle） |
| 查在线/在线排行榜/财富榜 | `PublicCommands.kt` + `LeaderboardCommands.kt` + `CoinsLeaderboardCommands.kt` + `render/OnlineListRenderer.kt`/`LeaderboardRenderer.kt` | 图片分段发送（每页 15 人）、命令冷却 `render.command-cooldown-seconds`、内置背景图（backgrounds/，img/ 可自定义随机） |
| /查性能（1.6.7） | `PublicCommands.kt` → `spigot/stats/PerformanceService.kt` + `render/PerformanceRenderer.kt` | 性能监控卡片（**1.6.7.8 起固定五项 TPS/MSPT/内存/CPU/在线人数（仅人数），末行单卡整行通栏，高 1034 不变；1.6.7.6 曾有的「延迟」项 1.6.7.8 整体移除——玩家 Ping 只反映客户端到服务器连接往返，本地/同机恒 0ms 只代表服务器本地网络，无实际监控意义；快照无 avgPingMillis 字段、capture 四参工厂、spark playerPing 不再轮询**，null 显示「--」，**1.6.7.5 覆盖版：半透明面板与其他卡片同款 alpha（头部卡白 34/容器黑 120/指标卡白 16/胶囊白 30，无描边）**）；快照走 `KERONG.performanceSnapshot()`（**1.6.7.2 引入 / 1.6.7.5 修复反射链：装了 spark 时优先 SparkBridge 反射调其公开 API，方法一律从公共接口解析（实现类包私有，从实现类取 Method invoke 抛 IllegalAccessException 全指标被吞——1.6.7.5 前的暗坑）**——SparkProvider.get() 单例→tps().poll(TicksPerSecond.MINUTES_1)/mspt().poll(MillisPerTick.SECONDS_10).mean()/cpuProcess().poll(CpuUsage.MINUTES_1)（**1.6.7.8 起不再轮询 playerPing**），Spigot 端 getPlugin("spark") 启用才读、NF 端 ModList.isLoaded；无 spark 单项回退自研分层反射链，capture 新增三参重载接 cpuOverride；**1.6.7.3 新增 / 1.6.7.4 重做：启动接入自检提示 SparkStartupCheck**（挂载时机改服务器启动成功后——Spigot ServerLoadEvent、NF ServerStarted，probe lambda 返回三态 ProbeOutcome：未安装静默 / 已接入且 commands.查性能 开启才 infoAndKeep 正向确认（查询关闭仅文件留档）/ 已装但失败 warnAndKeep 必提示带原因与回退说明））；性能数据缓存 render.performance.cache-seconds（覆盖版默认 60 秒，PerformanceService peekCache/storeCache）；命令开关 commands.查性能，背景 render.performance.builtin-background；**覆盖版：液态玻璃样式已移除**（render.performance.style 入废弃键表自动清理），全局透明度 render.global-opacity（GlobalOpacity.apply 四渲染器统一接入） |
| /查在线 markdown | `markdown.queryOnline` → online.md | 关图片走模板文本 |
| /motd | `PublicCommands.motd`（无参=本服状态 server-status 模板）→ **带参转发 `MotdCommands`**（minebbs API，motd-* 模板） | 1.6.3 修复了转发缺失导致 MotdCommands 永不可达 |
| AI 对话 | `spigot/qqbind/AiChat.kt` + `QClient.replyMarkdownWithReference` | @机器人触发；引用回复（MessageReference 协议）失败三级回退：引用→普通被动→纯文本；全局一份上下文 |
| 快捷面板 | `cn/kerong/bot/MenuManager.kt` | QQ 官方面板 API；1.6.7.10 起五重防护：**先删后建**（deleteAllPanels 删除全部旧面板含非本插件残留 → createPanelWithLimitRecovery 创建）+ **按钮上限裁剪**（trimToPlatformLimit：官方限制单面板最多 20 个元素，PANEL_ITEMS 21 项全功能开启必超限——30013 的真根因；按 PANEL_TRIM_ORDER 优先裁低频管理按钮，被裁命令仍可手输、日志如实列出）+ **30013 退避重试**（2.5s/5s 递增后清空重建，旧版三连发同秒全挂）+ **创建 QPM 节流**（官方 10 次/分钟，lastCreatePostAt 全局 6.5s 最小间隔）+ **列表分页**（next_cursor/is_end，查询失败返回 null 与「确认无面板」空表区分）；结果结构化返回（syncGroupPanelsWithResult → PanelSyncResult，旧 void syncGroupPanels 保留兼容桥——互转版 QClient 旧编译产物零重编直接链接）；错误码归一化（30009/30013/30020 双形态）+ describePanelError 人话 + limitHintOf 转出平台 limit 字段；按钮 name≤14/desc≤30 单位预校验；未装 Vault 隐藏财富榜按钮；调用方必须在异步线程（含退避等待最坏 20 秒级；/kerong panel 已由命令层包 submitAsync） |
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
- **/kerong chs**：手动重连（重置全部计数→QClient.resumeConnection；socket 不可达时回退完整重启）。Spigot：KERONGCommand+plugin.yml（命令名 kerong，**1.6.7.7 起 aliases 已删除**，权限 kerong.command）；NeoForge：NeoForgeCommands 主命令 literal（**1.6.7.7 起 /hb 别名已移除，主命令唯一 /kerong**）。
- 看门狗/兜底重连在挂起期间凭 `ReconnectManager.isSuspended()` 停连。

### 3.3 双服务器同绑一个机器人（1.6.1~1.6.6.2 检测，**1.6.6.3 整体移除**，务必理解）

**1.6.6.3 决策：双服同绑检测已全部移除（ConflictWatch 类删除、WssDiagnostics 证据计数删除、QClient.stopForConflict 删除、回复路径接入点删除）**。原因：平台网关抖动（会话无效 op 9 / create session error 4903 / 网关定期踢线 1000）引起的重连风暴会触发「短命会话连续 3 次」判定，向管理员误报「已有另一台服务器绑定了同一个机器人」——实测单服务器连接时也必然误报，误导排查方向。移除后重连风暴按本来面目（网络 / 平台问题）记录与重试。

历史背景（理解 40054005 修复链路仍有价值）：QQ 同一 AppID 的第二条连接**不总是被立刻踢**，两台同时在线时被动回复去重键=(msg_id, msg_seq) 双方都从 1 起→后发的被 40054005 拒收（1.6.0/1.6.1「回复图片消息失败」的根因之一）。

**ReplySeqGuard（1.6.2，保留至今——去重修复本体）**：GroupMessageHandler 入口按 msg_id 分配回复序号基数（第 1 次=1，第 N 次重投递=1+(N-1)×100000），防网关弱网/会话恢复重投递到**本服**时的序号重复。**遇到 40054005 时先想到 ReplySeqGuard（已防住本服重投递）**：若仍被拒收，可能是双服同绑，但不再自动推断——由管理员自行核对是否有多台服务器填了同一凭据。

### 3.4 更新检查（1.6.2，1.6.4 彩色，1.6.7.9 镜像回退）

`bot/UpdateChecker.kt`：启动后异步 8 秒多源依次 GET（1.6.7.9 起）——官网域名 `https://kerong.xin/penguin/version.json`（主源）→ 内置镜像 `https://156.233.228.248/penguin/version.json`（官网服务器 IP 直连，Host 头 `kerong.xin` 路由同站，镜像请求放行证书域名比对（证书链仍按系统 CA 校验）），镜像命中仅文件留档；逐段数字比较版本；有新版→控制台输出带 § 颜色码的版本号/下载页/最多 6 行简要日志；全部源失败→控制台一句话 + 关闭指引（update-check.enabled: false），细节进日志。`update-check.enabled` 可关；`update-check.mirror-url`（1.6.7.9 新增）自定义镜像，off/false/none = 关闭回退只试主源。

### 3.5 渲染管线与头像获取（1.6.4 头像修复，1.6.5 内存与性能，1.6.6 多线路与落盘，1.6.6.1 覆盖版：本地优先 + 缓存配置化 + MinePic，1.6.6.2：缓存分层 + 空闲释放 + 失败分类 + 获取方式日志，1.6.6.2 覆盖版：日志去重 + 单飞 + /kerong mem，1.6.6.4：移除空闲 GC 提示，1.6.7：新增性能卡片渲染器 PerformanceRenderer——毛玻璃 2×N 指标卡，与在线列表共用背景/字体，画布 1350 宽高度按可见项数计算，PNG 写出后 flush）
> **1.6.9.4 背景（遮罩）样式**：`render.background-style`（gray-mask 默认原版灰色遮罩 / blur-mask 模糊遮罩——参考 EasyBot 液态玻璃模板观感）。全部卡片统一走 InfoCardAssets.applyBackgroundStyle（blur 时先 blurImage 再 darkOverlay；内置图本就不叠遮罩、blur 时仅模糊）；blurImage = 降采样 1/8（BLUR_DOWNSCALE）→ 两趟可分离盒式模糊（BLUR_BOX_RADIUS=3，整数滑动窗口）→ 双线性放大，等效约 40px 高斯柔化、毫秒级；样式标签（backgroundStyleTag）进 processedBackground（文件路径#标签）与 processedBuiltinBackground（builtin:资源#尺寸#标签）缓存键，reload 切样式自动重新处理互不污染。

CPU 渲染（CardRenderPool 共享线程池，核数一半 1~4 线程）、结果缓存、背景图内置+随机 LRU、长图封顶 1600px、子采样解码防 OOM。图片发送：`replyWithImgBytes`（base64 上传），读超时（SDK OkHttp 10 秒 < QQ 富媒体普遍 10 秒+应答）**不算失败**（图片大概率已投递，1.6.1）；ret 2022021x=已受理算成功。**1.6.5 内存策略**：确定性 flush（单次画布 / 中间图 / 缓存淘汰 / clearCaches 均在失去引用前 flush，剩余堆内存交给常规新生代 GC，System.gc 与 gc-after-render 已废除）。**1.6.6.1 覆盖版内存再优化**：三渲染器圆角头像零中间图直绘（共享 `InfoCardRenderer.drawRoundedAvatar`）；内置背景处理高度统一封顶 1600px。**1.6.6.2 缓存与内存策略**：①头像内存缓存上限 64→**128 条**，并新增 **AVATAR_MEMORY_MAX_BYTES = 10KB 单张门槛**——PNG 编码后超 10KB 的大头像只写磁盘缓存不进内存（`cacheAndStoreAvatar`：`encodeAvatarPng` 编码一次，磁盘写入与内存门槛共用；磁盘缓存关闭时退回全量内存缓存；磁盘命中 `DiskAvatar` 带文件字节数超门槛不回填）；②**渲染空闲内存释放**：`render.idle-release-minutes`（默认 5，支持小数，0 = 关闭）——CardRenderPool 任务收尾（含异常收尾）刷新 `markRenderCompleted` 时间戳 + `activeRenders` 活跃计数护栏，render-idle-releaser 守护线程每 30 秒检查，距上次渲染完成超阈值且无进行中渲染时 flush + 清空 avatar/background/builtin 三类图像缓存并重置计时，missCache / premiumUuidCache 轻量元数据保留，释放动作写日志；③内存缓存及时清理：sweepDiskCache 每轮开头 `purgeMemoryAvatarCache` 清超 TTL（10 分钟）条目，仅无活跃渲染时执行；④磁盘缓存强制关机后可清：`ensureAvatarSweeper` 双端主类启动即调用（首轮 10 秒后清停服/强关期间过期文件）。

**头像链路**（`InfoCardRenderer.kt` 的 InfoCardAssets）：内存缓存（10 分钟，上限 128 条，**1.6.6.2 起只存 PNG ≤ 10KB 的小头像**；超时条目由清扫线程每轮顺带清除）→ **磁盘缓存**（1.6.6 新增，1.6.6.1 有效期默认 3 小时且可配置：`render.avatar-cache-hours`（RenderSettings.avatarCacheHours 实时读，支持小数，0 = 关闭不读不写），插件版 plugins/KERONGPenguin/cache/avatars/、模组版 kerongpenguin/cache/avatars/，512 文件上限，**超 10KB 大头像只存这里不进内存**，重启后免重复请求；过期文件由 avatar-cache-sweeper 守护线程每 30 分钟清扫删除（首轮启动 10 秒后，**双端主类启动时即启动该线程（1.6.6.2，强制关机后残留过期文件下次启动 10 秒内清掉，不依赖运行期状态）**），查询命中过期文件时也顺手删）→ **按 `render.avatar-fetch-priority` 决定本地/外置先后（1.6.6.1 覆盖版新增，RenderSettings.avatarFetchPriority 实时读，local/external 不区分大小写，无效值按 local）**：
- **本地方式（默认 local，先试）**：本服已有数据解析皮肤贴图 URL（在线玩家 Profile（含 SkinsRestorer 修改，主线程捕获进 AvatarRequest.skinUrl）→ 本服 playerdata NBT（extractNbtSkinUrl，离线服+皮肤插件场景））→ 下载贴图 PNG（textures.minecraft.net，服务器不存皮肤文件本身这是物理上限，但不走任何账号 API）→ cropFace 裁脸；
- **外置方式（1.6.8 起顺序反转）**：**外部多线路先行**（MC-Heads → Minotar → **MinePic（minepic.org/avatar/<无横线UUID或名字>/216，实测 1.7~6s 波动大、未知账号不响应按失败处理，超时 3 秒）** → Crafatar 按实测速度排序；基岩玩家（Floodgate UUID：msb 全 0 + lsb 高 16 位 0x0009，低 48 位为 xuid）走 MC-Heads xuid 线路 `https://mc-heads.net/avatar/xuid:<xuid>/216`；名字线路 MC-Heads → Minotar → MinePic；生产实测 2026-10：MC-Heads 0.4s / Minotar 0.5s / MinePic 1.7~6s / Crafatar 5.8s，LittleSkin 与 mc-api.io 不可达弃用）→ **Mojang 原生官方 API 移到最后兜底**（会话服务器 v4 直查 + 玩家名反查，网络冷却见下；1.6.8 前官方排首位——国内服务器访问官方普遍超时（单玩家最多 3+5 秒），排行榜 4 线程 × 8 秒预热预算全烧在逐玩家官方超时上、预算耗尽整批头像被取消，用户实测「排行榜大多数头像都没显示」，遂反转顺序；官方链路构件全部保留只是移位）；
- 先试的一方失败自动切换另一方（external 模式 = 外置全败后再试本地）。**1.6.6.2 失败分类**（修复「极少玩家在清除缓存后头像获取失败」：磁盘缓存整批到期→集中重取风暴→Mojang 429/网络级失败进 5 分钟全局冷却→风暴尾部玩家失败被写入 missCache 60 秒冷却重试无门）：`networkTrouble` ThreadLocal 逐次尝试标记（httpGet/httpGetWithStatus 网络异常、官方冷却期内跳过、markMojangUnreachable 429/5xx 均置位，fetchAvatar 开头重置），**网络抖动型失败不写 missCache，下次查询立即重试；确定性失败（404 等）才写冷却**。
- **并发获取单飞（1.6.6.2 覆盖版）**：fetchAvatar 在网络获取段外裹 `inFlightFetches`（key → FetchLatch 门闩）——第一个到达的请求是执行者（真正走网络，finally 里移除门闩并 countDown），并发到达的其余请求是等待者（最多等 8 秒，直接取执行者结果经门闩传递绕过 10KB 内存门槛）。启动 / 刷屏场景多张渲染并发预热同一批头像时，网络请求与分配量按并发数成倍削减（启动期内存飙升的放大器拆除）。
- **获取方式日志（1.6.6.2；覆盖版改文件日志 + 会话去重）**：成功获取（含磁盘命中）记 `[头像] 玩家名 ← 方式`（本地皮肤贴图（在线Profile/存档NBT）/ Mojang官方API / MC-Heads（含基岩 xuid 与名字线路）/ Minotar / MinePic / Crafatar / 本地磁盘缓存（NKB）），失败记一行含是否可重试；**只写 PluginFileLog（logs/qq/qq-bind-日期.log）不进控制台**（覆盖版修复泄漏到控制台刷屏），**同一玩家首次获取与换线路时才记录**（loggedAvatarSources 会话去重表，上限 2048，clearCaches 清空）；内存缓存命中不记（非获取动作）。排查某玩家头像链路看日志文件即可。

**1.6.4 关键修复：Mojang 官方接口网络级失败（超时/IO/429/5xx）开启 5 分钟全局冷却**——「大部分玩家头像获取失败」的根因是服务器到 Mojang 的线路不稳，旧版每个玩家都先等 5 秒超时才能失败，在线列表 8 秒预热预算全部耗尽；冷却期内直接跳过官方链路（走外部线路或诚实失败），不再逐玩家等超时。会话服务器超时也从 5 秒降到 3 秒。离线服无皮肤数据的玩家仍诚实画占位块（不做「人人史蒂夫」假回退）。

### 3.6 配置系统（1.6.7.6 增语法错误三级自愈：`ConfigBrokenRecovery`）

磁盘 config.yml 写坏（最高频：`key:value` 冒号后漏空格）时：①严格解析（`YamlConfiguration().load(Reader)` 抛出可感知）→ ②缺空格逐行修复（可解析则备份 config.yml.error-<时间戳> 后写回修复版）→ ③修不了的备份原文件 + 从 JAR 释放默认配置继续启动（控制台醒目指引从备份填回自定义设置）——替代旧版「空配置硬跑 → 机器人不启动」死胡同。挂载点：ConfigManager.initialize 与 reload 双处。

`ConfigManager.kt`（共享源码）：DEFAULT_VALUES 全量默认 + `ConfigUpgrader.fillMissing` 补齐缺失键 + `CONFIG_VERSION_PATH` 版本迁移链（**当前 config-version 19**：16→17 render/leaderboard 顶层化，17→18 金币榜→财富榜，18→19 from-game 默认带玩家名 + gc-after-render 废除清理）。修改配置一律重启生效（无热重载指令，教程与注释已如此说明）。

### 3.7 QQ 绑定拦截策略（1.6.4 双端统一）

未绑定玩家分两段拦截（开启 qq-bind.enabled 后）：
- **老玩家**（服务器已有档案：Spigot 用 `hasPlayedBefore()` + `playerdata/<uuid>.dat` 双判定；NeoForge 用 `playerdata/<uuid>.dat` 文件判定）→ **进入游戏之前**拦截：Spigot 在 `PlayerLoginEvent.disallow`；NeoForge 在**配置阶段**断开（`BindLoginGate.kt`：mod 总线配置阶段事件（20.5+ RegisterConfigurationTasksEvent / 20.4 OnGameConfigurationEvent，运行时 Class.forName 探测）注册自定义 ConfigurationTask，任务 start() 时 listener.disconnect(绑定提示 Component)；**1.6.7.11 起监听器经反射 IEventBus.addListener(Class, Consumer) 以具体事件类精确注册——不能用 @SubscribeEvent 基类 Event 监听（mod 总线 markerType(IModBusEvent) 校验必拒）**）。显示路径等价原版白名单/封禁——绑定码直接显示在“无法连接到服务器”界面，不会被加入流程/“服务器连接失败”覆盖。
- **新玩家**（没档案）→ 放行完成加入（服务器建档 UUID/玩家数据），随后踢出展示绑定码：Spigot `GameChat.enforceJoinBinding`（PlayerJoinEvent 内直接 kickPlayer）；NeoForge `NeoForgeEvents.enforceLoginBinding`（加入后 1 tick disconnect）。
- **兜底链**：NeoForge 闸门任何环节异常（mod 总线挂载失败/反射失败/管理器未就绪）一律放行，由加入后踢出兜底；Spigot 登录期未拦到（如登录时管理器未就绪）也由加入后踢出兜底。
- **NeoForge 关键事实**：1.21.1 无登录期玩家事件；PlayerLoggedInEvent 在 placeNewPlayer 末尾（全太晚）；ConfigurationTask.start(Consumer<Packet<?>>) 的参数是**发包器**，任务完成靠监听器响应包驱动（finishCurrentTask）——所以闸门采用“注册时判定、仅拦截时注册任务、任务 start 直接断开”的模式（放行时零干扰，拦截时无需完成任务）。

### 3.8 spark 探测全景与性能读数边界（1.6.7.6 覆盖版，实测结论）

- **Paper 1.21+ 系核心（Paper/Purpur/Folia 1.21 及 26.x）预捆绑 spark**（Paper 官方文档 docs.papermc.io/paper/profiling：「Starting with 1.21, Paper bundles the spark profiler」；启动日志可见 `[spark] This server bundles the spark profiler`）。**内置 spark 不以插件形式注册**——`Bukkit.getPluginManager().getPlugin("spark")` 永远 null（沙盒 Paper 26.2 实测复现：旧版 probeSpark 完全静默 = 用户「装了 spark 检测不到」的根因）；**且默认忽略 plugins 目录自装的 spark JAR**（想用自装新版需 `-Dpaper.preferSparkPlugin=true`，见 Paper 文档）。**Modrinth 的 spark 0/119 版本无 Spigot 变体**（全是 NeoForge/Fabric/Forge）——用户从 Modrinth 下载的 spark 文件放进 plugins 无法被 Bukkit 加载；Bukkit 插件版唯一分发渠道是 spark 官网 spark.lucko.me（Bukkit 下载位）与 SpigotMC 资源页。
- **探测链（覆盖版后，五态）**：① getPlugin("spark") 命中 → 原三/四态逻辑；② 插件表查无 → `SparkBridge.bundledSparkClassLoader(bukkitApiLoader())`（候选链：Bukkit API loader → 本类 loader → 线程 context loader，逐个 Class.forName(me.lucko.spark.api.SparkProvider)+get() 试探）→ 命中即 Installed(bundled=true)（实测 Paper 26.2：「已接入 spark（来源：服务器核心内置，可读项：TPS / MSPT / CPU）」）；③ 内置也无 → `findStraySparkFiles()` 扫 plugins 目录 spark 字样 jar → NotLoaded(fileNames)（warning 引导模组变体/官网渠道/Paper 已内置三要点）；④ 全无 → NotInstalled 静默。查询链 takePerformanceSnapshot 同步走 ①/②（loader 选择一致），否则 Purpur 26.2 上 /查性能 永远用不上 spark。
- **TPS 读数语义边界**：MC 1.21.2+ 引入 tick 加速（`/tick sprint` 命令与后台任务自动 sprint；控制台出现 `The game is sprinting`）。**加速期间 getTPS()/recentTps 如实反映真实 tick 率**（实测 `/tick sprint 12000` 后 Paper `/tps` 输出 `219.8`——用户「TPS 飙到 300 多」即此，加速更长可达更高）。TPS 卡语义上限 20 → `PerformanceSnapshot.capture` 全参工厂统一 `clampTps`（>20→20、NaN→null、<0→0）——所有链路（spark/反射/采样器/换算）汇入 capture 一处生效，双端共享；MSPT 为每刻实际耗时不受加速影响。
- **凭据空白坑**：YAML 引号内尾随空格保留（plain scalar 会被 strip，`"1905828670 "` 不会）→ AppID 带空格拼请求 → QQ 开放平台 `{"code":100007,"message":"appid invalid"}`（200 响应体带 code）→ SDK 一串 401/parse error/装配异常连锁。**诊断特征**：启动日志「正在后台启动 QQ 机器人客户端（AppID: 1905828670 ）」——代码模板 `$appId）` 无空格而输出有空格即凭据带空白。修复：ConfigManager.botAppId/botSecret → sanitizeCredential/trimInvisible（全角空格 U+3000/NBSP U+00A0/零宽 U+200B~U+200D/U+2060/BOM U+FEFF 等），清洗发生即 warning 知情提示；QqTokenHealth 增 interpretTokenErrorCode（100007 → AppID 无效含空白指引；100016/100213 → 凭据不匹配重置指引）。
- **沙盒实测环境备注**：MC 26.x 需要 **Java 25**（Paper 启动硬检查，`Minecraft 26.1 and newer requires running the server with Java 25 or above`）；Paper 26.2 从 fill.papermc.io/v3 API 下载（旧 v2 API 已 sunset）；PurpurMC API 时断时续且 ~10KB/s，Purpur 实测可用 Paper 26.2 同源替代（bundled spark 机制相同）。

---


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

1. **版本号六处**（1.6.8 起修订——双 toml 都在 META-INF 下，1.6.8 曾漏改 `META-INF/mods.toml` 由门禁 V3 抓回）：根 `build.gradle.kts`、`server-Spigot/src/main/resources/plugin.yml`、`server-NeoForge/build.gradle.kts`、`server-NeoForge/src/main/resources/META-INF/neoforge.mods.toml`、`server-NeoForge/src/main/resources/META-INF/mods.toml`、`KERONGNeoForge.kt` 的 `MOD_VERSION`（+README 当前版本行）。
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

- **Paper 1.21+/26.x 内置 spark 不注册插件表**（1.6.7.6 覆盖版实测）：getPlugin("spark") null ≠ 没有 spark——先试 bundledSparkClassLoader 再下结论；这些核心还默认忽略 plugins 自装 spark JAR（-Dpaper.preferSparkPlugin=true 才用自装）；Modrinth 的 spark 无 Spigot 变体（用户装的常是模组版）。MC 26.x 服务器要 Java 25。
- **YAML 引号内空白 + MC 1.21.2+ tick sprint**（1.6.7.6 覆盖版）：① YAML 引号内的尾随空格/零宽字符不会被 strip，凭据带空白 → QQ 平台 100007（日志模板无空格而输出有空格即铁证）；② tick sprint 期间 getTPS 反映真实 tick 率（可达数百），TPS 类展示一律封顶 20。
- **javap 三坑**（1.6.8 门禁实战）：① 类文件平铺放置（文件名 ≠ 包路径）时 javap 虽能输出但常量池被降级截断（85 条 vs 全量 821 行）——必须按包路径展开 + 全限定名调用；② javap -v 常量池行格式是 `#N = String #M // 内容`（注释无 "String" 字样），URL 模板等常量只在 Utf8 条目出现——字符串断言要同时收 `#N = Utf8 内容` 与代码区 `// String 内容` 两形态；且 Kotlin 字符串模板编译为 \u0001 占位常量、javap 显示为字面量转义文本（Python 期望值要双反斜杠）；③ toml 注释行合法提及历史损坏段头 `[[ods]]`——段头断言必须在非注释行上做，否则必误报（1.6.7.10 的 B6 检查就栽在这）。
- **官网 index.html 整文件覆盖回滚**（2026-11-01 实锤）：0.3AI v0.5 上线时用了 1.6.7.10 时代的旧底稿整文件上传，把前一天已上线的 1.6.7.11 主下载框/历史版本表/更新日志整体回滚成 1.6.7.10（version.json/双 JAR/源码包不受影响，游戏内更新提示照常，仅首页展示错版本）——**改官网任何文件前必须先从线上拉当前版作底稿**，上传后 curl 复核版本号三处（主下载框/历史表首行/更新日志首段）再收工。

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
| /kerong reload 边界（1.6.9.4 核实） | 绝大多数配置 reload 即时生效（实时读取）；仅 bot.app-id/bot.secret（QQ 客户端启动时连接一次，/kerong chs 也用旧凭据）与 update-check（启动后第 8 秒一次）需重启；面板按钮改完用 /kerong panel 手动同步。历史行「已移除该概念：改配置一律重启」为过时记录（1.6.9.4 已修正 config 头部误导文案） |
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
| 宣称未实装（第三次） | 1.6.7.9 声称「mods.toml 段头笔误修复」但源码与线上 JAR 字节级核实仍是损坏的 [[ods]]（缺字母 m，FML 解析失败模组无法加载）——与 1.6.6 覆盖版 TPS 封顶同病；**宣称修复前必须对产物做字节级核对**（toml 断言用文件字节 5b5b6d6f64735d5d，不能照抄屏幕显示） |
| 终端显示吞 [[mods]] | 工具输出管道把 `[[mods]]` 显示成 `[ods]]`（吞掉 `[m`，与既有记录的 `b]` 吞转义同源）——toml/字节断言一律以文件字节为准（hexdump / ord 逐字节 / tomllib 解析），屏幕显示不可信 |
| 互转版签名兼容 | MenuManager 公开方法改签名前先想互转版：其 QClient 旧编译产物按 (Starter;List)V 描述符调用，主版改返回值类型会让互转版 NoSuchMethodError——保留 void 兼容桥 + 新结果方法（syncGroupPanelsWithResult）是互转版精准覆写工作流的前提 |
| mod 总线 @SubscribeEvent 参数约束 | FML mod 总线以 markerType(IModBusEvent) 构造：监听参数必须实现 IModBusEvent，事件基类 Event 直接被拒（"not valid for this bus"）；而参数还必须是 Event 子类型且是目标事件类超类——跨版本（编译目标 20.4、运行 20.4~21.11）下不存在满足全部条件的公共类型，**跨版本 mod 总线监听只能反射 addListener(Class, Consumer) 按具体事件类注册**（1.6.7.11 教训，1.6.7.9~1.6.7.10 闸门因此从未挂上） |
| 冒烟必须覆盖真实总线注册 | smoke1679 只测了「事件类 Class.forName 探测」没在真实 EventBus 上走注册+分发——mod 总线校验类异常完全漏网（宣称跨版本事件桥可用实则必炸）。**凡涉及事件总线挂载的改动，冒烟必须在按 FML 方式（markerType(IModBusEvent)+allowPerPhasePost）构造的真实总线上端到端跑注册→post→消费器收到，并加负向对照复现原始故障**（smoke16711 G 组范式：BusBuilder 构造 + 代理 ServerConfigurationPacketListener + 私有反射缓存字段断言） |
| Write 工具损坏 \uXXXX 转义 | Write 工具会把源码里的 `\u003C` 之类转义序列写成 NUL 字节（0x00），含特殊序列（尖括号标签/转义）的文件必须用 Python 字节级写入（chr(92) 拼反斜杠），Bash heredoc 传 Python 可安全保留（0.3AI v0.4 实测：THINK 标签常量两次被损坏） |
| Kotlin 块注释支持嵌套 | 注释里出现 `text/*` 这类序列会开一个**嵌套注释**，把后面的代码全吞进注释——编译报「Expecting a top level declaration」且行号诡异（报在几十行之后）；注释里写 glob/路径时避免 `/*` 相邻序列（0.3AI v0.4 WebCrawler 实测） |
| 冒烟 JVM 需补服务器自带依赖 | paper-api 的 MemorySection.set 需要 guava、YamlConfiguration 需要 adventure——真实服务器自带但独立 JVM 冒烟要手动加 classpath，否则 save **静默失败**（被 Kotlin catch 吞掉，文件根本不落盘）；YamlConfiguration 实例 load(File) 对真损坏 YAML 正常抛异常，但「:::xxx」其实是合法 YAML 标量——冒烟要用「messages: [unclosed」这类真坏样本 |
| gh-proxy 不转发 push 鉴权 | gh-proxy 的 clone/ls-remote 可用，但 push 时 GitHub 收到匿名请求报「No anonymous write access」——**push 一律走官网服务器中继**（bundle→bare.git→带 PAT 直连，服务器可直连 GitHub）；含 token 的脚本用后即删（0.3AI v0.4 会话实测） |
| Edit 工具改长中文注释易匹配失败 | 长中文注释块存在不可见字符差异（全半角/相似码位），Edit/MultiEdit 的 old_str 精确匹配易失败且报错信息不指明差异；批量改中文注释/配置块用 Python 脚本按锚点行+正则处理（幂等设计），单点小片段仍可用 Edit |
| Write 工具吞标签字面量（0.3AI v0.5） | Write 对完整标签形态的字面量（think 标签等）会把标签内容吞掉只留引号——与反斜杠u转义变 NUL 同源的第二种损坏形态；修复必须 Python 字节级写入，改完 NUL+repr 双重复核 |
| 正则改组后 groupValues 索引不同步 | 正则增加捕获组（如表情标记加分隔符组）后关键词组号变化，解析代码仍读旧组号→静默返回空——正则分组变更必须同步检查全部 groupValues 索引 |
| 快速守卫与新语法脱节 | 函数入口的 contains 快速守卫（要求完整「[表情]」）挡住了冒号新形态「[表情:x]」——守卫条件要放宽到公共前缀，冒烟必须覆盖每种语法形态 |
| 中缀运算符优先级 | 「1 until 1L shl 40」左结合解析为「(1 until 1L) shl 40」报 unresolved reference——until 与位运算混用必须加括号 |
| grep -c 打断命令链 | 管道接 grep -c 无匹配时 exit 1 会中断后续 and-and 链——错误计数检查要单独执行 |
| Python set 的 in 是成员判定 | `"子串" in some_set` 是**精确成员判定**不是子串包含——单词条常量（如 javap 常量池里的「已读取」）恰好相等碰巧能用，长常量（如「最大 \u0001 人（MOTD 展示值）…」）必误报；常量池断言查子串要用 `any(sub in c for c in consts)`（verify_jar_1693 实测 7 项假 FAIL 部分源于此） |
| Kotlin const val 调用点内联 | `const val` 编译期直接内联到调用点 ldc，字节码**无字段引用**——断言「服务常量被引用」要查调用方反汇编里的 `// String 字面量`（FETCH_FAILED_TEXT 曾按字段引用断言误报），服务类常量池里该字符串仍然在位 |
| NF 模块资源副本陷阱 | `server-NeoForge/src/main/resources/` 有自己的一份 Markdown/ 全量副本（NF 打包脚本从模块 jar 合入）——**删模板资源时双份都要删**，只删 common-Bot 会让 NF 分发 JAR 残留旧资源（CD1 抓回）；改 help.md 同样要同步两份 |
| javap 不显示 makeConcatWithConstants 配方 | Kotlin 字符串模板编译为 invokedynamic makeConcatWithConstants，recipe 常量（"\u0001.项目名称"）在 BootstrapMethods 属性里——`javap -c` 代码区不可见，只有 `-v` 的 Utf8 条目才有；模板形态断言一律用 -v 的常量池 |
| Kotlin internal 方法字节码带模块后缀 | `internal fun` 编译后方法名是 `cardHeight$common_Bot`（Spigot 产物）/`cardHeight$server_NeoForge`（NF 产物）——**同一源码在两模块产物后缀不同**，反射调用要按前缀 `startsWith("cardHeight")` 动态查找，写死任一后缀必在另一端 NoSuchMethod（1.6.9.4 冒烟实测） |
| javap -v 常量池中文是 Unicode 转义 | javap -v 对常量池里的中文字符串输出 `\u6d88\u606f` 形态（非原文字符）——中文字面量断言必须按**类文件 UTF-8 字节**搜（BMP 区 modified UTF-8 与标准 UTF-8 兼容），搜 javap 输出文本必假 FAIL（1.6.9.4 门禁 MF3 实测；英文常量两形态收集法仍然适用） |
| ssh.py 用法 | `scripts/ssh.py` 把**全部参数拼成命令**执行（无 run 子命令）——写 `ssh.py run "cmd"` 会变成执行 `run` 报 127；正确用法 `python3 scripts/ssh.py "cmd"`；命令串里带 `;` 时 `run xxx; yyy` 后半段仍会执行（首查 version.json 曾因此静默漏跑） |

---

## 7. 当前已知状态（2026-11-01 六次会话，v1.6.9.4 完成）

- **v1.6.9.4 已发布上线**（双 JAR / version.json / index.html / tutorial.html 全部在线验证通过，git 远端 main=a68514f 已推送直连复核）。四项需求全落地：
- **背景遮罩样式**：RenderSettings 新增 BackgroundStyle 枚举 + backgroundStyle()（blur-mask/blur/glass→BLUR_MASK；乱值/留空→GRAY_MASK 兜底）；InfoCardAssets 新增 applyBackgroundStyle/blurImage/boxBlur/boxBlurAxis/backgroundStyleTag 五方法；样式进双缓存键（随机图 `路径#gray|blur`、内置图 `builtin:资源#WxH#gray|blur`）；内置图 blur 时只模糊不叠暗色遮罩（保持「设计自带可读性不叠遮罩」的原语义）；配置版本 24→25（DEFAULT_VALUES 补 render.background-style=gray-mask）。视觉复核：VisualCompare1694（彩色渐变+色块+高频细纹测试图）数值验证 blur 局部方差降 96.7%、边缘强度降 61.6%、亮度一致（暗色遮罩等效）。
- **合并转发防刷屏**：QQ 官方文档 MsgElement.message_type=102=聊天记录；实测（zaofan-make/dsh-qqbot#8）QQ 服务端把合并记录渲染成「=== 消息 N ===/[消息内容]/[发送者]/[消息类型] 合并转发消息/[关联消息]/--- 第N条 ---」多行模板串整段下发（嵌套层用 --- 第N条 ---）；detectMergedForward 双通道判定 + 双正则取最大条数；占位「[聊天记录 共N条]」与 [图片]/[语音]/[视频] 同风格。
- **reload 边界**：ConfigManager.reload() 重读配置+fillMissing+版本迁移、reloadRuntimeConfig() 重建 Markdown 模板+MessageTemplates+自定义命令、全部 getter 实时读 plugin.config（groupOpenIds/commandSwitches/chatFormat/playerEventFormat/RenderSettings.*/variable-fetch 逐查询读取）；唯一不能 reload 的两类：QQ 凭据（launchQqClient 仅启动一次，chs=resumeConnection 用旧会话）与 update-check（scheduleUpdateCheck 启动后 8 秒一次）；面板不随 reload（/kerong panel 手动同步）。写入位置：config.yml 头部总说明+bot 凭据段+update-check 段+commands 段（双端源+JAR 内置）、双端 /kerong reload 完成反馈、双端 help 行、官网教程技术卡「哪些配置不能用 /kerong reload」一节。
- **异步化审查**：已异步链路清单（五卡片 CardRenderPool、游戏→QQ OutboxQueue、进出服播报 OutboxQueue、游戏内 AI FoliaCompat.runAsync、PlayerStatsManager 保存 submitAsync、头像清扫后台线程、UpdateChecker 独立线程、面板同步 1.6.7.10 起、命令执行 CompletableFuture、敏感词审核 1.5.4.5 起）；本轮修复：handleAiChat 整体 submitAsync（原 AiChat.chat 在 WSS 读取线程同步跑 HTTP，AI 慢时后续群消息/命令全排队）+ /kerong reload 双端 submitAsync（原在主线程做配置读写/损坏恢复/模板释放/补齐保存）。
- 冒烟基建：smoke1694/FakeKeronRuntime（继承 NF 兼容层 JavaPlugin + 实现 cn.kerong.bot.KERONG 接口——Java 实现 Kotlin interface 的 val 生成 getX()，五 provider 19 成员全 stub；注册 BotShared.setInstance 后 RenderSettings 的「BotShared.instance as? JavaPlugin」配置通路在冒烟 JVM 端到端打通）。
- 本轮踩坑三枚已入 §6：NF 打包 base 误传 NF JAR（shaded 类被当旧字节码剔除 19MB→6MB，已有旧坑条目重演——**看产物体积骤降要立刻起疑**）；internal 方法模块后缀；javap 中文 Unicode 转义。
- 环境注：本会话 tooling 位于 /tmp/my-project/tooling（JDK21/Gradle home/deps），经符号链接 /home/z/my-project/tooling 挂回（gradle.properties 的 java.installations.paths 指向 /home/z 路径）；首次 gradle 启动约 80 秒；git push 直连 github.com 成功（本轮未走服务器中继）。

## 7.（上一轮状态，v1.6.9.3 —— 历史归档）

- **双端 1.6.9.3 全部完成**（门禁 verify_jar_1693 55 项 / 冒烟 smoke1693 50 项全绿 / 官网三文件 + 双 JAR 已上线、
  在线验证含双 JAR md5 字节一致与外网域名复核 / git 远端 main = fff86a7 经 gh-proxy 双通道复核）：
  **变量获取扩展**（用户需求：查信息项目自动隐藏修改 + 自定义项目 + 玩家名/tps 可配置 + 其他地方同步拉取）+
  **查性能最大人数** + **命令精简**。实现：
  - `PlaceholderVariableService` 重做：PLAYER_FIELDS 六字段（五项 + player-name）+ **Context**（overrides 六值 +
    coinsConfigured/pointsConfigured/titleConfigured 显隐标记 + customItems 解析结果——configured 与读取成败/
    PAPI 可用性无关，决定「填了变量必展示」）+ snapshotContext（六键 + 自定义项一次解析，日志汇总）+
    customItems()（读 variable-fetch.custom-items 段，子键中文「项目名称」「获取变量」，缺任一跳过；每次查询
    实时读取 → /kerong reload 即生效）+ resolveBatch（排行榜批量：两键均未配置零开销空表）+ resolveServerTps
    （服务器级：有在线玩家借第一个解析否则 null OfflinePlayer，PAPI server 类变量无需玩家上下文）+
    resolveOnMainThread（FoliaCompat.callSync 主线程跳转 3 秒超时，异步路径变量解析统一入口）+
    resolveForOffline（支持 null target）；FETCH_FAILED_TEXT=「未获取成功」。
  - `QueryInfoService`：MainThreadSnapshot 五 var 字段收敛为 `vars: Context`；buildItems 三处显隐改
    `vaultAvailable || vars.coinsConfigured` 等 + elvis 链 `vars.coins ?: if (vaultAvailable) 自带 else 未获取成功`；
    自定义项目 for 循环追加卡片末尾；卡片头部 playerName = vars.overrides.playerName ?: 真实名（头像/统计/缓存
    仍按真实玩家）；timeoutSnapshot 用 EMPTY_CONTEXT。
  - 排行榜/财富榜：snapshotVariableOverrides 主线程快照批量解析 top 条目（player-name + total-playtime/coins），
    LeaderEntry.name 与 valueText 应用覆盖（头像/排名仍按真实名）。
  - 查性能：PerformanceSnapshot 增 maxPlayers（capture 五参工厂带默认值兼容旧调用）；Spigot 端 api.maxPlayers、
    NF 端 server.playerList.maxPlayers；**MotdDisplayTracker** 新单例记录 ServerListPingEvent MONITOR 优先级
    （全部修改者之后）读到的 event.maxPlayers——即其他插件改过的展示人数；PerformanceService.resolveMaxPlayers
    按 render.performance.max-players-source（real 默认 / motd 展示值，无人 ping 过回退真实）选源；
    PerformanceRenderer 在线人数卡「N / M」+ 占比进度条 + 副说明标记（MOTD 展示值），TPS 卡 tpsVariableText
    变量原文输出（parseDoubleOrNull 容错千分位，可解析数值同步用于状态分级与进度条，非数值状态「已读取」）。
  - 命令删除：/在线服务器 与 /motd 全链路清理（PublicCommands 两方法、MotdCommands.kt、GroupMessageHandler
    注册、MessageTemplates 六模板 ID、Markdown 资源**双份**、MenuManager 面板与裁剪表 21→19 项、help.md、
    ConfigManager 三张表 + REMOVED_CONFIG_KEYS、双端 config.yml commands/messages 段）；motd 配置段保留
    （查在线仍用 use-markdown/api/server-ip）。
  - 配置：双端 config.yml variable-fetch 增 player-name/tps/custom-items（含示例注释）+
    render.performance.max-players-source；config-version 23→24。
  - 兼容层 PluginManager 单槽改多槽（COW 列表按名查找；生产仅注册模组自身行为不变；解决 1.6.9 冒烟记录的
    「双插件无法共存」限制——snapshotContext/resolveServerTps/resolveBatch 全链冒烟得以真跑）。
  - 冒烟亮点：假 PAPI 下端到端 snapshotContext 六键解析（含 player-name）与自定义项目（正常返回保留）；
    buildItems「填了变量读不到且无 Vault → 金币展示未获取成功」正反向对照；resolveServerTps 采用/未配置；
    resolveBatch 双玩家名值覆盖与未注册回退；性能卡端到端 PNG 99571B（maxPlayers+MOTD 标记+TPS 变量）；
    resolveBatch 入参需构造重定位后的 cn.kerong.shaded.kotlin.Pair（NF JAR kotlin 已重定位）。

---

## 7. 当前已知状态（2026-11-01 四次会话，v1.6.9 完成）

- **双端 1.6.9 全部完成**（门禁 verify_jar_169 33 项 / 冒烟 smoke169 27 项全绿 / 官网三文件 + 双 JAR + 源码包已上线、
  在线验证含双 JAR md5 字节一致 / git 远端 main = 9418cb7 经 gh-proxy 复核）：
  **变量获取信息（variable-fetch）**——用户需求：开关开启时（默认关）各信息自动获取填写的变量返回的内容进行输出，
  可配置总在线时长/今日在线时长/金币/点券/称号五项；获取失败退回插件自带；留空默认用自带。实现：
  - `common-Bot/.../stats/PlaceholderVariableService.kt`（新文件）：isEnabled 总开关 + placeholderOf 键读取
    （首尾空白清洗，与凭据清洗同思路——网页复制变量常带零宽字符）+ snapshot 主线程一次解析五项
    （Overrides 五 String?）+ resolve 反射 setPlaceholders(OfflinePlayer, String)（与称号链路同模式：
    PAPI 插件自身类加载器加载，跨插件类不可见）。回退判定三条：结果 trim 后空→null；结果与原文相同且原文含 %→null
    （未注册；纯文本无 % 放行直通——服主可填固定文本）；异常→null。变量支持与固定文本混排（"%变量% 小时"）。
    PAPI 缺失时经 PapiDependencyCheck.notifyMissingOnce 会话级提醒 + 文件日志，snapshot 全 null 回退。
  - `QueryInfoService`：MainThreadSnapshot 增五个 var 字段；gatherOnMainThread 主线程快照内调用 snapshot
    （PAPI 第三方扩展非线程安全；关闭时直接返回全 null 零开销；不增加主线程往返）；timeoutSnapshot 五 var 字段
    null（主线程超时不做变量解析）；buildItems 五处 elvis 覆盖 + 金币/称号/点券显隐扩展
    （`vaultAvailable || varCoins != null`——变量给出内容时对应依赖插件未装也展示该项，变量本身就是数据来源；
    变量 null 时与旧版完全一致）。
  - 配置：双端 config.yml 新增 variable-fetch 段（enabled 默认 false + 五键；注释含规则与「仅插件版有效——
    模组版无 PAPI 生态恒用自带数据」口径）；config-version 22→23（升级自动重建写入新段，用户已改值保留）；
    ConfigManager CURRENT_CONFIG_VERSION 与 DEFAULT_VALUES 六键同步。
  - 官网：1.6.9 三处 + 兼容表新增 PlaceholderAPI 行与说明补充；version.json 1.6.9 + changelog；
    **tutorial.html 全量小白化改写**（用户要求：教程给小白看，不要技术/原理/细节/更新问题，技术细节全收进
    最后一个卡片）——结构：快速上手三步（原样）→「常用设置」（18 小节，每节配置片段+行内注释+一两句话，
    砍掉全部长段落）→「技术细节与常见问题」最后卡片（配置自动升级/AppID 空白/双服同绑/查性能指标细节/
    头像链路/变量回退规则/语法自愈/版本选择/数据存储/Geyser/内存 11 项收纳）；新增变量获取信息教程节；
    26 项变量表砍到 7 项常用 + 指向 config.yml 注释。
  - 冒烟亮点：FakeKeronPlugin 继承 NF 兼容层 JavaPlugin 提供 config（isEnabled/placeholderOf 配置通路真实走通）；
    动态代理 Plugin 名为 PlaceholderAPI + me.clip.placeholderapi.PlaceholderAPI 假类，resolve 的反射链路端到端
    真跑（成功采用/未注册回退/纯文本直通/混排四路径）；buildItems 经 17 参私有构造器反射端到端验证 elvis 与
    显隐扩展。兼容层 PluginManager 单槽 self 的限制：snapshot 全链（同时要 KERONGPenguin 与 PlaceholderAPI
    两个插件）无法在冒烟 JVM 复现，单插件场景 + resolve 直测分覆盖。
  - 踩坑：**javap -c 不带 -p 时私有方法体不反汇编**——gatherOnMainThread 是私有方法，snapshot 调用点在
    javap -c 默认输出里完全不可见（曾误判调用丢失），反汇编查私有方法必须加 -p。

## 7. 当前已知状态（2026-11-01 三次会话，v1.6.8 完成）

- **双端 1.6.8 全部完成**（门禁 verify_jar_168 27 项 / 冒烟 smoke168 20 项全绿 / 官网三文件 + 双 JAR + 源码包已上线、
  在线验证含双 JAR md5 字节一致；**git 远端 main = 40ab3ab 已推送**——细粒度 PAT 对 SUZHE014/KERONGPenguin 报 403
  Permission denied，token2 经典令牌 ghp_ 推送成功，后续 push 直接用经典令牌）：
  **时长单位小时化**——QueryInfoService.formatPlayTime（查信息「在线时长」，紧凑风 77时20分，不足 1 小时保持
  秒级 42分33秒）与 LeaderboardRenderer.formatPlayTime（在线排行榜，长风 77小时20分钟）两处实现的 days/86400
  计算整体移除，secs 展示条件 days==0&&hours==0 简化为 hours==0；formatTodayTime 本就小时制不变；边界文案
  （0分钟/不足1分钟/暂无记录）原样。**排行榜头像修复**——InfoCardAssets.fetchViaExternalChain 顺序反转：
  fetchExternalAvatar（MC-Heads→Minotar→MinePic→Crafatar）先行，resolveMojangSkinUrl（官方会话直查+名字反查）
  殿后；根因（用户实测「排行榜大多数头像都没显示」）：官方线路国内普遍不可达，单玩家 sessionServerSkinUrl
  3s 超时 + resolveMojangSkinUrl 内名字反查 5s 超时（冷却标记后本玩家名字反查仍会执行），排行榜 4 线程 ×
  8s invokeAll 预算整轮烧光、头像 future 被整批取消，且官方 5 分钟冷却到期后又重复烧；四条外部线路实测
  0.4~5.8s 从未被轮到先走。类级文档/方法 KDoc/fetchAvatar 注释/双端 config.yml avatar-fetch-priority 注释
  同步新序；官方链路构件（冷却/负缓存/失败分类）全部保留仅移位。**版本号**：1.6.8 六处（1.6.7.10 的「五处」
  口径漏了 META-INF/mods.toml——双 toml 都在 META-INF 下，本次首查漏改由门禁 V3 抓回后重打 NF 包）。
  **官网清理与 UI 精简**：删 1.6.7.9 之前全部版本（双端 1.6.7.5/6/7/8 + 互转版 Cross-posting.2 共 9 JAR 下架 404、
  历史表与更新日志同步删除，保留 1.6.7.9/10/11；0.3AI 三件保留）；主下载按钮缩短（⬇ Spigot 插件版 / ⬇ NeoForge
  模组版），其他版本板块 0.3AI 十四条→三条、互转版四条→一条，按钮同步缩短；version.json changelog 裁到
  1.6.7.9 起四条；tutorial.html 两处同步（头像说明段线路新序 + 排行榜段时长单位）。
  **环境第八次重建**：tooling/ 被重置清空——JDK21（TUNA Adoptium 21.0.13_9）与 Gradle 8.14.5（腾讯镜像，
  wrapper dists 哈希目录 690y85m0j9nfaub7xoiayko8a + .ok 标记）重装进 tooling/gradle-home（gradle.properties：
  tooling/jdk21 + kotlin in-process + jvmargs 2800m）；deps.jar 从官网 1.6.7.11 Spigot JAR 重建（kept=10793）；
  NF libs 沿用 penguin-git 旧工作区遗留（MC 1.20.4 / NF 20.4.253 目标与 1.6.7.11 一致，全部跳过）；javac 需
  PATH 前置 tooling/jdk21/bin（package_jar_neoforge.py 的 find_javac 查 which 与 ~/.gradle/jdks/，已建软链）。
  冒烟 javac 编译的类在默认包（无 package 声明）——运行主类名是不带路径的 Test168（-cp 指向 out 目录）。

## 7. 当前已知状态（2026-10-31 第二次会话，0.3AI v0.4 + 主线 git 推送完成）

- **主线 git 已推送（历史欠账清零）**：远端 GitHub main = **e3debc5**（1.6.7.11 等效提交，按官网源码包
  重建；历史链 bf9a48c→e931218→beefeb0→2011bac→e3debc5 全部上线），gh-proxy 与官网服务器双通道
  ls-remote 复核一致。推送通道：**官网服务器中继**（沙盒直连 GitHub 被墙、gh-proxy 不转发 push 鉴权），
  /root/gitpush/bare.git 为常驻中继仓（token 脚本已删，下次 push 重新生成即可）。
- **0.3AI v0.5 已发布**（2026-11-01，AI 专属版；源码灾备 /root/ai-source/0.3AI_v0.5_source.zip；
  0.3AI-0.4.jar 保留历史；工作流见 §9）：
  **零环境自动部署**——AiLocalRuntime：启动异步守护线程，base-url 为本机回环且服务离线时自动下载
  Ollama 运行时（tgz/zip 按平台解压到插件目录 ollama-runtime/，免 root）并 ProcessBuilder 拉起
  serve（OLLAMA_HOST=配置端口 / OLLAMA_MODELS=安装目录 models 自包含），模型缺失自动 pull（digest
  分层进度 + 10 分钟看门狗）；**内存检测每次重启一次**：min(物理内存, cgroup 限额) - MC 堆 - 1.5GB
  预留 → 档位 24GB+→32b / 11GB+→14b / 6GB+→7b / 其余→1.5b，auto-model 开启回写 ai.local.model；
  stop-on-shutdown 仅停自拉起进程。**引用可选**——RecentGroupMessages 每群环形缓冲记录消息（引用
  ID 取事件 metadata deepFindValue msg_idx，回退消息 ID + ignore_get_message_error），注入本群
  最近 10 条到上下文（独立 system 消息），AI 回复 [引用 N] 标记 → message_reference 载荷引用回复；
  默认不引用：主动消息优先（group.send 普通气泡）被拒回退被动；ai.reply-quote 三态 auto/always/never。
  **表情包**——AiMemeLibrary：guaipng 文件夹（png/jpg/gif/webp/bmp），文件名=含义，列表注入提示词
  动态段，[表情] 标记三级匹配（归一化精确→互包含→字符重合度≥0.5）→ MessageChain image 被动发送
  （读超时不算失败，10MB 上限）。协议说明由 capabilitySection() 动态注入（老 ais.md 免迁移）；
  chat() 返回 ChatOutcome；config-version 2→3。门禁 138 + 冒烟 120 全绿。
- **0.3AI v0.4 已发布**（AI 专属版，仅 Spigot 插件形态；源码 ai-git 不进公开仓库，服务器灾备
  /root/ai-source/0.3AI_v0.4_source.zip；工作流见 §9）：
  **本地 AI**——云端 API 整体替换为本机 OpenAI 兼容服务（默认 Ollama http://127.0.0.1:11434 +
  deepseek-r1:7b，仅支持 DeepSeek 系列；启动自检 + /kerong ai 探测 /v1/models 给部署指引；R1 思考段
  剥离）；**删私信**——FriendMessageHandler 整体移除；**全局上下文**——ai-context.yml 一份共享记忆
  （重启保留）+ ai-users.yml 用户名片（【用户名】前缀区分发言者，服主可改名）；**ais.md**——系统提示词
  文件（自动创建默认版，mtime 缓存保存即生效）；**网页爬虫**——[GET] 标记协议 + WebCrawler（jsoup 提
  正文、SSRF 全套防护、每轮限页限长，关闭/达上限时标记不外泄）；**管理命令**——「删除全局上下文」
  （群主/群管 sender.meta.member_role 自动 + ai.admin-ids 名单）+ AiPanelManager 指令面板单按钮（沿
  1.6.7.10 成熟同步逻辑，bot.groups 空时自动学习群 + 90s 防抖重同步）；**配置迁移**——config-version
  1→2 自动清理云端旧键。
  门禁 verify_ai_04 101 项 + 冒烟 TestAi04 67 项全绿；官网 index.html AI 区块已更新、0.3AI-0.4.jar
  上线（md5 3c5202522c804c60b04cb58b8aa0ccfb）、0.3AI-0.3.jar 保留为历史版本；version.json 不动
  （AI 版独立产品线无更新检查）。

## 7. 当前已知状态（2026-10-31，v1.6.7.11 完成）

- **双端 1.6.7.11 全部完成**（门禁 verify_jar_16711 39 项 / 冒烟 smoke16711 27 项全绿 / 官网三文件 + 双 JAR + 源码包已上线、
  在线验证含双 JAR md5 字节一致）：
  **NeoForge 登录闸门挂载修复**——用户线上日志：同秒两条「登录闸门挂载异常（老玩家拦截退化为加入后踢出）:
  Method …BindGateBusListener.onModBusEvent(Event) has @SubscribeEvent annotation, but takes an argument that is not
  valid for this bus」+「登录闸门已挂载（未绑定老玩家在配置阶段拦截）」——闸门从未挂上（拦截退化在加入后踢出），
  误导性 INFO 带偏排查。修复：①删 Event 基类监听器 BindGateBusListener，改反射 IEventBus.addListener(Class, Consumer)
  按运行时探测事件类精确注册（+mounted 幂等护栏）；②registerTo 返回 boolean，KERONGNeoForge 按「已挂载」真实结果
  输出；③跨版本事实全部真实构件字节级核对（20.4.253 / 20.6.144 / 21.1.252 / 21.11.48：两代事件类与
  ICustomConfigurationTask 字节 md5 一致、均实现 IModBusEvent、ModLoader.postEventWithReturn 广播、bus 7.2.0 与
  8.0.5 的 addListener(Class,Consumer) 签名一致）；④冒烟 G 组在真实 FML 风格 mod 总线端到端（G2 负向对照复现原始
  故障）；⑤README 顺带修复上期 1.6.7.10 摘要块整段重复。Spigot 端行为不变随版本号同步。
  **环境第六次重建**：JDK21.0.13/Gradle8.14.5/CFR 全部装 my-project/tooling 随沙盒持久化（GRADLE_USER_HOME=
  tooling/gradle-home），冒烟构件（21.1.252-universal/bus-8.0.5/loader-4.0.44/log4j-api-2.22.1/paper-api-1.20.4）
  补回 scripts/deps-work。git：本地 main = 55854ae（1.6.7.11），push 仍待凭据。

- **双端 1.6.7.10 全部完成**（门禁 verify_jar_16710 24 项 / verify_jar_neoforge_16710 21 项 / 冒烟 smoke16710 14 项
  （TRIM 裁剪行为三场景 + 兼容桥 + 结果结构，对主版 Spigot JAR 与互转版 Cross-posting.3 JAR 双跑全绿）/
  官网三文件 + 双 JAR + 互转版 .3 + 源码包已上线）：
  **快捷面板 30013 真根因修复**——用户实测 1.6.7.9 先删后建后仍失败（日志：平台上无残留面板→创建被拒 30013→
  清空重建仍拒→三连发同秒全挂→末尾还绿字误报「已重新同步」）；查 QQ 官方文档确认「一个指令面板里最多配置
  20 个面板元素」，PANEL_ITEMS 恰 21 项（1.6.7 加「查性能」后 20→21）——列表 0 面板仍报数量超限的真根因，
  与旧面板残留无关；修复=trimToPlatformLimit 优先级裁剪（全功能开启裁「查询open ID」）+
  /kerong panel 异步执行与真实结果反馈（旧版主线程同步会卡服 + 无论成败一律报成功两个 bug）+
  30013 退避 + 10 QPM 创建节流 + 分页列表与失败区分 + void 兼容桥（互转版零重编）。
  **NeoForge mods.toml 段头真修复**——1.6.7.9 声称修复但字节级核实源码与线上 JAR 仍是损坏的 [[ods]]
  （缺字母 m，FML 解析 toml 失败模组无法加载；又一例「宣称未实装」，与 1.6.6 覆盖版 TPS 封顶同病），
  本次双 toml 字节级修复 [[mods]] + tomllib 解析验证。
  **互转版 1.6.7_Cross-posting.3 精准覆写打包**（新工作流见 §8，替代反编译还原）。
  **git 历史已重建**：本地 main = bf9a48c（远端 1.6.7.8）→ e931218（按官网 1.6.7.9 源码包重建的等效提交，
  原 bdc54bf+4d89e52 随环境丢失未 push）→ beefeb0（1.6.7.10）；push 仍待凭据补齐。



- **双端 1.6.7.9 全部完成**（门禁 verify_jar_1679 88 项 / 冒烟 smoke1679 48 项（PM 错误码归一化与人话、
  UC 双源与本地 HTTP 服务器、MV 独立类加载器下 20.4/21.1 双向事件探测与跨版本桥梁、TO 双 toml、
  B 基线）/ 官网三文件 + 双 JAR + 源码包 + 互转版 .2 已上线，在线验证 26 项全绿）：
  **快捷面板先删后建**（修复 HTTP 400 code 30013 超出数量限制）+ **更新检查镜像回退**（多源依次尝试，
  update-check.mirror-url 可自定义/off）+ **NeoForge 多版本适配**（编译目标 20.4.253 / mods.toml 笔误修复
  + 双声明 / BindLoginGate 跨版本事件桥 / getRawText 三代核对直调；1.20.4、1.20.6 需 Java 21 运行）。
  **环境第四次重置后重建**：git 经 gh-proxy 克隆（bf9a48c）、deps.jar 从官网 1.6.7.8 Spigot JAR 剔除重建、
  NF libs 走 BMCLAPI（build_neoforge_libs.py 已镜像化 + 参数化 + fetch 重试）、Gradle 8.14.5 腾讯镜像预置、
  JDK 21.0.13 TUNA（21.0.12.1 已下架换新）、**Kotlin 编译须 `kotlin.compiler.execution.strategy=in-process`
  + gradle jvmargs 2800m**（4G 内存机器上独立 KotlinCompileDaemon 会无声死亡——gradle 进程活着但日志停在
  compileKotlin 无任何错误行，in-process 后稳定编完）、**git push 因 GitHub 凭据随环境丢失未执行**
  （commit bdc54bf + 4d89e52 已完成、源码包已存档服务器 penguin-source，push 待凭据补齐后补做）。
- **互转版 1.6.7_Cross-posting.2 完成**（分支 cross-posting-1 本地重建：从 6917246 建分支 → 合并 main
  （带入面板修复）→ 反编译官网 Cross-posting.1 JAR（CFR）还原差异——CrossPosting.kt / QClient.
  sendTextToGroupsExcept / GroupMessageHandler 挂接 / ConfigManager 群 20 截断 + crossPostingConfig /
  KERONG 接口默认实现 + 无更新检查 / config.yml 互转版头 + cross-posting 段教程注释；本地 commit
  0a1ee9e 永不 push。该版本无更新检查（定义特征），镜像回退对它不适用；面板修复经合并自动并入）。


- **⚠️ 1.6.7.6 覆盖版源码丢失事故与 1.6.7.7 归一**：1.6.7.6 覆盖版代码未 commit/push，环境重置后丢失——git main 一度停留在首发版而官网已是覆盖版（三项功能「官网有、源码无」）。1.6.7.7 反编译官网 JAR（CFR）逐项核实：内置通道与凭据清洗完整重做合入；**TPS 封顶覆盖版并未实装**（字节码全链路无 min(20) 逻辑，仅 version.json 宣称——用户因此再次报告 300+），1.6.7.7 以三层封顶真正落位（冒烟实测 300→20）。**教训：同日覆盖必须立即 commit+push；官网宣称前必须字节级核对 JAR。**
- **双端 1.6.7.8 全部完成**（门禁 verify_jar_1678 83 项（LR 组延迟移除 21 项反向断言：字节码无 avgPingMillis/playerPing/延迟标签/300.0 满值常量 + V/TC/SD/SN/CR/HB 组保持 + B 组基线）/ 冒烟 smoke1678 47 项（快照无 ping 字段与旧五参工厂（反射 NoSuchField/NoSuchMethod 断言）/ 假 spark 含 playerPing 被忽略（read 照常成功且 SparkMetrics 无 getPing）/ TPS 封顶四参回归 / 五卡通栏渲染 1350×1034 / 在线人数卡三态 / JAR 内容）/ 官网三文件 + 双 JAR + 源码包已上线）：**移除「延迟（玩家平均）」指标全链路**（用户反馈：本地/同机连接下 Ping 恒 0ms，输出的数据一直是服务器本地网络，无任何实际监控意义）——快照、双端采集、spark 桥、渲染卡、自检清单、双端 config 注释全链路下线；布局改五项（末行在线人数整行通栏）；NF config 顺带修正历史滞留的「1.6.7.1 四项」旧注释。
- **双端 1.6.7.7 全部完成**（门禁 verify_jar_1677 75 项 / 冒烟 smoke1677 35 项（TC 三层封顶行为：capture 300→20·18.2 原样·null 保持，readTps getTPS/recentTps/装箱数组封顶，tpsFromMspt 回归；真实形态假 spark 300→20 封顶+mspt/cpu/ping 不受影响；bundledSparkClassLoader 行为：候选命中/全候选 null；延迟卡三态反射验证：暂不可读/暂无玩家/0ms 流畅；JAR 内容：版本/无 aliases/help.md/config.yml）/ 部署 16 项 / 官网已上线）：TPS 三层封顶（SparkBridge.pollTps + PerformanceSnapshot.firstTps + capture 工厂，任何来源 >20 按满速 20）；延迟 0 合法（双端 >=0 计数，-1 仍为失败哨兵，延迟卡区分暂不可读/暂无玩家）；spark 探测五态（findSparkPlugin 大小写不敏感 → bundledSparkClassLoader(bukkitApiLoader()) 内置通道 → findStraySparkFiles → NotLoaded(fileNames) 引导（Modrinth 全是模组变体 / Paper 1.21+ 已内置）/ NotInstalled，Installed(metrics, bundled) 提示「来源：服务器核心内置/插件」，查询链同口径）；凭据清洗（sanitizeCredential+trimInvisible 显式 \uXXXX 转义 20 不可见码位，污染时 logger.warning 带 appid invalid 指引，双端共用）；/hb 别名移除（plugin.yml aliases 删除 + NeoForgeCommands 仅 kerong + 24 处文案改 /kerong，门禁正则断言全库无可操作 /hb 指引）。
- 环境重建备注（第三次环境重置）：JDK21 TUNA 21.0.12.1 装 /home/z/jdk21；Gradle 8.14.5 腾讯镜像 zip 预置 wrapper dists（哈希目录 690y85m0j9nfaub7xoiayko8a）；~/.gradle/gradle.properties 指定本地 JDK 防 foojay 联网；冒烟依赖从 ~/.gradle/caches 复制（paper-api-1.20.4-R0.1-SNAPSHOT 等）；kerong.xin 对 curl DNS 报错——用 `-H "Host: kerong.xin" https://156.233.228.248/...` IP 直连；长任务后台必须 setsid nohup（普通 nohup & 会被会话回收）；打包 base JAR 从官网下载现行版（gh-proxy fetch git + 官网 JAR 反推覆盖版差异是环境重置后的标准恢复路径）。

## 7b.（原 1.6.7.6 状态段，历史归档）

- **v1.6.7.6 同日覆盖版（2026-10-18 晚）已发布**：双端 JAR/源码包/官网三文件/知识库全部覆盖更新，版本号不变。三项内容：① Paper 1.21+ 系核心内置 spark 接入（bundledSparkClassLoader + NotLoaded 引导 + Installed(bundled) 来源标注，启动自检与查询链同步；沙盒 Paper 26.2+JDK25 实测验证「已接入 spark（来源：服务器核心内置）」）；② TPS 封顶 20（clampTps at capture 全参工厂，实测 /tick sprint 后 getTPS=219.8 复现原问题）；③ 凭据空白清洗（sanitizeCredential/trimInvisible + QqTokenHealth 错误码人话化——用户日志实锤 AppID 尾随空格：模板无空格输出带空格）。门禁 verify_jar_1676 扩至 85 项（OV1~OV23 覆盖版断言）；冒烟 smoke1676 新增 Test1676r2 28 项（clampTps 全行为/capture 钳制/bundledSparkClassLoader 候选链/NotLoaded 构造/JAR 版本不变）全绿。

- 双端 **1.6.7.6** 全部完成（门禁 verify_jar_1676 62 项 / 冒烟 smoke1676 26 项（ConfigBrokenRecovery 三级恢复含用户实测 player-events:true 样例 / TickMonitor 窗口数学 / PerformanceSnapshot 新字段 / fakespark1676 playerPing / 六项卡渲染 1350×1034）/ 官网部署 / git 推送 6917246）：
  - 修复①：Folia 多线程核心 MSPT 读不到 + 数值忽高忽低——新增 **TickMonitor**（common-Bot stats 包：FoliaCompat.runTimer 每 tick 采样 nanoTime 间隔 → 1200 tick 环形缓冲；MSPT=最近 200 tick 均值、TPS=60 秒窗口换算封顶 20；样本<40 不读、单次间隔>10s 丢弃；Spigot onEnable start/onDisable stop）——Folia 上采样器优先、反射兜底，其他核心反射优先、采样器兜底，spark 窗口均值最优先；CPU 无 spark 时经 PerformanceSnapshot.capture 的 EWMA（0.7/0.3）平滑；
  - 修复②：装了 spark 还是提示「接入失败」误报——probeSpark 新增第四态 **NoStatsOnThisCore**（spark 已装且启用但统计全 null 且当前为 Folia → 信息级提示「已用内置采样器」；非 Folia 仍按 Failed 警告）；
  - 修复③：config.yml 语法错误死胡同（`player-events:true`）——**ConfigBrokenRecovery** 三级自愈（见 §3.6）；
  - 新增④：查性能六项——PerformanceSnapshot 增 `avgPingMillis`/`onlinePlayers`（capture 全参工厂）；延迟链路（**1.6.7.8 已整体移除**）：spark playerPing（Spark#playerPing→GenericStatistic#poll(PlayerPing.MINUTES_1)→mean）→ Spigot 各玩家 getPing 平均（Folia 经 callSync 全局区域线程，纯 Spigot 无此 API 时 NoSuchMethodError 被捕获仅人数照常）→ NF 各玩家连接 `ServerCommonPacketListenerImpl#latency()`（mojmap 公有方法）；
  - 版本：1.6.7.6 / config-version 22（无键增删）。
- **1.6.7_Cross-posting.1 互转版**（同日发布，仅 Spigot 插件版）：本地分支 `cross-posting-1`（git worktree /home/z/my-project/cross-posting-git，**不上传 git**）；基于 1.6.7.6；多群互通（bot.groups 多行最多 20 群、全量转发默认 / 艾特转发 `/多群转发` 命令、cross-posting 段 enabled/full-forward/format、QClient.sendTextToGroupsExcept 经 OutboxQueue、/多群转发 管理员限定）；**无更新检查**；**教程全部写 config.yml cross-posting 段注释**（官网「其他版本」卡片不放教程）；门禁 verify_crossposting 34 项 / 冒烟 TestCP 9 项；该版本**仅用户要求时更新**。
- 官网历史下载：1.6.7.6 起保留 1.6.7.5~1.6.7.6（+互转版）；1.6.6.4 已下架。
- 1.6.7.5 历史项：SparkBridge 反射链修复（方法从公共接口解析，实现类包私有不再阻断；冒烟假对象须模拟包私有可见性）/ AI 聊天艾特判定（GROUP_AT 帧级捕获 + msg_id 去重 + 行首占位剥离）/ 查性能卡半透明面板与其他卡片统一（白 34 / 黑 120 / 白 16 / 白 30，无描边）。
- 待观察：TickMonitor 在真实 Folia 上的窗口读数节奏；Cross-posting 版真实多群转发链路（含附件占位）。

## 8. Cross-posting 互转版（1.6.7_Cross-posting.3，重要工作流）

- **分支**：原本地 `cross-posting-1`（git worktree `/home/z/my-project/cross-posting-git`）已随环境重置丢失且
  原计划「从官网 Cross-posting.1 JAR 反编译（CFR）还原差异」自 1.6.7.10 起**不再需要**——实测与线上
  Cross-posting.2 JAR 字节级比对：互转版与主版的差异仅在 CrossPosting/CrossPostingCommands/CrossPostingConfig
  三件套 + QClient/GroupMessageHandler/ConfigManager(+Companion)/KERONG(三件)/KERONGSpigot/
  PerformanceSnapshot(两件)/TextExecution 共 12 类 + 3 个专属类，**而 MenuManager 与 KERONGCommand
  与主版完全一致**。
- **1.6.7.10 起新工作流：精准覆写打包**（`scripts/package_crossposting_16710.py`，脚本按「互转版源码不进
  公开仓库」惯例用 .git/info/exclude 排除、不进 git 与源码包）：以线上互转版 JAR 为底座，仅覆写新编译的
  `cn/kerong/bot/MenuManager*` 与 `cn/kerong/penguin/spigot/commands/KERONGCommand*` 类（含新增内部类）+
  plugin.yml 版本号，互转版专属类全部原样保留；新旧 JAR 差异审计应为「5 条预期变更 + 3 新增内部类 +
  其余条目原样」（本次实测 11825/11830 原样保留）；**前提是 MenuManager 保持 void 签名兼容桥**
  （syncGroupPanels(Starter;List)V 描述符不变，互转版 QClient 旧编译产物零重编直接链接，
  新调用方走 syncGroupPanelsWithResult）——今后改 MenuManager 公开签名前先想互转版。
- **适用边界**：主版改动若触及互转版差异类（QClient/GroupMessageHandler/ConfigManager/KERONG/CrossPosting
  三件套），精准覆写不可用，需回到反编译还原或要求用户提供互转版源码；改 MenuManager/KERONGCommand/
  渲染/命令层则精准覆写直接适用。
- **构建**：`python3 scripts/package_crossposting_16710.py /path/to/KERONGPenguin_Spigot-<旧Cross-posting>.jar`
  （输出 build/dist/KERONGPenguin_Spigot-1.6.7_Cross-posting.3.jar；底座用线上现行互转版 JAR，
  官网下载 IP 直连 `-k -H "Host: kerong.xin"`）。
- **与主版差异**（历史，供反编译还原时参考）：CrossPosting.kt（转发器+命令+会话）、KERONG 接口
  crossPostingConfig 契约、QClient.sendTextToGroupsExcept、GroupMessageHandler 挂接、ConfigManager 群 20
  截断与 cross-posting 段、无更新检查（initializeRuntime 的 scheduleUpdateCheck 注释 + config.yml 无
  update-check 段）、config.yml 互转版头 + cross-posting 段教程注释（官网不放教程）。
- **更新节奏**：用户要求才更新；**永远不 push**——该版教程与源码不进公开仓库。

---

## 9. 0.3AI 专属版（v0.4，重要工作流）

- **产品定位**：KERONGPenguin 的极简分支（只有「连接机器人 + QQ 群 AI 对话」），独立插件名 0.3AI、
  独立版本号（与主线无关）、仅 Spigot 插件形态（无 NeoForge 版）、无更新检查；官网「其他版本」卡片
  展示，教程全部内置在 config.yml 注释里。**源码不进公开仓库**（沿互转版惯例）：本地
  `/home/z/my-project/ai-git/`（src/ + resources/ + smoke/），服务器灾备 `/root/ai-source/`（非 web 目录）。
- **v0.4 起的架构**：包结构沿主线（cn.kerong.bot + cn.kerong.penguin.spigot），但类大幅精简（58 个
  核心类）。核心类：AiManager（配置/上下文/名片/ais.md/自检）、AiChat（对话循环 + [GET] 爬虫协议 +
  R1 思考剥离）、AiContextStore（ai-context.yml 全局记忆）、AiUserDirectory（ai-users.yml 名片）、
  AiCommands（命令匹配 + 管理员判定）、WebCrawler（jsoup + SSRF 防护）、AiPanelManager（指令面板）、
  QClient/GroupMessageHandler（仅群聊）/ConfigManager(+ConfigBrokenRecovery)/KERONGSpigot/KERONGCommand。
  基础设施类（WssDiagnostics/ReconnectManager/ReplySeqGuard/RecentMessageGate/GroupAtTracker/
  QqTokenHealth/SptCompat/FoliaCompat/PluginFileLog/QqText/providers）与主线同源，打包时从底座继承
  **不重编**。
- **构建工作流（精准覆写，与互转版 §8 同范式）**：
  1. 编译：kotlinc 直编 ai-git/src 全部 .kt（`java -cp tooling/kotlin-compiler-2.2.0.jar:<上一版JAR>:tooling/kotlinx-coroutines-core-jvm-1.8.0.jar:tooling/kotlin-reflect-1.6.10.jar
     org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -jvm-target 17 -language-version 2.2
     -cp "<上一版JAR>:tooling/paper-api-1.20.4.jar:tooling/adventure-api-4.16.0.jar:tooling/adventure-key-4.16.0.jar:tooling/examination-api-1.3.0.jar:tooling/examination-string-1.3.0.jar"
     -d ai-git/build/classes <全部kt>`）——不走 Gradle（AI 版改动集中在十几个类，kotlinc 秒级）；
  2. 打包：`python3 scripts/package_ai_05.py`（底座=0.3AI-0.4.jar：覆写/新增记账 + 目录条目保留 +
     未触及条目字节一致断言；**下一个版本把脚本里 BASE/CLASSES/OUT 与断言改指新底座与产物名即可**）；
  3. 门禁：`python3 scripts/verify_ai_05.py`（138 项：版本/资源/新类/引用/表情/部署字节码内容/
     基线一致/JAR 完整性——**改版本号后同步更新脚本里的断言**）；
  4. 冒烟：`ai-git/smoke/TestAi05.java`（120 项；JDK 内置 HttpServer + 沙盒全局 IP 21.0.3.21 自访问
     测爬虫全链路 + 假 AI 服务测多轮对话与上下文注入；classpath = 产物 JAR + tooling 的 guava+
     snakeyaml+adventure 四件+paper-api——缺了 YamlConfiguration 会静默失败）。
- **改公开类前想兼容**：AI 版与主线/互转版共享 cn.kerong.* 命名空间但**独立 JAR 独立演进**，无互转版
  那种旧产物链接问题；但 AiPanelManager/AiCommands 等新类是 AI 版专属，主线没有——两边不要混拷。
- **QQ 平台事实（AI 版相关）**：面板按钮 name 即点击后发送的命令文本（按钮名=命令名）；群消息 sender.meta
  的 member_role 取值 owner/admin/member（管理员判定用）；平台不给真实 QQ 号，用户标识是 per-bot 的
  OpenID（名片机制的基础）；面板 access token 从 `starter.APPLICATION.INSTANCE.contextManager
  .getContextEntity(Start0::class.java).accessToken` 取（与主线 MenuManager 同）。
- **更新节奏**：用户要求才更新；官网 index.html 的 AI 卡片同步更新（version.json 永不动）。

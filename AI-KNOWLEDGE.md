# KERONGPenguin AI 交接知识库

> **本文档的用途**：把本文件丢给一个对项目一无所知的 AI，它能立刻了解当前情况并接手工作。
> 面向：接手开发/发版的 AI 助手。最后更新：2026-11-03（**主线 v1.7.2 发布**）。
> 本版为 1.7.2 的**细化重写版**：新增 §2 完整构建指南（含常见报错与解法）、§3 部署流程、§4 官网发版流程、§8 历次踩坑记录（根因+解决步骤）、§9 1.7.2 逐项改动清单；功能全景 / 核心机制 / 历史坑表保留并按最新口径刷新。
> 自包含版本（发给本地无任何东西的 AI）见官网 `https://kerong.xin/penguin/AI-KNOWLEDGE.txt`。

## 0. 一句话概括与版本状态

KERONGPenguin 是一个 **Minecraft 服务器 + QQ 官方机器人联动**的项目：玩家在 QQ 群里查数据/签到/聊天，插件在服务器内渲染统计卡片图片并回发。Kotlin 多模块：`common-Bot`（全部业务核心）+ `server-Spigot`（Bukkit 插件版，支持 Spigot/Paper/Folia/混合端）+ `server-NeoForge`（NeoForge 模组版 **MC 1.20.4+**：1.20.4 / 1.20.6 / 1.21.x 全区间，编译目标 20.4.253 面向最低版本、在更高版本运行；复用 common-Bot 全部源码 + Spigot 的 manager 源码；mods.toml + neoforge.mods.toml 双声明，BindLoginGate 跨版本事件桥，产物名 MC 段 = 1.20.4）。

**版本规则**：插件版与模组版同步发号。当前版本 **1.7.2**（详见 §9 逐项改动清单）。config-version **29**。

**git push 状态（重要）**：1.7.2 发版提交 `dd9ca25` 已本地提交（含 bpng/ 移出仓库）；远端 GitHub `SUZHE014/KERONGPenguin` main 仍停在 `c7d7b26`（1.7.1 知识库同步）——**push 需要写权限令牌**（fine-grained PAT 只读，见 §8 坑表）；中继路径：`git bundle create /tmp/x.bundle main` → 传服务器 → `/root/gitrelay/KERONGPenguin.git` fetch bundle 到 relay-main → 服务器上 `git push https://x-access-token:<写令牌>@github.com/SUZHE014/KERONGPenguin.git refs/heads/relay-main:refs/heads/main`（服务器可直连 GitHub）。1.7.1 的旧 bundle（/root/gitrelay-1711.bundle）与中继仓 relay-main 仍就绪可续传。

## 1. 环境与位置（必读）

| 项 | 值 |
|---|---|
| 本地仓库 | `/home/z/my-project/penguin-git`（GitHub `SUZHE014/KERONGPenguin`，main 分支；新环境从服务器 `/root/gitrelay-1711.bundle` 克隆恢复全历史，或从 `/wwwroot/penguin-source/` 源码包重建） |
| 官网服务器 | `156.233.228.248`（宝塔 nginx，站点 kerong.xin；SSH root） |
| ⚠️ MC 服务端 | **不在这台服务器上**（2026-11-03 实测：无 java 进程、无 25565 端口、无 MC 目录；此前"同机跑 MC"的说法不成立）——插件 JAR 的实际部署目标由用户提供，替换 JAR 后重启 MC 生效 |
| 官网目录 | `/wwwroot/penguin/`（index.html 下载页 / tutorial.html 教程 / style.css / version.json 更新检查文件 / AI-KNOWLEDGE.txt 本文档网页版） |
| 产物目录 | `/wwwroot/penguin/downloads/`（双端 JAR，保留约 8 个历史版本） |
| 官网图片包目录 | `/wwwroot/penguin/bpng/`（manifest.json + <包ID>/ 图片；1.7.2 起每包记录 count/bytes；**git 镜像已删除**——仓库不再有 bpng/，.gitignore 排除） |
| 源码包目录 | `/wwwroot/penguin-source/`（git archive 打的 zip，与仓库同结构；留最新 3 个） |
| SSH/SFTP 脚本 | `/home/z/my-project/scripts/ssh.py`（paramiko；`python3 ssh.py "命令"` / `--put 本地 远程` / `--get 远程 本地`，凭据在文件内） |
| 图片包上传工具 | `/home/z/my-project/scripts/bpng_upload.py`（1.7.2 新增；用法 `python3 bpng_upload.py 本地zip 包ID 包名 [描述]`：上传→服务器端解压→自动生成/更新 manifest（count=zip 内图片数、bytes=图片总字节、逐文件 md5）→chown www:www→curl 验证） |
| 打包脚本 | `scripts/package_jar.py`（Spigot）、`scripts/package_jar_neoforge.py`（NeoForge）、`scripts/rebuild_deps_jar.py`（deps.jar 重建）、`scripts/build_neoforge_libs.py`（NF 编译构件） |
| 门禁/冒烟 | `scripts/verify_jar_<版本>.py`（每版新写一个）+ `scripts/smoke<版本>/`（独立 JVM 冒烟） |
| 工作日志 | `/home/z/my-project/worklog.md`（多 agent 共享，每次工作后追加） |
| Java | 21（装在 `/home/z/my-project/tooling/jdk21`，TUNA Adoptium 21.0.13_9 含 javac；系统 apt 的 openjdk-21 是 JRE-only 无 javac） |
| Gradle | 8.14.5（华为镜像 zip 预置到 `tooling/gradle-home/wrapper/dists/gradle-8.14.5-bin/690y85m0j9nfaub7xoiayko8a/`；`GRADLE_USER_HOME=/home/z/my-project/tooling/gradle-home`，其 gradle.properties：`org.gradle.java.installations.paths=/home/z/my-project/tooling/jdk21` + `auto-download=false` + `kotlin.compiler.execution.strategy=in-process` + `org.gradle.jvmargs=-Xmx2800m`） |
| pip 镜像 | 阿里云 `https://mirrors.aliyun.com/pypi/simple/`（清华 TUNA 对部分包 403） |

**打包关键点（历史血泪，全部仍然有效）**：
- fat JAR 必须保留**目录条目**（以 / 结尾的条目），否则 SDK 组件扫描 NPE、机器人启动失败；打包脚本有 dir_count 熔断（正常约 776 个）。
- 打包脚本以**上一版可分发 JAR 为底座**（依赖字节码来自底座，新编译产物覆盖插件类）；运行前需要上一版 JAR（官网 `/wwwroot/penguin/downloads/` 有）。
- **NeoForge 打包的底座必须用 Spigot 版 JAR**（不能用 NF 版自己当底座，会把已重定位的依赖再处理一遍导致类丢失，产物体积骤降=坏的）。
- NeoForge 版做包重定位（kotlin/okhttp3/com.alibaba 等 → `cn.kerong.shaded`）+ 剔除 org/slf4j、com/google/gson（平台自带，split package 崩溃源）。
- `server-NeoForge/libs/` 与 `server-Spigot/libs/`（deps.jar）**不入 git**，新环境先重建（见 §2）。
- 发布前 `gradlew clean` 全量重建（增量编译可能产出 stale 字节码假通过）。

## 2. 完整构建指南（从零到双端 JAR）

### 2.1 环境重建（沙盒重置后按此执行，全部实测 2026-11-03）

1. **JDK21**：
   `curl -sL -o tooling/jdk21.tar.gz "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/linux/OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz"` → 解压为 `tooling/jdk21`（验证 `tooling/jdk21/bin/javac` 存在）。
2. **Gradle 8.14.5**（华为镜像，腾讯镜像会 567）：
   `curl -sL -o gradle-8.14.5-bin.zip "https://mirrors.huaweicloud.com/gradle/gradle-8.14.5-bin.zip"` → 解压到 `tooling/`；把 `gradle-8.14.5/` 目录放进 `tooling/gradle-home/wrapper/dists/gradle-8.14.5-bin/690y85m0j9nfaub7xoiayko8a/`，并 `touch` 同目录下的 **`gradle-8.14.5-bin.zip.ok`**（标记文件名必须是这个，写错 wrapper 会重新联网下载）。
3. **gradle.properties**（`tooling/gradle-home/gradle.properties`）：
   ```
   org.gradle.java.installations.paths=/home/z/my-project/tooling/jdk21
   org.gradle.java.installations.auto-download=false
   kotlin.compiler.execution.strategy=in-process
   org.gradle.jvmargs=-Xmx2800m
   ```
   （in-process 必须：4G 内存机器上独立 KotlinCompileDaemon 会无声死亡；foojay 自动下载走 GitHub 被墙。）
4. **deps.jar**（两个模块各需要一份）：
   `python3 scripts/rebuild_deps_jar.py <官网上一版Spigot JAR>`（剔除 cn/kerong/**、Markdown/、backgrounds/、fonts/、plugin.yml、config.yml，保留约 10792 个依赖条目）→ 产物在 `common-Bot/libs/deps.jar`，**再复制一份到 `server-Spigot/libs/deps.jar`**（构建脚引用后者，两处都不进 git）。
5. **NeoForge 编译构件**：
   `export PATH=/home/z/my-project/tooling/jdk21/bin:$PATH && python3 scripts/build_neoforge_libs.py`（全走 BMCLAPI 镜像；ART 重映射 5-10 分钟、内存 2G 够用、必须前台跑完；产出 server-NeoForge/libs/ 下 7 个 jar，已存在自动跳过）。

### 2.2 日常构建与打包

```bash
cd /home/z/my-project/penguin-git
export GRADLE_USER_HOME=/home/z/my-project/tooling/gradle-home
export PATH=/home/z/my-project/tooling/jdk21/bin:$PATH

# 编译三模块（clean 全量，防 stale 字节码）
./gradlew clean :common-Bot:jar :server-Spigot:jar :server-NeoForge:jar --no-daemon

# 打 Spigot 分发 JAR（底座 = 上一版官网 Spigot JAR）
python3 scripts/package_jar.py /home/z/my-project/penguin-work/KERONGPenguin_Spigot-1.7.1.jar
#   → build/dist/KERONGPenguin_Spigot-<新版本>.jar

# 打 NeoForge 分发 JAR（底座 = 本版刚打出的 Spigot JAR！）
python3 scripts/package_jar_neoforge.py build/dist/KERONGPenguin_Spigot-<新版本>.jar
#   → build/dist/KERONGPenguin_NeoForge-1.20.4-<新版本>.jar

# 门禁 + 冒烟（每版新写，参考 verify_jar_172.py / smoke172/）
python3 scripts/verify_jar_<版>.py
# 冒烟：javac/java 用 tooling/jdk21/bin，classpath 见 scripts/smoke<版>/classpath.txt
#   （NF 冒烟 classpath = NF 分发 JAR + scripts/slf4j-api-2.0.13.jar + scripts/slf4j-nop-2.0.13.jar + out）
```

### 2.3 常见报错与解法（实测汇总）

| 报错/现象 | 根因 | 解法 |
|---|---|---|
| `Cannot access class 'Starter'. Check your module classpath` | server-Spigot/libs/deps.jar 缺失 | 从 common-Bot/libs/deps.jar 复制一份过去（§2.1 第 4 步） |
| wrapper 启动联网下载 gradle 卡死/失败 | dists 目录的 .ok 标记缺失或文件名写错 | 标记必须是 `gradle-8.14.5-bin.zip.ok` 且与解压目录同级 |
| Gradle zip 下载 HTTP 567 | 腾讯镜像对该路径故障 | 换华为云镜像 `mirrors.huaweicloud.com/gradle/` |
| `找不到 javac（编译 RelocateTool 需要）` | package_jar_neoforge.py 的 find_javac 查 PATH 与 ~/.gradle/jdks | `export PATH=/home/z/my-project/tooling/jdk21/bin:$PATH` 后重跑 |
| pip 装 paramiko 403 | TUNA 对部分包禁止 | `pip install paramiko --index-url https://mirrors.aliyun.com/pypi/simple/` |
| Edit 改完源码构建"成功"但产物行为没变 | Gradle 增量编译产出 stale 字节码 | `gradlew clean` 全量重编；构建后以字节码采样复核（javap 抽查关键方法） |
| 长构建中途被杀 | 沙盒后台进程在 bash 会话间隙被回收 | gradle / NF libs 的 ART 必须**前台跑足超时**（Bash timeout 给够 600s） |
| javap 常量池断言假 FAIL | ① 常量池中文是 \uXXXX 转义（须按类文件 UTF-8 字节搜）② `#N = String #M // 内容` 两形态 ③ Kotlin 字符串模板编译为 makeConcatWithConstants（recipe 常量只在 -v 的 Utf8 条目） | 用 `javap -v` 的常量池 + 类文件字节双通道断言；中文一律搜字节 |
| Kotlin `const val` 断言字段引用失败 | const val 编译期内联到调用点 ldc，字节码无字段引用 | 断言查调用方反汇编里的 `// String 字面量` 或方法体内联常量 |
| javap 反汇编看不到私有方法体 | 不带 -p 时私有方法不反汇编 | `javap -p -c` |
| 冒烟 JVM YamlConfiguration save 静默失败 | paper-api 传递依赖缺失 | NF 冒烟用 NF 分发 JAR 自带 shaded 依赖即可；引用 paper-api 的场景需补 guava+adventure |
| 互转版 / 旧产物 NoSuchMethodError | 主版改了公开方法签名 | 保留 void 兼容桥 + 新结果方法（syncGroupPanels 教训） |

## 3. 部署流程（双端 JAR 上线）

1. **先 commit 再部署**（发版纪律：任何时点线上 JAR 必须能由 git HEAD 复现；1.6.6.3 曾出现线上 JAR 含未提交功能）。
2. 上传双 JAR 到 `/wwwroot/penguin/downloads/`：`python3 scripts/ssh.py --put build/dist/<Spigot JAR> /wwwroot/penguin/downloads/<名>`（NF 同理）。
3. `chown www:www` + `chmod 644`，`md5sum` 本地与线上**往返核对**（防传输损坏）。
4. **MC 服务端替换**：目标服务器不在 156.233.228.248 上（见 §1）——把 JAR 放到实际 MC 服务器的 plugins/（插件版）或 mods/（模组版），**重启 MC 生效**；数据目录：插件版 `plugins/KERONGPenguin/`、模组版 `kerongpenguin/`（同一绑定数据不互通迁移）。
5. 配置升级自动进行：磁盘 config-version 低于 JAR 内置 29 时启动自动补齐新键（只加新键+默认值，用户已改值一律原样保留——1.7.0 起无旧值迁移机制，**不要恢复它**）。

## 4. 官网发版流程（每次版本更新照此）

> 铁律：**改官网任何文件前必须先从线上拉当前版作底稿**（0.3AI v0.5 曾用旧底稿整文件覆盖，把已上线的 1.6.7.11 主下载框/历史表/更新日志整体回滚）。

1. **拉底稿**：`curl -sk -H 'Host: kerong.xin' https://127.0.0.1/penguin/{index.html,tutorial.html,style.css,version.json} -o <本地>`。
2. **改四个文件**：
   - `version.json`：新版本号 + changelog 数组**置顶一条**（保持 4 条；这是更新检查的数据源，忘改则老用户收不到提示）；
   - `index.html`：主下载框（版本/日期/副标题/两个按钮 href→新 JAR 名）→ 历史版本表加两行（新版本置顶，NF 在前 Spigot 在后）→ 更新日志加本版段落（**简化风格**：每版几条要点、每条 1-3 行；只保留最近 3 个版本详细块，更早的合并成「更早版本一览」一行一版）；
   - `tutorial.html`：仅当功能/行为/命令/配置有变化时同步（教程与插件注释保持一致的原则）；
   - `style.css`：仅改版时动（2026-11-03 起为**暗色主题**：深背景 #12151a、卡片 #1a1e24、边框 #2a2f36、文字 #d7dde3/#aab3bc、链接 #4da3ff；改内容时注意 index/tutorial 里的**内联色**也要用暗色系）。
3. **上传**：ssh.py --put → `chown www:www` + `chmod 644`（属主必须是 www:www）。
4. **curl 验证清单**（服务器本机 + 外网域名双通道）：
   - `version.json` 首字段 = 新版本号；
   - `index.html` **三处**版本号（主下载框 / 历史表首行 / 更新日志首段）+ 两个新 JAR 链接存在；
   - 两个新 JAR URL `200` 且字节数正确；
   - manifest.json / 源码包 URL `200`；
   - `curl -s https://kerong.xin/penguin/version.json`（外网直连，确认 DNS/证书路径也通）。
5. **源码包**：`git archive --format=zip -o /tmp/KERONGPenguin_v<版>_source.zip HEAD`（自动只含 git 跟踪文件；确认无 bpng/ 残留、约 6.7MB）→ 上传 `/wwwroot/penguin-source/` → 删旧包只留最新 3 个。
6. worklog 追加 + 本知识库同步（架构级变化时）+ 重新上传 AI-KNOWLEDGE.txt 到官网目录。

## 5. 功能全景与实现位置（摘要）

（文件路径相对 `common-Bot/src/main/kotlin/`；Spigot 版另有 `server-Spigot/src/main/kotlin/`，NeoForge 版在 `server-NeoForge/src/main/kotlin/`）

- **命令分发**：`bot/events/GroupMessageHandler.kt`（ReplySeqGuard 序号防重 → RecentMessageGate msg_id 去重 → 纯数字自动绑定 → 命令分发 → @机器人 AI（submitAsync）→ 全量转发（合并聊天记录占位 `[合并聊天记录]`））。命令用 `@Commands("名")` 注解，别名在 `spigot/manager/ConfigManager.kt` COMMAND_ALIASES。
- **消息模板**：`spigot/qqbind/MessageTemplates.kt` + 资源 `Markdown/*.md`（双端两份资源副本，**改模板双份都要改**）；文本回复统一 sendDirect（带@）/sendPlain，出口应用经济别名 EconomyName。
- **绑定/签到/黑名单**：`spigot/qqbind/QqBindManager.kt`（单例；QUUID 一人一文件数据布局；绑定码 4-8 位默认 5、有效期 10 分钟）+ `events/commands/BindCommands.kt`；签到 `CheckInCommands.kt`（1.7.2 起走 `qqbind/EconomyRewardService.kt`：Vault 金币 + PlayerPoints 点券 + CoinsEngine 备用，全部余额差值到账校验）。
- **统计卡/排行榜/查性能**：`events/commands/PublicCommands.kt` 等 → `spigot/stats/*Service.kt` → `spigot/render/*Renderer.kt`（CardRenderPool CPU 渲染；灰度 1.7.2 起可配 render.title-card-gray / render.item-card-gray；头像链路见 §6.3）。
- **背景图片包**：`render/BackgroundPackService.kt`（1.7.2 起单一官网源 + manifest 动态 count/bytes + image-download.enabled 开关）+ 双端命令层 bpng/bpngsz。
- **命令执行器**：Spigot `commands/BukkitConsoleSender.kt` / `HybridCommandExecutor.kt`（1.7.2 起派发自定义 Sender）+ `commands/CommandOutputAppender.kt`（噪音黑名单）；NF `neoforge/NeoForgeConsoleExecutor.kt`（1.7.2 起 withSource 包装收集）；共享噪音过滤 `bot/tools/ConsoleNoiseFilter.kt`。
- **快捷面板**：`bot/MenuManager.kt`（先删后建 + 20 按钮上限裁剪 + 30013 退避 + QPM 节流 + 分页；公开签名改动前先想互转版兼容）。
- **连接/重连**：`bot/QClient.kt`（kloping SDK Starter）、`bot/WssDiagnostics.kt`（钩子必须 return true！）、`bot/ReconnectManager.kt`（5 次失败挂起 30 分钟，/hbkrg chs 手动重试）。
- **平台适配**：Spigot 真 Bukkit API + FoliaCompat 调度桥；NF `org.bukkit` 兼容层（编译进 NF 的 Spigot 源码只有 manager 目录）；关服 SptCompat 清扫线程池 + halt(0)。
- **公开 API**：`KERONGPenguinAPI`（第三方插件用）。
- **互转版 Cross-posting / 0.3AI**：独立产品线，源码不进公开仓库，工作流见 §10/§11。

## 6. 核心机制与兜底（排查问题先看这里）

### 6.1 日志体系
业务代码 → `PluginFileLog.infoAndKeep/warnAndKeep/errorAndKeep` → 平台适配器 → 控制台 + `logs/qq/qq-bind-日期.log` 双写；`PluginFileLog.write` 仅文件。控制台行不要再拼时间戳。SDK 日志经 LogSink 接管（重连噪声仅文件）。

### 6.2 配置系统（config-version 29）
`ConfigManager.kt`（共享源码）：DEFAULT_VALUES 全量默认 + `ConfigUpgrader.fillMissing` 补缺失键（新键升级自动写入）+ REMOVED_CONFIG_KEYS 清废弃键。**1.7.0 定下的原则：升级只加新键+默认值，不做任何旧值迁移**。语法错误三级自愈（ConfigBrokenRecovery）。绝大多数配置 reload 即时生效；仅 bot.app-id/bot.secret 与 update-check 需重启。

### 6.3 渲染管线与头像获取（1.7.2 口径）
- 渲染：CardRenderPool（核数一半 1~4 线程）→ 结果缓存 → 背景内置+img 随机 LRU（36MB 字节预算）；模糊度 render.blur-strength（0-100 默认 50，backdrop-filter 只模糊卡片背后）；灰度 render.title-card-gray（默认 142）/ render.item-card-gray（默认 106）。
- 头像链路：内存缓存（10 分钟，128 条，只存 ≤10KB PNG）→ 磁盘缓存（默认 3 小时，render.avatar-cache-hours）→ **外部多线路**（MC-Heads → Minotar(helm) → MinePic(skin+裁脸) → Crafatar(overlay)）→ Mojang 官方兜底（429/超时 5 分钟全局冷却）。**1.7.2 超时修复**：头像 HTTP 走 httpGetAvatar（连接 5s / 读 25s 分离），任一线路超时本轮内立即换下一源，第一轮全败且超时型时对快线路补试一轮；超时型失败不写 60 秒冷却（下次查询立即重试），只有确定性失败（404）才写冷却。单飞 inFlightFetches 并发去重（等待 8s）。基岩玩家走 MC-Heads xuid 线路。
- 失败分类日志（logs/qq 文件）：「获取失败（本次链路遇网络抖动，未写失败冷却…）」= 超时/网络型，正常降级；「获取失败（本地与外置链路均未命中，60 秒内不重试）」= 确定性失败。

### 6.4 QQ 绑定拦截
老玩家登录期拦截（Spigot PlayerLoginEvent.disallow / NF BindLoginGate 配置阶段断开）；新玩家建档后踢出展示验证码；群里**直接发送纯数字验证码**（4-12 位）自动绑定或 /绑定 <码>；验证消息文案「可以直接发送纯数字验证码：xxx」（1.7.2）。

### 6.5 背景图片包系统（1.7.2 口径）
`BackgroundPackService.kt`：清单 `{packs:[{id,name,description,count,bytes,files:[{name,size,md5}]}]}`（count/bytes 为 1.7.2 新增展示字段，缺失回退 files 统计）；下载源**仅官网** `https://kerong.xin/penguin/bpng/`；下载逐张进度/中断/断点续传/md5 校验/.part 原子写/64MB 熔断；`/hbkrg bpngsz yes|no` 五键一键切换；`image-download.enabled`（默认 true）关闭时 bpng 列表与下载入口拦截。**新包上线用 `scripts/bpng_upload.py`（本地）或服务器端 make_bpng_manifest.py + 手动部署**（清单驱动，插件零更新）。

### 6.6 spark 探测与性能读数
Paper 1.21+ 内置 spark 不注册插件表（bundledSparkClassLoader 探测）；TPS 语义上限 20 三层封顶（tick sprint 期 getTPS 可达数百是真实 tick 率）；MSPT 如实；指标固定五项 TPS/MSPT/内存/CPU/在线人数。

### 6.7 更新检查
`bot/UpdateChecker.kt`：启动后 8 秒 GET 官网 version.json（镜像 IP 直连源因 Java 无 SNI 从未生效，无害遗留）；逐段数字比较；有新版控制台提示（含 § 颜色码最多 6 行）。

## 7. 书写与产品口径（用户明确要求过的风格）

- 更新日志简化（每版几条要点、每条 1-3 行；官网只留最近 3 版详细 + 「更早版本一览」一行一版；README 同样最近三版）。
- 教程小白向（步骤化、命令代码块、技术细节收进末尾卡片）；改教程/下载页后插件版与模组版同步说明。
- 主下载按钮在版本号下方（Spigot 在前 NF 在后，max-width 440px 居中）。
- 官网 2026-11-03 起暗色主题（布局不动只换色板，见 §4）。
- 渲染图片设计标注统一「设计参考」（BaiZhouziYou / 新亭泪，issue #1）。

## 8. 历次踩坑记录（根因 + 解决步骤）

### 8.1 1.7.2 三大 case 全记录（本次发版修复）

**① 头像获取部分失败（用户判定"8 秒超时被直接中断"）**
- 现象：批量报「获取失败（本次链路遇网络抖动，未写失败冷却，下次查询立即重试；本张渲染占位块）」，下一轮查询重试即成功（成功日志「头像 xxx ← Minotar（名字线路）」）。
- 根因：头像下载 HTTP 的读超时过短（旧版连接/读同为 3 秒）——慢速可用的源流读到一半被 SO_TIMEOUT 切断；在线列表路径另有 8 秒预热预算（invokeAll）会中断整批，尾部玩家渲染占位块。SO_TIMEOUT 是**单次 read 阻塞**上限，数据在流动就不断重置，不是总时长——拉长只影响真正断流的连接。
- 解决步骤：a) 新增 httpGetAvatar（连接 5s / 读 25s 分离，头像链路全走它）；b) 任一线路超时后本轮渲染内立即换下一头像源（elvis 链既有行为明确化）；c) 第一轮全败且属超时型（networkTrouble 标记）时对 MC-Heads / Minotar 快线路补试一轮（fetchExternalAvatarOnce(retryPass=true)）；d) 超时型失败继续不写 missCache 冷却（既有机制保持）；e) 真失败（HTTP 404 明确无此玩家）的 60 秒冷却不动。
- 验证：门禁 AV 组 6 项（字节码常量 5000/25000、方法在位、旧三参方法移除、4 处调用点）。

**② /执行命令 返回混入控制台噪音（[MediaSource] 刷屏、[Not Secure] 行）**
- 现象：QQ 返回混入「[MediaSource] 布谷鸟时钟 BlockPos{…} 媒体=(空) mode=FIT …」高频刷屏与「[Not Secure] [Server] 1」。
- 根因：旧架构把命令派发给**真实控制台**（`Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)` / NF `server.createCommandSourceStack()`），命令输出落进控制台日志，插件只能靠 **log4j 根 Logger 的 Appender 全量捕获窗口内（2 秒）所有日志行**来拿输出——窗口里所有异步日志（NeoForge 模组周期日志、聊天行）都进了返回。
- 解决步骤：a) Spigot/混合端：改为 `Bukkit.dispatchCommand(this, cmd)` 派发给自定义 ConsoleCommandSender（BukkitConsoleSender 本身就是），只有其 sendMessage 回调收到的行进返回；b) NF：`createCommandSourceStack().withSource(CollectingCommandSource)` 包装命令源（sendSystemMessage 收集；withSource 个别版本缺失时 catch Throwable 回退）；c) 命令无 Sender 输出（只往 logger 打的命令）才回退到窗口日志；d) 兜底黑名单 ConsoleNoiseFilter（消息前缀 `[MediaSource]`/`[Not Secure]` + logger 名含 MediaSource 双通道），双端 Appender 副本接入；e) 验收标准：返回 = 该命令实际产生的输出，无多余行。
- 验证：门禁 EX 组 9 项（双执行器无 getConsoleSender、NF withSource、CollectingCommandSource、ConsoleNoiseFilter 双 JAR、双 Appender 接入）+ 冒烟 NZ 组 5 项。

**③ bpng 列表「（29 张，约 58.1 MB）」疑似硬编码**
- 现象/判定：用户认定列表的张数与体积是硬编码。核查：插件端其实一直按 manifest 的 files 数组动态计算，但清单**没有显式的 count/bytes 字段**，且 description 文案里嵌了"29 张"字样——观感与维护口径都是硬编码。
- 解决步骤：a) manifest 每包新增 count（文件数）/bytes（总字节数）展示字段；b) 插件 parseManifest 读取，PackInfo.count/totalBytes 优先声明值、缺失回退 files 统计（向后兼容）；c) 官网 wl1 清单补写 count=29 / bytes=60926352（=58.1MB，与旧显示一致）；d) 上传工具 bpng_upload.py 自动生成 count/bytes；e) make_bpng_manifest.py 同步输出新字段。
- 验证：门禁 BP 组 + 冒烟 S/H 组（声明值优先、旧清单回退、列表行渲染声明张数体积）。

### 8.2 历史坑速查表（保留全部，避免重蹈覆辙）

| 坑 | 根因/表现 | 解法 |
|---|---|---|
| SDK 钩子返回 false | 拦截 SDK 自身处理（鉴权/心跳/重连全断）→ 1006 死循环 | WssDiagnostics 回调必须 return true |
| fat JAR 无目录条目 | SDK 组件装配 NPE，机器人启动失败 | 打包脚本 dir_count 熔断 |
| NeoForge 底座用错 | NF 打包底座必须是 Spigot 版 JAR；产物体积骤降=类丢失 | 传本版 Spigot JAR 作 base |
| NF 平台包冲突 | org/slf4j、com/google/gson 必须剔除（split package） | EXCLUDE 前缀固化在打包脚本 |
| kloping SDK 对 4xx/5xx 不抛异常 | 「调用没抛异常」≠「平台已接收」 | 判定送达必须查 V2Result.id 非空 |
| Java 连 IP 的 HTTPS URL 无 SNI | 本站 nginx handshake_failure；Host 头属 JDK 受限头被静默忽略 | 弃用 IP 直连源（UpdateChecker 内置镜像从未生效，无害遗留） |
| volatile 取消标志残留 | 标志只在"下一次动作开始"重置 → 中断一次后清单获取永久失败 | 随"一次完整操作"结束 finally 清除 |
| Kotlin 尾随 lambda 绑定最后形参 | 函数式参数不在签名最后时尾随 lambda 落错位 | 函数式参数一律放最后 |
| fine-grained PAT 只读判定 | API 读通 + push 403 + 建 ref 报 Resource not accessible = 令牌只读 | 向用户要写权限令牌（经典 ghp_ 或 Contents:Read and write） |
| gh-proxy 不转发 push 鉴权 | push 报 No anonymous write access | push 走官网服务器中继（bundle→bare.git→带 PAT 直连） |
| 官网整文件覆盖回滚 | 用旧底稿整文件上传覆盖了已上线内容 | 改前必拉线上当前版作底稿；上传后 curl 复核版本号三处 |
| 复用旧 build 目录 | 空目录/旧字节码被 Gradle 打进产物 | 发布前 gradlew clean 全量重建 |
| 终端显示吞转义 | 工具输出管道吞 `[h`/`[[mods]]` 显示层 | 断言一律以文件字节为准（hexdump/tomllib） |
| 宣称未实装（三次教训） | 宣称修复但字节级核实未落位 | 宣称前必须对产物做字节级核对 |
| mod 总线 @SubscribeEvent 参数约束 | 跨版本无满足条件的公共类型 | 反射 addListener(Class, Consumer) 按具体事件类注册 |
| 冒烟必须覆盖真实总线注册 | 只测 Class.forName 不测注册→分发 | FML 风格总线端到端 + 负向对照 |
| Write 工具损坏 \uXXXX 转义 | 转义序列写成 NUL 字节 | 特殊序列用 Python 字节级写入 |
| Kotlin 块注释嵌套 | 注释里 `/*` 相邻序列吞掉后面代码 | 注释里避免 glob/路径相邻 `/*` |
| MultiEdit 非原子 | 部分成功后报错"未执行" | 失败后先 rg 检查文件实际状态 |
| drawImage 十参目标/源矩形序 | 写反时等尺寸静默正确、cover 时整画布空白 | 几何改动必须不同宽高比像素级端到端断言 |
| javap 三坑 | 平铺放置常量池降级截断 / 两形态字符串 / Kotlin 模板 recipe 在 BootstrapMethods | 按包路径展开 + -v 常量池 + 字节双通道断言 |
| internal 方法模块后缀 | cardHeight$common_Bot vs $server_NeoForge | 反射按前缀动态查找 |
| Bukkit YAML 流重载 | paper-api 已删 InputStream 重载 | 双端共用源码解析流走 Reader |
| snakeyaml 单元素列表单行 | dump 形态判断误当标量 | 序列化分支按输入类型分派 |
| YamlConfiguration.loadConfiguration 吞异常 | 返回空配置不抛出 | 失败可感知场景用实例方法 yaml.load(reader) |
| YAML 引号内空白 | AppID 带空格 → QQ 平台 100007 | sanitizeCredential/trimInvisible 清洗 |
| Paper 1.21+ 内置 spark 不注册插件表 | getPlugin("spark") null ≠ 没有 spark | bundledSparkClassLoader 候选链探测 |
| tick sprint 期 TPS 数百 | getTPS 反映真实 tick 率 | TPS 展示一律封顶 20（capture 三层） |
| grep -c 打断命令链 | 无匹配 exit 1 中断 && 链 | 错误计数检查单独执行 |
| Python set 的 in 是成员判定 | `"子串" in set` 是精确匹配 | 常量池断言用 any(sub in c for c in consts) |
| 快速守卫与新语法脱节 | 入口 contains 守卫挡住新形态 | 守卫放宽到公共前缀 + 冒烟覆盖每种形态 |
| 双端资源副本 | NF resources 有自己的 Markdown/ 全量副本 | 删改模板资源双份都要动 |
| JVM 已提交≠存活 | 已提交堆是 JVM 预留量 | /hbkrg mem 诊断；不要 System.gc 归还 |
| 停机 zip closed | 停机阶段提前关插件 jar ZipFile | onEnable 预热 CommandOutputAppender；onDisable 每步独立 try-catch |
| Kloping 线程池不退出 | 50+ 非守护线程 | SptCompat 清扫 + 5 秒 halt(0) |
| 死代码判定 | 反射入口（@Commands/@SubscribeEvent）不算死 | 查三模块+脚本+资源+反射再删 |

## 9. 1.7.2 逐项改动清单（用户 19 项待办 → 落地情况）

| # | 待办 | 状态 | 落点 |
|---|---|---|---|
| 1 | 删除 git 图片包镜像下载线路 | ✅ | BackgroundPackService.DOWNLOAD_SOURCES 只留官网；git 仓库 bpng/ 移出 + .gitignore；make_bpng_manifest.py 清镜像表述 |
| 2 | bpng 列表动态读取 | ✅ | manifest 新增 count/bytes；PackInfo.declaredCount/declaredBytes + 回退兼容；官网 wl1 清单已补（count=29/bytes=60926352） |
| 3 | 头像 8 秒超时修复 | ✅ | httpGetAvatar 连接 5s/读 25s；超时换源 + 快线路补试轮；超时不写冷却保持；见 §8.1① |
| 4 | /执行命令 噪音 | ✅ | 双端派发自定义 Sender / withSource 包装；噪音黑名单 ConsoleNoiseFilter；见 §8.1② |
| 5 | bpng_upload.py 上传工具 | ✅ | /home/z/my-project/scripts/bpng_upload.py（SFTP+服务器端解压+manifest 生成+chown+curl 验证） |
| 6 | image-download.enabled 开关 | ✅ | 配置默认 true；双端 bpng 入口拦截；bpngsz 不受影响 |
| 7 | 签到经济插件检测 | ✅ | EconomyRewardService（Vault/PlayerPoints/CoinsEngine 全反射+余额差值校验）；points-reward 配置；plugin.yml softdepend+CoinsEngine；⚠️ 用户实际 MC 服务器装了哪些经济插件未核实（服务器不在本机） |
| 8 | QQ 绑定验证文案 | ✅ | kick-message「可以直接发送纯数字验证码：xxx」；bind-invalid 一句话 |
| 9 | 项目卡片灰度 | ✅ | 64→106（(120+64)/2=92 偏旧值一侧）；render.item-card-gray |
| 10 | 标题卡片灰度 | ✅ | 恢复旧值 142；render.title-card-gray |
| 11 | 灰度参数进配置 | ✅ | 双键 0-255 实时读取；四渲染器接入；硬编码清除 |
| 12 | 设计来源统一标注「参考」 | ✅ | 四渲染器 KDoc + README + 官网节标题 |
| 13 | 官网暗色调 | ✅ | style.css 深背景/深卡片/浅文字；index/tutorial 内联色同步；布局不动 |
| 14 | 版本号 1.7.2 + 更新日志 | ✅ | 六处版本号 + README + version.json + index 日志块 |
| 15 | 构建双端 JAR | ✅ | 门禁 verify_jar_172 63 项全过 + 冒烟 smoke172 20 项全绿 |
| 16 | 部署 | ✅（官网）/ ⚠️（MC） | 双 JAR + 源码包 + 官网已上线 curl+md5 验证；**MC 服务端不在 156.233.228.248 上**，JAR 替换与重启需用户在实际服务器执行 |
| 17 | 官网发版 + curl 验证 | ✅ | version.json/index（三处版本号）/tutorial/style 200；manifest count/bytes；外网域名复核 |
| 18 | git 推送 | ⏸ | 发版提交 dd9ca25 已本地提交（不含图片包）；**push 待用户提供仓库写权限令牌**（中继流程见 §0） |
| 19 | 交接文档细化重写 | ✅ | 本文档（新增 §2 构建指南 / §3 部署 / §4 官网发版 / §8 踩坑根因 / §9 逐项清单） |

**1.7.2 配置变更汇总**：config-version 28→29；新增 `image-download.enabled: true`、`render.title-card-gray: 142`、`render.item-card-gray: 106`、`qq-bind.checkin.points-reward: 0`（只加新键，不做旧值迁移）。

## 10. Cross-posting 互转版（1.6.7_Cross-posting.3）

多群互通分支，仅 Spigot 插件形态，**源码不进公开仓库**、永不 push、用户要求才更新。1.6.7.10 起工作流为「精准覆写打包」（scripts/package_crossposting_16710.py，以线上互转版 JAR 为底座仅覆写 MenuManager+KERONGCommand 类）；前提是 MenuManager 保持 void 兼容桥（互转版旧产物按旧描述符链接）。主版改动若触及 QClient/GroupMessageHandler/ConfigManager/KERONG/CrossPosting 三件套，精准覆写不可用。官网「其他版本」卡片展示，教程全部内置在 config.yml 注释。

## 11. 0.3AI 专属版（v0.5）

极简分支（连接机器人 + QQ 群 AI 对话），独立插件名/版本号，仅 Spigot 形态，无更新检查；源码本地 `/home/z/my-project/ai-git/`（不进公开仓库），服务器灾备 `/root/ai-source/`。构建走 kotlinc 直编（tooling/ 内 kotlin-compiler-2.2.0.jar + coroutines + reflect，classpath 另需 paper-api/adventure 四件）+ package_ai_05.py 精准覆写打包；门禁 verify_ai_05.py 138 项 + 冒烟 TestAi05 120 项。QQ 平台事实：面板按钮 name 即点击后发送的命令文本；用户标识是 per-bot OpenID；access token 从 starter.APPLICATION.INSTANCE.contextManager 取。更新节奏：用户要求才更新；version.json 永不动。

---

## 12. 历史版本归档（要点版，详细日志见 git log 与官网）

- **1.7.1（2026-11-02）**：背景图片包系统（bpng 下载/bpngsz 一键切换/中断标志残留修复）；官网 bpng/ 目录 + git 镜像（1.7.2 已删除）；门禁 48 + 冒烟 36；push 受阻于只读 PAT（bundle + 中继就绪）。
- **1.7.0（2026-11-02）**：头像统一在线获取 + 外层纹理修复（Minotar /helm、MinePic /skin 裁脸）；毛玻璃统一（blur-strength 可配，gray-mask/global-opacity 删除）；/hbkng→/hbkrg；/执行命令 真根因修复（SDK 4xx 不抛异常按 V2Result.id 判送达 + 控制字符清洗 + NF 定格 bug）；配置精简（旧默认值迁移机制删除）；门禁 119 + 冒烟 24。
- **1.6.9.5（2026-11-01）**：blur-mask 改 backdrop-filter 局部模糊；背景 cover 裁剪（十参 drawImage 参数序 bug 冒烟抓出）；[合并聊天记录] 占位；/kerong→/hbkng；/执行命令 回复修复（1200 字符裁剪 + Boolean 回退）；reload 一并同步面板。
- **1.6.9.4（2026-11-01）**：背景遮罩样式 render.background-style；合并转发防刷屏；reload 边界写明；AI 对话与 reload 异步化。
- **1.6.9.3（2026-11-01）**：变量获取扩展（玩家名/TPS/自定义项目）；查性能最大人数；删 /在线服务器 与 /motd。
- **1.6.9（2026-11-01）**：变量获取信息 variable-fetch（默认关）。
- **1.6.8（2026-11-01）**：时长单位小时化；排行榜头像修复（官方线路移到最后兜底）。
- **1.6.7.11（2026-10-31）**：NeoForge 登录闸门挂载真修复（反射 addListener 跨版本注册）。
- **1.6.7.10（2026-10-31）**：快捷面板 30013 真根因（单面板 20 元素上限 + 先删后建 + 退避 + QPM 节流 + 分页）；mods.toml 段头字节级修复；互转版 .3 精准覆写工作流。
- **更早（1.6.7.x 及以前）**：spark 反射链修复与五态探测 / TPS 封顶 / 凭据清洗 / 配置三级自愈 / Folia TickMonitor / 头像多线路与缓存体系 / 面板 API 体系 / 双端架构奠基——详见 git log（提交信息均为长信息风格全量记录）。

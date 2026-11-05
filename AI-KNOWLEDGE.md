# KERONGPenguin AI 交接知识库

> **本文档的用途**：把本文件丢给一个对项目一无所知的 AI，它能立刻了解当前情况并接手工作。
> 面向：接手开发/发版的 AI 助手。最后更新：2026-11-05（**1.7.3 覆盖版三轮部署**：用户两项——①主标题框顶边距继续加大：三列表卡 HEADER_HEIGHT 164→182、眉题基线 46→64、主标题 118→136，眉题视觉顶边距 27→45px **对齐查信息最上框（像素实测 45px）**（+18 全部给顶部；底边距 42px 与眉题→主标题间距不变；徽标卡随框加高 136→154、内容下移 9px 保持居中、四周 14px 间距不变；**副标题框零改动**——用户口径：间距调的是主标题框，副标题框一切正常，副标题条顶 40+182+18=240）；②副标题条灰色小标签调深：四渲染器新增专用色 COLOR_SUB_LABEL 0x9FB3C8→0x8296AA（亮度 175→146），只作用于副标题条标签（服务器/更新时间/展示玩家/渲染耗时），眉题/徽标标签/行内数值不变。验证：门禁 verify_jar_173_round3 **120 项**全过（R3 组：源码 182/0x8296AA/COLOR_SUB_LABEL 接线 + 字节码 bipush 64·51·109/sipush 136·130·150·170/ldc 182.0f·240.0f·84.0f + 二轮项坐标更新 + RC 一轮项沿用）+ 冒烟 smoke173 **51 项**全绿（R3 组 4 项：眉题顶边距精确 45px/主标题底边距 ≥40/副标题框几何不变（间隙带 min 255、条内黑卡、白字 2592）/标签核心亮度 146<160——R3d 旧色像素计数假命中教训：Java2D 文字抗锯齿经 gamma 校正的非线性混叠色（实测 #A5B3C2）恰穿旧色附近，核心像素判定必须用最暗像素法而非颜色计数）+ VLM 三卡头部与双卡标签复核全过；无新增配置键、config-version 32 不变；同版本号覆盖上线 + push）。前次 2026-11-05（1.7.3 覆盖版二轮部署：用户三项——①标题框上边缘间距加大（三列表卡 HEADER_HEIGHT 150→164、眉题基线 38→46、主标题 108→118，眉题视觉顶边距约 18→26px）；②右上角小方框与标题框四周留 14px 可见间距（旧上/下 8px、右侧 0px 与标题框齐平；cardInset=14，标题区加高后徽标卡尺寸不减）；③排行榜副标题第三格「渲染耗时」→「展示玩家」显示 xTOP（= min(统计玩家数, 显示上限)，如 8TOP；drawRenderElapsed 计时补画从 LeaderboardRenderer 移除，查信息/查在线/查性能保留）。验证：门禁 verify_jar_173_round2 **92 项**全过 + 冒烟 smoke173 **46 项**全绿（新增 R2 组 6 项：顶部 22px 无文字/右·上·下条带均为标题底色 175/两次渲染字节一致/展示玩家白字）+ VLM 三卡头部复核全过；无新增配置键、config-version 32 不变、展示玩家四字已在字体子集无需补字；同版本号覆盖上线 + push）。前次 2026-11-05（1.7.3 覆盖版部署完成：沙盒回收后环境从零重建→重编译重打包→门禁 69+冒烟 40+VLM 四卡全过→双 JAR 官网覆盖上线；同轮补遗：双端资源 config.yml config-version 31→32（上轮漏改资源模板）+ 覆盖版门禁脚本 verify_jar_173_overwrite.py 入库 69 项；git push a6806e6；交接副本已放服务器 /SUZHE014/AI-KNOWLEDGE.txt）。前次 2026-11-05（1.7.3 覆盖版：标题内边距 / 徽标灰度 / 综合状态 / 副标题灰度调深 56 / 渲染耗时 / 等距修复 / 自定义渲染线程 / 字体补「渲」字，同版本号覆盖发布；沙盒回收后从官网源码包恢复源码）。前次 2026-11-04（1.7.3 版：标题卡全宽化 / 副标题灰度与副标题条 / 排行榜单页上限 / 今日在线跨天修复；同日官网互转版卡片重新上线）。前次 2026-11-04（官网清理轮：删 1.7.0 前历史版本 / 更新日志 / 下载本体、教程按小配置重构、设计来源全量口径、下载卡片去简介日志）。
> 本轮覆盖版三轮（2026-11-05，用户两项）：①主标题框顶边距 27→45px 对齐查信息最上框（像素实测法：白底黑卡渲染后扫描眉题首行亮像素 y85-框顶 40=45px；查信息欢迎面板同法实测 45px——三轮起头部几何一律以像素实测对齐而非估算）；②副标题灰色小标签 COLOR_SUB_LABEL 0x8296AA（四渲染器独立常量，不动 COLOR_LABEL 其他用途）。门禁 verify_jar_173_round3 120 项 + 冒烟 51 项（R3 组 4 项）+ VLM 复核全过；无配置变更。
> 本轮覆盖版二轮（2026-11-05，用户三项微调）：①标题框上边缘间距还是太近——三列表卡标题区 150→164px、眉题基线 38→46、主标题基线 108→118；②小方框与标题框四周间距太小——cardInset 14（上/右/下，旧 8/8/0；cardX 加右缩进、cardHeight=HEADER-2*inset=136）；③排行榜副标题渲染耗时改展示玩家（xTOP=min(统计玩家数,显示上限)，渲染计时补画逻辑同步删除——两次渲染字节级一致）。门禁 verify_jar_173_round2 92 项 + 冒烟 46 项（R2 组 6 项）+ VLM 三卡头部复核全过；无配置变更。
> 本轮覆盖版新增修复：①config.yml 注释保留保存（NeoForge 端 bpngsz/配置保存后注释全丢——兼容层整树 dump 剥注释，复现实测 231→0 行，见 §8.1④）；②图片标题框黑度调不动（四张卡片顶部标题区硬编码半透明白，改 render.title-card-gray 不生效，见 §8.1⑤）；③QQ 远程执行命令控制台无记录（[QQ 远程命令] 前缀转发留档）；④QQ 手动重连过程回显到群；⑤移除升级备份 config.yml.bak（损坏恢复的 .error 应急备份保留）。config-version 29→30（注释精简重写 + 标题框旧默认 96→80 一次性收敛）。门禁 89 项 + 冒烟 39 项全绿。
> 本轮（2026-11-04 官网清理，用户五项，仅动官网与本文档）：①删 1.7.0 之前的历史版本（index 历史表 / 更新日志「更早版本一览」块 / version.json 1.6.9.5 条目）与下载本体（服务器 downloads/ 15 个 1.6.x JAR 移至 /root/penguin-archive-downloads/ 归档而非 rm，含互转版底座 JAR），官网只留 1.7.0/1.7.1/1.7.2 + 0.3AI 线；②更新日志简化（三版各 3-6 条短要点，version.json 同步 3 条）；③下载卡片去掉简介日志行（只留版本号 + 发布日期）；④教程「常用设置」重构为每个小配置独立条目（.cfg-item：2-8 行带父级上下文的 YAML 片段 + 一两句描述，共 49 条；style.css 新增样式 + 手机端 pre-wrap），废弃「一个总配置块 + 一大串文字」写法；⑤设计参考改全量口径——图片渲染**全部**设计（卡片版式 / 毛玻璃 / 背景 / 头像呈现）都参考 BaiZhouziYou / 新亭泪 的 EasyBot 模板，删除「其后的分页、背景、头像渲染等为本项目的独立改进」表述；互转版官网卡片随 JAR 归档一并删除。0.3AI 线三个 JAR 未动（不在「1.7.0 之前的历史版本」范围）。
> 本轮（2026-11-04，v1.7.3）：①标题卡全宽化——查在线 / 排行榜（含财富榜）/ 查性能顶部标题区改整块全宽卡（模糊+灰度+圆角），旧版标题文字直画背景上且灰度只作用于右侧小方框；②新增 render.subtitle-card-gray（默认 40，明显低于 80/106）——查信息玩家名称项（旧硬编码白 22 调不动）+ 五张卡片副标题条；③副标题条（服务器/更新时间/状态）新增到在线排行榜/财富榜/查性能/查信息（查在线原有改接副标题灰度），性能监控原服务器名小字移除；④leaderboard.page-size（全局，默认 20，旧硬编 15）两命令接入；⑤今日在线跨天误报修复（rolledForToday 展示层归零，存档不动）；⑥同日官网：互转版卡片重新上线（简版）+ JAR 从归档放回 downloads/。门禁 92 项 + 冒烟 27 项全绿。
> 本轮覆盖版（2026-11-05，用户 7 项视觉/功能修复 + 复核）：①标题卡文字内边距（三列表卡眉题/主标题距卡缘 4/8px → 28/24px，对齐查信息欢迎面板）；②右上角小方框永远比标题框灰度深一点（headerBadgeCardGray = titleGray+36，旧同灰度叠加融合看不出）；③性能卡副标题条与标题框/指标容器等距（SUBTITLE_GAP 18→32，旧版与容器只隔 4px）；④查性能右上角「更新时间」→「综合状态」（TPS/MSPT/内存/CPU 四项各评三级取最差，不含人数，绿/黄/红）；⑤副标题灰度调深 40→56（仍低于标题 80/项目 106；旧默认自动收敛）；⑥副标题第三格「状态」→「渲染耗时」（render 入口 nanoTime → 全部面板绘制完成后补画 ms → PNG 编码；非从接收指令起算；VLM 实测 29~253ms）；⑦render.custom-render-threads 段（enabled/render-threads/avatar-threads，线程池创建时定格需重启，⚠ 警告注释双端）。字体子集缺「渲」字由 VLM 视觉复核抓出（图上「渲染耗时」显示成「染耗时」三字——历史图片从未上过该字；fonttools 从系统 NotoSansSC 重新子集化 3889→3890 字符）。门禁 68 项 + 冒烟 40 项 + VLM 四卡视觉复核全过。config-version 31→32。
> 自包含版本（发给本地无任何东西的 AI）见官网 `https://kerong.xin/penguin/AI-KNOWLEDGE.txt`；服务器根目录 `/SUZHE014/` 另存一份交接副本（2026-11-05 用户指令）。

## 0. 一句话概括与版本状态

KERONGPenguin 是一个 **Minecraft 服务器 + QQ 官方机器人联动**的项目：玩家在 QQ 群里查数据/签到/聊天，插件在服务器内渲染统计卡片图片并回发。Kotlin 多模块：`common-Bot`（全部业务核心）+ `server-Spigot`（Bukkit 插件版，支持 Spigot/Paper/Folia/混合端）+ `server-NeoForge`（NeoForge 模组版 **MC 1.20.4+**：1.20.4 / 1.20.6 / 1.21.x 全区间，编译目标 20.4.253 面向最低版本、在更高版本运行；复用 common-Bot 全部源码 + Spigot 的 manager 源码；mods.toml + neoforge.mods.toml 双声明，BindLoginGate 跨版本事件桥，产物名 MC 段 = 1.20.4）。

**版本规则**：插件版与模组版同步发号。当前版本 **1.7.3（覆盖版三轮，2026-11-05）**（标题卡全宽化与内边距 / 徽标灰度与综合状态 / 副标题条与渲染耗时 / 自定义渲染线程 / 排行榜分页配置 / 今日在线跨天修复 / 二轮：标题框上边距 + 徽标四周间距 + 排行榜展示玩家格 / 三轮：主标题框顶边距 45px 对齐 + 副标题标签调深，详见 §9）。config-version **32**（新增 render.custom-render-threads 三键；subtitle-card-gray 旧默认 40→56 一次性收敛）。

**git push 状态**：✅ 已完成（2026-11-05 部署轮实测）——远端 main = `a6806e6`（恢复基线 6c10da7 + 覆盖版 3f41c59 + 文档 a7bc293 + 部署轮补遗 a6806e6）。**ghfast.top 代理支持带鉴权 push**（`git push https://ghfast.top/https://github.com/SUZHE014/KERONGPenguin.git main:main` + GIT_ASKPASS 令牌文件，本轮再次实测成功；注意沙盒本机直连 ghfast.top clone/fetch 可能超时——此时用服务器中继：服务器侧 ghfast.top 可达，`git -C /root/gitrelay/KERONGPenguin.git fetch https://ghfast.top/https://github.com/SUZHE014/KERONGPenguin.git +main:relay-main` 强推更新后 `git bundle create /tmp/x.bundle relay-main` 拉回本地再 fetch FETCH_HEAD，历史若重建过需 + 强制）。github.com 直连 push 超时不可用、api.github.com 直连可用；经典令牌 ghp_ 对仓库有 admin/push 权限。令牌经 /tmp/.ghaskpass（umask 077）+ GIT_ASKPASS 读取，用完即删。沙盒本机 git 环境重建：git init -b main → fetch bundle → git reset --mixed FETCH_HEAD（不能直接 fetch 到已 checkout 的 main 分支）。

## 1. 环境与位置（必读）

| 项 | 值 |
|---|---|
| 本地仓库 | `/home/z/my-project/penguin-git`（GitHub `SUZHE014/KERONGPenguin`，main 分支；新环境从服务器中继仓恢复：`git -C /root/gitrelay/KERONGPenguin.git bundle create /tmp/x.bundle relay-main` → scp 拉回 → clone 后 checkout） |
| 官网服务器 | `156.233.228.248`（宝塔 nginx，站点 kerong.xin；SSH root） |
| ⚠️ MC 服务端 | **不在这台服务器上**（2026-11-03 实测：无 java 进程、无 25565 端口、无 MC 目录；此前"同机跑 MC"的说法不成立）——插件 JAR 的实际部署目标由用户提供，替换 JAR 后重启 MC 生效 |
| 官网目录 | `/wwwroot/penguin/`（index.html 下载页 / tutorial.html 教程 / style.css / version.json 更新检查文件 / AI-KNOWLEDGE.txt 本文档网页版）；**四文件自 2026-11-04 起同时跟踪在仓库 `website/` 目录**（与线上保持同步，环境重置后从仓库恢复底稿；改前仍遵守「先拉线上当前版」铁律） |
| 产物目录 | `/wwwroot/penguin/downloads/`（1.7.0 起双端 JAR + 0.3AI 线 + **互转版 KERONGPenguin_Spigot-1.6.7_Cross-posting.3.jar（2026-11-04 用户指令恢复，长期保留勿删）**；其余 1.7.0 前旧 JAR 归档在服务器 `/root/penguin-archive-downloads/`） |
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
5. 配置升级自动进行：磁盘 config-version 低于 JAR 内置 30 时启动以新版 config.yml 为底重建（注释/结构全刷新、用户已改值原样保留；**不再生成备份文件**——1.7.2 覆盖版移除；仅标题框旧默认 96→80 一次性收敛，其余无旧值迁移——1.7.0 起该机制原则上不恢复，**不要顺手加回**）。

## 4. 官网发版流程（每次版本更新照此）

> 铁律：**改官网任何文件前必须先从线上拉当前版作底稿**（0.3AI v0.5 曾用旧底稿整文件覆盖，把已上线的 1.6.7.11 主下载框/历史表/更新日志整体回滚）。

1. **拉底稿**：`curl -sk -H 'Host: kerong.xin' https://127.0.0.1/penguin/{index.html,tutorial.html,style.css,version.json} -o <本地>`。
2. **改四个文件**：
   - `version.json`：新版本号 + changelog 数组**置顶一条**（只保留 1.7.0 起的条目，当前 3 条；1.7.0 前条目 2026-11-04 已删不再恢复；这是更新检查的数据源，忘改则老用户收不到提示）；
   - `index.html`：主下载框（版本号 + 发布日期 + 两个按钮 href→新 JAR 名；**不放更新日志摘要**——2026-11-04 用户要求删除）→ 历史版本表加两行（新版本置顶，NF 在前 Spigot 在后；**只保留 1.7.0 及以后**）→ 更新日志加本版段落（**简化风格**：每版几条要点、每条 1-3 行；只保留 1.7.0 起各版本，「更早版本一览」块已删）；
   - 历史版本本体：`downloads/` 只保留 1.7.0 起双端 JAR + 0.3AI 线；1.7.0 前的 15 个 JAR（含互转版底座）2026-11-04 起归档在服务器 `/root/penguin-archive-downloads/`（新版本发布后旧的 1.7.x 是否归档听用户指示）；
   - `tutorial.html`：仅当功能/行为/命令/配置有变化时同步（教程与插件注释保持一致的原则）；**「常用设置」固定格式（2026-11-04 用户要求，长期有效）——每个小配置独立一条 `.cfg-item`：小标题（功能名 + 键路径）+ 自己的 2-8 行 YAML 片段（带父级上下文）+ 一两句描述；禁止「一个总配置块展示整段 + 后面跟一大串文字」的写法**。样式在 style.css（.cfg-item 系列 + 手机端 pre 自动换行）；
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

### 6.2 配置系统（config-version 32）
`ConfigManager.kt`（共享源码）：DEFAULT_VALUES 全量默认 + `ConfigUpgrader.fillMissing` 补缺失键（新键升级自动写入）+ REMOVED_CONFIG_KEYS 清废弃键。**1.7.0 定下的原则：升级只加新键+默认值，不做任何旧值迁移**（例外仅两处「旧默认收敛」：标题框 96→80、副标题 40→56——旧默认是插件写入而非用户意图，等于旧默认才自动换新默认，自改值不动）。语法错误三级自愈（ConfigBrokenRecovery）。绝大多数配置 reload 即时生效；仅 bot.app-id/bot.secret、update-check 与 render.custom-render-threads（线程池首次渲染时创建后定格）需重启。
**注释保留保存（1.7.2 覆盖版，NeoForge 端）**：兼容层 `JavaPlugin.saveConfig()` 不再整树 dump（会剥光注释，bpngsz 后 231→0 行实测），改走 `ConfigCommentSaver` 差分合并——磁盘文本为底，快照树 vs 内存树逐叶子比对（宽松等值：数字按 double），差异键交 `YamlTextMerge.merge(text, overrides, removals)`（新增删除能力：键行+块体+紧贴注释一并删）文本级合并，整树校验通过才原子落盘，否则退回 dump（注释丢但数据不丢）；从未装载（configLoaded=false）或装载失败时跳过写盘防覆盖。Spigot 端不改动——Paper/Spigot 1.18.1+ 平台 YamlConfiguration 自带注释往返（Bukkit 1.18.1 引入注释 API；仅 1.18.0 边缘版本会丢，接受）。

### 6.3 渲染管线与头像获取（1.7.3 覆盖版口径）
- 渲染：CardRenderPool（**默认核数一半 1~4 线程；1.7.3 覆盖版可配** render.custom-render-threads 段：enabled 开关（默认 false=自动）+ render-threads 1-16 + avatar-threads 1-16，线程池首次渲染时创建后定格、改后需重启——⚠ 调大渲染线程会争抢 CPU 可能影响 tick）→ 结果缓存 → 背景内置+img 随机 LRU（36MB 字节预算）；模糊度 render.blur-strength（0-100 默认 50，backdrop-filter 只模糊卡片背后）；三档灰度（实时读取 0-255）：render.title-card-gray（默认 80 = 全部五张卡片顶部标题区——整块全宽卡：模糊+灰度+圆角）/ render.item-card-gray（默认 106 = 列表容器：查信息统计面板 + 查在线/排行榜/查性能容器）/ render.subtitle-card-gray（**覆盖版默认 56** = 副标题类：查信息玩家名称项 + 五张卡片副标题条（服务器/更新时间/渲染耗时三小卡），低于标题 80；初版 40 太浅已收敛）；**右上角小方框（徽标卡）灰度 = 标题框 +36**（RenderSettings.headerBadgeCardGray 派生键，随 title-card-gray 联动，无独立配置——旧版同灰度叠加融合看不出边界）；标题卡内文字 padding 统一距卡左缘 24~28px（眉题 28 / 主标题 24，对齐查信息欢迎面板）；性能卡 SUBTITLE_GAP 32 = 标题↔副标题视觉 18px = 副标题↔容器视觉 18px（等距；容器上垫 14 补偿），查信息四卡间距统一 20px（统计面板 436→432）；**查性能右上角小方框 = 「综合状态」**（buildOverallStatus：TPS/MSPT/内存/CPU 四项各评三级取最差，不含人数；运行良好(绿)/轻度负载(黄)/负载过高(红)/暂不可读(灰)）；**五卡副标题条第三格 = 「渲染耗时」**（render() 入口 nanoTime → 全部面板绘制完成后 drawRenderElapsed 补画毫秒数 → PNG 编码；不含取数/排队/冷却——用户口径非从接收指令起算；PNG 编码与上传无法预先包含在图内，属物理限制）；副标题条新增于在线排行榜/财富榜/查性能/查信息头部下方（查在线原有状态栏改接副标题灰度），性能监控原服务器名小字移除；排行榜单页行数 leaderboard.page-size（全局默认 20，1-100，两命令实时读取）。像素级验证（白底关模糊）：title 255/0 → 标题区 17/253；subtitle 255/0 → 玩家名称项 5/254 与副标题条 24/254；item 0/255 → 统计面板 254/13；徽标区比标题卡深 108<179；副标题 203>标题 179；首页高度 +102（副标题条 84+18）+性能卡等距补偿 +14。
- 头像链路：内存缓存（10 分钟，128 条，只存 ≤10KB PNG）→ 磁盘缓存（默认 3 小时，render.avatar-cache-hours）→ **外部多线路**（MC-Heads → Minotar(helm) → MinePic(skin+裁脸) → Crafatar(overlay)）→ Mojang 官方兜底（429/超时 5 分钟全局冷却）。**1.7.2 超时修复**：头像 HTTP 走 httpGetAvatar（连接 5s / 读 25s 分离），任一线路超时本轮内立即换下一源，第一轮全败且超时型时对快线路补试一轮；超时型失败不写 60 秒冷却（下次查询立即重试），只有确定性失败（404）才写冷却。单飞 inFlightFetches 并发去重（等待 8s）。基岩玩家走 MC-Heads xuid 线路。**批量获取并发重做**：在线列表 / 排行榜 / 财富榜 / 查信息统一走 InfoCardAssets.fetchAvatarBatch（**8 线程共享池** PenguinAvatarFetch + **12 秒渲染等待预算**；超预算的玩家本轮占位块先回复、任务不取消——后台下载完成后进缓存下一轮即有真实头像；40 秒迟到硬上限由 PenguinAvatarReaper 兜底取消防慢源链路占满线程池；旧版各服务自建 4 线程池 + invokeAll(8s) 到点中断在途下载已全部移除；查信息单头像旧版在渲染线程无上限阻塞、QQ 可等一分多钟，也改走同入口有界等待）。
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

- 更新日志简化（每版几条要点、每条 1-3 行；官网与 version.json 只保留 1.7.0 起的版本，「更早版本一览」与 1.7.0 前条目 2026-11-04 已删不再恢复；README 同样最近三版）。
- 教程小白向（步骤化、命令代码块、技术细节收进末尾卡片）；改教程/下载页后插件版与模组版同步说明。
- **教程「常用设置」按小配置粒度成条**（.cfg-item：片段 + 描述）——用户明确否决「总配置展示一个 + 剩下的写一大串」（2026-11-04），后续新增配置的教程段落必须照此格式写。
- 主下载卡片不放更新日志摘要（只有版本号 + 发布日期；2026-11-04 用户要求）。
- 官网历史版本与下载本体只保留 1.7.0 起；旧 JAR 归档服务器 /root/penguin-archive-downloads/（不 rm，互转版打包还要用底座）。
- 主下载按钮在版本号下方（Spigot 在前 NF 在后，max-width 440px 居中）。
- 官网 2026-11-03 起暗色主题（布局不动只换色板，见 §4）。
- 渲染图片设计标注统一「设计参考」（BaiZhouziYou / 新亭泪，issue #1）；**全部图片渲染设计（卡片版式 / 毛玻璃 / 背景 / 头像呈现）来源都是该模板——没有「早期独立设计 / 其后独立改进」，官网设计参考段按全量口径表述（2026-11-04 用户更正）。

## 8. 历次踩坑记录（根因 + 解决步骤）

### 8.1 1.7.2 五大 case 全记录（首轮三项 + 覆盖版两项）

**① 头像获取部分失败（用户判定"8 秒超时被直接中断"）**
- 现象：批量报「获取失败（本次链路遇网络抖动，未写失败冷却，下次查询立即重试；本张渲染占位块）」，下一轮查询重试即成功（成功日志「头像 xxx ← Minotar（名字线路）」）。
- 根因：头像下载 HTTP 的读超时过短（旧版连接/读同为 3 秒）——慢速可用的源流读到一半被 SO_TIMEOUT 切断；批量路径另有 8 秒预热预算（invokeAll）**到点中断在途下载**（用户实测「获取到一半就直接被截断，然后渲染发送」：invokeAll 超时会对任务 future.cancel(true)，线程在 inputStream.read 中被 interrupt，已下载字节全部作废），大名单尾部玩家还排队等不到线程。SO_TIMEOUT 是**单次 read 阻塞**上限，数据在流动就不断重置，不是总时长——拉长只影响真正断流的连接。
- 解决步骤：a) 新增 httpGetAvatar（连接 5s / 读 25s 分离，头像链路全走它）；b) 任一线路超时后本轮渲染内立即换下一头像源（elvis 链既有行为明确化）；c) 第一轮全败且属超时型（networkTrouble 标记）时对 MC-Heads / Minotar 快线路补试一轮（fetchExternalAvatarOnce(retryPass=true)）；d) 超时型失败继续不写 missCache 冷却（既有机制保持）；e) 真失败（HTTP 404 明确无此玩家）的 60 秒冷却不动；f) **批量并发重做**——InfoCardAssets.fetchAvatarBatch 统一四服务入口（在线列表 / 排行榜 / 财富榜 / 查信息）：8 线程共享池并行收齐（大量头像几秒完成，QQ 回复不久等）、12 秒渲染等待预算内逐个收结果、超预算先渲染发送但**任务不取消**（后台下载完成后进缓存，下一轮查询即显示真实头像——这是根治「截断一半」的关键：预算与下载解耦）、40 秒迟到硬上限兜底取消（防慢源链路占满预热线程池，PenguinAvatarReaper 单守护线程调度）；查信息单头像同入口（旧版渲染线程无上限阻塞，慢链路时 QQ 等一分多钟）。
- 验证：门禁 AV 组 11 项（字节码常量 5000/25000、方法在位、旧三参方法移除、AV3 批量并发 5 项：fetchAvatarBatch 双 JAR 在位 + 12000l/40000l 常量 + 线程名字面量 + 四服务接入 + invokeAll 反断言）；冒烟 AV 组 3 项（空列表秒回 / 100ms 预算到点即回实测 116ms / 返回 Map 非 null）。

**② /执行命令 返回混入控制台噪音（[MediaSource] 刷屏、[Not Secure] 行）**
- 现象：QQ 返回混入「[MediaSource] 布谷鸟时钟 BlockPos{…} 媒体=(空) mode=FIT …」高频刷屏与「[Not Secure] [Server] 1」。
- 根因：旧架构把命令派发给**真实控制台**（`Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)` / NF `server.createCommandSourceStack()`），命令输出落进控制台日志，插件只能靠 **log4j 根 Logger 的 Appender 全量捕获窗口内（2 秒）所有日志行**来拿输出——窗口里所有异步日志（NeoForge 模组周期日志、聊天行）都进了返回。
- 解决步骤：a) Spigot/混合端：改为 `Bukkit.dispatchCommand(this, cmd)` 派发给自定义 ConsoleCommandSender（BukkitConsoleSender 本身就是），只有其 sendMessage 回调收到的行进返回；b) NF：`createCommandSourceStack().withSource(CollectingCommandSource)` 包装命令源（sendSystemMessage 收集；withSource 个别版本缺失时 catch Throwable 回退）；c) 命令无 Sender 输出（只往 logger 打的命令）才回退到窗口日志；d) 兜底黑名单 ConsoleNoiseFilter（消息前缀 `[MediaSource]`/`[Not Secure]` + logger 名含 MediaSource 双通道），双端 Appender 副本接入；e) 验收标准：返回 = 该命令实际产生的输出，无多余行。
- 验证：门禁 EX 组 9 项（双执行器无 getConsoleSender、NF withSource、CollectingCommandSource、ConsoleNoiseFilter 双 JAR、双 Appender 接入）+ 冒烟 NZ 组 5 项。

**③ bpng 列表「（29 张，约 58.1 MB）」疑似硬编码**
- 现象/判定：用户认定列表的张数与体积是硬编码。核查：插件端其实一直按 manifest 的 files 数组动态计算，但清单**没有显式的 count/bytes 字段**，且 description 文案里嵌了"29 张"字样——观感与维护口径都是硬编码。
- 解决步骤：a) manifest 每包新增 count（文件数）/bytes（总字节数）展示字段；b) 插件 parseManifest 读取，PackInfo.count/totalBytes 优先声明值、缺失回退 files 统计（向后兼容）；c) 官网 wl1 清单补写 count=29 / bytes=60926352（=58.1MB，与旧显示一致）；d) 上传工具 bpng_upload.py 自动生成 count/bytes；e) make_bpng_manifest.py 同步输出新字段。
- 验证：门禁 BP 组 + 冒烟 S/H 组（声明值优先、旧清单回退、列表行渲染声明张数体积）。

**④ config.yml 注释全部丢失（覆盖版，用户实测「旧版 1.7.2 配置文件注释没有」）**
- 现象：NeoForge 端使用 /hbkrg bpngsz（或任何触发 saveConfig 的路径）后，config.yml 被剥成光杆键值（复现实测：231 行注释 → 0 行）。
- 根因：NF 兼容层 YamlConfiguration.save() 是 snakeyaml 整树 dump（其 KDoc 早已自认「重存会丢失注释」）；旧版靠 ConfigFileRebuilder 在**版本升级**时文本重建保注释，但 bpngsz 五键写回、reload 兜底补齐等**运行期保存**都直接 dump——注释一存即光。Spigot 端 1.18.1+ 由平台 YamlConfiguration 自带注释往返，无此问题。
- 解决步骤：a) YamlTextMerge 新增 removals 三参重载（键行+块体+紧贴上方注释一并删除）；b) 兼容层 JavaPlugin 挂 configLoaded 装载标记（reloadConfig 先复位再置位——装载抛异常保持 false）；c) saveConfig() 改调 ConfigCommentSaver（同文件 private object）：磁盘文本→快照树→与内存树逐叶子差分（宽松等值）→文本合并→整树校验→原子写，任一环失败退回 dump 兜底；d) 未装载/装载失败 + 文件存在 → 跳过写盘（旧版会把空树 dump 覆盖用户配置/损坏文件，顺带修掉）；e) binds.yml 等自有 YamlConfiguration 实例不走此路径（仍 dump，本就无注释，零影响）。
- 验证：复现脚本 scripts/repro_comment_loss（旧 JAR FAIL 231→0，新 JAR PASS 166→166）；冒烟 CL 组 7 项（bpngsz 翻转/补键/删键/未装载跳过/损坏保护/roundtrip）+ CF 组 6 项。

**⑤ 图片标题框黑度调不动（覆盖版，用户实测「无论调成多少都是透明」）**
- 现象：调 render.title-card-gray 看不出任何变化；四张卡片顶部标题区视觉恒为透明。
- 根因：首轮 1.7.2 把该键接到了查信息「统计信息」面板，但用户说的「标题框」是四张卡片**顶部的标题区**（查信息欢迎面板/查在线人数卡/排行榜统计卡/查性能更新时间卡）——这些区域硬编码 `Color(0xFF,0xFF,0xFF,38/34)` 半透明白，改配置自然不动。
- 解决步骤：a) 四处标题区全部改接 titleCardGray()（黑 alpha）；b) 查信息统计面板归类为列表容器改接 itemCardGray()（与其他卡片容器一致）；c) 默认 96→80（用户「再调低一点」）；d) **旧默认收敛**：v29 磁盘里的 96 是默认写入非用户意图——重建器 pre-step 与 reload 兜底双路径 `96→80`（自行改过的值不动；不做其他键的旧默认迁移）；e) 门禁 GY 组改双灰度断言 + 源码级 38/34 反断言。
- 验证：冒烟 GY 像素组（白底关模糊渲染：title 255/0 → 标题框亮度 17/253；item 0/255 → 统计面板 254/15）+ CF3 收敛断言。

### 8.2 历史坑速查表（保留全部，避免重蹈覆辙）

| 坑 | 根因/表现 | 解法 |
|---|---|---|
| Java2D 文字抗锯齿混叠色假命中目标色 | 文字边缘像素经 **gamma 校正非线性混叠**（非 RGB 线性插值），混叠带恰穿过相邻目标色附近（实测新标签色 0x8296AA → 白底的混叠像素 #A5B3C2，距旧色 0x9FB3C8 仅 ±6）——「旧色像素计数 = 0」断言被 197 个假命中打挂（1.7.3 覆盖版三轮 R3d） | 文字颜色断言一律用**标签区最暗像素 = 字色核心**法（22px 字有充足整色像素；阈值取新旧色亮度中点），不用目标色邻域计数；线性混叠理论推导在 gamma 抗锯齿下不成立 |
| SDK 钩子返回 false | 拦截 SDK 自身处理（鉴权/心跳/重连全断）→ 1006 死循环 | WssDiagnostics 回调必须 return true |
| fat JAR 无目录条目 | SDK 组件装配 NPE，机器人启动失败 | 打包脚本 dir_count 熔断 |
| NeoForge 底座用错 | NF 打包底座必须是 Spigot 版 JAR；产物体积骤降=类丢失 | 传本版 Spigot JAR 作 base |
| NF 平台包冲突 | org/slf4j、com/google/gson 必须剔除（split package） | EXCLUDE 前缀固化在打包脚本 |
| kloping SDK 对 4xx/5xx 不抛异常 | 「调用没抛异常」≠「平台已接收」 | 判定送达必须查 V2Result.id 非空 |
| Java 连 IP 的 HTTPS URL 无 SNI | 本站 nginx handshake_failure；Host 头属 JDK 受限头被静默忽略 | 弃用 IP 直连源（UpdateChecker 内置镜像从未生效，无害遗留） |
| volatile 取消标志残留 | 标志只在"下一次动作开始"重置 → 中断一次后清单获取永久失败 | 随"一次完整操作"结束 finally 清除 |
| Kotlin 尾随 lambda 绑定最后形参 | 函数式参数不在签名最后时尾随 lambda 落错位 | 函数式参数一律放最后 |
| fine-grained PAT 只读判定 | API 读通 + push 403 + 建 ref 报 Resource not accessible = 令牌只读 | 向用户要写权限令牌（经典 ghp_ 或 Contents:Read and write） |
| gh-proxy 不转发 push 鉴权（已部分推翻） | 旧 gh-proxy 报 No anonymous write access；**ghfast.top 实测 2026-11-05 支持带鉴权 push**（GIT_ASKPASS + 令牌文件）；github.com 直连 push 超时 | 首选 ghfast.top 代理 push；备选官网服务器中继（bundle→bare.git→服务器带 PAT 直连） |
| 官网整文件覆盖回滚 | 用旧底稿整文件上传覆盖了已上线内容 | 改前必拉线上当前版作底稿；上传后 curl 复核版本号三处 |
| 复用旧 build 目录 | 空目录/旧字节码被 Gradle 打进产物 | 发布前 gradlew clean 全量重建 |
| 终端显示吞转义 | 工具输出管道吞 `[h`/`[[mods]]` 显示层 | 断言一律以文件字节为准（hexdump/tomllib） |
| 宣称未实装（三次教训） | 宣称修复但字节级核实未落位 | 宣称前必须对产物做字节级核对 |
| mod 总线 @SubscribeEvent 参数约束 | 跨版本无满足条件的公共类型 | 反射 addListener(Class, Consumer) 按具体事件类注册 |
| 冒烟必须覆盖真实总线注册 | 只测 Class.forName 不测注册→分发 | FML 风格总线端到端 + 负向对照 |
| Write 工具损坏 \uXXXX 转义 | 转义序列写成 NUL 字节 | 特殊序列用 Python 字节级写入 |
| Kotlin 块注释嵌套 | 注释里 `/*` 相邻序列吞掉后面代码 | 注释里避免 glob/路径相邻 `/*` |
| NF 兼容层 YamlConfiguration 整树 dump 剥注释 | save() 走 snakeyaml dump，注释一存即光（bpngsz 实测 231→0 行） | config.yml 一律走注释保留保存（ConfigCommentSaver 差分合并）；平台侧 Spigot 1.18.1+ 自带注释往返 |
| 运行期改配置的保存链路绕过重建器 | ConfigFileRebuilder 只管版本升级；bpngsz/reload 补齐直接 saveConfig | 兼容层 saveConfig 已收敛为注释保留；新增保存入口时确认走 plugin.saveConfig() 而非 YamlConfiguration.save() |
| javap 泛型签名断言写裸类型 | `merge(String, Map, Set)` 断言匹配不到（实际输出带泛型 `<java.lang.String, ? extends …>`） | 断言用泛型全形态或只匹配方法名+尾部 `Set<java.lang.String>)` |
| 源码 KDoc 提及已删除文件名 | 反断言 `config.yml.bak not in source` 被自家注释误触发 FAIL | 反断言只查字节码 + 源码注释改用「升级备份文件」等不带文件名的表述 |
| @Volatile 装载标记的复位时机 | reloadConfig 装载失败若不先复位标记，saveConfig 会拿旧树覆盖损坏文件 | 标记在 load 前复位、成功后置位（失败保持 false → 保存跳过） |
| MultiEdit 非原子 | 部分成功后报错"未执行" | 失败后先 rg 检查文件实际状态 |
| drawImage 十参目标/源矩形序 | 写反时等尺寸静默正确、cover 时整画布空白 | 几何改动必须不同宽高比像素级端到端断言 |
| javap 三坑 | 平铺放置常量池降级截断 / 两形态字符串 / Kotlin 模板 recipe 在 BootstrapMethods | 按包路径展开 + -v 常量池 + 字节双通道断言 |
| internal 方法模块后缀 | cardHeight$common_Bot vs $server_NeoForge（fetchAvatarBatch 同坑：Java 冒烟编译报 cannot find symbol） | 反射按前缀动态查找；需要 Java 侧直调的入口直接声明 public（与 fetchAvatar 同风格），编译期消除后缀 |
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
| 内置字体子集缺字 | 图片新文案用了子集外的字（如「渲」）→ drawString 静默缺字形，图上「渲染耗时」显示成「染耗时」；字节码断言只查常量池字面量查不出 | 新增图片文字必须过 VLM/像素级视觉复核；缺字用 fonttools 从系统完整 NotoSansSC 按旧子集字符集+新字重新子集化（scripts 无常备脚本，当场 python subset.main），双端四 ttf 同步替换 |
| JVM 已提交≠存活 | 已提交堆是 JVM 预留量 | /hbkrg mem 诊断；不要 System.gc 归还 |
| 停机 zip closed | 停机阶段提前关插件 jar ZipFile | onEnable 预热 CommandOutputAppender；onDisable 每步独立 try-catch |
| Kloping 线程池不退出 | 50+ 非守护线程 | SptCompat 清扫 + 5 秒 halt(0) |
| 死代码判定 | 反射入口（@Commands/@SubscribeEvent）不算死 | 查三模块+脚本+资源+反射再删 |

## 9. 1.7.2 逐项改动清单（用户 19 项待办 → 落地情况）

| # | 待办 | 状态 | 落点 |
|---|---|---|---|
| 1 | 删除 git 图片包镜像下载线路 | ✅ | BackgroundPackService.DOWNLOAD_SOURCES 只留官网；git 仓库 bpng/ 移出 + .gitignore；make_bpng_manifest.py 清镜像表述 |
| 2 | bpng 列表动态读取 | ✅ | manifest 新增 count/bytes；PackInfo.declaredCount/declaredBytes + 回退兼容；官网 wl1 清单已补（count=29/bytes=60926352） |
| 3 | 头像 8 秒超时修复 | ✅ | httpGetAvatar 连接 5s/读 25s；超时换源 + 快线路补试轮；超时不写冷却保持；批量并发重做 fetchAvatarBatch（8 线程 + 12 秒渲染预算 + 超预算不中断在途下载 + 40 秒迟到硬取消），四服务统一入口；见 §8.1① |
| 4 | /执行命令 噪音 | ✅ | 双端派发自定义 Sender / withSource 包装；噪音黑名单 ConsoleNoiseFilter；见 §8.1② |
| 5 | bpng_upload.py 上传工具 | ✅ | /home/z/my-project/scripts/bpng_upload.py（SFTP+服务器端解压+manifest 生成+chown+curl 验证） |
| 6 | image-download.enabled 开关 | ✅ | 配置默认 true；双端 bpng 入口拦截；bpngsz 不受影响 |
| 7 | 签到经济插件检测 | ✅ | EconomyRewardService（Vault/PlayerPoints/CoinsEngine 全反射+余额差值校验）；points-reward 配置；plugin.yml softdepend+CoinsEngine；⚠️ 用户实际 MC 服务器装了哪些经济插件未核实（服务器不在本机） |
| 8 | QQ 绑定验证文案 | ✅ | kick-message「可以直接发送纯数字验证码：xxx」；bind-invalid 一句话 |
| 9 | 项目卡片灰度 | ✅ | 64→106（(120+64)/2=92 偏旧值一侧）；render.item-card-gray；覆盖版起统一收口全部列表容器（含查信息统计面板） |
| 10 | 标题卡片灰度 | ✅ | 覆盖版改口径：默认 80 = 四张卡片**顶部标题区**（首轮误接查信息统计面板，用户调不动——见 §8.1⑤）；旧默认 96 升级自动收敛 80 |
| 11 | 灰度参数进配置 | ✅ | 双键 0-255 实时读取；四渲染器接入；硬编码清除；像素级验证标题框/统计面板分别随双键变化 |
| 12 | 设计来源统一标注「参考」 | ✅ | 四渲染器 KDoc + README + 官网节标题 |
| 13 | 官网暗色调 | ✅ | style.css 深背景/深卡片/浅文字；index/tutorial 内联色同步；布局不动 |
| 14 | 版本号 1.7.2 + 更新日志 | ✅ | 六处版本号 + README + version.json + index 日志块 |
| 15 | 构建双端 JAR | ✅ | 覆盖版全量重编译重打包：门禁 verify_jar_172 **89 项**全过（新增 CL/RC/WEB 组 + GY 双灰度断言）+ 冒烟 smoke172 **39 项**全绿（新增 GY 像素/NZ6/CL/CF 组） |
| 16 | 部署 | ✅（官网）/ ⚠️（MC） | 双 JAR + 源码包 + 官网已上线 curl+md5 验证；**MC 服务端不在 156.233.228.248 上**，JAR 替换与重启需用户在实际服务器执行 |
| 17 | 官网发版 + curl 验证 | ✅ | version.json/index（三处版本号）/tutorial/style 200；manifest count/bytes；外网域名复核 |
| 18 | git 推送 | ✅ | 首轮：远端 main = b080241；覆盖版：本轮中继推送（bundle 基线选远端真实 HEAD，流程见 §0） |
| 19 | 交接文档细化重写 | ✅ | 本文档（新增 §2 构建指南 / §3 部署 / §4 官网发版 / §8 踩坑根因 / §9 逐项清单） |

**1.7.2 覆盖版追加清单（本轮用户五项反馈）**：

| # | 待办 | 状态 | 落点 |
|---|---|---|---|
| 20 | 旧版 1.7.2 配置注释丢失 | ✅ | 兼容层注释保留保存（ConfigCommentSaver + YamlTextMerge removals），见 §8.1④；复现脚本前后对照 231→0 修复为 166→166 |
| 21 | 图片标题框黑度调不动 | ✅ | 四处标题区接 titleCardGray；统计面板归 itemCardGray；默认 80；96→80 收敛，见 §8.1⑤ |
| 22 | 标题框默认灰度再调低 | ✅ | 96 → 80（含升级收敛） |
| 23 | 移除配置备份功能 | ✅ | 重建器不再生成 config.yml.bak（损坏恢复 .error 应急备份保留——语法损坏重建默认前保用户设置的最后保险；如也要删需用户再确认） |
| 24 | QQ 远程命令控制台无记录 | ✅ | 双执行器 [QQ 远程命令] 前缀转发控制台/latest.log + 噪音黑名单收录防回混 |
| 25 | QQ 重连过程不显示 | ✅ | 手动重连（/hbkrg chs）一轮内里程碑回显到配置群（announceToGroups + broadcastReconnectLine）；自动重连不广播 |
| 26 | 官网教程小白化 + 溢出修复 | ✅ | tutorial 常用设置只留功能开关、技术细节归末节；index 兼容性卡片 &nbsp; 连排改 .core-line 可换行结构（375px 视口 0px 溢出实测） |

**2026-11-04 官网清理（用户五项，仅官网 + 本文档，插件未动）**：

| # | 待办 | 状态 | 落点 |
|---|---|---|---|
| 27 | 删 1.7.0 前历史版本 / 更新日志 / 本体 | ✅ | index 历史表与「更早版本一览」、version.json 1.6.9.5 条目删除；服务器 15 个 1.6.x JAR 归档 /root/penguin-archive-downloads/（含互转版底座）；互转版官网卡片删除（**1.7.3 日同日恢复上线，见 #40**）；0.3AI 线未动 |
| 28 | 更新日志简化 | ✅ | index 三版各 3-6 条短要点；version.json 同步精简为 3 条 |
| 29 | 下载卡片去简单更新日志 | ✅ | 主下载框只留「版本 1.7.2 + 发布于 2026-11-03」 |
| 30 | 教程按小配置重构 | ✅ | 常用设置 49 个 .cfg-item（片段 + 描述）；style.css 新增条目样式；手机端长注释行 pre-wrap 自动换行（375px 实测 0 溢出）；安装「完成」步生效说明改准确（凭据重启 / 其余 reload） |
| 31 | 设计来源全量口径 | ✅ | 设计参考段改为「全部设计均参考该模板」，删「独立改进」句（footer 口径本就一致未动） |
| 32 | AI 交接文档同步 | ✅ | 本块 + §4 发版流程 + §7 口径四条新规则 + §10/§12 归档说明 |

**1.7.3 逐项清单（2026-11-04，用户十一项）**：

| # | 待办 | 状态 | 落点 |
|---|---|---|---|
| 33 | 标题卡：模糊 + 卡片边缘 + 整卡灰度（三张列表卡头部） | ✅ | OnlineList/Leaderboard/Performance.drawHeader 全部先 paintCard（模糊）再 titleCardGray 填充全宽圆角卡；门禁 HD1-4 + 冒烟 LB4/LB6/PF3/PF5（左侧无卡区域随灰度变暗） |
| 34 | 查信息玩家名称项卡片不可调 | ✅ | drawIdentityBar 硬编码白 22 → subtitleCardGray；冒烟 PX7（255→5 可调） |
| 35 | 新增副标题灰度（明显低于项目卡与标题卡） | ✅ | render.subtitle-card-gray 默认 40（用户口径「低得要明显」）；RenderSettings.subtitleCardGray 实时读取 |
| 36 | 副标题：服务器/更新时间/状态（五张卡） | ✅ | Leaderboard/Performance/InfoCard 新增 drawSubtitle（三小卡）；查在线原状态栏改接副标题灰度；InfoCard.CardData 新增 serverName/updateTime 字段，QueryInfoService 传入 |
| 37 | 性能监控原服务器名显示去掉 | ✅ | drawHeader 删 serverName 小字（签名改三参）；服务器名改由副标题条展示 |
| 38 | 配置说明服务器名显示在图片上 | ✅ | 双端 config.yml serverName 注释改「显示在图片卡片的副标题与进出服播报里」 |
| 39 | 排行榜单页项上限（全局，默认 20） | ✅ | leaderboard.page-size；ROWS_PER_PAGE 常量删除；两命令接入 RenderSettings.leaderboardPageSize（1-100 收敛）；查在线/财富榜（自定义标题）共用 |
| 40 | 互转版官网卡片重新上线（简版） | ✅ | index.html「其他版本」区新增简版卡片 + JAR 从归档放回 downloads/（2026-11-04 用户指令：不要删） |
| 41 | 今日在线跨天误报修复 | ✅ | PlayerStatsManager.query 三处离线回退路径 rolledForToday（展示层归零，存档不动）；门禁 TD1/TD2 |
| 42 | 版本 1.7.3 + 构建 + 部署 | ✅ | 六处版本号 + config-version 31；门禁 verify_jar_173 **92 项**全过 + 冒烟 smoke173 **27 项**全绿（ST/PX/LB/PF 四组） |
| 43 | 标题卡文字内边距（参考查信息最上框距离） | ✅（覆盖版） | 三列表卡眉题 x=MARGIN+8→+32、主标题 +4→+28（距卡左缘 28/24px）；门禁 padding 常量 72.0f/68.0f 断言 |
| 44 | 右上角小方框永远比标题框灰度深一点 | ✅（覆盖版） | RenderSettings.headerBadgeCardGray() = titleCardGray()+36 clamp 255；三渲染器徽标卡接入；冒烟 RC1/RC2（116/255）+ RC7 像素（徽标区比标题卡深 108<179） |
| 45 | 性能卡副标题与标题框/项目框等距 | ✅（覆盖版） | SUBTITLE_GAP 18→32（= HEADER_GAP 18 + 容器上垫 14，视觉两侧均 18px）；查信息统计面板 436→432（四卡统一 20px）；冒烟 RC11/RC12 |
| 46 | 查性能右上角小方框改「综合状态」（不含人数） | ✅（覆盖版） | buildOverallStatus：TPS/MSPT/内存/CPU 各评三级取最差；运行良好/轻度负载/负载过高/暂不可读；语义色；冒烟 RC10 绿色像素 |
| 47 | 副标题灰度再调深（不比标题/项目框高） | ✅（覆盖版） | DEFAULT_SUBTITLE_CARD_GRAY 40→56；ConfigManager+Rebuilder 双路收敛；冒烟 RC8 像素（副标题 203 > 标题 179） |
| 48 | 副标题「状态」框改「渲染耗时」 | ✅（覆盖版） | 四渲染器 render() nanoTime 起点 → drawRenderElapsed 补画（全部面板绘制完成后、PNG 编码前）；非从接收指令起算；冒烟 RC9/RC13 |
| 49 | 图片渲染线程可配置 + 开关 + 注释警告 | ✅（覆盖版） | render.custom-render-threads{enabled:false,render-threads:2,avatar-threads:8}；CardRenderPool.THREADS / avatarBatchPool 创建时定格；⚠ 警告双端 config.yml；冒烟 RC3-RC5 |
| 50 | 字体子集补「渲」字 | ✅（覆盖版） | VLM 视觉复核抓出「渲染耗时」显示成「染耗时」；fonttools 从系统 NotoSansSC 重新子集化 3889→3890 字符，双端四 ttf 替换；复测四字完整 |
| 51 | 构建双端覆盖 1.7.3 | ✅（覆盖版） | 门禁 verify_173_overwrite **68 项**全过 + 冒烟 smoke173 **40 项**全绿（新增 RC 组 13 项）+ VLM 四卡视觉复核（六要点逐卡全过） |
| 52 | 官网覆盖部署 + git push + 交接文档移 /SUZHE014 | ✅（部署轮） | 沙盒回收后环境从零重建（JDK21/Gradle/deps.jar/NF libs）→ 全量重编译双 JAR 重打包 → 门禁 verify_jar_173_overwrite **69 项**全过（上轮脚本丢失本轮重写并入库）+ 冒烟 smoke173 **40 项** + VLM 四卡复核全过 → 双 JAR 覆盖上传 md5 往返一致（Spigot 213d33b5 / NF 76ea350b）→ 官网三文件覆盖（style.css 无变化）→ 源码包更新 → /SUZHE014/AI-KNOWLEDGE.txt 交接副本 → push a6806e6；**同轮补遗：双端资源 config.yml config-version 31→32（上轮只改 ConfigManager.CONFIG_VERSION，资源模板漏改——全新安装首启会白跑一次升级重建；本轮修复后重编译重打包重验）** |
| 53 | 标题框上边缘间距还是太贴近文字 | ✅（覆盖版二轮） | 三列表卡 HEADER_HEIGHT 150→164 + 眉题基线 38→46 + 主标题 108→118（眉题视觉顶边距约 18→26px）；门禁 R2c bipush 46/118 + R2d ldc 164.0f；冒烟 R2a（顶部 22px 条带无文字 max 0） |
| 54 | 标题框右上角小方框四周间距太小 | ✅（覆盖版二轮） | cardInset=14（上/右/下；旧 8/8/0）；cardX 加右缩进、cardHeight=HEADER-2*inset=136（标题区加高后徽标卡不减）；门禁 R2b 源码 + 冒烟 R2b/R2c/R2c2（右/上/下条带均为标题卡底色 175，旧版 139/混合/255） |
| 55 | 排行榜副标题渲染时长改展示玩家（xTOP） | ✅（覆盖版二轮） | drawSubtitle 第三格「展示玩家」→「${min(totalPlayers,topLimit)}TOP」；drawRenderElapsed 与 renderStart 计时从 LeaderboardRenderer 删除（查信息/查在线/查性能保留）；门禁 R2e/R2f/R2g + 冒烟 R2d（两次渲染字节一致）+ R2e（白字 255）+ VLM（8TOP 四字完整） |
| 56 | 本次覆盖 1.7.3 | ✅（覆盖版二轮） | 门禁 verify_jar_173_round2 **92 项**全过（R2 组：源码 164/cardInset×3 渲染器 + 字节码 bipush 46·118/ldc 164f·222f/展示玩家字面量/makeConcat/移除断言/三卡保留断言；RC 一轮项沿用）+ 冒烟 smoke173 **46 项**（新增 R2 组 6 项）+ VLM 三卡头部复核 → 双 JAR 官网覆盖上线 + push（无新增配置键，config-version 32 不变；官网 tutorial 无需改） |
| 57 | 主标题框（非副标题）顶边距再加大 | ✅（覆盖版三轮） | 三列表卡 HEADER_HEIGHT 164→182、眉题基线 46→64、主标题 118→136；眉题视觉顶边距 27→45px 对齐查信息最上框（像素实测）；副标题框零改动（条顶 240 = 40+182+18、高度 84 不变）；门禁 R3a/c/d/e/g/h + 冒烟 R3a/R3b/R3c |
| 58 | 副标题灰色小文字调深 | ✅（覆盖版三轮） | 四渲染器新增 COLOR_SUB_LABEL = 0x8296AA（旧 0x9FB3C8，亮度 175→146），只作用于副标题条标签；门禁 R3b/f/i + 冒烟 R3d（标签核心亮度 146 < 160；⚠ 旧色像素计数会被 gamma 抗锯齿混叠色 #A5B3C2 假命中——见 §8 教训） |

**1.7.3 覆盖版三轮配置变更汇总**：无（同版本号覆盖，config-version 32 不变、无新增键；仅渲染几何与标签色微调）。

**1.7.3 覆盖版二轮配置变更汇总**：无（同版本号覆盖，config-version 32 不变、无新增键；仅渲染布局与文案微调）。

**1.7.3 覆盖版配置变更汇总**：config-version 31 → 32（新增 `render.custom-render-threads.enabled: false`、`render.custom-render-threads.render-threads: 2`、`render.custom-render-threads.avatar-threads: 8` 三键；`render.subtitle-card-gray` 旧默认 40 → 56 一次性收敛，自改值不动；同版本号 1.7.3 覆盖发布，六处版本号不变）。

**1.7.3 配置变更汇总**：config-version 30 → 31（新增 `render.subtitle-card-gray: 40`、`leaderboard.page-size: 20`，只加新键+默认值，不做旧值迁移）。

**1.7.2 配置变更汇总**：config-version 28→29→30（覆盖版再升 1，无新增键，注释全文精简重写）；新增 `image-download.enabled: true`、`render.title-card-gray: 80`、`render.item-card-gray: 106`、`qq-bind.checkin.points-reward: 0`（只加新键，不做旧值迁移；唯一例外：标题框旧默认 96 升级收敛 80）。升级不再生成备份文件。

## 10. Cross-posting 互转版（1.6.7_Cross-posting.3）

多群互通分支，仅 Spigot 插件形态，**源码不进公开仓库**、永不 push、用户要求才更新。**2026-11-04 官网清理时曾随 1.7.0 前版本清理下线（卡片删除 + JAR 归档），同日用户指令恢复：官网「其他版本」卡片重新上线（简版：二选一安装 + 独立产品线两条要点）+ JAR KERONGPenguin_Spigot-1.6.7_Cross-posting.3.jar 已从 /root/penguin-archive-downloads/ 放回 downloads/（长期保留，勿再清理）。**1.6.7.10 起工作流为「精准覆写打包」（scripts/package_crossposting_16710.py，以线上互转版 JAR 为底座仅覆写 MenuManager+KERONGCommand 类）；前提是 MenuManager 保持 void 兼容桥（互转版旧产物按旧描述符链接）。主版改动若触及 QClient/GroupMessageHandler/ConfigManager/KERONG/CrossPosting 三件套，精准覆写不可用。教程全部内置在 config.yml 注释。

## 11. 0.3AI 专属版（v0.5）

极简分支（连接机器人 + QQ 群 AI 对话），独立插件名/版本号，仅 Spigot 形态，无更新检查；源码本地 `/home/z/my-project/ai-git/`（不进公开仓库），服务器灾备 `/root/ai-source/`。构建走 kotlinc 直编（tooling/ 内 kotlin-compiler-2.2.0.jar + coroutines + reflect，classpath 另需 paper-api/adventure 四件）+ package_ai_05.py 精准覆写打包；门禁 verify_ai_05.py 138 项 + 冒烟 TestAi05 120 项。QQ 平台事实：面板按钮 name 即点击后发送的命令文本；用户标识是 per-bot OpenID；access token 从 starter.APPLICATION.INSTANCE.contextManager 取。更新节奏：用户要求才更新；version.json 永不动。

---

## 12. 历史版本归档（要点版，详细日志见 git log；**1.7.0 前版本官网条目与下载本体 2026-11-04 删除归档，其中互转版同日经用户指令恢复上线（见 §10），其余仍归档在服务器 /root/penguin-archive-downloads/，本节仅知识留档**）

- **1.7.3（2026-11-04）**：标题卡全宽化（模糊+灰度+圆角）/ 副标题灰度 render.subtitle-card-gray + 五张卡副标题条 / leaderboard.page-size / 今日在线跨天修复；互转版官网卡片恢复上线；门禁 92 + 冒烟 27。

- **1.7.2（2026-11-03，同名覆盖版）**：注释保留保存（ConfigCommentSaver）/ 标题框接线 / 远程命令留档 / 重连回显 / 升级备份移除；门禁 89 + 冒烟 39。
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

#!/usr/bin/env python3
"""1.7.5.9 知识库三件套回写（更新日志/会话上下文/AI交接文档坑表追加）"""
import pathlib
from datetime import datetime

KB = pathlib.Path('/home/z/my-project/work/kb')
SZ = '/SUZHE014/知识库/'

# ========== 1. 更新日志.md：状态表 + 本轮节（只留最新 1 轮） ==========
p = KB / 'changelog.md'
t = p.read_text(encoding='utf-8')

status_new = '''# KERONGPenguin 更新日志

> 本文件是项目唯一权威的详细更新日志：每个版本带准确时间（北京时间，精确到分）、完整改动内容与验证结果。
> **只保留最新 1 轮更新**（2026-11-13 用户指令：知识库只保留最新的版本）——更早版本（1.7.5.8 及之前共 100+ 轮）的详细记录已从本文件移除，全量历史见 git 提交历史（服务器中继 /root/gitrelay/KERONGPenguin.git，每条提交信息均带全量改动记录）与官网 /wwwroot/penguin/index.html 更新日志（1.7.0 起要点版）。
> 面向用户的简版更新日志在官网 /wwwroot/penguin/index.html 与 version.json（只保留 1.7.0 起，每版 3-6 条要点，与本文详略口径不同属正常）。
> 时间来源：git 提交历史（GitHub SUZHE014/KERONGPenguin，UTC+8 换算）。


## 当前版本状态(更新于 2026-11-14 17:55)

| 项 | 状态 |
|---|---|
| 当前版本 | **1.7.5.9**（全量转发显示名可配 + 菜单模板删除重建 + Fabric 绑定限制策略；config-version **50**） |
| 四端产物 | KERONGPenguin_Spigot-1.7.5.9.jar(md5 24e269c0…)/ KERONGPenguin_NeoForge-1.20.4-1.7.5.9.jar(85d8b13f…)/ KERONGPenguin_Fabric-1.20.4-1.7.5.9.jar(eea04c27…)/ KERONGPenguin_Fabric-1.20.1-1.7.5.9.jar(b8320727…)(/wwwroot/penguin/downloads/，md5 往返 + 外网复核 17/17) |
| config-version | **50**（1.7.5.9 两新键：chat-format.qq-forward-display-name 默认 group_nickname / qq-bind.restrict-timeout-seconds 默认 120；旧键 chat-format.group-sender-name（1.7.5.8）入废弃清单升级自动删除） |
| git push | ✅ GitHub main = **33c1bd6**（8aaf105 主体 + 33c1bd6 官网/README；服务器中继 /root/gitrelay/KERONGPenguin.git main 已同步 33c1bd6） |
| 官网 | version.json(1.7.5.9 置顶)/ index.html(主框 1.7.5.9 四按钮 + 历史表四行[1.7.5.8 保留] + v1.7.5.9 日志块三条)/ tutorial.html(group-sender-name 条目替换为 qq-forward-display-name + Fabric 绑定段新策略口径 + 新增 restrict-timeout-seconds 条目)上线；源码包 KERONGPenguin_v1.7.5.9_source.zip(1110 文件 8.78MB，HEAD 33c1bd6，md5 89342a3f)上传 /wwwroot/penguin-source/（留最新 3 个：1.7.5.7/1.7.5.8/1.7.5.9） |
| MC 服务端 | ⚠️ 不在 156.233.228.248 上；用户在实际服务器替换 JAR 并重启后生效（插件版 plugins/KERONGPenguin/，模组版 mods/；四端任选） |
| fa 版本 | Fabric 1.7.5.9 两产物：通用包(1.20.4–1.21.x) + 1.20.1 独立包（随主线同号） |
| 图片包 | wl1「蔚蓝档案」41 张 / 86,655,491 字节（本轮未动） |
| 备份 | ✅ 单包口径：SUZHEWWO(2026.11.07-4).tar.gz(v4 全量)；/SUZHE014/ 只留这一个包 |

## 本轮详细更新日志(1.7.5.9)

### 2026-11-14 16:45-17:55：1.7.5.9（全量转发显示名可配 + 菜单模板删除重建 + Fabric 绑定限制策略）

会话来源：用户交接文档（三项任务固化，含用户对 1.7.5.8 上轮方案的澄清）。**开轮重大发现**：交接文档所述「v1.7.5.8 已定义未开工」与知识库实际状态冲突——1.7.5.8 已于当日 14:00-15:40 完成上线（显示名 group/player 方案 + 假人豁免方案，HEAD eb97d7e），但那是上轮 AI 对需求的初次实现，方向与用户澄清后的需求有偏差；本轮按交接文档三项任务**整体覆盖**上轮方案，版本号因 1.7.5.8 已被占用顺延为 **1.7.5.9**（同号重发大忌，坑表有载）。

**[16:45-17:10] 环境重建+调研**：paramiko 阿里云镜像安装；ssh_helper/git-askpass 重写；中继 bundle 267MB 打包 SFTP 拉回克隆（1981 文件，HEAD eb97d7e）+ fabric-deps 20MB + JDK21/Gradle 8.14.5（dists 布局必须含 gradle-8.14.5/ 子目录层——散文件布局导致 wrapper 误判未安装强制联网，修正后 .ok 生效）；知识库三件套细读（发现冲突如上）。任务1 调研：kloping SDK Contact 类 javap 实锤（id/openid/username/role，无 card/nickname）+ QQ 官方文档双重确认（GROUP_MESSAGE_CREATE/GROUP_AT_MESSAGE_CREATE 的 author/mentions 均只有 username 一个昵称字段，「用户昵称」=群内显示名：群名片优先、未设置即 QQ 昵称）——交接文档「sender.card/sender.nickname」为 OneBot 时代记忆，本项目（kloping=QQ 官方 API）不适用；上轮结论成立，用户二选一需求按「两选项当前同值+如实注释+官方开放后接入」实现。任务2 调研：menu.md git 考古（1.7.5 引入 MessageTemplates 时首发，历代 4 形态 40d6534/ee8bf7c/45cfbcd/1f9c24d 全部已在 DEFAULT_CONTENT_UPGRADES.olds + features 兜底；1.7.4 及以前不存在菜单模板文件，1.7.4 时代菜单文案硬编码无磁盘残留）；四端资源副本/JAR 产物/释放记录/initialize 调用链逐一验证为最新完备——用户方案与现有机制同义，本轮按用户口径显式化。任务3 调研：Fabric 端 1.7.5.1 起「不拦截+qbd 自助取码」（bbc69c7）；fabric-api 事件面核查（ALLOW_COMMAND_MESSAGE 仅拦 /me /say 广播型——PlayerManagerMixin 注入 broadcast(SignedMessage,ServerCommandSource,…) 1.20.4 只有 EmoteCommands/SayCommand 调用）；命令通用拦截方案定为 brigadier requirement 包装（Unsafe 替换 private final，fabric-api 无公开命令事件、mixin 管线重映射字符串风险高不引入）。

**[17:10-17:40] 实现**：①新键 chat-format.qq-forward-display-name（group_nickname 默认/qq_nickname；GroupMessageHandler 私有 resolveForwardDisplayName 解析接入全量转发；/发信息 命令转发保持群昵称不走新键）；上轮 group-sender-name 整体拆除（REMOVED_CONFIG_KEYS 删键/GroupSenderName 类删除/getBoundPlayerNameByOpenid 删除/ChatFormat 字段改 qqForwardDisplayName）；config-version 49→50。②MessageTemplates 替换路径显式化为 applyReplacement（先 delete 再 writeBytes 全新文件）；释放记录②与升级表③统一走删除重建；menu olds 考据注释化。③server-Fabric 新增 BindRestrict.kt（BindRestrict 限制会话/tick 循环：锚点锁定 connection.teleport+超时踢出 connection.disconnect+绑定检测 isBoundByUuid 解限+绑定码输出 sendSystemMessage 每 5 秒、hasPendingCode 过期自动换新；BindRestrictInteractions 五事件入口；BindRestrictCommandGate Unsafe 包装 requirement 幂等防嵌套失败降级）；FabricEvents 接线（END_SERVER_TICK/ALLOW_COMMAND_MESSAGE/UseBlock/AttackBlock/UseEntity + JOIN/DISCONNECT 接 BindRestrict）；FabricCommands 删 qbd（节点/handler/帮助行/解绑提示三处）；QqBindManager.hasPendingCode + ConfigManager.restrictTimeoutSeconds + KERONGFabric.restrictTimeoutSeconds；三端 config.yml config-version 50+两新键四端注释。

**[17:40-18:00 约] 构建验证**：模块 build 为 clone 携带旧产物（rm -rf 四模块 build 后全量重编，BUILD SUCCESSFUL——首轮 package_jar 误读旧 plugin.yml 版本 1.7.5.8 致输出名与 base 同名互相覆盖 3.0MB 残废 JAR，git checkout -- build/dist 恢复后重打）；门禁 verify_jar_1759 **184/184 全绿**（A 版本资源 config 50+两新键四端/T1 显示名 11 项含 GroupSenderName 与 getBoundPlayerNameByOpenid 反断言/T2 菜单模板 5 项含删除重建日志与 olds 字节级/T3 限制策略 21 项——intermediary 名断言经 merged.tiny 核实：teleport(DDDFF)=method_14363（非初判 method_14397）/sendSystemMessage=method_43496/disconnect 两版 method_52396 与 method_14367；R 段假人豁免 Spigot+NF 回归；H1 阈值修正为仅 Spigot≥700——NF 661/Fa 680 为历史基线与 1.7.5.8 完全一致）；冒烟 smoke1759 **20/20 全绿**（S1-S3 显示名三模式/S4-S6b 菜单比对 4 历史形态+BOM 容忍+手改保留+幂等+特征兜底/S7-S8 删除重建 mtime 变化/F0-F8 包装结构——ensureWrapped 真实 Unsafe 替换+original 保留+幂等+null 快路径；受限玩家端到端未在冒烟 JVM 完成——MC 类加载需 Mojang 私有库 datafixers/fastutil 沙盒不可达（libraries.minecraft.net/Central 均 404），由门禁字节码断言+生产环境覆盖，如实存档）。

**[18:00-17:55 计] 部署提交**（北京时间，实际完成 17:35-17:55）：主体 commit 8aaf105 + 官网 commit 33c1bd6；四 JAR md5 往返一致上传；官网三件（md5 三方一致铁律前置通过：线上基线 421b8be4/d98a080d 与 git website/ 一致）；源码包 1110 文件 8.78MB（git archive 逐模块显式排除+smoke174 slf4j 两 jar+smoke1759/out；libs/README.txt 保留「留 README」口径）上传留最新 3 版（删 1.7.5.6）；push 8aaf105..33c1bd6；中继 main 同步 33c1bd6；外网复核 **17/17**（version.json/index 三处版本号+四新 JAR 链接+1.7.5.8 保留/tutorial 新条目+四 JAR 公网 md5/源码包 md5/旧 JAR 在线）。

- **给用户的口径**：升级替换 JAR 重启即完成（配置自动升 50：新键 qq-forward-display-name 默认 group_nickname=群昵称、restrict-timeout-seconds 默认 120 秒；旧键 group-sender-name 自动删除）。①全量转发的显示名在配置里可选「群昵称/QQ 用户名」——注意 QQ 官方接口在群消息里只返回一个昵称字段（群名片优先、未设置即 QQ 昵称，即用户要的「card 空回退 nickname」），两个选项当前显示同一个值；「/发信息」命令转发的名字保持群昵称不变。②菜单模板：没改过的（命中任一历史版本默认）升级时删除重建为本版默认；改过的保留不动。③Fabric 端绑定改为限制策略：未绑定玩家进服后动不了/点不了/聊不了/发不了命令，聊天栏每 5 秒显示绑定码，120 秒（可配）未绑定踢出，群里绑定成功自动解除全部限制；/hbkrg qbd 已删除。⚠ 假人插件无白名单：新策略下假人可正常加入但超时同样会被踢（本轮未做假人白名单，用户未要求）；Spigot/NeoForge 端仍为登录期拦截（1.7.5.8 假人豁免保持），与 Fabric 端两套策略的差异待用户定夺。
'''
i = t.find('## 本轮详细更新日志')
t = status_new + '\n' + t[t.find('\n## 本轮详细更新日志', 10):] if False else status_new
# 直接以新状态+本轮节整体替换（旧 1.7.5.8 节随只留最新 1 轮规则移除）
p.write_text(status_new, encoding='utf-8')
print('[ok] 更新日志.md')

# ========== 2. 会话上下文.md ==========
p = KB / 'session-context.md'
t = p.read_text(encoding='utf-8')
head_end = t.find('## 会话记录')
if head_end < 0:
    head_end = t.find('---')
new_session = '''# 会话上下文(SUZHE014 / KERONGPenguin)

> **维护规则(长期有效)**:
> 1. 每进行一个【大步骤】就更新一次本文件,写上准确日期时间(北京时间,精确到分)。
> 2. 完成一整个任务后,再更新一次(稍微简略写一点,做总结)。
> 3. 本文件由当轮 AI 会话负责维护;新会话动手之前必须先读完知识库所有内容(见 AI交接文档.md)。
> 4. 每轮会话结束删除更早的会话小节,只保留最新一轮(2026-11-13 用户指令:知识库只保留最新的版本;历史会话记录见各轮 git 提交信息)。

---

## 会话记录

### 2026-11-14 16:45 起：1.7.5.9 发版轮(显示名可配+菜单模板删除重建+Fabric 绑定限制策略,已完成上线)

会话来源:用户交接文档(「企鹅 QQ 机器人项目 · AI 交接文档」,三项任务固化+用户对 1.7.5.8 上轮方案的澄清)。

**步骤记录(北京时间 2026-11-14)**:
- [16:45-17:10] 环境重建+三件套细读+三项任务调研:中继 bundle 267MB 恢复(1981 文件 eb97d7e)+fabric-deps+JDK21/Gradle(dists 布局坑:内容必须在 gradle-8.14.5/ 子目录层,散文件布局致 wrapper 强制联网)。**开轮发现交接文档与知识库冲突:1.7.5.8 已上线(eb97d7e)但为上轮 AI 初次实现,方向偏差(显示名做成 group/player;假人做成地址豁免),本轮按用户澄清整体覆盖,版本号顺延 1.7.5.9**。调研实锤:官方事件 User 只有 username 一个昵称字段(bot.qq.com 文档+kloping Contact javap 双通道;「card/nickname」为 OneBot 记忆);menu.md 历代 4 形态 git 考古全在 olds;fabric-api 无通用命令事件(ALLOW_COMMAND_MESSAGE 仅 /me /say——PlayerManagerMixin 注 broadcast 形态 1.20.4 调用者仅 EmoteCommands/SayCommand),命令通用拦截定为 brigadier requirement Unsafe 包装。
- [17:10-17:40] 实现:①qq-forward-display-name(group_nickname/qq_nickname,仅全量转发;/发信息保持群昵称);上轮 group-sender-name 整体拆除(REMOVED 删键/GroupSenderName/getBoundPlayerNameByOpenid 删/ChatFormat 字段改);config 49→50。②applyReplacement 先 delete 再 writeBytes;②③路径统一;menu 考据注释。③BindRestrict/BindRestrictInteractions/BindRestrictCommandGate 三新文件+FabricEvents 五新事件接线+JOIN/DISCONNECT 接 BindRestrict+FabricCommands 删 qbd;hasPendingCode/restrictTimeoutSeconds;三端 config.yml 两新键。
- [17:40-18:00 约] 构建验证:四模块 rm -rf build 全量重编(clone 携带旧 build 产物坑:首轮 package_jar 误读旧 plugin.yml 版本,输出名与 base 同名互相覆盖 3.0MB 残废 JAR,git checkout -- build/dist 恢复);门禁 verify_jar_1759 **184/184**(intermediary 名经 merged.tiny 核实:teleport DDDFF=method_14363 非初判 method_14397;H1 阈值修正仅 Spigot——NF661/Fa680 历史基线);冒烟 smoke1759 **20/20**(受限玩家端到端未完成——MC 类加载需 Mojang 私有库 datafixers/fastutil 沙盒不可达,如实存档由门禁字节码+生产覆盖)。
- [17:35-17:55] 发版:commit 8aaf105(主体)+33c1bd6(官网/README);四 JAR md5 往返;官网三件(铁律前置:线上基线 421b8be4 与 git website/ 一致);源码包 1110 文件 8.78MB(排除清单对齐上轮+smoke174 slf4j 两 jar+smoke1759/out;libs/README.txt 保留);push+中继 main=33c1bd6;外网复核 **17/17**。

**任务完成总结(2026-11-14 17:55)**:
1.7.5.9 闭环上线。①显示名 chat-format.qq-forward-display-name(group_nickname 黄1/qq_nickname;官方单昵称字段两选项当前同值,如实注释,官方开放后 resolveForwardDisplayName 接入);/发信息保持群昵称;上轮 group-sender-name(player 模式)整体拆除。②菜单模板按用户口径显式化:命中历史默认→delete+writeBytes 删除重建;改过保留;menu 历代 4 形态考据注释。③Fabric 绑定限制策略:锁移动/禁交互/禁聊天/禁命令(requirement Unsafe 包装)/绑定码 5 秒一条(过期自动换新)/120 秒可配超时踢出/绑定成功 1 秒内解限/qbd 删除;假人无白名单如实实现;Spigot/NF 端不动(登录期拦截+1.7.5.8 假人豁免),两套策略差异待用户定夺。config-version 49→50。GitHub main=33c1bd6,中继同步,外网复核 17/17。给用户口径:替换 JAR 重启即升级;默认显示群昵称已是现行为;Fabric 端新策略详见教程。
'''
p.write_text(new_session, encoding='utf-8')
print('[ok] 会话上下文.md')

# ========== 3. AI交接文档.md：坑表追加 ==========
p = KB / 'handover.md'
t = p.read_text(encoding='utf-8')
anchor = '## 10. Cross-posting 互转版（1.6.7_Cross-posting.3）'
assert anchor in t
new_rows = '''| 沙盒 clone 携带旧 build 产物(1.7.5.9) | bundle 恢复自带 server-*/build/libs 旧 jar(内 plugin.yml 还是旧版本),直接 package_jar 误读版本号→输出名与 base 同名互相截断(3.0MB 残废 JAR);且 git 跟踪的 build/dist JAR 在 clone 时可能被截断(BadZipFile/zip 条目时间戳 07:27) | 发版轮一律 `rm -rf` 四模块 build 后全量重编(模块级 clean 不会被 root `gradlew clean` 覆盖);base JAR 损坏用 `git checkout -- build/dist/` 恢复(git 对象完好);打包后必比对产物尺寸与上一轮同级 |
| fat JAR 目录条目阈值适用面(1.7.5.9) | NF 661/Fa 680 目录条目为历史基线(与上一版完全一致零消失),按 Spigot 的 776 阈值套全部端会误报 | H1 类断言阈值仅 Spigot≥700;NF/Fa 按历史基线断言(1.7.5.8/1.7.5.9 两版实测 661/680 不变) |
| intermediary 方法名必须查表核实(1.7.5.9) | teleort(DDDFF) 初判 method_14397 实为 **method_14363**(merged.tiny `grep "m\\t(DDDFF)V"`);sendSystemMessage=method_43496;position=method_19538;disconnect 1.20.4=method_52396/1.20.1=method_14367 | Fabric JAR 断言一律 grep merged.tiny/merged-1201.tiny 取真名;两张表均需核对(两版同名才可共用断言) |
| MC 私有库沙盒不可达(1.7.5.9) | datafixers/fastutil 只在 libraries.minecraft.net(Central/华为镜像 404,沙盒拉取失败)→冒烟 JVM 无法加载 ServerPlayer/CommandSourceStack(clinit 连锁 NoClassDefFound) | 冒烟聚焦可测结构(包装/isRestricted/字节常量);端到端行为由门禁字节码断言+生产 Fabric 环境覆盖,如实存档不虚报 |
| Kotlin object 冒烟接收者(1.7.5.9 再踩) | BindRestrict/BindRestrictInteractions 是 object,Java 冒烟直接静态调 BindRestrict.isRestricted 编译报 non-static | 一律 INSTANCE 接收者;内部类字节码在独立 class 文件(BindRestrictCommandGate$GatePredicate.class),javap 单类不含内部类,断言要单独取 |
| 门禁断言的 Fabric 中文常量(1.7.5.9) | javap -v 常量池中文为 \\uXXXX 转义,直接 in 文本查不到 | 类字节级搜:zf.read(class) 后 `"中文".encode("utf-8") in bytes`(坑表既有条目的再应用);方法引用断言查 intermediary 名(javap 文本可见) |
| 源码包排除清单增量(1.7.5.9) | smoke174 的 slf4j 两 jar 上一轮已排除,本轮清单漏抄会混入;smoke 新目录的 out/ 编译产物不该入包;libs/README.txt 按「留 README」口径保留 | 打包后与上一版 zip 做 namelist diff(仅新增本轮文件+仅删除已删源文件为通过);smoke1759 口径=Java 源+classpath.txt 不含 out/ |

'''
t = t.replace(anchor, new_rows + anchor, 1)
p.write_text(t, encoding='utf-8')
print('[ok] AI交接文档.md 坑表追加 7 行')

# ========== 上传 ==========
import subprocess
for local, remote in [
    (str(KB / 'changelog.md'), SZ + 'AI交接文档.md.tmp-x'),  # 占位避免误传
]:
    pass
names = {
    'changelog.md': '更新日志.md',
    'session-context.md': '会话上下文.md',
    'handover.md': 'AI交接文档.md',
}
for local, remote in names.items():
    r = subprocess.run(['python3', '/home/z/my-project/scripts/ssh_helper.py', 'put',
                        str(KB / local), SZ + remote], capture_output=True, text=True, timeout=120)
    print('[put]', remote, '->', 'OK' if r.returncode == 0 else r.stdout[-200:])
print('知识库回写完成')

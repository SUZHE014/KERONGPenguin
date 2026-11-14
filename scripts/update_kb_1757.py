#!/usr/bin/env python3
"""1.7.5.7 修复轮知识库更新：更新日志.md / 会话上下文.md / AI交接文档.md（坑表增补）。"""
import pathlib, sys

KB = pathlib.Path("/home/z/my-project/kb")
ok = True

def patch(name, pairs):
    global ok
    p = KB / name
    t = p.read_text(encoding="utf-8")
    for old, new in pairs:
        n = t.count(old)
        if n != 1:
            print(f"FAIL {name}: 锚点出现 {n} 次（应为 1）: {old[:60]!r}")
            ok = False
            return False
        t = t.replace(old, new)
    p.write_text(t, encoding="utf-8")
    print(f"patched {name}")
    return True

# ================= 更新日志.md =================
# (1) 状态表全量替换
patch("更新日志.md", [(
    """## 当前版本状态(更新于 2026-11-14 02:00)

| 项 | 状态 |
|---|---|
| 当前版本 | **1.7.5.6**（同号两波：Fa 修复轮 + 主线修复轮；config-version **47**，只加两新键无迁移） |
| 四端产物 | KERONGPenguin_Spigot-1.7.5.6.jar(md5 360c49ac…)/ KERONGPenguin_NeoForge-1.20.4-1.7.5.6.jar(32ffa9d6…)/ KERONGPenguin_Fabric-1.20.4-1.7.5.6.jar(8486dc81…)/ KERONGPenguin_Fabric-1.20.1-1.7.5.6.jar(4040d325…)(/wwwroot/penguin/downloads/，md5 往返一致，8/8) |
| config-version | **47**（新增 ai.reminder-mode=""（AI 提醒方式三选一，空串=未选择回退读旧键 ai.reply-quote）+ bot.button-cooldown-seconds=5（按钮互动冷却，0 关闭）；不做旧值迁移，升级用户旧 reply-quote 偏好不被覆盖） |
| git push | ✅ GitHub main = **d15c3c9**（aed9c91 主体 + d15c3c9 部署脚本入库；ghfast.top 代理 + classic ghp_ GIT_ASKPASS；api.github.com 复核；服务器中继 relay-main 已同步 aed9c91） |
| 官网 | version.json(1.7.5.6 七条：Fa 轮四条 + 主线轮三条)/ index.html(下载按钮版本简写（1.20.4）/（1.20.1）+ Fabric 版本要求兼容性卡片 + 删「其他版本」区残留 1.7.5.5 旧卡片 + 历史表 Fabric 行改「通用包/独立包」标注 + 1.7.5.6 日期 2026-11-14)/ tutorial.html(新增「AI 回复提醒方式」与「按钮互动冷却」两个 cfg-item，替换旧「回复引用」条目)上线；源码包 KERONGPenguin_v1.7.5.6_source.zip(9.06MB/833 文件，HEAD aed9c91，md5 ff3390cb)传 /wwwroot/penguin-source/（替换同号旧包，仍留最新 3 个） |
| MC 服务端 | ⚠️ 不在 156.233.228.248 上；用户在实际服务器替换 JAR 并重启后生效（插件版 plugins/KERONGPenguin/，模组版 mods/；四端任选，数据互通迁移见 AI交接文档 §3） |
| fa 版本 | Fabric 1.7.5.6 两产物：通用包(1.20.4–1.21.x) + 1.20.1 独立包（同号两波均随行） |
| Fa 遗留 | ✅ 全部闭环：1.7.4.1 崩溃=旧打包流程方法名漏 remap（1.7.5.1 起已修）；「3 个二维码」=旧实现重显叠加（1.7.5.6 手动=全新码）；踢人 disconnect 1.20.4/1.21 映射正确，1.20.1 经映射表补丁修复（本轮门禁 G10/G11 复验） |
| 图片包 | wl1「蔚蓝档案」41 张 / 86,655,491 字节（本轮未动） |
| 备份 | ✅ 单包口径：SUZHEWWO(2026.11.07-4).tar.gz(v4 全量)；/SUZHE014/ 只留这一个包 |""",
    """## 当前版本状态(更新于 2026-11-14 02:40)

| 项 | 状态 |
|---|---|
| 当前版本 | **1.7.5.7**（艾特其他机器人误判修复轮，一项修复 + 连带防御；config-version **47 保持**，纯判定逻辑修复无新键无迁移） |
| 四端产物 | KERONGPenguin_Spigot-1.7.5.7.jar(md5 e7817624…)/ KERONGPenguin_NeoForge-1.20.4-1.7.5.7.jar(3f891a62…)/ KERONGPenguin_Fabric-1.20.4-1.7.5.7.jar(f7097ba8…)/ KERONGPenguin_Fabric-1.20.1-1.7.5.7.jar(b8410a4d…)(/wwwroot/penguin/downloads/，md5 往返 7/7 + 外网复核 8/8) |
| config-version | **47**（1.7.5.6 两新键不变；本轮零配置变更——用户无需改任何配置，替换 JAR 重启即完成升级） |
| git push | ✅ GitHub main = **7cecaeb**（64cc343 主体 + 7cecaeb 部署/验证脚本入库；api.github.com 复核；服务器中继 /root/gitrelay/KERONGPenguin.git main 已同步 7cecaeb） |
| 官网 | version.json(1.7.5.7 两条修复说明，替换 1.7.5.6 七条)/ index.html(主框版本 1.7.5.7 + 四下载按钮 + 历史表 1.7.5.7 四行置顶 + 更新日志 v1.7.5.7 块)/ tutorial.html 零改动(无新键无行为变化)上线；源码包 KERONGPenguin_v1.7.5.7_source.zip(9.28MB/1085 文件，HEAD 7cecaeb，md5 ce19e5ae)传 /wwwroot/penguin-source/（留最新 3 个：1.7.5.5/1.7.5.6/1.7.5.7） |
| MC 服务端 | ⚠️ 不在 156.233.228.248 上；用户在实际服务器替换 JAR 并重启后生效（插件版 plugins/KERONGPenguin/，模组版 mods/；四端任选，数据互通迁移见 AI交接文档 §3） |
| fa 版本 | Fabric 1.7.5.7 两产物：通用包(1.20.4–1.21.x) + 1.20.1 独立包（随主线同号） |
| Fa 遗留 | ✅ 全部闭环（1.7.5.6 定版）：1.7.4.1 崩溃=旧打包流程方法名漏 remap（1.7.5.1 起已修）；「3 个二维码」=旧实现重显叠加（1.7.5.6 手动=全新码）；踢人 disconnect 1.20.4/1.21 映射正确，1.20.1 经映射表补丁修复（本轮门禁 C14/C15 复验） |
| 图片包 | wl1「蔚蓝档案」41 张 / 86,655,491 字节（本轮未动） |
| 备份 | ✅ 单包口径：SUZHEWWO(2026.11.07-4).tar.gz(v4 全量)；/SUZHE014/ 只留这一个包 |"""
)])

# (2) 本轮详细日志节：整节替换（只保留最新 1 轮）
import re
t = (KB / "更新日志.md").read_text(encoding="utf-8")
marker = "## 本轮详细更新日志"
idx = t.find(marker)
if idx == -1:
    print("FAIL 更新日志.md: 找不到本轮节标记")
    ok = False
else:
    new_section = """## 本轮详细更新日志(1.7.5.7 修复轮；1.7.5.6 及之前全量历史见 git 提交历史)

### 2026-11-14 02:05-02:40:1.7.5.7 艾特其他机器人误判修复轮(用户报告一项 + 连带防御)

用户报告：「本次 1.7.5.7，修复艾特其他机器人回去知道艾特他自己的问题」——群里艾特其他机器人（如另一个 AI 机器人）时，本机器人误判为被艾特，AI 插嘴回复还艾特发送人。

**[02:05-02:10] 根因定位**：GroupMessageHandler.isMentionedBot（判定「消息是否艾特了本机器人」，AI 对话触发门槛）通道二旧条件「mentions 任一成员 bot=true 即命中」——bot 标志只说明该成员是机器人，不说明被艾特的是本机器人；群里艾特其他机器人时全量模式 GROUP_MESSAGE_CREATE 的 mentions 同样带那个机器人的 bot=true 条目 → 误判成立 → AI 误触发插嘴 + 回复艾特发送人。全项目核查 bot==true 泛匹配仅此一处误用（AiAt/PublicCommands 两处为正确排除用法）。

**[02:10-02:15] 三处修复（common-Bot 一处改动三端生效）**：①根因：isMentionedBot 通道二删除 bot 泛匹配，只认精确 id（botSelfId=QClient.starter.bot.id WSS 登录后可用 / botAppId=配置 bot.app-id 双源）；通道一 GROUP_AT 帧标记（GroupAtTracker，GROUP_AT_MESSAGE_CREATE 只在用户艾特本机器人时推送，艾特其他机器人不产生）仍为主判定，通道三文本 <@id> 匹配保留。艾特本机器人正常对话、艾特本机器人+其他机器人同场均不受影响；只艾特其他机器人/其他人照旧不触发命令与 AI（落全量转发，与 1.7.4.1「只@其他人」口径一致）。②连带防御一：AiAt.renderOutgoing 一级反查（本条消息 mentions）排除 bot 成员——AI 输出的 [at:机器人昵称] 不再反查到机器人 openid，降级纯文本 @昵称。③连带防御二：OpenIdDirectory.recordEvent mentions 不再收录机器人（二级反查防线）。发送者侧无需防御（QQ 官方机器人之间互收不到消息，sender 不可能是机器人，SDK Contact 类亦无 bot 字段——javap 确认）。版本号六处 1.7.5.6→1.7.5.7；config-version 47 保持（无新键）；官网 index.html+version.json 更新（tutorial 无新键不动）+README 三节。

**[02:15-02:20] 构建验证**：构建前删 fabric remap 缓存（坑表预防，本轮未踩）；四模块构建 2m31s；四 JAR 打包（Spigot 底座=线上 1.7.5.6，NF/Fa 底座=本版 Spigot）。门禁 verify_jar_1757 **125/125 全绿**（A 版本资源/B 修复点三处字节码断言——isMentionedBot 方法体无 User.getBot、通道一 isAtBot 与 Bot.getId 双源保留、renderOutgoing 与 recordEvent 含 getBot 排除/C 前轮全量回归含 Fabric 双包未映射白名单 4 条/H 形态）。冒烟 smoke_1757 **14/14 全绿**（独立 JVM 真实类驱动：S1 艾特其他机器人 bot=true 不误判（旧版必误判）/S1b 用户场景端到端/S2 精确命中 botAppId/S3 文本通道/S4 GROUP_AT 帧/S5 无艾特/S6 混合场/S7-S9 AI 出向机器人降级纯文本+普通成员正常渲染/S10 入向占位剥除回归/S11-S13 目录不收录机器人/收录普通成员/同名真人不挡）。md5：Spigot e7817624/NeoForge 3f891a62/Fabric-1.20.4 f7097ba8/Fabric-1.20.1 b8410a4d。

**[02:20-02:40] 部署提交**：部署 7/7 SAME（四 JAR+官网两件+源码包 1085 文件 9.28MB；tutorial 无改动未传；下载目录与源码包各留最新 3 版）；外网复核 8/8（四 JAR 公网 md5+官网三件内容断言+源码包）；commit 64cc343+7cecaeb push 成功（api.github.com 复核 main=7cecaeb）；服务器中继 main 同步 7cecaeb；知识库三件套更新。

- **给用户的口径**：升级只需替换 JAR 重启，无需改任何配置；群里艾特其他机器人不再触发本机器人插嘴，艾特本机器人的 AI 对话一切照旧；AI 也不会再艾特群里其他机器人（防两个机器人互相艾特刷屏）。
"""
    t = t[:idx] + new_section
    (KB / "更新日志.md").write_text(t, encoding="utf-8")
    print("patched 更新日志.md 本轮节")

# ================= 会话上下文.md =================
(KB / "会话上下文.md").write_text("""# 会话上下文(SUZHE014 / KERONGPenguin)

> **维护规则(长期有效)**:
> 1. 每进行一个【大步骤】就更新一次本文件,写上准确日期时间(北京时间,精确到分)。
> 2. 完成一整个任务后,再更新一次(稍微简略写一点,做总结)。
> 3. 本文件由当轮 AI 会话负责维护;新会话动手之前必须先读完知识库所有内容(见 AI交接文档.md)。
> 4. 每轮会话结束删除更早的会话小节,只保留最新一轮(2026-11-13 用户指令:知识库只保留最新的版本;历史会话记录见各轮 git 提交信息)。

---

## 会话记录

### 2026-11-14 02:05 起:1.7.5.7 艾特其他机器人误判修复轮(已完成上线)

会话来源:用户消息「本次1.7.5.7 修复艾特其他机器人回去知道艾特他自己的问题」(上一轮 1.7.5.6 A/B 系列已全部完成,知识库为证)。

**步骤记录(北京时间 2026-11-14)**:
- [02:05-02:10] 根因定位:isMentionedBot 通道二「member.bot==true 即判定被艾特」泛匹配——群里艾特其他机器人时全量模式 mentions 同样带 bot=true 条目,误判→AI 插嘴+回复艾特发送人;全项目核查泛匹配仅此一处(AiAt/PublicCommands 为正确排除用法);SDK User 有 bot 字段、Contact(sender) 无;QQ 官方机器人互收不到消息(sender 防御不需要)。
- [02:10-02:15] 修复:①isMentionedBot 通道二删泛匹配只认精确 id(botSelfId/botAppId 双源),通道一 GROUP_AT 帧标记为主判定(艾特其他机器人不产生该事件),通道三文本匹配保留;②AiAt.renderOutgoing 一级反查排除 bot(出向 [at:机器人] 降级纯文本);③OpenIdDirectory.recordEvent mentions 不收录机器人(二级反查防线)。版本六处→1.7.5.7;config 47 保持;官网 index+version.json+README。
- [02:15-02:20] 构建:先删 fabric remap 缓存(坑表预防,未踩);四模块 2m31s;四 JAR 打包。验证:门禁 verify_jar_1757 125/125(B 段修复点字节码断言含合成方法 lambda 坑修正:Regex.replace 的 lambda 编译为 renderOutgoing$lambda$N,同类方法 javap 不带类前缀);冒烟 smoke_1757 14/14(独立 JVM 真实类,SDK 事件构造需 metadata 含 id+author 且 fastjson 在 NF JAR 重定位为 cn.kerong.shaded)。
- [02:20-02:40] 部署:7/7 SAME+外网复核 8/8;commit 64cc343+7cecaeb push(api.github.com 复核 main=7cecaeb);中继同步;知识库三件套更新。

**任务完成总结(2026-11-14 02:40)**:
1.7.5.7 一项修复+连带防御全部闭环上线。①艾特其他机器人不再误触发 AI 插嘴(通道二精确 id 匹配,GROUP_AT 帧标记为主判定);②AI 出向不再艾特其他机器人(renderOutgoing 排除 bot 降级纯文本,防机器人互踩);③成员目录不再收录机器人(二级反查防线)。艾特本机器人的正常 AI 对话完全不受影响。零配置变更(47 保持),用户替换 JAR 重启即升级。md5:Spigot e7817624/NeoForge 3f891a62/Fabric-1.20.4 f7097ba8/Fabric-1.20.1 b8410a4d/源码包 ce19e5ae。开发注意存档(已入 AI交接文档坑表):①git archive 的 ':(exclude)**/build' 双星 glob 不生效,须逐模块显式路径 ':(exclude)server-NeoForge/build' 等;②源码包口径=git 内容排除 build/.gradle/各模块 build/scripts/deps-work/全部 libs/*.jar(保留 README),本轮 1085 文件 9.28MB;③deps.jar 内部分条目 zip 权限位损坏,unzip 出来 Permission denied,须 Python zipfile 解压+chmod;④冒烟构造 BaseGroupMessageEvent 必须 metadata 含 id+author 键(fastjson 用重定位类名反射构造)。
""", encoding="utf-8")
print("rewritten 会话上下文.md")

# ================= AI交接文档.md：坑表增补 =================
patch("AI交接文档.md", [(
    "| package_jar_fabric remap 缓存无条件复用 | common-Bot 有改动但 scripts/deps-work/fabric-libs/package/server-Fabric-intermediary-*.jar 未删 → Fabric 双 JAR 混入旧 common-Bot 类(体积正常,门禁 B/C/D/E 段批量失败抓出) | common-Bot 有改动的轮次先删该两个缓存 jar 再跑 package_jar_fabric(1.7.5.6 主线修复轮实测) |",
    """| package_jar_fabric remap 缓存无条件复用 | common-Bot 有改动但 scripts/deps-work/fabric-libs/package/server-Fabric-intermediary-*.jar 未删 → Fabric 双 JAR 混入旧 common-Bot 类(体积正常,门禁 B/C/D/E 段批量失败抓出) | common-Bot 有改动的轮次先删该两个缓存 jar 再跑 package_jar_fabric(1.7.5.6 主线修复轮实测) |
| git archive exclude 双星 glob 不生效 | ':(exclude)**/build' 排不掉 server-*/build(打出 2272 文件/140MB) | 逐模块显式路径:':(exclude)common-Bot/build' 等逐个列出;源码包口径=git 内容排除 build/.gradle/各模块 build/scripts/deps-work/全部 libs/*.jar(留 README)(1.7.5.7 实测 1085 文件 9.28MB) |
| deps.jar 部分条目 zip 权限位损坏 | unzip 解出 SDK 类文件 Permission denied(javap class not found) | Python zipfile 逐条目解压+chmod 0o644(1.7.5.7 冒烟准备时踩) |
| 冒烟构造 SDK 群消息事件 | BaseGroupMessageEvent 构造器读 metadata.getString("id")与 getJSONObject("author")→Member 构造再读 author 内键,null 即 NPE | metadata 必须含 id+author(id/member_openid/username);fastjson 在 NF 分发 JAR 里已重定位,须 Class.forName("cn.kerong.shaded.com.alibaba.fastjson.JSONObject") 反射构造(1.7.5.7 冒烟) |
| 门禁断言 Kotlin lambda/同类调用 | Regex.replace 的 lambda 未内联,反查调用落在合成方法 renderOutgoing$lambda$N(方法体抽取漏掉→误报 FAIL);同类私有方法调用 javap 只显示 Method record: 不带类前缀 | lambda 断言按 re.findall(r"方法名\\$lambda\\$\\d+") 抽全部合成方法合并判;同类调用按 "Method 方法名:" 计数(1.7.5.7 门禁 B8/B10 初版误报修正) |"""
)])

sys.exit(0 if ok else 1)

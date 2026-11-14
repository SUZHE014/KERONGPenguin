#!/usr/bin/env python3
"""1.7.5.7 覆盖轮知识库更新：更新日志.md / 会话上下文.md / AI交接文档.md（坑表增补）+ 上传。"""
import pathlib
import sys

sys.path.insert(0, "/home/z/my-project/scripts")
from ssh_cmd import sftp_upload, run  # noqa: E402

KB = pathlib.Path("/home/z/my-project/kb")
REMOTE = "/SUZHE014/知识库"
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
# (1) 状态表替换（1.7.5.7 主轮行 → 覆盖轮行）
patch("更新日志.md", [(
    """## 当前版本状态(更新于 2026-11-14 02:40)

| 项 | 状态 |
|---|---|
| 当前版本 | **1.7.5.7**（艾特其他机器人误判修复轮，一项修复 + 连带防御；config-version **47 保持**，纯判定逻辑修复无新键无迁移） |
| 四端产物 | KERONGPenguin_Spigot-1.7.5.7.jar(md5 e7817624…)/ KERONGPenguin_NeoForge-1.20.4-1.7.5.7.jar(3f891a62…)/ KERONGPenguin_Fabric-1.20.4-1.7.5.7.jar(f7097ba8…)/ KERONGPenguin_Fabric-1.20.1-1.7.5.7.jar(b8410a4d…)(/wwwroot/penguin/downloads/，md5 往返 7/7 + 外网复核 8/8) |
| config-version | **47**（1.7.5.6 两新键不变；本轮零配置变更——用户无需改任何配置，替换 JAR 重启即完成升级） |
| git push | ✅ GitHub main = **7cecaeb**（64cc343 主体 + 7cecaeb 部署/验证脚本入库；api.github.com 复核；服务器中继 /root/gitrelay/KERONGPenguin.git main 已同步 7cecaeb） |
| 官网 | version.json(1.7.5.7 两条修复说明，替换 1.7.5.6 七条)/ index.html(主框版本 1.7.5.7 + 四下载按钮 + 历史表 1.7.5.7 四行置顶 + 更新日志 v1.7.5.7 块)/ tutorial.html 零改动(无新键无行为变化)上线；源码包 KERONGPenguin_v1.7.5.7_source.zip(9.28MB/1085 文件，HEAD 7cecaeb，md5 ce19e5ae)传 /wwwroot/penguin-source/（留最新 3 个：1.7.5.5/1.7.5.6/1.7.5.7） |""",
    """## 当前版本状态(更新于 2026-11-14 11:15)

| 项 | 状态 |
|---|---|
| 当前版本 | **1.7.5.7**（同号两波：主轮艾特其他机器人误判修复 + 覆盖轮全量群艾特唤起 AI 修复/配置清理/官网口径；config-version **48**，删旧键 ai.reply-quote 自动迁移） |
| 四端产物 | KERONGPenguin_Spigot-1.7.5.7.jar(md5 d4756a76…)/ KERONGPenguin_NeoForge-1.20.4-1.7.5.7.jar(1861d084…)/ KERONGPenguin_Fabric-1.20.4-1.7.5.7.jar(f4aa4cf1…)/ KERONGPenguin_Fabric-1.20.1-1.7.5.7.jar(f2cfadcf…)(/wwwroot/penguin/downloads/，md5 往返 8/8 + 外网复核 23/23；同号覆盖主轮产物) |
| config-version | **48**（删旧键轮：ai.reply-quote 并入 ai.reminder-mode 后删除，升级自动归一迁移——旧键 false 且新键未选 → none，其余 → quote；用户无需改任何配置，替换 JAR 重启即完成升级） |
| git push | ✅ GitHub main = **fd56911**（b0edb87 主体 + fd56911 部署/验证脚本入库；服务器中继 /root/gitrelay/KERONGPenguin.git main 已同步 fd56911） |
| 官网 | version.json(1.7.5.7 四条：全量群艾特修复+主轮两条+配置清理)/ index.html(Fabric 按钮括号（1.20.4-1.21.x）+ 兼容表同步 + v1.7.5.7 日志块补两条)/ tutorial.html(扫码段通俗化+reminder-mode 段删旧键)上线；源码包 KERONGPenguin_v1.7.5.7_source.zip(9.10MB/846 文件，HEAD b0edb87，md5 0b876916)覆盖上传 /wwwroot/penguin-source/（留最新 3 个：1.7.5.5/1.7.5.6/1.7.5.7） |""",
)])

# (2) 本轮详细日志节：整节替换（只保留最新 1 轮）
t = (KB / "更新日志.md").read_text(encoding="utf-8")
marker = "## 本轮详细更新日志"
idx = t.find(marker)
if idx == -1:
    print("FAIL 更新日志.md: 找不到本轮节标记")
    ok = False
else:
    new_section = """## 本轮详细更新日志(1.7.5.7 覆盖轮；主轮及之前全量历史见 git 提交历史)

### 2026-11-14 10:30-11:15:1.7.5.7 覆盖轮(同号重发，四项)

用户指令：「本次覆盖1.7.5.7：①修复现在艾特机器人也无法唤起AI回复的问题 ②新增的几个配置文件注释简化，不要出现（1.....新增）等字段，把旧键删了 ③官网 Fa1.20.4 版本的下载按钮括号里面的内容改为1.20.4-1.21.x ④教程扫二维码那里不要写那些小白看不懂的东西」。

**[10:30-10:50] 根因定位（艾特机器人无法唤起 AI）**：主轮上线后用户报告反向 bug。反编译 SDK（deps.jar：GroupEventsRegister.handle 对 GROUP_AT_MESSAGE_CREATE 与 GROUP_MESSAGE_CREATE 两帧构造同一个 BaseGroupMessageEvent 分发；Bot.getInfo 懒加载缓存 /users/@me）+ 查官方文档（bot.qq.com 群消息全量模式事件）+ ErisPulse 成熟适配器佐证，确认两层根因：①群消息 mentions 里被艾特成员（含机器人）的 id 是**群内 openid 加密串，与机器人全局 id（bot.id/AppID）不是同一 ID 体系**——主轮的精确 id 匹配（botSelfId/botAppId 双源）对群消息永远不成立（1.7.5.6 泛匹配恰好是唯一能命中的通道，被主轮删掉）；②机器人开启「接收所有群消息」（全量模式）后 @ 消息只以 GROUP_MESSAGE_CREATE 推送、**GROUP_AT_MESSAGE_CREATE 不再到达**——通道一 GroupAtTracker 永远空。三通道全灭 → 艾特机器人唤不起 AI 也用不了「@机器人+指令」（mentionBarred 挡命令）。ErisPulse 文档同证：「The @ marker is represented as <@{group space openid}> (which is not in the same ID system as the bot_id returned by READY)」「After enabling "receive all group messages", @ messages are pushed via GROUP_MESSAGE_CREATE (GROUP_AT_MESSAGE_CREATE no longer arrives)」，其解法「bot name returned by /users/@me matches the nickname in the mentions array」即昵称匹配。

**[10:50-11:00] 修复（common-Bot 一处三端生效）**：isMentionedBot 通道二新增**昵称匹配**（群全量模式主判定）——bot=true 的机器人条目且 username 与本机器人昵称一致即认定被艾特；昵称双源（/users/@me 的 username（Bot.getInfo SDK 懒加载缓存线程安全）+ 配置 bot.name）；匹配集懒构造（出现机器人条目才查，普通消息零开销）；艾特其他机器人时条目昵称是那个机器人的昵称不命中——主轮「艾特其他机器人不误判」修复口径严格保持。配置清理：三端 config.yml 去「（1.7.x 新增）」类版本字样；删旧键 ai.reply-quote（QqBindManager.isAiReplyQuote 属性删除、aiReminderMode 不再回退读旧键、ConfigManager 47→48 迁移先归一旧值再删键、DEFAULT_VALUES/废弃键清单同步）；教程 reminder-mode 段同步。官网：index.html Fabric 按钮括号（1.20.4）→（1.20.4-1.21.x）+兼容表同步+v1.7.5.7 日志块补两条；version.json 四条；tutorial.html 扫码段通俗化（不再提控制台编码/GBK/渲染形态）。

**[11:00-11:05] 构建验证**：构建坑——同号重发时 package_jar 的 base JAR 与输出同路径互相截断（上轮版本号变更无此冲突）：首轮 base=旧 1.7.5.7 产物与输出同名，Spigot 打包读一半被写坏（BadZipFile），NeoForge 随后用已截断的空 base 打出 3.5MB 残废产物（尺寸异常抓出）；改用完好的 1.7.5.6 产物作 base（第三方依赖等价、插件字节码全由新编译替换）重打四 JAR 全部正常。fabric remap 缓存按坑表预删。门禁 verify_jar_1757b **181/181 全绿**（A config 48+无 reply-quote+注释无版本字样/B 昵称通道四要素 getBot+getUsername+getInfo+botName 双源+aiReminderMode 无旧键+ConfigManager 48 迁移+主轮修复回归/C 前轮全量回归含 Fa 双包映射白名单/H 形态）。冒烟 smoke_1757b **19/19 全绿**（独立 JVM 真实类：N1 全量群艾特本机器人 openid 形态+昵称命中→true（主轮版必 false）/N2 其他机器人不命中/N3 普通成员同名 bot 收窄/N4 混合场/N5 空格容差/N6 空昵称/N7 bot=null/R1-R5 通道回归+用户场景端到端/M1-M7 删旧键后行为）。md5：Spigot d4756a76/NeoForge 1861d084/Fabric-1.20.4 f4aa4cf1/Fabric-1.20.1 f2cfadcf。

**[11:05-11:15] 部署提交**：部署 8/8 SAME（四 JAR+官网三件+源码包 846 文件 9.10MB 同号覆盖）；外网复核 23/23（四 JAR 公网 md5+index 按钮括号/兼容表/日志块+version.json 四条+tutorial 通俗文案与无 GBK 术语断言+源码包昵称通道与 config 48）；commit b0edb87+fd56911 push；中继同步 fd56911；知识库三件套更新。

- **给用户的口径**：升级只需替换 JAR 重启（配置自动迁移到 48，无需手动改）；开全量的群里艾特机器人即可正常唤起 AI 与「@机器人+指令」；艾特其他机器人依旧不响应（上一轮修复保持）；配置文件注释已清理，旧键 ai.reply-quote 已删除（想改提醒方式直接改 ai.reminder-mode）。
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

### 2026-11-14 10:30 起:1.7.5.7 覆盖轮(同号重发,四项,已完成上线)

会话来源:用户消息「本次覆盖1.7.5.7 修复现在艾特机器人也无法唤起AI回复的问题+新增的几个配置文件注释简化不要出现(1.x新增)等字段把旧键删了+官网Fa1.20.4下载按钮括号改1.20.4-1.21.x+教程扫二维码不要写小白看不懂的东西」(1.7.5.7 主轮已上线,本行为主轮副作用修复+三项用户指令)。

**步骤记录(北京时间 2026-11-14)**:
- [10:30-10:50] 根因定位(艾特唤不起 AI):反编译 SDK 确认 GroupEventsRegister 对 GROUP_AT/GROUP_MESSAGE 两帧构造同一 BaseGroupMessageEvent 分发,Bot.getInfo 懒加载缓存;官方文档+ErisPulse 佐证两层根因——①群 mentions id 是群内 openid,与 bot.id/AppID 不同 ID 体系,主轮精确 id 匹配对群消息永远不成立(1.7.5.6 泛匹配是唯一命中通道被主轮删掉);②全量模式开启后 GROUP_AT 帧不再到达,通道一 GroupAtTracker 永远空——三通道全灭。
- [10:50-11:00] 修复:isMentionedBot 通道二新增昵称匹配(bot=true 条目+username 与本机器人昵称一致;双源 /users/@me getInfo+配置 bot.name;懒构造匹配集);艾特其他机器人昵称不命中,主轮修复口径保持。配置:三端 config.yml 去版本字样注释;删 ai.reply-quote(isAiReplyQuote 删/aiReminderMode 无回退/ConfigManager 47→48 迁移归一旧值再删键);教程 reminder-mode 段同步。官网:Fa 按钮括号 1.20.4-1.21.x+兼容表+日志块;version.json 四条;tutorial 扫码段通俗化。
- [11:00-11:05] 构建验证:踩新坑——同号重发时 package_jar base 与输出同名互相截断(Spigot BadZipFile,NeoForge 用空 base 打出 3.5MB 残废产物,尺寸异常抓出),改用 1.7.5.6 产物作 base 重打;fabric remap 缓存已预删。门禁 verify_jar_1757b 181/181;冒烟 smoke_1757b 19/19(N1 全量群昵称命中→true 主轮版必 false;M2 旧键残留不再回退)。md5:Spigot d4756a76/NF 1861d084/Fa1204 f4aa4cf1/Fa1201 f2cfadcf。
- [11:05-11:15] 部署:8/8 SAME+外网复核 23/23;commit b0edb87+fd56911 push;中继同步;知识库三件套更新。

**任务完成总结(2026-11-14 11:15)**:
1.7.5.7 覆盖轮四项全部闭环上线(同号重发,版本号不变,config-version 47→48)。①全量群艾特机器人无法唤起 AI 已修(通道二昵称匹配,openid/全局 id 不同体系+全量模式 GROUP_AT 不达两层根因定位存档);②配置注释清理+旧键 ai.reply-quote 删除(自动迁移保留用户偏好);③官网 Fa 按钮括号 1.20.4-1.21.x;④教程扫码段通俗化。开发注意存档(已入 AI交接文档坑表):同号重发轮 package_jar 必须用上一版产物作 base(同路径读写互相截断,首个失败还会连带污染后续打包);冒烟昵称通道测试用 FakeKeronRuntime.getBotName() 硬编码 KERONGPenguin 作昵称源(QClient.starter 冒烟环境为 null,官方昵称源靠门禁 B6b 字节码断言覆盖)。
""", encoding="utf-8")
print("rewritten 会话上下文.md")

# ================= AI交接文档.md：坑表增补 =================
patch("AI交接文档.md", [(
    "| 门禁断言 Kotlin lambda/同类调用 | Regex.replace 的 lambda 未内联,反查调用落在合成方法 renderOutgoing$lambda$N(方法体抽取漏掉→误报 FAIL);同类私有方法调用 javap 只显示 Method record: 不带类前缀 | lambda 断言按 re.findall(r\"方法名\\$lambda\\$\\d+\") 抽全部合成方法合并判;同类调用按 \"Method 方法名:\" 计数(1.7.5.7 门禁 B8/B10 初版误报修正) |",
    """| 门禁断言 Kotlin lambda/同类调用 | Regex.replace 的 lambda 未内联,反查调用落在合成方法 renderOutgoing$lambda$N(方法体抽取漏掉→误报 FAIL);同类私有方法调用 javap 只显示 Method record: 不带类前缀 | lambda 断言按 re.findall(r\"方法名\\$lambda\\$\\d+\") 抽全部合成方法合并判;同类调用按 \"Method 方法名:\" 计数(1.7.5.7 门禁 B8/B10 初版误报修正) |
| 同号重发轮 package_jar base 与输出同名互相截断 | 版本号不变时 PENGUIN_BASE_JAR 与输出同为 build/dist/KERONGPenguin_Spigot-同版本.jar:打包边读边写,base 被截断(BadZipFile)且产物损坏;后续用被截断的空 base 打包还会产出 3.5MB 残废 JAR(无第三方依赖) | 同号重发轮用上一版产物作 base(如 1.7.5.6);打包后必查产物尺寸与上一轮同级,异常即弃(1.7.5.7 覆盖轮实测,尺寸对比抓出 NeoForge 残废产物) |""",
)])

# ================= 上传三件套 + md5 往返 =================
if ok:
    import hashlib
    for name in ("更新日志.md", "会话上下文.md", "AI交接文档.md"):
        local = KB / name
        remote = f"{REMOTE}/{name}"
        sftp_upload(str(local), remote)
        lm = hashlib.md5(local.read_bytes()).hexdigest()
        code, out, _ = run(f"md5sum '{remote}'", 30)
        rm = out.split()[0] if out else ""
        print(f"[{'SAME' if lm == rm else 'DIFF!'}] {remote} md5 {lm[:8]}")
        if lm != rm:
            ok = False

sys.exit(0 if ok else 1)

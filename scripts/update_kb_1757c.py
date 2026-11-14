#!/usr/bin/env python3
"""1.7.5.7 再覆盖轮知识库更新：更新日志.md / 会话上下文.md / AI交接文档.md（坑表增补）+ 上传。"""
import hashlib
import pathlib
import sys

sys.path.insert(0, "/home/z/my-project/scripts")
from ssh_cmd import run, sftp_upload  # noqa: E402

KB = pathlib.Path("/home/z/my-project/kb")
REMOTE = "/SUZHE014/知识库"
ok = True


def patch(name, pairs):
    """pairs: [(anchor, replacement_includes_anchor), ...]；anchor 唯一命中才替换。"""
    global ok
    p = KB / name
    t = p.read_text(encoding="utf-8")
    for anchor, repl in pairs:
        if anchor not in t:
            print(f"[MISS] {name}: anchor 不存在\n  {anchor[:60]}...")
            ok = False
        elif t.count(anchor) != 1:
            print(f"[WARN] {name}: anchor 非唯一({t.count(anchor)})，跳过\n  {anchor[:60]}...")
            ok = False
        else:
            t = t.replace(anchor, repl)
            print(f"[OK] {name}: patched\n  {anchor[:50]}...")
    p.write_text(t, encoding="utf-8")


# ================= 更新日志.md：状态表 + 本轮节替换 =================
t = (KB / "更新日志.md").read_text(encoding="utf-8")

# 状态表整块替换（从「## 当前版本状态」到「## 本轮详细更新日志」前）
import re
m = re.search(r"## 当前版本状态.*?(?=## 本轮详细更新日志)", t, re.S)
assert m, "状态表定位失败"
new_status = """## 当前版本状态(更新于 2026-11-14 12:10)

| 项 | 状态 |
|---|---|
| 当前版本 | **1.7.5.7**（同号三波：主轮艾特其他机器人误判修复 + 覆盖轮全量群艾特唤起 AI 修复/配置清理/官网口径 + 再覆盖轮昵称匹配只认官方昵称；config-version **48**） |
| 四端产物 | KERONGPenguin_Spigot-1.7.5.7.jar(md5 523b913a…)/ KERONGPenguin_NeoForge-1.20.4-1.7.5.7.jar(c517a6b6…)/ KERONGPenguin_Fabric-1.20.4-1.7.5.7.jar(616ac17a…)/ KERONGPenguin_Fabric-1.20.1-1.7.5.7.jar(60a4c5c9…)(/wwwroot/penguin/downloads/，md5 往返 7/7 + 外网复核 24/24；同号第三次覆盖) |
| config-version | **48**（保持；再覆盖轮无配置变更——bot.name 键保留仍作显示用途，仅退出艾特识别） |
| git push | ✅ GitHub main = **7ba9895**（40e4f79 主体 + 7ba9895 部署/复核脚本入库；服务器中继 /root/gitrelay/KERONGPenguin.git main 已同步 7ba9895） |
| 官网 | version.json(1.7.5.7 五条：全量群艾特修复+主轮两条+配置清理+⑤昵称唯一源)/ index.html(v1.7.5.7 日志块五条含「艾特识别的机器人昵称改为只从 QQ 官方接口自动获取(无需申请任何权限),不再依赖配置 bot.name」)/ tutorial.html(bot.name 段「仅用于显示」口径正确未动)上线；源码包 KERONGPenguin_v1.7.5.7_source.zip(9.12MB/854 文件，HEAD 40e4f79，md5 970a5406)覆盖上传 /wwwroot/penguin-source/（留最新 3 个：1.7.5.5/1.7.5.6/1.7.5.7） |
| MC 服务端 | ⚠️ 不在 156.233.228.248 上；用户在实际服务器替换 JAR 并重启后生效（插件版 plugins/KERONGPenguin/，模组版 mods/；四端任选，数据互通迁移见 AI交接文档 §3） |
| fa 版本 | Fabric 1.7.5.7 两产物：通用包(1.20.4–1.21.x) + 1.20.1 独立包（随主线同号） |
| Fa 遗留 | ✅ 全部闭环（1.7.5.6 定版）：1.7.4.1 崩溃=旧打包流程方法名漏 remap（1.7.5.1 起已修）；「3 个二维码」=旧实现重显叠加（1.7.5.6 手动=全新码）；踢人 disconnect 1.20.4/1.21 映射正确，1.20.1 经映射表补丁修复（再覆盖轮门禁 C14/C15 复验通过） |
| 图片包 | wl1「蔚蓝档案」41 张 / 86,655,491 字节（本轮未动） |
| 备份 | ✅ 单包口径：SUZHEWWO(2026.11.07-4).tar.gz(v4 全量)；/SUZHE014/ 只留这一个包 |

"""
t = t[:m.start()] + new_status + t[m.end():]

# 本轮节替换（「## 本轮详细更新日志」到「- **给用户的口径**」段末尾）
m2 = re.search(r"## 本轮详细更新日志.*", t, re.S)
assert m2, "本轮节定位失败"
new_round = """## 本轮详细更新日志(1.7.5.7 再覆盖轮；主轮/覆盖轮及之前全量历史见 git 提交历史)

### 2026-11-14 11:20-12:10:1.7.5.7 再覆盖轮(同号重发，昵称匹配去 bot.name 源)

用户指令：「配置 bot.name 的经常会有人写其他的，所以这个并不严谨；确保官方接口昵称不用申请任何东西能正常获取；如果本次不用修改任何东西，就只回答我的问题就行了，如果要修改则覆盖」。

**[11:20-11:30] 核实与定性**：①bot.name 双源风险坐实——覆盖轮昵称匹配集同时含官方昵称与配置 bot.name，bot.name 是显示名、实际使用中经常被随意填写，一旦撞上群里其他机器人的昵称，艾特那个机器人会被误判成艾特本机器人（主轮修复口径的复活通道）→ 需要修改；②官方接口结论（bot.qq.com 文档 + SDK 反编译）——`GET /users/@me` 是基础接口（「用户/机器人」分类，50 QPS，仅凭登录 token 即可调用，与登录 WSS 同一套凭据），**需要特殊申请的只有 union_openid / union_user_account 两个跨应用字段**，代码只读 username（机器人昵称）不涉及——**不用申请任何东西，能正常获取**；SDK 链路 Bot.getInfo()（synchronized 懒加载缓存）→ UserBase.botInfo() → GET /users/@me，失败不缓存（user 字段保持 null）、下一条消息自动重试。

**[11:30-11:40] 修复（common-Bot 一处三端生效）**：GroupMessageHandler.loadBotNicknames 删除 plugin.botName 辅源——昵称匹配唯一源 = /users/@me 返回的 username（官方真实昵称，即 QQ 客户端艾特时显示的名字）；取不到时返回空集（昵称通道静默不影响其他通道）。bot.name 配置键保留（仍作显示用途：集群名回退 ClusterState.effectiveSelf / 消息卡片显示），与艾特识别彻底解耦；三端 config.yml 注释「机器人名称（仅用于显示）」口径不变。isMentionedBot 与 loadBotNicknames KDoc 同步（唯一源说明 + 不读 bot.name 的原因）。

**[11:40-11:50] 官网与 README**：index.html v1.7.5.7 日志块补第五条「艾特识别的机器人昵称改为只从 QQ 官方接口自动获取（基础接口，无需申请任何权限），不再依赖配置 bot.name——它写错、甚至与群里其他机器人重名也不会影响艾特识别（仍仅作显示用途）」；version.json changelog 四条→五条（⑤同义）；tutorial.html bot.name 段「仅用于显示」「不影响功能」口径已正确不动。README 摘要 v1.7.5.7 节补再覆盖轮段 + 详史新增条目（覆盖轮详史保留双源表述作历史事实）。

**[11:50-12:00] 构建验证**：fabric remap 缓存按坑表预删；base 用 1.7.5.6 产物（同号重发坑）。门禁 verify_jar_1757c **185/185 全绿**（在 1757b 181 项基础上：B6b 改唯一源断言 / B6c 反向断言 loadBotNicknames 无 getBotName 引用 / 新增 B6e isMentionedBot 方法体无 getBotName 引用，四 JAR 同断言 + 前轮全量回归）。冒烟 smoke_1757c **24/24 全绿**（独立 JVM 真实类；**官方源注入新方案**：Unsafe.allocateInstance 绕过 Starter 构造器（spt 扫描副作用）+ 反射注 contextManager=FakeContextManager（Starter 无 bot 字段，getBot() 走 contextManager.getContextEntity(Bot.class)）+ Bot 无参构造（仅 new HashMap 无害）+ 反射注 Bot.user 预设官方昵称（getInfo 预设即返不碰 HTTP）+ 反射写 QClient.starter（private set）——X1 bot.name 撞其他机器人昵称（艾特「米娅」机器人）→ false（上轮版必 true，本轮核心场景）/ X2 同场艾特本机器人+撞名机器人 → true / N1 全量群 openid 形态+官方昵称命中 → true（官方源真路径，上轮靠 botName 源）/ N8 官方昵称取不到 → 不回退 bot.name（上轮版必 true）/ R1b botSelfId=starter.bot.id 真路径（上轮冒烟 starter=null 从未跑过）/ 其余 N/R/M 全回归）。md5：Spigot 523b913a/NeoForge c517a6b6/Fabric-1.20.4 616ac17a/Fabric-1.20.1 60a4c5c9。

**[12:00-12:10] 部署提交**：部署 7/7 SAME（四 JAR + 官网两件 + 源码包 854 文件 9.12MB 同号覆盖）；外网复核 24/24（四 JAR 公网 md5 + index 新条目三条断言 + version.json 五条 + tutorial bot.name 仅显示口径 + 源码包唯一源断言「源码无 plugin.botName 读取」）；commit 40e4f79 + 7ba9895 push；中继同步 7ba9895；知识库三件套更新。

- **给用户的口径**：升级只需替换 JAR 重启（无配置变更）；艾特识别现在完全自动——昵称从 QQ 官方接口实时获取，bot.name 随便写都不影响艾特识别（它只管显示名）；官方接口不需要申请任何权限。
"""
t = t[:m2.start()] + new_round
(KB / "更新日志.md").write_text(t, encoding="utf-8")
print("rewritten 更新日志.md")

# ================= 会话上下文.md：最新节替换 =================
ctx = """# 会话上下文(SUZHE014 / KERONGPenguin)

> **维护规则(长期有效)**:
> 1. 每进行一个【大步骤】就更新一次本文件,写上准确日期时间(北京时间,精确到分)。
> 2. 完成一整个任务后,再更新一次(稍微简略写一点,做总结)。
> 3. 本文件由当轮 AI 会话负责维护;新会话动手之前必须先读完知识库所有内容(见 AI交接文档.md)。
> 4. 每轮会话结束删除更早的会话小节,只保留最新一轮(2026-11-13 用户指令:知识库只保留最新的版本;历史会话记录见各轮 git 提交信息)。

---

## 会话记录

### 2026-11-14 11:20 起:1.7.5.7 再覆盖轮(同号重发,昵称匹配去 bot.name 源,已完成上线)

会话来源:用户消息「配置 bot.name 的经常会有人写其他的,所以这个并不严谨。确保官方接口昵称不用申请任何东西能正常获取。如果本次不用修改任何东西,就只回答我的问题就行了,如果要修改则覆盖」(1.7.5.7 覆盖轮已上线,本行为覆盖轮昵称双源方案的严谨性收口)。

**步骤记录(北京时间 2026-11-14)**:
- [11:20-11:30] 核实定性:①bot.name 双源有撞名误判风险(用户乱填撞上群里其他机器人昵称→艾特那个机器人被误判→主轮修复口径复活通道)→需修改;②官方接口 /users/@me 是基础接口(50 QPS,仅需登录 token;需特殊申请的只有 union_openid/union_user_account 两字段,代码只读 username)——不用申请任何东西;SDK 链路 Bot.getInfo(synchronized 懒加载,失败不缓存自动重试)→UserBase.botInfo→GET /users/@me。
- [11:30-11:50] 修复+文档:loadBotNicknames 删 plugin.botName 辅源,唯一源=/users/@me username;bot.name 键保留(显示用途:集群名回退/卡片显示),与艾特识别解耦;KDoc 同步;index.html 日志块补第五条+version.json 五条+tutorial 口径已正确不动;README 摘要+详史再覆盖轮条目。
- [11:50-12:00] 构建验证:fabric remap 缓存预删+base 用 1.7.5.6 产物(坑表)。门禁 verify_jar_1757c 185/185(B6c 反向断言无 getBotName+新增 B6e)。冒烟 smoke_1757c 24/24——官方源注入新方案:Unsafe.allocateInstance 绕过 Starter 构造器+反射注 contextManager=FakeContextManager+Bot.user 预设+反射写 QClient.starter(private set);X1 bot.name 撞名(艾特「米娅」机器人)→false(上轮版必 true);N1 官方昵称真路径命中;N8 官方取不到不回退 bot.name;R1b botSelfId 真路径(上轮从未跑过)。md5:Spigot 523b913a/NF c517a6b6/Fa1204 616ac17a/Fa1201 60a4c5c9。
- [12:00-12:10] 部署:7/7 SAME(四 JAR+官网两件+源码包 854 文件)+外网复核 24/24;commit 40e4f79+7ba9895 push;中继同步 7ba9895;知识库三件套更新。

**任务完成总结(2026-11-14 12:10)**:
1.7.5.7 再覆盖轮闭环上线(同号第三次重发,无配置变更,config-version 48 保持)。①昵称匹配唯一源改为官方接口 /users/@me 的 username(基础接口无需申请任何权限,失败不缓存自动重试);②bot.name 彻底退出艾特识别(键保留仅作显示,乱写/撞名都不再影响);③官网 version.json 五条+index 日志块五条。开发注意存档(已入 AI交接文档坑表):Starter 无 bot 字段(getBot() 走 contextManager.getContextEntity),冒烟注入官方源用 Unsafe.allocateInstance+反射三连;Bot.getId()=getInfo().getId() 存在但方法列表在 javap 输出末尾易漏看;冒烟 JVM 需 --add-opens jdk.unsupported/sun.misc=ALL-UNNAMED。
"""
(KB / "会话上下文.md").write_text(ctx, encoding="utf-8")
print("rewritten 会话上下文.md")

# ================= AI交接文档.md：坑表增补 =================
patch("AI交接文档.md", [(
    "| 同号重发轮 package_jar base 与输出同名互相截断 | 版本号不变时 PENGUIN_BASE_JAR 与输出同为 build/dist/KERONGPenguin_Spigot-同版本.jar:打包边读边写,base 被截断(BadZipFile)且产物损坏;后续用被截断的空 base 打包还会产出 3.5MB 残废 JAR(无第三方依赖) | 同号重发轮用上一版产物作 base(如 1.7.5.6);打包后必查产物尺寸与上一轮同级,异常即弃(1.7.5.7 覆盖轮实测,尺寸对比抓出 NeoForge 残废产物) |",
    """| 同号重发轮 package_jar base 与输出同名互相截断 | 版本号不变时 PENGUIN_BASE_JAR 与输出同为 build/dist/KERONGPenguin_Spigot-同版本.jar:打包边读边写,base 被截断(BadZipFile)且产物损坏;后续用被截断的空 base 打包还会产出 3.5MB 残废 JAR(无第三方依赖) | 同号重发轮用上一版产物作 base(如 1.7.5.6);打包后必查产物尺寸与上一轮同级,异常即弃(1.7.5.7 覆盖轮实测,尺寸对比抓出 NeoForge 残废产物) |
| 冒烟注入官方昵称源(Starter 无 bot 字段) | Starter 类没有 bot 字段——getBot() 实现是 contextManager.getContextEntity(Bot.class);而 Starter 构造器含 spt 组件扫描副作用(StarterObjectApplication),不能 new;Bot.getInfo() 懒加载需 user 字段预设才能不碰 HTTP;QClient.starter 是 Kotlin private set | Unsafe.allocateInstance(Starter.class) 绕过构造器→反射注 contextManager=FakeContextManager(5 方法小接口,仅 Bot.class 返回预设 Bot)→Bot 无参构造(仅 new HashMap 无害)+反射注 user 字段(预设即返)→反射写 QClient.starter 字段;JVM 需 --add-opens jdk.unsupported/sun.misc=ALL-UNNAMED(1.7.5.7 再覆盖轮 smoke_1757c 方案,替代上轮「官方昵称源仅靠门禁字节码断言覆盖」的缺口) |
| Bot.getId() 曾误判不存在 | javap -p Bot 类方法列表输出末尾才是 getId()/getConfig(),rg "getId" 带上下文时只匹配到 Guild.getId 调用行易误判;Bot.getId()=getInfo().getId()(即 /users/@me 的 id 字段) | 反查类方法存在性用 javap 全量方法行(rg "\\(\\)" 或逐行看),别用带 -A/-B 上下文的调用行匹配(1.7.5.7 再覆盖轮实测:getId 在列表末尾) |
| /users/@me 是基础接口无需申请(用户问「官方接口昵称不用申请任何东西能正常获取吗」的权威答案) | QQ 官方机器人 API 的 GET /users/@me(获取机器人详情):50 QPS,仅凭登录 token(与 WSS 同凭据)即可调用;需特殊申请的只有响应里 union_openid/union_user_account 两个跨应用字段;代码只读 username(=QQ 客户端艾特时显示的机器人昵称) | 机器人昵称类需求直接走 Bot.getInfo() SDK 懒加载缓存(失败不缓存、下一条消息自动重试);错误码 11253(应用权限不通过需申请)不适用于此接口(1.7.5.7 再覆盖轮核实并存档) |""",
)])

# ================= 上传三件套 + md5 往返 =================
if ok:
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

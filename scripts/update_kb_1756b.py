#!/usr/bin/env python3
"""1.7.5.6 主线修复轮知识库更新：三件套（只留最新一轮口径）+ 上传。"""
import sys

sys.path.insert(0, "/home/z/my-project/scripts")
from ssh_cmd import run, sftp_upload  # noqa: E402

BASE = "/home/z/my-project/kb-out"
import pathlib
pathlib.Path(BASE).mkdir(exist_ok=True)

# ---------------- 更新日志.md（整文件重写，只留最新一轮） ----------------
UPDATELOG = """# KERONGPenguin 更新日志

> 本文件是项目唯一权威的详细更新日志：每个版本带准确时间（北京时间，精确到分）、完整改动内容与验证结果。
> **只保留最新 1 轮更新**（2026-11-13 用户指令：知识库只保留最新的版本）——更早版本（1.7.5.5 及之前共 100+ 轮）的详细记录已从本文件移除，全量历史见 git 提交历史（服务器中继 /root/gitrelay/KERONGPenguin.git，每条提交信息均带全量改动记录）与官网 /wwwroot/penguin/index.html 更新日志（1.7.0 起要点版）。
> 面向用户的简版更新日志在官网 /wwwroot/penguin/index.html 与 version.json（只保留 1.7.0 起，每版 3-6 条要点，与本文详略口径不同属正常）。
> 时间来源：git 提交历史（GitHub SUZHE014/KERONGPenguin，UTC+8 换算）。


## 当前版本状态(更新于 2026-11-14 02:00)

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
| 备份 | ✅ 单包口径：SUZHEWWO(2026.11.07-4).tar.gz(v4 全量)；/SUZHE014/ 只留这一个包 |

## 本轮详细更新日志(1.7.5.6 主线修复轮；1.7.5.5 及之前全量历史见 git 提交历史)

### 2026-11-14 01:05-01:55:1.7.5.6 主线修复轮(同号重发，A1-A6；A2 经核查已就位)

v4 交接文档待办 A 系列六项（A2 控制台颜色码经核查 1.6.6 的 ConsoleColors 已实现，本轮门禁 F 段复核），版本 1.7.5.6 同号重发（先例：1.7.5.5 AI 覆盖轮），四端同号，config-version 46→47。

**[01:05-01:15] 会话核查**：沙箱全新重建（clone=8070c5c，B 系列经知识库核对已由上一轮完成）；fabric-deps/底座 JAR 从部署服务器拉回（四底座 md5 与知识库记录一致）；工具链 JDK21+Gradle 8.14.5 重建；A1-A6 逐项对代码现状核查定位缺口（A1=升级表只覆盖 5 模板；A4=两布尔无 at 模式；A5=全无；A3=三处输出缺 @；A6=按钮带全区间文案）。

**[01:15-01:30] 代码实施**：①A1 MessageTemplates 重写为「释放记录」机制——Markdown/.release-record.json 记每个模板释放/确认时的默认内容指纹（normalize 后 SHA-256），升级后指纹仍匹配=未自改的旧默认→整体 writeText 替换为当前 JAR 内置默认（完整替换零追加）；三路径=①等于当前默认补指纹②记录命中整体替换③升级表兜底（表降级为无记录老安装的判定，替换内容统一取内置资源）；手改文件指纹不匹配永不动；读写损坏回空表不阻断。②A4 新键 ai.reminder-mode（at/quote/none，中文 艾特/引用/无，大小写不敏感；at=每次回复开头 <@发送人>，quote=引用原消息，none=都不）——空串回退读旧键 ai.reply-quote（true→quote/false→none，双缺省=quote 保持 1.7.5.5 行为），非法取值按 quote+一次性文件日志告警；QqBindManager.aiReminderMode() 新增，GroupMessageHandler 出向接线（at 模式拼 <@发送人>，quote 模式带 message_reference）。③A5 新对象 ButtonInteractionCooldown（新键 bot.button-cooldown-seconds 默认 5 秒/0 关闭/每次点击实时读取）——compute 原子放行键=群|用户；InteractionFastPath.dispatch 与 InterActionListener（仅本插件按钮，新增 isOwnButton）分发前统一门禁，间隔内回应码 2「操作频繁」不执行；/hbkrg reload 清空（三平台 reloadPluginConfig 挂 clearAll）。④A3 三处互动输出补 @：MenuButtons.sendPage 加 userOpenId 参（三参签名）开头 @ 点击者；ServerSelectButtons 选择器消息 @ 触发者（新增 getSender）；UnblacklistButtons 解除结果 @ 操作管理员；dispatchAsClicker 合成命令路径本就带 @ 零改动。⑤A6 官网：Fabric 下载按钮版本简写（1.20.4）/（1.20.1），完整版本要求（Loader 0.15.1/0.16.10 + Fabric API 对应 + 已实测清单 1.20.4/1.20.5/1.20.6/1.21/1.21.4/1.21.6/1.21.11）移入「支持的插件与兼容性」新表格卡片；删除「其他版本」区残留的 1.7.5.5 旧 Fabric 卡片（上一轮升级漏更的残留）；历史表 Fabric 行标注改「1.20.4 通用包/1.20.1 独立包」。⑥config-version 47：三端 config.yml 同步两新键+小白注释；ConfigManager DEFAULT_VALUES+升 47 注释。

**[01:30-01:40] 构建与验证**：gradlew clean 四模块 5m1s 全过；四 JAR 打包（底座=线上 1.7.5.6，NF/Fa 底座=本版 Spigot）。**踩坑**：package_jar_fabric 的 remap 缓存（scripts/deps-work/fabric-libs/package/server-Fabric-intermediary-*.jar）无条件复用，首轮 Fabric 双 JAR 含旧 common-Bot 类（门禁 B/C/D/E 段 48 项失败抓出），删缓存重打修复——**common-Bot 有改动的轮次必须先删该缓存**（与上一轮「删重映射缓存重跑」同源，已入 AI交接文档坑表）。门禁 verify_jar_1756b **182/182 全绿**（A 版本资源+config 47 三键/B 释放记录机制/C aiReminderMode 全链/D 冷却双路径+三平台/E sendPage 三参+getSender+recipe 字节/F ConsoleColors 回归/G 前轮全量回归含 Fa1201-1204 映射与白名单 4 条/H 形态）；冒烟 smoke_1756b **23/23 全绿**（独立 JVM+FakeKeronRuntime，NeoForge JAR 类路径：A1 端到端 8 项——首释 69 模板+记录 69 条/表兜底/非表模板 bind-success 凭记录收敛/自改不动/幂等/BOM 容差；A5 真实时钟 6 项；A4 全链 9 项）。md5：Spigot 360c49ac/NeoForge 32ffa9d6/Fabric-1.20.4 8486dc81/Fabric-1.20.1 4040d325。

**[01:42-01:55] 提交部署**：commit aed9c91（主体）+d15c3c9（部署脚本）；源码包 9.06MB/833 文件重打（HEAD aed9c91）；部署 8/8 SAME（四 JAR+官网三件+源码包，md5 往返+chown www:www+chmod 644）；git push ghfast.top 代理成功（api.github.com 复核 main=d15c3c9）；服务器中继 relay-main 同步 aed9c91。

- **给用户的口径**：本波与 Fa 修复轮同属 1.7.5.6（同号重发）；升级只需替换 JAR 重启，config 自动补两新键（ai.reminder-mode 默认空串=跟随旧 reply-quote 设置，bot.button-cooldown-seconds 默认 5 秒）；旧配置里改过 reply-quote=false 的用户偏好自动保留（空串=未选择）。模板「帮助」等旧文案残留：升级到本版后未自改过的旧默认模板自动整体替换为最新默认，无需再手动删文件。
"""

# ---------------- 会话上下文.md（整文件重写，只留最新一轮） ----------------
SESSION = """# 会话上下文(SUZHE014 / KERONGPenguin)

> **维护规则(长期有效)**:
> 1. 每进行一个【大步骤】就更新一次本文件,写上准确日期时间(北京时间,精确到分)。
> 2. 完成一整个任务后,再更新一次(稍微简略写一点,做总结)。
> 3. 本文件由当轮 AI 会话负责维护;新会话动手之前必须先读完知识库所有内容(见 AI交接文档.md)。
> 4. 每轮会话结束删除更早的会话小节,只保留最新一轮(2026-11-13 用户指令:知识库只保留最新的版本;历史会话记录见各轮 git 提交信息)。

---

## 会话记录

### 2026-11-14 01:05 起:1.7.5.6 主线修复轮(A1-A6 同号重发,已全部完成上线)

会话来源:v4 交接文档待办 A 系列六项(B 系列经核查已由上一轮 1.7.5.6 Fa 修复轮完成,知识库为证)。

**步骤记录(北京时间 2026-11-14)**:
- [01:05-01:15] 沙箱重建:clone 8070c5c;SSH/知识库核对;A1-A6 现状定位(A1=升级表只覆盖 5 模板/A3=三处缺 @/A4=无 at 模式/A5=全无/A6=按钮全区间文案;A2=1.6.6 ConsoleColors 已实现);工具链+fabric-deps+四底座 JAR 拉回。
- [01:15-01:30] 代码:A1 MessageTemplates 释放记录机制(.release-record.json 指纹三路径全量替换);A4 ai.reminder-mode 三选一(空串回退旧键);A5 ButtonInteractionCooldown(5 秒可配可关,双路径门禁);A3 三处 @;config 47 三端同步;A6 官网三件+README。
- [01:30-01:40] 构建:四模块 5m1s;四 JAR 打包。踩坑:Fabric remap 缓存未删→旧类混入(门禁 48 项失败抓出),删缓存重打修复(坑已入 AI交接文档)。
- [01:40] 验证:门禁 verify_jar_1756b 182/182;冒烟 smoke_1756b 23/23(独立 JVM 真文件系统+真实时钟)。
- [01:42-01:55] 提交部署:commit aed9c91+d15c3c9;部署 8/8 SAME(四 JAR+官网三件+源码包);push 成功(api.github.com 复核 main=d15c3c9);中继同步;知识库三件套本文件更新。

**任务完成总结(2026-11-14 02:00)**:
A 系列六项全部闭环上线(1.7.5.6 同号两波)。①A1 模板升级=完整替换(释放记录机制,非表模板也自动收敛,手改永不动);②A2 控制台颜色码确认 1.6.6 已实现(ConsoleColors ANSI 探测+剥离),门禁 F 段复核;③A3 按钮互动输出统一带 @(翻页/选择器/解除结果);④A4 AI 提醒方式三选一(ai.reminder-mode:at/quote/none+中文取值,空串回退旧键);⑤A5 按钮冷却 bot.button-cooldown-seconds 默认 5 秒可改可关;⑥A6 官网 Fabric 按钮简写+兼容性卡片+删残留旧卡片。config-version 47(只加键);验证:门禁 182/182+冒烟 23/23+部署 8/8+外网复核全绿。md5:Spigot 360c49ac/NeoForge 32ffa9d6/Fabric-1.20.4 8486dc81/Fabric-1.20.1 4040d325/源码包 ff3390cb。开发注意存档:①package_jar_fabric 的 remap 缓存(scripts/deps-work/fabric-libs/package/server-Fabric-intermediary-*.jar)无条件复用,common-Bot 有改动的轮次必须先删(本轮已踩,门禁抓出,坑表已录);②同号重发时源码包同名替换(penguin-source/ 仍留最新 3 个);③服务器文件时间戳与北京时间有约 8 小时偏移(部署核验以 md5 为准,勿依赖 ls 时间)。
"""

files = {
    "更新日志.md": UPDATELOG,
    "会话上下文.md": SESSION,
}

# AI交接文档.md：定向补丁（在服务器现文件基础上改，不整文件重写）
def patch_handover(current: str) -> str:
    # 1) 坑表新增一行（插在「历史坑速查表」表格最后一行后）
    pit_row = "| package_jar_fabric remap 缓存无条件复用 | common-Bot 有改动但 scripts/deps-work/fabric-libs/package/server-Fabric-intermediary-*.jar 未删 → Fabric 双 JAR 混入旧 common-Bot 类(体积正常,门禁 B/C/D/E 段批量失败抓出) | common-Bot 有改动的轮次先删该两个缓存 jar 再跑 package_jar_fabric(1.7.5.6 主线修复轮实测) |\n"
    anchor = "| 死代码判定 | 反射入口（@Commands/@SubscribeEvent）不算死 | 查三模块+脚本+资源+反射再删 |\n"
    assert current.count(anchor) == 1
    current = current.replace(anchor, anchor + pit_row)

    # 2) §6.2 配置系统版本口径 46 → 47
    old62 = "### 6.2 配置系统（config-version 34）"
    if old62 in current:
        current = current.replace(old62, "### 6.2 配置系统（config-version 47）", 1)
    old_note = "语法错误三级自愈（ConfigBrokenRecovery）。"
    new_note = ("语法错误三级自愈（ConfigBrokenRecovery）。"
                "**47 两新键（1.7.5.6 主线修复轮）**：ai.reminder-mode（AI 回复提醒方式 at/quote/none，"
                "默认空串=未选择——空串回退读旧键 ai.reply-quote，双缺省=quote；"
                "空串默认值保证升级用户的旧 reply-quote 偏好不被覆盖）、"
                "bot.button-cooldown-seconds（按钮互动冷却秒数，默认 5，0 关闭，实时读取）。")
    assert current.count(old_note) == 1
    current = current.replace(old_note, new_note, 1)

    # 3) §5 消息模板行补释放记录口径
    old_tpl = "- **消息模板**：`spigot/qqbind/MessageTemplates.kt` + 资源 `Markdown/*.md`（双端两份资源副本，**改模板双份都要改**）；文本回复统一 sendDirect（带@）/sendPlain，出口应用经济别名 EconomyName。"
    assert current.count(old_tpl) == 1
    current = current.replace(old_tpl, old_tpl + "\n- **模板升级（1.7.5.6 释放记录机制）**：每个模板释放/确认为默认时把内容指纹记入数据目录 `Markdown/.release-record.json`；升级后磁盘内容仍与记录指纹一致 = 未自改的旧默认 → **整体替换**为当前 JAR 内置默认（完整替换零追加）；`DEFAULT_CONTENT_UPGRADES` 表降级为无记录老安装的兜底判定；手改文件指纹不匹配永不动。", 1)
    return current


def main():
    # 写出两个整文件
    for name, content in files.items():
        p = pathlib.Path(BASE) / name
        p.write_text(content, encoding="utf-8")
        print("composed", p)
    # AI交接文档.md 拉现文件打补丁
    code, cur, err = run("cat '/SUZHE014/知识库/AI交接文档.md'")
    if code != 0:
        raise SystemExit(f"读取 AI交接文档 失败: {err[:200]}")
    patched = patch_handover(cur)
    p = pathlib.Path(BASE) / "AI交接文档.md"
    p.write_text(patched, encoding="utf-8")
    print("composed", p)

    # 上传三件套
    for name in ["更新日志.md", "会话上下文.md", "AI交接文档.md"]:
        local = str(pathlib.Path(BASE) / name)
        remote = f"/SUZHE014/知识库/{name}"
        sftp_upload(local, remote)
        code, out, _ = run(f"md5sum '{remote}'" + " && md5sum " + f"'{local}'", 30)
        print("uploaded", remote)
    code, out, _ = run("ls -la /SUZHE014/知识库/ && wc -l /SUZHE014/知识库/*", 30)
    print(out)
    return 0


if __name__ == "__main__":
    sys.exit(main())

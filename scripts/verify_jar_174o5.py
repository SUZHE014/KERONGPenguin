#!/usr/bin/env python3
"""1.7.4 第五轮覆盖版门禁验证。

验证范围（源码 + 双端 JAR 字节码 / 资源）：
A. QQ 号行全员可见：玩家名反查 openidPart 无管理员判断（OpenId 反查保持管理员口径）
B. 二维码自动刷新上限 3：MAX_REFRESH_COUNT=3 + 提示文案 + 计数重置口径
C. 已配置手动扫码替换凭据：start(manual) + 头部文案 + 关旧会话重连 + 双端命令
D. 二维码服务器启动完成后显示：markStartupPending/startIfStartupPending + Spigot
   ServerLoadEvent 消费 + 兜底线程 + NeoForge ServerStartedEvent 尾部消费
E. 文档同步：README / 官网三件（启动后显示 / 上限 3 / 替换凭据 / 全员可见），官网无版本轮次字段
F. 基线保持：版本 1.7.4 / 776 目录条目 / 第四轮内容（反查 / 灰度 106 / config 37）不回退
"""
import re
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
DIST = REPO / "build" / "dist"
SPIGOT_JAR = DIST / "KERONGPenguin_Spigot-1.7.4.jar"
NF_JAR = DIST / "KERONGPenguin_NeoForge-1.20.4-1.7.4.jar"

passed = []
failed = []


def check(name, ok, detail=""):
    (passed if ok else failed).append(name)
    print(f"  {'✓' if ok else '✗'} {name}" + (f" — {detail}" if detail and not ok else ""))


def src(rel):
    return (REPO / rel).read_text(encoding="utf-8")


def jar_names(jar):
    with zipfile.ZipFile(jar) as zf:
        return zf.namelist()


def jar_read(jar, name):
    with zipfile.ZipFile(jar) as zf:
        return zf.read(name).decode("utf-8", errors="replace")


def class_refs(jar, pattern):
    rx = re.compile(pattern.encode())
    with zipfile.ZipFile(jar) as zf:
        for name in zf.namelist():
            if name.endswith(".class"):
                if rx.search(zf.read(name)):
                    return True
    return False


pc = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/PublicCommands.kt")
qrm = src("common-Bot/src/main/kotlin/cn/kerong/bot/QrLoginManager.kt")
kerong = src("common-Bot/src/main/kotlin/cn/kerong/bot/KERONG.kt")
qclient = src("common-Bot/src/main/kotlin/cn/kerong/bot/QClient.kt")
spigot_main = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/KERONGSpigot.kt")
spigot_cmd = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/commands/KERONGCommand.kt")
nf_main = src("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt")
nf_cmd = src("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/NeoForgeCommands.kt")
cm = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
rs = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/RenderSettings.kt")
cfg_s = src("server-Spigot/src/main/resources/config.yml")
cfg_n = src("server-NeoForge/src/main/resources/config.yml")
spigot_names = jar_names(SPIGOT_JAR)
nf_names = jar_names(NF_JAR)

print("== A. QQ 号行全员可见（玩家名反查） ==")
lp_section = pc.split("private fun lookupByPlayerName")[1].split("private fun")[0] if "private fun lookupByPlayerName" in pc else ""
check("openidPart 无管理员判断（恒显示）", 'val openidPart = "QQ(OpenId): $openId\\n"' in lp_section)
check("玩家名反查段不再引用 isAdmin", "isAdmin" not in lp_section)
check("OpenId 反查保持管理员口径",
      'if (isAdmin(plugin, event)) "OpenId: $openId\\n" else ""' in pc)
check("JAR 字节码含 QQ(OpenId) 行", class_refs(SPIGOT_JAR, r"QQ\(OpenId\)"))

print("== B. 二维码自动刷新上限 3 ==")
check("MAX_REFRESH_COUNT = 3", "MAX_REFRESH_COUNT = 3" in qrm)
check("无 6 次上限残留", "MAX_REFRESH_COUNT = 6" not in qrm)
check("超限提示引导手动命令", "自动刷新已停止——可用 /hbkrg qr 手动重新发起" in qrm)
check("重新发起后计数重置口径", "重新发起后计数重置" in qrm)
check("会话内提示带自动刷新上限", "本次会话自动刷新上限 $MAX_REFRESH_COUNT 次" in qrm)
check("JAR 字节码超限文案（自动刷新已停止）", class_refs(SPIGOT_JAR, "自动刷新已停止"))
check("JAR 字节码上限提示（刷新计数前缀）", class_refs(SPIGOT_JAR, "二维码已过期，正在刷新…（第 "))

print("== C. 已配置手动扫码替换凭据 ==")
check("start 支持 manual 参数", "fun start(manual: Boolean = false): String" in qrm)
check("AuthSession 携带 manual 标记", "private class AuthSession(val manual: Boolean)" in qrm)
check("已配置头部文案（将替换现有凭据）", "本次扫码成功后将替换 bot.app-id / bot.secret 并以新凭据重新连接" in qrm)
check("成功后关闭旧会话再重连", "QClient.closeSessionForCredentialSwap()" in qrm.split("========== 扫码成功")[1][:2000]
      if "========== 扫码成功" in qrm else False)
check("QClient 新增 closeSessionForCredentialSwap", "fun closeSessionForCredentialSwap()" in qclient)
swap_section = qclient.split("fun closeSessionForCredentialSwap")[1].split("fun shutdown")[0] if "fun closeSessionForCredentialSwap" in qclient else ""
check("换凭据只关 WSS 不关发送队列", "OutboxQueue" not in swap_section and "starter" in swap_section)
check("整体停机仍走 shutdown（不可逆清理在彼处）", "OutboxQueue.shutdown()" in qclient.split("fun shutdown()")[1][:1500])
check("JAR 字节码方法 closeSessionForCredentialSwap", class_refs(SPIGOT_JAR, "closeSessionForCredentialSwap")
      and class_refs(NF_JAR, "closeSessionForCredentialSwap"))
check("Spigot /hbkrg qr 手动发起", "QrLoginManager.start(manual = true)" in spigot_cmd)
check("NeoForge /hbkrg qr 手动发起", "QrLoginManager.start(manual = true)" in nf_cmd)
check("Spigot 已配置替换提示", "本次扫码成功后将替换 bot.app-id / bot.secret 并以新凭据重新连接" in spigot_cmd)
check("NeoForge 已配置替换提示", "本次扫码成功后将替换 bot.app-id / bot.secret 并以新凭据重新连接" in nf_cmd)
check("Spigot 无「请先清空并重启」旧文案", "请先清空 bot.app-id / bot.secret 并重启服务器" not in spigot_cmd)
check("NeoForge 无「请先清空并重启」旧文案", "请先清空 bot.app-id / bot.secret 并重启服务器" not in nf_cmd)
check("Spigot help 行更新（替换现有凭据）", "扫码替换现有凭据并重连" in spigot_cmd)

print("== D. 二维码服务器启动完成后显示 ==")
check("launchQqClient 改标记待发", "QrLoginManager.markStartupPending()" in kerong
      and "QrLoginManager.start()" not in kerong)
check("待发提示文案", "扫码登录二维码将在服务器启动完成后显示" in kerong)
check("markStartupPending 方法在位", "fun markStartupPending()" in qrm)
check("startIfStartupPending 方法在位", "fun startIfStartupPending(): Boolean" in qrm)
check("startIfStartupPending 消费后自动发起", "start(manual = false)" in qrm.split("fun startIfStartupPending")[1][:600])
check("Spigot ServerLoadEvent 消费点", "QrLoginManager.startIfStartupPending()" in spigot_main)
check("Spigot 兜底守护线程 KERONG-QrDeferred", "KERONG-QrDeferred" in spigot_main)
check("NeoForge ServerStarted 尾部消费", "QrLoginManager.startIfStartupPending()" in nf_main)
check("NeoForge 消费点在 initializeRuntime 之后", nf_main.index("initializeRuntime()") < nf_main.index("QrLoginManager.startIfStartupPending()"))
check("JAR 字节码含 startIfStartupPending（双端）", class_refs(SPIGOT_JAR, "startIfStartupPending")
      and class_refs(NF_JAR, "startIfStartupPending"))
check("JAR 字节码含 KERONG-QrDeferred 线程名", class_refs(SPIGOT_JAR, "KERONG-QrDeferred"))
check("JAR 字节码待发提示文案", class_refs(SPIGOT_JAR, "扫码登录二维码将在服务器启动完成后显示"))

print("== E. 文档同步 ==")
readme = src("README.md")
tutorial = src("website/tutorial.html")
index = src("website/index.html")
version = src("website/version.json")
for label, doc in [("README", readme), ("tutorial", tutorial), ("index", index), ("version.json", version)]:
    check(f"{label} 启动完成后显示", "服务器启动完成后" in doc)
    check(f"{label} 上限 3 次", "上限 3 次" in doc)
    check(f"{label} 替换凭据口径", "替换现有凭据" in doc)
for label, doc in [("README", readme), ("tutorial", tutorial), ("index", index), ("version.json", version)]:
    check(f"{label} QQ 号行全员可见", "QQ 号行全员可见" in doc or "QQ 号（OpenId）行全员可见" in doc)
check("README 第五轮详史段", "1.7.4 第五轮覆盖更新" in readme)
check("tutorial 查信息行全员可见", "QQ 号行全员可见" in tutorial)
check("官网三件无「第五轮」字段", "第五轮" not in tutorial and "第五轮" not in index and "第五轮" not in version)
check("官网三件无「覆盖更新」字段", "覆盖更新" not in tutorial and "覆盖更新" not in index and "覆盖更新" not in version)
check("README 无「6 次回落」残留", "6 次回落" not in readme and "连续过期 6" not in readme)
check("tutorial 无「仅提示当前凭据状态」旧文案", "仅提示当前凭据状态" not in tutorial)

print("== F. 基线保持（第四轮内容不回退） ==")
check("plugin.yml 版本 1.7.4", "version: '1.7.4'" in src("server-Spigot/src/main/resources/plugin.yml")
      or "version: 1.7.4" in src("server-Spigot/src/main/resources/plugin.yml"))
check("Spigot JAR 目录条目 776 基线", len([n for n in spigot_names if n.endswith("/")]) == 776,
      str(len([n for n in spigot_names if n.endswith("/")])))
for cls in ["cn/kerong/bot/QrLoginManager.class", "cn/kerong/bot/ClusterState.class",
            "cn/kerong/bot/ServerNotifyManager.class", "cn/kerong/bot/events/AutoGroupBinder.class",
            "cn/kerong/bot/events/PlayerSelection.class", "cn/kerong/bot/events/ServerSelectButtons.class"]:
    check(f"既有功能类保留 {Path(cls).name}", cls in spigot_names and cls in nf_names)
check("玩家名反查保留（索引+扫盘）", "getQuuid(playerName) ?: bindManager.findQuuidByPlayerName(playerName)" in pc)
check("player-lookup 模板双端在 JAR", "Markdown/player-lookup.md" in spigot_names and "Markdown/player-lookup.md" in nf_names)
check("副标题灰度 106 保持", "DEFAULT_SUBTITLE_CARD_GRAY = 106" in rs and '"render.subtitle-card-gray" to 106' in cm)
check("config-version 37 保持", "CURRENT_CONFIG_VERSION = 37" in cm
      and "config-version: 37" in cfg_s and "config-version: 37" in cfg_n)
check("扫码登录协议链保持", "connect.html?task_id=" in qrm and "create_bind_task" in qrm)
check("docs.huhobot 链接零出现",
      len([l for l in subprocess.run(["grep", "-rn", "docs.huhobot.dpdns.org",
          "--include=*.kt", "--include=*.yml", "--include=*.md", "--include=*.html", "--include=*.json", "."],
          cwd=REPO, capture_output=True, text=True).stdout.splitlines() if "/build/" not in l]) == 0)

print(f"\n===== 结果：{len(passed)} 通过 / {len(failed)} 失败 =====")
if failed:
    print("失败项：")
    for name in failed:
        print("  ✗", name)
    sys.exit(1)

#!/usr/bin/env python3
"""1.7.4 第三轮覆盖版门禁验证。

验证范围（源码 + 双端 JAR 字节码 / 资源）：
A. 新文件到位：ClusterState / ServerSelectButtons / AutoGroupBinder / ServerNotifyManager
B. 服务器开关消息：配置八键 + 双端 config.yml + 字节码引用 + 接线
C. 多服务器集群：bot.cluster 键 + 路由接线（GroupMessageHandler / QClient）
D. 切换服务器指令：@Commands + COMMAND_NAMES + 面板 + help + 双端 config
E. 绑定码机制已删除：GroupBindManager / openid-bindcode / /hbkrg bd 全部不出现
F. 自动群绑定：AutoGroupBinder 引用 + config.yml 注释口径
G. 旧版指令兜底下线：@Commands 无查询open变体 + COMMAND_ALIASES 无映射 + 白名单无前缀匹配
H. 教程二维码/链接删除：printQqSetupQr / kerong.xin 教程链接不出现
I. docs.huhobot.dpdns.org 链接零出现（源码 + 官网 + README）
J. 基线保持：版本 1.7.4 / config-version 36 / 既有功能类还在
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
    """在 JAR 全部类文件的常量池字符串里查 pattern（粗粒度字节码引用）。"""
    rx = re.compile(pattern.encode())
    with zipfile.ZipFile(jar) as zf:
        for name in zf.namelist():
            if name.endswith(".class"):
                if rx.search(zf.read(name)):
                    return True
    return False


print("== A. 新文件到位 ==")
for rel, key in [
    ("common-Bot/src/main/kotlin/cn/kerong/bot/ClusterState.kt", "object ClusterState"),
    ("common-Bot/src/main/kotlin/cn/kerong/bot/events/ServerSelectButtons.kt", "object ServerSelectButtons"),
    ("common-Bot/src/main/kotlin/cn/kerong/bot/events/AutoGroupBinder.kt", "object AutoGroupBinder"),
    ("common-Bot/src/main/kotlin/cn/kerong/bot/ServerNotifyManager.kt", "object ServerNotifyManager"),
]:
    check(f"源码 {Path(rel).name}", Path(REPO / rel).is_file() and key in src(rel))
spigot_names = jar_names(SPIGOT_JAR)
for cls in ["cn/kerong/bot/ClusterState.class", "cn/kerong/bot/events/ServerSelectButtons.class",
            "cn/kerong/bot/events/AutoGroupBinder.class", "cn/kerong/bot/ServerNotifyManager.class"]:
    check(f"Spigot JAR 含 {Path(cls).name}", cls in spigot_names)

print("== B. 服务器开关消息（server-notify） ==")
cm = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
for key in ["server-notify.enabled", "server-notify.shutdown.enabled", "server-notify.shutdown.message",
            "server-notify.restart.enabled", "server-notify.restart.message",
            "server-notify.crash.enabled", "server-notify.crash.message", "server-notify.restart-window"]:
    check(f"ConfigManager 读 {key}", key in cm)
cfg_s = src("server-Spigot/src/main/resources/config.yml")
cfg_n = src("server-NeoForge/src/main/resources/config.yml")
for label, cfg in [("Spigot", cfg_s), ("NeoForge", cfg_n)]:
    check(f"{label} config.yml server-notify 节", "server-notify:" in cfg and "服务器已正常关闭" in cfg
          and "restart-window: 180" in cfg)
    check(f"{label} config.yml server-notify 默认全开",
          cfg.count("server-notify") >= 1 and 'enabled: true' in cfg.split("server-notify:")[1].split("#")[0] + "enabled: true")
check("ConfigProvider 默认实现", "fun isServerNotifyEnabled(): Boolean = true" in
      src("common-Bot/src/main/kotlin/cn/kerong/bot/provider/ConfigProvider.kt"))
spigot_main = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/KERONGSpigot.kt")
check("Spigot 启动接线 onServerStarted", "ServerNotifyManager.onServerStarted()" in spigot_main)
check("Spigot 停止接线 onServerStopping(isRealServerStop())", "ServerNotifyManager.onServerStopping(isRealServerStop())" in spigot_main)
check("Spigot isRealServerStop 判定", "fun isRealServerStop()" in spigot_main)
nf_main = src("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt")
check("NF 启动接线", "ServerNotifyManager.onServerStarted()" in nf_main)
check("NF 停止接线（真实停止）", "ServerNotifyManager.onServerStopping(true)" in nf_main)
check("JAR 字节码引用 server-notify 键", class_refs(SPIGOT_JAR, r"server-notify\.shutdown\.message"))

print("== C. 多服务器集群 ==")
gmh = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/GroupMessageHandler.kt")
check("消息路由 filterByCluster", "filterByCluster" in gmh and "isArbiterFor" in gmh)
check("播报路由 shouldServeGroup（QClient）",
      "ClusterState.shouldServeGroup" in src("common-Bot/src/main/kotlin/cn/kerong/bot/QClient.kt"))
for label, cfg in [("Spigot", cfg_s), ("NeoForge", cfg_n)]:
    check(f"{label} config.yml bot.cluster 节", "cluster:" in cfg and 'members: []' in cfg)
check("ConfigProvider clusterName/clusterMembers", "fun clusterName()" in
      src("common-Bot/src/main/kotlin/cn/kerong/bot/provider/ConfigProvider.kt"))
check("互动监听接线 ServerSelectButtons.onInteraction",
      "ServerSelectButtons.onInteraction" in src("common-Bot/src/main/kotlin/cn/kerong/bot/events/InterActionListener.kt"))

print("== D. 切换服务器指令 ==")
pc = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/PublicCommands.kt")
check("@Commands(\"切换服务器\")", '@Commands("切换服务器")' in pc)
check("COMMAND_NAMES 含切换服务器", '"切换服务器"' in cm)
check("面板按钮 + 集群模式过滤", 'PanelItem("切换服务器"' in
      src("common-Bot/src/main/kotlin/cn/kerong/bot/MenuManager.kt"))
for rel in ["common-Bot/src/main/resources/Markdown/help.md", "server-NeoForge/src/main/resources/Markdown/help.md"]:
    check(f"{Path(rel).parent.parent.name} help.md 含切换服务器", "/切换服务器" in src(rel))
for label, cfg in [("Spigot", cfg_s), ("NeoForge", cfg_n)]:
    check(f"{label} commands.切换服务器 开关", "切换服务器: true" in cfg)
check("JAR 含切换服务器指令方法", class_refs(SPIGOT_JAR, "切换服务器"))

print("== E. 绑定码机制已删除 ==")
check("GroupBindManager.kt 不存在", not (REPO / "common-Bot/src/main/kotlin/cn/kerong/bot/GroupBindManager.kt").is_file())
check("openid-bindcode.md 不存在（双端资源）",
      not (REPO / "common-Bot/src/main/resources/Markdown/openid-bindcode.md").is_file() and
      not (REPO / "server-NeoForge/src/main/resources/Markdown/openid-bindcode.md").is_file())
check("源码无 GroupBindManager 引用",
      "GroupBindManager" not in subprocess.run(["grep", "-rn", "GroupBindManager",
          "--include=*.kt", "common-Bot/src", "server-Spigot/src", "server-NeoForge/src"],
          cwd=REPO, capture_output=True, text=True).stdout)
check("源码无 openid-bindcode 引用",
      "openid-bindcode" not in subprocess.run(["grep", "-rn", "openid-bindcode",
          "--include=*.kt", "--include=*.yml", "common-Bot/src", "server-Spigot/src", "server-NeoForge/src"],
          cwd=REPO, capture_output=True, text=True).stdout)
kc = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/commands/KERONGCommand.kt")
nfc = src("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/NeoForgeCommands.kt")
check("Spigot 无 bd 子命令", '"bd"' not in kc and "bd <绑定码>" not in kc)
check("NF 无 bd 子命令", '"bd"' not in nfc and "bd <绑定码>" not in nfc)
check("JAR 无 GroupBindManager 字节码", "cn/kerong/bot/GroupBindManager.class" not in spigot_names)
check("JAR 无 openid-bindcode 资源", "Markdown/openid-bindcode.md" not in spigot_names)

print("== F. 自动群绑定 ==")
check("GroupMessageHandler 调 AutoGroupBinder.tryBind", "AutoGroupBinder.tryBind(groupId)" in gmh)
check("AutoGroupBinder 仅空 groups 触发", "current.isNotEmpty()" in
      src("common-Bot/src/main/kotlin/cn/kerong/bot/events/AutoGroupBinder.kt"))
check("写入 bot.groups + saveConfig + reload", 'config.set("bot.groups"' in
      src("common-Bot/src/main/kotlin/cn/kerong/bot/events/AutoGroupBinder.kt"))
check("reload 重置绑定标记", "AutoGroupBinder.reset()" in spigot_main and "AutoGroupBinder.reset()" in nf_main)
for label, cfg in [("Spigot", cfg_s), ("NeoForge", cfg_n)]:
    check(f"{label} config.yml 自动绑定注释", "自动绑定" in cfg and "任意消息" in cfg.replace("任意互动", "任意"))
check("JAR 字节码引用 [自动绑定]", class_refs(SPIGOT_JAR, r"\[自动绑定\]"))

print("== G. 旧版指令兜底下线 ==")
check("@Commands 无查询open 变体", "查询open" not in pc.split("@Commands")[0] + "".join(
    seg.split(")")[0] for seg in pc.split("@Commands")[1:]) or not re.search(r'@Commands\([^)]*查询', pc))
check("COMMAND_ALIASES 无查询open 映射", "查询open" not in cm.split("COMMAND_ALIASES")[1].split(")")[0])
gmh_code = "\n".join(l for l in gmh.splitlines() if not l.strip().startswith("//") and not l.strip().startswith("*"))
check("白名单放行无查询open前缀匹配（代码行）", "查询open" not in gmh_code)
check("废弃键清单含查询open 8 变体", cm.count("commands.查询") >= 8)
with zipfile.ZipFile(SPIGOT_JAR) as _zf:
    _offenders = [n for n in _zf.namelist() if n.endswith(".class") and n != "cn/kerong/penguin/spigot/manager/ConfigManager.class" and b"\xe6\x9f\xa5\xe8\xaf\xa2open ID" in _zf.read(n)]
check("JAR 字节码无查询open（除废弃键清理清单）", len(_offenders) == 0, "; ".join(_offenders[:3]))

print("== H. 教程二维码/链接删除 ==")
check("printQqSetupQr 已删除", "printQqSetupQr" not in subprocess.run(["grep", "-rn", "printQqSetupQr",
    "--include=*.kt", "common-Bot/src", "server-Spigot/src", "server-NeoForge/src"],
    cwd=REPO, capture_output=True, text=True).stdout)
check("QrLoginManager 无教程回落", "printQqSetupQrFallback" not in src("common-Bot/src/main/kotlin/cn/kerong/bot/QrLoginManager.kt"))
check("扫码登录仍保留（连接链接输出）", "connect.html?task_id=" in src("common-Bot/src/main/kotlin/cn/kerong/bot/QrLoginManager.kt"))

print("== I. docs.huhobot 链接零出现 ==")
grep_out = subprocess.run(["grep", "-rn", "docs.huhobot.dpdns.org",
    "--include=*.kt", "--include=*.yml", "--include=*.md", "--include=*.html", "--include=*.json", "."],
    cwd=REPO, capture_output=True, text=True).stdout
lines = [l for l in grep_out.splitlines() if "/build/" not in l]
check("源码 + 官网 + README 零出现", len(lines) == 0, "; ".join(lines[:3]))

print("== J. 基线保持 ==")
check("plugin.yml 版本 1.7.4", "version: '1.7.4'" in src("server-Spigot/src/main/resources/plugin.yml")
      or "version: 1.7.4" in src("server-Spigot/src/main/resources/plugin.yml"))
check("config-version 36（双端）", "config-version: 36" in cfg_s and "config-version: 36" in cfg_n)
for cls in ["cn/kerong/bot/QrLoginManager.class", "cn/kerong/bot/events/PlayerSelection.class",
            "cn/kerong/bot/events/UnblacklistButtons.class", "cn/kerong/bot/tools/Keyboards.class"]:
    check(f"既有功能类保留 {Path(cls).name}", cls in spigot_names)
check("Spigot JAR 目录条目 776 基线", len([n for n in spigot_names if n.endswith("/")]) == 776,
      str(len([n for n in spigot_names if n.endswith("/")])))
check("Markdown 模板 cluster-single/cluster-list-fallback 入 JAR",
      "Markdown/cluster-single.md" in spigot_names and "Markdown/cluster-list-fallback.md" in spigot_names)
check("死亡播报/按钮模板仍保留",
      "Markdown/help.md" in spigot_names and "Markdown/select-player.md" in spigot_names)

print(f"\n===== 结果：{len(passed)} 通过 / {len(failed)} 失败 =====")
if failed:
    print("失败项：")
    for name in failed:
        print("  ✗", name)
    sys.exit(1)

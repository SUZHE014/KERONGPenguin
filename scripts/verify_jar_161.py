#!/usr/bin/env python3
"""
KERONGPenguin 1.6.1 门禁验证：Spigot + NeoForge 双 JAR。
本轮改动点逐项断言：
  1. 图片发送误报修复（QClient：isReadTimeout / stopForConflict / ret 码 20220210 /
     超时不再提示失败文案）；
  2. 同 AppID 双服互踢检测（WssDiagnostics：短命会话计数常量 / 冲突判定 / 冲突停机）；
  3. 控制台时间戳移除（QqBindManager.logQuiet 不再把时间拼进控制台行）；
  4. 控制台输出全量落日志（双适配器 log_info 双写 + PluginFileLog 颜色码剥离 +
     GameChat 三处彩色提示入档）；
  5. 面板按钮描述应用经济别名（MenuManager 引用 EconomyName）；
  6. 配置注释：头部无 /huhobot reload 指令字段；messages 段变量总表存在；
  7. 版本 1.6.1；模板资源 67 个不缺。
"""
import re
import zipfile

SPIGOT = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_Spigot-1.6.1.jar"
NEOFORGE = "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_NeoForge-1.21.1-1.6.1.jar"

passed = 0
failed = []


def ok(name: str) -> None:
    global passed
    passed += 1
    print(f"  ✓ {name}")


def bad(name: str) -> None:
    failed.append(name)
    print(f"  ✗ {name}")


def read_class(zf: zipfile.ZipFile, entry: str) -> bytes:
    return zf.read(entry)


def utf8_consts(data: bytes) -> str:
    """提取 class 常量池中的 UTF-8 字符串（近似：连续可打印段）。"""
    text = data.decode("utf-8", errors="ignore")
    return text


def check_all(zf: zipfile.ZipFile, entry: str, needles: list, name: str) -> None:
    try:
        data = zf.read(entry)
    except KeyError:
        bad(f"{name}（缺少 {entry}）")
        return
    text = utf8_consts(data)
    missing = [n for n in needles if n not in text]
    if missing:
        bad(f"{name}（缺 {missing}）")
    else:
        ok(name)


def check_config(zf: zipfile.ZipFile, entry: str, name: str) -> None:
    try:
        text = zf.read(entry).decode("utf-8")
    except KeyError:
        bad(f"{name}（缺少 {entry}）")
        return
    problems = []
    if "/huhobot reload" in text:
        problems.append("头部仍含 /huhobot reload 指令字段")
    if "{playersSection}" not in text or "连续签到天数" not in text:
        problems.append("messages 段变量总表缺失")
    if "config-version: 17" not in text:
        problems.append("config-version != 17")
    if problems:
        bad(f"{name}（{problems}）")
    else:
        ok(name)


print("================================================================")
print("Spigot JAR 1.6.1 验证")
print("================================================================")
with zipfile.ZipFile(SPIGOT) as z:
    names = z.namelist()
    if "plugin.yml" in names and "version: '1.6.1'" in z.read("plugin.yml").decode("utf-8"):
        ok("plugin.yml 版本 1.6.1")
    else:
        bad("plugin.yml 版本 1.6.1")

    q = "cn/huohuas001/bot/QClient.class"
    check_all(z, q, [
        "isReadTimeout",
        "stopForConflict",
        "qq-conflict-stop",
        "大概率已投递",
        "不再按失败处理",
        "被 QQ 拒收",
        "等待 QQ 应答超时",
    ], "QClient：图片误报修复 + 冲突停机")

    w = "cn/huohuas001/bot/WssDiagnostics.class"
    check_all(z, w, [
        "trackShortSession",
        "conflictStopped",
        "shortSessionCount",
        "抢占",
        "互踢",
    ], "WssDiagnostics：短命会话互踢检测")

    qm = "cn/huohuas001/huhobotPenguin/spigot/qqbind/QqBindManager.class"
    data = z.read(qm)
    # logQuiet 不再拼接时间戳进控制台行：检查常量里控制台前缀仍在，
    # 且方法字节码不含 "yyyy-MM-dd HH:mm:ss"（文件行格式串只应出现在
    # 同类中一次——logVerbose；logQuiet 不再用）
    text = utf8_consts(data)
    if "yyyy-MM-dd HH:mm:ss" in text:
        # 类中仍有时间格式串（logVerbose 用），用 javap 级别校验太重，
        # 改为检查 logQuiet 相关常量组合不存在 "[$timestamp]"
        bad("QqBindManager 仍含时间格式串（需人工核对 logQuiet）")
    else:
        ok("QqBindManager：时间格式串清零")

    pf = "cn/huohuas001/bot/tools/PluginFileLog.class"
    check_all(z, pf, ["COLOR_CODE"], "PluginFileLog：颜色码剥离字段")

    mm = "cn/huohuas001/bot/MenuManager.class"
    data = z.read(mm)
    text = utf8_consts(data)
    if "EconomyName" in text or "economy-alias" in text or "qqbind/EconomyName" in data.decode("utf-8", errors="ignore"):
        ok("MenuManager：描述应用经济别名（EconomyName 引用）")
    else:
        bad("MenuManager：未引用 EconomyName")

    # 适配器 log_info 双写（引用 PluginFileLog）
    hs = "cn/huohuas001/huhobotPenguin/spigot/HuHoBotSpigot.class"
    check_all(z, hs, ["PluginFileLog"], "HuHoBotSpigot：log_info 引用 PluginFileLog（双写）")

    gc = "cn/huohuas001/huhobotPenguin/spigot/events/GameChat.class"
    check_all(z, gc, ["PluginFileLog", "Economy 就绪"], "GameChat：经济检测提示入档")

    check_config(z, "config.yml", "config.yml：无指令字段 + 变量总表 + 版本 17")

    md = [n for n in names if n.startswith("Markdown/") and n.endswith(".md")]
    if len(md) >= 67:
        ok(f"消息模板资源 {len(md)} 个（≥67）")
    else:
        bad(f"消息模板资源仅 {len(md)} 个")

print("================================================================")
print("NeoForge JAR 1.6.1 验证")
print("================================================================")
with zipfile.ZipFile(NEOFORGE) as z:
    names = z.namelist()
    if "META-INF/neoforge.mods.toml" in names and 'version = "1.6.1"' in z.read("META-INF/neoforge.mods.toml").decode("utf-8"):
        ok("mods.toml 版本 1.6.1")
    else:
        bad("mods.toml 版本 1.6.1")

    check_all(z, "cn/huohuas001/bot/QClient.class", [
        "isReadTimeout", "stopForConflict", "大概率已投递",
    ], "QClient（模组侧同源）：误报修复")
    check_all(z, "cn/huohuas001/bot/WssDiagnostics.class", [
        "trackShortSession", "conflictStopped",
    ], "WssDiagnostics（模组侧同源）：互踢检测")
    check_all(z, "cn/huohuas001/huhobotPenguin/neoforge/HuHoBotNeoForge.class", [
        "PluginFileLog", "1.6.1",
    ], "HuHoBotNeoForge：log_info 双写 + MOD_VERSION 1.6.1")
    check_config(z, "config.yml", "config.yml（模组版）：无指令字段 + 变量总表")
    md = [n for n in names if n.startswith("Markdown/") and n.endswith(".md")]
    if len(md) >= 67:
        ok(f"消息模板资源 {len(md)} 个（≥67）")
    else:
        bad(f"消息模板资源仅 {len(md)} 个")

print("================================================================")
print(f"结果：{passed} 项通过，{len(failed)} 项失败")
if failed:
    for f in failed:
        print(f"  ✗ {f}")
    raise SystemExit(1)
print("全部通过")

#!/usr/bin/env python3
"""1.7.6.1 门禁：四分发 JAR 字节码核查（1.7.6 三次覆盖轮全量回归 + 本轮假人过滤任务）。

本轮改动（用户指令 1.7.6.1 新版本）：
  FK 假人插件假人过滤——①在线排行榜不再计入假人时长（FakePlayerRegistry
     统一口径：连接空/回环/内存连接 或 qq-bind.fake-player-whitelist 名单
     命中；join 事件登记 fake-players.json，真人真实连接自愈移除；两个
     收集源存档直枚举/插件统计都过滤，过滤计数写日志）；②新配置键
     online-list.show-fake-players（默认关）：开启后 /查在线 主列表只显示
     真实玩家、末页底部额外画「假人在线」小节（markdown 文字版同开关
     同口径，人数只算真实玩家+末尾假人摘要行）；③白名单语义全端化。
  上轮改动（1.7.6 三次覆盖轮，全量回归）：
  T7 在线排行榜存档源直枚举服务器数据——不再以 QQ 绑定为入口（没开
     绑定的服务器也能出榜）：StatsFileService 删 readPlayTimeAll、新增
     readAllPlayTimeEntries（usercache.json 全量条目【同名/改名取最新名】
     + stats 目录补充扫描【名字用调用方绑定提示补出，无名字跳过计数】）；
     LeaderboardCommands.collectSaveDataEntries 重写（绑定记录降级为
     名字提示 + 插件统计回退两个辅助角色）。
  D  总在线时长获取源默认 variable → save-data（config-version 53→54；
     旧默认 variable 升级自动收敛，自选 plugin/save-data 不动；未配置/
     空值静默按 save-data，未知值告警按 save-data）。
  G  bot.groups 只写一对双引号 ""（或纯空白/纯引号）不再误判已填写——
     ConfigManager.normalizeGroupIds 归一（去空白+剥包裹引号+剔空），
     groupOpenIds 走归一。
  回归：1.7.6 二次覆盖轮全部断言（两级 UUID 名片链路 / 排行榜格式统一 /
  变量覆盖按源收紧）+ 覆盖轮（命令日志 / 模板收紧 / 真实玻璃下线）+
  首发四任务 + 1.7.5.9 回归。

用法：python3 scripts/verify_jar_176o4.py
"""
import re
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
DIST = REPO / "build" / "dist"
JARS = {
    "Spigot": DIST / "KERONGPenguin_Spigot-1.7.6.1.jar",
    "NeoForge": DIST / "KERONGPenguin_NeoForge-1.20.4-1.7.6.1.jar",
    "Fabric": DIST / "KERONGPenguin_Fabric-1.20.4-1.7.6.1.jar",
    "Fabric1201": DIST / "KERONGPenguin_Fabric-1.20.1-1.7.6.1.jar",
}
JAVAP = "/home/z/my-project/tooling/jdk21/bin/javap"

results = []


def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))


def javap(jar, inner, extra=("-c", "-p")):
    import tempfile
    with zipfile.ZipFile(jar) as zf:
        data = zf.read(inner)
    with tempfile.TemporaryDirectory() as td:
        p = Path(td) / "c.class"
        p.write_bytes(data)
        out = subprocess.run([JAVAP, *extra, str(p)], capture_output=True, text=True, timeout=120)
        return out.stdout


def method_body(javap_txt, name):
    out, inside, pending = [], False, name
    decl = re.compile(r"^  \S[^(]*\(")
    for line in javap_txt.splitlines():
        if decl.match(line):
            if inside:
                if re.search(rf"\b{re.escape(pending)}\(", line):
                    continue
                break
            if re.search(rf"\b{re.escape(pending)}\(", line):
                inside = True
        if inside:
            out.append(line)
    return "\n".join(out)


def scan_mc_refs(jar):
    import tempfile
    OWN = ("cn/kerong/penguin/fabric/", "org/bukkit/")
    refs = set()
    with tempfile.TemporaryDirectory() as td, zipfile.ZipFile(jar) as zf:
        names = [n for n in zf.namelist()
                 if n.endswith(".class") and any(n.startswith(p) for p in OWN)]
        for n in names:
            (Path(td) / n).parent.mkdir(parents=True, exist_ok=True)
            (Path(td) / n).write_bytes(zf.read(n))
        for i in range(0, len(names), 40):
            out = subprocess.run([JAVAP, "-c", "-p"] + [str(Path(td) / n) for n in names[i:i + 40]],
                                 capture_output=True, text=True, timeout=300)
            for line in out.stdout.splitlines():
                m = re.search(r"// (?:Interface|Class)?(Method|Field) ([\w/$.]+)\.([\w$<>]+):(.+)$", line)
                if m and m.group(2).startswith("net/minecraft/"):
                    refs.add(f"{m.group(2)}.{m.group(3)} {m.group(4)}")
    return sorted(r for r in refs if not (".method_" in r or ".field_" in r or
                     re.search(r"\.(method_|field_)", r)))


def main():
    for label, jar in JARS.items():
        check(f"0.{label} 分发 JAR 存在", jar.is_file(), str(jar))
        if not jar.is_file():
            continue
        with zipfile.ZipFile(jar) as zf:
            names = zf.namelist()

            # ---------- A. 版本与资源 ----------
            if label == "Spigot":
                py = zf.read("plugin.yml").decode("utf-8", "replace")
                check("A1 Spigot plugin.yml 版本 1.7.6",
                      re.search(r"version:\s*'?\"?1\.7\.6", py) is not None)
            if label == "NeoForge":
                for mfile in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
                    mt = zf.read(mfile).decode("utf-8", "replace") if mfile in names else ""
                    check(f"A2 NF {mfile} 含 1.7.6.1", "1.7.6.1" in mt)
                check("A2b NF MOD_VERSION 1.7.6.1（ldc 常量池）",
                      "1.7.6.1" in javap(jar, "cn/kerong/penguin/neoforge/KERONGNeoForge.class", ("-c", "-p")))
            if label in ("Fabric", "Fabric1201"):
                fm = zf.read("fabric.mod.json").decode("utf-8", "replace")
                check(f"A3 {label} fabric.mod.json 版本 1.7.6.1",
                      re.search(r'"version":\s*"1\.7\.6\.1"', fm) is not None)
                if label == "Fabric":
                    check("A3b Fabric(1.20.4) minecraft 声明 >=1.20.4 <1.22",
                          re.search(r'"minecraft":\s*">=1\.20\.4 <1\.22"', fm) is not None, fm[:200])
                else:
                    check("A3b Fabric1201 minecraft 声明精确 1.20.1",
                          re.search(r'"minecraft":\s*"1\.20\.1"', fm) is not None, fm[:200])
            cfg = zf.read("config.yml").decode("utf-8", "replace")
            check(f"A4 {label} config-version 55（1.7.6.1：假人过滤轮）",
                  re.search(r"config-version:\s*55", cfg) is not None, cfg[:120])
            check(f"A5 {label} total-playtime-source 默认 save-data（三次覆盖轮）",
                  re.search(r'total-playtime-source:\s*"save-data"', cfg) is not None)
            check(f"A5d {label} 排行榜不依赖绑定注释在位（不依赖 QQ 绑定）",
                  "不需要开 QQ" in cfg or "不依赖 QQ" in cfg or "不需要 QQ" in cfg)
            check(f"A5e {label} bot.groups 空引号注释在位（只写一对引号视同没填）",
                  "只写一对引号" in cfg)
            check(f"A5b {label} render.real-glass 键已删除（覆盖轮反断言）",
                  "real-glass" not in cfg and "真实玻璃" not in cfg)
            check(f"A5c {label} qq-bind.fake-player-whitelist 键在位（回归）",
                  re.search(r"fake-player-whitelist:\s*\[\]", cfg) is not None)
            check(f"A6 {label} qq-bind.restrict-timeout-seconds: 120 键保留（回归）",
                  re.search(r"restrict-timeout-seconds:\s*120", cfg) is not None)
            check(f"A6b {label} 两代显示名键已删（1.7.5.9 回归反断言）",
                  "qq-forward-display-name" not in cfg and "group-sender-name" not in cfg)
            check(f"A6c {label} bot.button-cooldown-seconds 键保留（回归）",
                  re.search(r"button-cooldown-seconds:\s*5", cfg) is not None)
            check(f"A6d {label} 背景选择补注释键在位（coins-leaderboard.builtin-background 注释）",
                  re.search(r"coins-leaderboard:\n  # 显示前 N 名", cfg) is not None)

            # ---------- T1. 总在线时长存档读取修复（四端共有源码） ----------
            sfs = "cn/kerong/penguin/spigot/stats/StatsFileService.class"
            check(f"T1a {label} StatsFileService 类存在", sfs in names)
            if sfs in names:
                sbytes = zf.read(sfs)
                st = javap(jar, sfs, ("-c", "-p"))
                check(f"T1b {label} readPlayTime 两参新签名（String, UUID）",
                      re.search(r"readPlayTime\(java\.lang\.String, ?java\.util\.UUID\)", st) is not None,
                      st[:400])
                check(f"T1c {label} 旧 readPlayTicks 已删除（反断言）", "readPlayTicks" not in st)
                check(f"T1d {label} 旧 formatHours / noteUnavailableOnce 已删除（反断言）",
                      "formatHours" not in st and "noteUnavailableOnce" not in st)
                check(f"T1e {label} resolveAllServerUuids 按 usercache.json 解析（字节级，二次覆盖重构）",
                      b"usercache.json" in sbytes and "resolveAllServerUuids" in st)
                check(f"T1f {label} 统计键常量保持（play_time / play_one_minute，字节级）",
                      all(k.encode("utf-8") in sbytes for k in
                          ("minecraft:play_time", "minecraft:play_one_minute", "minecraft:custom", "stats")))
                check(f"T1g {label} 服务器 UUID 查找日志（[存档统计] 行，字节级）",
                      "[存档统计]".encode("utf-8") in sbytes and "绑定记录".encode("utf-8") in sbytes)
                su = method_body(st, "readPlayTime")
                check(f"T1h {label} readPlayTime 第二级遍历 usercache 同名候选（无文件自动试次新）",
                      "resolveAllServerUuids" in su and "parsePlayTicks" in su, su[:200])
                rau = method_body(st, "resolveAllServerUuids")
                check(f"T1h2 {label} resolveAllServerUuids 同名全部条目 + 忽略大小写 + expiresOn 降序",
                      "equals" in rau and "sortByDescending" in rau
                      and "loadUsercacheEntries" in rau, rau[:200])
                luc = method_body(st, "loadUsercacheEntries")
                check(f"T1h3 {label} loadUsercacheEntries 读盘 + lastModified 缓存",
                      "lastModified" in luc and "parseArray" in luc and "getString" in luc, luc[:200])

                # ---------- T7. 排行榜存档源直枚举（三次覆盖新增） ----------
                check(f"T7a {label} readAllPlayTimeEntries 签名（Map<UUID,String> → List，泛型全形态）",
                      re.search(r"readAllPlayTimeEntries\(java\.util\.Map<", st) is not None,
                      st[:300])
                check(f"T7a2 {label} readPlayTimeAll 已删除（三次覆盖反断言）",
                      "readPlayTimeAll" not in st)
                rpa = method_body(st, "readAllPlayTimeEntries")
                check(f"T7b {label} 直枚举两级（usercache 全量 loadUsercacheEntries + stats 目录补充 listFiles）",
                      "loadUsercacheEntries" in rpa and "parsePlayTicks" in rpa
                      and "listFiles" in rpa and "LinkedHashMap" in rpa, rpa[:260])
                check(f"T7b2 {label} 名字提示仅补名字（nameHints Map 取名，无绑定依赖入口）",
                      "nameHints" in rpa or "getName" in rpa or "get" in rpa, rpa[:200])
                spe = "cn/kerong/penguin/spigot/stats/StatsFileService$ServerPlayTimeEntry.class"
                check(f"T7c {label} ServerPlayTimeEntry 数据类在位", spe in names)
                sue = "cn/kerong/penguin/spigot/stats/StatsFileService$ServerUuidEntry.class"
                check(f"T7c2 {label} ServerUuidEntry 数据类保持（名片链路用，回归）", sue in names)
                check(f"T7d {label} usercache 解析缓存字段（usercacheEntries / usercacheStamp）",
                      "usercacheEntries" in st and "usercacheStamp" in st)
                check(f"T7e {label} 排行榜直枚举汇总日志（字节级）",
                      "排行榜直枚举".encode("utf-8") in sbytes and
                      "stats 目录补充".encode("utf-8") in sbytes and
                      "整榜回退插件统计".encode("utf-8") in sbytes)
                fpt2 = method_body(st, "formatPlayText")
                check(f"T7f {label} formatPlayText 回归（÷20 + 0分钟兑底）",
                      re.search(r"bipush\s+20", fpt2) is not None and
                      "0分钟".encode("utf-8") in sbytes, fpt2[:140])
                fpt = method_body(st, "formatPlayText")
                check(f"T1i {label} formatPlayText 统一 X时X分（÷20 秒 + 中文单位）",
                      re.search(r"bipush\s+20", fpt) is not None and
                      "时".encode("utf-8") in sbytes and "分".encode("utf-8") in sbytes, fpt[:160])
                rlt = method_body(st, "readPlayTime")
                check(f"T1j {label} readPlayTime 两级查找（绑定 UUID → usercache 同名候选回退）",
                      "resolveAllServerUuids" in rlt and "parsePlayTicks" in rlt, rlt[:200])
            qis = "cn/kerong/penguin/spigot/stats/QueryInfoService.class"
            if qis in names:
                qt = javap(jar, qis, ("-c", "-p"))
                rtp = method_body(qt, "resolveTotalPlaytime")
                check(f"T1k {label} resolveTotalPlaytime 走 readPlayTime + formatPlayText",
                      "readPlayTime" in rtp and "formatPlayText" in rtp
                      and "formatPlayTime" in rtp and "totalPlaytimeSource" in rtp, rtp[:220])
                snap = "cn/kerong/penguin/spigot/stats/QueryInfoService$MainThreadSnapshot.class"
                if snap in names:
                    snap_txt = javap(jar, snap, ("-p",))
                    check(f"T1l {label} MainThreadSnapshot 含 playerName + playerUuid 字段",
                          "getPlayerName" in snap_txt and "getPlayerUuid" in snap_txt)
            pvs = "cn/kerong/penguin/spigot/stats/PlaceholderVariableService.class"
            if pvs in names:
                pt = javap(jar, pvs)
                tps = method_body(pt, "totalPlaytimeSource")
                check(f"T1m {label} totalPlaytimeSource 三源常量保持（回归）",
                      "variable-fetch.total-playtime-source" in tps, tps[:140])
                pbytes = zf.read(pvs)
                check(f"T1n {label} 三源常量 + 中文别名保持（回归，字节级）",
                      all(k.encode("utf-8") in pbytes for k in
                          ("save-data", "plugin", "variable", "存档数据", "插件统计", "变量")))
            cm = "cn/kerong/penguin/spigot/manager/ConfigManager.class"
            if cm in names:
                cmfull = javap(jar, cm, ("-v",))
                cmbytes = zf.read(cm)
                check(f"T1o {label} DEFAULT_VALUES 含 total-playtime-source（回归）",
                      "variable-fetch.total-playtime-source".encode("utf-8") in cmbytes)
                check(f"T1p {label} render.real-glass 在 REMOVED_CONFIG_KEYS（升53删键，字节级）",
                      "render.real-glass".encode("utf-8") in cmbytes)
                check(f"T1q {label} CURRENT_CONFIG_VERSION 编译期内联 55（bipush 55 存在）",
                      re.search(r"bipush\s+55", javap(jar, cm, ("-c",))) is not None)
                fpw = method_body(javap(jar, cm), "fakePlayerWhitelist")
                check(f"T1r {label} ConfigManager.fakePlayerWhitelist 读名单键（回归）",
                      "qq-bind.fake-player-whitelist" in fpw and "getStringList" in fpw, fpw[:140])

            # ---------- T7g-T7l. 排行榜命令接入存档源（二次覆盖新增） ----------
            lbc = "cn/kerong/bot/events/commands/LeaderboardCommands.class"
            check(f"T7g {label} LeaderboardCommands 类存在", lbc in names)
            if lbc in names:
                lbbytes = zf.read(lbc)
                lbt = javap(jar, lbc, ("-c", "-p"))
                cse = method_body(lbt, "collectSaveDataEntries")
                check(f"T7h {label} collectSaveDataEntries 直枚举收集（readAllPlayTimeEntries + 名字提示 + 插件统计回退）",
                      "readAllPlayTimeEntries" in cse and "formatPlayText" in cse
                      and "play-seconds" in cse and "getLong" in cse
                      and "putIfAbsent" in cse, cse[:240])
                check(f"T7h2 {label} 回退判定（UUID 已在榜或同名已在榜不回退）",
                      "contains" in cse or "in listOf" in cse or "HashSet" in cse, cse[:200])
                check(f"T7i {label} 排行榜汇总日志（字节级）",
                      "排行榜汇总".encode("utf-8") in lbbytes and
                      "回退插件统计".encode("utf-8") in lbbytes)
                lbm = method_body(lbt, "leaderboard")
                # submit { ... } lambda 体在合成方法——按类全文断分派逻辑；
                # SOURCE_SAVE_DATA 为 const val 编译期内联为 ldc "save-data"（坑表）
                check(f"T7j {label} leaderboard 按源分派（totalPlaytimeSource + collectSaveDataEntries + save-data）",
                      "totalPlaytimeSource" in lbt and "collectSaveDataEntries" in lbt
                      and "// String save-data" in lbt, lbt[:240])
                check(f"T7k {label} snapshotVariableOverrides 双参（resolveValue 布尔开关）",
                      re.search(r"snapshotVariableOverrides\([^)]*RawEntry[^)]*, ?boolean\)", lbt) is not None,
                      lbt[:300])
                # 嵌套 lambda 按类全文断（1.7.6 坑表）：valueKey 条件传参
                # if (resolveValue) "total-playtime" else null
                check(f"T7l {label} 时长变量仅 variable 源解析（total-playtime 条件 + null 分支）",
                      "// String total-playtime" in lbt and "aconst_null" in lbt, lbt[:200])
                raw = "cn/kerong/bot/events/commands/LeaderboardCommands$RawEntry.class"
                if raw in names:
                    rtxt = javap(jar, raw, ("-p",))
                    check(f"T7m {label} RawEntry 含 valueText 字段（存档行展示文本）",
                          "valueText" in rtxt, rtxt[:200])
            # 排行榜行时长格式统一 X时X分（旧「X小时X分钟」停用）
            lrr = "cn/kerong/penguin/spigot/render/LeaderboardRenderer.class"
            if lrr in names:
                lrbytes = zf.read(lrr)
                check(f"T7n {label} 排行榜行时长统一 X时X分（小时/暂无记录/不足1分钟 反断言）",
                      "小时".encode("utf-8") not in lrbytes and
                      "暂无记录".encode("utf-8") not in lrbytes and
                      "不足1分钟".encode("utf-8") not in lrbytes and
                      "0分钟".encode("utf-8") in lrbytes)
            # resolveBatch valueKey 可空（null = 不解析数值；金币榜调用不变）
            # ⚠ 泛型签名坑：List<kotlin.Pair<...>> 裸类型正则匹配不到（坑表）
            if pvs in names:
                rb = method_body(pt, "resolveBatch")
                check(f"T7o {label} resolveBatch 签名保持三参 + null 安全（valueKey 可空）",
                      re.search(r"resolveBatch\(java\.util\.List[^)]*, java\.lang\.String, java\.lang\.String\)", pt) is not None
                      and "placeholderOf" in rb, rb[:200])
            clc = "cn/kerong/bot/events/commands/CoinsLeaderboardCommands.class"
            if clc in names:
                cltxt = javap(jar, clc)
                check(f"T7p {label} 金币榜 resolveBatch 调用不变（coins 键，回归）",
                      "resolveBatch" in cltxt and "coins" in cltxt, cltxt[:200])


            # ---------- FK. 假人插件假人过滤（1.7.6.1 本轮新增） ----------
            fpr = "cn/kerong/penguin/spigot/stats/FakePlayerRegistry.class"
            check(f"FK1 {label} FakePlayerRegistry 类存在", fpr in names)
            if fpr in names:
                fbytes = zf.read(fpr)
                ft = javap(jar, fpr, ("-c", "-p"))
                ftp = javap(jar, fpr, ("-p",))
                check(f"FK2 {label} evaluateJoin 三参签名（UUID, String, boolean）",
                      re.search(r"evaluateJoin\(java\.util\.UUID, java\.lang\.String, boolean\)", ftp) is not None,
                      ftp[:200])
                check(f"FK3 {label} liveIsFake 签名（Player → boolean）",
                      re.search(r"liveIsFake\(org\.bukkit\.entity\.Player\)", ftp) is not None)
                # ⚠ 泛型全形态坑：Set<java.util.UUID>
                check(f"FK4 {label} isExcluded 签名（UUID, String, Set<UUID>）",
                      re.search(r"isExcluded\(java\.util\.UUID, java\.lang\.String, java\.util\.Set<", ftp) is not None,
                      ftp[:260])
                check(f"FK5 {label} isFakeName 签名（markdown 名字判定）",
                      re.search(r"isFakeName\(java\.lang\.String\)", ftp) is not None)
                check(f"FK6 {label} connectionDetector 平台注入字段在位",
                      "connectionDetector" in ftp)
                check(f"FK7 {label} 登记文件 fake-players.json（字节级）",
                      b"fake-players.json" in fbytes)
                check(f"FK8 {label} 白名单键读取（字节级）",
                      b"qq-bind.fake-player-whitelist" in fbytes)
                check(f"FK9 {label} 登记日志文案（字节级）",
                      "记为假人插件假人".encode("utf-8") in fbytes
                      and "自愈移除".encode("utf-8") in fbytes)
                ej = method_body(ft, "evaluateJoin")
                check(f"FK10 {label} evaluateJoin 白名单合并 + 登记/自愈双路",
                      "whitelisted" in ej and ("markFake" in ej or "unmarkFake" in ej), ej[:200])
                lif = method_body(ft, "liveIsFake")
                check(f"FK11 {label} liveIsFake 名单或连接检测器",
                      "whitelisted" in lif and "connectionDetector" in lif, lif[:160])
                iex = method_body(ft, "isExcluded")
                check(f"FK12 {label} isExcluded 三路判定（在线集 + 持久登记 + 名单）",
                      "contains" in iex and "whitelisted" in iex and "ensureLoaded" in iex, iex[:200])
                per = method_body(ft, "persist")
                check(f"FK13 {label} persist 原子写（tmp + renameTo）",
                      "renameTo" in per, per[:160])

            # 排行榜假人过滤接线
            if lbc in names:
                lbbytes2 = zf.read(lbc)
                check(f"FK20 {label} 排行榜假人过滤日志（字节级）",
                      "假人过滤".encode("utf-8") in lbbytes2)
                check(f"FK21 {label} 排行榜接线 FakePlayerRegistry.isExcluded",
                      "FakePlayerRegistry.isExcluded" in lbt, lbt[:300])
                osnap = "cn/kerong/bot/events/commands/LeaderboardCommands$OnlineSnapshot.class"
                check(f"FK22 {label} OnlineSnapshot 快照类（players + fakeUuids）",
                      osnap in names and "getFakeUuids" in javap(jar, osnap, ("-p",)))
                check(f"FK23 {label} snapshotOnlinePlayers 快照内假人实时判定（lambda 合成方法，类全文断言）",
                      "liveIsFake" in lbt and "snapshotOnlinePlayers" in lbt, lbt[:200])

            # 查在线假人分流
            ols = "cn/kerong/penguin/spigot/stats/OnlineListService.class"
            if ols in names:
                olbytes = zf.read(ols)
                olt = javap(jar, ols, ("-c", "-p"))
                pinfo = "cn/kerong/penguin/spigot/stats/OnlineListService$PlayerInfo.class"
                check(f"FK30 {label} PlayerInfo 快照条目类（fake 标记）",
                      pinfo in names and "getFake" in javap(jar, pinfo, ("-p",)))
                check(f"FK31 {label} 快照内 liveIsFake 判定",
                      "liveIsFake" in olt)
                check(f"FK32 {label} 缓存 key 假人段（#fake# 分隔，字节级）",
                      b"#fake#" in olbytes)
                check(f"FK33 {label} 假人分流走 RenderSettings.onlineListShowFakePlayers",
                      "onlineListShowFakePlayers" in olt)
                check(f"FK34 {label} 渲染构造传假人列表（OnlineListData 双 List 形态）",
                      "(Ljava/lang/String;Ljava/lang/String;Ljava/util/List;Ljava/util/List;" in olt, olt[:200])

            olr = "cn/kerong/penguin/spigot/render/OnlineListRenderer.class"
            if olr in names:
                olrbytes = zf.read(olr)
                olrt = javap(jar, olr, ("-c", "-p"))
                olrp = javap(jar, olr, ("-p",))
                old_inner = "cn/kerong/penguin/spigot/render/OnlineListRenderer$OnlineListData.class"
                check(f"FK40 {label} OnlineListData.fakeEntries 字段（内部类，默认空）",
                      old_inner in names and "fakeEntries" in javap(jar, old_inner, ("-p",))
                      and "getFakeEntries" in javap(jar, old_inner, ("-p",)))
                check(f"FK41 {label} drawFakeListHeader 小节头在位",
                      "drawFakeListHeader" in olrp)
                check(f"FK42 {label} 假人小节文案（字节级）",
                      "假人在线".encode("utf-8") in olrbytes)
                check(f"FK43 {label} drawPlayerGrid 返回底部 + topOverride/emptyAllowed 参数",
                      re.search(r"drawPlayerGrid\(java\.awt\.Graphics2D.*boolean, int, boolean\)", olrp) is not None,
                      olrp[:300])
                check(f"FK44 {label} measurePageHeight 三参（含 fakeCount）",
                      re.search(r"measurePageHeight\(int, boolean, int\)", olrp) is not None)
                check(f"FK45 {label} renderPages 末页带假人小节（getFakeEntries）",
                      "getFakeEntries" in method_body(olrt, "renderPages"))
                check(f"FK46 {label} renderSingle 画假人小节（drawFakeListHeader 接线）",
                      "drawFakeListHeader" in method_body(olrt, "renderSingle"),
                      method_body(olrt, "renderSingle")[:160])

            rs = "cn/kerong/penguin/spigot/render/RenderSettings.class"
            if rs in names:
                rsbytes = zf.read(rs)
                check(f"FK50 {label} RenderSettings.onlineListShowFakePlayers 读取新键",
                      "onlineListShowFakePlayers" in javap(jar, rs, ("-p",))
                      and b"online-list.show-fake-players" in rsbytes)

            pcmd = "cn/kerong/bot/events/commands/PublicCommands.class"
            if pcmd in names:
                pcbytes = zf.read(pcmd)
                pct = javap(jar, pcmd, ("-c", "-p"))
                qo = method_body(pct, "queryOnline")
                check(f"FK60 {label} markdown 查在线分流（isFakeName 双向过滤）",
                      "isFakeName" in qo and "onlineListShowFakePlayers" in qo, qo[:260])
                check(f"FK61 {label} markdown 假人摘要行（字节级）",
                      "假人在线（".encode("utf-8") in pcbytes)

            # 配置模板三件：新键 + 注释口径
            check(f"FK70 {label} online-list.show-fake-players 默认 false",
                  re.search(r"online-list:\n(  #.*\n)+  show-fake-players: false", cfg) is not None,
                  cfg[:120])
            check(f"FK71 {label} 假人列表注释说明判定口径（fake-player-whitelist 联动）",
                  "假人插件假人" in cfg and "假人在线" in cfg)
            check(f"FK72 {label} 白名单注释全端化（1.7.6.1 起所有端共用）",
                  "所有端共用本名单识别假人" in cfg)
            if cm in names:
                check(f"FK73 {label} DEFAULT_VALUES 含 online-list.show-fake-players（字节级）",
                      b"online-list.show-fake-players" in cmbytes)

            # ---------- D. 默认源改存档 + 收敛（三次覆盖新增） ----------
            if pvs in names:
                check(f"D1 {label} 未知值告警按 save-data（新警告文案，字节级）",
                      "按默认 save-data 处理".encode("utf-8") in pbytes, "")
                tps2 = method_body(javap(jar, pvs, ("-c", "-p")), "totalPlaytimeSource")
                check(f"D2 {label} totalPlaytimeSource 空值/存档同支（when 首支 ldc save-data）",
                      "// String save-data" in tps2, tps2[:160])
            if cm in names:
                cmtxt = javap(jar, cm, ("-c", "-p"))
                check(f"D3 {label} DEFAULT_VALUES 默认 save-data（静态初始化 ldc，字节级）",
                      "save-data".encode("utf-8") in cmbytes, "")
                check(f"D4 {label} reload 收敛块（收敛日志文案，字节级）",
                      "总在线时长获取源默认已改为服务器存档".encode("utf-8") in cmbytes, "")
                rlc = method_body(cmtxt, "reload")
                check(f"D5 {label} reload 收敛条件（ldc variable 比对旧默认 + bipush 54 区间）",
                      "// String variable" in rlc and re.search(r"bipush\s+54", rlc) is not None,
                      rlc[:80])
            cfb = "cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.class"
            if cfb in names:
                cfbbytes = zf.read(cfb)
                check(f"D6 {label} 重建器收敛 pre-step（total-playtime-source 键在重建器内，字节级）",
                      "variable-fetch.total-playtime-source".encode("utf-8") in cfbbytes, "")

            # ---------- G. bot.groups 空串归一（三次覆盖新增） ----------
            if cm in names:
                cmfull2 = javap(jar, cm, ("-p",))
                # ⚠ 泛型全形态坑：签名实为 normalizeGroupIds(java.util.List<java.lang.String>)
                check(f"G1 {label} normalizeGroupIds 静态方法在位（@JvmStatic，泛型全形态）",
                      re.search(r"normalizeGroupIds\(java\.util\.List<", cmfull2) is not None,
                      cmfull2[:200])
                # ⚠ @JvmStatic 生成的外类方法只是委托，真身在 Companion
                # ⚠ map/filter 内联：String.trim/StringsKt.trim 直接入体，
                # isNotEmpty 内联为 CharSequence.length 判断
                comp = "cn/kerong/penguin/spigot/manager/ConfigManager$Companion.class"
                ng = ""
                if comp in names:
                    ng = method_body(javap(jar, comp, ("-c", "-p")), "normalizeGroupIds")
                check(f"G2 {label} 归一链（StringsKt.trim 剥空白引号 + length 删空，Companion 真身）",
                      "StringsKt.trim" in ng and "CharSequence.length" in ng, ng[:200])
                go = method_body(javap(jar, cm, ("-c", "-p")), "groupOpenIds")
                check(f"G3 {label} groupOpenIds 走归一（getStringList + normalizeGroupIds）",
                      "getStringList" in go and "normalizeGroupIds" in go, go[:160])
                check(f"G4 {label} bot.groups 键保持（字节级）",
                      "bot.groups".encode("utf-8") in cmbytes, "")

            # ---------- T3. 真实玻璃渲染下线（四端反断言） ----------
            lgp = "cn/kerong/penguin/spigot/render/LiquidGlassPainter.class"
            check(f"T3a {label} LiquidGlassPainter 类已删除（反断言）", lgp not in names)
            rs = "cn/kerong/penguin/spigot/render/RenderSettings.class"
            if rs in names:
                rt2 = javap(jar, rs, ("-p",))
                check(f"T3b {label} RenderSettings.realGlassEnabled 已删除（反断言）",
                      "realGlassEnabled" not in rt2)
                check(f"T3c {label} RenderSettings 模糊度读取保持（回归）",
                      "blurStrength" in rt2)
            inner = "cn/kerong/penguin/spigot/render/InfoCardRenderer$CardBackdropContext.class"
            if inner in names:
                cbc = javap(jar, inner)
                pc2 = method_body(cbc, "paintCard")
                check(f"T3d {label} paintCard 无玻璃分支（realGlassEnabled/LiquidGlass 反断言）",
                      "realGlassEnabled" not in pc2 and "LiquidGlassPainter" not in pc2
                      and "drawImage" in pc2, pc2[:160])
            # cardBackdropOf 唯一定义处在 InfoCardAssets（同文件双 object 坑，1.7.6 坑表）
            ica = "cn/kerong/penguin/spigot/render/InfoCardAssets.class"
            if ica in names:
                cbo = method_body(javap(jar, ica), "cardBackdropOf")
                check(f"T3e {label} cardBackdropOf 恢复旧语义（模糊0→null，无玻璃特例）",
                      "realGlassEnabled" not in cbo and "blurStrength" in cbo
                      and "blurImage" in cbo, cbo[:200])

            # ---------- T5. 执行命令控制台日志（四端共有源码） ----------
            for cmd_cls, tag in (
                ("cn/kerong/bot/events/commands/AdministrationCommands.class", "Admin"),
                ("cn/kerong/bot/events/commands/PublicCommands.class", "Public"),
            ):
                if cmd_cls in names:
                    cbytes = zf.read(cmd_cls)
                    ct = javap(jar, cmd_cls, ("-c", "-p"))
                    check(f"T5a{tag} {label} executeGameCommandWithMention 五参（含 displayCommand）",
                          re.search(r"executeGameCommandWithMention\([^)]*, ?boolean, ?java\.lang\.String\)", ct) is not None,
                          ct[:300])
                    check(f"T5b{tag} {label} 「已执行命令：」回复头（字节级）",
                          "已执行命令：".encode("utf-8") in cbytes)
                    eg = method_body(ct, "executeGameCommandWithMention")
                    # lambda 嵌套形态（$lambda$3$lambda$2）无法逐方法抽取——
                    # 按类全文断常量（sent-request 只出现在本方法的 ldc）
                    check(f"T5c{tag} {label} 无输出走 sent-request + command 变量",
                          "// String sent-request" in ct and "// String command" in ct,
                          ct[:220])
            # sent-request 模板默认内容（双份资源：Spigot 走 common-Bot 资源，NF 有副本）
            sr = "Markdown/sent-request.md"
            if sr in names:
                content = zf.read(sr).decode("utf-8", "replace")
                check(f"T5d {label} sent-request.md 新默认（已执行命令 + {{command}}）",
                      "已执行命令：{command}" in content and "无输出" in content, content[:80])

            # ---------- T6. 消息模板覆盖轮（四端共有源码） ----------
            mtc = "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class"
            if mtc in names:
                mtxt = javap(jar, mtc, ("-c", "-p"))
                mbytes = zf.read(mtc)
                check(f"T6a {label} 自定义文件名释放日志常量（字节级）",
                      "使用自定义文件".encode("utf-8") in mbytes)
                ini = method_body(mtxt, "initialize")
                check(f"T6b {label} initialize 释放自定义文件名（mkdirs 父目录）",
                      "mkdirs" in ini, ini[:160])
                # shouldUpgrade 在内部类 MessageTemplates$UpgradeSpec 上（object 内 private class）
                upc = "cn/kerong/penguin/spigot/qqbind/MessageTemplates$UpgradeSpec.class"
                su2 = ""
                if upc in names:
                    su2 = method_body(javap(jar, upc), "shouldUpgrade")
                check(f"T6c {label} shouldUpgrade 只做精确比对（contains 特征反断言）",
                      "contains" not in su2 and "normalize" in su2, su2[:200])
                ar = method_body(mtxt, "applyReplacement")
                check(f"T6d {label} applyReplacement 先 delete（1.7.5.9 回归）",
                      "delete" in ar and "Code:" in ar, ar[:100])
                check(f"T6e {label} menu 模板 ID 保持（回归）",
                      "menu.md" in mtxt or '"menu"'.encode("utf-8") in mbytes or "menu" in mtxt)

            # ---------- T4. 按钮艾特修复回归 ----------
            rgm = "cn/kerong/bot/events/RecentGroupMessages.class"
            check(f"T4a {label} RecentGroupMessages 类存在（回归）", rgm in names)
            if rgm in names:
                rt3 = javap(jar, rgm, ("-p",))
                check(f"T4b {label} nextIdFromSender 双参在位（回归）",
                      re.search(r"nextIdFromSender\(java\.lang\.String, ?java\.lang\.String\)", rt3) is not None)
                check(f"T4c {label} 旧单参 nextId 已删除（回归反断言）",
                      re.search(r"nextId\(java\.lang\.String\)", rt3) is None)
            gmr = "cn/kerong/bot/events/GroupMessageHandler.class"
            if gmr in names:
                gt = javap(jar, gmr)
                ogm = method_body(gt, "onGroupMessage")
                check(f"T4d {label} onGroupMessage 登记带发送者（回归）",
                      "RecentGroupMessages.record:(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V" in ogm,
                      ogm[:300])
            mb = "cn/kerong/bot/events/MenuButtons.class"
            if mb in names:
                mt2 = javap(jar, mb)
                dac = method_body(mt2, "dispatchAsClicker")
                check(f"T4e {label} dispatchAsClicker 只引用点击者消息（回归）",
                      "nextIdFromSender" in dac, dac[:200])

            # ---------- 回归：1.7.5.9 转发显示名去配置化 ----------
            check(f"R0a {label} GroupSenderName 类已删除（回归反断言）",
                  "cn/kerong/bot/tools/GroupSenderName.class" not in names)
            if gmr in names:
                gtxt = javap(jar, gmr)
                fw = method_body(gtxt, "forwardFullGroupMessage")
                check(f"R0b {label} 全量转发直取事件昵称 getUsername（回归）",
                      "getUsername" in fw and "resolveForwardDisplayName" not in gtxt, fw[:150])
                lbn = method_body(gtxt, "loadBotNicknames")
                check(f"R0d {label} loadBotNicknames 无 getBotName（回归）",
                      "getBotName" not in lbn)

        # ---------- T2. Fabric 假人白名单（仅 Fa 两包，回归） ----------
        if label in ("Fabric", "Fabric1201"):
            with zipfile.ZipFile(jar) as zf:
                fnames = zf.namelist()
                fpe = "cn/kerong/penguin/fabric/FakePlayerExempt.class"
                check(f"T2a {label} FakePlayerExempt 类存在（回归）", fpe in fnames)
                if fpe in fnames:
                    fe_txt = javap(jar, fpe)
                    check(f"T2b {label} Connection 类锚定 class_2535（回归）",
                          "net.minecraft.class_2535" in fe_txt)
                    check(f"T2c {label} method_10756/10755（回归）",
                          "method_10756" in fe_txt and "method_10755" in fe_txt)
                    iex = method_body(fe_txt, "isExempt")
                    check(f"T2d {label} isExempt 双路（回归）",
                          "isWhitelisted" in iex and "isLocalFakePlayer" in iex, iex[:120])
                br = "cn/kerong/penguin/fabric/BindRestrict.class"
                check(f"T2e {label} BindRestrict 类存在（回归）", br in fnames)
                if br in fnames:
                    bt = javap(jar, br)
                    brbytes = zf.read(br)
                    oj = method_body(bt, "onJoin")
                    check(f"T2f {label} onJoin 假人白名单接线（回归）",
                          "FakePlayerExempt.isExempt" in oj, oj[:220])
                    check(f"T2g {label} onJoin 生成绑定码 + 已绑定放行（回归）",
                          "generateCode" in oj and "isBoundByUuid" in oj)
                    disc = "method_52396" if label == "Fabric" else "method_14367"
                    check(f"T2h {label} tick 超时踢出锚点（回归）",
                          disc in bt and "method_14363" in bt and "method_43496" in bt)
                bri = "cn/kerong/penguin/fabric/BindRestrictInteractions.class"
                check(f"T2i {label} BindRestrictInteractions 类存在（回归）", bri in fnames)
                # 未映射引用白名单
                unmapped = scan_mc_refs(jar)
                whitelist = {
                    "net/minecraft/class_124.ordinal ()I",
                    "net/minecraft/class_124.values ()[Lnet/minecraft/class_124;",
                    "net/minecraft/class_2561.getString ()Ljava/lang/String;",
                    "net/minecraft/server/MinecraftServer.execute (Ljava/lang/Runnable;)V",
                }
                bad = [r for r in unmapped if r not in whitelist]
                check(f"T2j {label} 未映射引用仅白名单 4 条（回归）",
                      not bad and len(unmapped) <= 4, "; ".join(unmapped[:8]))


        if label == "Spigot":
            with zipfile.ZipFile(jar) as zf:
                snames = zf.namelist()
                pel = "cn/kerong/penguin/spigot/events/PlayerEventsListener.class"
                check("FK80 Spigot PlayerEventsListener 假人登记接线",
                      pel in snames and "evaluateJoin" in javap(jar, pel)
                      and "isLocalFakeJoin" in javap(jar, pel))
                ksp = "cn/kerong/penguin/spigot/KERONGSpigot.class"
                if ksp in snames:
                    kst = javap(jar, ksp, ("-c", "-p"))
                    check("FK81 Spigot KERONGSpigot 注册 connectionDetector（onEnable，setConnectionDetector）",
                          "setConnectionDetector" in kst and "FakePlayerRegistry" in kst
                          and "isLocalFakeConnection" in kst)


        if label in ("Fabric", "Fabric1201"):
            with zipfile.ZipFile(jar) as zf:
                fnames2 = zf.namelist()
                if fpe in fnames2:
                    fep = javap(jar, fpe, ("-p",))
                    # 1.7.6.1：isLocalFakePlayer 由 private 改 public（FabricEvents 登记与检测器共用）
                    check(f"FK84 {label} FakePlayerExempt.isLocalFakePlayer 已公开（非 private）",
                          re.search(r"public .*isLocalFakePlayer\(net\.minecraft\.class_7226\)", fep) is not None
                          or re.search(r"isLocalFakePlayer\(", fep) is not None and "private" not in fep.split("isLocalFakePlayer")[0][-60:],
                          fep[:300])
                fev = "cn/kerong/penguin/fabric/FabricEvents.class"
                if fev in fnames2:
                    fet = javap(jar, fev, ("-c", "-p"))
                    check(f"FK85 {label} FabricEvents JOIN 假人登记接线",
                          "evaluateJoin" in fet and "isLocalFakePlayer" in fet, fet[:240])
                    check(f"FK86 {label} FabricEvents registerAll 注册 connectionDetector",
                          "setConnectionDetector" in fet)

        # ---------- 回归：Spigot/NF 假人豁免（1.7.5.8，保持） ----------
        if label == "Spigot":
            with zipfile.ZipFile(jar) as zf:
                gc = "cn/kerong/penguin/spigot/events/GameChat.class"
                names3 = zf.namelist()
                if gc in names3:
                    txt4 = javap(jar, gc)
                    lfl = method_body(txt4, "isLocalFakeLogin")
                    opl = method_body(txt4, "onPlayerLogin")
                    ejb = method_body(txt4, "enforceJoinBinding")
                    check("R1a Spigot isLocalFakeLogin 判回环（回归）",
                          "isLoopbackAddress" in lfl, lfl[:100])
                    check("R1b Spigot onPlayerLogin 接线豁免（回归）",
                          "isLocalFakeLogin" in opl)
                    check("R1c Spigot enforceJoinBinding 接线豁免（回归）",
                          "isLocalFakeJoin" in ejb)
                else:
                    check("R1 Spigot GameChat 类存在", False)

        if label == "NeoForge":
            with zipfile.ZipFile(jar) as zf:
                nnames = zf.namelist()
                nfe2 = "cn/kerong/penguin/neoforge/NeoForgeEvents.class"
                if nfe2 in nnames:
                    nft = javap(jar, nfe2, ("-c", "-p"))
                    opj = method_body(nft, "onPlayerJoin")
                    check("FK82 NF onPlayerJoin 假人登记接线（evaluateJoin + isLocalFakeConnection）",
                          "evaluateJoin" in opj and "isLocalFakeConnection" in opj, opj[:220])
                knf = "cn/kerong/penguin/neoforge/KERONGNeoForge.class"
                if knf in nnames:
                    knt = javap(jar, knf, ("-c", "-p"))
                    check("FK83 NF KERONGNeoForge 注册 connectionDetector（onServerStarted）",
                          "setConnectionDetector" in knt and "FakePlayerRegistry" in knt)

        if label == "NeoForge":
            with zipfile.ZipFile(jar) as zf:
                names4 = zf.namelist()
                blg = "cn/kerong/penguin/neoforge/BindLoginGate.class"
                nfe = "cn/kerong/penguin/neoforge/NeoForgeEvents.class"
                if blg in names4:
                    txt5 = javap(jar, blg)
                    ilfc = "\n".join(method_body(txt5, m) for m in
                                      set(re.findall(r"isLocalFakeConnection(?:\$\w+)?", txt5)))
                    check("R2a NF isLocalFakeConnection 回归",
                          "isLoopbackAddress" in ilfc and "isMemoryConnection" in ilfc
                          and "getRemoteAddress" in ilfc, ilfc[:120])
                    ek = method_body(txt5, "evaluateKick")
                    check("R2b NF evaluateKick 接线回归", "isLocalFakeConnection" in ek)
                if nfe in names4:
                    elb = method_body(javap(jar, nfe), "enforceLoginBinding")
                    check("R3 NF enforceLoginBinding 接线回归",
                          "isLocalFakeConnection" in elb)

        # ---------- H. 形态 ----------
        with zipfile.ZipFile(jar) as zf:
            znames = zf.namelist()
            dirs = sum(1 for n in znames if n.endswith("/"))
            if label == "Spigot":
                check(f"H1 {label} fat JAR 目录条目 ≥ 700（SDK 扫描需要）",
                      dirs >= 700, str(dirs))
            if label == "NeoForge":
                check("H2 NF 重定位 cn/kerong/shaded",
                      any(n.startswith("cn/kerong/shaded/") for n in znames))
                check("H2b NF 无 org/slf4j",
                      not any(n.startswith("org/slf4j/") for n in znames))
            if label in ("Fabric", "Fabric1201"):
                check(f"H3 {label} kotlin 原包名（不重定位）",
                      any(n.startswith("kotlin/") for n in znames))
                check(f"H3b {label} BindRestrict 原包名未重映射",
                      "cn/kerong/penguin/fabric/BindRestrict.class" in znames)

    # ---------- 汇总 ----------
    failed = [r for r in results if not r[1]]
    print(f"\n===== verify_jar_1761：{len(results)} 项，失败 {len(failed)} =====")
    for name, ok, detail in failed:
        print(f"  FAIL {name}  {detail[:160]}")
    print("全绿" if not failed else "存在失败项")
    sys.exit(0 if not failed else 1)


if __name__ == "__main__":
    main()

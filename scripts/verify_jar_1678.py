#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
1.6.7.8 门禁：双端分发 JAR 内容断言（1.6.7.7 基线保持 + 本轮改动新断言）。

1.6.7.8 新增：
  LR.  「延迟（玩家平均）」指标整体移除——玩家 Ping 只反映「客户端到
       服务器」连接的往返，本地 / 同机 / 内网穿透部署下恒为 0ms，数据
       永远只代表服务器本地网络，对性能监控无实际意义（用户反馈）：
       快照 avgPingMillis 字段 / 双端 getPing·latency 平均 / spark
       playerPing 轮询 / 渲染延迟卡 / 启动自检可读项 全部下线；
       卡片改五项布局（TPS / MSPT / 内存 / CPU / 在线人数），末行
       单卡整行通栏，画布高度 1034 不变
  V.   版本 1.6.7.8 五处

基线保持（1.6.7.7 全部关键项）：TPS 三层封顶 / spark 探测五态（大小写
不敏感 + NotLoaded + 内置通道）/ 凭据清洗 / 无 /hb / ConfigBrokenRecovery /
TickMonitor / EWMA / GlobalOpacity / 512KB 轮转 / 包结构 / 目录条目与体积。
（1.6.7.7 的 PG 组延迟 0 合法断言随指标下线废除，由 LR 组反向断言取代。）
"""
import os
import re
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(REPO, "build", "dist")
SPIGOT = os.path.join(DIST, "KERONGPenguin_Spigot-1.6.7.8.jar")
NEOFORGE = os.path.join(DIST, "KERONGPenguin_NeoForge-1.21.1-1.6.7.8.jar")

passed = 0
failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print(f"PASS  {name}")
    else:
        failed += 1
        print(f"FAIL  {name}  [{detail}]")


def read_text(zf, name):
    return zf.read(name).decode("utf-8")


def class_blob(zf, prefix):
    return b"".join(
        zf.read(n) for n in zf.namelist()
        if n.startswith(prefix) and n.endswith(".class")
    )


def has(zf, name):
    return name in zf.namelist()


def src(rel):
    with open(os.path.join(REPO, rel), encoding="utf-8") as f:
        return f.read()


for path in (SPIGOT, NEOFORGE):
    if not os.path.isfile(path):
        print(f"JAR 不存在：{path}")
        sys.exit(2)

with zipfile.ZipFile(SPIGOT) as sp, zipfile.ZipFile(NEOFORGE) as nf:

    # ---------- V. 版本 1.6.7.8 ----------
    plugin_yml = read_text(sp, "plugin.yml")
    check("V1 Spigot plugin.yml 版本 1.6.7.8", "version: '1.6.7.8'" in plugin_yml, plugin_yml.splitlines()[1])
    toml = read_text(nf, "META-INF/neoforge.mods.toml")
    check("V2 NeoForge mods.toml 版本 1.6.7.8", 'version = "1.6.7.8"' in toml)
    nf_main = class_blob(nf, "cn/kerong/penguin/neoforge/KERONGNeoForge")
    check("V3 NF MOD_VERSION 1.6.7.8", b"1.6.7.8" in nf_main)
    check("V4 无 1.6.7.7 版本残留（plugin.yml）", "1.6.7.7" not in plugin_yml)
    check("V5 无 1.6.7.7 版本残留（mods.toml）", "1.6.7.7" not in toml)
    root_gradle = src("build.gradle.kts")
    check("V6 根 build.gradle.kts 1.6.7.8", 'version = "1.6.7.8"' in root_gradle)
    nf_gradle = src("server-NeoForge/build.gradle.kts")
    check("V7 NF build.gradle.kts 1.6.7.8", 'version = "1.6.7.8"' in nf_gradle)

    # ---------- LR. 延迟指标整体移除 ----------
    ps_blob = class_blob(sp, "cn/kerong/bot/PerformanceSnapshot")
    ps_src = src("common-Bot/src/main/kotlin/cn/kerong/bot/KERONG.kt")
    check("LR-1 快照无 avgPingMillis 字段（字节码）", b"avgPingMillis" not in ps_blob)
    check("LR-2 快照 onlinePlayers 字段保持", b"onlinePlayers" in ps_blob)
    check("LR-3 快照源码无 avgPingMillis 声明/参数/引用（注释性历史提及不在此列）",
          "val avgPingMillis" not in ps_src and
          "avgPingMillis: Double?" not in ps_src and
          ".avgPingMillis" not in ps_src)
    check("LR-4 capture 四参工厂（源码签名）",
          "cpuOverride: Double?,\n            onlinePlayers: Int?,\n        ): PerformanceSnapshot {" in ps_src)
    sb_blob = class_blob(sp, "cn/kerong/penguin/spigot/stats/SparkBridge")
    sb_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/SparkBridge.kt")
    check("LR-5 SparkBridge 无 playerPing 轮询（字节码）", b"playerPing" not in sb_blob)
    check("LR-6 SparkBridge 无 PlayerPing 窗口枚举引用", b"StatisticWindow$PlayerPing" not in sb_blob)
    check("LR-7 SparkMetrics 三项（源码构造）",
          "else SparkMetrics(tps, mspt, cpu)" in sb_src and "val ping = pollPlayerPingMean" not in sb_src)
    pr_blob = class_blob(sp, "cn/kerong/penguin/spigot/render/PerformanceRenderer")
    pr_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/PerformanceRenderer.kt")
    check("LR-8 渲染器无「延迟（玩家平均）」标签", "延迟（玩家平均）".encode("utf-8") not in pr_blob)
    check("LR-9 渲染器无「暂无玩家」/「暂不可读（延迟）」专用文案",
          "暂无玩家".encode("utf-8") not in pr_blob and "核心不支持 getPing".encode("utf-8") not in pr_blob)
    check("LR-10 渲染器无 300ms 进度满值常量（IEEE754 0x4072...）",
          b"\x40\x72\xc0\x00\x00\x00\x00\x00" not in pr_blob)
    check("LR-11 五项卡布局（CARD_COUNT = 5）", "private const val CARD_COUNT = 5" in pr_src)
    check("LR-12 末行单卡整行通栏（源码断言）", "if (lone) contentWidth else cardWidth" in pr_src)
    check("LR-13 在线人数卡标签保持", "在线玩家（人数）".encode("utf-8") in pr_blob)
    check("LR-14 五卡画布高度 1034 不变（0x040A）", b"\x04\x0a" in pr_blob)
    ks_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/KERONGSpigot.kt")
    check("LR-15 Spigot 采集端无 player.ping / sparkPing / readPlayerStats",
          "player.ping" not in ks_src and "sparkPing" not in ks_src and "readPlayerStats" not in ks_src)
    check("LR-16 Spigot 采集端改人数专用方法",
          "readOnlinePlayerCount" in ks_src and
          "Bukkit.getOnlinePlayers().size" in ks_src)
    kn_src = src("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt")
    check("LR-17 NF 采集端无 latency() 平均 / sparkPing",
          "latency()" not in kn_src and "sparkPing" not in kn_src)
    check("LR-18 NF 四参 capture 调用",
          "PerformanceSnapshot.capture(tps, mspt, sparkCpu, online)" in kn_src)
    sc_blob = class_blob(sp, "cn/kerong/penguin/spigot/stats/SparkStartupCheck")
    sc_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/SparkStartupCheck.kt")
    check("LR-19 启动自检可读项无延迟（源码 + 字节码）",
          "if (metrics.ping != null)" not in sc_src and
          "延迟".encode("utf-8") not in sc_blob)
    cfg = read_text(sp, "config.yml")
    nf_cfg = read_text(nf, "config.yml")
    check("LR-20 Spigot config 指标注释五项 + 移除原因",
          "固定五项" in cfg and "延迟 = 全部在线玩家" not in cfg)
    check("LR-21 NF config 指标注释五项（修正历史滞留四项注释）",
          "固定五项" in nf_cfg)

    # ---------- TC. TPS 物理上限 20 三层封顶（1.6.7.7 落位，保持） ----------
    # 字节码层：capture 工厂含 coerceAtMost 调用与 20.0 double 常量（0x4034000000000000）
    check("TC-1 capture 工厂 coerceAtMost 在位", b"coerceAtMost" in ps_blob)
    check("TC-2 20.0 常量（IEEE754 0x4034...）", b"\x40\x34\x00\x00\x00\x00\x00\x00" in ps_blob)
    check("TC-3 capture 工厂 safeTps 源码断言", "val safeTps = tps?.coerceAtMost(20.0)" in ps_src)
    check(
        "TC-4 firstTps 出口封顶源码断言",
        "value.firstOrNull()?.coerceAtMost(20.0)" in ps_src and
        "(value.firstOrNull() as? Number)?.toDouble()?.coerceAtMost(20.0)" in ps_src,
    )
    check("TC-5 SparkBridge pollTps 封顶（coerceAtMost + 20.0）",
          b"coerceAtMost" in sb_blob and b"\x40\x34\x00\x00\x00\x00\x00\x00" in sb_blob)
    check("TC-6 pollTps 源码断言", 'listOf("MINUTES_1", "SECONDS_10"),\n        )?.coerceAtMost(20.0)' in sb_src)

    # ---------- SD. spark 探测强化 + 内置通道（保持） ----------
    ks_blob = class_blob(sp, "cn/kerong/penguin/spigot/KERONGSpigot")
    check("SD-1 findSparkPlugin 方法在位（大小写不敏感插件表匹配）", b"findSparkPlugin" in ks_blob)
    check("SD-2 findStraySparkFiles 方法在位（目录扫描）", b"findStraySparkFiles" in ks_blob)
    check("SD-3 大小写不敏感匹配（源码 + 字节码描述符）",
          'it.name.equals("spark", ignoreCase = true)' in ks_src and
          b"Ljava/lang/String;Z)Z" in ks_blob)
    check("SD-4 NotLoaded 态调用（文件在但未作插件加载）", b"NotLoaded" in ks_blob and b"NotLoaded" in sc_blob)
    check("SD-5 「spark 相关文件」提示 + 模组版指引", "spark.lucko.me".encode("utf-8") in sc_blob)
    check("SD-6 probe 与快照共用 findSparkPlugin", ks_src.count("findSparkPlugin()") >= 2)
    check("SD-7 旧精确匹配仅剩快路径一处", ks_src.count('getPlugin("spark")') == 1)

    # ---------- SN. 内置 spark 通道（保持） ----------
    check("SN-1 SparkBridge.bundledSparkClassLoader 在位", b"bundledSparkClassLoader" in sb_blob)
    check("SN-2 KERONGSpigot 内置通道探测挂载", b"bundledSparkClassLoader" in ks_blob and b"bukkitApiLoader" in ks_blob)
    check("SN-3 Installed 双参构造器（metrics + bundled 标记）",
          b"(Lcn/kerong/penguin/spigot/stats/SparkBridge$SparkMetrics;Z)V" in sc_blob and
          b"bundled" in sc_blob)
    check("SN-4 来源文案（服务器核心内置 / 插件）", "服务器核心内置".encode("utf-8") in sc_blob and "来源：".encode("utf-8") in sc_blob)
    check("SN-5 内置可读失败文案", "已随核心内置但各指标均暂不可读".encode("utf-8") in ks_blob)
    check("SN-6 性能快照同口径内置通道（源码断言）",
          "SparkBridge.bundledSparkClassLoader(bukkitApiLoader())" in ks_src)

    # ---------- CR. 凭据清洗（保持） ----------
    cm_blob = class_blob(sp, "cn/kerong/penguin/spigot/manager/ConfigManager")
    cm_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
    check("CR-1 sanitizeCredential 方法在位", b"sanitizeCredential" in cm_blob)
    check("CR-2 trimInvisible 方法在位", b"trimInvisible" in cm_blob)
    check("CR-3 botAppId/botSecret 经清洗（源码断言）",
          'sanitizeCredential(plugin.config.getString("bot.app-id"), "bot.app-id")' in cm_src and
          'sanitizeCredential(plugin.config.getString("bot.secret"), "bot.secret")' in cm_src)
    check("CR-4 零宽/BOM 字符表（源码 \\uFEFF / \\u200B / 全角空格）",
          "'\\uFEFF'" in cm_src and "'\\u200B'" in cm_src and "'\\u3000'" in cm_src)
    check("CR-5 污染警告文案（appid invalid 提示）", "appid invalid".encode("utf-8") in cm_blob)
    check("CR-6 双端共用（NF 也含 ConfigManager 清洗）", b"sanitizeCredential" in class_blob(nf, "cn/kerong/penguin/spigot/manager/ConfigManager"))

    # ---------- HB. 无 /hb 短别名（保持） ----------
    check("HB-1 plugin.yml 无 aliases 行", "aliases" not in plugin_yml)
    check("HB-2 plugin.yml usage 仅 /kerong", "/kerong reload|info|panel|chs|help" in plugin_yml)
    nc_src = src("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/NeoForgeCommands.kt")
    check("HB-3 NF 主命令仅 kerong", 'MAIN_COMMAND_LABELS = listOf("kerong")' in nc_src)
    check("HB-4 NF 注册无 hb literal", 'listOf("kerong", "hb")' not in nc_src)
    hb_hits = []
    op_re = re.compile(r"/hb\s?(chs|panel|reload|help|mem)")
    for root, _dirs, files in os.walk(REPO):
        if any(s in root for s in (os.sep + "build", os.sep + ".git", os.sep + "deps-work")):
            continue
        for fn in files:
            if not fn.endswith((".kt", ".yml", ".md", ".toml", ".json", ".html")):
                continue
            p = os.path.join(root, fn)
            try:
                with open(p, encoding="utf-8", errors="ignore") as f:
                    text = f.read()
            except Exception:
                continue
            if op_re.search(text):
                hb_hits.append(p)
    check("HB-5 全库零可操作 /hb 指引（chs/panel/reload/help/mem）", not hb_hits, "; ".join(hb_hits[:6]))
    help_md = read_text(sp, "Markdown/help.md")
    check("HB-6 分发 JAR help.md 无 /hb", "/hb" not in help_md and "/kerong help" in help_md)
    check("HB-7 分发 JAR config.yml 无 /hb", "/hb" not in cfg)
    check("HB-8 配置注释重连指引已改 /kerong chs", "/kerong chs" in cfg)

    # ---------- B. 基线保持 ----------
    cbr = "cn/kerong/penguin/spigot/manager/ConfigBrokenRecovery"
    check("B1 ConfigBrokenRecovery 在位（Spigot/NF）", has(sp, cbr + ".class") and has(nf, cbr + ".class"))
    tm = "cn/kerong/penguin/spigot/stats/TickMonitor"
    check("B2 TickMonitor 在位（Spigot/NF）", has(sp, tm + ".class") and has(nf, tm + ".class"))
    tm_blob = class_blob(sp, tm)
    check("B3 TickMonitor sampledMspt/sampledTps", b"sampledMspt" in tm_blob and b"sampledTps" in tm_blob)
    check("B4 快照字段（onlinePlayers 在位，avgPingMillis 已移除见 LR-1）", b"onlinePlayers" in ps_blob)
    check("B5 CPU EWMA smoothedCpu", b"smoothedCpu" in ps_blob)
    check("B6 SparkBridge 公共接口反射", b"me.lucko.spark.api.Spark" in sb_blob)
    check("B7 spark 探测第四态 NoStatsOnThisCore", b"NoStatsOnThisCore" in sc_blob and b"NoStatsOnThisCore" in ks_blob)
    check("B8 探测失败态保留（真故障仍警告）", b"Failed" in sc_blob)
    check("B9 GroupAtTracker/RecentMessageGate/ReplySeqGuard 在位",
          has(sp, "cn/kerong/bot/GroupAtTracker.class") and
          has(sp, "cn/kerong/bot/RecentMessageGate.class") and
          has(sp, "cn/kerong/bot/ReplySeqGuard.class"))
    check("B10 GlobalOpacity 在位", has(sp, "cn/kerong/penguin/spigot/render/GlobalOpacity.class"))
    check("B11 512KB 日志轮转", b"resolveRotated" in class_blob(sp, "cn/kerong/bot/tools/PluginFileLog"))
    check("B12 包结构无旧包名", not any(n.startswith("cn/huohuas001") for n in sp.namelist()))
    check("B13 Spigot 目录条目 >700（SDK 组件扫描依赖）", sum(1 for n in sp.namelist() if n.endswith("/")) > 700)
    check("B14 ConfigFileRebuilder 在位", has(sp, "cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.class"))
    check("B15 主类路径 plugin.yml", "cn.kerong.penguin.spigot.KERONGSpigot" in plugin_yml)
    check("B16 NF 主类", has(nf, "cn/kerong/penguin/neoforge/KERONGNeoForge.class"))
    check("B17 无液态玻璃残片（style 键）", "render.performance.style" not in cfg)
    check("B18 性能缓存默认 60", "cache-seconds: 60" in cfg)
    check("B19 NF snakeyaml 重定位在位", has(nf, "cn/kerong/shaded/org/yaml/snakeyaml/Yaml.class"))
    sp_size = os.path.getsize(SPIGOT)
    nf_size = os.path.getsize(NEOFORGE)
    check("B20 Spigot JAR 体积 19~21.5MB", 19_000_000 < sp_size < 21_500_000, f"{sp_size}")
    check("B21 NF JAR 体积 17.5~20.5MB", 17_500_000 < nf_size < 20_500_000, f"{nf_size}")
    check("B22 配置五项注释 + 内置采样器说明保持",
          "固定五项" in cfg and "内置采样器" in cfg)

print(f"\n========== 1.6.7.8 门禁：{passed} 通过 / {failed} 失败 / {passed + failed} 总计 ==========")
sys.exit(0 if failed == 0 else 1)

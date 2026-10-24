#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
1.6.7.7 门禁：双端分发 JAR 内容断言（1.6.7.6 基线保持 + 本轮四项改动新断言）。

1.6.7.7 新增：
  TC.  TPS 物理上限 20 三层封顶（反射 firstTps / SparkBridge pollTps /
       capture 工厂 safeTps——修复「查性能 TPS 飙到 300+」；1.6.7.6 覆盖版
       宣称过但未实装，本轮真正落位）
  PG.  延迟采集 0 合法（双端 ping >= 0 计数）+ 延迟卡区分「暂不可读」
       与「暂无玩家」（修复「延迟暂不可读」）
  SD.  spark 探测强化（findSparkPlugin 大小写不敏感 + findStraySparkFiles
       目录扫描 → NotLoaded 态带排查指引）
  SN.  内置 spark 通道重做（SparkBridge.bundledSparkClassLoader 三候选
       加载器探测 + ProbeOutcome.Installed 带 bundled 来源标记——
       Paper 1.21+ 系核心内置 spark 不注册插件表，Purpur 26.2 实测
       「装了 spark 检测不到」的真正根因；1.6.7.6 覆盖版功能在源码
       仓库丢失后重做合入，1.6.7.7 起源码与官网口径一致）
  CR.  凭据清洗重做（sanitizeCredential + trimInvisible 剖 20 个不可见
       码位 + 污染警告——appid invalid 100007 根因之一；1.6.7.6 覆盖版
       功能重做合入）
  HB.  移除 /hb 短别名（plugin.yml 无 aliases；NeoForgeCommands 仅 kerong；
       源码 / 资源 / 用户文案零 /hb 残留）
  V.   版本 1.6.7.7 五处

基线保持（1.6.7.6 全部关键项）：ConfigBrokenRecovery / TickMonitor /
性能快照新字段 / 渲染器六项卡 / spark 探测第四态 / SparkBridge 公共接口
反射 / GlobalOpacity / 512KB 轮转 / 包结构 / 目录条目与体积。
"""
import os
import re
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(REPO, "build", "dist")
SPIGOT = os.path.join(DIST, "KERONGPenguin_Spigot-1.6.7.7.jar")
NEOFORGE = os.path.join(DIST, "KERONGPenguin_NeoForge-1.21.1-1.6.7.7.jar")

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

    # ---------- V. 版本 1.6.7.7 ----------
    plugin_yml = read_text(sp, "plugin.yml")
    check("V1 Spigot plugin.yml 版本 1.6.7.7", "version: '1.6.7.7'" in plugin_yml, plugin_yml.splitlines()[1])
    toml = read_text(nf, "META-INF/neoforge.mods.toml")
    check("V2 NeoForge mods.toml 版本 1.6.7.7", 'version = "1.6.7.7"' in toml)
    nf_main = class_blob(nf, "cn/kerong/penguin/neoforge/KERONGNeoForge")
    check("V3 NF MOD_VERSION 1.6.7.7", b"1.6.7.7" in nf_main)
    check("V4 无 1.6.7.6 版本残留（plugin.yml）", "1.6.7.6" not in plugin_yml)
    check("V5 无 1.6.7.6 版本残留（mods.toml）", "1.6.7.6" not in toml)
    root_gradle = src("build.gradle.kts")
    check("V6 根 build.gradle.kts 1.6.7.7", 'version = "1.6.7.7"' in root_gradle)
    nf_gradle = src("server-NeoForge/build.gradle.kts")
    check("V7 NF build.gradle.kts 1.6.7.7", 'version = "1.6.7.7"' in nf_gradle)

    # ---------- TC. TPS 物理上限 20 三层封顶 ----------
    ps_blob = class_blob(sp, "cn/kerong/bot/PerformanceSnapshot")
    # 字节码层：capture 工厂含 coerceAtMost 调用与 20.0 double 常量（0x4034000000000000）
    check("TC-1 capture 工厂 coerceAtMost 在位", b"coerceAtMost" in ps_blob)
    check("TC-2 20.0 常量（IEEE754 0x4034...）", b"\x40\x34\x00\x00\x00\x00\x00\x00" in ps_blob)
    ps_src = src("common-Bot/src/main/kotlin/cn/kerong/bot/KERONG.kt")
    check("TC-3 capture 工厂 safeTps 源码断言", "val safeTps = tps?.coerceAtMost(20.0)" in ps_src)
    check(
        "TC-4 firstTps 出口封顶源码断言",
        "value.firstOrNull()?.coerceAtMost(20.0)" in ps_src and
        "(value.firstOrNull() as? Number)?.toDouble()?.coerceAtMost(20.0)" in ps_src,
    )
    sb_blob = class_blob(sp, "cn/kerong/penguin/spigot/stats/SparkBridge")
    check("TC-5 SparkBridge pollTps 封顶（coerceAtMost + 20.0）",
          b"coerceAtMost" in sb_blob and b"\x40\x34\x00\x00\x00\x00\x00\x00" in sb_blob)
    sb_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/SparkBridge.kt")
    check("TC-6 pollTps 源码断言", 'listOf("MINUTES_1", "SECONDS_10"),\n        )?.coerceAtMost(20.0)' in sb_src)

    # ---------- PG. 延迟采集与展示 ----------
    ks_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/KERONGSpigot.kt")
    check("PG-1 Spigot ping >= 0 计数源码断言", "if (ping >= 0) {" in ks_src)
    check("PG-2 Spigot 旧 > 0 判定已移除", "if (ping > 0) {" not in ks_src)
    kn_src = src("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt")
    check("PG-3 NF latency >= 0 计数源码断言", "if (value >= 0) {" in kn_src)
    check("PG-4 NF 旧 > 0 判定已移除", "if (value > 0) {" not in kn_src)
    pr_blob = class_blob(sp, "cn/kerong/penguin/spigot/render/PerformanceRenderer")
    check("PG-5 延迟卡「暂不可读」状态在位", "暂不可读".encode("utf-8") in pr_blob)
    check("PG-6 延迟卡「暂无玩家」保留", "暂无玩家".encode("utf-8") in pr_blob)
    check("PG-7 延迟读不到副说明在位", "核心不支持 getPing".encode("utf-8") in pr_blob)
    check("PG-8「暂不可读」与在线人数联动源码断言",
          "ping == null && online != null && online > 0" in src(
              "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/PerformanceRenderer.kt"))

    # ---------- SD. spark 探测强化 + 内置通道（1.6.7.6 覆盖版功能重做 + 本轮强化） ----------
    ks_blob = class_blob(sp, "cn/kerong/penguin/spigot/KERONGSpigot")
    sc_blob = class_blob(sp, "cn/kerong/penguin/spigot/stats/SparkStartupCheck")
    check("SD-1 findSparkPlugin 方法在位（大小写不敏感插件表匹配）", b"findSparkPlugin" in ks_blob)
    check("SD-2 findStraySparkFiles 方法在位（目录扫描）", b"findStraySparkFiles" in ks_blob)
    check("SD-3 大小写不敏感匹配（源码 + 字节码描述符）",
          'it.name.equals("spark", ignoreCase = true)' in ks_src and
          b"Ljava/lang/String;Z)Z" in ks_blob)
    check("SD-4 NotLoaded 态调用（文件在但未作插件加载）", b"NotLoaded" in ks_blob and b"NotLoaded" in sc_blob)
    check("SD-5 「spark 相关文件」提示 + 模组版指引", "spark.lucko.me".encode("utf-8") in sc_blob)
    check("SD-6 probe 与快照共用 findSparkPlugin", ks_src.count("findSparkPlugin()") >= 2)
    check("SD-7 旧精确匹配仅剩快路径一处", ks_src.count('getPlugin("spark")') == 1)

    # ---------- SN. 内置 spark 通道（Paper 1.21+ 系核心内置 spark，1.6.6 覆盖版重做） ----------
    check("SN-1 SparkBridge.bundledSparkClassLoader 在位", b"bundledSparkClassLoader" in sb_blob)
    check("SN-2 KERONGSpigot 内置通道探测挂载", b"bundledSparkClassLoader" in ks_blob and b"bukkitApiLoader" in ks_blob)
    check("SN-3 Installed 双参构造器（metrics + bundled 标记）",
          b"(Lcn/kerong/penguin/spigot/stats/SparkBridge$SparkMetrics;Z)V" in sc_blob and
          b"bundled" in sc_blob)
    check("SN-4 来源文案（服务器核心内置 / 插件）", "服务器核心内置".encode("utf-8") in sc_blob and "来源：".encode("utf-8") in sc_blob)
    check("SN-5 内置可读失败文案", "已随核心内置但各指标均暂不可读".encode("utf-8") in ks_blob)
    check("SN-6 性能快照同口径内置通道（源码断言）",
          "SparkBridge.bundledSparkClassLoader(bukkitApiLoader())" in ks_src)

    # ---------- CR. 凭据清洗（appid invalid 100007，1.6.6 覆盖版重做） ----------
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

    # ---------- HB. 移除 /hb 短别名 ----------
    check("HB-1 plugin.yml 无 aliases 行", "aliases" not in plugin_yml)
    check("HB-2 plugin.yml usage 仅 /kerong", "/kerong reload|info|panel|chs|help" in plugin_yml)
    nc_src = src("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/NeoForgeCommands.kt")
    check("HB-3 NF 主命令仅 kerong", 'MAIN_COMMAND_LABELS = listOf("kerong")' in nc_src)
    check("HB-4 NF 注册无 hb literal", 'listOf("kerong", "hb")' not in nc_src)
    # 全源码与资源零「可操作 /hb 指引」残留（历史更新日志里的说明性提及
    # 如「移除 /hb 别名」不在此列；README/AI-KNOWLEDGE 中的历史操作指引已同步改写）
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
    cfg = read_text(sp, "config.yml")
    check("HB-7 分发 JAR config.yml 无 /hb", "/hb" not in cfg)
    check("HB-8 配置注释重连指引已改 /kerong chs", "/kerong chs" in cfg)

    # ---------- B. 基线保持（1.6.7.6 全部关键项） ----------
    cbr = "cn/kerong/penguin/spigot/manager/ConfigBrokenRecovery"
    check("B1 ConfigBrokenRecovery 在位（Spigot/NF）", has(sp, cbr + ".class") and has(nf, cbr + ".class"))
    tm = "cn/kerong/penguin/spigot/stats/TickMonitor"
    check("B2 TickMonitor 在位（Spigot/NF）", has(sp, tm + ".class") and has(nf, tm + ".class"))
    tm_blob = class_blob(sp, tm)
    check("B3 TickMonitor sampledMspt/sampledTps", b"sampledMspt" in tm_blob and b"sampledTps" in tm_blob)
    check("B4 性能快照新字段（avgPingMillis/onlinePlayers）",
          b"avgPingMillis" in ps_blob and b"onlinePlayers" in ps_blob)
    check("B5 CPU EWMA smoothedCpu", b"smoothedCpu" in ps_blob)
    check("B6 SparkBridge playerPing 反射", b"playerPing" in sb_blob)
    check("B7 SparkBridge 公共接口反射", b"me.lucko.spark.api.Spark" in sb_blob)
    check("B8 spark 探测第四态 NoStatsOnThisCore", b"NoStatsOnThisCore" in sc_blob and b"NoStatsOnThisCore" in ks_blob)
    check("B9 探测失败态保留（真故障仍警告）", b"Failed" in sc_blob)
    check("B10 渲染器六项卡（延迟/在线人数标签）",
          "延迟（玩家平均）".encode("utf-8") in pr_blob and "在线玩家（人数）".encode("utf-8") in pr_blob)
    check("B11 延迟进度条 300ms 满值（IEEE754）", b"\x40\x72\xc0\x00\x00\x00\x00\x00" in pr_blob)
    check("B12 六卡画布高度 1034（0x040A）", b"\x04\x0a" in pr_blob)
    check("B13 GroupAtTracker/RecentMessageGate/ReplySeqGuard 在位",
          has(sp, "cn/kerong/bot/GroupAtTracker.class") and
          has(sp, "cn/kerong/bot/RecentMessageGate.class") and
          has(sp, "cn/kerong/bot/ReplySeqGuard.class"))
    check("B14 GlobalOpacity 在位", has(sp, "cn/kerong/penguin/spigot/render/GlobalOpacity.class"))
    check("B15 512KB 日志轮转", b"resolveRotated" in class_blob(sp, "cn/kerong/bot/tools/PluginFileLog"))
    check("B16 包结构无旧包名", not any(n.startswith("cn/huohuas001") for n in sp.namelist()))
    check("B17 Spigot 目录条目 >700（SDK 组件扫描依赖）", sum(1 for n in sp.namelist() if n.endswith("/")) > 700)
    check("B18 ConfigFileRebuilder 在位", has(sp, "cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.class"))
    check("B19 主类路径 plugin.yml", "cn.kerong.penguin.spigot.KERONGSpigot" in plugin_yml)
    check("B20 NF 主类", has(nf, "cn/kerong/penguin/neoforge/KERONGNeoForge.class"))
    check("B21 无液态玻璃残片（style 键）", "render.performance.style" not in cfg)
    check("B22 性能缓存默认 60", "cache-seconds: 60" in cfg)
    check("B23 NF snakeyaml 重定位在位", has(nf, "cn/kerong/shaded/org/yaml/snakeyaml/Yaml.class"))
    sp_size = os.path.getsize(SPIGOT)
    nf_size = os.path.getsize(NEOFORGE)
    check("B24 Spigot JAR 体积 19~21.5MB", 19_000_000 < sp_size < 21_500_000, f"{sp_size}")
    check("B25 NF JAR 体积 17.5~20.5MB", 17_500_000 < nf_size < 20_500_000, f"{nf_size}")
    check("B26 配置六项注释保持", "固定六项" in cfg and "延迟" in cfg)
    check("B27 Folia 内置采样器说明保持", "内置采样器" in cfg)

print(f"\n========== 1.6.7.7 门禁：{passed} 通过 / {failed} 失败 / {passed + failed} 总计 ==========")
sys.exit(0 if failed == 0 else 1)

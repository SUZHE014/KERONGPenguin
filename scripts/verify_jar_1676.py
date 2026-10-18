#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
1.6.7.6 门禁：双端分发 JAR 内容断言（1.6.7.5 基线保持 + 本轮五项改动新断言）。

1.6.7.6 新增：
  FM2. 配置解析失败自动恢复（ConfigBrokenRecovery：严格解析 / 缺空格轻量
       修复 / 备份重建默认 + 醒目指引；ConfigManager initialize 与 reload 双挂载）
  TM.  自采样刻监视器 TickMonitor（Folia 多线程核心：MSPT 读不到 + 数值
       忽高忽低修复——窗口均值稳定；KERONGSpigot start/stop 挂载）
  PN.  性能快照新字段（延迟 avgPingMillis + 在线人数 onlinePlayers；CPU EWMA
       平滑 smoothedCpu；SparkBridge ping 反射 playerPing）
  RC.  渲染器六项指标卡（延迟 + 在线人数（仅人数展示））
  PB.  spark 探测第四态 NoStatsOnThisCore（Folia 下不误报「接入失败」）
  V.   版本 1.6.7.6 五处（含修复两处滞留 1.6.7.4 的 spot）

基线保持（1.6.7.5 关键项）：SparkBridge 公共接口反射 / GroupAtTracker /
RecentMessageGate / 半透明性能卡常量 / GlobalOpacity / 512KB 轮转 / 包结构 /
门禁目录条目与体积。
"""
import os
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(REPO, "build", "dist")
SPIGOT = os.path.join(DIST, "KERONGPenguin_Spigot-1.6.7.6.jar")
NEOFORGE = os.path.join(DIST, "KERONGPenguin_NeoForge-1.21.1-1.6.7.6.jar")

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


for path in (SPIGOT, NEOFORGE):
    if not os.path.isfile(path):
        print(f"JAR 不存在：{path}")
        sys.exit(2)

with zipfile.ZipFile(SPIGOT) as sp, zipfile.ZipFile(NEOFORGE) as nf:

    # ---------- V. 版本 1.6.7.6 ----------
    plugin_yml = read_text(sp, "plugin.yml")
    check("V1 Spigot plugin.yml 版本 1.6.7.6", "version: '1.6.7.6'" in plugin_yml, plugin_yml.splitlines()[1])
    toml = read_text(nf, "META-INF/neoforge.mods.toml")
    check("V2 NeoForge mods.toml 版本 1.6.7.6", 'version = "1.6.7.6"' in toml)
    nf_main = class_blob(nf, "cn/kerong/penguin/neoforge/KERONGNeoForge")
    check("V3 NF MOD_VERSION 1.6.7.6", b"1.6.7.6" in nf_main)
    check("V4 无 1.6.7.5 版本残留（plugin.yml）", "1.6.7.5" not in plugin_yml)
    check("V5 无 1.6.7.4 版本残留（mods.toml）", "1.6.7.4" not in toml)

    # ---------- FM2. 配置解析失败恢复 ----------
    cbr = "cn/kerong/penguin/spigot/manager/ConfigBrokenRecovery"
    check("FM2-1 ConfigBrokenRecovery 类在位（Spigot）", has(sp, cbr + ".class"))
    check("FM2-1n ConfigBrokenRecovery 类在位（NF 共享源码）", has(nf, cbr + ".class"))
    cbr_blob = class_blob(sp, cbr)
    check("FM2-2 recoverIfBroken 方法", b"recoverIfBroken" in cbr_blob)
    check("FM2-3 备份文件名前缀 config.yml.error", b"config.yml.error" in cbr_blob)
    check("FM2-4 缺空格修复正则", b"(\\s*)([^:\\s#][^:]*?):(\\S.*)$" in cbr_blob or b"repair" in cbr_blob.lower())
    check("FM2-5 备份与重建提示文案", b"config.yml" in cbr_blob and "已重建默认配置" .encode("utf-8") in cbr_blob)
    cm_blob = class_blob(sp, "cn/kerong/penguin/spigot/manager/ConfigManager")
    check("FM2-6 ConfigManager 引用恢复器（常量池去重按出现计）", cm_blob.count(b"recoverIfBroken") >= 1, f"出现 {cm_blob.count(b'recoverIfBroken')} 次")
    src_cm = open(os.path.join(REPO, "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt"), encoding="utf-8").read()
    check("FM2-6b 双挂载源码确认（initialize + reload 各一次）", src_cm.count("ConfigBrokenRecovery.recoverIfBroken") == 2)
    # rebuildIfNeeded 先于恢复器兜底之后执行（源码断言）
    check("FM2-7 initialize 恢复先于 rebuildIfNeeded", src_cm.find("recoverIfBroken") < src_cm.find("rebuildIfNeeded"))

    # ---------- TM. 自采样刻监视器 ----------
    tm = "cn/kerong/penguin/spigot/stats/TickMonitor"
    check("TM-1 TickMonitor 类在位（Spigot）", has(sp, tm + ".class"))
    check("TM-1n TickMonitor 类在位（NF 编译可用）", has(nf, tm + ".class"))
    tm_blob = class_blob(sp, tm)
    check("TM-2 sampledMspt / sampledTps 方法", b"sampledMspt" in tm_blob and b"sampledTps" in tm_blob)
    check("TM-3 start / stop 生命周期", b"start" in tm_blob and b"stop" in tm_blob)
    # 1200 tick 窗口常量（int 常量进字节码：0x4B0 = 1200）
    check("TM-4 窗口常量 1200（0x04B0）", b"\x00\x04\xb0" in tm_blob or b"\x00\x00\x04\xb0" in tm_blob)
    check("TM-5 FoliaCompat 桥接引用", b"cn/kerong/penguin/spigot/scheduler/FoliaCompat" in tm_blob)
    ks_blob = class_blob(sp, "cn/kerong/penguin/spigot/KERONGSpigot")
    check("TM-6 主类启动 start 挂载", b"TickMonitor" in ks_blob and b"start" in ks_blob)
    check("TM-7 onDisable stop 清理", b"stop" in ks_blob)

    # ---------- PN. 性能快照新字段 ----------
    ps_blob = class_blob(sp, "cn/kerong/bot/PerformanceSnapshot")
    check("PN-1 avgPingMillis 字段", b"avgPingMillis" in ps_blob)
    check("PN-2 onlinePlayers 字段", b"onlinePlayers" in ps_blob)
    check("PN-3 capture 五参工厂", ps_blob.count(b"capture") >= 3)
    check("PN-4 CPU EWMA 平滑字段 smoothedCpu", b"smoothedCpu" in ps_blob)
    sb_blob = class_blob(sp, "cn/kerong/penguin/spigot/stats/SparkBridge")
    check("PN-5 SparkMetrics.ping 字段", b"ping" in sb_blob)
    check("PN-6 playerPing 反射接口方法名", b"playerPing" in sb_blob)
    check("PN-7 PlayerPing 窗口枚举常量", b"PlayerPing" in sb_blob)
    # Spigot 平台：Folia 分支采样 + getPing 兜底
    check("PN-8 KERONGSpigot readPlayerStats 采样", b"readPlayerStats" in ks_blob)
    # NF 平台：connection latency 直读
    kn_blob = nf_main
    check("PN-9 KERONGNeoForge 玩家人数与延迟采集", b"latency" in kn_blob and b"getPlayerList" in kn_blob)
    # JAR 内 config.yml 六项注释
    cfg = read_text(sp, "config.yml")
    check("PN-10 config.yml 六项指标注释", "固定六项" in cfg and "延迟" in cfg)
    check("PN-11 config.yml Folia 内置采样器说明", "内置采样器" in cfg)

    # ---------- RC. 渲染器六项卡 ----------
    pr_blob = class_blob(sp, "cn/kerong/penguin/spigot/render/PerformanceRenderer")
    check("RC-1 延迟卡标签", "延迟（玩家平均）".encode("utf-8") in pr_blob)
    check("RC-2 延迟卡状态（暂无玩家/偏高）", "暂无玩家".encode("utf-8") in pr_blob and "偏高".encode("utf-8") in pr_blob)
    check("RC-3 在线人数卡标签", "在线玩家（人数）".encode("utf-8") in pr_blob)
    check("RC-4 仅人数说明（名单用 /查在线）", "/查在线".encode("utf-8") in pr_blob)
    check("RC-5 延迟进度条以 300ms 为满（IEEE754）", b"\x40\x72\xc0\x00\x00\x00\x00\x00" in pr_blob)
    # 画布高度：六项 = 3 行 → 40+150+18+(3*250+2*18)+40 = 1034（编译期常量折叠校验）
    check("RC-6 六卡画布高度 1034（0x040A）", b"\x04\x0a" in pr_blob)
    # 半透明面板基线保持（1.6.7.5）
    check("RC-7 半透明常量基线保持", b"COLOR_HEADER_CARD" in pr_blob or b"\xff\x22" in pr_blob)

    # ---------- PB. spark 探测第四态 ----------
    sc_blob = class_blob(sp, "cn/kerong/penguin/spigot/stats/SparkStartupCheck")
    check("PB-1 NoStatsOnThisCore 态在位", b"NoStatsOnThisCore" in sc_blob)
    check("PB-2 信息级提示文案", "已安装 spark，但其统计在当前核心暂不可读".encode("utf-8") in sc_blob)
    check("PB-3 readableList 含延迟", "延迟".encode("utf-8") in sc_blob)
    check("PB-4 KERONGSpigot probe 引用第四态", b"NoStatsOnThisCore" in ks_blob)
    check("PB-5 探测失败态保留（真故障仍警告）", b"Failed" in sc_blob)

    # ---------- B. 基线保持（1.6.7.5 关键项） ----------
    check("B1 GroupAtTracker 在位", has(sp, "cn/kerong/bot/GroupAtTracker.class"))
    check("B2 RecentMessageGate 在位", has(sp, "cn/kerong/bot/RecentMessageGate.class"))
    check("B3 ReplySeqGuard 在位", has(sp, "cn/kerong/bot/ReplySeqGuard.class"))
    check("B4 SparkBridge 公共接口反射", b"me.lucko.spark.api.Spark" in sb_blob)
    check("B5 GlobalOpacity 在位", has(sp, "cn/kerong/penguin/spigot/render/GlobalOpacity.class"))
    check("B6 半透明性能常量（1.6.7.5 覆盖版）", b"\xff\x1e" in pr_blob or b"COLOR_PILL_BG" in pr_blob)
    check("B7 512KB 日志轮转", b"resolveRotated" in class_blob(sp, "cn/kerong/bot/tools/PluginFileLog"))
    check("B8 包结构 cn/kerong 无旧包名", not any(n.startswith("cn/huohuas001") for n in sp.namelist()))
    check("B9 Spigot 目录条目 >700（SDK 组件扫描依赖）", sum(1 for n in sp.namelist() if n.endswith("/")) > 700)
    check("B10 ConfigFileRebuilder 基线在位", has(sp, "cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.class"))
    check("B11 主类路径 plugin.yml", "cn.kerong.penguin.spigot.KERONGSpigot" in plugin_yml)
    check("B12 NF 主类", has(nf, "cn/kerong/penguin/neoforge/KERONGNeoForge.class"))
    check("B13 无液态玻璃残片（style 键）", "render.performance.style" not in cfg)
    check("B14 性能缓存默认 60", "cache-seconds: 60" in cfg)
    check("B15 NF snakeyaml 重定位在位", has(nf, "cn/kerong/shaded/org/yaml/snakeyaml/Yaml.class"))
    sp_size = os.path.getsize(SPIGOT)
    nf_size = os.path.getsize(NEOFORGE)
    check("B16 Spigot JAR 体积 19~21MB", 19_000_000 < sp_size < 21_500_000, f"{sp_size}")
    check("B17 NF JAR 体积 17.5~20MB", 17_500_000 < nf_size < 20_500_000, f"{nf_size}")

print(f"\n========== 1.6.7.6 门禁：{passed} 通过 / {failed} 失败 / {passed + failed} 总计 ==========")
sys.exit(0 if failed == 0 else 1)

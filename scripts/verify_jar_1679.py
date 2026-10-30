#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
1.6.7.9 门禁：双端分发 JAR 内容断言（1.6.7.8 基线保持 + 本轮改动新断言）。

1.6.7.9 新增：
  PM.  快捷面板「先删后建」（修复创建面板失败 HTTP 400 code 30013 超出
       数量限制）：同步逻辑改为先删除全部已有面板（含非本插件残留）再
       创建；创建遇 30013（含 40030013 形态）自动清空重建；错误码归一化
       （30009/30013/30020）与如实人话提示；旧 PUT 原地更新路径整体移除
  UC.  更新检查镜像回退：官网域名主源 → 官网服务器 IP 直连镜像
       （Host 头 + 证书域名放行）多源依次尝试；update-check.mirror-url
       可自定义 / off 关闭；全部失败一句话带关闭指引
  MV.  NeoForge 多版本适配（MC 1.20.4+：1.20.4 / 1.20.6 / 1.21.x）：
       编译目标降到 NeoForge 20.4.253 / FML 2.0.17 / bus 7.2.0；
       mods.toml 与 neoforge.mods.toml 双声明（段头历史笔误修复）；
       版本范围 minecraft [1.20.4,1.22) / neoforge [20.4.0,)；
       BindLoginGate 跨版本事件桥（20.4 OnGameConfigurationEvent 与
       20.5+ RegisterConfigurationTasksEvent 运行时探测，Event 基类
       监听 + ICustomConfigurationTask 跨版本桥梁）；ServerChatEvent
       getRawText 三代签名一致直接调用
  V.   版本 1.6.7.9 五处

基线保持（1.6.7.8 关键项）：无 /hb / 延迟指标移除 / TPS 三层封顶 /
spark 探测五态 / 凭据清洗 / ConfigBrokenRecovery / TickMonitor /
GlobalOpacity / 512KB 轮转 / 包结构 / 目录条目与体积。
"""
import os
import re
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = os.path.join(REPO, "build", "dist")
SPIGOT = os.path.join(DIST, "KERONGPenguin_Spigot-1.6.7.9.jar")
NEOFORGE = os.path.join(DIST, "KERONGPenguin_NeoForge-1.20.4-1.6.7.9.jar")

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

    # ---------- V. 版本 1.6.7.9 五处 ----------
    plugin_yml = read_text(sp, "plugin.yml")
    check("V1 Spigot plugin.yml 版本 1.6.7.9", "version: '1.6.7.9'" in plugin_yml,
          plugin_yml.splitlines()[1] if len(plugin_yml.splitlines()) > 1 else "")
    toml_modern = read_text(nf, "META-INF/neoforge.mods.toml")
    toml_legacy = read_text(nf, "META-INF/mods.toml")
    check("V2 NeoForge neoforge.mods.toml 版本 1.6.7.9", 'version = "1.6.7.9"' in toml_modern)
    check("V3 NeoForge mods.toml 版本 1.6.7.9（双声明）", 'version = "1.6.7.9"' in toml_legacy)
    nf_main = class_blob(nf, "cn/kerong/penguin/neoforge/KERONGNeoForge")
    check("V4 NF MOD_VERSION 1.6.7.9", b"1.6.7.9" in nf_main)
    check("V5 根 build.gradle.kts 版本 1.6.7.9", 'version = "1.6.7.9"' in src("build.gradle.kts"))
    check("V6 NF build.gradle.kts 版本 1.6.7.9",
          'version = "1.6.7.9"' in src("server-NeoForge/build.gradle.kts"))
    check("V7 无 1.6.7.8 版本残留（plugin.yml）", "1.6.7.8" not in plugin_yml)
    check("V8 无 1.6.7.8 版本残留（mods.toml）",
          "1.6.7.8" not in toml_modern and "1.6.7.8" not in toml_legacy)

    # ---------- PM. 快捷面板先删后建 ----------
    menu_sp = class_blob(sp, "cn/kerong/bot/MenuManager")
    menu_nf = class_blob(nf, "cn/kerong/bot/MenuManager")
    for tag, blob in (("Spigot", menu_sp), ("NeoForge", menu_nf)):
        check(f"PM1 {tag} createPanelWithLimitRecovery 在位", b"createPanelWithLimitRecovery" in blob)
        check(f"PM2 {tag} deleteAllPanels 在位", b"deleteAllPanels" in blob)
        check(f"PM3 {tag} 30013 常量字面量", b"30013" in blob or b"\x00\x75\x65" in blob)
        check(f"PM4 {tag} 自动清空重建提示文案", "自动清空全部面板后重建".encode("utf-8") in blob)
        check(f"PM5 {tag} 超出面板数量限制人话", "超出面板数量限制".encode("utf-8") in blob)
        check(f"PM6 {tag} 关闭指引（面板重试）", "/kerong panel".encode("utf-8") in blob)
        check(f"PM7 {tag} 旧 updatePanel 已移除", b"updatePanel" not in blob)
        check(f"PM8 {tag} 旧 fetchPanelDetail 已移除", b"fetchPanelDetail" not in blob)
        check(f"PM9 {tag} PUT 请求字面量已移除", b'"PUT"' not in blob and b"PUT" not in
              re.findall(rb'"[A-Z]{4,6}"', blob))
    # 源码断言：先删后建顺序 + 归一化
    menu_src = src("common-Bot/src/main/kotlin/cn/kerong/bot/MenuManager.kt")
    check("PM10 源码：deleteAllPanels 先于 createPanelWithLimitRecovery",
          menu_src.index("deleteAllPanels(authHeader)") < menu_src.index("createPanelWithLimitRecovery(authHeader"))
    check("PM11 源码：错误码归一化 400 前缀折算",
          "code - 40_000_000" in menu_src)
    check("PM12 源码：30013 分支触发清空重建",
          "PANEL_ERR_LIMIT && attempt < RETRY_TIMES - 1" in menu_src)
    check("PM13 源码：删除失败如实计数", "成功 $deleted 个、失败 $failed 个" in menu_src)

    # ---------- UC. 更新检查镜像回退 ----------
    upd_sp = class_blob(sp, "cn/kerong/bot/UpdateChecker")
    upd_nf = class_blob(nf, "cn/kerong/bot/UpdateChecker")
    for tag, blob in (("Spigot", upd_sp), ("NeoForge", upd_nf)):
        check(f"UC1 {tag} 内置镜像常量（IP 直连）",
              b"https://156.233.228.248/penguin/version.json" in blob)
        check(f"UC2 {tag} 镜像 Host 头常量", b"kerong.xin" in blob)
        check(f"UC3 {tag} mirror-url 配置键", b"update-check.mirror-url" in blob)
        check(f"UC4 {tag} off/false/none 关闭语义", b"none" in blob)
        check(f"UC5 {tag} 关闭指引文案",
              "update-check.enabled: false".encode("utf-8") in blob)
        check(f"UC6 {tag} 镜像命中留档文案", "已由镜像源完成检查".encode("utf-8") in blob)
        check(f"UC7 {tag} HostnameVerifier 桥接", b"setHostnameVerifier" in blob)
        check(f"UC8 {tag} 多源循环（已尝试源汇总）", "已尝试源".encode("utf-8") in blob)
    upd_src = src("common-Bot/src/main/kotlin/cn/kerong/bot/UpdateChecker.kt")
    check("UC9 源码：多源 withIndex 循环回退", "sources.withIndex()" in upd_src)
    check("UC10 源码：镜像放行仅跳过主机名比对",
          "hostnameVerifier = javax.net.ssl.HostnameVerifier" in upd_src)
    check("UC11 ConfigManager mirror-url 默认值",
          '"update-check.mirror-url" to ""' in src(
              "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt"))
    cfg_sp = read_text(sp, "config.yml")
    cfg_nf = read_text(nf, "config.yml")
    check("UC12 Spigot config mirror-url 注释", "mirror-url" in cfg_sp and "镜像源" in cfg_sp)
    check("UC13 NeoForge config mirror-url 注释", "mirror-url" in cfg_nf and "镜像源" in cfg_nf)

    # ---------- MV. NeoForge 多版本适配 ----------
    check("MV1 mods.toml 在位（1.20.4 读取）", has(nf, "META-INF/mods.toml"))
    check("MV2 neoforge.mods.toml 在位（1.20.5+ 读取）", has(nf, "META-INF/neoforge.mods.toml"))
    check("MV3 双 toml 内容一致", toml_modern == toml_legacy)
    check("MV4 段头笔误已修复（无 [ods]]）",
          "[ods]]" not in toml_modern and "[ods]]" not in toml_legacy)
    check("MV5 [[mods]] 数组表段头", "[[mods]]" in toml_modern)
    check("MV6 minecraft 版本范围 [1.20.4,1.22)",
          "[1.20.4,1.22)" in toml_modern)
    check("MV7 neoforge 依赖范围 [20.4.0,)", "[20.4.0,)" in toml_modern)
    gate = class_blob(nf, "cn/kerong/penguin/neoforge/BindLoginGate")
    check("MV8 20.5+ 事件类名探测串",
          b"net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent" in gate)
    check("MV9 20.4 事件类名探测串",
          b"net.neoforged.neoforge.network.event.OnGameConfigurationEvent" in gate)
    check("MV10 ICustomConfigurationTask 跨版本桥梁（接口引用）",
          b"net/neoforged/neoforge/network/configuration/ICustomConfigurationTask" in gate)
    check("MV11 Event 基类监听器（BindGateBusListener）",
          has(nf, "cn/kerong/penguin/neoforge/BindLoginGate$BindGateBusListener.class"))
    check("MV12 反射桥 getListener/register 方法名",
          b"getListener" in gate and b"register" in gate)
    check("MV13 未知形态放行降级提示",
          "退化为加入后踢出".encode("utf-8") in gate)
    events = class_blob(nf, "cn/kerong/penguin/neoforge/NeoForgeEvents")
    check("MV14 ServerChatEvent.getRawText 直接调用（三代签名一致）",
          b"getRawText" in events)
    check("MV15 KERONGNeoForge serverVersion 兜底无版本硬编码",
          b"NeoForge 1.21.1" not in nf_main)
    build_nf = src("server-NeoForge/build.gradle.kts")
    check("MV16 NF 构建头注释多版本说明", "1.20.4 / 1.20.6 / 1.21.x" in build_nf)
    check("MV17 NF 产物名 MC 段为最低支持版本",
          os.path.basename(NEOFORGE).startswith("KERONGPenguin_NeoForge-1.20.4-"))

    # ---------- B. 基线保持（1.6.7.8 关键项） ----------
    all_sp = b"".join(zf.read(n) for zf in (sp,) for n in zf.namelist() if n.endswith(".class"))
    all_nf = b"".join(nf.read(n) for n in nf.namelist() if n.endswith(".class"))
    check("B1 无 /hb 可操作指引（Spigot）",
          not re.search(rb"/hb\s*(chs|panel|reload|help|mem)", all_sp))
    check("B2 无 /hb 可操作指引（NeoForge）",
          not re.search(rb"/hb\s*(chs|panel|reload|help|mem)", all_nf))
    check("B3 延迟指标无残留 avgPingMillis（Spigot）", b"avgPingMillis" not in all_sp)
    check("B4 延迟指标无残留 avgPingMillis（NeoForge）", b"avgPingMillis" not in all_nf)
    check("B5 无旧包名残留（Spigot）", not has(sp, "cn/huohuas001/"))
    check("B6 无旧包名残留（NeoForge）", not has(nf, "cn/huohuas001/"))
    check("B7 ConfigBrokenRecovery 在位", has(sp, "cn/kerong/penguin/spigot/manager/ConfigBrokenRecovery.class"))
    check("B8 TickMonitor 在位", has(sp, "cn/kerong/penguin/spigot/stats/TickMonitor.class"))
    check("B9 GroupAtTracker 在位", has(sp, "cn/kerong/bot/GroupAtTracker.class"))
    check("B10 GlobalOpacity 在位", has(sp, "cn/kerong/penguin/spigot/render/GlobalOpacity.class"))
    check("B11 help.md 在位（双端）", has(sp, "Markdown/help.md") and has(nf, "Markdown/help.md"))
    dir_sp = sum(1 for n in sp.namelist() if n.endswith("/"))
    dir_nf = sum(1 for n in nf.namelist() if n.endswith("/"))
    check("B12 Spigot 目录条目 > 500", dir_sp > 500, str(dir_sp))
    check("B13 NeoForge 目录条目 > 500", dir_nf > 500, str(dir_nf))
    size_sp = os.path.getsize(SPIGOT)
    size_nf = os.path.getsize(NEOFORGE)
    check("B14 Spigot 体积量级 15~25MB", 15 << 20 < size_sp < 25 << 20, str(size_sp))
    check("B15 NeoForge 体积量级 15~25MB", 15 << 20 < size_nf < 25 << 20, str(size_nf))
    spark_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/SparkBridge.kt")
    check("B16 spark 探测内置通道保持（bundledSparkClassLoader）",
          "bundledSparkClassLoader" in spark_src)
    cfgm_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
    check("B17 凭据清洗保持（sanitizeCredential）", "sanitizeCredential" in cfgm_src)
    check("B18 512KB 日志轮转保持",
          "ROTATE_THRESHOLD_BYTES" in src("common-Bot/src/main/kotlin/cn/kerong/bot/tools/PluginFileLog.kt"))
    check("B19 Bukkit 流重载 Reader 重载保持（1.6.7.2 坑）",
          "loadConfiguration" in src("server-NeoForge/src/main/kotlin/org/bukkit/configuration/file/YamlConfiguration.kt"))
    check("B20 配置版本保持 22", "config-version" in cfg_sp and "22" in
          re.search(r"config-version:\s*(\d+)", cfg_sp).group(0))

print()
print(f"== verify_jar_1679 结果：PASS {passed} / FAIL {failed} ==")
sys.exit(1 if failed else 0)

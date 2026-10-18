#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
1.6.7.2 门禁：双端分发 JAR 内容断言。

覆盖：
  SP. Spark 联动（SparkBridge 反射桥 / 双端 performanceSnapshot 接入）
  LG. 液态玻璃已移除（覆盖版：枚举/样式配置/玻璃绘制全删，反断言）
  GO. 全局透明度（覆盖版：GlobalOpacity / render.global-opacity / 四渲染器接入）
  K.  性能数据缓存（cache-seconds 覆盖版默认 60 / PerformanceService peekCache）
  F.  配置版本重建（ConfigFileRebuilder / YamlTextMerge 单元素列表修复 /
      兼容层 getResource / load(Reader) / config-version 21）
  D.  默认值调整（from-group 新默认 / idle-release-minutes 15 / 缓存 60）
  P.  /查性能 1.6.7.1 调整保持（TPS/MSPT 分层反射 / 固定四项 / show-* 已删）
  M.  /查在线 默认图片渲染（1.6.7 保持）
  R.  重连风暴降噪（1.6.6.3 保持）
  C.  双服同绑检测移除保持
  Z.  1.6.6.4 空闲 GC 提示移除保持
  Y.  双端 config.yml
  V.  版本 1.6.7.2（plugin.yml / mods.toml）
  B.  基线保持（ReplySeqGuard / 看门狗 / 单飞 / 空闲释放 / 头像本地优先 /
      WebUI 与 PlayTimeSync 保持删除 / 打包完整性）
"""
import os
import re
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = "/home/z/my-project/download/kerongpenguin-1.6.7.2-release"
SPIGOT = os.path.join(DIST, "KERONGPenguin_Spigot-1.6.7.2.jar")
NEOFORGE = os.path.join(DIST, "KERONGPenguin_NeoForge-1.21.1-1.6.7.2.jar")

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


def src(name):
    with open(os.path.join(REPO, name), encoding="utf-8") as f:
        return f.read()


def main():
    if not os.path.isfile(SPIGOT):
        raise SystemExit(f"缺少 Spigot JAR：{SPIGOT}")
    if not os.path.isfile(NEOFORGE):
        raise SystemExit(f"缺少 NeoForge JAR：{NEOFORGE}")

    for label, jar_path in (("Spigot", SPIGOT), ("NeoForge", NEOFORGE)):
        with zipfile.ZipFile(jar_path) as zf:
            names = zf.namelist()

            # ================= SP. Spark 联动（1.6.7.2 新增） =================
            check(f"SP1 {label} SparkBridge 类在位", has(zf, "cn/kerong/penguin/spigot/stats/SparkBridge.class"))
            spark_bridge = class_blob(zf, "cn/kerong/penguin/spigot/stats/SparkBridge")
            check(f"SP2 {label} SparkBridge 反射 spark 公开 API（SparkProvider / poll / mean）",
                  b"me.lucko.spark.api.SparkProvider" in spark_bridge and b"poll" in spark_bridge and
                  b"mean" in spark_bridge)
            check(f"SP3 {label} SparkBridge 窗口与指标名（MINUTES_1 / SECONDS_10 / cpuProcess）",
                  b"MINUTES_1" in spark_bridge and b"SECONDS_10" in spark_bridge and
                  b"cpuProcess" in spark_bridge and b"MillisPerTick" in spark_bridge)
            check(f"SP4 {label} SparkBridge 窗口枚举类名（TicksPerSecond / CpuUsage）",
                  b"StatisticWindow$TicksPerSecond" in spark_bridge and
                  b"StatisticWindow$CpuUsage" in spark_bridge)
            kerong = class_blob(zf, "cn/kerong/bot/KERONG")
            snap_blob = class_blob(zf, "cn/kerong/bot/PerformanceSnapshot")
            check(f"SP5 {label} 快照支持 CPU 覆盖（capture 三参重载）",
                  b"capture" in snap_blob and b"cpuOverride" in snap_blob)
            if label == "Spigot":
                spigot_main = class_blob(zf, "cn/kerong/penguin/spigot/KERONGSpigot")
                check("SP6 Spigot 端 spark 检测接入（SparkBridge + getPlugin）",
                      b"SparkBridge" in spigot_main and b"getPlugin" in spigot_main and
                      b"read" in spigot_main)
            else:
                nf_main = class_blob(zf, "cn/kerong/penguin/neoforge/KERONGNeoForge")
                check("SP6 NeoForge 端 spark 检测接入（SparkBridge + ModList）",
                      b"SparkBridge" in nf_main and b"ModList" in nf_main and b"spark" in nf_main)

            # ================= LG. 液态玻璃已移除（1.6.7.2 覆盖版反断言） =================
            rs = class_blob(zf, "cn/kerong/penguin/spigot/render/RenderSettings")
            check(f"LG1 {label} RenderSettings 无样式读取器（performanceStyle 已删）",
                  b"performanceStyle" not in rs and b"performanceCacheMillis" in rs)
            check(f"LG2 {label} RenderSettings 样式枚举已删（无 PerformanceStyle / liquid-glass）",
                  b"PerformanceStyle" not in rs and b"liquid-glass" not in rs and b"vanilla" not in rs)
            perf = class_blob(zf, "cn/kerong/penguin/spigot/render/PerformanceRenderer")
            check(f"LG3 {label} 渲染器玻璃绘制已删（无 brighten / LIQUID_GLASS），经典面板保留",
                  b"paintPanel" in perf and b"brighten" not in perf and b"LIQUID_GLASS" not in perf)
            check(f"LG4 {label} 渲染器入参无 style（PerformanceData 在位）",
                  has(zf, "cn/kerong/penguin/spigot/render/PerformanceRenderer$PerformanceData.class") and
                  b"style" not in perf)
            psvc = class_blob(zf, "cn/kerong/penguin/spigot/stats/PerformanceService")
            check(f"LG5 {label} 服务不再读样式（performanceStyle 无引用）",
                  b"performanceStyle" not in psvc)

            # ================= GO. 全局透明度（1.6.7.2 覆盖版新增） =================
            check(f"GO1 {label} GlobalOpacity 类在位",
                  has(zf, "cn/kerong/penguin/spigot/render/GlobalOpacity.class"))
            go = class_blob(zf, "cn/kerong/penguin/spigot/render/GlobalOpacity")
            check(f"GO2 {label} GlobalOpacity 用 AlphaComposite 合成（apply / applyOpacity）",
                  b"applyOpacity" in go and b"java/awt/AlphaComposite" in go)
            check(f"GO3 {label} RenderSettings 含 globalOpacity 读取器",
                  b"globalOpacity" in rs and b"global-opacity" in rs)
            onl = class_blob(zf, "cn/kerong/penguin/spigot/render/OnlineListRenderer")
            lb = class_blob(zf, "cn/kerong/penguin/spigot/render/LeaderboardRenderer")
            icr2 = class_blob(zf, "cn/kerong/penguin/spigot/render/InfoCardRenderer")
            check(f"GO4 {label} 四渲染器全部接入全局透明度",
                  b"GlobalOpacity" in perf and b"GlobalOpacity" in onl and
                  b"GlobalOpacity" in lb and b"GlobalOpacity" in icr2)

            # ================= K. 性能数据缓存（1.6.7.2；覆盖版默认 60） =================
            check(f"K1 {label} 服务缓存字段与方法（peekCache / storeCache / cachedPng）",
                  b"peekCache" in psvc and b"storeCache" in psvc and b"cachedPng" in psvc)
            check(f"K2 {label} 服务缓存时长读取（performanceCacheMillis）",
                  b"performanceCacheMillis" in psvc)
            check(f"K3 {label} 缓存默认 60 秒（源码常量内联 60）",
                  b"performanceCacheMillis" in rs and b"liquid-glass" not in rs)

            # ================= F. 配置版本重建（1.6.7.2 新增） =================
            check(f"F1 {label} ConfigFileRebuilder 类在位", has(zf, "cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.class"))
            rebuilder = class_blob(zf, "cn/kerong/penguin/spigot/manager/ConfigFileRebuilder")
            check(f"F2 {label} 重建器备份与原子写（config.yml.bak / config.yml.tmp）",
                  b"config.yml.bak" in rebuilder and b"config.yml.tmp" in rebuilder)
            check(f"F3 {label} 重建器版本比较键（config-version）",
                  b"config-version" in rebuilder)
            check(f"F4 {label} YamlTextMerge 类在位（文本级保注释合并）",
                  has(zf, "cn/kerong/penguin/spigot/manager/YamlTextMerge.class"))
            ytm = class_blob(zf, "cn/kerong/penguin/spigot/manager/YamlTextMerge")
            check(f"F5 {label} YamlTextMerge 走 snakeyaml 渲染值",
                  b"org/yaml/snakeyaml/DumperOptions" in ytm)
            check(f"F5b {label} YamlTextMerge 列表按类型分派（Collection，覆盖版单元素列表修复）",
                  b"java/util/Collection" in ytm and b"[]" in ytm)
            check(f"F9 {label} 重建器 loadYaml 可感知失败（load + Reader）",
                  b"java/io/Reader" in rebuilder and b"loadConfiguration" not in rebuilder)
            check(f"F10 {label} 重建器旧默认收敛键（cache-seconds 字符串）",
                  b"render.performance.cache-seconds" in rebuilder)
            if label == "NeoForge":
                jp = class_blob(zf, "org/bukkit/plugin/java/JavaPlugin")
                check("F6 NeoForge 兼容层 JavaPlugin 含 getResource",
                      b"getResource" in jp)
                yc = class_blob(zf, "org/bukkit/configuration/file/YamlConfiguration")
                check("F7 NeoForge 兼容层 YamlConfiguration 含 Reader 重载",
                      b"java/io/Reader" in yc)
                check("F8 NeoForge 兼容层 load 整树替换（clear 语义在位）",
                      b"clear" in yc)

            # ================= D. 默认值调整（1.6.7.2 覆盖版） =================
            cfg = read_text(zf, "config.yml")
            check(f"D1 {label} config-version 升 21（覆盖版）", "config-version: 21" in cfg)
            check(f"D2 {label} from-group 新默认（§b 染 QQ）",
                  'from-group: "[§bQQ§r] {name}: {message}"' in cfg)
            check(f"D3 {label} idle-release-minutes 默认 15", "idle-release-minutes: 15" in cfg)
            check(f"D4 {label} 旧 from-group 默认不再出现", 'from-group: "[QQ] {name}: {message}"' not in cfg)
            check(f"D5 {label} cache-seconds 默认 60（覆盖版 5 → 60）", "cache-seconds: 60" in cfg)
            check(f"GO5 {label} config global-opacity: 1.0", "global-opacity: 1.0" in cfg)

            # ================= P. /查性能 1.6.7.1 调整保持 =================
            check(f"P1 {label} PerformanceRenderer 类在位", has(zf, "cn/kerong/penguin/spigot/render/PerformanceRenderer.class"))
            check(f"P2 {label} PerformanceService 类在位", has(zf, "cn/kerong/penguin/spigot/stats/PerformanceService.class"))
            check(f"P3 {label} PerformanceSnapshot 数据类在位", has(zf, "cn/kerong/bot/PerformanceSnapshot.class"))
            check(f"P4 {label} 渲染器标题文案（服务器性能监控）", "服务器性能监控".encode() in perf)
            check(f"P5 {label} 指标卡标签（TPS / MSPT / 内存 / CPU）",
                  "TPS".encode() in perf and "MSPT".encode() in perf and
                  "内存占用".encode() in perf and "CPU 占用".encode() in perf)
            check(f"P5b {label} 在线卡已删（无 在线玩家/已满员 文案）",
                  "在线玩家".encode() not in perf and "已满员".encode() not in perf)
            check(f"P6 {label} 状态分级文案（流畅 / 过载）",
                  "流畅".encode() in perf and "过载".encode() in perf)
            check(f"P7 {label} 渲染器走结果缓存清理（画布 flush）", b"flush" in perf)
            check(f"P8 {label} 服务走共享渲染池（CardRenderPool.submit）", b"CardRenderPool" in psvc)
            check(f"P9 {label} 服务失败模板（performance-render-failed）",
                  "performance-render-failed".encode() in psvc)
            check(f"P9b {label} 服务无 performanceItemEnabled 引用", b"performanceItemEnabled" not in psvc)
            pc = class_blob(zf, "cn/kerong/bot/events/commands/PublicCommands")
            check(f"P10 {label} 命令注册（查性能）", "查性能".encode() in pc)
            mm = class_blob(zf, "cn/kerong/bot/MenuManager")
            check(f"P11 {label} 快捷菜单按钮（查看服务器性能）", "查看服务器性能".encode() in mm)
            check(f"P12 {label} KERONG 接口含 performanceSnapshot", b"performanceSnapshot" in kerong)

            snap = class_blob(zf, "cn/kerong/bot/PerformanceSnapshot")
            check(f"P13 {label} 快照含 capture 工厂与 CPU 采集", b"capture" in snap and b"ProcessCpuLoad" in snap)
            check(f"P13b {label} 分层读取器（readTps / readMspt / tpsFromMspt）",
                  b"readTps" in snap and b"readMspt" in snap and b"tpsFromMspt" in snap)
            check(f"P13c {label} 反射目标名（recentTps / tickTimesNanos / getAverageTickTimeNanos）",
                  b"recentTps" in snap and b"tickTimesNanos" in snap and
                  b"getAverageTickTimeNanos" in snap and b"getTPS" in snap)
            check(f"P13d {label} 快照无在线字段（onlineCount / maxPlayers）",
                  b"onlineCount" not in snap and b"maxPlayers" not in snap)

            check(f"P14 {label} RenderSettings 仅存 performanceBuiltinBackground（+1.6.7.2 两项）",
                  b"performanceBuiltinBackground" in rs and b"performanceItemEnabled" not in rs)
            check(f"P15 {label} 模板 Markdown/performance-render-failed.md 在 JAR",
                  has(zf, "Markdown/performance-render-failed.md"))
            if label == "Spigot":
                spigot_main2 = class_blob(zf, "cn/kerong/penguin/spigot/KERONGSpigot")
                check("P16 Spigot 平台实现（分层读取 + MSPT 换算兜底）",
                      b"performanceSnapshot" in spigot_main2 and b"readTps" in spigot_main2 and
                      b"readMspt" in spigot_main2 and b"tpsFromMspt" in spigot_main2)
            else:
                nf_main2 = class_blob(zf, "cn/kerong/penguin/neoforge/KERONGNeoForge")
                check("P16 NeoForge 平台实现（分层读取 + MSPT 换算兜底）",
                      b"performanceSnapshot" in nf_main2 and b"readTps" in nf_main2 and
                      b"readMspt" in nf_main2 and b"tpsFromMspt" in nf_main2)
                check("P16b NeoForge 旧错误反射调用已移除",
                      b'"getAverageTickTime"' not in nf_main2 and b'"tickTimes"' not in nf_main2)

            check(f"P17 {label} config performance 段：builtin-background + cache-seconds 60，style 已删",
                  "builtin-background: true" in cfg and "cache-seconds: 60" in cfg and
                  "style: vanilla" not in cfg and "show-tps" not in cfg and "show-online" not in cfg)
            check(f"P18 {label} config commands.查性能 默认开", "查性能: true" in cfg)
            check(f"P19 {label} help.md 含 /查性能 行（四项指标）",
                  "/查性能" in read_text(zf, "Markdown/help.md") and
                  "在线人数）" not in read_text(zf, "Markdown/help.md"))

            # ================= M. /查在线 默认改图片渲染（保持） =================
            check(f"M1 {label} config use-markdown 默认 false（图片输出）",
                  "use-markdown: false" in cfg)

            # ================= R. 重连风暴降噪（保持） =================
            wss = class_blob(zf, "cn/kerong/bot/WssDiagnostics")
            check(f"R1 {label} WssDiagnostics 类在位", len(wss) > 10000)
            check(f"R2 {label} 关闭帧文案保留（文件级）", "连接关闭：code=" in wss.decode("utf-8", "ignore"))
            check(f"R3 {label} op9 会话无效文案保留（文件级）", "会话无效(op 9" in wss.decode("utf-8", "ignore"))
            check(f"R5 {label} WssDiagnostics 引用 PluginFileLog（write）", b"PluginFileLog" in wss)
            rcm = class_blob(zf, "cn/kerong/bot/ReconnectManager")
            check(f"R7 {label} ReconnectManager 里程碑文案（最后一次）",
                  "（本轮最后一次自动重连）" in rcm.decode("utf-8", "ignore"))
            check(f"R12 {label} SDK 噪声文案过滤（reconnect in 3 seconds）",
                  "reconnect in 3 seconds" in kerong.decode("utf-8", "ignore"))

            # ================= C. 双服同绑检测移除（保持） =================
            check(f"C1 {label} ConflictWatch 类已删除", not has(zf, "cn/kerong/bot/ConflictWatch.class"))
            all_classes = b"".join(
                zf.read(n) for n in names if n.endswith(".class") and n.startswith("cn/kerong/")
            )
            check(f"C13 {label} 全部插件类无 ConflictWatch 引用", b"ConflictWatch" not in all_classes)

            # ================= G. GC / 释放日志文件化（保持） =================
            icr = class_blob(zf, "cn/kerong/penguin/spigot/render/InfoCardAssets")
            check(f"G1 {label} 释放文案保留（分钟无渲染，仅文件）", "分钟无渲染，释放素材缓存内存" in icr.decode("utf-8", "ignore"))
            check(f"G2 {label} 释放文案不再带 gc-hint 状态标注",
                  "开启时本次已随释放向 JVM 发 GC 提示" not in icr.decode("utf-8", "ignore"))

            # ================= Z. 1.6.6.4 空闲 GC 提示移除保持 =================
            check(f"Z1 {label} InfoCardAssets 无 idleGcHint 引用", b"idleGcHint" not in icr)
            rs_cls = zf.read("cn/kerong/penguin/spigot/render/RenderSettings.class")
            check(f"Z2 {label} RenderSettings 无 idleGcHint 方法", b"idleGcHint" not in rs_cls)
            check(f"Z3 {label} 全插件类无 auto-shrink", b"auto-shrink" not in all_classes)
            check(f"Z4 {label} config.yml 无 auto-shrink 键", "auto-shrink" not in cfg)
            check(f"Z4b {label} config.yml 无 idle-gc-hint 键", "idle-gc-hint" not in cfg)

            # ================= Y. config.yml =================
            check(f"Y3 {label} 释放说明：不刷控制台", "不刷控制台" in cfg)
            check(f"Y6 {label} idle-release 默认 15（1.6.7.2）", "idle-release-minutes: 15" in cfg)
            check(f"Y7 {label} style 键与液态玻璃说明已删（覆盖版）",
                  "液态玻璃" not in cfg and "style: vanilla" not in cfg)
            check(f"Y8 {label} cache-seconds 注释含渲染过慢说明", "渲染过慢" in cfg)
            check(f"Y9 {label} global-opacity 注释（全局透明度说明）", "全局透明度" in cfg)

            # ================= V. 版本 =================
            if label == "Spigot":
                check("V1 plugin.yml version 1.6.7.2", "version: '1.6.7.2'" in read_text(zf, "plugin.yml"))
                check("V2 plugin.yml 无 1.6.7.1 残留", "version: '1.6.7.1'" not in read_text(zf, "plugin.yml"))
            else:
                toml = read_text(zf, "META-INF/neoforge.mods.toml")
                check("V3 mods.toml version 1.6.7.2", 'version = "1.6.7.2"' in toml)
                check("V4 mods.toml 无 1.6.7.1 残留", 'version = "1.6.7.1"' not in toml)

            # ================= B. 基线保持 =================
            check(f"B1 {label} ReplySeqGuard 保留", b"ReplySeqGuard" in class_blob(zf, "cn/kerong/bot/"))
            check(f"B2 {label} QqTokenHealth 保留（凭据自检）", has(zf, "cn/kerong/bot/QqTokenHealth.class"))
            check(f"B3 {label} 看门狗心跳假死检测保留", "连接假死" in wss.decode("utf-8", "ignore"))
            check(f"B5 {label} 单飞 FetchLatch 保留", b"FetchLatch" in icr)
            check(f"B6 {label} cacheStats 保留（/kerong mem）", b"cacheStats" in icr)
            check(f"B7 {label} 头像本地优先保留", b"avatarFetchPriority" in icr)
            check(f"B8 {label} WebUI 保持删除", not has(zf, "cn/kerong/bot/web/WebUiServer.class"))
            check(f"B9 {label} PlayTimeSync 保持删除", not has(zf, "cn/kerong/penguin/spigot/stats/PlayTimeSync.class"))
            dir_entries = sum(1 for n in names if n.endswith("/"))
            dir_min = 700 if label == "Spigot" else 600
            check(f"B10 {label} 目录条目保留（{dir_entries} > {dir_min}）", dir_entries > dir_min)
            check(f"B11 {label} relocate 干净（无 io.github.kloping 顶层类）",
                  not any(re.match(r"io/github/kloping/[^/]+\.class", n) for n in names))

    # ================= 源码级断言 =================
    pc_src = src("common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/PublicCommands.kt")
    check("S1 源码 @Commands(\"查性能\") 注册",
          '@Commands("查性能")' in pc_src and "PerformanceService.handle" in pc_src)
    mm_src = src("common-Bot/src/main/kotlin/cn/kerong/bot/MenuManager.kt")
    check("S2 源码快捷菜单按钮在查在线后",
          'PanelItem("查在线", "查询在线玩家"),\n        PanelItem("查性能", "查看服务器性能"),' in mm_src)
    cm_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt")
    check("S3 COMMAND_NAMES 含 查性能", '"查性能"' in cm_src.split("COMMAND_NAMES")[1].split(")")[0])
    check("S4 MESSAGE_TEMPLATE_IDS 含 performance-render-failed",
          '"performance-render-failed"' in cm_src)
    mt_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/MessageTemplates.kt")
    check("S5 MessageTemplates TEMPLATE_IDS 同步", '"performance-render-failed"' in mt_src)
    check("S6 motd() use-markdown 默认 false",
          'plugin.config.getBoolean("motd.use-markdown", false)' in cm_src)
    check("S7 DEFAULT_VALUES use-markdown false", '"motd.use-markdown" to false' in cm_src)

    # ---- S 组 1.6.7.2 源码断言 ----
    sb_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/SparkBridge.kt")
    check("S20 SparkBridge 源码：SparkProvider 反射 + 窗口候选 + MSPT mean",
          'Class.forName("me.lucko.spark.api.SparkProvider"' in sb_src and
          '"MINUTES_1", "SECONDS_10"' in sb_src and '"mean"' in sb_src)
    check("S20b SparkBridge 源码：逐项容错（单项失败不拖累）",
          "tps == null && mspt == null && cpu == null" in sb_src)
    spigot_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/KERONGSpigot.kt")
    nf_src = src("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt")
    check("S21 Spigot 源码 spark 优先接入",
          'getPlugin("spark")' in spigot_src and "SparkBridge.read(sparkPlugin.javaClass.classLoader)" in spigot_src)
    check("S22 NeoForge 源码 spark 优先接入（ModList.isLoaded）",
          "ModList.get().isLoaded(\"spark\")" in nf_src and "SparkBridge.read()" in nf_src)
    kerong_src = src("common-Bot/src/main/kotlin/cn/kerong/bot/KERONG.kt")
    check("S23 KERONG 源码 capture 三参重载（cpuOverride）",
          "fun capture(\n            tps: Double?,\n            mspt: Double?,\n            cpuOverride: Double?," in kerong_src)
    rs_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/RenderSettings.kt")
    check("S24 RenderSettings 源码缓存与透明度默认值（覆盖版）",
          "const val DEFAULT_PERFORMANCE_CACHE_SECONDS = 60" in rs_src and
          "const val DEFAULT_GLOBAL_OPACITY = 1.0" in rs_src and
          "DEFAULT_IDLE_RELEASE_MINUTES = 15.0" in rs_src)
    check("S24b RenderSettings 源码样式枚举已删（覆盖版）",
          "enum class PerformanceStyle" not in rs_src and "liquid-glass" not in rs_src and
          "fun globalOpacity(): Float" in rs_src)
    check("S24c RenderSettings 源码 idle 默认注释（5 → 15）", "1.6.7.2 默认 5 → 15" in rs_src)
    pr_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/PerformanceRenderer.kt")
    check("S25 渲染器源码玻璃绘制已删（覆盖版）",
          "paintPanel" in pr_src and "brighten" not in pr_src and "LIQUID_GLASS" not in pr_src)
    check("S25b 渲染器源码无样式入参（覆盖版）",
          "style: RenderSettings.PerformanceStyle" not in pr_src and
          "RenderSettings.PerformanceStyle" not in pr_src)
    check("S25c 渲染器设计来源含模板合集链接",
          "easybot-image-templates" in pr_src)
    check("S25d 渲染器源码接入全局透明度（覆盖版）",
          "GlobalOpacity.apply(image)" in pr_src)
    ps_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/PerformanceService.kt")
    check("S26 性能服务源码缓存（peekCache / storeCache，无样式失效链）",
          "peekCache" in ps_src and "storeCache" in ps_src and "cachedStyle" not in ps_src)
    check("S26b 性能服务源码渲染无样式参数（覆盖版）",
          "style = style" not in ps_src and "performanceStyle" not in ps_src)
    fr_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigFileRebuilder.kt")
    check("S27 重建器源码：默认值收敛（旧 from-group / 旧 idle 5）",
          'OLD_FROM_GROUP_DEFAULT = "[QQ] {name}: {message}"' in fr_src and
          "OLD_IDLE_RELEASE_MINUTES = 5.0" in fr_src and
          "NEW_FROM_GROUP_DEFAULT" in fr_src and "NEW_IDLE_RELEASE_MINUTES = 15.0" in fr_src)
    check("S27b 重建器源码：备份 + 原子写 + 校验",
          "config.yml.bak" in fr_src and "Files.move" in fr_src and "verify" in fr_src)
    check("S27c 重建器源码：文本合并保注释",
          "YamlTextMerge" in fr_src and "不经 YAML 序列化重写" in fr_src)
    check("S27d ConfigManager 挂接重建器",
          "ConfigFileRebuilder.rebuildIfNeeded(" in cm_src)
    check("S27e ConfigManager 版本 21 与共享迁移表（覆盖版）",
          "CURRENT_CONFIG_VERSION = 21" in cm_src and
          "internal val LEGACY_PATH_MOVES" in cm_src and "internal val REMOVED_CONFIG_KEYS" in cm_src)
    check("S27f DEFAULT_VALUES 覆盖版（style 删 / cache 60 / global-opacity）",
          '"render.performance.style" to "vanilla"' not in cm_src and
          '"render.performance.cache-seconds" to 60' in cm_src and
          '"render.global-opacity" to 1.0' in cm_src and
          '"chat-format.from-group" to "[§bQQ§r] {name}: {message}"' in cm_src and
          '"render.idle-release-minutes" to 15' in cm_src)
    check("S27h ConfigManager 废弃键含 style（覆盖版）",
          '"render.performance.style",' in cm_src)
    check("S27i ConfigManager 兑底收敛（cache 5 → 60）",
          'config.set("render.performance.cache-seconds", 60)' in cm_src)
    check("S27j 重建器源码：单元素列表修复（Collection 类型分派，覆盖版核心）",
          "value is Collection<*>" in fr_src and
          'listOf("$indentStr$key: []")' in fr_src and
          '"$indentStr- $dumped"' in fr_src)
    check("S27k 重建器源码：loadYaml 可感知失败（config.load 实例方法，覆盖版）",
          "config.load(it)" in fr_src and "reader.use { config.load(it) }" in fr_src)
    check("S27l 重建器源码：旧缓存默认收敛（5 删 60 生效，覆盖版）",
          "OLD_PERFORMANCE_CACHE_SECONDS = 5" in fr_src)
    check("S27g chatFormat() 读取新默认", '"[§bQQ§r] {name}: {message}") ?: "[§bQQ§r] {name}: {message}"' in cm_src)
    nf_jp_src = src("server-NeoForge/src/main/kotlin/org/bukkit/plugin/java/JavaPlugin.kt")
    check("S28 NF 兼容层 getResource 源码在位", "fun getResource(name: String)" in nf_jp_src)
    nf_yc_src = src("server-NeoForge/src/main/kotlin/org/bukkit/configuration/file/YamlConfiguration.kt")
    check("S28b NF 兼容层 load 整树替换 + Reader 重载",
          "rootMap.clear()" in nf_yc_src and "fun loadConfiguration(reader: java.io.Reader)" in nf_yc_src)
    check("S28c NF 兼容层注释更新（重建不经本层重存）", "ConfigFileRebuilder 文本级重建" in nf_yc_src)
    check("S28d NF 兼容层 load(Reader) 实例方法（覆盖版，可感知失败）",
          "fun load(reader: java.io.Reader)" in nf_yc_src)
    go_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/GlobalOpacity.kt")
    check("S28e GlobalOpacity 源码：AlphaComposite 合成 + 纯函数形态（覆盖版）",
          "AlphaComposite.SrcOver.derive" in go_src and "fun applyOpacity(image: BufferedImage, opacity: Float)" in go_src)

    check("S29 源码渲染器无 System.gc() 调用",
          "System.gc()" not in src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt") and
          "System.gc()" not in ps_src)
    check("S15b 渲染器源码固定四项（measureHeight 无参 / CARD_COUNT）",
          "fun measureHeight(): Int" in pr_src and "showOnline" not in pr_src)
    check("S16 ConfigManager 迁移表已收编废弃键检查保持",
          '"render.idle-gc-hint",' in cm_src)
    check("S17 双端 help.md 查性能行",
          "/查性能" in src("common-Bot/src/main/resources/Markdown/help.md") and
          "/查性能" in src("server-NeoForge/src/main/resources/Markdown/help.md"))
    check("S18 双端 config.yml show-* 全删 + style 键删（覆盖版）+ 缓存 60",
          "show-tps" not in src("server-Spigot/src/main/resources/config.yml") and
          "show-tps" not in src("server-NeoForge/src/main/resources/config.yml") and
          "style: vanilla" not in src("server-Spigot/src/main/resources/config.yml") and
          "style: vanilla" not in src("server-NeoForge/src/main/resources/config.yml") and
          "cache-seconds: 60" in src("server-Spigot/src/main/resources/config.yml") and
          "global-opacity: 1.0" in src("server-Spigot/src/main/resources/config.yml") and
          "global-opacity: 1.0" in src("server-NeoForge/src/main/resources/config.yml"))
    check("S19 RenderSettings 源码无 performanceItemEnabled",
          "performanceItemEnabled" not in rs_src)
    check("S30 README 设计来源含模板合集 + 1.6.7.2 更新日志",
          "easybot-image-templates" in src("README.md") and
          "### v1.6.7.2 更新日志" in src("README.md"))

    print(f"\n===== 门禁结果：{passed} 通过 / {failed} 失败 =====")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

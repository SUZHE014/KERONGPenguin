#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
1.6.7.1 门禁：双端分发 JAR 内容断言。

覆盖：
  P. /查性能 1.6.7.1 调整（TPS/MSPT 分层反射修复 / 删在线卡 / 删 show-* 显隐）
  M. /查在线 默认改图片渲染（1.6.7 保持）
  R. 重连风暴降噪（1.6.6.3 保持）
  C. 双服同绑检测整体移除（1.6.6.3 保持）
  Z. 1.6.6.4 空闲 GC 提示移除保持
  G. 释放日志文件化（1.6.6.3 保持）
  Y. 双端 config.yml（性能配置块仅剩 builtin-background）
  V. 版本 1.6.7.1（plugin.yml / mods.toml）
  B. 基线保持（ReplySeqGuard / 看门狗 / 单飞 / 空闲释放 / 头像本地优先 /
     WebUI 与 PlayTimeSync 保持删除 / 打包完整性）
"""
import os
import re
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = "/home/z/my-project/download/kerongpenguin-1.6.7.1-release"
SPIGOT = os.path.join(DIST, "KERONGPenguin_Spigot-1.6.7.1.jar")
NEOFORGE = os.path.join(DIST, "KERONGPenguin_NeoForge-1.21.1-1.6.7.1.jar")

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

            # ================= P. /查性能 1.6.7.1 调整 =================
            check(f"P1 {label} PerformanceRenderer 类在位", has(zf, "cn/kerong/penguin/spigot/render/PerformanceRenderer.class"))
            check(f"P2 {label} PerformanceService 类在位", has(zf, "cn/kerong/penguin/spigot/stats/PerformanceService.class"))
            check(f"P3 {label} PerformanceSnapshot 数据类在位", has(zf, "cn/kerong/bot/PerformanceSnapshot.class"))
            perf = class_blob(zf, "cn/kerong/penguin/spigot/render/PerformanceRenderer")
            check(f"P4 {label} 渲染器标题文案（服务器性能监控）", "服务器性能监控".encode() in perf)
            check(f"P5 {label} 指标卡标签（TPS / MSPT / 内存 / CPU）",
                  "TPS".encode() in perf and "MSPT".encode() in perf and
                  "内存占用".encode() in perf and "CPU 占用".encode() in perf)
            check(f"P5b {label} 在线卡已删（无 在线玩家/已满员 文案）",
                  "在线玩家".encode() not in perf and "已满员".encode() not in perf)
            check(f"P6 {label} 状态分级文案（流畅 / 过载）",
                  "流畅".encode() in perf and "过载".encode() in perf)
            check(f"P7 {label} 渲染器走结果缓存清理（画布 flush）", b"flush" in perf)
            psvc = class_blob(zf, "cn/kerong/penguin/spigot/stats/PerformanceService")
            check(f"P8 {label} 服务走共享渲染池（CardRenderPool.submit）", b"CardRenderPool" in psvc)
            check(f"P9 {label} 服务失败模板（performance-render-failed）",
                  "performance-render-failed".encode() in psvc)
            check(f"P9b {label} 服务无 performanceItemEnabled 引用", b"performanceItemEnabled" not in psvc)
            pc = class_blob(zf, "cn/kerong/bot/events/commands/PublicCommands")
            check(f"P10 {label} 命令注册（查性能）", "查性能".encode() in pc)
            mm = class_blob(zf, "cn/kerong/bot/MenuManager")
            check(f"P11 {label} 快捷菜单按钮（查看服务器性能）", "查看服务器性能".encode() in mm)
            kerong = class_blob(zf, "cn/kerong/bot/KERONG")
            check(f"P12 {label} KERONG 接口含 performanceSnapshot", b"performanceSnapshot" in kerong)

            # 快照：2 参 capture + 分层反射读取器（1.6.7.1 修复核心）
            snap = class_blob(zf, "cn/kerong/bot/PerformanceSnapshot")
            check(f"P13 {label} 快照含 capture 工厂与 CPU 采集", b"capture" in snap and b"ProcessCpuLoad" in snap)
            check(f"P13b {label} 分层读取器（readTps / readMspt / tpsFromMspt）",
                  b"readTps" in snap and b"readMspt" in snap and b"tpsFromMspt" in snap)
            check(f"P13c {label} 反射目标名（recentTps / tickTimesNanos / getAverageTickTimeNanos）",
                  b"recentTps" in snap and b"tickTimesNanos" in snap and
                  b"getAverageTickTimeNanos" in snap and b"getTPS" in snap)
            check(f"P13d {label} 快照无在线字段（onlineCount / maxPlayers）",
                  b"onlineCount" not in snap and b"maxPlayers" not in snap)

            rs = class_blob(zf, "cn/kerong/penguin/spigot/render/RenderSettings")
            check(f"P14 {label} RenderSettings 仅存 performanceBuiltinBackground",
                  b"performanceBuiltinBackground" in rs and b"performanceItemEnabled" not in rs)
            check(f"P15 {label} 模板 Markdown/performance-render-failed.md 在 JAR",
                  has(zf, "Markdown/performance-render-failed.md"))
            if label == "Spigot":
                spigot_main = class_blob(zf, "cn/kerong/penguin/spigot/KERONGSpigot")
                check("P16 Spigot 平台实现（分层读取 + MSPT 换算兜底）",
                      b"performanceSnapshot" in spigot_main and b"readTps" in spigot_main and
                      b"readMspt" in spigot_main and b"tpsFromMspt" in spigot_main)
            else:
                nf_main = class_blob(zf, "cn/kerong/penguin/neoforge/KERONGNeoForge")
                check("P16 NeoForge 平台实现（分层读取 + MSPT 换算兜底）",
                      b"performanceSnapshot" in nf_main and b"readTps" in nf_main and
                      b"readMspt" in nf_main and b"tpsFromMspt" in nf_main)
                check("P16b NeoForge 旧错误反射调用已移除（getAverageTickTime 直调不再出现于主类）",
                      b'"getAverageTickTime"' not in nf_main and b'"tickTimes"' not in nf_main)
            cfg = read_text(zf, "config.yml")
            check(f"P17 {label} config performance 仅剩 builtin-background",
                  "builtin-background: true" in cfg and "show-tps" not in cfg and
                  "show-mspt" not in cfg and "show-memory" not in cfg and
                  "show-cpu" not in cfg and "show-online" not in cfg)
            check(f"P18 {label} config commands.查性能 默认开", "查性能: true" in cfg)
            check(f"P19 {label} help.md 含 /查性能 行（四项指标）",
                  "/查性能" in read_text(zf, "Markdown/help.md") and
                  "在线人数）" not in read_text(zf, "Markdown/help.md"))

            # ================= M. /查在线 默认改图片渲染（1.6.7 保持） =================
            check(f"M1 {label} config use-markdown 默认 false（图片输出）",
                  "use-markdown: false" in cfg)

            # ================= R. 重连风暴降噪（1.6.6.3 保持） =================
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

            # ================= C. 双服同绑检测移除（1.6.6.3 保持） =================
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
            check(f"Y6 {label} idle-release-minutes 默认 5", "idle-release-minutes: 5" in cfg)

            # ================= V. 版本 =================
            if label == "Spigot":
                check("V1 plugin.yml version 1.6.7.1", "version: '1.6.7.1'" in read_text(zf, "plugin.yml"))
                check("V2 plugin.yml 无 1.6.7 纯版本残留", "version: '1.6.7'" not in read_text(zf, "plugin.yml"))
            else:
                toml = read_text(zf, "META-INF/neoforge.mods.toml")
                check("V3 mods.toml version 1.6.7.1", 'version = "1.6.7.1"' in toml)
                check("V4 mods.toml 无 1.6.7 纯版本残留", 'version = "1.6.7"' not in toml)

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
    check("S7b DEFAULT_VALUES 性能仅 builtin-background",
          '"render.performance.builtin-background" to true' in cm_src and
          '"render.performance.show-tps" to true' not in cm_src and
          '"render.performance.show-online" to true' not in cm_src)
    check("S7c 废弃键清理表收编 show-*（用户旧配置自动清除）",
          '"render.performance.show-tps", "render.performance.show-mspt",' in cm_src and
          '"render.performance.show-online",' in cm_src)
    ps_src = src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/PerformanceService.kt")
    check("S9 性能服务冷却接入（commandCooldownMillis）", "commandCooldownMillis" in ps_src)
    check("S9b 性能服务无显隐读取", "performanceItemEnabled" not in ps_src)
    spigot_src = src("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/KERONGSpigot.kt")
    nf_src = src("server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt")
    check("S11 Spigot 源码 performanceSnapshot 分层读取实现",
          "override fun performanceSnapshot" in spigot_src and "readTps" in spigot_src and
          "readMspt" in spigot_src and "tpsFromMspt" in spigot_src)
    check("S12 NeoForge 源码 performanceSnapshot 分层读取实现",
          "override fun performanceSnapshot" in nf_src and "readMspt" in nf_src and
          "tpsFromMspt" in nf_src)
    kerong_src = src("common-Bot/src/main/kotlin/cn/kerong/bot/KERONG.kt")
    check("S13 KERONG 接口源码声明（data class PerformanceSnapshot）",
          "fun performanceSnapshot(): PerformanceSnapshot?" in kerong_src and
          "data class PerformanceSnapshot" in kerong_src)
    check("S13b 快照源码分层读取器与反射目标名",
          "fun readTps(" in kerong_src and "fun readMspt(" in kerong_src and
          "fun tpsFromMspt(" in kerong_src and "getAverageTickTimeNanos" in kerong_src and
          "recentTps" in kerong_src and "tickTimesNanos" in kerong_src)
    check("S13c 快照源码无在线字段（onlineCount / maxPlayers 构造参数已删）",
          "onlineCount: Int" not in kerong_src.split("data class PerformanceSnapshot")[1].split("companion")[0] and
          "maxPlayers: Int" not in kerong_src.split("data class PerformanceSnapshot")[1].split("companion")[0])
    check("S14 源码渲染器无 System.gc() 调用",
          "System.gc()" not in src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt") and
          "System.gc()" not in ps_src)
    check("S15 渲染器源码接住所有 null 指标（-- 占位）",
          '"--"' in src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/PerformanceRenderer.kt"))
    check("S15b 渲染器源码固定四项（measureHeight 无参 / CARD_COUNT）",
          "fun measureHeight(): Int" in src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/PerformanceRenderer.kt") and
          "showOnline" not in src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/PerformanceRenderer.kt"))
    check("S16 ConfigManager 迁移表已收编废弃键检查保持",
          '"render.idle-gc-hint",' in cm_src)
    check("S17 双端 help.md 查性能行",
          "/查性能" in src("common-Bot/src/main/resources/Markdown/help.md") and
          "/查性能" in src("server-NeoForge/src/main/resources/Markdown/help.md"))
    check("S18 双端 config.yml show-* 全删",
          "show-tps" not in src("server-Spigot/src/main/resources/config.yml") and
          "show-tps" not in src("server-NeoForge/src/main/resources/config.yml") and
          "performance:" in src("server-Spigot/src/main/resources/config.yml") and
          "performance:" in src("server-NeoForge/src/main/resources/config.yml"))
    check("S19 RenderSettings 源码无 performanceItemEnabled",
          "performanceItemEnabled" not in src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/RenderSettings.kt"))

    print(f"\n===== 门禁结果：{passed} 通过 / {failed} 失败 =====")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

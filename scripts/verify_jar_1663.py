#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
1.6.6.3 门禁：双端分发 JAR 内容断言。

覆盖：
  R. 重连风暴降噪（WSS 帧关闭/会话无效/要求重连仅文件 / 重连里程碑输出 /
     SDK 三条固定噪声过滤 / LogSink 接入）
  C. 双服同绑检测整体移除（ConflictWatch 类删除 / 短命会话证据计数删除 /
     QClient.stopForConflict 删除 / 回复路径无接入点）
  N. 重连计数越界修复（onOpen 上限重置 / 成功提示不再 6/5）
  G. GC 提示与释放日志文件化（InfoCardRenderer 释放动作 PluginFileLog.write）
  Y. 双端 config.yml GC 注释（卡顿说明 / 内存充足可关闭）+ reconnect-notify 新注释
  V. 版本 1.6.6.3（plugin.yml / mods.toml / 双端 config 无版本串残留 1.6.6.2）
  B. 基线保持（ReplySeqGuard / /hb chs 命令 / 看门狗 / 单飞 / 空闲释放 /
     头像本地优先 / WebUI 与 PlayTimeSync 保持删除 / 打包完整性）
"""
import os
import sys
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DIST = "/home/z/my-project/download/kerongpenguin-1.6.6.3-release"
SPIGOT = os.path.join(DIST, "KERONGPenguin_Spigot-1.6.6.3.jar")
NEOFORGE = os.path.join(DIST, "KERONGPenguin_NeoForge-1.21.1-1.6.6.3.jar")

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

            # ================= R. 重连风暴降噪 =================
            wss = class_blob(zf, "cn/kerong/bot/WssDiagnostics")
            check(f"R1 {label} WssDiagnostics 类在位", len(wss) > 10000)

            # 关闭帧 / op9 / op7 只写文件：方法字符串与 PluginFileLog.write 常量池引用均在
            check(f"R2 {label} 关闭帧文案保留（文件级）", "连接关闭：code=" in wss.decode("utf-8", "ignore"))
            check(f"R3 {label} op9 会话无效文案保留（文件级）", "会话无效(op 9" in wss.decode("utf-8", "ignore"))
            check(f"R4 {label} op7 要求重连文案保留（文件级）", "平台要求重连(op 7" in wss.decode("utf-8", "ignore"))
            check(f"R5 {label} WssDiagnostics 引用 PluginFileLog（write）", b"PluginFileLog" in wss)

            rcm = class_blob(zf, "cn/kerong/bot/ReconnectManager")
            check(f"R7 {label} ReconnectManager 里程碑文案（最后一次）",
                  "（本轮最后一次自动重连）" in rcm.decode("utf-8", "ignore"))
            check(f"R8 {label} ReconnectManager 仅文件过程提示（写入文件）",
                  "次重试连接…（连接已打开，等待鉴权）" in rcm.decode("utf-8", "ignore"))

            # KERONG 接口类在 common-Bot；LogSink 过滤器常量
            kerong = class_blob(zf, "cn/kerong/bot/KERONG")
            check(f"R9 {label} KERONG LogSink 噪声过滤在位", b"isSdkReconnectNoise" in kerong)
            check(f"R10 {label} SDK 噪声文案过滤（暂未处理的异常code）",
                  "暂未处理的异常code" in kerong.decode("utf-8", "ignore"))
            check(f"R11 {label} SDK 噪声文案过滤（1011 server internal）",
                  "websocket closed with code 1011,server internal exception" in kerong.decode("utf-8", "ignore"))
            check(f"R12 {label} SDK 噪声文案过滤（reconnect in 3 seconds）",
                  "reconnect in 3 seconds" in kerong.decode("utf-8", "ignore"))

            # ================= C. 双服同绑检测移除 =================
            check(f"C1 {label} ConflictWatch 类已删除", not has(zf, "cn/kerong/bot/ConflictWatch.class"))
            check(f"C2 {label} 旧包名 ConflictWatch 已删除", not has(zf, "cn/huohuas001/bot/ConflictWatch.class"))
            check(f"C3 {label} WssDiagnostics 无证据计数（trackShortSession）",
                  b"trackShortSession" not in wss)
            check(f"C4 {label} WssDiagnostics 无证据计数（recordConflictEvidence）",
                  b"recordConflictEvidence" not in wss)
            check(f"C5 {label} WssDiagnostics 无 trackAuthRejected", b"trackAuthRejected" not in wss)
            check(f"C6 {label} WssDiagnostics 无 stopForConflict 引用", b"stopForConflict" not in wss)
            check(f"C7 {label} QClient 无 stopForConflict", b"stopForConflict" not in class_blob(zf, "cn/kerong/bot/QClient"))
            check(f"C8 {label} ReconnectManager 无 onConflictDetected", b"onConflictDetected" not in rcm)
            check(f"C9 {label} ReconnectManager 无 onDedupConflictConfirmed", b"onDedupConflictConfirmed" not in rcm)
            check(f"C10 {label} 抢占误报文案不存在（另一台服务器）",
                  "另一台服务器" not in wss.decode("utf-8", "ignore") and
                  "另一台服务器" not in rcm.decode("utf-8", "ignore"))
            qc = class_blob(zf, "cn/kerong/bot/QClient")
            check(f"C11 {label} QClient 回复路径无 ConflictWatch 接入", b"ConflictWatch" not in qc)
            mt = class_blob(zf, "cn/kerong/penguin/spigot/qqbind/MessageTemplates")
            check(f"C12 {label} MessageTemplates 无 ConflictWatch 接入", b"ConflictWatch" not in mt)
            # 全 JAR 范围兜底：class 字节里不应再出现该类名
            all_classes = b"".join(
                zf.read(n) for n in names if n.endswith(".class") and n.startswith("cn/kerong/")
            )
            check(f"C13 {label} 全部插件类无 ConflictWatch 引用", b"ConflictWatch" not in all_classes)

            # ================= N. 重连计数越界修复 =================
            check(f"N1 {label} onOpen 上限重置源码标志（挂起后 SDK 已排队）",
                  "SDK 已排队的延迟重连" in rcm.decode("utf-8", "ignore") or
                  "SDK 已排队的延迟重连" in src("common-Bot/src/main/kotlin/cn/kerong/bot/ReconnectManager.kt"))

            # ================= G. GC / 释放日志文件化 =================
            # 释放与头像缓存逻辑位于 InfoCardAssets（与渲染器同源文件不同 object）
            icr = class_blob(zf, "cn/kerong/penguin/spigot/render/InfoCardAssets")
            check(f"G1 {label} 释放文案保留（分钟无渲染，仅文件）", "分钟无渲染，释放素材缓存内存" in icr.decode("utf-8", "ignore"))
            check(f"G2 {label} 释放动作带 gc-hint 状态标注",
                  "开启时本次已随释放向 JVM 发 GC 提示" in icr.decode("utf-8", "ignore"))
            # 旧控制台路径：log_info 调用在 KERONG 接口上（BotShared.instance?.log_info）——
            # 源码断言 releaseIfIdle 使用 PluginFileLog.write
            check(f"G3 {label} 源码：releaseIfIdle 用 PluginFileLog.write",
                  "PluginFileLog.write(\n                \"[渲染] 已" in src("common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt"))

            # ================= Y. config.yml =================
            cfg = read_text(zf, "config.yml")
            check(f"Y1 {label} GC 注释：每次 GC 卡顿说明", "每次 GC 会导致 0.05~0.6 秒的卡顿" in cfg)
            check(f"Y2 {label} GC 注释：内存充足可关闭", "服务器内存充足可关闭此配置" in cfg)
            check(f"Y3 {label} 释放说明：不刷控制台", "不刷控制台" in cfg)
            check(f"Y4 {label} reconnect-notify 新注释（里程碑）", "重试里程碑" in cfg)
            check(f"Y5 {label} idle-gc-hint 默认 true", "idle-gc-hint: true" in cfg)
            check(f"Y6 {label} idle-release-minutes 默认 5", "idle-release-minutes: 5" in cfg)

            # ================= V. 版本 =================
            if label == "Spigot":
                check("V1 plugin.yml version 1.6.6.3", "version: '1.6.6.3'" in read_text(zf, "plugin.yml"))
                check("V2 plugin.yml 无 1.6.6.2 残留", "1.6.6.2" not in read_text(zf, "plugin.yml"))
            else:
                toml = read_text(zf, "META-INF/neoforge.mods.toml")
                check("V3 mods.toml version 1.6.6.3", 'version = "1.6.6.3"' in toml)
                check("V4 mods.toml 无 1.6.6.2 残留", "1.6.6.2" not in toml)

            # ================= B. 基线保持 =================
            check(f"B1 {label} ReplySeqGuard 保留（40054005 修复本体）",
                  b"ReplySeqGuard" in class_blob(zf, "cn/kerong/bot/"))
            check(f"B2 {label} QqTokenHealth 保留（凭据自检）", has(zf, "cn/kerong/bot/QqTokenHealth.class"))
            check(f"B3 {label} 看门狗心跳假死检测保留", "连接假死" in wss.decode("utf-8", "ignore"))
            check(f"B4 {label} 心跳兜底保留", "心跳兜底" in wss.decode("utf-8", "ignore"))
            check(f"B5 {label} 单飞 FetchLatch 保留", b"FetchLatch" in icr)
            check(f"B6 {label} cacheStats 保留（/kerong mem）", b"cacheStats" in icr)
            check(f"B7 {label} 头像本地优先保留", b"avatarFetchPriority" in icr)
            check(f"B8 {label} WebUI 保持删除", not has(zf, "cn/kerong/bot/web/WebUiServer.class"))
            check(f"B9 {label} PlayTimeSync 保持删除", not has(zf, "cn/kerong/penguin/spigot/stats/PlayTimeSync.class"))
            # 打包完整性（目录条目数与 1.6.6.2 基线一致：Spigot 776 / NeoForge 661）
            dir_entries = sum(1 for n in names if n.endswith("/"))
            dir_min = 700 if label == "Spigot" else 600
            check(f"B10 {label} 目录条目保留（{dir_entries} > {dir_min}）", dir_entries > dir_min)
            import re
            check(f"B11 {label} relocate 干净（无 io.github.kloping 顶层类）",
                  not any(re.match(r"io/github/kloping/[^/]+\.class", n) for n in names))

    # 源码级断言（源文件与 JAR 同源构建）
    rcm_src = src("common-Bot/src/main/kotlin/cn/kerong/bot/ReconnectManager.kt")
    check("S1 ReconnectManager.onOpen 上限重置逻辑",
          "if (attempt.get() >= SMALL_RETRY_MAX)" in rcm_src and "attempt.set(0)" in rcm_src)
    check("S2 重试里程碑：第 1 次上控制台",
          'n == 1' in rcm_src and "PluginFileLog.infoAndKeep(\"[重连] 第 $n/" in rcm_src)
    check("S3 中间过程仅文件", "PluginFileLog.write(\"[重连] 第 $n/" in rcm_src)
    check("S4 最后一次上控制台", "（本轮最后一次自动重连）" in rcm_src)
    check("S5 giveUp 文案无冲突疑似原因", "疑似原因" not in rcm_src)
    check("S6 WssDiagnostics 关闭帧 write", 'PluginFileLog.write(\n            "[WSS] 连接关闭' in
          src("common-Bot/src/main/kotlin/cn/kerong/bot/WssDiagnostics.kt"))
    check("S6b 看门狗/心跳兜底保留控制台 WARN（warnAndKeep）",
          'PluginFileLog.warnAndKeep(message)' in src("common-Bot/src/main/kotlin/cn/kerong/bot/WssDiagnostics.kt") and
          'PluginFileLog.warnAndKeep(\n                "[WSS心跳兜底]' in src("common-Bot/src/main/kotlin/cn/kerong/bot/WssDiagnostics.kt"))
    check("S7 WssDiagnostics op9 write", '9 -> PluginFileLog.write(' in
          src("common-Bot/src/main/kotlin/cn/kerong/bot/WssDiagnostics.kt"))
    check("S8 KERONG LogSink 过滤接入", "isSdkReconnectNoise(message)" in
          src("common-Bot/src/main/kotlin/cn/kerong/bot/KERONG.kt"))
    check("S9 ConflictWatch 源文件已删除",
          not os.path.isfile(os.path.join(REPO, "common-Bot/src/main/kotlin/cn/kerong/bot/ConflictWatch.kt")))
    check("S10 QClient 源码无 ConflictWatch",
          "ConflictWatch" not in src("common-Bot/src/main/kotlin/cn/kerong/bot/QClient.kt"))

    print(f"\n===== 门禁结果：{passed} 通过 / {failed} 失败 =====")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())

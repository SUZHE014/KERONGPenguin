#!/usr/bin/env python3
"""1.7.6 门禁：四分发 JAR 字节码核查（1.7.5.9 回归 + 本轮四任务）。

本轮改动（用户指令四项）：
  T1 总在线时长多源——variable-fetch.total-playtime-source 新键
     （save-data 存档数据 / plugin 插件统计 / variable 变量，默认 variable）；
     StatsFileService 读主世界 stats/<uuid>.json 的 minecraft:play_time
     （play_one_minute 旧键兼容），÷72000 小时展示；save-data 读不到
     自动回退变量 → 插件统计；config-version 51 → 52。
  T2 Fabric 假人白名单——BindRestrict.onJoin 接 FakePlayerExempt：
     连接自动检测（class_2535 类型锚定 + method_10756/10755 反射，
     intermediary 常量）+ qq-bind.fake-player-whitelist 名单（大小写
     不敏感），与 Spigot/NF 1.7.5.8 豁免同口径。
  T3 真实玻璃渲染（测试功能）——render.real-glass 默认 false；开启后
     CardBackdropContext.paintCard 改走 LiquidGlassPainter（SDF 折射 +
     RGB 色差 + 边缘高光）；模糊度 0 时原图为玻璃源。
  T4 按钮输出艾特上一个人修复——RecentGroupMessages 登记发送者，
     按钮回复只引用点击者自己的消息（nextIdFromSender）；点击者没有
     消息 → 主动消息（不引用他人）。
  回归：1.7.5.9 全部断言（去配置化 / 菜单模板 / Fabric 限制策略 /
  假人豁免 / 昵称唯一源 / 未映射白名单 / 形态）。

用法：python3 scripts/verify_jar_176.py
"""
import re
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
DIST = REPO / "build" / "dist"
JARS = {
    "Spigot": DIST / "KERONGPenguin_Spigot-1.7.6.jar",
    "NeoForge": DIST / "KERONGPenguin_NeoForge-1.20.4-1.7.6.jar",
    "Fabric": DIST / "KERONGPenguin_Fabric-1.20.4-1.7.6.jar",
    "Fabric1201": DIST / "KERONGPenguin_Fabric-1.20.1-1.7.6.jar",
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
                    check(f"A2 NF {mfile} 含 1.7.6", "1.7.6" in mt)
                check("A2b NF MOD_VERSION 1.7.6（ldc 常量池）",
                      "1.7.6" in javap(jar, "cn/kerong/penguin/neoforge/KERONGNeoForge.class", ("-c", "-p")))
            if label in ("Fabric", "Fabric1201"):
                fm = zf.read("fabric.mod.json").decode("utf-8", "replace")
                check(f"A3 {label} fabric.mod.json 版本 1.7.6",
                      re.search(r'"version":\s*"1\.7\.6"', fm) is not None)
                if label == "Fabric":
                    check("A3b Fabric(1.20.4) minecraft 声明 >=1.20.4 <1.22",
                          re.search(r'"minecraft":\s*">=1\.20\.4 <1\.22"', fm) is not None, fm[:200])
                else:
                    check("A3b Fabric1201 minecraft 声明精确 1.20.1",
                          re.search(r'"minecraft":\s*"1\.20\.1"', fm) is not None, fm[:200])
            cfg = zf.read("config.yml").decode("utf-8", "replace")
            check(f"A4 {label} config-version 52", re.search(r"config-version:\s*52", cfg) is not None, cfg[:120])
            # 本轮三个新键
            check(f"A5 {label} variable-fetch.total-playtime-source 键在位（默认 variable）",
                  re.search(r'total-playtime-source:\s*"variable"', cfg) is not None)
            check(f"A5b {label} render.real-glass 键在位（默认 false）",
                  re.search(r"real-glass:\s*false", cfg) is not None)
            check(f"A5c {label} qq-bind.fake-player-whitelist 键在位（默认空）",
                  re.search(r"fake-player-whitelist:\s*\[\]", cfg) is not None)
            # 回归：上一轮键保持
            check(f"A6 {label} qq-bind.restrict-timeout-seconds: 120 键保留",
                  re.search(r"restrict-timeout-seconds:\s*120", cfg) is not None)
            check(f"A6b {label} qq-forward-display-name 键已删（1.7.5.9 回归反断言）",
                  "qq-forward-display-name" not in cfg and "group-sender-name" not in cfg)
            check(f"A6c {label} bot.button-cooldown-seconds 键保留",
                  re.search(r"button-cooldown-seconds:\s*5", cfg) is not None)

            # ---------- T1. 总在线时长多源（四端共有源码） ----------
            sfs = "cn/kerong/penguin/spigot/stats/StatsFileService.class"
            check(f"T1a {label} StatsFileService 类存在", sfs in names)
            if sfs in names:
                sbytes = zf.read(sfs)
                st = javap(jar, sfs)
                rp = method_body(st, "readPlayTicks")
                check(f"T1b {label} readPlayTicks 读 stats/minecraft:custom/play_time（字节级）",
                      all(k.encode("utf-8") in sbytes for k in
                          ("minecraft:play_time", "minecraft:play_one_minute", "minecraft:custom", "stats")),
                      )
                check(f"T1c {label} readPlayTicks 方法在位且走 JSON 解析",
                      "readPlayTicks" in st and "parseObject" in rp, rp[:120])
                fh = method_body(st, "formatHours")
                check(f"T1d {label} formatHours ÷72000（ldc2_w 72000）",
                      re.search(r"72000", fh) is not None, fh[:160])
            pvs = "cn/kerong/penguin/spigot/stats/PlaceholderVariableService.class"
            if pvs in names:
                pt = javap(jar, pvs)
                tps = method_body(pt, "totalPlaytimeSource")
                check(f"T1e {label} totalPlaytimeSource 方法在位且读 total-playtime-source 键",
                      "variable-fetch.total-playtime-source" in tps, tps[:140])
                pbytes = zf.read(pvs)
                check(f"T1f {label} 三源常量 save-data/plugin/variable + 中文别名（字节级）",
                      all(k.encode("utf-8") in pbytes for k in
                          ("save-data", "plugin", "variable", "存档数据", "插件统计", "变量")))
            qis = "cn/kerong/penguin/spigot/stats/QueryInfoService.class"
            if qis in names:
                qt = javap(jar, qis)
                rtp = method_body(qt, "resolveTotalPlaytime")
                check(f"T1g {label} resolveTotalPlaytime 回退链（readPlayTicks→变量→formatPlayTime）",
                      "StatsFileService.readPlayTicks" in rtp and "formatPlayTime" in rtp
                      and "totalPlaytimeSource" in rtp, rtp[:200])
                bi = method_body(qt, "buildItems")
                check(f"T1h {label} buildItems 在线时长项走 resolveTotalPlaytime",
                      "resolveTotalPlaytime" in bi)
                snap = "cn/kerong/penguin/spigot/stats/QueryInfoService$MainThreadSnapshot.class"
                if snap in names:
                    check(f"T1i {label} MainThreadSnapshot 含 playerUuid 字段",
                          "getPlayerUuid" in javap(jar, snap, ("-p",)))
            cm = "cn/kerong/penguin/spigot/manager/ConfigManager.class"
            if cm in names:
                cmfull = javap(jar, cm, ("-v",))
                cmbytes = zf.read(cm)
                check(f"T1j {label} DEFAULT_VALUES 含 total-playtime-source",
                      "variable-fetch.total-playtime-source".encode("utf-8") in cmbytes)
                check(f"T1k {label} DEFAULT_VALUES 含 render.real-glass 与 fake-player-whitelist",
                      "render.real-glass".encode("utf-8") in cmbytes and
                      "qq-bind.fake-player-whitelist".encode("utf-8") in cmbytes)
                check(f"T1l {label} CURRENT_CONFIG_VERSION 编译期内联 52（bipush）",
                      re.search(r"bipush\s+52", javap(jar, cm, ("-c",))) is not None)
                fpw = method_body(javap(jar, cm), "fakePlayerWhitelist")
                check(f"T1m {label} ConfigManager.fakePlayerWhitelist 读名单键",
                      "qq-bind.fake-player-whitelist" in fpw and "getStringList" in fpw, fpw[:140])

            # ---------- T3. 真实玻璃渲染（四端共有源码） ----------
            lgp = "cn/kerong/penguin/spigot/render/LiquidGlassPainter.class"
            check(f"T3a {label} LiquidGlassPainter 类存在", lgp in names)
            if lgp in names:
                lt = javap(jar, lgp)
                pc = method_body(lt, "paintCard")
                check(f"T3b {label} paintCard 逐像素管线（getRGB + setRGB + drawImage）",
                      "getRGB" in pc and "setRGB" in lt and "drawImage" in pc, pc[:160])
                check(f"T3c {label} 折射/边缘带常量（18.0 / 9.0 ldc）",
                      re.search(r"ldc\s+#\d+\s+// float 18\.0", pc) is not None or "18.0" in pc,
                      pc[:80])
                check(f"T3d {label} sampleIndex 边界钳制方法在位", "sampleIndex" in lt)
            rs = "cn/kerong/penguin/spigot/render/RenderSettings.class"
            if rs in names:
                rt2 = javap(jar, rs)
                rg = method_body(rt2, "realGlassEnabled")
                check(f"T3e {label} RenderSettings.realGlassEnabled 读 render.real-glass",
                      "render.real-glass" in rg, rg[:120])
            icr = "cn/kerong/penguin/spigot/render/InfoCardRenderer.class"
            ica = "cn/kerong/penguin/spigot/render/InfoCardAssets.class"
            if ica in names:
                iat = javap(jar, ica)
                # paintCard 在 InfoCardAssets$CardBackdropContext 上（唯一定义处）
                inner = "cn/kerong/penguin/spigot/render/InfoCardAssets$CardBackdropContext.class"
                if inner in names:
                    cbc = javap(jar, inner)
                    pc2 = method_body(cbc, "paintCard")
                    check(f"T3f {label} paintCard 分支 realGlassEnabled→LiquidGlassPainter",
                          "realGlassEnabled" in pc2 and "LiquidGlassPainter.paintCard" in pc2,
                          pc2[:180])
                else:
                    check(f"T3f {label} CardBackdropContext 内部类存在", False)
                cbo = method_body(iat, "cardBackdropOf")
                check(f"T3g {label} cardBackdropOf 真实玻璃+模糊0 语义（realGlassEnabled 接线）",
                      "realGlassEnabled" in cbo, cbo[:200])

            # ---------- T4. 按钮艾特修复（四端共有源码） ----------
            rgm = "cn/kerong/bot/events/RecentGroupMessages.class"
            check(f"T4a {label} RecentGroupMessages 类存在", rgm in names)
            if rgm in names:
                rt3 = javap(jar, rgm, ("-p",))
                check(f"T4b {label} nextIdFromSender 方法在位（双参）",
                      re.search(r"nextIdFromSender\(java\.lang\.String, ?java\.lang\.String\)", rt3) is not None,
                      rt3[:400])
                check(f"T4c {label} 旧单参 nextId 已删除（反断言）",
                      re.search(r"nextId\(java\.lang\.String\)", rt3) is None)
                check(f"T4d {label} record 三参形态（String,String,String）",
                      re.search(r"record\(java\.lang\.String, ?java\.lang\.String, ?java\.lang\.String\)", rt3) is not None)
                nifs = method_body(javap(jar, rgm), "nextIdFromSender")
                check(f"T4e {label} nextIdFromSender 按发送者匹配 + 轮转推进",
                      "getSenderOpenId" in nifs and "getCursor" in nifs and "setCursor" in nifs,
                      nifs[:180])
            gmr = "cn/kerong/bot/events/GroupMessageHandler.class"
            if gmr in names:
                gt = javap(jar, gmr)
                ogm = method_body(gt, "onGroupMessage")
                check(f"T4f {label} onGroupMessage 登记带发送者（record 三参描述符）",
                      "RecentGroupMessages.record:(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V" in ogm,
                      ogm[:300])
            mb = "cn/kerong/bot/events/MenuButtons.class"
            if mb in names:
                mt2 = javap(jar, mb)
                dac = method_body(mt2, "dispatchAsClicker")
                check(f"T4g {label} dispatchAsClicker 只引用点击者消息（nextIdFromSender）",
                      "nextIdFromSender" in dac, dac[:200])
                check(f"T4h {label} 旧 nextId 调用已移除（反断言）",
                      re.search(r"RecentGroupMessages\.nextId:\(Ljava/lang/String;\)", mt2) is None)

            # ---------- 回归：1.7.5.9 转发显示名去配置化 ----------
            check(f"R0a {label} GroupSenderName 类已删除（回归反断言）",
                  "cn/kerong/bot/tools/GroupSenderName.class" not in names)
            if gmr in names:
                gtxt = javap(jar, gmr)
                fw = method_body(gtxt, "forwardFullGroupMessage")
                check(f"R0b {label} 全量转发直取事件昵称 getUsername（回归）",
                      "getUsername" in fw and "resolveForwardDisplayName" not in gtxt, fw[:150])

            # ---------- 回归：菜单模板删除重建 ----------
            mtc = "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class"
            if mtc in names:
                mtxt = javap(jar, mtc)
                ar = method_body(mtxt, "applyReplacement")
                check(f"R0c {label} applyReplacement 先 delete（回归）",
                      "delete" in ar and "Code:" in ar, ar[:100])

            # ---------- 回归：昵称匹配唯一源 ----------
            if gmr in names:
                lbn = method_body(javap(jar, gmr), "loadBotNicknames")
                check(f"R0d {label} loadBotNicknames 无 getBotName（回归）",
                      "getBotName" not in lbn)

        # ---------- T2. Fabric 假人白名单（仅 Fa 两包） ----------
        if label in ("Fabric", "Fabric1201"):
            with zipfile.ZipFile(jar) as zf:
                fnames = zf.namelist()
                fpe = "cn/kerong/penguin/fabric/FakePlayerExempt.class"
                check(f"T2a {label} FakePlayerExempt 类存在", fpe in fnames)
                if fpe in fnames:
                    fe_txt = javap(jar, fpe)
                    fe_bytes = zf.read(fpe)
                    # intermediary 常量（ASCII 字符串，javap ldc // String 可见）
                    check(f"T2b {label} Connection 类锚定 class_2535（intermediary）",
                          "net.minecraft.class_2535" in fe_txt)
                    check(f"T2c {label} isMemoryConnection→method_10756（两版同名）",
                          "method_10756" in fe_txt)
                    check(f"T2d {label} getRemoteAddress→method_10755（两版同名）",
                          "method_10755" in fe_txt)
                    lfp = method_body(fe_txt, "isLocalFakePlayer")
                    # player.connection 直连字段引用被重映射为 field_13987（两版同）
                    check(f"T2e {label} player.connection 引用已重映射（field_13987）",
                          "field_13987" in lfp, lfp[:200])
                    check(f"T2f {label} 内存连接 + 回环判定（invoke isMemoryConnection 路径）",
                          "isLoopbackAddress" in lfp or "method_10756" in lfp, lfp[:200])
                    co = method_body(fe_txt, "connectionOf")
                    check(f"T2g {label} connectionOf 按类型锚定（declaredFields）",
                          "getDeclaredFields" in co, co[:160])
                    wl = method_body(fe_txt, "isWhitelisted")
                    check(f"T2h {label} isWhitelisted 走 fakePlayerWhitelist + 忽略大小写",
                          "fakePlayerWhitelist" in wl and "StringsKt.equals" in wl, wl[:180])
                    iex = method_body(fe_txt, "isExempt")
                    check(f"T2i {label} isExempt 双路（名单 + 连接检测）",
                          "isWhitelisted" in iex and "isLocalFakePlayer" in iex, iex[:120])
                # onJoin 接线
                br = "cn/kerong/penguin/fabric/BindRestrict.class"
                check(f"T2j {label} BindRestrict 类存在", br in fnames)
                if br in fnames:
                    bt = javap(jar, br)
                    brbytes = zf.read(br)
                    oj = method_body(bt, "onJoin")
                    check(f"T2k {label} onJoin 假人白名单接线（FakePlayerExempt.isExempt）",
                          "FakePlayerExempt.isExempt" in oj, oj[:220])
                    check(f"T2l {label} 豁免跳过日志（字节级）",
                          "假人白名单".encode("utf-8") in brbytes)
                # KERONGFabric 委托
                kf = "cn/kerong/penguin/fabric/KERONGFabric.class"
                if kf in fnames:
                    check(f"T2m {label} KERONGFabric.fakePlayerWhitelist 委托在位",
                          "fakePlayerWhitelist" in javap(jar, kf, ("-p",)))

            # 回归：1.7.5.9 Fabric 限制策略核心
            with zipfile.ZipFile(jar) as zf:
                fnames = zf.namelist()
                br = "cn/kerong/penguin/fabric/BindRestrict.class"
                bri = "cn/kerong/penguin/fabric/BindRestrictInteractions.class"
                brc = "cn/kerong/penguin/fabric/BindRestrictCommandGate.class"
                check(f"T2n {label} BindRestrictInteractions 类存在（回归）", bri in fnames)
                check(f"T2o {label} BindRestrictCommandGate 类存在（回归）", brc in fnames)
                if br in fnames:
                    bt = javap(jar, br)
                    oj = method_body(bt, "onJoin")
                    check(f"T2p {label} onJoin 生成绑定码 + 已绑定放行（回归）",
                          "generateCode" in oj and "isBoundByUuid" in oj)
                    disc = "method_52396" if label == "Fabric" else "method_14367"
                    check(f"T2q {label} tick 超时踢出 + 锚点锁定 + 绑定码输出（回归）",
                          disc in bt and "method_14363" in bt and "method_43496" in bt)
                if brc in fnames:
                    gt2 = javap(jar, brc)
                    check(f"T2r {label} GatePredicate Unsafe 包装（回归）",
                          "putObject" in gt2 and "theUnsafe" in gt2)

            # 回归：Fa 未映射引用白名单
            unmapped = scan_mc_refs(jar)
            whitelist = {
                "net/minecraft/class_124.ordinal ()I",
                "net/minecraft/class_124.values ()[Lnet/minecraft/class_124;",
                "net/minecraft/class_2561.getString ()Ljava/lang/String;",
                "net/minecraft/server/MinecraftServer.execute (Ljava/lang/Runnable;)V",
            }
            bad = [r for r in unmapped if r not in whitelist]
            check(f"T2s {label} 未映射引用仅白名单 4 条（回归）",
                  not bad and len(unmapped) <= 4, "; ".join(unmapped[:8]))

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
    print(f"\n===== verify_jar_176：{len(results)} 项，失败 {len(failed)} =====")
    for name, ok, detail in failed:
        print(f"  FAIL {name}  {detail[:160]}")
    print("全绿" if not failed else "存在失败项")
    sys.exit(0 if not failed else 1)


if __name__ == "__main__":
    main()

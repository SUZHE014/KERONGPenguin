#!/usr/bin/env python3
"""1.7.5.9 覆盖轮门禁：四分发 JAR 字节码核查（首发 1759 回归 + 本轮去配置化）。

本轮改动（1.7.5.9 同号覆盖，用户指令「转发不用配置了，直接统一全部默认
显示群昵称」+「确保 1.7.5.8 的假人插件修复还在」+「本次覆盖 1.7.5.9」）：
  T1' 转发显示名去配置化——chat-format.qq-forward-display-name（首发
      1759 新增）整体废弃删键（入废弃清单，cv50 残留配置升 51 自动删）；
      全量转发与 /发信息 统一固定群昵称（事件唯一昵称字段）；ChatFormat
      字段与 resolveForwardDisplayName 解析器删除；config-version 50 → 51。
  回归：T2 菜单模板删除重建 / T3 Fabric 限制策略 / R 假人豁免（1.7.5.8，
      用户点名确保）/ B 昵称匹配唯一源 / C Fabric 字节码 / H 形态，全部保持。

A. 版本与资源（1.7.5.9 / config-version 51 / 新键已删 / 限制键保留）
N. 本轮 T1'（去配置化断言）+ T2/T3 回归
B/C/R/H. 前轮回归与形态

用法：python3 scripts/verify_jar_1759o2.py
"""
import re
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path("/home/z/my-project/penguin-git")
DIST = REPO / "build" / "dist"
JARS = {
    "Spigot": DIST / "KERONGPenguin_Spigot-1.7.5.9.jar",
    "NeoForge": DIST / "KERONGPenguin_NeoForge-1.20.4-1.7.5.9.jar",
    "Fabric": DIST / "KERONGPenguin_Fabric-1.20.4-1.7.5.9.jar",
    "Fabric1201": DIST / "KERONGPenguin_Fabric-1.20.1-1.7.5.9.jar",
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
                check("A1 Spigot plugin.yml 版本 1.7.5.9",
                      re.search(r"version:\s*'?\"?1\.7\.5\.9", py) is not None)
            if label == "NeoForge":
                for mfile in ("META-INF/mods.toml", "META-INF/neoforge.mods.toml"):
                    mt = zf.read(mfile).decode("utf-8", "replace") if mfile in names else ""
                    check(f"A2 NF {mfile} 含 1.7.5.9", "1.7.5.9" in mt)
                check("A2b NF MOD_VERSION 1.7.5.9（ldc 常量池）",
                      "1.7.5.9" in javap(jar, "cn/kerong/penguin/neoforge/KERONGNeoForge.class", ("-c", "-p")))
            if label in ("Fabric", "Fabric1201"):
                fm = zf.read("fabric.mod.json").decode("utf-8", "replace")
                check(f"A3 {label} fabric.mod.json 版本 1.7.5.9",
                      re.search(r'"version":\s*"1\.7\.5\.9"', fm) is not None)
                if label == "Fabric":
                    check("A3b Fabric(1.20.4) minecraft 声明 >=1.20.4 <1.22",
                          re.search(r'"minecraft":\s*">=1\.20\.4 <1\.22"', fm) is not None, fm[:200])
                else:
                    check("A3b Fabric1201 minecraft 声明精确 1.20.1",
                          re.search(r'"minecraft":\s*"1\.20\.1"', fm) is not None, fm[:200])
            cfg = zf.read("config.yml").decode("utf-8", "replace")
            check(f"A4 {label} config-version 51", re.search(r"config-version:\s*51", cfg) is not None, cfg[:120])
            check(f"A5 {label} bot.button-cooldown-seconds 键在位",
                  re.search(r"button-cooldown-seconds:\s*5", cfg) is not None)
            check(f"A5b {label} ai.reminder-mode 键在位",
                  re.search(r"reminder-mode:\s*\"\"", cfg) is not None)
            check(f"A5c {label} 旧键 ai.reply-quote 已删（配置内不存在）",
                  "reply-quote" not in cfg)
            check(f"A5d {label} ai.at-enabled 键保留",
                  re.search(r"at-enabled:\s*true", cfg) is not None)
            check(f"A5e {label} 配置注释无版本字样（1.7.x 新增类）",
                  not re.search(r"（1\.7\.\d[^）]*新增|（旧键，1\.7", cfg))
            # 本轮去配置化（四端模板同步）：两代显示名配置键都已删除
            check(f"A6 {label} qq-forward-display-name 键已删（反断言）",
                  "qq-forward-display-name" not in cfg)
            check(f"A6b {label} 旧键 group-sender-name 不在配置模板",
                  "group-sender-name" not in cfg)
            check(f"A6d {label} 固定群昵称口径注释在位",
                  "不再提供配置项" in cfg and "群昵称" in cfg)
            check(f"A6c {label} qq-bind.restrict-timeout-seconds: 120 键在位",
                  re.search(r"restrict-timeout-seconds:\s*120", cfg) is not None)

            # ---------- N(T1). 显示名来源（四端共有源码） ----------
            check(f"T1a {label} GroupSenderName 类已删除（反断言）",
                  "cn/kerong/bot/tools/GroupSenderName.class" not in names)
            check(f"T1b {label} getBoundPlayerNameByOpenid 已删除（反断言）",
                  "getBoundPlayerNameByOpenid" not in javap(jar, "cn/kerong/penguin/spigot/qqbind/QqBindManager.class", ("-p",)))
            cfc = "cn/kerong/bot/provider/ConfigProvider$ChatFormat.class"
            if cfc not in names:
                cfc = "cn/kerong/bot/provider/ChatFormat.class"
            if cfc in names:
                check(f"T1c {label} ChatFormat 组件已删 getQqForwardDisplayName（反断言）",
                      "getQqForwardDisplayName" not in javap(jar, cfc, ("-p",)))
            cm = "cn/kerong/penguin/spigot/manager/ConfigManager.class"
            if cm in names:
                cf_body = method_body(javap(jar, cm), "chatFormat")
                check(f"T1d {label} chatFormat 不再读 qq-forward-display-name（反断言）",
                      "qq-forward-display-name" not in cf_body and "group-sender-name" not in cf_body)
                rt = method_body(javap(jar, cm), "restrictTimeoutSeconds")
                check(f"T1e {label} ConfigManager.restrictTimeoutSeconds 读 qq-bind.restrict-timeout-seconds",
                      "qq-bind.restrict-timeout-seconds" in rt and "coerceAtLeast" in rt)
                cmfull = javap(jar, cm, ("-v",))
                check(f"T1f {label} REMOVED_CONFIG_KEYS 含两代废弃键（group-sender-name）",
                      "chat-format.group-sender-name" in cmfull)
                check(f"T1f2 {label} REMOVED_CONFIG_KEYS 含本轮废弃键（qq-forward-display-name）",
                      "chat-format.qq-forward-display-name" in cmfull)
                cm_c = javap(jar, cm, ("-c",))
                check(f"T1f3 {label} CURRENT_CONFIG_VERSION 编译期内联 51",
                      re.search(r"bipush\s+51", cm_c) is not None)
            gmr = "cn/kerong/bot/events/GroupMessageHandler.class"
            if gmr in names:
                gtxt = javap(jar, gmr)
                fw = method_body(gtxt, "forwardFullGroupMessage")
                check(f"T1g {label} resolveForwardDisplayName 已删除（反断言，全文）",
                      "resolveForwardDisplayName" not in gtxt)
                check(f"T1h {label} 全量转发直取事件昵称 getUsername（统一群昵称）",
                      "getUsername" in fw, fw[:200])
                check(f"T1i {label} 全量转发无 getQqForwardDisplayName 引用（反断言）",
                      "getQqForwardDisplayName" not in gtxt and "groupSenderName" not in gtxt)
            pbc = "cn/kerong/bot/events/commands/PublicCommands.class"
            if pbc in names:
                sm = method_body(javap(jar, pbc), "sendGameMessage")
                check(f"T1j {label} /发信息 保持直取（无 resolve 接线，历史行为）",
                      "GroupSenderName" not in sm and "resolveForwardDisplayName" not in sm)
                check(f"T1k {label} /发信息 仍读事件昵称 getUsername",
                      "getUsername" in sm, sm[:200])

            # ---------- N(T2). 菜单模板删除重建（四端共有源码） ----------
            mtc = "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class"
            if mtc in names:
                mtxt = javap(jar, mtc)
                ar = method_body(mtxt, "applyReplacement")
                check(f"T2a {label} applyReplacement 存在且先 delete",
                      "delete" in ar and "Code:" in ar, ar[:120])
                ini = method_body(mtxt, "initialize")
                check(f"T2b {label} initialize ②③路径调 applyReplacement",
                      ini.count("applyReplacement") >= 2)
                # 汇总日志口径：类字节级搜「删除重建」（常量池中文按 UTF-8 字节）
                mbytes = zf.read(mtc)
                check(f"T2c {label} 汇总日志为删除重建口径（字节级）",
                      "删除重建".encode("utf-8") in mbytes)
                # menu 历代 olds 常量（字节级）
                for tag, frag in (
                    ("olds[0] 1.7.5 引入版", "点击下方按钮即可执行"),
                    ("olds[1] 覆盖第二轮", "### 指令菜单（{page}/{pages}）"),
                    ("olds[2] 覆盖第三轮", "**当前插件版本：v{version}**"),
                ):
                    check(f"T2d {label} menu 比对表 {tag}（字节级）",
                          frag.encode("utf-8") in mbytes)
            else:
                check(f"T2 {label} MessageTemplates 类存在", False)

            # ---------- B. 1758 回归（昵称匹配唯一源，全部四端） ----------
            if gmr in names:
                txt = javap(jar, gmr)
                lbn = method_body(txt, "loadBotNicknames")
                check(f"B1 {label} loadBotNicknames 无 getBotName 引用（唯一源回归）",
                      "getBotName" not in lbn)
                imb = method_body(txt, "isMentionedBot")
                check(f"B1b {label} isMentionedBot 方法体无 getBotName（回归）",
                      "getBotName" not in imb)

            # ---------- C(Fabric 端字节码细节 + 未映射白名单) ----------
        if label in ("Fabric", "Fabric1201"):
            with zipfile.ZipFile(jar) as zf:
                fnames = zf.namelist()
            txt = javap(jar, "cn/kerong/penguin/fabric/FabricConsoleExecutor.class")
            check(f"C13 {label} ConsoleExecutor brigadier 直发回归",
                  "com/mojang/brigadier/exceptions/CommandSyntaxException" in txt and
                  re.search(r"CommandDispatcher.*\.execute:\(Ljava/lang/String;Ljava/lang/Object;\)I", txt) is not None)
        if label == "Fabric1201":
            txt = javap(jar, "org/bukkit/entity/FabricPlayer.class")
            check("C14 Fa1201 disconnect→method_14367", "class_3244.method_14367" in txt)
        if label == "Fabric":
            txt = javap(jar, "org/bukkit/entity/FabricPlayer.class")
            check("C15 Fa1204 disconnect→method_52396", "class_3244.method_52396" in txt)
        if label in ("Fabric", "Fabric1201"):
            unmapped = scan_mc_refs(jar)
            whitelist = {
                "net/minecraft/class_124.ordinal ()I",
                "net/minecraft/class_124.values ()[Lnet/minecraft/class_124;",
                "net/minecraft/class_2561.getString ()Ljava/lang/String;",
                "net/minecraft/server/MinecraftServer.execute (Ljava/lang/Runnable;)V",
            }
            bad = [r for r in unmapped if r not in whitelist]
            check(f"C16 {label} 未映射引用仅白名单 4 条",
                  not bad and len(unmapped) <= 4, "; ".join(unmapped[:8]))

        # ---------- N(T3). Fabric 端限制策略（仅 Fa 两包） ----------
        if label in ("Fabric", "Fabric1201"):
            with zipfile.ZipFile(jar) as zf:
                fnames = zf.namelist()
            br = "cn/kerong/penguin/fabric/BindRestrict.class"
            bri = "cn/kerong/penguin/fabric/BindRestrictInteractions.class"
            brc = "cn/kerong/penguin/fabric/BindRestrictCommandGate.class"
            check(f"T3a {label} BindRestrict 类存在", br in fnames)
            check(f"T3b {label} BindRestrictInteractions 类存在", bri in fnames)
            check(f"T3c {label} BindRestrictCommandGate 类存在", brc in fnames)
            if br in fnames:
                bt = javap(jar, br)
                with zipfile.ZipFile(jar) as z2:
                    brbytes = z2.read(br)
                oj = method_body(bt, "onJoin")
                check(f"T3d {label} onJoin 生成绑定码 generateCode",
                      "generateCode" in oj, oj[:150])
                check(f"T3e {label} onJoin 已绑定放行 isBoundByUuid",
                      "isBoundByUuid" in oj)
                tk = method_body(bt, "tick")
                # 重映射后方法引用为 intermediary 名（tiny 表核实：
                # teleport(DDDFF)=method_14363 两版同；sendSystemMessage=method_43496；
                # disconnect：1.20.4=method_52396 / 1.20.1=method_14367）
                disc = "method_52396" if label == "Fabric" else "method_14367"
                check(f"T3f {label} tick 超时踢出（{disc} + 超时文案）",
                      disc in bt and "QQ 绑定超时".encode("utf-8") in brbytes, tk[:150])
                check(f"T3g {label} tick/lockAnchor 锚点锁定（method_14363）",
                      "method_14363" in bt)
                check(f"T3h {label} tick 绑定检测 isBoundByUuid（解限）",
                      "isBoundByUuid" in tk)
                check(f"T3i {label} tick 绑定码输出（method_43496）",
                      "method_43496" in bt)
                la = method_body(bt, "lockAnchor")
                check(f"T3j {label} lockAnchor 传送回锚点（method_14363+getAnchorX+position）",
                      "method_14363" in la and "getAnchorX" in la and "method_19538" in la)
                check(f"T3k {label} hasPendingCode 过期换新码接线",
                      "hasPendingCode" in bt)
            if bri in fnames:
                it = javap(jar, bri)
                check(f"T3l {label} onChat/onCommand/onUseBlock/onAttackBlock/onUseEntity 五入口",
                      all(m in it for m in
                          ("onChat", "onCommand", "onUseBlock", "onAttackBlock", "onUseEntity")))
            if brc in fnames:
                gt = javap(jar, brc)
                gp_inner = "cn/kerong/penguin/fabric/BindRestrictCommandGate$GatePredicate.class"
                if gp_inner in fnames:
                    gp_txt = javap(jar, gp_inner)
                    check(f"T3n {label} GatePredicate 查 isRestricted（拒受限玩家命令）",
                          "isRestricted" in gp_txt)
                else:
                    check(f"T3n {label} GatePredicate 内部类存在", False)
                check(f"T3m {label} GatePredicate 包装 + Unsafe requirement 替换",
                      "GatePredicate" in gt and "putObject" in gt and "theUnsafe" in gt)
            fe = "cn/kerong/penguin/fabric/FabricEvents.class"
            if fe in fnames:
                ft = javap(jar, fe)
                ra = method_body(ft, "registerAll")
                check(f"T3o {label} FabricEvents 接线五个新回调",
                      all(k in ra for k in
                          ("END_SERVER_TICK", "ALLOW_COMMAND_MESSAGE",
                           "UseBlockCallback", "AttackBlockCallback", "UseEntityCallback")),
                      ra[:200])
                # JOIN/DISCONNECT 接线在 lambda 合成方法里：按全文（含合成方法）断言
                check(f"T3p {label} JOIN/DISCONNECT 接 BindRestrict（全文含合成方法）",
                      "BindRestrict.onJoin" in ft and "BindRestrict.onQuit" in ft)
                hac = method_body(ft, "handleAllowChat")
                check(f"T3q {label} handleAllowChat 不再做限制拦截（由 onChat 前置）",
                      "BindRestrictInteractions" not in hac)
            fc = "cn/kerong/penguin/fabric/FabricCommands.class"
            if fc in fnames:
                ct = javap(jar, fc, ("-v",))
                check(f"T3r {label} /hbkrg qbd 已删除（反断言：无 qbd literal）",
                      'literal("qbd")' not in ct and '"qbd"' not in ct)
                check(f"T3s {label} qbd 取码残留文案不存在（反断言）",
                      "获取新验证码" not in ct and "获取绑定验证码请用" not in ct)
                check(f"T3t {label} 帮助含自动显示绑定码口径",
                      "自动显示在聊天栏" in ct or "自动在聊天栏持续显示绑定码" in ct)

        # ---------- N. 1758 假人豁免回归（Spigot / NF 本轮不动） ----------
        if label == "Spigot":
            with zipfile.ZipFile(jar) as zf:
                gc = "cn/kerong/penguin/spigot/events/GameChat.class"
                names3 = zf.namelist()
                if gc in names3:
                    txt4 = javap(jar, gc)
                    lfl = method_body(txt4, "isLocalFakeLogin")
                    lfj = method_body(txt4, "isLocalFakeJoin")
                    opl = method_body(txt4, "onPlayerLogin")
                    ejb = method_body(txt4, "enforceJoinBinding")
                    check("R1 Spigot isLocalFakeLogin 存在且判回环（回归）",
                          "isLoopbackAddress" in lfl, lfl[:100])
                    check("R1b Spigot isLocalFakeJoin 存在且判回环（回归）",
                          "isLoopbackAddress" in lfj, lfj[:100])
                    check("R1c Spigot onPlayerLogin 接线假人豁免（回归）",
                          "isLocalFakeLogin" in opl)
                    check("R1d Spigot enforceJoinBinding 接线假人豁免（回归）",
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
                    check("R2 NF isLocalFakeConnection 回归",
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
            # fat JAR 目录条目阈值仅对 Spigot（NF 661 / Fa 680 为历史基线，两版一致）
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
    print(f"\n===== verify_jar_1759o2：{len(results)} 项，失败 {len(failed)} =====")
    for name, ok, detail in failed:
        print(f"  FAIL {name}  {detail[:160]}")
    print("全绿" if not failed else "存在失败项")
    sys.exit(0 if not failed else 1)


if __name__ == "__main__":
    main()

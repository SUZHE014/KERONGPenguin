#!/usr/bin/env python3
"""由 1757c 门禁生成 1758 门禁：版本/路径替换 + 追加 N 段（1.7.5.8 两项新功能）断言。"""
import re
from pathlib import Path

SRC = Path("/home/z/my-project/penguin-git/scripts/verify_jar_1757c.py")
DST = Path("/home/z/my-project/penguin-git/scripts/verify_jar_1758.py")

text = SRC.read_text(encoding="utf-8")

# ---- 版本与路径替换 ----
text = text.replace("KERONGPenguin_Spigot-1.7.5.7.jar", "KERONGPenguin_Spigot-1.7.5.8.jar")
text = text.replace("KERONGPenguin_NeoForge-1.20.4-1.7.5.7.jar", "KERONGPenguin_NeoForge-1.20.4-1.7.5.8.jar")
text = text.replace("KERONGPenguin_Fabric-1.20.4-1.7.5.7.jar", "KERONGPenguin_Fabric-1.20.4-1.7.5.8.jar")
text = text.replace("KERONGPenguin_Fabric-1.20.1-1.7.5.7.jar", "KERONGPenguin_Fabric-1.20.1-1.7.5.8.jar")
text = text.replace('"""1.7.5.7 再覆盖轮门禁：四分发 JAR 字节码核查。',
                    '"""1.7.5.8 门禁：四分发 JAR 字节码核查（1757c 全量回归 + 本轮两项新功能）。')
# A 段版本断言：1.7.5.7 → 1.7.5.8（A1/A2/A2b/A3）
text = text.replace('re.search(r"version:\\s*\'?\\"?1\\.7\\.5\\.7', 're.search(r"version:\\s*\'?\\"?1\\.7\\.5\\.8')
text = text.replace('check(f"A2 NF {mfile} 含 1.7.5.7", "1.7.5.7" in mt)',
                    'check(f"A2 NF {mfile} 含 1.7.5.8", "1.7.5.8" in mt)')
text = text.replace('check("A2b NF MOD_VERSION 1.7.5.7（ldc 常量池）",\n                      "1.7.5.7" in javap',
                    'check("A2b NF MOD_VERSION 1.7.5.8（ldc 常量池）",\n                      "1.7.5.8" in javap')
text = text.replace('check(f"A3 {label} fabric.mod.json 版本 1.7.5.7",\n                      re.search(r\'"version":\\s*"1\\.7\\.5\\.7"\'',
                    'check(f"A3 {label} fabric.mod.json 版本 1.7.5.8",\n                      re.search(r\'"version":\\s*"1\\.7\\.5\\.8"\'')
# config-version 48 → 49（A4 + B10b/B10c 改查 49，48 迁移为历史回归保留）
text = text.replace('check(f"A4 {label} config-version 48（删旧键轮）", re.search(r"config-version:\\s*48", cfg) is not None, cfg[:120])',
                    'check(f"A4 {label} config-version 49（显示名来源轮）", re.search(r"config-version:\\s*49", cfg) is not None, cfg[:120])')
text = text.replace('check(f"B10b {label} ConfigManager 版本 48（sipush 0x30）",\n                      b"\\x11\\x00\\x30" in cb or b"\\x10\\x30" in cb, "sipush/bipush 48")',
                    'check(f"B10b {label} ConfigManager 版本 49（sipush 0x31）",\n                      b"\\x11\\x00\\x31" in cb or b"\\x10\\x31" in cb, "sipush/bipush 49")')
text = text.replace('check(f"B10c {label} 升 48 迁移块（bipush 48 比较在方法体）",\n                      re.search(r"\\bsipush\\s+48\\b|\\bbipush\\s+48\\b", txt) is not None)',
                    'check(f"B10c {label} 升 49 迁移块（sipush/bipush 49 比较在方法体）",\n                      re.search(r"\\bsipush\\s+49\\b|\\bbipush\\s+49\\b", txt) is not None)')

# ---- 追加 N 段（插在 failed 汇总前、for 循环内末尾 H 段之后）----
n_section = '''
            # ---------- N. 1.7.5.8 新功能 ----------
            # N1 群消息转发显示名来源键
            check(f"N1 {label} group-sender-name: group 键在位",
                  re.search(r"group-sender-name:\\s*group", cfg) is not None)
            gsn = "cn/kerong/bot/tools/GroupSenderName.class"
            if gsn in names:
                txt_g = javap(jar, gsn)
                res_body = method_body(txt_g, "resolve")
                check(f"N2 {label} GroupSenderName.resolve 存在",
                      "Code:" in res_body, res_body[:100])
                check(f"N2b {label} resolve 读 ChatFormat.getGroupSenderName",
                      "getGroupSenderName" in res_body)
                check(f"N2c {label} resolve 反查 getBoundPlayerNameByOpenid",
                      "getBoundPlayerNameByOpenid" in res_body)
            else:
                check(f"N2 {label} GroupSenderName 类存在", False)
            if gmr in names:
                fw = method_body(javap(jar, gmr), "forwardFullGroupMessage")
                check(f"N3 {label} 全量转发接线 GroupSenderName.resolve",
                      "GroupSenderName.resolve" in fw)
            pbc = "cn/kerong/bot/events/commands/PublicCommands.class"
            if pbc in names:
                sm = method_body(javap(jar, pbc), "sendGameMessage")
                check(f"N4 {label} /发信息 接线 GroupSenderName.resolve",
                      "GroupSenderName.resolve" in sm)
            if qqm in names:
                gq = method_body(javap(jar, qqm), "getBoundPlayerNameByOpenid")
                check(f"N5 {label} getBoundPlayerNameByOpenid 存在（getQuuidByOpenid+readQuuidRecord）",
                      "getQuuidByOpenid" in gq and "readQuuidRecord" in gq and "playerName" in gq)
            cfc = "cn/kerong/bot/provider/ConfigProvider$ChatFormat.class"
            if cfc not in names:
                cfc = "cn/kerong/bot/provider/ChatFormat.class"
            if cfc in names:
                check(f"N6 {label} ChatFormat.getGroupSenderName 组件",
                      "getGroupSenderName" in javap(jar, cfc, ("-p",)))
            if cm in names:
                cf_body = method_body(javap(jar, cm), "chatFormat")
                check(f"N7 {label} ConfigManager 读 chat-format.group-sender-name",
                      "chat-format.group-sender-name" in cf_body)
'''
spigot_n = '''
        # ---------- N. 1.7.5.8 假人豁免（Spigot 端） ----------
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
                    check("N8 Spigot isLocalFakeLogin 存在且判回环",
                          "isLoopbackAddress" in lfl, lfl[:100])
                    check("N8b Spigot isLocalFakeJoin 存在且判回环",
                          "isLoopbackAddress" in lfj, lfj[:100])
                    check("N8c Spigot onPlayerLogin 接线假人豁免",
                          "isLocalFakeLogin" in opl)
                    check("N8d Spigot enforceJoinBinding 接线假人豁免",
                          "isLocalFakeJoin" in ejb)
                else:
                    check("N8 Spigot GameChat 类存在", False)
'''
nf_n = '''
        # ---------- N. 1.7.5.8 假人豁免（NeoForge 端） ----------
        if label == "NeoForge":
            with zipfile.ZipFile(jar) as zf:
                names4 = zf.namelist()
                blg = "cn/kerong/penguin/neoforge/BindLoginGate.class"
                nfe = "cn/kerong/penguin/neoforge/NeoForgeEvents.class"
                if blg in names4:
                    txt5 = javap(jar, blg)
                    # internal 方法后缀无关断言（$server_NeoForge 后缀）
                    ilfc = "\\n".join(method_body(txt5, m) for m in
                                      set(re.findall(r"isLocalFakeConnection(?:\\$\\w+)?", txt5)))
                    check("N9 NF BindLoginGate.isLocalFakeConnection 存在",
                          "isLoopbackAddress" in ilfc and "isMemoryConnection" in ilfc
                          and "getRemoteAddress" in ilfc, ilfc[:120])
                    ek = method_body(txt5, "evaluateKick")
                    check("N9b NF evaluateKick 接线假人豁免",
                          "isLocalFakeConnection" in ek)
                else:
                    check("N9 NF BindLoginGate 类存在", False)
                if nfe in names4:
                    elb = method_body(javap(jar, nfe), "enforceLoginBinding")
                    check("N10 NF enforceLoginBinding 接线假人豁免",
                          "isLocalFakeConnection" in elb)
                else:
                    check("N10 NF NeoForgeEvents 类存在", False)
'''

anchor = "        # ---------- C(Fabric 端字节码细节) ----------"
assert anchor in text, "C(Fabric) 锚点未找到"
text = text.replace(anchor, n_section + "\n" + anchor)

anchor2 = "        # ---------- H. 形态 ----------"
assert anchor2 in text, "H 形态锚点未找到"
text = text.replace(anchor2, spigot_n + nf_n + "\n" + anchor2)

DST.write_text(text, encoding="utf-8")
print(f"生成 {DST}（{len(text.splitlines())} 行）")

#!/usr/bin/env python3
"""生成 verify_jar_1761.py：以 verify_jar_176o4.py 为底，升级版本断言 + 追加 1.7.6.1 假人过滤 FK 组。"""
import re
from pathlib import Path

SRC = Path("/home/z/my-project/penguin-git/scripts/verify_jar_176o4.py")
DST = Path("/home/z/my-project/penguin-git/scripts/verify_jar_1761.py")

text = SRC.read_text(encoding="utf-8")

# ---------- 文档头替换 ----------
text = text.replace(
    '"""1.7.6 三次覆盖轮门禁：四分发 JAR 字节码核查（二次覆盖轮全量回归 + 本轮任务）。',
    '"""1.7.6.1 门禁：四分发 JAR 字节码核查（1.7.6 三次覆盖轮全量回归 + 本轮假人过滤任务）。',
)
text = text.replace(
    """本轮改动（用户指令，同号重发 1.7.6）：""",
    """本轮改动（用户指令 1.7.6.1 新版本）：
  FK 假人插件假人过滤——①在线排行榜不再计入假人时长（FakePlayerRegistry
     统一口径：连接空/回环/内存连接 或 qq-bind.fake-player-whitelist 名单
     命中；join 事件登记 fake-players.json，真人真实连接自愈移除；两个
     收集源存档直枚举/插件统计都过滤，过滤计数写日志）；②新配置键
     online-list.show-fake-players（默认关）：开启后 /查在线 主列表只显示
     真实玩家、末页底部额外画「假人在线」小节（markdown 文字版同开关
     同口径，人数只算真实玩家+末尾假人摘要行）；③白名单语义全端化。
  上轮改动（1.7.6 三次覆盖轮，全量回归）：""",
)

# ---------- JAR 路径 ----------
for old, new in (
    ("KERONGPenguin_Spigot-1.7.6.jar", "KERONGPenguin_Spigot-1.7.6.1.jar"),
    ("KERONGPenguin_NeoForge-1.20.4-1.7.6.jar", "KERONGPenguin_NeoForge-1.20.4-1.7.6.1.jar"),
    ("KERONGPenguin_Fabric-1.20.4-1.7.6.jar", "KERONGPenguin_Fabric-1.20.4-1.7.6.1.jar"),
    ("KERONGPenguin_Fabric-1.20.1-1.7.6.jar", "KERONGPenguin_Fabric-1.20.1-1.7.6.1.jar"),
):
    text = text.replace(old, new)

# ---------- 版本断言收紧到 1.7.6.1 ----------
text = text.replace(
    '''check("A1 Spigot plugin.yml 版本 1.7.6",
                  re.search(r"version:\\s*'?\\"?1\\.7\\.6", py) is not None)''',
    '''check("A1 Spigot plugin.yml 版本 1.7.6.1",
                  re.search(r"version:\\s*'?\\"?1\\.7\\.6\\.1", py) is not None)''',
)
text = text.replace(
    '''check(f"A2 NF {mfile} 含 1.7.6", "1.7.6" in mt)''',
    '''check(f"A2 NF {mfile} 含 1.7.6.1", "1.7.6.1" in mt)''',
)
text = text.replace(
    '''check("A2b NF MOD_VERSION 1.7.6（ldc 常量池）",
                      "1.7.6" in javap(jar, "cn/kerong/penguin/neoforge/KERONGNeoForge.class", ("-c", "-p")))''',
    '''check("A2b NF MOD_VERSION 1.7.6.1（ldc 常量池）",
                      "1.7.6.1" in javap(jar, "cn/kerong/penguin/neoforge/KERONGNeoForge.class", ("-c", "-p")))''',
)
text = text.replace(
    '''check(f"A3 {label} fabric.mod.json 版本 1.7.6",
                      re.search(r'"version":\\s*"1\\.7\\.6"', fm) is not None)''',
    '''check(f"A3 {label} fabric.mod.json 版本 1.7.6.1",
                      re.search(r'"version":\\s*"1\\.7\\.6\\.1"', fm) is not None)''',
)
# config-version 54 -> 55（A4 + T1q）
text = text.replace(
    '''check(f"A4 {label} config-version 54（三次覆盖轮：默认源改存档）",
                  re.search(r"config-version:\\s*54", cfg) is not None, cfg[:120])''',
    '''check(f"A4 {label} config-version 55（1.7.6.1：假人过滤轮）",
                  re.search(r"config-version:\\s*55", cfg) is not None, cfg[:120])''',
)
text = text.replace(
    '''check(f"T1q {label} CURRENT_CONFIG_VERSION 编译期内联 54（bipush 54 存在）",
                      re.search(r"bipush\\s+54", javap(jar, cm, ("-c",))) is not None)''',
    '''check(f"T1q {label} CURRENT_CONFIG_VERSION 编译期内联 55（bipush 55 存在）",
                      re.search(r"bipush\\s+55", javap(jar, cm, ("-c",))) is not None)''',
)
# 汇总输出名
text = text.replace('print(f"\\n===== verify_jar_176o4：', 'print(f"\\n===== verify_jar_1761：')

# ---------- 追加 FK 组（插在汇总块之前） ----------
FK_BLOCK = '''
            # ---------- FK. 假人插件假人过滤（1.7.6.1 本轮新增） ----------
            fpr = "cn/kerong/penguin/spigot/stats/FakePlayerRegistry.class"
            check(f"FK1 {label} FakePlayerRegistry 类存在", fpr in names)
            if fpr in names:
                fbytes = zf.read(fpr)
                ft = javap(jar, fpr, ("-c", "-p"))
                ftp = javap(jar, fpr, ("-p",))
                check(f"FK2 {label} evaluateJoin 三参签名（UUID, String, boolean）",
                      re.search(r"evaluateJoin\\(java\\.util\\.UUID, java\\.lang\\.String, boolean\\)", ftp) is not None,
                      ftp[:200])
                check(f"FK3 {label} liveIsFake 签名（Player → boolean）",
                      re.search(r"liveIsFake\\(org\\.bukkit\\.entity\\.Player\\)", ftp) is not None)
                # ⚠ 泛型全形态坑：Set<java.util.UUID>
                check(f"FK4 {label} isExcluded 签名（UUID, String, Set<UUID>）",
                      re.search(r"isExcluded\\(java\\.util\\.UUID, java\\.lang\\.String, java\\.util\\.Set<", ftp) is not None,
                      ftp[:260])
                check(f"FK5 {label} isFakeName 签名（markdown 名字判定）",
                      re.search(r"isFakeName\\(java\\.lang\\.String\\)", ftp) is not None)
                check(f"FK6 {label} connectionDetector 平台注入字段在位",
                      "connectionDetector" in ftp)
                check(f"FK7 {label} 登记文件 fake-players.json（字节级）",
                      b"fake-players.json" in fbytes)
                check(f"FK8 {label} 白名单键读取（字节级）",
                      b"qq-bind.fake-player-whitelist" in fbytes)
                check(f"FK9 {label} 登记日志文案（字节级）",
                      "记为假人插件假人".encode("utf-8") in fbytes
                      and "自愈移除".encode("utf-8") in fbytes)
                ej = method_body(ft, "evaluateJoin")
                check(f"FK10 {label} evaluateJoin 白名单合并 + 登记/自愈双路",
                      "whitelisted" in ej and ("markFake" in ej or "unmarkFake" in ej), ej[:200])
                lif = method_body(ft, "liveIsFake")
                check(f"FK11 {label} liveIsFake 名单或连接检测器",
                      "whitelisted" in lif and "connectionDetector" in lif, lif[:160])
                iex = method_body(ft, "isExcluded")
                check(f"FK12 {label} isExcluded 三路判定（在线集 + 持久登记 + 名单）",
                      "contains" in iex and "whitelisted" in iex and "ensureLoaded" in iex, iex[:200])
                per = method_body(ft, "persist")
                check(f"FK13 {label} persist 原子写（tmp + renameTo）",
                      "renameTo" in per, per[:160])

            # 排行榜假人过滤接线
            if lbc in names:
                lbbytes2 = zf.read(lbc)
                check(f"FK20 {label} 排行榜假人过滤日志（字节级）",
                      "假人过滤".encode("utf-8") in lbbytes2)
                check(f"FK21 {label} 排行榜接线 FakePlayerRegistry.isExcluded",
                      "FakePlayerRegistry.isExcluded" in lbt, lbt[:300])
                osnap = "cn/kerong/bot/events/commands/LeaderboardCommands$OnlineSnapshot.class"
                check(f"FK22 {label} OnlineSnapshot 快照类（players + fakeUuids）",
                      osnap in names and "getFakeUuids" in javap(jar, osnap, ("-p",)))
                sop = method_body(lbt, "snapshotOnlinePlayers")
                check(f"FK23 {label} snapshotOnlinePlayers 快照内假人实时判定",
                      "liveIsFake" in sop, sop[:200])

            # 查在线假人分流
            ols = "cn/kerong/penguin/spigot/stats/OnlineListService.class"
            if ols in names:
                olbytes = zf.read(ols)
                olt = javap(jar, ols, ("-c", "-p"))
                pinfo = "cn/kerong/penguin/spigot/stats/OnlineListService$PlayerInfo.class"
                check(f"FK30 {label} PlayerInfo 快照条目类（fake 标记）",
                      pinfo in names and "getFake" in javap(jar, pinfo, ("-p",)))
                check(f"FK31 {label} 快照内 liveIsFake 判定",
                      "liveIsFake" in olt)
                check(f"FK32 {label} 缓存 key 假人段（#fake# 分隔，字节级）",
                      b"#fake#" in olbytes)
                check(f"FK33 {label} 假人分流走 RenderSettings.onlineListShowFakePlayers",
                      "onlineListShowFakePlayers" in olt)
                check(f"FK34 {label} 渲染传 fakeEntries",
                      "fakeEntries" in olt)

            olr = "cn/kerong/penguin/spigot/render/OnlineListRenderer.class"
            if olr in names:
                olrbytes = zf.read(olr)
                olrt = javap(jar, olr, ("-c", "-p"))
                olrp = javap(jar, olr, ("-p",))
                check(f"FK40 {label} OnlineListData.fakeEntries 字段（默认空）",
                      "fakeEntries" in olrp)
                check(f"FK41 {label} drawFakeListHeader 小节头在位",
                      "drawFakeListHeader" in olrp)
                check(f"FK42 {label} 假人小节文案（字节级）",
                      "假人在线".encode("utf-8") in olrbytes)
                check(f"FK43 {label} drawPlayerGrid 返回底部 + topOverride/emptyAllowed 参数",
                      re.search(r"drawPlayerGrid\\(java\\.awt\\.Graphics2D.*boolean, int, boolean\\)", olrp) is not None,
                      olrp[:300])
                check(f"FK44 {label} measurePageHeight 三参（含 fakeCount）",
                      re.search(r"measurePageHeight\\(int, boolean, int\\)", olrp) is not None)
                check(f"FK45 {label} renderPages 末页带假人小节",
                      "fakeEntries" in method_body(olrt, "renderPages"))
                check(f"FK46 {label} renderSingle 画假人小节（drawFakeListHeader 接线）",
                      "drawFakeListHeader" in olrt and "FAKE_SECTION_GAP" not in olrt
                      and "drawFakeListHeader" in method_body(olrt, "renderSingle"))

            if rs in names:
                rsbytes = zf.read(rs)
                check(f"FK50 {label} RenderSettings.onlineListShowFakePlayers 读取新键",
                      "onlineListShowFakePlayers" in javap(jar, rs, ("-p",))
                      and b"online-list.show-fake-players" in rsbytes)

            if cmd_cls in names and "Public" in cmd_cls:
                pcbytes = zf.read(cmd_cls)
                pct = javap(jar, cmd_cls, ("-c", "-p"))
                qo = method_body(pct, "queryOnline")
                check(f"FK60 {label} markdown 查在线分流（isFakeName 双向过滤）",
                      "isFakeName" in qo and "onlineListShowFakePlayers" in qo, qo[:260])
                check(f"FK61 {label} markdown 假人摘要行（字节级）",
                      "假人在线（".encode("utf-8") in pcbytes)

            # 配置模板三件：新键 + 注释口径
            check(f"FK70 {label} online-list.show-fake-players 默认 false",
                  re.search(r"online-list:\\n(  #.*\\n)+  show-fake-players: false", cfg) is not None,
                  cfg[:120])
            check(f"FK71 {label} 假人列表注释说明判定口径（fake-player-whitelist 联动）",
                  "假人插件假人" in cfg and "假人在线" in cfg)
            check(f"FK72 {label} 白名单注释全端化（1.7.6.1 起所有端共用）",
                  "所有端共用本名单识别假人" in cfg)
            if cm in names:
                check(f"FK73 {label} DEFAULT_VALUES 含 online-list.show-fake-players（字节级）",
                      b"online-list.show-fake-players" in cmbytes)

'''

# 插入位置：T7p 金币榜块之后（D 组之前）
anchor = "            # ---------- D. 默认源改存档 + 收敛（三次覆盖新增） ----------"
assert anchor in text, "D 组锚点未找到"
text = text.replace(anchor, FK_BLOCK + anchor, 1)

# ---------- 平台钩子断言：插入各平台回归段 ----------
SPIGOT_HOOK = '''
        if label == "Spigot":
            with zipfile.ZipFile(jar) as zf:
                snames = zf.namelist()
                pel = "cn/kerong/penguin/spigot/events/PlayerEventsListener.class"
                check("FK80 Spigot PlayerEventsListener 假人登记接线",
                      pel in snames and "evaluateJoin" in javap(jar, pel)
                      and "isLocalFakeJoin" in javap(jar, pel))
                ksp = "cn/kerong/penguin/spigot/KERONGSpigot.class"
                if ksp in snames:
                    kst = javap(jar, ksp, ("-c", "-p"))
                    check("FK81 Spigot KERONGSpigot 注册 connectionDetector（onEnable）",
                          "connectionDetector" in kst and "FakePlayerRegistry" in kst
                          and "isLocalFakeConnection" in kst)
'''
anchor2 = '''        # ---------- 回归：Spigot/NF 假人豁免（1.7.5.8，保持） ----------
        if label == "Spigot":'''
assert anchor2 in text, "Spigot 回归锚点未找到"
text = text.replace(anchor2, SPIGOT_HOOK + "\n" + anchor2, 1)

NF_HOOK = '''
        if label == "NeoForge":
            with zipfile.ZipFile(jar) as zf:
                nnames = zf.namelist()
                nfe2 = "cn/kerong/penguin/neoforge/NeoForgeEvents.class"
                if nfe2 in nnames:
                    nft = javap(jar, nfe2, ("-c", "-p"))
                    opj = method_body(nft, "onPlayerJoin")
                    check("FK82 NF onPlayerJoin 假人登记接线（evaluateJoin + isLocalFakeConnection）",
                          "evaluateJoin" in opj and "isLocalFakeConnection" in opj, opj[:220])
                knf = "cn/kerong/penguin/neoforge/KERONGNeoForge.class"
                if knf in nnames:
                    knt = javap(jar, knf, ("-c", "-p"))
                    check("FK83 NF KERONGNeoForge 注册 connectionDetector（onServerStarted）",
                          "connectionDetector" in knt and "FakePlayerRegistry" in knt)
'''
anchor3 = '''        if label == "NeoForge":
            with zipfile.ZipFile(jar) as zf:
                names4 = zf.namelist()'''
assert anchor3 in text, "NF 回归锚点未找到"
text = text.replace(anchor3, NF_HOOK + "\n" + anchor3, 1)

FABRIC_HOOK = '''
        if label in ("Fabric", "Fabric1201"):
            with zipfile.ZipFile(jar) as zf:
                fnames2 = zf.namelist()
                if fpe in fnames2:
                    fep = javap(jar, fpe, ("-p",))
                    # 1.7.6.1：isLocalFakePlayer 由 private 改 public（FabricEvents 登记与检测器共用）
                    check(f"FK84 {label} FakePlayerExempt.isLocalFakePlayer 已公开（非 private）",
                          re.search(r"public .*isLocalFakePlayer\\(net\\.minecraft\\.class_7226\\)", fep) is not None
                          or re.search(r"isLocalFakePlayer\\(", fep) is not None and "private" not in fep.split("isLocalFakePlayer")[0][-60:],
                          fep[:300])
                fev = "cn/kerong/penguin/fabric/FabricEvents.class"
                if fev in fnames2:
                    fet = javap(jar, fev, ("-c", "-p"))
                    check(f"FK85 {label} FabricEvents JOIN 假人登记接线",
                          "evaluateJoin" in fet and "isLocalFakePlayer" in fet, fet[:240])
                    check(f"FK86 {label} FabricEvents registerAll 注册 connectionDetector",
                          "connectionDetector" in fet)
'''
anchor4 = '''        # ---------- 回归：Spigot/NF 假人豁免（1.7.5.8，保持） ----------'''
assert anchor4 in text, "Fabric 钩子插入锚点未找到"
text = text.replace(anchor4, FABRIC_HOOK + "\n" + anchor4, 1)

DST.write_text(text, encoding="utf-8")
print(f"[OK] 已生成 {DST}（{len(text.splitlines())} 行）")

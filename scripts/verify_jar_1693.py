#!/usr/bin/env python3
"""verify_jar_1693 —— 1.6.9.3 产物门禁（变量获取扩展 / 查性能人数 / 命令精简）。

背景（用户需求）：
  ① 查信息项目自动隐藏修改：变量读取开启且对应项目填写了变量时，即使
     服务器没有安装对应插件也展示（没有读取到就显示「未获取成功」）；
  ② 自定义查信息项目（项目ID（仅区分不展示）/ 项目名称 / 获取变量），
     reload 重载即生效，正常返回才展示；
  ③ 变量配置新增玩家名、tps；除查信息外其他有对应数据项的地方也拉取
     变量（在线排行榜 / 财富榜 / 查性能），不返回则用插件自带的；
  ④ 查性能在线人数增加服务器最大人数（默认真实值，可配置为其他插件
     修改过的 MOTD 展示人数）；
  ⑤ 删除 /在线服务器 与 /motd 命令。

断言组：
  VF 组  变量获取扩展（双 JAR 字节级 + 源码）
       VF1  PlaceholderVariableService 新嵌套类（Context / CustomItem /
            ResolvedCustomItem）+ MotdDisplayTracker 在位（双 JAR）
       VF2  配置键常量：player-name / tps / custom-items 完整字面量 + 模板形态
       VF3  自定义项目子键常量：项目名称 / 获取变量（UTF8）
       VF4  未获取成功常量 + QueryInfoService 引用 FETCH_FAILED_TEXT
       VF5  源码 buildItems：三处「configured 显隐 + 未获取成功兜底」+ 自定义项循环
       VF6  卡片头部玩家名变量覆盖（vars.overrides.playerName ?: playerName）
       VF7  resolveBatch 在位（javap）+ 双排行榜调用（total-playtime / coins）
       VF8  resolveServerTps + resolveOnMainThread 在位；PerformanceService 调用链
       VF9  timeoutSnapshot 用 EMPTY_CONTEXT（超时不做变量解析）
       VF10 ConfigManager：版本 24 + 新键（player-name / tps / custom-items）
            + custom-items 键读取（getConfigurationSection）
  PF 组  查性能最大人数 + ping 捕获
       PF1  PerformanceSnapshot.maxPlayers 字段（javap -p）
       PF2  KERONGSpigot.performanceSnapshot 读 getMaxPlayers（javap -c）
       PF3  KERONGNeoForge.performanceSnapshot 读 getMaxPlayers
       PF4  PlayerEventsListener.onServerListPing（MONITOR）在位（javap -c）
       PF5  PerformanceRenderer.PerformanceData 三新字段（javap -p）
       PF6  PerformanceRenderer 渲染常量（N / M 拼接 / MOTD 展示值 / 已读取）
       PF7  双端 config.yml + JAR 内置：max-players-source: "real"
  CD 组  命令删除
       CD1  MotdCommands 类 + motd-*.md / server-status.md 资源全部不在（双 JAR）
       CD2  PublicCommands 无 @Commands("motd") / @Commands("在线服务器")
       CD3  MessageTemplates 常量池无 motd-* / server-status 模板 ID
       CD4  MenuManager 按钮表无「在线服务器」/「motd」
       CD5  GroupMessageHandler 无 MotdCommands 引用
       CD6  help.md 无两条命令行
       CD7  config.yml（源 + JAR 内置）commands/messages 无两命令相关键
       CD8  ConfigManager REMOVED_CONFIG_KEYS 含两命令清理键
  V 组  版本六处 1.6.9.3
  B 组  基线回归（B1-B8 同 1.6.9 门禁 + B9 MainThreadSnapshot vars 字段）

用法：python3 scripts/verify_jar_1693.py [spigot_jar] [nf_jar]
"""
import pathlib
import re
import subprocess
import sys
import tempfile
import tomllib
import zipfile

SPIGOT_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_Spigot-1.6.9.3.jar"
NF_JAR = sys.argv[2] if len(sys.argv) > 2 else "build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.9.3.jar"
JAVAP = str(pathlib.Path("/home/z/my-project/tooling/jdk21/bin/javap"))
ROOT = pathlib.Path(__file__).resolve().parent.parent

checks = []


def check(name, cond, detail=""):
    checks.append((name, bool(cond), detail))
    suffix = f" — {detail}" if detail else ""
    print(f"  [{'PASS' if cond else 'FAIL'}] {name}{suffix}")


def read_class(jar, entry):
    with zipfile.ZipFile(jar) as z:
        return z.read(entry)


def _javap_run(jar, entry, flags):
    """按包路径展开后 javap（类文件路径必须与全限定名一致，否则常量池输出被降级）。"""
    with tempfile.TemporaryDirectory() as tmp:
        cls = pathlib.Path(tmp) / entry
        cls.parent.mkdir(parents=True, exist_ok=True)
        cls.write_bytes(read_class(jar, entry))
        fqcn = entry[:-len(".class")].replace("/", ".")
        out = subprocess.run(
            [JAVAP, "-p", *flags, "-cp", tmp, fqcn],
            capture_output=True, text=True,
        )
        if out.returncode != 0:
            raise RuntimeError(f"javap failed: {out.stderr[:400]}")
        return out.stdout


def javap_c(jar, entry):
    """javap -p -c 反汇编（含私有方法体）。"""
    return _javap_run(jar, entry, ("-c",))


def utf8_constants(jar, entry):
    """类的常量池字符串集合（Utf8 条目与代码区 // String 两形态取并集）。"""
    out = _javap_run(jar, entry, ("-v",))
    strings = set()
    for line in out.splitlines():
        m = re.match(r"\s*#\d+ = Utf8\s+(.*)$", line)
        if m:
            strings.add(m.group(1))
        elif "// String " in line:
            strings.add(line.split("// String ", 1)[1])
    return strings


def main():
    spigot = pathlib.Path(SPIGOT_JAR)
    nf = pathlib.Path(NF_JAR)
    for p in (spigot, nf):
        if not p.exists():
            raise SystemExit(f"JAR 不存在：{p}")

    src_query = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/QueryInfoService.kt").read_text(encoding="utf-8")
    src_vf = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/PlaceholderVariableService.kt").read_text(encoding="utf-8")
    src_perf = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/PerformanceService.kt").read_text(encoding="utf-8")
    src_cfgmgr = (ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt").read_text(encoding="utf-8")
    src_public = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/PublicCommands.kt").read_text(encoding="utf-8")
    src_menu = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/MenuManager.kt").read_text(encoding="utf-8")
    src_tpl = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/qqbind/MessageTemplates.kt").read_text(encoding="utf-8")
    src_lb = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/LeaderboardCommands.kt").read_text(encoding="utf-8")
    src_clb = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/bot/events/commands/CoinsLeaderboardCommands.kt").read_text(encoding="utf-8")
    cfg_spigot = (ROOT / "server-Spigot/src/main/resources/config.yml").read_text(encoding="utf-8")
    cfg_nf = (ROOT / "server-NeoForge/src/main/resources/config.yml").read_text(encoding="utf-8")

    VF_CLASS = "cn/kerong/penguin/spigot/stats/PlaceholderVariableService.class"
    VF_CTX = "cn/kerong/penguin/spigot/stats/PlaceholderVariableService$Context.class"
    VF_CUSTOM = "cn/kerong/penguin/spigot/stats/PlaceholderVariableService$CustomItem.class"
    VF_RESOLVED = "cn/kerong/penguin/spigot/stats/PlaceholderVariableService$ResolvedCustomItem.class"
    TRACKER_CLASS = "cn/kerong/penguin/spigot/stats/MotdDisplayTracker.class"
    QUERY_CLASS = "cn/kerong/penguin/spigot/stats/QueryInfoService.class"
    SNAPSHOT_CLASS = "cn/kerong/penguin/spigot/stats/QueryInfoService$MainThreadSnapshot.class"
    PERF_SERVICE = "cn/kerong/penguin/spigot/stats/PerformanceService.class"
    PERF_RENDERER = "cn/kerong/penguin/spigot/render/PerformanceRenderer.class"
    PERF_RENDERER_DATA = "cn/kerong/penguin/spigot/render/PerformanceRenderer$PerformanceData.class"
    INFO_CLASS = "cn/kerong/penguin/spigot/render/InfoCardAssets.class"

    print("== VF 组：变量获取扩展 ==")
    with zipfile.ZipFile(spigot) as z:
        spigot_names = z.namelist()
    with zipfile.ZipFile(nf) as z:
        nf_names = z.namelist()
    for label, names in (("Spigot", spigot_names), ("NeoForge", nf_names)):
        check(f"VF1 {label} 新嵌套类 + MotdDisplayTracker 在位",
              all(c in names for c in (VF_CLASS, VF_CTX, VF_CUSTOM, VF_RESOLVED, TRACKER_CLASS)))

    vf_consts = utf8_constants(spigot, VF_CLASS)
    check("VF2 配置键常量（player-name / tps / custom-items + 模板形态）",
          "player-name" in vf_consts
          and "tps" in vf_consts
          and "variable-fetch.custom-items" in vf_consts
          and "variable-fetch.\\u0001" in vf_consts)
    check("VF3 自定义项目子键常量（模板形态 \\u0001.项目名称 / \\u0001.获取变量）",
          "\\u0001.项目名称" in vf_consts and "\\u0001.获取变量" in vf_consts)
    check("VF4 未获取成功常量在位（服务 + 查信息引用）",
          "未获取成功" in vf_consts, "FETCH_FAILED_TEXT")

    dis_query = javap_c(spigot, QUERY_CLASS)
    check("VF4b QueryInfoService 调用 snapshotContext + Context 取值 + 未获取成功内联",
          "PlaceholderVariableService.snapshotContext:(Ljava/lang/String;Ljava/util/UUID;Lorg/bukkit/entity/Player;)" in dis_query
          and "PlaceholderVariableService$Context.getCoinsConfigured" in dis_query
          and "PlaceholderVariableService$Context.getPointsConfigured" in dis_query
          and "PlaceholderVariableService$Context.getTitleConfigured" in dis_query
          and "PlaceholderVariableService$Context.getCustomItems" in dis_query
          and "// String 未获取成功" in dis_query)

    build_src = re.search(r"private fun buildItems\(.*?\n    \}", src_query, re.S)
    check("VF5 buildItems 三处 configured 显隐 + 未获取成功兜底 + 自定义项循环",
          build_src is not None and all(
              s in build_src.group(0) for s in (
                  "snapshot.vaultAvailable || snapshot.vars.coinsConfigured",
                  "snapshot.titleAvailable || snapshot.vars.titleConfigured",
                  "snapshot.pointsAvailable || snapshot.vars.pointsConfigured",
                  "PlaceholderVariableService.FETCH_FAILED_TEXT",
                  "for (custom in snapshot.vars.customItems)")),
          "填了变量必展示，读不到显示未获取成功")

    check("VF6 卡片头部玩家名变量覆盖",
          "snapshot.vars.overrides.playerName ?: playerName" in src_query,
          "CardData playerName")

    vf_dis = javap_c(spigot, VF_CLASS)
    check("VF7 resolveBatch 在位（javap，含 List<Pair<String,UUID>> 参数）",
          "resolveBatch(java.util.List" in vf_dis)
    check("VF7b 双排行榜调用 resolveBatch（total-playtime / coins）",
          'nameKey = "player-name",\n                valueKey = "total-playtime"' in src_lb
          and 'nameKey = "player-name",\n                valueKey = "coins"' in src_clb
          and "snapshotVariableOverrides" in src_lb and "snapshotVariableOverrides" in src_clb)
    check("VF7c 排行榜条目应用变量覆盖（名称 + valueText）",
          "variableOverrides[raw.name]?.first ?: raw.name" in src_lb
          and "variableOverrides[raw.name]?.first ?: raw.name" in src_clb
          and "variableOverrides[raw.name]?.second" in src_lb
          and "variableOverrides[raw.name]?.second" in src_clb)

    check("VF8 resolveServerTps + resolveOnMainThread 在位",
          "fun resolveServerTps" in src_vf and "fun <T> resolveOnMainThread" in src_vf)
    dis_perf = javap_c(spigot, PERF_SERVICE)
    check("VF8b PerformanceService 调用链（isConfigured + resolveOnMainThread + resolveServerTps）",
          "PlaceholderVariableService.isConfigured" in dis_perf
          and "PlaceholderVariableService.resolveOnMainThread" in dis_perf
          and "PlaceholderVariableService.resolveServerTps" in dis_perf)

    timeout_src = re.search(r"private fun timeoutSnapshot\(\): MainThreadSnapshot = MainThreadSnapshot\(.*?\n    \)", src_query, re.S)
    check("VF9 timeoutSnapshot 用 EMPTY_CONTEXT（超时不做变量解析）",
          timeout_src is not None and "vars = PlaceholderVariableService.EMPTY_CONTEXT" in timeout_src.group(0))

    check("VF10 ConfigManager 版本 24 + 新键 + custom-items 段读取",
          re.search(r"CURRENT_CONFIG_VERSION = 24", src_cfgmgr) is not None
          and '"variable-fetch.player-name" to ""' in src_cfgmgr
          and '"variable-fetch.tps" to ""' in src_cfgmgr
          and '"variable-fetch.custom-items"' in src_cfgmgr
          and 'getConfigurationSection("variable-fetch.custom-items")' in src_vf)

    print("== PF 组：查性能最大人数 ==")
    snap_class = "cn/kerong/bot/PerformanceSnapshot.class"
    snap_meta = _javap_run(spigot, snap_class, ("-p",))
    check("PF1 PerformanceSnapshot.maxPlayers 字段在位",
          "maxPlayers" in snap_meta and "java.lang.Integer maxPlayers" in snap_meta)

    spigot_main = "cn/kerong/penguin/spigot/KERONGSpigot.class"
    dis_spigot = javap_c(spigot, spigot_main)
    check("PF2 Spigot 读真实最大人数（getMaxPlayers + 5 参 capture）",
          "org/bukkit/Server.getMaxPlayers" in dis_spigot
          and "capture:(Ljava/lang/Double;Ljava/lang/Double;Ljava/lang/Double;Ljava/lang/Integer;Ljava/lang/Integer;)" in dis_spigot)

    nf_main = "cn/kerong/penguin/neoforge/KERONGNeoForge.class"
    dis_nfmain = javap_c(nf, nf_main)
    nf_perf_body = dis_nfmain[dis_nfmain.find("performanceSnapshot"):dis_nfmain.find("performanceSnapshot") + 6000]
    check("PF3 NeoForge 读真实最大人数（maxPlayers 属性）",
          "getMaxPlayers" in nf_perf_body or "maxPlayers" in nf_perf_body)

    listener = "cn/kerong/penguin/spigot/events/PlayerEventsListener.class"
    dis_listener = javap_c(spigot, listener)
    check("PF4 ServerListPingEvent MONITOR 监听在位",
          "onServerListPing" in dis_listener
          and "org/bukkit/event/server/ServerListPingEvent" in dis_listener
          and "MotdDisplayTracker.record" in dis_listener)

    data_meta = _javap_run(spigot, PERF_RENDERER_DATA, ("-p",))
    check("PF5 PerformanceData 三新字段",
          all(f in data_meta for f in ("maxPlayers", "maxPlayersFromMotd", "tpsVariableText")))

    render_consts = utf8_constants(spigot, PERF_RENDERER)
    check("PF6 渲染常量（MOTD 展示值 / 已读取 / 人数上限文案）",
          any("（MOTD 展示值）" in c for c in render_consts)
          and "已读取" in render_consts
          and any("服务器最大 \\u0001 人" in c for c in render_consts)
          and any("最大 \\u0001 人（MOTD 展示值）" in c for c in render_consts))
    check("PF6b parseDoubleOrNull 在位（TPS 变量数值解析）",
          "parseDoubleOrNull" in javap_c(spigot, PERF_RENDERER))

    for label, cfg in (("Spigot", cfg_spigot), ("NeoForge", cfg_nf)):
        check(f"PF7 {label} config.yml max-players-source",
              re.search(r'^\s*max-players-source: "real"$', cfg, re.M) is not None
              and "max-players-source" in cfg)
    for label, jar in (("Spigot", spigot), ("NeoForge", nf)):
        with zipfile.ZipFile(jar) as z:
            res = z.read("config.yml").decode("utf-8")
        check(f"PF7b {label} JAR 内置 config.yml 含 max-players-source",
              'max-players-source: "real"' in res)

    print("== CD 组：命令删除 ==")
    for label, names, jar in (("Spigot", spigot_names, spigot), ("NeoForge", nf_names, nf)):
        motd_cmd = [n for n in names if "MotdCommands" in n]
        motd_md = [n for n in names if re.match(r"Markdown/(motd-[^/]+|server-status)\.md$", n)]
        check(f"CD1 {label} MotdCommands 类与 motd-*/server-status 模板资源不在",
              not motd_cmd and not motd_md,
              f"类残留 {motd_cmd} / 资源残留 {motd_md}")

    check("CD2 PublicCommands 无两条命令注解",
          '@Commands("motd")' not in src_public
          and '@Commands("在线服务器")' not in src_public
          and "MotdCommands" not in src_public.replace("1.6.9.3：移除 /在线服务器 与 /motd 两条命令（用户需求；服务器地址", ""))

    tpl_class = "cn/kerong/penguin/spigot/qqbind/MessageTemplates.class"
    tpl_consts = utf8_constants(spigot, tpl_class)
    check("CD3 MessageTemplates 常量池无 motd-* / server-status 模板 ID",
          not any(s in tpl_consts for s in
                  ("motd-usage", "motd-query-failed", "motd-offline", "motd-error", "motd-status", "server-status")))

    menu_class = "cn/kerong/bot/MenuManager.class"
    menu_consts = utf8_constants(spigot, menu_class)
    check("CD4 MenuManager 按钮表无「在线服务器」/「motd」按钮名",
          "在线服务器" not in menu_consts
          and not any(c == "motd" for c in menu_consts))

    handler = "cn/kerong/bot/events/GroupMessageHandler.class"
    handler_consts = utf8_constants(spigot, handler)
    check("CD5 GroupMessageHandler 无 MotdCommands 引用",
          "cn/kerong/bot/events/commands/MotdCommands" not in handler_consts)

    with zipfile.ZipFile(spigot) as z:
        help_md = z.read("Markdown/help.md").decode("utf-8")
    check("CD6 help.md 无两条命令行",
          "/在线服务器" not in help_md
          and not re.search(r"^.*- /motd ", help_md, re.M))

    for label, cfg in (("Spigot", cfg_spigot), ("NeoForge", cfg_nf)):
        check(f"CD7 {label} config.yml commands/messages 无两命令键",
              re.search(r"^\s*在线服务器:", cfg, re.M) is None
              and re.search(r"^\s*motd: (true|false)", cfg, re.M) is None
              and re.search(r"^\s*motd-usage:", cfg, re.M) is None
              and re.search(r"^\s*server-status:", cfg, re.M) is None)
    for label, jar in (("Spigot", spigot), ("NeoForge", nf)):
        with zipfile.ZipFile(jar) as z:
            res = z.read("config.yml").decode("utf-8")
        check(f"CD7b {label} JAR 内置 config.yml 同步",
              re.search(r"^\s*在线服务器:", res, re.M) is None
              and re.search(r"^\s*motd-usage:", res, re.M) is None)

    check("CD8 ConfigManager REMOVED_CONFIG_KEYS 含两命令清理键",
          all(k in src_cfgmgr for k in (
              '"commands.在线服务器"', '"commands.motd"', '"messages.server-status"',
              '"messages.motd-usage"', '"messages.motd-status"')))

    print("== V 组：版本六处 ==")
    with zipfile.ZipFile(spigot) as z:
        plugin_yml = z.read("plugin.yml").decode("utf-8")
    check("V1 plugin.yml 1.6.9.3", "version: '1.6.9.3'" in plugin_yml)
    with zipfile.ZipFile(nf) as z:
        nf_toml = z.read("META-INF/neoforge.mods.toml").decode("utf-8")
        nf_mods = z.read("META-INF/mods.toml").decode("utf-8")
    check("V2 neoforge.mods.toml 1.6.9.3", 'version = "1.6.9.3"' in nf_toml)
    check("V3 mods.toml 双声明同步", 'version = "1.6.9.3"' in nf_mods)
    root_build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    nf_build = (ROOT / "server-NeoForge/build.gradle.kts").read_text(encoding="utf-8")
    kerong_nf = (ROOT / "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt").read_text(encoding="utf-8")
    check("V4 根/NF build.gradle.kts 1.6.9.3",
          'version = "1.6.9.3"' in root_build and 'version = "1.6.9.3"' in nf_build)
    check("V5 MOD_VERSION 1.6.9.3", 'MOD_VERSION = "1.6.9.3"' in kerong_nf)
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    check("V6 README 当前版本 1.6.9.3", "当前版本：**1.6.9.3**" in readme)

    print("== B 组：基线回归 ==")
    dir_count = sum(1 for n in spigot_names if n.endswith("/"))
    check("B1 Spigot 目录条目 > 700", dir_count > 700, f"实际 {dir_count}")
    nf_dirs = sum(1 for n in nf_names if n.endswith("/"))
    has_shaded = any(n.startswith("cn/kerong/shaded/") for n in nf_names)
    bare_kotlin = any(n.startswith("kotlin/") and n.endswith(".class") for n in nf_names)
    has_slf4j = any(n.startswith("org/slf4j/") for n in nf_names)
    has_gson = any(n.startswith("com/google/gson/") for n in nf_names)
    with zipfile.ZipFile(nf) as z:
        toml_raw = z.read("META-INF/neoforge.mods.toml")
    check("B1b NF 目录条目 > 600", nf_dirs > 600, f"实际 {nf_dirs}")
    check("B2 NF 重定位（shaded 在 / kotlin 不裸露）", has_shaded and not bare_kotlin)
    check("B3 NF 剔除 slf4j / gson", not has_slf4j and not has_gson)

    gate_entry = next((n for n in nf_names if "BindLoginGate" in n and n.endswith(".class")
                       and "$" not in n), None)
    dis_gate = javap_c(nf, gate_entry) if gate_entry else ""
    check("B4 BindLoginGate addListener 反射注册保持（无 @SubscribeEvent）",
          "addListener" in dis_gate and "SubscribeEvent" not in dis_gate,
          "1.6.7.11 闸门修复回归")

    mods_bytes = bytes([0x5B, 0x5B, 0x6D, 0x6F, 0x64, 0x73, 0x5D, 0x5D])  # [[mods]]
    broken_bytes = bytes([0x5B, 0x5B, 0x6F, 0x64, 0x73, 0x5D, 0x5D])        # [[ods]
    toml_code_lines = "\n".join(
        l for l in toml_raw.decode("utf-8").splitlines() if not l.strip().startswith("#"))
    check("B5 toml 段头字节级 [ods]]（非注释行）",
          mods_bytes in toml_code_lines.encode("utf-8")
          and broken_bytes not in toml_code_lines.encode("utf-8"))
    parsed = tomllib.loads(toml_raw.decode("utf-8"))
    check("B5b toml 可解析且 modId 正确",
          parsed.get("mods", [{}])[0].get("modId") == "kerongpenguin")

    q_consts = utf8_constants(spigot, QUERY_CLASS)
    check("B6 1.6.8 回归：QueryInfoService 常量池无「天」", "天" not in q_consts)

    dis_info = javap_c(spigot, INFO_CLASS)
    lines = dis_info.splitlines()
    method_body = None
    for idx, line in enumerate(lines):
        if "fetchViaExternalChain" in line and "(" in line:
            body = []
            for l2 in lines[idx:]:
                body.append(l2)
                if len(body) > 1 and l2.startswith("  }"):
                    break
            method_body = "\n".join(body)
            break
    ok_order = method_body is not None and \
        method_body.find("fetchExternalAvatar") != -1 and \
        method_body.find("resolveMojangSkinUrl") != -1 and \
        method_body.find("fetchExternalAvatar") < method_body.find("resolveMojangSkinUrl")
    check("B7 1.6.8 回归：外部头像线路先于 Mojang 官方", ok_order)

    info_consts = utf8_constants(spigot, INFO_CLASS)
    check("B8 1.6.8 回归：四条外部线路 URL 保持",
          all(s in info_consts for s in
              ("https://mc-heads.net/avatar/\\u0001/216", "https://minotar.net/avatar/\\u0001/216",
               "https://minepic.org/avatar/\\u0001/216",
               "https://crafatar.com/avatars/\\u0001?size=216&overlay")))

    snap_meta2 = _javap_run(spigot, SNAPSHOT_CLASS, ("-p",))
    check("B9 1.6.9 回归：MainThreadSnapshot.vars 字段（Context 类型）",
          "vars" in snap_meta2
          and "PlaceholderVariableService$Context vars" in snap_meta2)

    failed = [c for c in checks if not c[1]]
    print(f"\n共 {len(checks)} 项，通过 {len(checks) - len(failed)}，失败 {len(failed)}")
    if failed:
        for name, _, detail in failed:
            print(f"  FAIL: {name} {detail}")
        sys.exit(1)
    print("verify_jar_1693 全部通过")


if __name__ == "__main__":
    main()

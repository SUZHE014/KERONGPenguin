#!/usr/bin/env python3
"""verify_jar_169 —— 1.6.9 产物门禁（变量获取信息）。

背景（用户需求）：
  新增「变量获取信息」配置（variable-fetch 段，默认关闭）——开启后
  /查信息 卡片的总在线时长 / 今日在线时长 / 金币 / 点券 / 称号五个项目
  会先拉取服主填写的 PlaceholderAPI 变量，输出变量返回的内容；变量留空
  = 用插件自带数据；变量获取失败自动回退插件自带数据。

断言组：
  VF 组  变量获取信息（双 JAR 字节级 + 源码）
       VF1  PlaceholderVariableService + $Overrides 类在位（双 JAR）
       VF2  配置键常量：variable-fetch.enabled 完整字面量 + variable-fetch.\u0001
            模板形态（placeholderOf 的键拼接）
       VF3  反射构件：me.clip.placeholderapi.PlaceholderAPI / setPlaceholders /
            org/bukkit/OfflinePlayer（与称号链路同模式）
       VF4  日志常量：PAPI 缺失整句 + 采用/回退/留空模板（排障日志）
       VF5  QueryInfoService 反汇编（-p）含 snapshot 调用与 Overrides 取值
       VF6  MainThreadSnapshot 五个 var 字段在位（javap -p）
       VF7  源码 buildItems：五处 elvis 覆盖 + 依赖显隐扩展（|| varX != null）
       VF8  双端 config.yml：variable-fetch 段六键 + config-version: 23
       VF9  双 JAR 内置 config.yml 资源同步（variable-fetch / config-version 23）
       VF10 ConfigManager：CURRENT_CONFIG_VERSION = 23 + DEFAULT_VALUES 六键
       VF11 timeoutSnapshot 五个 var 字段 null（主线程超时不做变量解析）
  V 组  版本六处 1.6.9（plugin.yml / 双 toml / 根与 NF build.gradle.kts / MOD_VERSION）
  B 组  基线回归：
       B1  fat JAR 目录条目 > 700 / NF > 600（SDK 组件扫描 NPE 防线）
       B2  NF 重定位：cn/kerong/shaded 存在、kotlin/ 不裸露
       B3  NF 剔除 org/slf4j 与 com/google/gson（split package 防线）
       B4  BindLoginGate addListener 反射注册（1.6.7.11 回归，无 @SubscribeEvent）
       B5  双 toml 段头字节级 [ods]]（非注释行）+ tomllib 解析 modId
       B6  1.6.8 回归：QueryInfoService 常量池无「天」
       B7  1.6.8 回归：fetchViaExternalChain 外部线路先于 Mojang 官方
       B8  1.6.8 回归：四条外部头像线路 URL 常量在位

用法：python3 scripts/verify_jar_169.py [spigot_jar] [nf_jar]
"""
import pathlib
import re
import subprocess
import sys
import tempfile
import tomllib
import zipfile

SPIGOT_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_Spigot-1.6.9.jar"
NF_JAR = sys.argv[2] if len(sys.argv) > 2 else "build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.9.jar"
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
    src_cfgmgr = (ROOT / "server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager/ConfigManager.kt").read_text(encoding="utf-8")
    cfg_spigot = (ROOT / "server-Spigot/src/main/resources/config.yml").read_text(encoding="utf-8")
    cfg_nf = (ROOT / "server-NeoForge/src/main/resources/config.yml").read_text(encoding="utf-8")
    src_info = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt").read_text(encoding="utf-8")

    VF_CLASS = "cn/kerong/penguin/spigot/stats/PlaceholderVariableService.class"
    VF_OVERRIDES = "cn/kerong/penguin/spigot/stats/PlaceholderVariableService$Overrides.class"
    QUERY_CLASS = "cn/kerong/penguin/spigot/stats/QueryInfoService.class"
    SNAPSHOT_CLASS = "cn/kerong/penguin/spigot/stats/QueryInfoService$MainThreadSnapshot.class"
    INFO_CLASS = "cn/kerong/penguin/spigot/render/InfoCardAssets.class"

    print("== VF 组：变量获取信息 ==")
    for label, jar in (("Spigot", spigot), ("NeoForge", nf)):
        with zipfile.ZipFile(jar) as z:
            names = z.namelist()
        check(f"VF1 {label} PlaceholderVariableService + Overrides 在位",
              VF_CLASS in names and VF_OVERRIDES in names)

    vf_consts = utf8_constants(spigot, VF_CLASS)
    check("VF2 配置键常量（完整 + 模板形态）",
          "variable-fetch.enabled" in vf_consts and "variable-fetch.\\u0001" in vf_consts,
          "variable-fetch.enabled / variable-fetch.\\u0001")
    check("VF3 PAPI 反射构件",
          "me.clip.placeholderapi.PlaceholderAPI" in vf_consts
          and "setPlaceholders" in vf_consts
          and "org/bukkit/OfflinePlayer" in vf_consts)
    check("VF4 日志常量（PAPI 缺失整句 + 采用/回退/留空模板）",
          "[变量] variable-fetch 已开启但未检测到 PlaceholderAPI，本次全部使用插件自带数据" in vf_consts
          and "\\u0001=留空" in vf_consts
          and "\\u0001=\\'\\u0001\\'→失败(回退自带)" in vf_consts
          and "\\u0001=\\'\\u0001\\'→\\'\\u0001\\'(采用)" in vf_consts
          and "[变量] 查信息 \\u0001:\\u0001" in vf_consts)

    dis_query = javap_c(spigot, QUERY_CLASS)
    check("VF5 QueryInfoService 调用 snapshot + Overrides 取值",
          "PlaceholderVariableService.snapshot:(Ljava/lang/String;Ljava/util/UUID;Lorg/bukkit/entity/Player;)"
          in dis_query
          and "PlaceholderVariableService$Overrides.getCoins" in dis_query
          and "PlaceholderVariableService$Overrides.getTitle" in dis_query)

    snap_meta = _javap_run(spigot, SNAPSHOT_CLASS, ("-p",))
    check("VF6 MainThreadSnapshot var 字段在位",
          all(f in snap_meta for f in
              ("varPlaytime", "varToday", "varCoins", "varPoints", "varTitle")))

    build_src = re.search(r"private fun buildItems\(.*?\n    \}", src_query, re.S)
    check("VF7 buildItems elvis 覆盖 + 显隐扩展",
          build_src is not None and all(
              s in build_src.group(0) for s in (
                  "snapshot.varCoins\n                    ?:",
                  "snapshot.varTitle ?: snapshot.title",
                  "snapshot.varPlaytime ?: formatPlayTime",
                  "snapshot.varToday ?: formatTodayTime",
                  "snapshot.varPoints ?: snapshot.pointsText",
                  "snapshot.vaultAvailable || snapshot.varCoins != null",
                  "snapshot.titleAvailable || snapshot.varTitle != null",
                  "snapshot.pointsAvailable || snapshot.varPoints != null")),
          "五处 elvis + 三处 || varX != null")

    for label, cfg in (("Spigot", cfg_spigot), ("NeoForge", cfg_nf)):
        m = re.search(r"^variable-fetch:\n((?:  #.*\n|  [\w-]+: .*\n)+)", cfg, re.M)
        ok = m is not None and all(
            s in m.group(0) for s in (
                "enabled: false", 'total-playtime: ""', 'today-playtime: ""',
                'coins: ""', 'points: ""', 'title: ""'))
        check(f"VF8 {label} config.yml variable-fetch 段", ok)
        check(f"VF8b {label} config-version: 23",
              re.search(r"^config-version: 23$", cfg, re.M) is not None)

    for label, jar in (("Spigot", spigot), ("NeoForge", nf)):
        with zipfile.ZipFile(jar) as z:
            res = z.read("config.yml").decode("utf-8")
        check(f"VF9 {label} JAR 内置 config.yml 同步",
              "variable-fetch:" in res and "enabled: false" in res
              and re.search(r"^config-version: 23$", res, re.M) is not None)

    check("VF10 ConfigManager 版本 23 + DEFAULT_VALUES 六键",
          re.search(r"CURRENT_CONFIG_VERSION = 23", src_cfgmgr) is not None
          and all(k in src_cfgmgr for k in (
              '"variable-fetch.enabled" to false',
              '"variable-fetch.total-playtime" to ""',
              '"variable-fetch.today-playtime" to ""',
              '"variable-fetch.coins" to ""',
              '"variable-fetch.points" to ""',
              '"variable-fetch.title" to ""')))

    timeout_src = re.search(r"private fun timeoutSnapshot\(\): MainThreadSnapshot = MainThreadSnapshot\(.*?\n    \)", src_query, re.S)
    check("VF11 timeoutSnapshot 五个 var 字段 null（共 8 处 null）",
          timeout_src is not None and timeout_src.group(0).count("null") == 8
          and "varPlaytime = null" in timeout_src.group(0)
          and "varTitle = null" in timeout_src.group(0))

    check("VF12 源码注释含「留空/失败回退」规则（与教程一致口径）",
          "变量留空" in src_vf and "回退" in src_vf and "默认关闭" in src_vf)

    print("== V 组：版本六处 ==")
    with zipfile.ZipFile(spigot) as z:
        plugin_yml = z.read("plugin.yml").decode("utf-8")
    check("V1 plugin.yml 1.6.9", "version: '1.6.9'" in plugin_yml)
    with zipfile.ZipFile(nf) as z:
        nf_toml = z.read("META-INF/neoforge.mods.toml").decode("utf-8")
        nf_mods = z.read("META-INF/mods.toml").decode("utf-8")
    check("V2 neoforge.mods.toml 1.6.9", 'version = "1.6.9"' in nf_toml)
    check("V3 mods.toml 双声明同步", 'version = "1.6.9"' in nf_mods)
    root_build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    nf_build = (ROOT / "server-NeoForge/build.gradle.kts").read_text(encoding="utf-8")
    kerong_nf = (ROOT / "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt").read_text(encoding="utf-8")
    check("V4 根/NF build.gradle.kts 1.6.9",
          'version = "1.6.9"' in root_build and 'version = "1.6.9"' in nf_build)
    check("V5 MOD_VERSION 1.6.9", 'MOD_VERSION = "1.6.9"' in kerong_nf)
    readme = (ROOT / "README.md").read_text(encoding="utf-8")
    check("V6 README 当前版本 1.6.9", "当前版本：**1.6.9**" in readme)

    print("== B 组：基线回归 ==")
    with zipfile.ZipFile(spigot) as z:
        names = z.namelist()
        dir_count = sum(1 for n in names if n.endswith("/"))
    check("B1 Spigot 目录条目 > 700", dir_count > 700, f"实际 {dir_count}")
    with zipfile.ZipFile(nf) as z:
        nf_names = z.namelist()
        nf_dirs = sum(1 for n in nf_names if n.endswith("/"))
        has_shaded = any(n.startswith("cn/kerong/shaded/") for n in nf_names)
        bare_kotlin = any(n.startswith("kotlin/") and n.endswith(".class") for n in nf_names)
        has_slf4j = any(n.startswith("org/slf4j/") for n in nf_names)
        has_gson = any(n.startswith("com/google/gson/") for n in nf_names)
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

    mods_bytes = b"[mods]]"[:0] or bytes([0x5B, 0x5B, 0x6D, 0x6F, 0x64, 0x73, 0x5D, 0x5D])  # [mods]]
    broken_bytes = bytes([0x5B, 0x5B, 0x6F, 0x64, 0x73, 0x5D, 0x5D])                        # [[ods]
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

    failed = [c for c in checks if not c[1]]
    print(f"\n共 {len(checks)} 项，通过 {len(checks) - len(failed)}，失败 {len(failed)}")
    if failed:
        for name, _, detail in failed:
            print(f"  FAIL: {name} {detail}")
        sys.exit(1)
    print("verify_jar_169 全部通过")


if __name__ == "__main__":
    main()

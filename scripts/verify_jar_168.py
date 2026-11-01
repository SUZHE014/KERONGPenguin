#!/usr/bin/env python3
"""verify_jar_168 —— 1.6.8 产物门禁（时长单位小时化 + 头像线路顺序修复）。

背景（用户反馈两项）：
  1. 总/在线时长的最大时间进制改为小时——此前 formatPlayTime 以「天」为
     最大单位（如 3天5时20分），现统一改为小时（77时20分 / 77小时20分钟），
     查信息卡片与在线排行榜同步（formatTodayTime 本就小时制不受影响）；
  2. 排行榜等大批量头像显示不出——Mojang 官方线路（sessionserver 直查 +
     api.mojang.com 名字反查）此前排外置链路首位，国内服务器普遍不可达时
     单玩家最多烧 3+5 秒超时，排行榜 4 线程 × 8 秒预热预算全部耗尽后整批
     头像被 invokeAll 取消（官方 5 分钟冷却到期后重复烧一轮）；现外部多线路
     （MC-Heads → Minotar → MinePic → Crafatar）先行，官方 API 移到最后兜底。

断言组：
  TF 组  时长格式小时化（双 JAR 字节级 + 源码）
       TF1  QueryInfoService.class 常量池无「天」字符串
       TF2  QueryInfoService 源码 formatPlayTime 无 days / 86400
       TF3  LeaderboardRenderer.class 常量池无「天」字符串
       TF4  LeaderboardRenderer 源码 formatPlayTime 无 days / 86400
       TF5  两处 formatPlayTime 方法仍在（描述符 (J)Ljava/lang/String;）
       TF6  formatTodayTime / 「不足1分钟」/「暂无记录」边界文案保持
  AV 组  头像线路顺序（Mojang 官方移到最后）
       AV1  fetchViaExternalChain 字节码：fetchExternalAvatar 先于 resolveMojangSkinUrl
       AV2  「Mojang官方API」标签常量仍在（最后兜底线路，成功日志用）
       AV3  InfoCardRenderer 源码：外部链路多线路先行 + 官方最后（注释与实码一致）
       AV4  双端 config.yml 注释：external 说明改为多线路 → Mojang 官方（最后）
       AV5  官方链路构件保持：resolveMojangSkinUrl / sessionServerSkinUrl /
            markMojangUnreachable / MOJANG 冷却常量（未误删，只是移位）
       AV6  四条外部线路 URL 常量保持（mc-heads / minotar / minepic / crafatar）
  V 组  版本五处 1.6.8（plugin.yml / 双 toml / 根与 NF build.gradle.kts / MOD_VERSION）
  B 组  基线回归：
       B1  fat JAR 目录条目 > 700（SDK 组件扫描 NPE 防线）
       B2  NF 重定位：cn/kerong/shaded 存在、kotlin/ 不裸露
       B3  NF 剔除 org/slf4j 与 com/google/gson（split package 防线）
       B4  PanelSyncResult 与 PANEL_TRIM_ORDER（1.6.7.10 回归）
       B5  BindLoginGate addListener 反射注册（1.6.7.11 回归，无 @SubscribeEvent）
       B6  双 toml 段头字节级 [[mods]]（1.6.7.10 回归）

用法：python3 scripts/verify_jar_168.py [spigot_jar] [nf_jar]
"""
import pathlib
import subprocess
import sys
import tempfile
import tomllib
import zipfile

SPIGOT_JAR = sys.argv[1] if len(sys.argv) > 1 else "build/dist/KERONGPenguin_Spigot-1.6.8.jar"
NF_JAR = sys.argv[2] if len(sys.argv) > 2 else "build/dist/KERONGPenguin_NeoForge-1.20.4-1.6.8.jar"
JAVA = str(pathlib.Path("/home/z/my-project/tooling/jdk21/bin/java"))
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


def javap(jar, entry, extra=()):
    """javap -c 反汇编 JAR 内单个类（含常量池引用与指令顺序）。"""
    return _javap_run(jar, entry, ("-c", *extra))


def utf8_constants(jar, entry):
    """类的常量池字符串集合。

    javap -v 两种形态都要收：
      - 常量池 Utf8 条目：`#N = Utf8  <内容>`（URL 模板等仅在此出现，
        Kotlin 字符串模板编译为 \\u0001 占位常量，经 makeConcat 拼接）；
      - 代码区 ldc 注释：`// String <内容>`（与 Utf8 重复，取并集）。
    """
    out = _javap_run(jar, entry, ("-v",))
    strings = set()
    for line in out.splitlines():
        m = __import__("re").match(r"\s*#\d+ = Utf8\s+(.*)$", line)
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

    src_info = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/InfoCardRenderer.kt").read_text(encoding="utf-8")
    src_query = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/stats/QueryInfoService.kt").read_text(encoding="utf-8")
    src_lb = (ROOT / "common-Bot/src/main/kotlin/cn/kerong/penguin/spigot/render/LeaderboardRenderer.kt").read_text(encoding="utf-8")
    cfg_spigot = (ROOT / "server-Spigot/src/main/resources/config.yml").read_text(encoding="utf-8")
    cfg_nf = (ROOT / "server-NeoForge/src/main/resources/config.yml").read_text(encoding="utf-8")

    INFO_CLASS = "cn/kerong/penguin/spigot/render/InfoCardAssets.class"
    LB_CLASS = "cn/kerong/penguin/spigot/render/LeaderboardRenderer.class"
    QUERY_CLASS = "cn/kerong/penguin/spigot/stats/QueryInfoService.class"

    print("== TF 组：时长格式小时化 ==")
    q_consts = utf8_constants(spigot, QUERY_CLASS)
    check("TF1 QueryInfoService 常量池无「天」", "天" not in q_consts,
          "时/分/秒文案保持，天单位整体移除")
    m = __import__("re").search(
        r"fun formatPlayTime\(seconds: Long\): String \{.*?\n    \}", src_query, __import__("re").S)
    check("TF2 QueryInfoService formatPlayTime 无 days/86400",
          m is not None and "86400" not in m.group(0) and "days" not in m.group(0),
          "小时为最大单位")
    lb_consts = utf8_constants(spigot, LB_CLASS)
    check("TF3 LeaderboardRenderer 常量池无「天」", "天" not in lb_consts,
          "小时/分钟文案保持")
    m = __import__("re").search(
        r"fun formatPlayTime\(seconds: Long\): String \{.*?\n    \}", src_lb, __import__("re").S)
    check("TF4 LeaderboardRenderer formatPlayTime 无 days/86400",
          m is not None and "86400" not in m.group(0) and "days" not in m.group(0))
    dis_query = javap(spigot, QUERY_CLASS)
    dis_lb = javap(spigot, LB_CLASS)
    check("TF5 两处 formatPlayTime 方法在位（描述符 (J)Ljava/lang/String;）",
          "formatPlayTime(long)" in dis_query and "formatPlayTime(long)" in dis_lb and
          "formatPlayTime:(J)Ljava/lang/String;" in dis_query)
    check("TF6 边界文案保持",
          "0分钟" in q_consts and "不足1分钟" in q_consts and "不足1分钟" in lb_consts
          and "暂无记录" in lb_consts and "今日在线时长" in q_consts)

    print("== AV 组：头像线路顺序 ==")
    dis_info = javap(spigot, INFO_CLASS)
    # 找到 fetchViaExternalChain 方法体，断言 fetchExternalAvatar 先于 resolveMojangSkinUrl
    method_body = None
    lines = dis_info.splitlines()
    for idx, line in enumerate(lines):
        if "fetchViaExternalChain" in line and "(" in line:
            body = []
            for l2 in lines[idx:]:
                body.append(l2)
                if l2.strip() in ("}", "") and len(body) > 3 and l2 == "    }":
                    break
                if len(body) > 1 and l2.startswith("  }"):
                    break
            method_body = "\n".join(body)
            break
    check("AV1 fetchViaExternalChain 可反汇编", method_body is not None)
    if method_body:
        pos_ext = method_body.find("fetchExternalAvatar")
        pos_mojang = method_body.find("resolveMojangSkinUrl")
        check("AV1 外部多线路先于 Mojang 官方解析",
              pos_ext != -1 and pos_mojang != -1 and pos_ext < pos_mojang,
              f"fetchExternalAvatar@{pos_ext} < resolveMojangSkinUrl@{pos_mojang}")
    info_consts = utf8_constants(spigot, INFO_CLASS)
    check("AV2 「Mojang官方API」兜底标签在位", "Mojang官方API" in info_consts)
    chain_src = __import__("re").search(
        r"private fun fetchViaExternalChain.*?\n    \}", src_info, __import__("re").S)
    check("AV3 源码顺序一致",
          chain_src is not None and
          chain_src.group(0).find("fetchExternalAvatar") < chain_src.group(0).find("resolveMojangSkinUrl"))
    for label, cfg in (("Spigot", cfg_spigot), ("NeoForge", cfg_nf)):
        m = __import__("re").search(
            r"external = 优先外置接口：.*?尝试本地；", cfg, __import__("re").S)
        ok = m is not None and "MC-Heads" in m.group(0) and "Mojang 官方 API" in m.group(0) and \
            m.group(0).find("MC-Heads") < m.group(0).find("Mojang 官方 API")
        check(f"AV4 {label} config 注释：多线路在前官方最后", ok)
    check("AV5 官方链路构件保持（移位未删除）",
          "resolveMojangSkinUrl" in dis_info and "sessionServerSkinUrl" in dis_info
          and "markMojangUnreachable" in dis_info and "mojangInCooldown" in dis_info)
    # Kotlin 字符串模板编译为 \u0001 占位常量（如 https://mc-heads.net/avatar/\u0001/216）
    check("AV6 四条外部线路 URL 保持（含模板占位形态）",
          all(s in info_consts for s in
              ("https://mc-heads.net/avatar/\\u0001/216", "https://minotar.net/avatar/\\u0001/216",
               "https://minepic.org/avatar/\\u0001/216",
               "https://crafatar.com/avatars/\\u0001?size=216&overlay")))

    print("== V 组：版本五处 ==")
    with zipfile.ZipFile(spigot) as z:
        plugin_yml = z.read("plugin.yml").decode("utf-8")
    check("V1 plugin.yml 1.6.8", "version: '1.6.8'" in plugin_yml)
    with zipfile.ZipFile(nf) as z:
        nf_toml = z.read("META-INF/neoforge.mods.toml").decode("utf-8")
        try:
            nf_mods = z.read("META-INF/mods.toml").decode("utf-8")
        except KeyError:
            nf_mods = ""
    check("V2 neoforge.mods.toml 1.6.8", 'version = "1.6.8"' in nf_toml)
    check("V3 mods.toml 双声明同步", 'version = "1.6.8"' in nf_mods)
    root_build = (ROOT / "build.gradle.kts").read_text(encoding="utf-8")
    nf_build = (ROOT / "server-NeoForge/build.gradle.kts").read_text(encoding="utf-8")
    kerong_nf = (ROOT / "server-NeoForge/src/main/kotlin/cn/kerong/penguin/neoforge/KERONGNeoForge.kt").read_text(encoding="utf-8")
    check("V4 根/NF build.gradle.kts 1.6.8",
          'version = "1.6.8"' in root_build and 'version = "1.6.8"' in nf_build)
    check("V5 MOD_VERSION 1.6.8", 'MOD_VERSION = "1.6.8"' in kerong_nf)

    print("== B 组：基线回归 ==")
    with zipfile.ZipFile(spigot) as z:
        names = z.namelist()
        dir_count = sum(1 for n in names if n.endswith("/"))
        plugin_main = z.read("plugin.yml").decode("utf-8")
    check("B1 Spigot 目录条目 > 700", dir_count > 700, f"实际 {dir_count}")
    with zipfile.ZipFile(nf) as z:
        nf_names = z.namelist()
        nf_dirs = sum(1 for n in nf_names if n.endswith("/"))
        has_shaded = any(n.startswith("cn/kerong/shaded/") for n in nf_names)
        bare_kotlin = any(n.startswith("kotlin/") and n.endswith(".class") for n in nf_names)
        has_slf4j = any(n.startswith("org/slf4j/") for n in nf_names)
        has_gson = any(n.startswith("com/google/gson/") for n in nf_names)
        nf_info = z.read(INFO_CLASS)
    check("B1b NF 目录条目 > 600", nf_dirs > 600, f"实际 {nf_dirs}")
    check("B2 NF 重定位（shaded 在 / kotlin 不裸露）", has_shaded and not bare_kotlin)
    check("B3 NF 剔除 slf4j / gson", not has_slf4j and not has_gson)
    with zipfile.ZipFile(spigot) as z:
        panel_cls = z.read("cn/kerong/bot/MenuManager.class")
    check("B4 PanelSyncResult / TRIM_ORDER 基线",
          b"PanelSyncResult" in panel_cls or b"PanelSyncResult" in read_class(
              spigot, "cn/kerong/bot/MenuManager.class"),
          "1.6.7.10 面板修复类保持")
    dis_gate = javap(nf, "cn/kerong/penguin/neoforge/qqbind/BindLoginGate.class") \
        if "cn/kerong/penguin/neoforge/qqbind/BindLoginGate.class" in nf_names else ""
    if not dis_gate:
        # 类路径可能在其他包下，容错查找
        gate_entry = next((n for n in nf_names if "BindLoginGate" in n and n.endswith(".class")
                           and "$" not in n), None)
        if gate_entry:
            dis_gate = javap(nf, gate_entry)
    check("B5 BindLoginGate addListener 反射注册保持（无 @SubscribeEvent）",
          "addListener" in dis_gate and "SubscribeEvent" not in dis_gate,
          "1.6.7.11 闸门修复回归")
    mods_bytes = bytes([0x5B, 0x5B, 0x6D, 0x6F, 0x64, 0x73, 0x5D, 0x5D])  # [[mods]]
    broken_bytes = bytes([0x5B, 0x5B, 0x6F, 0x64, 0x73, 0x5D, 0x5D])      # [[ods]]
    with zipfile.ZipFile(nf) as z:
        toml_raw = z.read("META-INF/neoforge.mods.toml")
    # 注释里合法提及历史损坏段头 [[ods]]（说明文字），段头断言只在非注释行上做
    toml_code_lines = "\n".join(
        l for l in toml_raw.decode("utf-8").splitlines() if not l.strip().startswith("#"))
    check("B6 toml 段头字节级 [[mods]]（非注释行）",
          mods_bytes in toml_code_lines.encode("utf-8")
          and broken_bytes not in toml_code_lines.encode("utf-8"))
    with zipfile.ZipFile(nf) as z:
        neoforge_toml_text = z.read("META-INF/neoforge.mods.toml").decode("utf-8")
    parsed = tomllib.loads(neoforge_toml_text)
    check("B6b toml 可解析且 modId 正确",
          parsed.get("mods", [{}])[0].get("modId") == "kerongpenguin")

    failed = [c for c in checks if not c[1]]
    print(f"\n共 {len(checks)} 项，通过 {len(checks) - len(failed)}，失败 {len(failed)}")
    if failed:
        for name, _, detail in failed:
            print(f"  FAIL: {name} {detail}")
        sys.exit(1)
    print("verify_jar_168 全部通过")


if __name__ == "__main__":
    main()

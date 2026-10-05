#!/usr/bin/env python3
"""
NeoForge 分发模组 JAR 增量补丁打包 —— 1.5.5（在线排行榜 + 签到金币自适应 + 图片分段）。

背景（1.5.5）：本次改动全部位于 common-Bot（10 个顶层类）+ server-Spigot 的
manager/ConfigManager（两处常量）+ 两份 config.yml 资源；server-NeoForge 模块
自身源码零改动、仅 neoforge.mods.toml 版本升 1.5.4.5 → 1.5.5。完整管线
（gradlew :server-NeoForge:jar + package_jar_neoforge.py）需要本机具备
server-NeoForge/libs 真实构件（mojmap MC jar 等）；本脚本沿用 1.5.4.5 验证过的
增量补丁路径：

  1. 从 common-Bot 构建产物（与 Spigot 线同一份字节码——同一 Kotlin 2.2.0
     编译器、同源）取出改动类；ConfigManager 从 server-Spigot 构建产物取出；
  2. 经 scripts/relocate/RelocateTool 重定位（kotlin/kotlinx/okhttp3/okio/
     fastjson/... → cn.huohuas001.shaded.*，与整包重定位同规则同前缀）；
  3. 以官网已发布的 NeoForge 1.5.4.5 分发 JAR 为基底：替换 11 个改动类、
     新增 LeaderboardCommands / LeaderboardRenderer 类族、config.yml 与
     mods.toml / MANIFEST 版本升——其余全部条目逐字节原样保留；
  4. 差异白名单校验：产物与基底 JAR 相比，仅允许白名单内条目存在差异。

链接兼容性（与 1.5.4.5「零 org.bukkit 引用」口径不同——本次改动类包含
org.bukkit 引用，本脚本新增方法级链接校验门禁）：

  - 对每个补丁类解析常量池，抽取全部 Methodref / InterfaceMethodref /
    Fieldref / Class 引用（含 invokedynamic 引导方法间接引用）；
  - 凡 owner 位于 org/bukkit/**（兼容层）或 cn/huohuas001/**（项目类），
    在【最终产物 JAR】里做方法解析（沿父类 / 接口链），要求名字+描述符
    完全命中，且调用种类（invokestatic / invokeinterface / invokevirtual）
    与目标类/接口形态兼容；
  - 已知不可达或有 Throwable 兜底的少量引用进 LINK_WHITELIST（附依据）；
  - 重定位后常量池结构性引用零残留（与 1.5.4.5 门禁同口径）。

用法：
    python3 scripts/patch_jar_neoforge_155.py [base_jar] [-o output.jar]
    （base_jar 缺省用本地 nf-work 下的 1.5.4.5，缺失则从官网下载）
"""
import argparse
import glob
import os
import re
import shutil
import struct
import subprocess
import sys
import urllib.request
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COMMON_JAR = os.path.join(REPO, "common-Bot", "build", "libs", "common-Bot.jar")
SPIGOT_JAR = os.path.join(REPO, "server-Spigot", "build", "libs", "server-Spigot.jar")
TOML_PATH = os.path.join(REPO, "server-NeoForge", "src", "main", "resources",
                         "META-INF", "neoforge.mods.toml")
CONFIG_PATH = os.path.join(REPO, "server-NeoForge", "src", "main", "resources", "config.yml")
DEFAULT_DIST_DIR = os.path.join(REPO, "build", "dist")
DEPS_CACHE_DIR = os.path.join(REPO, "scripts", "deps-work")
ASM_URLS = {
    "asm-9.7.1.jar": "https://repo.maven.apache.org/maven2/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar",
    "asm-commons-9.7.1.jar": "https://repo.maven.apache.org/maven2/org/ow2/asm/asm-commons/9.7.1/asm-commons-9.7.1.jar",
}
RELOCATE_SRC = os.path.join(REPO, "scripts", "relocate", "RelocateTool.java")
RELOCATE_OUT = os.path.join(REPO, "scripts", "relocate", "out")
FIXUP_SRC = os.path.join(REPO, "scripts", "relocate", "FixupTool.java")
FIXUP_OUT = os.path.join(REPO, "scripts", "relocate", "out-fixup")
RELOCATE_PREFIXES = (
    "kotlin/", "kotlinx/", "okhttp3/", "okio/", "com/alibaba/",
    "org/yaml/", "org/bouncycastle/", "org/jsoup/", "org/java_websocket/",
    "org/jetbrains/", "org/intelli/",
)
RELOCATE_TARGET = "cn/huohuas001/shaded"

# ---------------- 补丁类清单 ----------------
# common-Bot 模块（含内部类 / 比较器内联类，从 common-Bot.jar 取）
COMMON_PREFIXES = (
    "cn/huohuas001/bot/MenuManager",
    "cn/huohuas001/bot/events/GroupMessageHandler",
    "cn/huohuas001/bot/events/commands/CheckInCommands",
    "cn/huohuas001/bot/events/commands/LeaderboardCommands",
    "cn/huohuas001/bot/events/commands/PublicCommands",
    "cn/huohuas001/bot/web/WebUiSchema",
    "cn/huohuas001/huhobotPenguin/spigot/qqbind/QqBindManager",
    "cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer",
    "cn/huohuas001/huhobotPenguin/spigot/render/OnlineListRenderer",
    "cn/huohuas001/huhobotPenguin/spigot/stats/OnlineListService",
)
# manager 目录（从 server-Spigot.jar 取）
SPIGOT_PREFIXES = (
    "cn/huohuas001/huhobotPenguin/spigot/manager/ConfigManager",
)

NEW_CLASS_FILES = (
    "cn/huohuas001/bot/events/commands/LeaderboardCommands.class",
    "cn/huohuas001/bot/events/commands/LeaderboardCommands$Companion.class",
    "cn/huohuas001/bot/events/commands/LeaderboardCommands$RawEntry.class",
    "cn/huohuas001/bot/events/commands/LeaderboardCommands$collectEntries$$inlined$compareByDescending$1.class",
    "cn/huohuas001/bot/events/commands/LeaderboardCommands$collectEntries$$inlined$thenBy$1.class",
    "cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer.class",
    "cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer$LeaderEntry.class",
    "cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer$LeaderboardData.class",
)

BASE_URL = "https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.21.1-1.5.4.5.jar"
BASE_LOCAL = os.path.join(os.path.dirname(REPO), "nf-work",
                          "KERONGPenguin_NeoForge-1.21.1-1.5.4.5.jar")

# 链接校验白名单：kind / owner / name / desc → 依据（空——经 FixupTool 字节修复后
# 预期全部引用直接解析，不允许任何豁免；如需豁免必须附运行时行为依据）
LINK_WHITELIST = {}

MOD_ID = "kerongpenguin"

# ---------------- class 文件解析 ----------------

def read_constant_pool(data: bytes):
    """与 scripts 目录 class_strings.py 同口径的常量池解析。"""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("not a class file")
    count = struct.unpack(">H", data[8:10])[0]
    pos = 10
    consts = [None] * count
    i = 1
    while i < count:
        tag = data[pos]
        pos += 1
        if tag == 1:
            length = struct.unpack(">H", data[pos:pos + 2])[0]
            pos += 2
            raw = data[pos:pos + length]
            pos += length
            try:
                text = raw.decode("utf-8", "replace")
            except Exception:
                text = raw.decode("latin-1", "replace")
            consts[i] = ("utf8", text)
        elif tag in (7, 8, 16, 19, 20):
            idx = struct.unpack(">H", data[pos:pos + 2])[0]
            pos += 2
            consts[i] = ("ref", tag, idx)
        elif tag == 15:
            pos += 3
            consts[i] = ("skip", tag)
        elif tag in (3, 4):
            pos += 4
            consts[i] = ("skip", tag)
        elif tag in (5, 6):
            pos += 8
            consts[i] = ("skip", tag)
            consts[i + 1] = ("pad",)
            i += 2
            continue
        elif tag in (9, 10, 11, 12, 17, 18):
            a, b = struct.unpack(">HH", data[pos:pos + 4])
            pos += 4
            consts[i] = ("ref2", tag, a, b)
        else:
            raise ValueError(f"unknown tag {tag} at {pos}")
        i += 1
    return consts, pos


def parse_class(data: bytes):
    """解析 class 文件：常量池 + 访问标志 + 父类 + 接口 + 方法/字段表。"""
    consts, pos = read_constant_pool(data)

    def utf8(idx):
        e = consts[idx]
        return e[1] if e and e[0] == "utf8" else None

    def cls_name(idx):
        e = consts[idx]
        if e and e[0] == "ref" and e[1] == 7:
            return utf8(e[2])
        return None

    access, = struct.unpack(">H", data[pos:pos + 2])
    this_idx, super_idx = struct.unpack(">HH", data[pos + 2:pos + 6])
    n_ifaces, = struct.unpack(">H", data[pos + 6:pos + 8])
    pos += 8
    interfaces = []
    for _ in range(n_ifaces):
        idx, = struct.unpack(">H", data[pos:pos + 2])
        interfaces.append(cls_name(idx))
        pos += 2

    def members():
        nonlocal pos
        out = []
        count, = struct.unpack(">H", data[pos:pos + 2])
        pos += 2
        for _ in range(count):
            flags, name_idx, desc_idx, attr_count = struct.unpack(">HHHH", data[pos:pos + 8])
            pos += 8
            for _ in range(attr_count):
                alen, = struct.unpack(">I", data[pos + 2:pos + 6])
                pos += 6 + alen
            out.append((flags, utf8(name_idx), utf8(desc_idx)))
        return out

    fields = members()
    methods = members()
    return {
        "access": access,
        "this": cls_name(this_idx),
        "super": cls_name(super_idx) if super_idx else None,
        "interfaces": [i for i in interfaces if i],
        "fields": fields,
        "methods": methods,
        "consts": consts,
    }


ACC_INTERFACE = 0x0200
ACC_STATIC = 0x0008


def collect_refs(parsed):
    """从解析结果抽取 (kind, owner, name, desc) 引用集合。

    kind：
      "method"  —— tag 10 Methodref（invokestatic / invokevirtual / invokespecial
                   均用此 tag；目标应为类，接口默认方法的调用在 Kotlin 编译产物中
                   会以接口为 owner 用 tag 11）
      "iface"   —— tag 11 InterfaceMethodref（invokeinterface；目标应为接口）
      "field"   —— tag 9 Fieldref
    """
    consts = parsed["consts"]
    refs = set()

    def resolve_name(idx):
        e = consts[idx]
        if e and e[0] == "ref" and e[1] == 7:
            u = consts[e[2]]
            return u[1] if u and u[0] == "utf8" else None
        return None

    for e in consts:
        if not e or e[0] != "ref2":
            continue
        tag, a, b = e[1], e[2], e[3]
        if tag not in (9, 10, 11):
            continue
        owner = resolve_name(a)
        ne = consts[b]
        if not (ne and ne[0] == "ref2" and ne[1] == 12):
            continue
        name_idx, desc_idx = ne[2], ne[3]
        name = consts[name_idx][1] if consts[name_idx] and consts[name_idx][0] == "utf8" else None
        desc = consts[desc_idx][1] if consts[desc_idx] and consts[desc_idx][0] == "utf8" else None
        if not owner or not name or not desc:
            continue
        kind = {9: "field", 10: "method", 11: "iface"}[tag]
        refs.add((kind, owner, name, desc))
    return refs


def collect_class_refs(parsed):
    """抽取需要落地校验的 Class 类型引用（org/bukkit 与 cn/huohuas001 命名空间）。"""
    consts = parsed["consts"]
    out = set()
    for e in consts:
        if e and e[0] == "ref" and e[1] == 7:
            u = consts[e[2]]
            if u and u[0] == "utf8":
                name = u[1]
                if name.startswith("["):
                    name = name.rsplit("L", 1)[-1].rstrip(";")
                if name.startswith(("org/bukkit/", "cn/huohuas001/")):
                    out.add(name)
    return out


def method_exists(ci, name, desc):
    return any(n == name and d == desc for _, n, d in ci["methods"])


def field_exists(ci, name, desc):
    return any(n == name and d == desc for _, n, d in ci["fields"])


def resolve_member(jar_index, start, name, desc, want):
    """沿父类/接口链解析方法或字段。

    jar_index: {internal_name: parsed_class}
    want: "method" | "field"
    返回 (found_owner, kind_flag)；kind_flag ∈ {"class", "interface"}
    found_owner 为 None 表示未解析到（JDK 类等不可见时返回 ("java", None) 语义
    由调用方豁免——仅当超类不在 jar 内时）。
    """
    worklist = [start]
    seen = set()
    owner_kind = None
    while worklist:
        cur = worklist.pop(0)
        if cur is None or cur in seen or cur == "java/lang/Object":
            continue
        seen.add(cur)
        ci = jar_index.get(cur)
        if ci is None:
            # 超类 / 接口不在产物内（JDK 等）——无法继续下钻
            continue
        owner_kind = "interface" if (ci["access"] & ACC_INTERFACE) else "class"
        if want == "method" and method_exists(ci, name, desc):
            return cur, owner_kind
        if want == "field" and field_exists(ci, name, desc):
            return cur, owner_kind
        worklist.append(ci["super"])
        worklist.extend(ci["interfaces"])
    return None, owner_kind


def verify_link_compat(output_jar: str, patch_classes: dict) -> None:
    """链接兼容性硬校验（本补丁的核心门禁）。

    对每个补丁类：
      1. 常量池 Class 引用：org/bukkit/** 与 cn/huohuas001/** 必须在产物内存在；
      2. Methodref / InterfaceMethodref / Fieldref：owner 在上述两命名空间时，
         在产物内沿父类/接口链解析（名字+描述符全匹配），并校验调用种类
         与目标形态兼容（interface 引用要求接口，method 引用要求类——
         跨形态即 ICCE，须进白名单）；
      3. 白名单 LINK_WHITELIST 逐条给出运行时行为依据。
    """
    jar_index = {}
    with zipfile.ZipFile(output_jar) as zf:
        entries = set(zf.namelist())
        for name in entries:
            if name.endswith(".class") and (name.startswith("org/bukkit/")
                                            or name.startswith("cn/huohuas001/")):
                try:
                    jar_index[name[:-6]] = parse_class(zf.read(name))
                except Exception as exc:
                    raise SystemExit(f"解析产物类 {name} 失败：{exc}")

    failures = []
    whitelisted = []
    checked = 0
    for entry, data in sorted(patch_classes.items()):
        parsed = parse_class(data)
        # 1. Class 引用存在性
        for cref in sorted(collect_class_refs(parsed)):
            if cref not in jar_index and cref + ".class" not in entries:
                failures.append(f"{entry}: Class 引用 {cref} 不在产物内")
        # 2. 成员引用可解析性 + 形态兼容
        for kind, owner, name, desc in sorted(collect_refs(parsed)):
            if not (owner.startswith("org/bukkit/") or owner.startswith("cn/huohuas001/")):
                continue
            checked += 1
            wl_key = {"iface": "interface", "method": "virtual", "field": "field"}[kind]
            wl = LINK_WHITELIST.get((wl_key, owner, name, desc))
            found, found_kind = resolve_member(
                jar_index, owner, name, desc,
                "field" if kind == "field" else "method")
            if kind == "iface":
                # INVOKEINTERFACE：owner 必须是接口且方法可解析
                if found is None:
                    if wl:
                        whitelisted.append((entry, owner, name, desc))
                        continue
                    failures.append(
                        f"{entry}: interface 引用 {owner}.{name}{desc} 无法解析")
                    continue
                if found_kind != "interface" and not wl:
                    failures.append(
                        f"{entry}: interface 引用 {owner}.{name}{desc} 解析到类"
                        f"{found}（ICCE）")
                elif wl:
                    whitelisted.append((entry, owner, name, desc))
            else:
                # Methodref / Fieldref：owner 应为类；解析失败或解析到接口即风险
                if found is None:
                    if wl:
                        whitelisted.append((entry, owner, name, desc))
                        continue
                    failures.append(
                        f"{entry}: {'字段' if kind == 'field' else '方法'}引用 "
                        f"{owner}.{name}{desc} 无法解析")
                    continue
                if found_kind == "interface" and not wl:
                    failures.append(
                        f"{entry}: {'字段' if kind == 'field' else '方法'}引用 "
                        f"{owner}.{name}{desc} 解析到接口 {found}（ICCE 风险）")
                elif wl:
                    whitelisted.append((entry, owner, name, desc))
    if failures:
        print("链接校验失败项：")
        for f in failures:
            print("  ✗", f)
        raise SystemExit(f"链接兼容性校验失败：{len(failures)} 项")
    print(f"链接校验通过：{checked} 项成员引用全部解析"
          f"（白名单豁免 {len(whitelisted)} 处，均附运行时依据）")
    for entry, owner, name, desc in sorted(set(whitelisted)):
        print(f"  ○ 白名单 {owner}.{name}（{entry.split('/')[-1]}）")


def read_source_version() -> str:
    with open(TOML_PATH, encoding="utf-8") as f:
        text = f.read()
    m = re.search(r'^version\s*=\s*"([0-9A-Za-z.\-]+)"', text, re.M)
    if not m:
        raise SystemExit("源码 neoforge.mods.toml 未找到 version 字段")
    return m.group(1)


def read_jar_version(jar: str) -> str:
    with zipfile.ZipFile(jar) as zf:
        text = zf.read("META-INF/neoforge.mods.toml").decode("utf-8")
    m = re.search(r'^version\s*=\s*"([0-9A-Za-z.\-]+)"', text, re.M)
    if not m:
        raise SystemExit(f"{jar} 的 mods.toml 未找到 version 字段")
    return m.group(1)


def ensure_base(path: str) -> str:
    if os.path.isfile(path) and os.path.getsize(path) > 10_000_000:
        return path
    print(f"下载基底 JAR：{BASE_URL}")
    urllib.request.urlretrieve(BASE_URL, path)
    return path


def ensure_asm() -> list:
    jars = []
    os.makedirs(DEPS_CACHE_DIR, exist_ok=True)
    for fname, url in ASM_URLS.items():
        path = os.path.join(DEPS_CACHE_DIR, fname)
        if not (os.path.isfile(path) and os.path.getsize(path) > 50_000):
            print(f"下载 {fname}：{url}")
            urllib.request.urlretrieve(url, path)
        jars.append(path)
    return jars


def find_tool(name: str) -> str:
    found = shutil.which(name)
    if found:
        return found
    for cand in sorted(glob.glob(os.path.expanduser(f"~/.gradle/jdks/*/bin/{name}"))):
        if os.path.isfile(cand):
            return cand
    raise SystemExit(f"找不到 {name}")


def build_relocate_tool(asm_jars: list) -> str:
    os.makedirs(RELOCATE_OUT, exist_ok=True)
    class_file = os.path.join(RELOCATE_OUT, "RelocateTool.class")
    if not (os.path.isfile(class_file)
            and os.path.getmtime(RELOCATE_SRC) <= os.path.getmtime(class_file)):
        javac = find_tool("javac")
        cmd = [javac, "-cp", os.pathsep.join(asm_jars), "-d", RELOCATE_OUT, RELOCATE_SRC]
        print("编译 RelocateTool：" + " ".join(cmd))
        subprocess.run(cmd, check=True)
    return RELOCATE_OUT


def extract_patch_classes() -> dict:
    """从 common-Bot.jar 与 server-Spigot.jar 收集补丁类，并做新鲜度前置校验。"""
    if not os.path.isfile(COMMON_JAR):
        raise SystemExit(f"缺少 common-Bot 构建产物：{COMMON_JAR}\n先执行 ./gradlew :common-Bot:jar")
    if not os.path.isfile(SPIGOT_JAR):
        raise SystemExit(f"缺少 server-Spigot 构建产物：{SPIGOT_JAR}\n先执行 ./gradlew :server-Spigot:jar")
    out = {}
    with zipfile.ZipFile(COMMON_JAR) as zf:
        names = set(zf.namelist())
        for prefix in COMMON_PREFIXES:
            hits = sorted(n for n in names
                          if n == prefix + ".class" or n.startswith(prefix + "$"))
            if not hits:
                raise SystemExit(f"common-Bot.jar 缺少 {prefix}（构建产物过期？）")
            for n in hits:
                out[n] = zf.read(n)
    with zipfile.ZipFile(SPIGOT_JAR) as zf:
        names = set(zf.namelist())
        for prefix in SPIGOT_PREFIXES:
            hits = sorted(n for n in names
                          if n == prefix + ".class" or n.startswith(prefix + "$"))
            if not hits:
                raise SystemExit(f"server-Spigot.jar 缺少 {prefix}（构建产物过期？）")
            for n in hits:
                out[n] = zf.read(n)
    # 新鲜度：1.5.5 特征串
    def must_have(entry, *needles):
        for needle in needles:
            if needle.encode("utf-8") not in out[entry]:
                raise SystemExit(f"{entry} 缺少 1.5.5 特征 {needle!r} —— 构建产物过期")
    must_have("cn/huohuas001/bot/events/commands/LeaderboardCommands.class",
              "在线排行榜", "getLeaderboardTop", "listQuuids", "PenguinAvatarFetch")
    must_have("cn/huohuas001/huhobotPenguin/spigot/render/LeaderboardRenderer.class",
              "measurePageHeight", "renderPage")
    must_have("cn/huohuas001/huhobotPenguin/spigot/stats/OnlineListService.class",
              "renderPages", "sendPages")
    must_have("cn/huohuas001/bot/events/commands/CheckInCommands.class",
              "isEconomyAvailable")
    must_have("cn/huohuas001/huhobotPenguin/spigot/manager/ConfigManager.class",
              "qq-bind.leaderboard.top")
    must_have("cn/huohuas001/bot/MenuManager.class", "在线排行榜")
    return out


def relocate(classes: dict, workdir: str) -> dict:
    """把补丁类打为临时 jar，经 RelocateTool 整体重定位后读回。"""
    asm_jars = ensure_asm()
    tool_dir = build_relocate_tool(asm_jars)
    mini_in = os.path.join(workdir, "patch-in.jar")
    mini_out = os.path.join(workdir, "patch-out.jar")
    with zipfile.ZipFile(mini_in, "w", zipfile.ZIP_DEFLATED) as zf:
        for entry, data in classes.items():
            zf.writestr(entry, data)
    java = find_tool("java")
    cmd = [java, "-cp", os.pathsep.join([tool_dir] + asm_jars), "RelocateTool",
           mini_in, mini_out, RELOCATE_TARGET, ",".join(RELOCATE_PREFIXES)]
    print("执行重定位：" + " ".join(cmd))
    result = subprocess.run(cmd, check=False)
    if result.returncode != 0:
        raise SystemExit(f"RelocateTool 失败（exit={result.returncode}）")
    out = {}
    with zipfile.ZipFile(mini_out) as zf:
        for entry in classes:
            out[entry] = zf.read(entry)
    return out


def build_fixup_tool(asm_jars: list) -> str:
    os.makedirs(FIXUP_OUT, exist_ok=True)
    class_file = os.path.join(FIXUP_OUT, "FixupTool.class")
    if not (os.path.isfile(class_file)
            and os.path.getmtime(FIXUP_SRC) <= os.path.getmtime(class_file)):
        javac = find_tool("javac")
        cmd = [javac, "-cp", os.pathsep.join(asm_jars), "-d", FIXUP_OUT, FIXUP_SRC]
        print("编译 FixupTool：" + " ".join(cmd))
        subprocess.run(cmd, check=True)
    return FIXUP_OUT


def fixup(classes: dict, workdir: str, base_jar: str) -> dict:
    """经 FixupTool 做字节链接修复（类型重映射 / 调用种类转换 / 合成名 / runTask 描述符）。"""
    asm_jars = ensure_asm()
    tool_dir = build_fixup_tool(asm_jars)
    fix_in = os.path.join(workdir, "fix-in.jar")
    fix_out = os.path.join(workdir, "fix-out.jar")
    with zipfile.ZipFile(fix_in, "w", zipfile.ZIP_DEFLATED) as zf:
        for entry, data in classes.items():
            zf.writestr(entry, data)
    java = find_tool("java")
    cmd = [java, "-cp", os.pathsep.join([tool_dir] + asm_jars), "FixupTool",
           fix_in, fix_out, base_jar]
    print("执行链接修复：" + " ".join(cmd))
    result = subprocess.run(cmd, check=False)
    if result.returncode != 0:
        raise SystemExit(f"FixupTool 失败（exit={result.returncode}）")
    out = {}
    with zipfile.ZipFile(fix_out) as zf:
        for entry in classes:
            out[entry] = zf.read(entry)
    # 修复后常量池结构性引用零残留（与 1.5.4.5 门禁同口径；FixupTool 仅改
    # org/bukkit 命名空间，不应引入任何未重定位的 kotlin/fastjson 引用）
    for entry, data in out.items():
        consts, _ = read_constant_pool(data)
        hits = 0
        for c in consts:
            if not c:
                continue
            if c[0] == "ref" and c[1] == 7:
                ne = consts[c[2]]
                cn = ne[1] if ne and ne[0] == "utf8" else ""
                if any(cn.startswith(p) for p in RELOCATE_PREFIXES):
                    hits += 1
            elif c[0] == "ref2" and c[1] == 12:
                de = consts[c[3]]
                desc = de[1] if de and de[0] == "utf8" else ""
                if any(f"L{p}" in desc for p in RELOCATE_PREFIXES):
                    hits += 1
        if hits:
            raise SystemExit(f"{entry} 修复后常量池残留 {hits} 处未重定位引用")
    # 修复内容抽查：皮肤链四类替换应到位
    lbc = out["cn/huohuas001/bot/events/commands/LeaderboardCommands.class"]
    if b"com/destroystokyo/paper/profile/PlayerProfile" in lbc:
        raise SystemExit("LeaderboardCommands 仍残留 destroystokyo PlayerProfile 引用")
    if b"font$common_Bot" in out["cn/huohuas001/huhobotPenguin/spigot/render/OnlineListRenderer.class"]:
        raise SystemExit("OnlineListRenderer 仍残留 font$common_Bot 合成名")
    return out


def make_manifest(version: str) -> bytes:
    return ("Manifest-Version: 1.0\n"
            f"Implementation-Title: KERONGPenguin-NeoForge\n"
            f"Implementation-Version: {version}\n"
            f"Automatic-Module-Name: {MOD_ID}\n").encode("utf-8")


def patch(base_jar: str, output: str, classes: dict, version: str) -> None:
    os.makedirs(os.path.dirname(output), exist_ok=True)
    replace = set(classes) | {"META-INF/neoforge.mods.toml", "META-INF/MANIFEST.MF",
                              "config.yml"}
    with open(TOML_PATH, "rb") as f:
        toml = f.read()
    with open(CONFIG_PATH, "rb") as f:
        config = f.read()
    with zipfile.ZipFile(base_jar) as base, \
            zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as out:
        for item in base.infolist():
            name = item.filename
            if name in replace:
                continue
            out.writestr(item, base.read(name))
        stamp = (2026, 10, 5, 0, 0, 0)
        for entry, data in classes.items():
            info = zipfile.ZipInfo(entry, date_time=stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info, data)
        for name, data in (("META-INF/neoforge.mods.toml", toml),
                           ("META-INF/MANIFEST.MF", make_manifest(version)),
                           ("config.yml", config)):
            info = zipfile.ZipInfo(name, date_time=stamp)
            info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info, data)


def verify_diff(base_jar: str, output: str, version: str, classes: dict) -> None:
    """差异白名单硬校验：产物与基底仅允许补丁集合内条目不同，其余逐字节一致。"""
    with zipfile.ZipFile(base_jar) as a, zipfile.ZipFile(output) as b:
        an, bn = set(a.namelist()), set(b.namelist())
        added = sorted(bn - an)
        expected_added = sorted(n for n in NEW_CLASS_FILES)
        if added != expected_added:
            raise SystemExit(f"新增条目异常：{added}（期望 {expected_added}）")
        removed = sorted(an - bn)
        if removed:
            raise SystemExit(f"不允许删除条目：{removed}")
        changed = []
        for name in sorted(an & bn):
            if a.read(name) != b.read(name):
                changed.append(name)
        allowed_changed = set(classes) | {"META-INF/neoforge.mods.toml",
                                          "META-INF/MANIFEST.MF", "config.yml"}
        allowed_changed -= set(NEW_CLASS_FILES)
        unexpected = [n for n in changed if n not in allowed_changed]
        if unexpected:
            raise SystemExit(f"白名单外条目被改动：{unexpected}")
        toml = b.read("META-INF/neoforge.mods.toml").decode("utf-8")
        manifest = b.read("META-INF/MANIFEST.MF").decode("utf-8")
        config = b.read("config.yml").decode("utf-8")
        if f'version = "{version}"' not in toml:
            raise SystemExit("产物 mods.toml 版本未更新")
        if f"Implementation-Version: {version}" not in manifest:
            raise SystemExit("产物 MANIFEST 版本未更新")
        for needle in ("config-version: 13", "leaderboard:", "在线排行榜: true"):
            if needle not in config:
                raise SystemExit(f"产物 config.yml 缺少 {needle!r}")
    print(f"差异校验通过：改动 {len(changed)} 条 + 新增 {len(expected_added)} 条，"
          f"其余 {len(an) - len(changed)} 条目逐字节一致")


def main() -> None:
    parser = argparse.ArgumentParser(description="NeoForge 分发 JAR 增量补丁打包（1.5.5）")
    parser.add_argument("base_jar", nargs="?", default=None,
                        help="基底 JAR（官网上一版分发模组 1.5.4.5）")
    parser.add_argument("-o", "--output", default=None)
    args = parser.parse_args()

    version = read_source_version()
    base = args.base_jar or BASE_LOCAL
    base = ensure_base(base)
    base_version = read_jar_version(base)
    if base_version == version:
        raise SystemExit(f"基底 JAR 版本({base_version})与目标版本({version})相同，无需补丁")
    print(f"补丁：{base_version} → {version}（common-Bot + manager 增量）")

    mc_version = "1.21.1"
    output = args.output or os.path.join(
        DEFAULT_DIST_DIR, f"KERONGPenguin_NeoForge-{mc_version}-{version}.jar")

    workdir = os.path.join(DEFAULT_DIST_DIR, f".nf-patch-{version}")
    os.makedirs(workdir, exist_ok=True)
    classes = extract_patch_classes()
    relocated = relocate(classes, workdir)
    fixed = fixup(relocated, workdir, base)
    patch(base, output, fixed, version)
    verify_diff(base, output, version, fixed)
    verify_link_compat(output, fixed)

    size_mb = os.path.getsize(output) / 1024 / 1024
    print(f"补丁完成：{output}（{size_mb:.1f} MB，版本 {version}）")


if __name__ == "__main__":
    main()



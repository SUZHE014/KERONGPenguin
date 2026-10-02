#!/usr/bin/env python3
"""
NeoForge 分发模组 JAR 增量补丁打包（无 server-NeoForge/libs 构建机环境用）。

背景（1.5.4.5）：QQ 消息同步延迟修复（OutboxQueue 异步发送队列）全部位于
common-Bot（OutboxQueue.kt 新增 / QClient.kt / GroupMessageHandler.kt 改动），
server-NeoForge 模块源码零改动、仅 neoforge.mods.toml 版本升。完整管线
（gradlew :server-NeoForge:jar + package_jar_neoforge.py）需要本机具备
server-NeoForge/libs 真实构件（mojmap MC jar 等，见该目录 README.txt）；
本脚本提供等价增量路径：

  1. 从 common-Bot 模块构建产物（common-Bot/build/libs/common-Bot.jar，
     与 Spigot 线同一份字节码——同一 Kotlin 2.2.0 编译器、同源）取出改动类；
  2. 经 scripts/relocate/RelocateTool 重定位（kotlin/kotlinx/okhttp3/okio/
     fastjson/... → cn.huohuas001.shaded.*，与整包重定位同规则同前缀）；
  3. 以官网已发布的上一版 NeoForge 分发 JAR 为基底：替换 QClient /
     GroupMessageHandler、新增 OutboxQueue、neoforge.mods.toml 与 MANIFEST
     版本升为源码当前版本——其余全部条目逐字节原样保留；
  4. 差异白名单校验：产物与基底 JAR 相比，仅允许白名单内条目存在差异。

链接兼容性依据（已用 javap 常量池核实）：
  - 三个类零 org.bukkit 引用（不触碰 Spigot API 与兼容层签名差异）；
  - 对项目类只经 HuHoBot 接口方法（invokeinterface，声明于 common-Bot 源，
    两线描述符一致）与未改动的 common-Bot 同源类；
  - 直接引用仅 io.github.kloping.*（不重定位）+ kotlin / fastjson（重定位）
    + JDK 类。

用法：
    python3 scripts/patch_jar_neoforge.py [base_jar] [-o output.jar]
    （base_jar 缺省从官网下载上一版 1.5.4.4）
"""
import argparse
import glob
import io
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COMMON_JAR = os.path.join(REPO, "common-Bot", "build", "libs", "common-Bot.jar")
TOML_PATH = os.path.join(REPO, "server-NeoForge", "src", "main", "resources",
                         "META-INF", "neoforge.mods.toml")
DEFAULT_DIST_DIR = os.path.join(REPO, "build", "dist")
DEPS_CACHE_DIR = os.path.join(REPO, "scripts", "deps-work")
ASM_URLS = {
    "asm-9.7.1.jar": "https://repo.maven.apache.org/maven2/org/ow2/asm/asm/9.7.1/asm-9.7.1.jar",
    "asm-commons-9.7.1.jar": "https://repo.maven.apache.org/maven2/org/ow2/asm/asm-commons/9.7.1/asm-commons-9.7.1.jar",
}
RELOCATE_SRC = os.path.join(REPO, "scripts", "relocate", "RelocateTool.java")
RELOCATE_OUT = os.path.join(REPO, "scripts", "relocate", "out")
RELOCATE_PREFIXES = (
    "kotlin/", "kotlinx/", "okhttp3/", "okio/", "com/alibaba/",
    "org/yaml/", "org/bouncycastle/", "org/jsoup/", "org/java_websocket/",
    "org/jetbrains/", "org/intellij/",
)
RELOCATE_TARGET = "cn/huohuas001/shaded"

# 本次补丁携带的 common-Bot 改动类（1.5.4.5：QQ 消息同步延迟修复）
PATCH_CLASSES = [
    "cn/huohuas001/bot/OutboxQueue.class",                       # 新增
    "cn/huohuas001/bot/QClient.class",                           # 替换
    "cn/huohuas001/bot/events/GroupMessageHandler.class",        # 替换
]

# 官网已发布的上一版（下载基底用）
BASE_URL = "https://kerong.xin/penguin/downloads/KERONGPenguin_NeoForge-1.21.1-1.5.4.4.jar"
BASE_LOCAL_FALLBACK = os.path.join(os.path.dirname(REPO), "nf-work",
                                   "KERONGPenguin_NeoForge-1.21.1-1.5.4.4.jar")

# 差异白名单：产物 vs 基底，仅这些条目允许不同（其余逐字节一致）
DIFF_ALLOW = set(PATCH_CLASSES) | {
    "META-INF/neoforge.mods.toml",
    "META-INF/MANIFEST.MF",
}

MOD_ID = "kerongpenguin"


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


def extract_patch_classes(workdir: str) -> dict:
    """从 common-Bot 构建产物取补丁类，并做前置内容校验。"""
    if not os.path.isfile(COMMON_JAR):
        raise SystemExit(f"缺少 common-Bot 构建产物：{COMMON_JAR}\n"
                         f"先执行 ./gradlew :common-Bot:jar")
    out = {}
    with zipfile.ZipFile(COMMON_JAR) as zf:
        names = set(zf.namelist())
        for entry in PATCH_CLASSES:
            if entry not in names:
                raise SystemExit(f"common-Bot.jar 缺少 {entry}（构建产物过期？）")
            out[entry] = zf.read(entry)
    # 前置校验：确认产物携带 1.5.4.5 修复特征
    if b"OutboxQueue" not in out["cn/huohuas001/bot/QClient.class"]:
        raise SystemExit("QClient.class 未引用 OutboxQueue —— 构建产物不含 1.5.4.5 修复")
    if b"sendPayloadToGroupsSync" not in out["cn/huohuas001/bot/QClient.class"]:
        raise SystemExit("QClient.class 无 sendPayloadToGroupsSync —— 构建产物过期")
    if b"submitAsync" not in out["cn/huohuas001/bot/events/GroupMessageHandler.class"]:
        raise SystemExit("GroupMessageHandler.class 无 submitAsync —— 构建产物过期")
    if b"KERONGPenguin-QQ-Outbox" not in out["cn/huohuas001/bot/OutboxQueue.class"]:
        raise SystemExit("OutboxQueue.class 缺线程名常量 —— 异常产物")
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
    # 重定位后校验：常量池结构性引用（Class / NameAndType 描述符）零残留——
    # 与 verify_jar_neoforge.py 门禁 5c 同口径。LocalVariableTypeTable 等调试
    # 结构里的 Lkotlin/... 字串不在解析路径（canonical 分发 jar 的 8 个项目类
    # 同样存在，运行时永不加载），不判失败。
    sys.path.insert(0, os.path.join(os.path.dirname(REPO), "scripts"))
    from class_strings import read_constant_pool
    for entry, data in out.items():
        consts, _, _ = read_constant_pool(data)
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
            raise SystemExit(f"{entry} 重定位后常量池残留 {hits} 处未重定位引用")
    return out


def make_manifest(version: str) -> bytes:
    return ("Manifest-Version: 1.0\n"
            f"Implementation-Title: KERONGPenguin-NeoForge\n"
            f"Implementation-Version: {version}\n"
            f"Automatic-Module-Name: {MOD_ID}\n").encode("utf-8")


def patch(base_jar: str, output: str, classes: dict, version: str) -> None:
    os.makedirs(os.path.dirname(output), exist_ok=True)
    replace = {"cn/huohuas001/bot/QClient.class",
               "cn/huohuas001/bot/events/GroupMessageHandler.class",
               "META-INF/neoforge.mods.toml", "META-INF/MANIFEST.MF"}
    with open(TOML_PATH, "rb") as f:
        toml = f.read()
    with zipfile.ZipFile(base_jar) as base, \
            zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as out:
        for item in base.infolist():
            name = item.filename
            if name in replace:
                continue
            out.writestr(item, base.read(name))
        for entry, data in classes.items():
            info = zipfile.ZipInfo(entry, date_time=(2026, 10, 2, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            out.writestr(info, data)
        info = zipfile.ZipInfo("META-INF/neoforge.mods.toml", date_time=(2026, 10, 2, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        out.writestr(info, toml)
        info = zipfile.ZipInfo("META-INF/MANIFEST.MF", date_time=(2026, 10, 2, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        out.writestr(info, make_manifest(version))


def verify_diff(base_jar: str, output: str, version: str) -> None:
    """差异白名单硬校验：产物与基底仅允许白名单条目不同，其余逐字节一致。"""
    with zipfile.ZipFile(base_jar) as a, zipfile.ZipFile(output) as b:
        an, bn = set(a.namelist()), set(b.namelist())
        added = sorted(bn - an)
        removed = sorted(an - bn)
        if added != ["cn/huohuas001/bot/OutboxQueue.class"]:
            raise SystemExit(f"新增条目异常：{added}")
        if removed:
            raise SystemExit(f"不允许删除条目：{removed}")
        changed = []
        for name in sorted(an & bn):
            if a.read(name) != b.read(name):
                changed.append(name)
        unexpected = [n for n in changed if n not in DIFF_ALLOW]
        if unexpected:
            raise SystemExit(f"白名单外条目被改动：{unexpected}")
        toml = b.read("META-INF/neoforge.mods.toml").decode("utf-8")
        manifest = b.read("META-INF/MANIFEST.MF").decode("utf-8")
        if f'version = "{version}"' not in toml:
            raise SystemExit("产物 mods.toml 版本未更新")
        if f"Implementation-Version: {version}" not in manifest:
            raise SystemExit("产物 MANIFEST 版本未更新")
    print(f"差异校验通过：仅 {sorted(set(changed))} 与新增 OutboxQueue.class 变化")


def main() -> None:
    parser = argparse.ArgumentParser(description="NeoForge 分发 JAR 增量补丁打包")
    parser.add_argument("base_jar", nargs="?", default=None,
                        help="基底 JAR（官网上一版分发模组）")
    parser.add_argument("-o", "--output", default=None)
    args = parser.parse_args()

    version = read_source_version()
    base = args.base_jar or (BASE_LOCAL_FALLBACK if os.path.isfile(BASE_LOCAL_FALLBACK) else BASE_LOCAL_FALLBACK)
    base = ensure_base(base)
    base_version = read_jar_version(base)
    if base_version == version:
        raise SystemExit(f"基底 JAR 版本({base_version})与目标版本({version})相同，无需补丁")
    print(f"补丁：{base_version} → {version}（common-Bot 增量）")

    mc_version = "1.21.1"
    output = args.output or os.path.join(
        DEFAULT_DIST_DIR, f"KERONGPenguin_NeoForge-{mc_version}-{version}.jar")

    workdir = os.path.join(DEFAULT_DIST_DIR, f".nf-patch-{version}")
    os.makedirs(workdir, exist_ok=True)
    classes = extract_patch_classes(workdir)
    relocated = relocate(classes, workdir)
    patch(base, output, relocated, version)
    verify_diff(base, output, version)

    # 复制一份到仓库外下载目录（交付习惯位置）
    size_mb = os.path.getsize(output) / 1024 / 1024
    print(f"补丁完成：{output}（{size_mb:.1f} MB，版本 {version}）")


if __name__ == "__main__":
    main()

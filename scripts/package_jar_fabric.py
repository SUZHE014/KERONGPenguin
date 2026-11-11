#!/usr/bin/env python3
"""
合成 Fabric 分发模组 JAR（KERONGPenguin_Fabric-<MC>-<version>.jar，fa 版
1.7.5.1 起随主线发版）。

用法：
    python3 scripts/package_jar_fabric.py [base_jar] [-o output.jar]

- base_jar：任一 Spigot 版可分发 JAR（提供第三方依赖字节码：kloping QQ SDK、
  fastjson、kotlin-stdlib、okhttp 等）。缺省使用环境变量 PENGUIN_BASE_JAR。
- 额外并入 snakeyaml（org.bukkit 兼容层 YamlConfiguration 的运行时依赖，
  Fabric 运行时不自带；与 NeoForge 打包同一缓存）。

两步打包（见 server-Fabric/build.gradle.kts 头部注释）：
  1. 模块产物（mojmap 名编译）经 tiny-remapper 把 net.minecraft 引用从
     mojang 名重映射为 intermediary 名（Fabric 运行时的 MC 类名）——映射用
     scripts/build_fabric_libs.py 生成的三列合并映射
     （mojang/official[混淆]/intermediary），与编译期 fabric-api 重映射同源；
  2. 以 Spigot 版可分发 JAR 为底座并入第三方依赖字节码，剔除底座的插件
    自身内容（cn/kerong、资源、kotlin_module）；模块自带 org.bukkit 兼容层。

与 NeoForge 打包（package_jar_neoforge.py）的差异：
  - **不重定位**第三方库（Fabric knot 类加载非 JPMS 模块层，无 split
    package 崩溃；fa 历史产物 kotlin/ 等保持原包名）；
  - **不剔除** org/slf4j、com/google/gson（Fabric 运行时不以命名模块进
    GAME 层，与 mod 内同包不冲突；fa 1.7.4.1 产物含 slf4j-nop / gson）；
  - 模组元数据为 fabric.mod.json（模块产物携带）。
"""
import argparse
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
MODULE_JAR = REPO / "server-Fabric" / "build" / "libs" / "server-Fabric.jar"
DEFAULT_OUT_DIR = REPO / "build" / "dist"
DEPS_CACHE_DIR = REPO / "scripts" / "deps-work"
MATERIALS = Path(os.environ.get("FABRIC_MATERIALS", "/home/z/my-project/fabric-deps"))
WORK = REPO / "scripts" / "deps-work" / "fabric-libs"
MERGED_TINY = WORK / "merged.tiny"

MC_VERSION = "1.20.4"

TINY_REMAPPER_JAR = "tiny-remapper-0.15.0.jar"
ASM_JARS = ["asm-9.7.1.jar", "asm-commons-9.7.1.jar", "asm-tree-9.7.1.jar", "asm-util-9.7.1.jar"]
MAPPING_IO_JAR = "mapping-io-0.7.1.jar"

SNAKEYAML_URL = "https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar"
SNAKEYAML_JAR = DEPS_CACHE_DIR / "snakeyaml-2.2.jar"

# base JAR 中需要剔除的内容（全部由新编译产物或模组资源提供）
EXCLUDE_PREFIXES = ("cn/kerong/", "cn/huohuas001/", "fonts/", "Markdown/", "backgrounds/")
EXCLUDE_EXACT = {
    "plugin.yml",
    "config.yml",
    "META-INF/MANIFEST.MF",
    "META-INF/common-Bot.kotlin_module",
    "META-INF/server-Spigot.kotlin_module",
}
# 1.6.3 死代码清理（与 Spigot / NeoForge 打包一致）
DEAD_LIB_PREFIXES = ("com/mchange/", "com/zaxxer/", "org/terracotta/", "webui/", "schema/")


def find_java() -> str:
    java = shutil.which("java")
    if java:
        return java
    for candidate in sorted(Path.home().glob(".gradle/jdks/*/bin/java")):
        if candidate.is_file():
            return str(candidate)
    raise SystemExit("找不到 java 运行时")


def read_mod_version() -> str:
    with zipfile.ZipFile(MODULE_JAR) as zf:
        text = zf.read("fabric.mod.json").decode("utf-8")
    import json
    return json.loads(text)["version"]


def excluded(name: str) -> bool:
    if name in EXCLUDE_EXACT or any(name.startswith(p) for p in EXCLUDE_PREFIXES):
        return True
    if any(name.startswith(p) for p in DEAD_LIB_PREFIXES):
        return True
    if name == "module-info.class" or name.endswith("/module-info.class"):
        return True
    if name.startswith("META-INF/versions/"):
        return True
    if name.startswith("META-INF/") and name.rsplit(".", 1)[-1] in {"SF", "RSA", "DSA"}:
        return True
    return False


def ensure_snakeyaml() -> Path:
    if SNAKEYAML_JAR.is_file() and SNAKEYAML_JAR.stat().st_size > 100_000:
        return SNAKEYAML_JAR
    import urllib.request
    DEPS_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    print(f"下载 snakeyaml 2.2：{SNAKEYAML_URL}")
    urllib.request.urlretrieve(SNAKEYAML_URL, SNAKEYAML_JAR)
    return SNAKEYAML_JAR


def remap_module(work_dir: Path) -> Path:
    """模块产物 mojang→intermediary 重映射（net.minecraft 引用）。"""
    if not MERGED_TINY.is_file():
        raise SystemExit(f"缺少合并映射 {MERGED_TINY}（先跑 scripts/build_fabric_libs.py）")
    remapped = work_dir / "server-Fabric-intermediary.jar"
    if remapped.is_file():
        return remapped
    cp = ":".join(str(MATERIALS / j) for j in [TINY_REMAPPER_JAR] + ASM_JARS + [MAPPING_IO_JAR])
    # 类路径：minecraft mojmap + fabric-api mojmap + loader + brigadier + authlib
    # + deps.jar（供层级解析，保证成员名传播正确；tiny-remapper 逐个传参）
    libs = REPO / "server-Fabric" / "libs"
    classpath = [str(p) for p in [
        libs / "minecraft-1.20.4-mojmap.jar",
        libs / "fabric-api-mojmap.jar",
        libs / "fabric-loader-0.16.14.jar",
        libs / "brigadier.jar",
        libs / "authlib.jar",
        libs / "deps.jar",
    ]]
    cmd = [
        find_java(), "-cp", cp, "net.fabricmc.tinyremapper.Main",
        str(MODULE_JAR), str(remapped), str(MERGED_TINY), "mojang", "intermediary",
        "--ignoreconflicts",
    ] + classpath
    print("重映射模块产物（mojang→intermediary）…")
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    if result.returncode != 0:
        raise SystemExit(f"模块重映射失败：\n{result.stdout[-600:]}\n{result.stderr[-600:]}")
    return remapped


def main() -> None:
    parser = argparse.ArgumentParser(description="合成 KERONGPenguin Fabric 分发模组 JAR")
    parser.add_argument("base_jar", nargs="?", default=os.environ.get("PENGUIN_BASE_JAR"))
    parser.add_argument("-o", "--output", default=None)
    args = parser.parse_args()

    if not args.base_jar or not os.path.isfile(args.base_jar):
        raise SystemExit("需要提供 base JAR（任一 Spigot 版可分发 JAR）路径，或设置 PENGUIN_BASE_JAR")
    if not MODULE_JAR.is_file():
        raise SystemExit(f"缺少编译产物，先执行 ./gradlew :server-Fabric:jar：{MODULE_JAR}")

    version = read_mod_version()
    output = args.output or str(DEFAULT_OUT_DIR / f"KERONGPenguin_Fabric-{MC_VERSION}-{version}.jar")
    work_dir = WORK / "package"
    work_dir.mkdir(parents=True, exist_ok=True)

    module = remap_module(work_dir)
    snakeyaml = ensure_snakeyaml()

    seen = set()
    dir_count = 0
    with zipfile.ZipFile(args.base_jar) as base, \
            zipfile.ZipFile(module) as mod, \
            zipfile.ZipFile(snakeyaml) as snake, \
            zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as out:
        # 1. base 第三方依赖字节码（含目录条目；不重定位）
        for item in base.infolist():
            name = item.filename
            if excluded(name):
                continue
            out.writestr(item, base.read(name))
            seen.add(name)
            if name.endswith("/"):
                dir_count += 1
        # 2. snakeyaml（YamlConfiguration 运行时依赖）
        for item in snake.infolist():
            name = item.filename
            if excluded(name) or (name.startswith("META-INF/") and not name.endswith("/")):
                continue
            if name in seen:
                continue
            out.writestr(item, snake.read(name))
            seen.add(name)
            if name.endswith("/"):
                dir_count += 1
        # 3. 重映射后的模块产物（含 org.bukkit 兼容层、fabric.mod.json 与资源）
        for item in mod.infolist():
            name = item.filename
            if name == "META-INF/MANIFEST.MF" or name in seen:
                continue
            out.writestr(item, mod.read(name))
            seen.add(name)
            if name.endswith("/"):
                dir_count += 1
        out.writestr(
            "META-INF/MANIFEST.MF",
            "Manifest-Version: 1.0\n"
            "Implementation-Title: KERONGPenguin-Fabric\n"
            f"Implementation-Version: {version}\n",
        )

    if dir_count == 0:
        raise SystemExit("严重错误：产物中没有目录条目，SDK 组件装配会失败！")

    size_mb = os.path.getsize(output) / 1024 / 1024
    print(f"打包完成：{output}（{size_mb:.1f} MB，版本 {version}，mojang→intermediary 重映射完成）")


if __name__ == "__main__":
    main()

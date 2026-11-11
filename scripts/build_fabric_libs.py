#!/usr/bin/env python3
"""
重建 server-Fabric/libs/ 编译期构件（fa 版 1.7.5.1 起入主线构建；幂等可重复执行）。

构件清单（server-Fabric/libs/，体积大不入库）：
  1. minecraft-1.20.4-mojmap.jar   —— 直接复用 server-NeoForge/libs/（同目标 MC）
  2. brigadier.jar / authlib.jar   —— 直接复用 server-NeoForge/libs/
  3. deps.jar                      —— 复用 common-Bot/libs/（QQ SDK 底料）
  4. fabric-loader-0.16.14.jar     —— Fabric loader 官方构件
  5. fabric-api-mojmap.jar         —— 7 个 fabric-api 模块（intermediary 名）
     经 tiny-remapper 重映射为 mojmap 名后合并（编译期对 mojmap 源码）

fabric-api 重映射需要**三列合并映射**（mojang / official[混淆名] / intermediary）：
intermediary tiny（official↔intermediary）+ Mojang 官方 ProGuard client.txt
（mojang↔混淆名）经 scripts/fabric-tools/MergeMappings.java（mapping-io）合并，
再由 tiny-remapper 一次完成 intermediary→mojang。分发打包时模块产物反向
mojmap→intermediary 复用同一映射（见 package_jar_fabric.py）。

原料目录（--materials，缺省 /home/z/my-project/fabric-deps/）：来自部署服务器
156.233.228.248:/root/fabric-deps/（fabric-api 模块 / loader / intermediary
映射 / tiny-remapper / asm / mapping-io）。缺文件时提示先从服务器拉取。

用法：python3 scripts/build_fabric_libs.py [--materials DIR] [--force]
前置：server-NeoForge/libs/ 已就绪（先跑 scripts/build_neoforge_libs.py，
client.txt 也取自 scripts/deps-work/nf-libs/）。
"""
import argparse
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LIBS = REPO / "server-Fabric" / "libs"
NF_LIBS = REPO / "server-NeoForge" / "libs"
NF_WORK = REPO / "scripts" / "deps-work" / "nf-libs"
COMMON_DEPS = REPO / "common-Bot" / "libs" / "deps.jar"
TOOLS = REPO / "scripts" / "fabric-tools"
WORK = REPO / "scripts" / "deps-work" / "fabric-libs"

# 原料（--materials 目录下）——与 server-Fabric 源码实际引用的模块一一对应
#（command.v2 / entity.event.v1 / event.lifecycle.v1 / event.player（events-interaction）/
#  message.v1 / networking.v1 / api-base）
FABRIC_API_MODULES = [
    "fabric-api-base-0.4.37+78d798af4f.jar",
    "fabric-command-api-v2-2.2.21+78d798af4f.jar",
    "fabric-entity-events-v1-1.6.2+09fc25014f.jar",
    "fabric-events-interaction-v0-0.7.2+389931eb4f.jar",
    "fabric-lifecycle-events-v1-2.3.1+a67ffb5d4f.jar",
    "fabric-message-api-v1-6.0.6+78d798af4f.jar",
    "fabric-networking-api-v1-3.1.9+6abb6a4b4f.jar",
]
LOADER_JAR = "fabric-loader-0.16.14.jar"
INTERMEDIARY_JAR = "intermediary-1.20.4-v2.jar"
TINY_REMAPPER_JAR = "tiny-remapper-0.15.0.jar"
ASM_JARS = ["asm-9.7.1.jar", "asm-commons-9.7.1.jar", "asm-tree-9.7.1.jar", "asm-util-9.7.1.jar"]
MAPPING_IO_JAR = "mapping-io-0.7.1.jar"
MC_VERSION = "1.20.4"


def find_java() -> str:
    java = shutil.which("java")
    if java:
        return java
    for candidate in sorted(Path.home().glob(".gradle/jdks/*/bin/java")):
        if candidate.is_file():
            return str(candidate)
    raise SystemExit("找不到 java 运行时")


def remapper_cmd(materials: Path) -> list:
    cp = ":".join(str(materials / j) for j in [TINY_REMAPPER_JAR] + ASM_JARS + [MAPPING_IO_JAR])
    return [find_java(), "-cp", cp, "net.fabricmc.tinyremapper.Main"]


def build_merged_mappings(materials: Path, work: Path) -> Path:
    """三列合并映射 mojang/official/intermediary（幂等）。"""
    merged = work / "merged.tiny"
    if merged.is_file():
        return merged
    tiny_file = work / "mappings.tiny"
    if not tiny_file.is_file():
        with zipfile.ZipFile(materials / INTERMEDIARY_JAR) as z:
            z.extract("mappings/mappings.tiny", str(work))
            tiny_file = work / "mappings" / "mappings.tiny"
    proguard = NF_WORK / f"client-{MC_VERSION}.txt"
    if not proguard.is_file():
        raise SystemExit(f"缺少 Mojang 官方映射 {proguard}（先跑 scripts/build_neoforge_libs.py）")
    # 编译 MergeMappings（源码比 class 新才重编）
    tool_cls = TOOLS / "MergeMappings.class"
    if not tool_cls.is_file() or (TOOLS / "MergeMappings.java").stat().st_mtime > tool_cls.stat().st_mtime:
        subprocess.run(
            [find_java(), "-cp", str(materials / MAPPING_IO_JAR), "MergeMappings.java"],
            cwd=str(TOOLS), check=True, timeout=120)
    subprocess.run(
        [find_java(), "-cp", f"{TOOLS}:{materials / MAPPING_IO_JAR}", "MergeMappings",
         str(tiny_file), str(proguard), str(merged)],
        check=True, timeout=600)
    return merged


def remap_fabric_api(materials: Path, out_jar: Path, merged: Path) -> None:
    """7 个 fabric-api 模块 intermediary→mojmap 重映射后合并为一个编译期 jar。"""
    if out_jar.is_file():
        return
    cmd = remapper_cmd(materials)
    merged_tmp = WORK / "fabric-api-merged-remapped.jar"
    seen = set()
    with zipfile.ZipFile(merged_tmp, "w", zipfile.ZIP_DEFLATED) as out:
        for module in FABRIC_API_MODULES:
            src = materials / module
            if not src.is_file():
                raise SystemExit(f"缺少 fabric-api 模块原料：{src}")
            remapped = WORK / f"remapped-{module}"
            result = subprocess.run(
                cmd + [str(src), str(remapped), str(merged), "intermediary", "mojang"],
                capture_output=True, text=True, timeout=300)
            if result.returncode != 0:
                raise SystemExit(f"重映射 {module} 失败：\n{result.stderr[-800:]}")
            with zipfile.ZipFile(remapped) as rz:
                for item in rz.infolist():
                    name = item.filename
                    if name in seen or (name.startswith("META-INF/") and not name.endswith("/")):
                        continue
                    out.writestr(item, rz.read(name))
                    seen.add(name)
            print(f"  fabric-api 模块重映射合并：{module}")
    out_jar.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(merged_tmp), out_jar)


def copy_if_missing(src: Path, dst: Path, force: bool) -> bool:
    if dst.is_file() and not force:
        return False
    if not src.is_file():
        raise SystemExit(f"缺少原料：{src}")
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description="重建 server-Fabric/libs/ 编译期构件")
    parser.add_argument("--materials", default="/home/z/my-project/fabric-deps",
                        help="原料目录（服务器 /root/fabric-deps/ 拉取到本地）")
    parser.add_argument("--force", action="store_true", help="已存在也重建")
    args = parser.parse_args()
    materials = Path(args.materials)

    print("== server-Fabric/libs/ 构建 ==")
    LIBS.mkdir(parents=True, exist_ok=True)
    WORK.mkdir(parents=True, exist_ok=True)

    copied = 0
    for name in ("minecraft-1.20.4-mojmap.jar", "brigadier.jar", "authlib.jar"):
        if copy_if_missing(NF_LIBS / name, LIBS / name, args.force):
            copied += 1
            print(f"  复用 NeoForge 构件：{name}")
    if copy_if_missing(COMMON_DEPS, LIBS / "deps.jar", args.force):
        copied += 1
        print("  复用 common-Bot deps.jar")
    if copy_if_missing(materials / LOADER_JAR, LIBS / LOADER_JAR, args.force):
        copied += 1
        print(f"  复制 {LOADER_JAR}")

    merged = build_merged_mappings(materials, WORK)
    print(f"  合并映射就绪：{merged.name}（mojang/official/intermediary）")
    remap_fabric_api(materials, LIBS / "fabric-api-mojmap.jar", merged)
    print("  fabric-api-mojmap.jar 就绪")

    # 旧错误产物清理（曾用二段式重映射的中间文件）
    for stale in ("fabric-api-obf",):
        p = LIBS / stale
        if p.exists():
            p.unlink()

    jars = sorted(p.name for p in LIBS.glob("*.jar"))
    print(f"== 完成（{len(jars)} 个 jar）：{jars}")
    print("下一步：settings.gradle.kts 已条件 include server-Fabric → ./gradlew :server-Fabric:jar")
    return 0


if __name__ == "__main__":
    sys.exit(main())

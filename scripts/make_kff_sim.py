#!/usr/bin/env python3
"""
重建 NeoForge 冒烟测试的 kotlin 共存模拟环境（1.5.4.3 起的回归场景）。

产物（工具目录，不入库）：
  1. tooling/fake-kff-mod.jar —— 模拟整合包中经 Sinytra Connector 载入的
     KFF kotlin.stdlib 命名模块：jar 内含原版 kotlin-stdlib 类（kotlin/**，
     取自 common-Bot/libs/deps.jar），以 lowcodefml 无代码模组身份进入 GAME 层，
     模块导出 kotlin.* 包。与未重定位的旧 fat jar 共存即 ResolutionException
     （1.5.4.3 复现崩溃）；与重定位后的分发 jar 共存必须正常启动。
  2. tooling/kotlin-stdlib-repro.jar —— 纯 kotlin-stdlib 类集合，供
     verify_jar_neoforge*.py 做"与 kotlin-stdlib 包零交集"静态门禁。

用法：python3 scripts/make_kff_sim.py
"""
import os
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEPS_JAR = os.path.join(REPO, "common-Bot", "libs", "deps.jar")
TOOLING = os.path.join(os.path.dirname(REPO), "tooling")

FAKE_KFF = os.path.join(TOOLING, "fake-kff-mod.jar")
KOTLIN_REPRO = os.path.join(TOOLING, "kotlin-stdlib-repro.jar")

KFF_TOML = """modLoader = "lowcodefml"
loaderVersion = "[1,)"
license = "Apache-2.0"

[[mods]]
modId = "kotlinforforge"
version = "5.0.0-sim"
displayName = "Kotlin for Forge (sim)"
description = '''Simulated kotlin.stdlib named module (split-package regression scene).'''
displayTest = "IGNORE_SERVER_VERSION"
"""


def copy_kotlin_classes(out_path: str, with_toml: bool) -> int:
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    count = 0
    with zipfile.ZipFile(DEPS_JAR) as src, \
            zipfile.ZipFile(out_path, "w", zipfile.ZIP_DEFLATED) as out:
        for item in src.infolist():
            name = item.filename
            if not name.startswith("kotlin/") or not name.endswith(".class"):
                continue
            out.writestr(item, src.read(name))
            count += 1
        if with_toml:
            out.writestr("META-INF/neoforge.mods.toml", KFF_TOML)
    return count


def main() -> None:
    if not os.path.isfile(DEPS_JAR):
        raise SystemExit(f"缺少 {DEPS_JAR}（先构建 common-Bot 编译依赖）")
    n1 = copy_kotlin_classes(FAKE_KFF, with_toml=True)
    n2 = copy_kotlin_classes(KOTLIN_REPRO, with_toml=False)
    print(f"fake-kff-mod.jar：{n1} 个 kotlin 类 + lowcodefml mods.toml")
    print(f"kotlin-stdlib-repro.jar：{n2} 个 kotlin 类")


if __name__ == "__main__":
    main()

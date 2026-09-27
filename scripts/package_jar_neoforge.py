#!/usr/bin/env python3
"""
合成 NeoForge 1.21.1 分发模组 JAR（KERONGPenguin_NeoForge-1.21.1-<version>.jar）。

用法：
    python3 scripts/package_jar_neoforge.py [base_jar] [-o output.jar]

- base_jar：任一 Spigot 版可分发 JAR（提供第三方依赖字节码：kloping QQ SDK、
  fastjson、kotlin-stdlib、okhttp 等）。缺省使用环境变量 PENGUIN_BASE_JAR。
- 额外并入 snakeyaml（org.bukkit 兼容层 YamlConfiguration 的运行时依赖，
  自动从 Maven Central 下载并缓存到 scripts/deps-work/）。
- 剔除 base_jar 中插件自身的旧字节码与 Spigot 资源（plugin.yml / config.yml /
  fonts / Markdown / 旧 kotlin_module），合入新编译的 server-NeoForge.jar
  （含 common-Bot 同源字节码、org.bukkit 兼容层、模组资源与 neoforge.mods.toml）。
- ⚠️ 模组生态规则（与 Spigot fat JAR 的差异）：
  1. 剔除全部 module-info.class（JPMS 模块描述与 FML SecureJar 自动模块冲突）；
  2. 剔除全部签名文件（META-INF/*.SF / *.RSA / *.DSA，重打包后签名失效会
     触发 JarVerifier 拒载）；
  3. 保留 JAR 目录条目（kloping SDK 组件扫描依赖目录条目——0.1.5.4 固化规则）；
  4. 保留依赖库自身的 kotlin_module / services（多文件门面与 SPI 运行时必需）。
"""
import argparse
import os
import re
import sys
import urllib.request
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULE_JAR = os.path.join(REPO, "server-NeoForge", "build", "libs", "server-NeoForge.jar")
DEFAULT_OUT_DIR = os.path.join(REPO, "build", "dist")
DEPS_CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "deps-work")
SNAKEYAML_URL = "https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar"
SNAKEYAML_JAR = os.path.join(DEPS_CACHE_DIR, "snakeyaml-2.2.jar")

MC_VERSION = "1.21.1"

# base JAR 中需要剔除的内容（全部由新编译产物或模组资源提供）
EXCLUDE_PREFIXES = ("cn/huohuas001/", "fonts/", "Markdown/")
EXCLUDE_EXACT = {
    "plugin.yml",
    "config.yml",
    "META-INF/MANIFEST.MF",
    "META-INF/common-Bot.kotlin_module",
    "META-INF/server-Spigot.kotlin_module",
}


def read_mod_version() -> str:
    with zipfile.ZipFile(MODULE_JAR) as zf:
        text = zf.read("META-INF/neoforge.mods.toml").decode("utf-8")
    match = re.search(r'^version\s*=\s*"?([0-9A-Za-z.\-]+)', text, re.MULTILINE)
    if not match:
        raise RuntimeError("neoforge.mods.toml 中未找到 version 字段")
    return match.group(1)


def excluded(name: str) -> bool:
    if name in EXCLUDE_EXACT or any(name.startswith(p) for p in EXCLUDE_PREFIXES):
        return True
    # 模组生态安全剔除：模块描述与签名
    if name == "module-info.class" or name.endswith("/module-info.class"):
        return True
    if name.startswith("META-INF/") and name.rsplit(".", 1)[-1] in {"SF", "RSA", "DSA"}:
        return True
    return False


def ensure_snakeyaml() -> str:
    if os.path.isfile(SNAKEYAML_JAR) and os.path.getsize(SNAKEYAML_JAR) > 100_000:
        return SNAKEYAML_JAR
    os.makedirs(DEPS_CACHE_DIR, exist_ok=True)
    print(f"下载 snakeyaml 2.2：{SNAKEYAML_URL}")
    urllib.request.urlretrieve(SNAKEYAML_URL, SNAKEYAML_JAR)
    return SNAKEYAML_JAR


def main() -> None:
    parser = argparse.ArgumentParser(description="合成 KERONGPenguin NeoForge 分发模组 JAR")
    parser.add_argument("base_jar", nargs="?", default=os.environ.get("PENGUIN_BASE_JAR"))
    parser.add_argument("-o", "--output", default=None)
    args = parser.parse_args()

    if not args.base_jar or not os.path.isfile(args.base_jar):
        raise SystemExit("需要提供 base JAR（任一 Spigot 版可分发 JAR）路径，或设置 PENGUIN_BASE_JAR")
    if not os.path.isfile(MODULE_JAR):
        raise SystemExit(f"缺少编译产物，先执行 ./gradlew :server-NeoForge:jar：{MODULE_JAR}")

    version = read_mod_version()
    output = args.output or os.path.join(DEFAULT_OUT_DIR, f"KERONGPenguin_NeoForge-{MC_VERSION}-{version}.jar")
    os.makedirs(os.path.dirname(output), exist_ok=True)
    snakeyaml = ensure_snakeyaml()

    seen = set()
    dir_count = 0
    with zipfile.ZipFile(args.base_jar) as base, \
            zipfile.ZipFile(MODULE_JAR) as module, \
            zipfile.ZipFile(snakeyaml) as snake, \
            zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as out:
        # 1. base 依赖字节码（含目录条目）
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
            if excluded(name) or name.startswith("META-INF/") and not name.endswith("/"):
                continue
            if name in seen:
                continue
            out.writestr(item, snake.read(name))
            seen.add(name)
            if name.endswith("/"):
                dir_count += 1
        # 3. 模组产物（含模组资源与 neoforge.mods.toml）
        for item in module.infolist():
            name = item.filename
            if name == "META-INF/MANIFEST.MF" or name in seen:
                continue
            out.writestr(item, module.read(name))
            seen.add(name)
            if name.endswith("/"):
                dir_count += 1
        out.writestr(
            "META-INF/MANIFEST.MF",
            "Manifest-Version: 1.0\n"
            f"Implementation-Title: KERONGPenguin-NeoForge\n"
            f"Implementation-Version: {version}\n",
        )

    if dir_count == 0:
        raise SystemExit("严重错误：产物中没有目录条目，SDK 组件装配会失败！")

    size_mb = os.path.getsize(output) / 1024 / 1024
    print(f"打包完成：{output}（{size_mb:.1f} MB，{len(seen)} 条目，目录条目 {dir_count} 个，版本 {version}）")


if __name__ == "__main__":
    main()

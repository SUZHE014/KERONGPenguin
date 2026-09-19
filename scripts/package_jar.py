#!/usr/bin/env python3
"""
合成可分发的 fat JAR（KERONGPenguin_Spigot-x.y.z.jar）。

用法：
    python3 scripts/package_jar.py [base_jar] [-o output.jar]

- base_jar：旧版可分发 JAR（提供第三方依赖字节码，如 kloping QQ SDK、fastjson、
  kotlin-stdlib、okhttp 等）。缺省使用环境变量 PENGUIN_BASE_JAR。
- 脚本会剔除 base_jar 中插件自身的旧字节码与模块资源，
  再合入新编译的 common-Bot.jar 与 server-Spigot.jar，
  版本号取自新 plugin.yml，输出 KERONGPenguin_Spigot-<version>.jar。

⚠️ 0.1.5.4 修复：必须保留 JAR 目录条目（以 / 结尾的条目）。
  kloping SDK 的组件扫描依赖目录条目，缺失会导致 Bot 实例装配 NPE、
  QQ 机器人无法启动（0.1.5.0 曾因同样问题翻车，0.1.5.1 修复；
  0.1.5.3 打包时再次遗漏，0.1.5.4 修复并在此固化规则）。
"""
import argparse
import os
import re
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
COMMON_JAR = os.path.join(REPO, "common-Bot", "build", "libs", "common-Bot.jar")
SPIGOT_JAR = os.path.join(REPO, "server-Spigot", "build", "libs", "server-Spigot.jar")
DEFAULT_OUT_DIR = os.path.join(REPO, "build", "dist")

# base JAR 中需要剔除的插件自身内容（全部由新编译产物提供）
EXCLUDE_PREFIXES = ("cn/huohuas001/", "fonts/", "Markdown/")
EXCLUDE_EXACT = {
    "plugin.yml",
    "config.yml",
    "META-INF/MANIFEST.MF",
    "META-INF/common-Bot.kotlin_module",
    "META-INF/server-Spigot.kotlin_module",
}


def read_version(spigot_jar: str) -> str:
    with zipfile.ZipFile(spigot_jar) as zf:
        text = zf.read("plugin.yml").decode("utf-8")
    match = re.search(r"^version:\s*['\"]?([0-9A-Za-z.\-]+)", text, re.MULTILINE)
    if not match:
        raise RuntimeError("plugin.yml 中未找到 version 字段")
    return match.group(1)


def excluded(name: str) -> bool:
    return name in EXCLUDE_EXACT or any(name.startswith(p) for p in EXCLUDE_PREFIXES)


def main() -> None:
    parser = argparse.ArgumentParser(description="合成 KERONGPenguin 可分发 fat JAR")
    parser.add_argument("base_jar", nargs="?", default=os.environ.get("PENGUIN_BASE_JAR"))
    parser.add_argument("-o", "--output", default=None)
    args = parser.parse_args()

    if not args.base_jar or not os.path.isfile(args.base_jar):
        raise SystemExit("需要提供 base JAR（旧版可分发 JAR）路径，或设置 PENGUIN_BASE_JAR")
    for path in (COMMON_JAR, SPIGOT_JAR):
        if not os.path.isfile(path):
            raise SystemExit(f"缺少编译产物，先执行 ./gradlew :common-Bot:jar :server-Spigot:jar：{path}")

    version = read_version(SPIGOT_JAR)
    output = args.output or os.path.join(DEFAULT_OUT_DIR, f"KERONGPenguin_Spigot-{version}.jar")
    os.makedirs(os.path.dirname(output), exist_ok=True)

    # 写入顺序：base 依赖（含目录条目）→ 模块产物（含目录条目），用 seen 去重
    seen = set()
    dir_count = 0
    with zipfile.ZipFile(args.base_jar) as base, \
            zipfile.ZipFile(COMMON_JAR) as common, \
            zipfile.ZipFile(SPIGOT_JAR) as spigot, \
            zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as out:
        for item in base.infolist():
            name = item.filename
            if excluded(name):
                continue
            out.writestr(item, base.read(name))
            seen.add(name)
            if name.endswith("/"):
                dir_count += 1
        for source in (common, spigot):
            for item in source.infolist():
                name = item.filename
                if name == "META-INF/MANIFEST.MF" or name in seen:
                    continue
                out.writestr(item, source.read(name))
                seen.add(name)
                if name.endswith("/"):
                    dir_count += 1
        out.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")

    if dir_count == 0:
        raise SystemExit("严重错误：产物中没有目录条目，SDK 组件装配会失败！")

    size_mb = os.path.getsize(output) / 1024 / 1024
    print(f"打包完成：{output}（{size_mb:.1f} MB，{len(seen)} 条目，目录条目 {dir_count} 个，版本 {version}）")


if __name__ == "__main__":
    main()

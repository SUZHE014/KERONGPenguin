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
  4. 保留依赖库自身的 kotlin_module / services（多文件门面与 SPI 运行时必需）；
  5. ⚠️ 剔除与 NeoForge 运行时 libraries 重复的包（split package 会在
     ModuleLayerHandler 模块解析时直接崩溃 ResolutionException，实测复现于
     21.1.252：org.slf4j(平台 slf4j-api 2.0.9) / com.google.gson(平台 gson 2.8.9)），
     运行时改用平台提供 版本；关联的 services 声明与 maven 元数据一并剔除。
     （scripts/nf_clash_scan.py 可对照本地安装的 server libraries 重新扫描验证）
  6. ⚠️ 1.5.4.3 起合并完成后执行第三方库整体重定位（scripts/relocate/RelocateTool，
     ASM 字节码重写）：ModLauncher 把每个 mod jar 当命名模块放进 GAME 层，fat jar
     自动模块导出全部包，与整合包中其他模块同包即 ResolutionException（实测复现：
     KFF 的 kotlin.stdlib 模块 → "export package kotlin.jvm"）。重定位范围：
     kotlin / kotlinx / okhttp3 / okio / fastjson / snakeyaml / bouncycastle /
     jsoup / java_websocket / jetbrains / intellij 注解 → cn.huohuas001.shaded.*；
     不重定位 io.github.kloping（组件扫描按原始包名枚举 jar 条目，改写会断）与
     quartz / c3p0 / HikariCP 等服务端基础设施库（模组生态不携带，quartz 配置键
     字符串重写有断连风险）。同包资源（okhttp publicsuffix 等）保持原位——非类
     条目不构成模块包，按原名加载不受影响。
"""
import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MODULE_JAR = os.path.join(REPO, "server-NeoForge", "build", "libs", "server-NeoForge.jar")
DEFAULT_OUT_DIR = os.path.join(REPO, "build", "dist")
DEPS_CACHE_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "deps-work")
SNAKEYAML_URL = "https://repo.maven.apache.org/maven2/org/yaml/snakeyaml/2.2/snakeyaml-2.2.jar"
SNAKEYAML_JAR = os.path.join(DEPS_CACHE_DIR, "snakeyaml-2.2.jar")
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

MC_VERSION = "1.21.1"
MOD_ID = "kerongpenguin"

# base JAR 中需要剔除的内容（全部由新编译产物或模组资源提供）
EXCLUDE_PREFIXES = ("cn/huohuas001/", "fonts/", "Markdown/")
EXCLUDE_EXACT = {
    "plugin.yml",
    "config.yml",
    "META-INF/MANIFEST.MF",
    "META-INF/common-Bot.kotlin_module",
    "META-INF/server-Spigot.kotlin_module",
}

# ⚠️ NeoForge 21.1.252 server libraries 已提供的包（实测 split package 崩溃源）。
# ModLauncher 把每个 mod jar 当 named module，平台 libraries 也以模块进入 GAME 层，
# 两边同包即 ResolutionException："Module kerongpenguin contains package ..."。
# 剔除后运行时从平台加载（slf4j-api 2.0.9 / gson 2.8.9，均为向上兼容的 API）。
PLATFORM_CLASH_PREFIXES = (
    "org/slf4j/",
    "com/google/gson/",
)
# 随冲突库一并剔除的 META-INF 残留（服务声明会让 ServiceLoader 找错提供方；
# maven 元数据保持产物干净）
PLATFORM_CLASH_META_PREFIXES = (
    "META-INF/services/org.slf4j.",
    "META-INF/maven/org.slf4j/",
    "META-INF/maven/com.google.code.gson/",
)


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
    # 平台重复库剔除（split package 崩溃修复）
    if any(name.startswith(p) for p in PLATFORM_CLASH_PREFIXES):
        return True
    if any(name.startswith(p) for p in PLATFORM_CLASH_META_PREFIXES):
        return True
    # 模组生态安全剔除：模块描述、签名、Multi-Release 变体
    if name == "module-info.class" or name.endswith("/module-info.class"):
        return True
    if name.startswith("META-INF/versions/"):
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


def ensure_asm() -> list:
    jars = []
    for fname, url in ASM_URLS.items():
        path = os.path.join(DEPS_CACHE_DIR, fname)
        if not (os.path.isfile(path) and os.path.getsize(path) > 50_000):
            os.makedirs(DEPS_CACHE_DIR, exist_ok=True)
            print(f"下载 {fname}：{url}")
            urllib.request.urlretrieve(url, path)
        jars.append(path)
    return jars


def find_javac() -> str:
    javac = shutil.which("javac")
    if javac:
        return javac
    for candidate in sorted(glob.glob(os.path.expanduser("~/.gradle/jdks/*/bin/javac"))):
        if os.path.isfile(candidate):
            return candidate
    raise SystemExit("找不到 javac（编译 RelocateTool 需要）：安装 JDK 或先跑一次 Gradle 构建")


def find_java() -> str:
    java = shutil.which("java")
    if java:
        return java
    for candidate in sorted(glob.glob(os.path.expanduser("~/.gradle/jdks/*/bin/java"))):
        if os.path.isfile(candidate):
            return candidate
    raise SystemExit("找不到 java 运行时")


def build_relocate_tool(asm_jars: list) -> str:
    """编译 RelocateTool（源码比 class 新才重编），返回其 class 目录。"""
    os.makedirs(RELOCATE_OUT, exist_ok=True)
    class_file = os.path.join(RELOCATE_OUT, "RelocateTool.class")
    need = (not os.path.isfile(class_file)
            or os.path.getmtime(RELOCATE_SRC) > os.path.getmtime(class_file))
    if need:
        javac = find_javac()
        cmd = [javac, "-cp", os.pathsep.join(asm_jars), "-d", RELOCATE_OUT, RELOCATE_SRC]
        print("编译 RelocateTool：" + " ".join(cmd))
        subprocess.run(cmd, check=True)
    return RELOCATE_OUT


def run_relocate(merged_jar: str, final_jar: str, asm_jars: list) -> None:
    tool_dir = build_relocate_tool(asm_jars)
    java = find_java()
    cmd = [
        java, "-cp", os.pathsep.join([tool_dir] + asm_jars),
        "RelocateTool", merged_jar, final_jar,
        RELOCATE_TARGET, ",".join(RELOCATE_PREFIXES),
    ]
    print("执行重定位：" + " ".join(cmd))
    result = subprocess.run(cmd, check=False)
    if result.returncode != 0:
        raise SystemExit(f"RelocateTool 失败（exit={result.returncode}）")


def main() -> None:
    parser = argparse.ArgumentParser(description="合成 KERONGPenguin NeoForge 分发模组 JAR")
    parser.add_argument("base_jar", nargs="?", default=os.environ.get("PENGUIN_BASE_JAR"))
    parser.add_argument("-o", "--output", default=None)
    parser.add_argument("--no-relocate", action="store_true",
                        help="跳过重定位步骤（调试用，正式发布禁止）")
    args = parser.parse_args()

    if not args.base_jar or not os.path.isfile(args.base_jar):
        raise SystemExit("需要提供 base JAR（任一 Spigot 版可分发 JAR）路径，或设置 PENGUIN_BASE_JAR")
    if not os.path.isfile(MODULE_JAR):
        raise SystemExit(f"缺少编译产物，先执行 ./gradlew :server-NeoForge:jar：{MODULE_JAR}")

    version = read_mod_version()
    output = args.output or os.path.join(DEFAULT_OUT_DIR, f"KERONGPenguin_NeoForge-{MC_VERSION}-{version}.jar")
    os.makedirs(os.path.dirname(output), exist_ok=True)
    snakeyaml = ensure_snakeyaml()
    merged_jar = os.path.join(os.path.dirname(output), f".nf-merged-{version}.jar")

    seen = set()
    dir_count = 0
    with zipfile.ZipFile(args.base_jar) as base, \
            zipfile.ZipFile(MODULE_JAR) as module, \
            zipfile.ZipFile(snakeyaml) as snake, \
            zipfile.ZipFile(merged_jar, "w", zipfile.ZIP_DEFLATED) as out:
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
            f"Implementation-Version: {version}\n"
            f"Automatic-Module-Name: {MOD_ID}\n",
        )

    if dir_count == 0:
        raise SystemExit("严重错误：产物中没有目录条目，SDK 组件装配会失败！")

    # 4. 第三方库重定位（split package 根治，1.5.4.3 起）
    if args.no_relocate:
        shutil.move(merged_jar, output)
    else:
        asm_jars = ensure_asm()
        run_relocate(merged_jar, output, asm_jars)
        os.remove(merged_jar)

    size_mb = os.path.getsize(output) / 1024 / 1024
    print(f"打包完成：{output}（{size_mb:.1f} MB，版本 {version}，重定位 {'关闭' if args.no_relocate else '开启'}）")


if __name__ == "__main__":
    main()

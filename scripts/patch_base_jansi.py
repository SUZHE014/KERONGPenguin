#!/usr/bin/env python3
"""base JAR jansi 引用重定位（1.7.6.7 Arclight 覆盖轮，打包流水线前置步骤）。

用法：
    python3 scripts/patch_base_jansi.py <base_jar> [-o 输出JAR]

- base_jar：旧版可分发 JAR（通常为线上 Spigot 版）；产物默认写
  build/base-jansi-patched.jar，之后三端打包均以它为 base：
      python3 scripts/package_jar.py build/base-jansi-patched.jar
      python3 scripts/package_jar_neoforge.py build/base-jansi-patched.jar
      python3 scripts/package_jar_fabric.py build/base-jansi-patched.jar
- 工具（tools/JansiRelocate.java）用 ASM ClassRemapper 把 SDK 五个类的
  org/fusesource/jansi/Ansi 引用改写到 cn/kerong/bot/compat/AnsiCompat
  （插件源码内编译的自有替身），根因与背景见两类 KDoc。
- ASM（asm / asm-commons 9.7.1）优先复用 server-Fabric 构建素材
  （fabric-deps），缺失时从 Aliyun Maven 下载缓存到 scripts/deps-work/asm/。
- 编译产物缓存到 scripts/deps-work/asm/classes/，源码未变不重编。

为什么必须做这一步（Arclight-forge 1.20.1「鉴权成功但机器人离线」）：
Spigot 1.18+ 的 PluginClassLoader 是父优先委托，fat JAR 内置的第三方库
一律被服务端自带同包名类覆盖。Spigot/Paper 恰好自带 jansi 2.x（有
Ansi.fgRgb），Arclight 自带 Forge 引导库 jansi 1.18（没有 fgRgb）→
SDK 每打一条日志就 NoSuchMethodError → QQ 客户端启动即死。把 jansi
引用改到插件自有类后，任何核心的服务端类路径都不再影响 SDK 日志链路。
"""
import argparse
import hashlib
import os
import shutil
import subprocess
import sys
import urllib.request

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOL_SRC = os.path.join(REPO, "tools", "JansiRelocate.java")
ASM_DIR = os.path.join(REPO, "scripts", "deps-work", "asm")
CLASSES_DIR = os.path.join(ASM_DIR, "classes")
FABRIC_DEPS = os.path.join(REPO, "server-Fabric", "libs")

ASM_VERSION = "9.7.1"
ASM_ARTIFACTS = {
    "asm.jar": f"org/ow2/asm/asm/{ASM_VERSION}/asm-{ASM_VERSION}.jar",
    "asm-commons.jar": f"org/ow2/asm/asm-commons/{ASM_VERSION}/asm-commons-{ASM_VERSION}.jar",
}
ALIYUN = "https://maven.aliyun.com/repository/public/"


def ensure_asm() -> list:
    """返回 ASM classpath（jar 路径列表），优先复用已入库构件。"""
    tracked = {
        "asm.jar": os.path.join(REPO, "scripts", "deps-work", f"asm-{ASM_VERSION}.jar"),
        "asm-commons.jar": os.path.join(REPO, "scripts", "deps-work", f"asm-commons-{ASM_VERSION}.jar"),
    }
    jars = []
    for name, path in ASM_ARTIFACTS.items():
        local = os.path.join(ASM_DIR, name)
        fabric = os.path.join(FABRIC_DEPS, os.path.basename(path))
        if not os.path.isfile(local) and os.path.isfile(tracked[name]):
            os.makedirs(ASM_DIR, exist_ok=True)
            shutil.copy2(tracked[name], local)
        if not os.path.isfile(local) and os.path.isfile(fabric):
            os.makedirs(ASM_DIR, exist_ok=True)
            shutil.copy2(fabric, local)
        if not os.path.isfile(local):
            os.makedirs(ASM_DIR, exist_ok=True)
            url = ALIYUN + path
            print(f"下载 {url}")
            urllib.request.urlretrieve(url, local)
        jars.append(local)
    return jars


def compile_tool(asm_jars: list) -> str:
    """编译 JansiRelocate（带源码 hash 缓存，未变不重编）；返回 classes 目录。"""
    os.makedirs(CLASSES_DIR, exist_ok=True)
    src_hash = hashlib.md5(open(TOOL_SRC, "rb").read()).hexdigest()
    stamp = os.path.join(CLASSES_DIR, ".tool-hash")
    if os.path.isfile(stamp) and open(stamp).read().strip() == src_hash \
            and os.path.isfile(os.path.join(CLASSES_DIR, "JansiRelocate.class")):
        return CLASSES_DIR
    javac = os.environ.get("JAVAC", "javac")
    cmd = [javac, "-cp", os.pathsep.join(asm_jars), "-d", CLASSES_DIR, TOOL_SRC]
    print("编译:", " ".join(cmd))
    subprocess.run(cmd, check=True)
    open(stamp, "w").write(src_hash)
    return CLASSES_DIR


def main() -> None:
    parser = argparse.ArgumentParser(description="base JAR jansi 引用重定位（Arclight 兼容）")
    parser.add_argument("base_jar")
    parser.add_argument("-o", "--output", default=None)
    args = parser.parse_args()

    if not os.path.isfile(args.base_jar):
        raise SystemExit(f"base JAR 不存在: {args.base_jar}")
    if not os.path.isfile(TOOL_SRC):
        raise SystemExit(f"缺少工具源码: {TOOL_SRC}")

    output = args.output or os.path.join(REPO, "build", "base-jansi-patched.jar")
    os.makedirs(os.path.dirname(output), exist_ok=True)

    asm_jars = ensure_asm()
    classes = compile_tool(asm_jars)

    java = os.environ.get("JAVA", "java")
    cp = os.pathsep.join(asm_jars + [classes])
    print(f"重定位 {args.base_jar} -> {output}")
    result = subprocess.run([java, "-cp", cp, "JansiRelocate", args.base_jar, output])
    if result.returncode != 0:
        raise SystemExit(f"JansiRelocate 失败（exit {result.returncode}）")
    size_mb = os.path.getsize(output) / 1024 / 1024
    print(f"完成：{output}（{size_mb:.1f} MB）——后续三端打包请以它为 base JAR")


if __name__ == "__main__":
    main()

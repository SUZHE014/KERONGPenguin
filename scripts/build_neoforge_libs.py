#!/usr/bin/env python3
"""
重建 server-NeoForge/libs/ 编译期真实构件（1.5.7 固化，可重复执行）。

背景：模组线为本地全量编译（gradle :server-NeoForge:jar），编译类路径需要
真实构件（全部 compileOnly，见 server-NeoForge/libs/README.txt）。构件体积
大不入库，本脚本按需下载 / 生成，已存在的文件自动跳过。

产出（server-NeoForge/libs/）：
  1. minecraft-1.21.1-mojmap.jar —— Mojang 官方 client.jar + client.txt
     （ProGuard 官方映射）经 ForgeAutoRenamingTool（ART）--reverse 反向重映射
     为 mojang 命名（net/minecraft/server/MinecraftServer 等）；
  2. neoforge-21.1.252-universal.jar —— NeoForge 官方 universal
     （事件类 / NeoForge.EVENT_BUS 等真实字节码）；
  3. loader-4.0.45.jar —— FML loader（net.neoforged.fml.common.@Mod 注解）；
  4. bus-8.0.5.jar —— NeoForge EventBus（net.neoforged.bus.api.SubscribeEvent）；
  5. brigadier-1.3.10.jar / authlib-6.0.54.jar —— Mojang 官方库
     （版本号自动取自 1.21.1 版本清单）；
  6. deps.jar —— kloping SDK / fastjson / kotlin-stdlib / okhttp 等
     （直接复制 common-Bot/libs/deps.jar，与 Spigot 线共用）。

用法：python3 scripts/build_neoforge_libs.py [--force]
"""
import argparse
import json
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
LIBS = REPO / "server-NeoForge" / "libs"
CACHE = REPO / "scripts" / "deps-work" / "nf-libs"

MC_VERSION = "1.21.1"
NEOFORGE_VERSION = "21.1.252"
FML_LOADER_VERSION = "4.0.45"
BUS_VERSION = "8.0.5"
# ART（ForgeAutoRenamingTool）：反向重映射官方 ProGuard 映射
ART_VERSION = "1.1.2"
ART_URL = f"https://maven.minecraftforge.net/net/minecraftforge/ForgeAutoRenamingTool/{ART_VERSION}/ForgeAutoRenamingTool-{ART_VERSION}-all.jar"

MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
NEOFORGE_MAVEN = "https://maven.neoforged.net/releases"


def download(url: str, dest: Path, label: str) -> Path:
    if dest.is_file() and dest.stat().st_size > 1000:
        print(f"跳过（已存在）：{label}")
        return dest
    print(f"下载 {label}：{url}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    # 2026-10：部分 maven 会拦截无 UA 的 urllib 默认请求（403），带 UA 重试
    request = urllib.request.Request(url, headers={"User-Agent": "KERONGPenguin-build/1.6.0"})
    with urllib.request.urlopen(request, timeout=120) as resp, open(dest, "wb") as out:
        out.write(resp.read())
    return dest


def main() -> None:
    parser = argparse.ArgumentParser(description="重建 server-NeoForge/libs 编译构件")
    parser.add_argument("--force", action="store_true", help="忽略缓存强制重新下载/生成")
    args = parser.parse_args()

    LIBS.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)

    # 1. Mojang 官方：client.jar + client.txt + brigadier + authlib
    print(f"解析 Mojang 版本清单（{MC_VERSION}）…")
    with urllib.request.urlopen(MANIFEST_URL) as resp:
        manifest = json.load(resp)
    version_url = next(v["url"] for v in manifest["versions"] if v["id"] == MC_VERSION)
    with urllib.request.urlopen(version_url) as resp:
        version = json.load(resp)

    client_jar = CACHE / "client.jar"
    client_txt = CACHE / "client.txt"
    download(version["downloads"]["client"]["url"], client_jar, "Mojang client.jar")
    download(version["downloads"]["client_mappings"]["url"], client_txt, "Mojang client.txt（官方映射）")

    # 键规范为 brigadier.jar / authlib.jar（值携带版本号用于兼容旧缓存命名）
    extra_libs = {}
    for lib in version["libraries"]:
        name = lib["name"]
        if name.startswith("com.mojang:brigadier:"):
            extra_libs["brigadier.jar"] = (name.split(":")[-1], lib["downloads"]["artifact"]["url"])
        elif name.startswith("com.mojang:authlib:"):
            extra_libs["authlib.jar"] = (name.split(":")[-1], lib["downloads"]["artifact"]["url"])
    for fname, (ver, url) in extra_libs.items():
        cached = CACHE / fname
        legacy = CACHE / f"{ver}.jar"
        # 旧版脚本以版本号命名缓存（如 1.3.10.jar），存在则改名复用
        if legacy.is_file() and legacy.stat().st_size > 1000 and not cached.is_file():
            shutil.copy2(legacy, cached)
            print(f"复用旧缓存：{legacy.name} → {fname}")
        download(url, cached, fname)

    # 2. NeoForge / FML / bus 官方构件
    artifacts = {
        f"neoforge-{NEOFORGE_VERSION}-universal.jar":
            f"{NEOFORGE_MAVEN}/net/neoforged/neoforge/{NEOFORGE_VERSION}/neoforge-{NEOFORGE_VERSION}-universal.jar",
        f"loader-{FML_LOADER_VERSION}.jar":
            f"{NEOFORGE_MAVEN}/net/neoforged/fancymodloader/loader/{FML_LOADER_VERSION}/loader-{FML_LOADER_VERSION}.jar",
        f"bus-{BUS_VERSION}.jar":
            f"{NEOFORGE_MAVEN}/net/neoforged/bus/{BUS_VERSION}/bus-{BUS_VERSION}.jar",
    }
    for fname, url in artifacts.items():
        download(url, CACHE / fname, fname)

    # 3. mojmap 反向重映射（ART --reverse）
    mojmap = LIBS / f"minecraft-{MC_VERSION}-mojmap.jar"
    if args.force or not mojmap.is_file():
        art = CACHE / f"ART-{ART_VERSION}.jar"
        download(ART_URL, art, "ForgeAutoRenamingTool")
        print("ART 反向重映射 client.jar（obf → mojang 命名）…")
        subprocess.run(
            ["java", "-jar", str(art),
             "--input", str(client_jar), "--output", str(mojmap),
             "--map", str(client_txt), "--reverse",
             "--ann-fix", "--ids-fix", "--record-fix"],
            check=True,
        )
    else:
        print(f"跳过（已存在）：minecraft-{MC_VERSION}-mojmap.jar")

    # 4. 拷入 libs/（deps.jar 与 Spigot 线共用）
    plan = {
        f"minecraft-{MC_VERSION}-mojmap.jar": mojmap,
        f"neoforge-{NEOFORGE_VERSION}-universal.jar": CACHE / f"neoforge-{NEOFORGE_VERSION}-universal.jar",
        f"loader-{FML_LOADER_VERSION}.jar": CACHE / f"loader-{FML_LOADER_VERSION}.jar",
        f"bus-{BUS_VERSION}.jar": CACHE / f"bus-{BUS_VERSION}.jar",
        "brigadier.jar": CACHE / "brigadier.jar",
        "authlib.jar": CACHE / "authlib.jar",
        "deps.jar": REPO / "common-Bot" / "libs" / "deps.jar",
    }
    for dest_name, src in plan.items():
        dest = LIBS / dest_name
        if not src.is_file():
            raise SystemExit(f"缺少构件源文件：{src}")
        if args.force or not dest.is_file():
            shutil.copy2(src, dest)
            print(f"拷入 libs/{dest_name}（{src.stat().st_size // 1024} KB）")
        else:
            print(f"跳过（已存在）：libs/{dest_name}")

    print()
    print(f"=== server-NeoForge/libs/ 重建完成（{len(list(LIBS.glob('*.jar')))} 个 jar）===")
    print("下一步：./gradlew :server-NeoForge:jar && python3 scripts/package_jar_neoforge.py")


if __name__ == "__main__":
    main()

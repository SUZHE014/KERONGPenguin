#!/usr/bin/env python3
"""
重建 server-NeoForge/libs/ 编译期真实构件（1.5.7 固化，可重复执行；
1.6.7.9 起版本参数化 + BMCLAPI 镜像，默认目标 MC 1.20.4 / NeoForge 20.4.253）。

背景：模组线为本地全量编译（gradle :server-NeoForge:jar），编译类路径需要
真实构件（全部 compileOnly，见 server-NeoForge/libs/README.txt）。构件体积
大不入库，本脚本按需下载 / 生成，已存在的文件自动跳载。

1.6.7.9 多版本适配（MC 1.20.4+ / 1.20.4 / 1.20.6 / 1.21.x）：
  - 编译目标降到 MC 1.20.4（NeoForge 20.4.253 / FML loader 2.0.17 / bus 7.2.0），
    面向最低支持版本编译、在更高版本运行（多版本模组标准做法）；
  - 全部下载经 BMCLAPI 镜像（GitHub/Maven 官方源在构建机不可达）：
      piston-meta.mojang.com / piston-data.mojang.com → bmclapi2.bangbang93.com（同路径）
      libraries.minecraft.net  → bmclapi2.bangbang93.com/libraries<路径>
      maven.neoforged.net     → bmclapi2.bangbang93.com/maven<路径>
      maven.minecraftforge.net→ bmclapi2.bangbang93.com/maven<路径>
  - 可用 --mc/--neoforge/--loader/--bus 覆盖目标版本。

产出（server-NeoForge/libs/）：
  1. minecraft-<MC>-mojmap.jar —— Mojang 官方 client.jar + client.txt
     （ProGuard 官方映射）经 ForgeAutoRenamingTool（ART）--reverse 反向重映射
     为 mojang 命名（net/minecraft/server/MinecraftServer 等）；
  2. neoforge-<NF>-universal.jar —— NeoForge 官方 universal
     （事件类 / NeoForge.EVENT_BUS 等真实字节码）；
  3. loader-<FML>.jar —— FML loader（net.neoforged.fml.common.@Mod 注解）；
  4. bus-<BUS>.jar —— NeoForge EventBus（net.neoforged.bus.api.SubscribeEvent）；
  5. brigadier.jar / authlib.jar —— Mojang 官方库
     （版本号自动取自目标 MC 版本清单）；
  6. deps.jar —— kloping SDK / fastjson / kotlin-stdlib / okhttp 等
     （直接复制 common-Bot/libs/deps.jar，与 Spigot 线共用）。

用法：python3 scripts/build_neoforge_libs.py [--force]
      python3 scripts/build_neoforge_libs.py --mc 1.21.1 --neoforge 21.1.252 \
          --loader 4.0.45 --bus 8.0.5
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

# 默认：最低支持版本 MC 1.20.4（NeoForge 20.4.253 / FML 2.0.17 / bus 7.2.0，
# 见 20.4.253 pom 依赖；loader 4.0.45 / bus 8.0.5 是 21.1.252 的配套版本）
MC_VERSION = "1.20.4"
NEOFORGE_VERSION = "20.4.253"
FML_LOADER_VERSION = "2.0.17"
BUS_VERSION = "7.2.0"
# ART（AutoRenamingTool，原 ForgeAutoRenamingTool）：反向重映射官方 ProGuard 映射。
# 2026-11 起改用 Maven Central 的 net.neoforged:AutoRenamingTool 2.0.x
#（BMCLAPI 的 /maven 段已不回源 net.minecraftforge 树，CLI 参数兼容 1.x）
ART_VERSION = "2.0.12"

MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

# BMCLAPI 镜像重写（构建机对 Mojang / NeoForge / Forge 官方域名不可达或极慢）
BMCLAPI = "https://bmclapi2.bangbang93.com"
# 2026-11 起 BMCLAPI 的 /maven 段不再回源 net.neoforged / net.minecraftforge 树
#（metadata 仍可读但构件一律 404）——备用社区镜像：CleanroomMC maven
# 全量代理这两个官方 maven（同路径直代，已验证 20.4.253-universal 可取）
CLEANROOM = "https://maven.cleanroommc.com"


# 官方域名 → BMCLAPI 路径前缀映射
_BMCLAPI_PREFIX = {
    "https://piston-meta.mojang.com": "",
    "https://piston-data.mojang.com": "",
    "https://libraries.minecraft.net": "/libraries",
    "https://maven.neoforged.net": "/maven",
    "https://maven.minecraftforge.net": "/maven",
}


def mirror(url: str) -> str:
    """把官方源 URL 重写为 BMCLAPI 镜像 URL（无可重写原样返回）。"""
    for host, prefix in _BMCLAPI_PREFIX.items():
        if url.startswith(host):
            return BMCLAPI + prefix + url[len(host):]
    return url


def mirror_candidates(url: str) -> list:
    """下载候选源列表（依次尝试：BMCLAPI → CleanroomMC → 原始 URL）。

    BMCLAPI 对 Mojang 域名仍稳定；neoforged / forge 域名 2026-11 起
    构件 404，由 CleanroomMC 兑底。"""
    candidates = [mirror(url)]
    for host in ("https://maven.neoforged.net", "https://maven.minecraftforge.net"):
        if url.startswith(host):
            candidates.append(CLEANROOM + url[len(host):])
    if url not in candidates:
        candidates.append(url)
    return candidates


def fetch(url: str, timeout: int = 180, retries: int = 5) -> bytes:
    """带重试的 URL 抓取（多镜像候选依次尝试；BMCLAPI 302 节点偶发
    握手超时/慢节点，轮换重试可恢复）。"""
    last_err: Exception = RuntimeError("unreachable")
    for real_url in mirror_candidates(url):
        for attempt in range(1, retries + 1):
            try:
                request = urllib.request.Request(
                    real_url, headers={"User-Agent": "KERONGPenguin-build/1.6.0"})
                with urllib.request.urlopen(request, timeout=timeout) as resp:
                    return resp.read()
            except Exception as e:  # noqa: BLE001 - 节点抖动统一重试
                last_err = e
                print(f"  第 {attempt}/{retries} 次尝试失败（{e}），{'重试' if attempt < retries else '换下一源'}")
    raise last_err


def fetch_json(url: str, timeout: int = 180):
    return json.loads(fetch(url, timeout=timeout).decode("utf-8"))


def download(url: str, dest: Path, label: str) -> Path:
    if dest.is_file() and dest.stat().st_size > 1000:
        print(f"跳过（已存在）：{label}")
        return dest
    print(f"下载 {label}：{mirror(url)}")
    dest.parent.mkdir(parents=True, exist_ok=True)
    data = fetch(url)
    with open(dest, "wb") as out:
        out.write(data)
    return dest


def main() -> None:
    parser = argparse.ArgumentParser(description="重建 server-NeoForge/libs 编译构件")
    parser.add_argument("--force", action="store_true", help="忽略缓存强制重新下载/生成")
    parser.add_argument("--mc", default=MC_VERSION, help=f"目标 Minecraft 版本（默认 {MC_VERSION}）")
    parser.add_argument("--neoforge", default=NEOFORGE_VERSION,
                        help=f"NeoForge 版本（默认 {NEOFORGE_VERSION}）")
    parser.add_argument("--loader", default=FML_LOADER_VERSION,
                        help=f"FML loader 版本（默认 {FML_LOADER_VERSION}）")
    parser.add_argument("--bus", default=BUS_VERSION, help=f"EventBus 版本（默认 {BUS_VERSION}）")
    args = parser.parse_args()
    mc, nf, loader, bus = args.mc, args.neoforge, args.loader, args.bus

    LIBS.mkdir(parents=True, exist_ok=True)
    CACHE.mkdir(parents=True, exist_ok=True)

    # 1. Mojang 官方：client.jar + client.txt + brigadier + authlib
    print(f"解析 Mojang 版本清单（{mc}）…")
    manifest = fetch_json(MANIFEST_URL)
    version_url = next(v["url"] for v in manifest["versions"] if v["id"] == mc)
    version = fetch_json(version_url)

    client_jar = CACHE / f"client-{mc}.jar"
    client_txt = CACHE / f"client-{mc}.txt"
    download(version["downloads"]["client"]["url"], client_jar, f"Mojang client.jar（{mc}）")
    download(version["downloads"]["client_mappings"]["url"], client_txt,
             f"Mojang client.txt（{mc}，官方映射）")

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

    # 2. NeoForge / FML / bus 官方构件（经 BMCLAPI 镜像）
    artifacts = {
        f"neoforge-{nf}-universal.jar":
            f"https://maven.neoforged.net/net/neoforged/neoforge/{nf}/neoforge-{nf}-universal.jar",
        f"loader-{loader}.jar":
            f"https://maven.neoforged.net/net/neoforged/fancymodloader/loader/{loader}/loader-{loader}.jar",
        f"bus-{bus}.jar":
            f"https://maven.neoforged.net/net/neoforged/bus/{bus}/bus-{bus}.jar",
    }
    for fname, url in artifacts.items():
        download(url, CACHE / fname, fname)

    # 3. mojmap 反向重映射（ART --reverse）
    mojmap = LIBS / f"minecraft-{mc}-mojmap.jar"
    if args.force or not mojmap.is_file():
        art = CACHE / f"ART-{ART_VERSION}.jar"
        download(f"https://repo1.maven.org/maven2/net/neoforged/AutoRenamingTool/"
                 f"{ART_VERSION}/AutoRenamingTool-{ART_VERSION}-all.jar", art,
                 "AutoRenamingTool")
        print(f"ART 反向重映射 client.jar（{mc}，obf → mojang 命名）…")
        subprocess.run(
            ["java", "-jar", str(art),
             "--input", str(client_jar), "--output", str(mojmap),
             "--map", str(client_txt), "--reverse",
             "--ann-fix", "--ids-fix", "--record-fix"],
            check=True,
        )
    else:
        print(f"跳过（已存在）：minecraft-{mc}-mojmap.jar")

    # 4. 拷入 libs/（deps.jar 与 Spigot 线共用）；不同 MC 版本的构件按版本共存，
    #    编译类路径由 server-NeoForge/libs/README.txt 说明的当前目标决定
    plan = {
        f"minecraft-{mc}-mojmap.jar": mojmap,
        f"neoforge-{nf}-universal.jar": CACHE / f"neoforge-{nf}-universal.jar",
        f"loader-{loader}.jar": CACHE / f"loader-{loader}.jar",
        f"bus-{bus}.jar": CACHE / f"bus-{bus}.jar",
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
    print(f"=== server-NeoForge/libs/ 重建完成（目标 MC {mc} / NeoForge {nf}，"
          f"{len(list(LIBS.glob('*.jar')))} 个 jar）===")
    print("下一步：./gradlew :server-NeoForge:jar && python3 scripts/package_jar_neoforge.py")


if __name__ == "__main__":
    main()

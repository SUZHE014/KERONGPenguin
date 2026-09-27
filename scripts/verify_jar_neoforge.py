#!/usr/bin/env python3
"""
KERONGPenguin NeoForge 分发模组 JAR 门禁验证。

用法：python3 scripts/verify_jar_neoforge.py [jar]
缺省验证 build/dist/KERONGPenguin_NeoForge-1.21.1-1.5.4.3.jar。
若本地存在已安装的 NeoForge server（tooling/nf-install/server），额外执行：
  - 平台包冲突扫描（split package 门禁，防 ResolutionException）；
  - 与 kotlin-stdlib jar 的包交集扫描（1.5.4.3 重定位门禁：重定位后必须零交集，
    模拟整合包中 KFF kotlin.stdlib 模块共存场景）。
"""
import re
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "relocate"))
sys.path.insert(0, "/home/z/my-project/scripts")
from class_strings import read_constant_pool

JAR = sys.argv[1] if len(sys.argv) > 1 else \
    "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_NeoForge-1.21.1-1.5.4.3.jar"

# 本地安装的 NeoForge 21.1.248 server（可选，存在则做平台冲突扫描）
NF_LIBS = Path("/home/z/my-project/tooling/nf-install/server/libraries")
# kotlin-stdlib 复现 jar（可选，存在则做包交集扫描）
KOTLIN_REPRO = Path("/home/z/my-project/tooling/kotlin-stdlib-repro.jar")

# 1.5.4.3 起重定位的第三方库前缀（与 package_jar_neoforge.py 保持一致）
RELOC_PREFIXES = (
    "kotlin/", "kotlinx/", "okhttp3/", "okio/", "com/alibaba/",
    "org/yaml/", "org/bouncycastle/", "org/jsoup/", "org/java_websocket/",
    "org/jetbrains/", "org/intellij/",
)
SHADE = "cn/huohuas001/shaded/"

results = []


def check(name: str, ok: bool, detail: str = ""):
    results.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (f"  {detail}" if detail and not ok else ""))


def packages_of(zf, names=None):
    names = names or zf.namelist()
    pkgs = set()
    for n in names:
        if n.endswith(".class") and not n.startswith("META-INF/"):
            parts = n.split("/")[:-1]
            if parts:
                pkgs.add("/".join(parts))
    return pkgs


def main():
    with zipfile.ZipFile(JAR) as zf:
        names = set(zf.namelist())
        dir_count = sum(1 for n in names if n.endswith("/"))

        # zip 完整性
        bad = zf.testzip()
        check("zip 完整性（testzip）", bad is None, str(bad))

        # 1. 模组元数据
        toml = zf.read("META-INF/neoforge.mods.toml").decode("utf-8") if "META-INF/neoforge.mods.toml" in names else ""
        check("neoforge.mods.toml 存在", bool(toml))
        check("mods.toml modId=kerongpenguin", 'modId = "kerongpenguin"' in toml)
        check("mods.toml version=1.5.4.3", re.search(r'^version = "1\.5\.4\.3"', toml, re.M) is not None)
        check("mods.toml modLoader=javafml", 'modLoader = "javafml"' in toml)
        check("mods.toml 依赖 neoforge 21.1+", "versionRange = \"[21.1.0,)\"" in toml)
        check("mods.toml 依赖 minecraft 1.21.1", "versionRange = \"[1.21.1,1.22)\"" in toml)

        # 2. 模组入口与兼容层
        must = [
            "cn/huohuas001/huhobotPenguin/neoforge/HuHoBotNeoForge.class",
            "cn/huohuas001/huhobotPenguin/neoforge/NeoForgeEvents.class",
            "cn/huohuas001/huhobotPenguin/neoforge/NeoForgeCommands.class",
            "cn/huohuas001/huhobotPenguin/neoforge/NeoForgeConsoleExecutor.class",
            "cn/huohuas001/huhobotPenguin/neoforge/LegacyText.class",
            "cn/huohuas001/huhobotPenguin/neoforge/NeoServerRef.class",
            "cn/huohuas001/bot/compat/SptCompat.class",
            "org/bukkit/Bukkit.class",
            "org/bukkit/OfflinePlayer.class",
            "org/bukkit/ChatColor.class",
            "org/bukkit/plugin/PluginManager.class",
            "org/bukkit/plugin/java/JavaPlugin.class",
            "org/bukkit/scheduler/BukkitScheduler.class",
            "org/bukkit/entity/Player.class",
            "org/bukkit/entity/NeoPlayer.class",
            "org/bukkit/entity/PlayerProfile.class",
            "org/bukkit/entity/EntityType.class",
            "org/bukkit/Material.class",
            "org/bukkit/Statistic.class",
            "org/bukkit/World.class",
            "org/bukkit/configuration/file/YamlConfiguration.class",
            "org/bukkit/configuration/file/MemorySection.class",
        ]
        for entry in must:
            check(f"存在 {entry.split('/')[-1]}", entry in names)

        # 3. common-Bot 同源业务类（含 1.5.4 乱码修复）
        must_business = [
            "cn/huohuas001/bot/QClient.class",
            "cn/huohuas001/bot/HuHoBot.class",
            "cn/huohuas001/bot/events/GroupMessageHandler.class",
            "cn/huohuas001/bot/tools/QqText.class",
            "cn/huohuas001/huhobotPenguin/spigot/qqbind/QqBindManager.class",
            "cn/huohuas001/huhobotPenguin/spigot/qqbind/BeijingTimeUtil.class",
            "cn/huohuas001/huhobotPenguin/spigot/stats/PlayerStatsManager.class",
            "cn/huohuas001/huhobotPenguin/spigot/stats/QueryInfoService.class",
            "cn/huohuas001/huhobotPenguin/spigot/stats/OnlineListService.class",
            "cn/huohuas001/huhobotPenguin/spigot/render/InfoCardRenderer.class",
            "cn/huohuas001/huhobotPenguin/spigot/render/OnlineListRenderer.class",
            "cn/huohuas001/huhobotPenguin/spigot/render/CardRenderPool.class",
            "cn/huohuas001/huhobotPenguin/spigot/manager/ConfigManager.class",
            "cn/huohuas001/huhobotPenguin/spigot/commands/CommandOutputAppender.class",
            "cn/huohuas001/bot/web/WebUiServer.class",
        ]
        for entry in must_business:
            check(f"存在 {entry.split('/')[-1]}（共用源码）", entry in names)

        # 4. 乱码修复关键串（GroupMessageHandler 引用 QqText）
        gm = zf.read("cn/huohuas001/bot/events/GroupMessageHandler.class")
        check("GroupMessageHandler 含 QqText 引用", b"QqText" in gm)

        # 4b. help 子命令修复（/huhobot help 与 /hb help 字面量注册）
        nc = zf.read("cn/huohuas001/huhobotPenguin/neoforge/NeoForgeCommands.class")
        check("NeoForgeCommands 含 help 子命令引用（≥2 处）", nc.count(b"help") >= 2,
              f"实际 {nc.count(b'help')} 处")

        # 5. 运行时依赖并入（1.5.4.3 起：kotlin/okhttp/okio/fastjson/snakeyaml/BC 已重定位）
        deps = [
            ("kloping SDK（原位不重定位）", "io/github/kloping/qqbot/Starter.class"),
            ("shaded kotlin-stdlib", SHADE + "kotlin/jvm/internal/Intrinsics.class"),
            ("shaded kotlin.collections", SHADE + "kotlin/collections/CollectionsKt.class"),
            ("shaded okhttp", SHADE + "okhttp3/OkHttpClient.class"),
            ("shaded okio", SHADE + "okio/Buffer.class"),
            ("shaded fastjson", SHADE + "com/alibaba/fastjson/JSON.class"),
            ("shaded snakeyaml", SHADE + "org/yaml/snakeyaml/Yaml.class"),
            ("shaded BouncyCastle", SHADE + "org/bouncycastle/jce/provider/BouncyCastleProvider.class"),
            ("shaded Java-WebSocket", SHADE + "org/java_websocket/client/WebSocketClient.class"),
        ]
        for label, entry in deps:
            check(f"依赖并入 {label}", entry in names)

        # 5b. ⚠️ 重定位硬门禁：重定位前缀下不得残留任何 .class
        for p in RELOC_PREFIXES:
            left = [n for n in names if n.startswith(p) and n.endswith(".class")]
            check(f"无 {p}*.class（已重定位）", not left, f"残留 {left[:3]}")
        check("无 META-INF/versions 条目", not any(n.startswith("META-INF/versions/") for n in names))
        check("services java.security.Provider 行已重写",
              all(
                  line.strip().startswith(SHADE.replace("/", "."))
                  for line in zf.read("META-INF/services/java.security.Provider").decode().splitlines()
                  if line.strip() and not line.startswith("#")
              ) if "META-INF/services/java.security.Provider" in names else True,
              "存在未重写的 provider 行")

        # 5c. ⚠️ 常量池结构性残留扫描（Class/NameAndType 引用必须全部重定位；
        # @Metadata d2 反射元数据字符串不判失败——仅 kotlin-reflect 读取，本包未携带）
        struct_hits = 0
        for n in names:
            if not n.endswith(".class"):
                continue
            try:
                consts, _, _ = read_constant_pool(zf.read(n))
            except Exception:
                struct_hits += 1
                continue
            for entry in consts:
                if not entry:
                    continue
                if entry[0] == "ref" and entry[1] == 7:
                    name_entry = consts[entry[2]]
                    cname = name_entry[1] if name_entry and name_entry[0] == "utf8" else ""
                    if any(cname.startswith(p) for p in RELOC_PREFIXES):
                        struct_hits += 1
                elif entry[0] == "ref2" and entry[1] == 12:
                    desc_entry = consts[entry[3]]
                    desc = desc_entry[1] if desc_entry and desc_entry[0] == "utf8" else ""
                    if any(f"L{p}" in desc for p in RELOC_PREFIXES):
                        struct_hits += 1
        check("常量池结构性引用零残留", struct_hits == 0, f"残留 {struct_hits} 处")

        # 5d. 业务类引用 shaded kotlin（证明自身字节码已同步重写）
        qclient = zf.read("cn/huohuas001/bot/QClient.class")
        check("QClient 引用 shaded kotlin", (SHADE + "kotlin/").encode() in qclient)
        compat = zf.read("cn/huohuas001/bot/compat/SptCompat.class")
        check("SptCompat 引用 shaded WebSocketClient", (SHADE + "org/java_websocket/").encode() in compat)

        # 6. 模组资源
        resources = [
            "config.yml",
            "fonts/NotoSansSC-Regular-subset.ttf",
            "fonts/NotoSansSC-Bold-subset.ttf",
            "Markdown/online.md",
        ]
        for entry in resources:
            check(f"资源 {entry}", entry in names)

        # 7. 生态安全：无泄漏 / 无模块描述 / 无签名
        check("无 net/minecraft 类泄漏", not any(n.startswith("net/minecraft/") for n in names))
        check("无 net/neoforged 类泄漏", not any(n.startswith("net/neoforged/") for n in names))
        check("无 module-info.class", not any(n == "module-info.class" or n.endswith("/module-info.class") for n in names))
        check("无签名文件 (.SF/.RSA/.DSA)", not any(
            n.startswith("META-INF/") and n.rsplit(".", 1)[-1] in {"SF", "RSA", "DSA"} for n in names))
        check("无 plugin.yml（Spigot 标记必须缺失）", "plugin.yml" not in names)
        check("无 server-Spigot 字节码泄漏", not any(
            n.startswith("cn/huohuas001/huhobotPenguin/spigot/events/") or
            n.startswith("cn/huohuas001/huhobotPenguin/spigot/commands/QqCommand") or
            n.startswith("cn/huohuas001/huhobotPenguin/spigot/commands/HuHoBotCommand") or
            n.startswith("cn/huohuas001/huhobotPenguin/spigot/commands/BukkitConsoleSender") or
            n.startswith("cn/huohuas001/huhobotPenguin/spigot/commands/HybridCommand") or
            n.startswith("cn/huohuas001/huhobotPenguin/spigot/HuHoBotSpigot")
            for n in names))
        check("无旧 kotlin_module 残留", "META-INF/common-Bot.kotlin_module" not in names and
              "META-INF/server-Spigot.kotlin_module" not in names)
        check("含模组 kotlin_module", "META-INF/server-NeoForge.kotlin_module" in names)

        # 8. 目录条目（SDK 组件扫描；1.5.4.3 丢弃 META-INF/versions 后约 748 个，
        # 阈值调整为 >700，另验 kloping 包目录条目存在——扫描兜底的关键依赖）
        check("目录条目 > 700", dir_count > 700, f"实际 {dir_count}")
        for d in ("io/github/kloping/", "io/github/kloping/qqbot/", "io/github/kloping/spt/"):
            check(f"kloping 目录条目存在 {d}", d in names)

        # 8b. ⚠️ 平台重复包硬门禁（split package → 模块解析 ResolutionException）
        check("无 org/slf4j 字节码（平台 slf4j-api 提供）", not any(
            n.startswith("org/slf4j/") and n.endswith(".class") for n in names))
        check("无 com/google/gson 字节码（平台 gson 提供）", not any(
            n.startswith("com/google/gson/") and n.endswith(".class") for n in names))
        check("无 slf4j services 声明残留", not any(
            n.startswith("META-INF/services/org.slf4j") for n in names))
        check("无 slf4j/gson maven 元数据残留", not any(
            n.startswith("META-INF/maven/org.slf4j/") or
            n.startswith("META-INF/maven/com.google.code.gson/") for n in names))

        # 8c. 与本地已安装 NeoForge 21.1.248 server libraries 全量包交集（深度门禁）
        if NF_LIBS.is_dir():
            platform_pkgs = set()
            for lib in NF_LIBS.rglob("*.jar"):
                with zipfile.ZipFile(lib) as lz:
                    ln = lz.namelist()
                    for n in ln:
                        if n.endswith(".class") and not n.startswith("META-INF/"):
                            parts = n.split("/")[:-1]
                            if parts:
                                platform_pkgs.add("/".join(parts))
            mod_pkgs = packages_of(zf, names)
            clash = sorted(mod_pkgs & platform_pkgs)
            check("与 NeoForge 21.1.248 平台包零交集", not clash, str(clash[:8]))
        else:
            print("SKIP  未找到本地 NeoForge server libraries，跳过平台交集扫描")

        # 8d. 与 kotlin-stdlib jar 包交集（1.5.4.3 重定位门禁：模拟 KFF kotlin.stdlib 共存）
        if KOTLIN_REPRO.is_file():
            with zipfile.ZipFile(KOTLIN_REPRO) as kz:
                kpkgs = packages_of(kz)
            mod_pkgs = packages_of(zf, names)
            clash = sorted(mod_pkgs & kpkgs)
            check("与 kotlin-stdlib 包零交集（KFF 共存）", not clash, str(clash[:8]))
        else:
            print("SKIP  未找到 kotlin-stdlib 复现 jar，跳过 KFF 共存交集扫描")

        # 9. MANIFEST
        manifest = zf.read("META-INF/MANIFEST.MF").decode("utf-8")
        check("MANIFEST 含 Implementation-Title", "KERONGPenguin-NeoForge" in manifest)
        check("MANIFEST Automatic-Module-Name=kerongpenguin", "Automatic-Module-Name: kerongpenguin" in manifest)

    failed = [r for r in results if not r[1]]
    print()
    print(f"共 {len(results)} 项，失败 {len(failed)} 项")
    if failed:
        for name, _, detail in failed:
            print(f"  FAIL: {name} {detail}")
        sys.exit(1)
    print("=== NeoForge 模组 JAR 全部验证通过 ===")


if __name__ == "__main__":
    main()

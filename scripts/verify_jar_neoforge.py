#!/usr/bin/env python3
"""
KERONGPenguin NeoForge 分发模组 JAR 门禁验证。

用法：python3 scripts/verify_jar_neoforge.py [jar]
缺省验证 build/dist/KERONGPenguin_NeoForge-1.21.1-1.5.4.jar。
"""
import re
import sys
import zipfile

JAR = sys.argv[1] if len(sys.argv) > 1 else \
    "/home/z/my-project/penguin-git/build/dist/KERONGPenguin_NeoForge-1.21.1-1.5.4.jar"

results = []


def check(name: str, ok: bool, detail: str = ""):
    results.append((name, ok, detail))
    print(("PASS " if ok else "FAIL ") + name + (f"  {detail}" if detail and not ok else ""))


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
        check("mods.toml version=1.5.4", re.search(r'^version = "1\.5\.4"', toml, re.M) is not None)
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

        # 5. 运行时依赖并入
        deps = [
            "io/github/kloping/qqbot/Starter.class",
            "com/alibaba/fastjson/JSON.class",
            "kotlin/jvm/internal/Intrinsics.class",
            "okhttp3/OkHttpClient.class",
            "org/yaml/snakeyaml/Yaml.class",
        ]
        for entry in deps:
            check(f"依赖并入 {entry.split('/')[-1]}", entry in names)

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

        # 8. 目录条目（SDK 组件扫描）
        check("目录条目 > 800", dir_count > 800, f"实际 {dir_count}")

        # 9. MANIFEST
        manifest = zf.read("META-INF/MANIFEST.MF").decode("utf-8")
        check("MANIFEST 含 Implementation-Title", "KERONGPenguin-NeoForge" in manifest)

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

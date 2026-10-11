server-NeoForge/libs/ 编译期真实构件说明（体积大不入库）
=====================================================

模组线为本地全量编译：gradle :server-NeoForge:jar 直接对 common-Bot 全部源码 +
server-Spigot 的 manager 目录 + org.bukkit 兼容层 + 模组源编译，编译类路径需要
下述真实构件（build.gradle.kts 中全部 compileOnly）。

一键重建（推荐）：
    python3 scripts/build_neoforge_libs.py

构件清单（7 个 jar）：
  1. minecraft-1.21.1-mojmap.jar
     Mojang 官方 client.jar + client.txt（ProGuard 官方映射），经
     ForgeAutoRenamingTool --reverse 反向重映射为 mojang 命名
     （net/minecraft/server/MinecraftServer 等）；
  2. neoforge-21.1.252-universal.jar
     NeoForge 官方 universal（事件类 / NeoForge.EVENT_BUS 等真实字节码）；
  3. loader-4.0.45.jar
     FML loader（net.neoforged.fml.common.@Mod 注解）；
  4. bus-8.0.5.jar
     NeoForge EventBus（net.neoforged.bus.api.SubscribeEvent）；
  5. brigadier.jar（com.mojang:brigadier，版本取自 1.21.1 版本清单）；
  6. authlib.jar（com.mojang:authlib，同上）；
  7. deps.jar
     kloping QQ SDK / fastjson / kotlin-stdlib / okhttp 等，直接复制
     common-Bot/libs/deps.jar（与 Spigot 线共用，获取方式见根 README）。

其余编译依赖（snakeyaml / log4j-core / fastutil / guava / gson）由 Gradle 从
Maven Central 按声明解析，无需放入本目录。

分发 JAR 由 scripts/package_jar_neoforge.py 合成（以任一 Spigot 分发 JAR 为
依赖基底 + 重定位），本目录仅用于编译。

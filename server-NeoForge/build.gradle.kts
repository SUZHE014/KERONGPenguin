plugins {
    kotlin("jvm")
}

// NeoForge 1.21.1 适配器：与 Spigot 版同源（common-Bot + manager 目录共用源码），
// 经 org.bukkit 兼容层（src/main/kotlin/org/bukkit）桥接到 NeoForge API。
//
// 构建方式（本仓库构建机内存不足以跑完 NFRT 反编译→重编译全流程）：
// 编译类路径直接使用真实构件（全部 compileOnly，见 libs/README.txt）——
//   - minecraft-1.21.1-mojmap.jar：NFRT 管线 rename 中间产物（mojmap 名的原版类）；
//   - neoforge-21.1.252-universal.jar：NeoForge 官方 universal（事件 / 总线 API 真实字节码）；
//   - fml-loader / bus / brigadier / authlib：官方构件。
// 分发 JAR 由 scripts/package_jar_neoforge.py 合成（并入 kloping SDK / fastjson /
// kotlin-stdlib / snakeyaml 等运行时依赖），模块自身产物不含这些依赖。
version = "1.5.4.3"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

sourceSets {
    main {
        kotlin.srcDir("src/main/kotlin")
        // 共用源码：common-Bot 全部 + server-Spigot 的 manager 目录（ConfigManager，
        // 1.5.4 起参数放宽为 JavaPlugin，经兼容层复用）
        kotlin.srcDir(rootProject.file("common-Bot/src/main/kotlin"))
        kotlin.srcDir(rootProject.file("server-Spigot/src/main/kotlin/cn/huohuas001/huhobotPenguin/spigot/manager"))
    }
}

dependencies {
    // 本地编译期真实构件（NeoForge / Minecraft mojmap / FML / bus / brigadier / authlib /
    // kloping SDK + fastjson + kotlin-stdlib 等）
    compileOnly(fileTree("libs") { include("*.jar") })
    // YamlConfiguration 兼容层（运行时由打包脚本并入 snakeyaml）
    compileOnly("org.yaml:snakeyaml:2.2")
    // log4j-core（服务器运行时自带；CommandOutputAppender 编译期引用）
    compileOnly("org.apache.logging.log4j:log4j-core:2.22.1")
    // Minecraft 传递依赖（编译解析签名用，运行时由服务器自带）
    compileOnly("it.unimi.dsi:fastutil-core:8.5.13")
    compileOnly("com.google.guava:guava:32.1.2-jre")
    compileOnly("com.google.code.gson:gson:2.11.0")
}

tasks.jar {
    archiveFileName = "server-NeoForge.jar"
}

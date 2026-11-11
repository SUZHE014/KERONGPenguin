plugins {
    kotlin("jvm")
}

// Fabric 适配器（测试版，MC 1.20.4+）：与 Spigot / NeoForge 版同源
// （common-Bot + manager 目录共用源码），经 org.bukkit 兼容层
// （src/main/kotlin/org/bukkit）桥接到 Fabric API。
//
// 构建方式（无 Loom，与 NeoForge 版同思路——直接用真实构件编译）：
// 编译类路径使用 mojmap 名的 minecraft jar（与 NeoForge 版共用）+
// fabric-api（已用 tiny-remapper 从 intermediary 重映射到 mojmap 名）+
// fabric-loader / brigadier / authlib / deps.jar（SDK），全部 compileOnly。
//
// 分发 JAR 两步（scripts/package_jar_fabric.py）：
// 1. 模块产物经 tiny-remapper 把 net.minecraft 类引用从 mojmap 名重映射为
//    intermediary 名（Fabric 运行时的 MC 类名）；
// 2. 以 Spigot 版可分发 JAR 为底座并入第三方依赖字节码（kloping SDK /
//    fastjson / kotlin-stdlib / snakeyaml 等，剔除底座的 Spigot 插件类与
//    org.bukkit 真实 API——由本模块自带兼容层提供同名类）。
version = "1.7.5.1"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

sourceSets {
    main {
        kotlin.srcDir("src/main/kotlin")
        // 共用源码：common-Bot 全部 + server-Spigot 的 manager 目录
        // （ConfigManager，1.5.4 起参数放宽为 JavaPlugin，经兼容层复用；
        //  与 NeoForge 版同一份共用源码）
        kotlin.srcDir(rootProject.file("common-Bot/src/main/kotlin"))
        kotlin.srcDir(rootProject.file("server-Spigot/src/main/kotlin/cn/kerong/penguin/spigot/manager"))
    }
}

dependencies {
    // 本地编译期真实构件（minecraft mojmap / fabric-api mojmap 版 /
    // fabric-loader / brigadier / authlib / kloping SDK + fastjson + kotlin-stdlib）
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
    archiveFileName = "server-Fabric.jar"
}

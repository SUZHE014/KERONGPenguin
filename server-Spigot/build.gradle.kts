plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // 保持与 Java 17+ 服务器兼容的字节码版本
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(project(":common-Bot"))
    // 1.5.7.3：升级到 1.20.4（最后的 Java 17 基线版本）——
    // 编译期需要 io.papermc.paper.threadedregions.scheduler（Folia 区域化调度 API）。
    // 运行时兼容性不受影响：服务器自带 Bukkit API，插件字节码仍按 Java 17 产出，
    // Folia 专属 API 仅在 FoliaDelegate 中引用（惰性加载，旧服务器永不触发）。
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    compileOnly(files("libs/deps.jar"))
    // Log4j2 Appender（服务器运行时自带）
    compileOnly("org.apache.logging.log4j:log4j-core:2.17.1")
}

// 构建产物：server-Spigot 模块的字节码（最终 fat JAR 由 build 目录下的打包脚本合成）
tasks.jar {
    archiveFileName = "server-Spigot.jar"
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

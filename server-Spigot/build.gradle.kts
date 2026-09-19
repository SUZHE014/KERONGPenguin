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
    compileOnly("io.papermc.paper:paper-api:1.18.2-R0.1-SNAPSHOT")
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

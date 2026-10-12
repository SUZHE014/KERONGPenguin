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

sourceSets {
    main {
        kotlin.srcDir("src/main/kotlin")
    }
}

dependencies {
    // Spigot 服务端 API（运行时由服务器提供）
    // 1.5.7.3：升级到 1.20.4（最后的 Java 17 基线版本），编译期获得
    // Folia 区域化调度 API；Folia 专属引用隔离在 FoliaDelegate（惰性加载）
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    // 原版 JAR 中内置的依赖（kloping QQ SDK / fastjson 等），编译期引用，打包时复用原 JAR 中的字节码
    compileOnly(files("libs/deps.jar"))
}

tasks.jar {
    archiveFileName = "common-Bot.jar"
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

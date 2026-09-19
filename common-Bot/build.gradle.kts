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
    compileOnly("io.papermc.paper:paper-api:1.18.2-R0.1-SNAPSHOT")
    // 原版 JAR 中内置的依赖（kloping QQ SDK / fastjson 等），编译期引用，打包时复用原 JAR 中的字节码
    compileOnly(files("libs/deps.jar"))
}

tasks.jar {
    archiveFileName = "common-Bot.jar"
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.papermc.io/repository/maven-public/") }
        maven { url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") }
        maven { url = uri("https://maven.aliyun.com/repository/public/") }
    }
}

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin/") }
        // 1.5.4：ModDevGradle 插件（server-NeoForge 模块，NeoForge 1.21.1）
        maven { url = uri("https://maven.neoforged.net/releases") }
    }
}

plugins {
    // 自动下载构建所需的 JDK 工具链
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "KERONGPenguin"

include(":common-Bot")
project(":common-Bot").projectDir = file("common-Bot")
include(":server-Spigot")
project(":server-Spigot").projectDir = file("server-Spigot")
include(":server-NeoForge")
project(":server-NeoForge").projectDir = file("server-NeoForge")

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

// server-Fabric（fa 版，1.7.5.1 起移入主线构建）：构建期构件（fabric-api
// mojmap 重映射 / loader / intermediary 映射等）体积大不入库，默认不在
// 仓库里的机器上；构件就绪（先跑 scripts/build_fabric_libs.py）时自动进
// 主线构建，与 Spigot / NeoForge 同一条发版链。
if (file("server-Fabric/libs/deps.jar").exists() && file("server-Fabric/libs/fabric-api-mojmap.jar").exists()) {
    include(":server-Fabric")
    project(":server-Fabric").projectDir = file("server-Fabric")
}

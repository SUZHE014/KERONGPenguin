package cn.huohuas001.bot.state

import cn.huohuas001.bot.datapack.AdministratorAccessMode
import cn.huohuas001.bot.datapack.StoredCommandSettings
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.Properties

/**
 * 群命令运行状态的持久化存储（人类可读的 INI 格式）。
 *
 * 文件结构示例：
 * ```
 * [administrators]
 * 群OpenId = 用户Id1, 用户Id2
 *
 * [authenticated-users]
 * 群OpenId = 用户Id1
 *
 * [administrator-modes]
 * 群OpenId = QQ|MANUAL|BOTH
 *
 * [full-forwarding]
 * 群OpenId = true|false
 * ```
 */
class HumanReadableStateFile {
    private var target: File? = null

    /**
     * 初始化：优先读取 command-state.ini，
     * 兼容旧版 command-state.properties（读取后迁移保存为 ini）。
     */
    fun initialize(dataDirectory: File?): StoredCommandSettings {
        if (dataDirectory == null) {
            target = null
            return StoredCommandSettings()
        }
        val iniFile = dataDirectory.resolve("command-state.ini").also { target = it }
        if (iniFile.isFile) return readIni(iniFile)
        val legacyFile = dataDirectory.resolve("command-state.properties")
        if (!legacyFile.isFile) return StoredCommandSettings()
        val migrated = readLegacyProperties(legacyFile)
        save(migrated)
        return migrated
    }

    /** 将快照写入临时文件后原子替换，避免写入中途损坏。 */
    @Synchronized
    fun save(snapshot: StoredCommandSettings) {
        val outputFile = target ?: return
        outputFile.parentFile?.mkdirs()
        val temporaryFile = File(outputFile.absolutePath + ".tmp")
        BufferedWriter(OutputStreamWriter(FileOutputStream(temporaryFile), StandardCharsets.UTF_8)).use { writer ->
            writer.append("# HuHoBot 群命令运行状态").append('\n')
            writer.append("# 建议仅在服务器停止时手动编辑此文件。").append('\n').append('\n')
            writeUserSection(writer, "administrators", snapshot.administrators)
            writeUserSection(writer, "authenticated-users", snapshot.authenticatedUsers)
            writeValueSection(
                writer,
                "administrator-modes",
                snapshot.administratorModes.mapValues { it.value.name },
            )
            writeValueSection(
                writer,
                "full-forwarding",
                snapshot.fullForwarding.mapValues { it.value.toString() },
            )
        }
        if (outputFile.exists()) outputFile.delete()
        temporaryFile.renameTo(outputFile)
    }

    /** 解析 INI 文件为设置快照。 */
    private fun readIni(file: File): StoredCommandSettings {
        val administrators = linkedMapOf<String, Set<String>>()
        val authenticatedUsers = linkedMapOf<String, Set<String>>()
        val administratorModes = linkedMapOf<String, AdministratorAccessMode>()
        val fullForwarding = linkedMapOf<String, Boolean>()

        var section = ""
        file.readLines(StandardCharsets.UTF_8).forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim()
                return@forEach
            }
            val parts = line.split('=', limit = 2)
            if (parts.size != 2) return@forEach
            val groupId = parts[0].trim()
            val value = parts[1].trim()
            when (section) {
                "administrators" -> administrators[groupId] = parseUsers(value)
                "authenticated-users" -> authenticatedUsers[groupId] = parseUsers(value)
                "administrator-modes" -> {
                    val mode = AdministratorAccessMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                    if (mode != null) administratorModes[groupId] = mode
                }
                "full-forwarding" -> value.toBooleanStrictOrNull()?.let { fullForwarding[groupId] = it }
            }
        }
        return StoredCommandSettings(administrators, authenticatedUsers, administratorModes, fullForwarding)
    }

    /** 读取旧版 properties 格式。 */
    private fun readLegacyProperties(file: File): StoredCommandSettings {
        val properties = Properties()
        FileInputStream(file).use { properties.load(it) }
        val administrators = linkedMapOf<String, Set<String>>()
        val authenticatedUsers = linkedMapOf<String, Set<String>>()
        val administratorModes = linkedMapOf<String, AdministratorAccessMode>()
        val fullForwarding = linkedMapOf<String, Boolean>()
        for (key in properties.stringPropertyNames()) {
            val value = properties.getProperty(key) ?: ""
            when {
                key.startsWith("admins.") -> administrators[key.removePrefix("admins.")] = parseUsers(value)
                key.startsWith("auth.") -> authenticatedUsers[key.removePrefix("auth.")] = parseUsers(value)
                key.startsWith("mode.") -> legacyMode(value)?.let { administratorModes[key.removePrefix("mode.")] = it }
                key.startsWith("full.") -> fullForwarding[key.removePrefix("full.")] = value.toBoolean()
            }
        }
        return StoredCommandSettings(administrators, authenticatedUsers, administratorModes, fullForwarding)
    }

    /** 旧版模式取值映射。 */
    private fun legacyMode(value: String): AdministratorAccessMode? = when (value) {
        "onlyQQ" -> AdministratorAccessMode.QQ
        "onlyManual" -> AdministratorAccessMode.MANUAL
        "both" -> AdministratorAccessMode.BOTH
        else -> null
    }

    /** 解析逗号分隔的用户列表。 */
    private fun parseUsers(value: String): Set<String> = value.split(',').map { it.trim() }.toSet()

    /** 写入用户列表 section。 */
    private fun writeUserSection(writer: BufferedWriter, name: String, values: Map<String, Set<String>>) {
        writeValueSection(writer, name, values.mapValues { (_, users) -> users.joinToString(",") })
    }

    /** 写入键值 section（按群号排序保证输出稳定）。 */
    private fun writeValueSection(writer: BufferedWriter, name: String, values: Map<String, String>) {
        writer.append("[$name]").append('\n')
        for ((groupId, value) in values.toSortedMap()) {
            writer.append("$groupId = $value").append('\n')
        }
        writer.append('\n')
    }
}

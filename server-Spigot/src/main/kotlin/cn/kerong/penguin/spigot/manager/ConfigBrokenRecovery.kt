package cn.kerong.penguin.spigot.manager

import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 配置文件解析失败自动恢复（1.6.7.6 新增）。
 *
 * ## 修复的问题（用户日志）
 *
 * 用户手改 config.yml 出现 YAML 语法错误（如 `player-events:true` 冒号后
 * 少了空格）时，旧版行为链是：
 * 1. Bukkit 的 `YamlConfiguration.loadConfiguration(File)` 吞异常打一大段
 *    吓人的 ERROR 堆栈（`Cannot load plugins\...\config.yml` +
 *    InvalidConfigurationException 整栈）；
 * 2. 内存里得到**空配置**；
 * 3. `ConfigManager.reload` 判「配置文件为空或格式错误，跳过保存以避免
 *    数据丢失」后直接 return——插件带着空配置继续跑；
 * 4. `bot.app-id` 读出来是空 → 「未配置 bot.app-id 或 bot.secret，
 *    QQ机器人未启动」——**机器人整个不启动**，用户只看到一条和真实原因
 *    （语法错误）无关的提示。
 *
 * ## 新行为（三级恢复）
 *
 * 1. **严格解析**：用 `YamlConfiguration().load(Reader)` 实例方法（解析
 *    失败抛出，同 [ConfigFileRebuilder.loadYaml] 的可感知语义），文件
 *    能解析 → 直接返回（零开销快路径）；
 * 2. **轻量修复**：解析失败先试「键冒号后缺空格」修复——逐行把
 *    `key:value`（冒号后无空格且非注释 / 非列表项）改写为 `key: value`
 *    后重试解析；能解析则备份原文件后把修复版写回，控制台 INFO 说明
 *    修复了几行（用户改完手滑的常见笔误零损失自愈）；
 * 3. **兜底重建**：仍解析不了（结构坏了，如本该是段落的键被赋了标量值
 *    导致子键悬空）——原文件备份为 `config.yml.error-<时间戳>`，从 JAR
 *    释放默认 config.yml 覆盖，控制台 WARN 多行说明：错误详情（含行号）、
 *    备份位置、请从备份把 bot.app-id / bot.secret / bot.groups 等自定义
 *    设置填回新文件后重启。**插件以默认配置继续启动（机器人可正常拉起）**，
 *    不再出现「跳过保存 → 当作未配置 → 机器人不启动」的死胡同。
 *
 * 双端共用（Spigot 与 NeoForge 兼容层同名 API）；调用点：
 * [ConfigManager.initialize]（rebuildIfNeeded 之前）与 [ConfigManager.reload]
 * （/kerong reload 运行期坏文件同样恢复）。
 */
internal object ConfigBrokenRecovery {

    /** 「键冒号后缺空格」行匹配（key:value → key: value）。 */
    private val missingSpaceRegex = Regex("^(\\s*)([^:\\s#][^:]*?):(\\S.*)$")

    /**
     * 检查并恢复损坏的 config.yml。
     *
     * @return true = 已用默认配置重建（调用方后续照常 reload 即拿到默认值）；
     *   false = 文件本来健康 / 已轻量修复（修复版已写回，照常走正常流程）
     */
    fun recoverIfBroken(plugin: JavaPlugin): Boolean {
        val file = File(plugin.dataFolder, "config.yml")
        if (!file.isFile) return false

        // 1. 严格解析：能过就直接走（零开销快路径）
        val firstError = tryParse(file) ?: return false

        // 2. 轻量修复：键冒号后缺空格（最高频手改笔误）
        val repaired = tryRepairMissingSpace(file)
        if (repaired != null) {
            val secondError = tryParse(repaired.first)
            if (secondError == null) {
                // 修复版可解析：备份原文件 → 写回修复版 → INFO 说明
                val backup = backupFile(file, "config.yml.error")
                try {
                    atomicWrite(file, repaired.first.readBytes())
                } catch (t: Throwable) {
                    plugin.logger.warning("配置自动修复写盘失败：${t.message}")
                    return false
                }
                plugin.logger.warning(
                    "配置文件有 ${repaired.second} 行「冒号后缺空格」的笔误（如 key:value 应为 key: value），" +
                        "已自动修复并写回；原文件备份为 ${backup.name}（如无其他改动可删除该备份）"
                )
                return false
            }
            // 修复了空格但仍解析失败：说明还有别的结构问题，走兜底重建
            return rebuildDefault(plugin, file, secondError, firstError)
        }

        // 3. 兜底重建：备份 + 释放默认 + 醒目指引
        return rebuildDefault(plugin, file, firstError, firstError)
    }

    // ---------- 内部 ----------

    /** 严格解析（失败返回异常消息文本，成功返回 null）。 */
    private fun tryParse(file: File): String? {
        return try {
            InputStreamReader(FileInputStream(file), StandardCharsets.UTF_8).use { reader ->
                YamlConfiguration().load(reader)
            }
            null
        } catch (t: Throwable) {
            compactError(t)
        }
    }

    /**
     * 生成「缺空格修复版」（返回临时文件 + 修复行数；无可修行返回 null）。
     * 只改非注释、非列表项、冒号后紧跟非空白字符的键行。
     */
    private fun tryRepairMissingSpace(file: File): Pair<File, Int>? {
        return try {
            val lines = file.readLines(StandardCharsets.UTF_8)
            var fixed = 0
            val output = StringBuilder()
            for ((index, raw) in lines.withIndex()) {
                val match = missingSpaceRegex.matchEntire(raw)
                if (match != null) {
                    output.append("${match.groupValues[1]}${match.groupValues[2]}: ${match.groupValues[3]}")
                    fixed++
                } else {
                    output.append(raw)
                }
                if (index < lines.size - 1) output.append('\n')
            }
            if (fixed == 0) null else {
                val tmp = File(file.parentFile, "config.yml.repair-tmp")
                atomicWrite(tmp, output.toString().toByteArray(StandardCharsets.UTF_8))
                tmp to fixed
            }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 兜底：备份损坏文件 → 从 JAR 释放默认 config.yml → 控制台醒目指引。
     *
     * @param primaryError 修复尝试（若有）后的解析错误
     */
    private fun rebuildDefault(
        plugin: JavaPlugin,
        file: File,
        primaryError: String,
        firstError: String,
    ): Boolean {
        // 释放 JAR 默认模板（与 saveDefaultConfig 同源）
        val defaultText = try {
            plugin.getResource("config.yml")?.use { it.readBytes() }
        } catch (_: Throwable) {
            null
        }
        if (defaultText == null) {
            // JAR 资源异常（极端）：保底只备份不覆盖，走旧容错路径
            backupFile(file, "config.yml.error")
            plugin.logger.warning("config.yml 解析失败且无法释放默认模板（${firstError}），已仅备份原文件")
            return false
        }
        val backup = backupFile(file, "config.yml.error")
        try {
            atomicWrite(file, defaultText)
        } catch (t: Throwable) {
            plugin.logger.warning("配置重建写盘失败：${t.message}")
            return false
        }
        val errorText = if (primaryError != firstError) "$firstError；修复空格后仍报 $primaryError" else firstError
        plugin.logger.warning("──────────────────────────────────────────────")
        plugin.logger.warning("配置文件 config.yml 存在语法错误，已自动恢复：")
        plugin.logger.warning("  解析错误：$errorText")
        plugin.logger.warning("  原文件已备份为：${backup.name}（你原来的全部设置都在里面）")
        plugin.logger.warning("  已重建默认配置并以默认值继续启动（机器人可正常拉起）。")
        plugin.logger.warning("  请打开备份文件对照，把 bot.app-id / bot.secret /")
        plugin.logger.warning("  bot.groups 等自定义设置重新填回新的 config.yml，")
        plugin.logger.warning("  然后重启服务器生效。")
        plugin.logger.warning("──────────────────────────────────────────────")
        // 文件留档完整错误详情（含堆栈首几帧，可追溯）
        try {
            cn.kerong.bot.tools.PluginFileLog.write(
                "[配置恢复] config.yml 解析失败已重建默认（错误：$errorText；备份：${backup.name}）"
            )
        } catch (_: Throwable) {
        }
        return true
    }

    /** 备份文件（带时间戳后缀，返回备份文件对象；失败返回原路径名兜底）。 */
    private fun backupFile(file: File, baseName: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val backup = File(file.parentFile, "$baseName-$stamp")
        return try {
            file.copyTo(backup, overwrite = true)
            backup
        } catch (_: Throwable) {
            File(file.parentFile, "$baseName-latest")
        }
    }

    /** 原子写盘（临时文件 + 替换）。 */
    private fun atomicWrite(file: File, bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        Files.write(tmp.toPath(), bytes)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    /** 异常消息压缩（保留定位信息，去掉换行堆栈；限 3 段）。 */
    private fun compactError(t: Throwable): String {
        val raw = t.message ?: t.toString()
        return raw.lineSequence().take(3).joinToString(" ").take(280)
    }
}

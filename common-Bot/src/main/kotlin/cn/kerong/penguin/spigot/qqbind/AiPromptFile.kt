package cn.kerong.penguin.spigot.qqbind

import cn.kerong.bot.tools.PluginFileLog
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * AI 提示词文件（单文件）：机器人的角色设定与专属知识写在插件目录的
 * 一个 .md 文件里（默认 提示词.md，ai.prompt-file 可改文件名），整份
 * 文件内容作为 system prompt 发给模型。
 *
 * 设计口径（取代早期的 knowledge 文件夹检索方案）：
 * - 单文件而非文件夹：角色设定（原配置项 ai.system-prompt）与专属
 *   知识都写在这一个文件里，段落结构由用户自由组织；
 * - 首次启动（或文件被删后）自动生成带说明结构的默认文件，已有文件
 *   一律不覆盖；
 * - 改完保存，下次对话自动生效（每次对话都重新读文件），无需重启；
 * - 升级迁移：旧配置 ai.system-prompt 的自定义值自动并入文件
 *   （见 ConfigManager.initialize 的迁移步骤），迁移必须在配置重建
 *   删键之前执行，否则用户自定义的角色设定会丢。
 *
 * 纯函数（默认内容 / 读取归一）拆出便于冒烟。
 */
object AiPromptFile {

    /** 默认提示词文件名（插件目录下，ai.prompt-file 可改）。 */
    const val DEFAULT_FILE_NAME = "提示词.md"

    /** 默认角色设定（与历史版本 ai.system-prompt 默认值一致）。 */
    const val DEFAULT_ROLE = "你是一个友好的游戏群助手，请简洁回答。"

    /** 单文件字符上限（超出截断记日志，防误放大文件撑爆对话上下文）。 */
    const val MAX_FILE_CHARS = 200_000

    /**
     * 默认文件内容（纯函数，冒烟直接验证）：
     * 「角色设定」段为身份与回复风格，「专属知识」段写给 AI 的资料
     * （服务器规则、玩法说明、常见问题等）。
     */
    fun defaultContent(role: String): String = buildString {
        append("# 角色设定\n\n")
        append(role.trim().ifEmpty { DEFAULT_ROLE })
        append("\n\n# 专属知识（服务器规则、玩法说明、常见问题等，写在这里 AI 对话时会参考）\n\n")
        append("（在这里补充内容，可删除本行）\n")
    }

    /**
     * 确保提示词文件存在（不存在时写入默认内容；已有文件一律不动）。
     * @param file 插件数据目录下的提示词文件（null = 未就绪，跳过）
     */
    fun ensureFile(file: File?): File? {
        if (file == null) return null
        return try {
            if (!file.isFile) {
                file.parentFile?.mkdirs()
                file.writeText(defaultContent(DEFAULT_ROLE), StandardCharsets.UTF_8)
                PluginFileLog.write(
                    "[提示词] 已生成默认提示词文件 ${file.name}" +
                        "（角色设定与专属知识都写在这个文件里，改完保存下次对话自动生效）"
                )
            }
            file
        } catch (t: Throwable) {
            PluginFileLog.write("[提示词] 创建文件失败（${t.message}）")
            null
        }
    }

    /**
     * 读取提示词内容（每次对话都重读，改文件即时生效）。
     * 文件缺失 / 读取失败 / 内容为空 → 返回默认角色设定（对话不受影响）。
     */
    fun loadPrompt(file: File?): String {
        if (file == null) return DEFAULT_ROLE
        return try {
            if (!file.isFile) return DEFAULT_ROLE
            val text = file.readText(StandardCharsets.UTF_8)
            if (text.length > MAX_FILE_CHARS) {
                PluginFileLog.write(
                    "[提示词] 文件过长（${text.length} 字符 > $MAX_FILE_CHARS），已截断使用前 $MAX_FILE_CHARS 字符"
                )
                text.substring(0, MAX_FILE_CHARS).trim().ifEmpty { DEFAULT_ROLE }
            } else {
                text.trim().ifEmpty { DEFAULT_ROLE }
            }
        } catch (t: Throwable) {
            PluginFileLog.write("[提示词] 读取失败（${t.message}），本次对话按默认角色设定")
            DEFAULT_ROLE
        }
    }

    /**
     * 升级迁移：把旧配置 ai.system-prompt 的值并入提示词文件
     * （文件已存在时不动——用户可能已手工编辑过）。
     * @return true = 已生成迁移文件；false = 无需迁移 / 文件已存在 / 失败
     */
    fun migrateFromSystemPrompt(file: File?, oldRole: String?): Boolean {
        if (file == null || oldRole.isNullOrBlank()) return false
        return try {
            if (file.isFile) return false
            file.parentFile?.mkdirs()
            file.writeText(defaultContent(oldRole), StandardCharsets.UTF_8)
            true
        } catch (t: Throwable) {
            PluginFileLog.write("[提示词] 迁移旧角色设定失败（${t.message}），将生成默认文件")
            false
        }
    }
}

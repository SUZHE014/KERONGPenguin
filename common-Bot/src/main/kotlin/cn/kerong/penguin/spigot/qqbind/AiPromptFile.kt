package cn.kerong.penguin.spigot.qqbind

import cn.kerong.bot.tools.PluginFileLog
import cn.kerong.penguin.spigot.manager.DataLayout
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
     * 「角色设定」段为身份与回复风格；「技能」段写 AI 拥有的技能与
     * 使用引导（1.7.6.3 三次覆盖轮新增——遇到不会 / 没把握 / 时效性
     * 的问题可以搜索，具体技能与开关状态以系统注入的「系统技能」段
     * 为准；四次覆盖轮：搜索引导仅在 [crawlerEnabled]（ai.crawler.enabled
     * 开启）时写入——关闭时不引导 AI 去用它用不了的功能；已生成的
     * 旧文件一律不动，不做自动清理）；「专属知识」段写给 AI 的资料
     * （服务器规则、玩法说明、常见问题等）。
     */
    fun defaultContent(role: String, crawlerEnabled: Boolean): String = buildString {
        append("# 角色设定\n\n")
        append(role.trim().ifEmpty { DEFAULT_ROLE })
        append("\n\n")
        append(skillSection(crawlerEnabled))
        append("\n# 专属知识（服务器规则、玩法说明、常见问题等，写在这里 AI 对话时会参考）\n\n")
        append("（在这里补充内容，可删除本行）\n")
    }

    /**
     * 「技能」段内容（含标题行与全部技能行，纯函数）：[defaultContent] /
     * [applySkillSection] / [applyCrawlerGuidance] 共用同源内容，杜绝
     * 多处手抄漂移。1.7.6.4 抽出（插件更新时技能段刷新机制，见
     * [syncSkillSection]）。
     */
    fun skillSection(crawlerEnabled: Boolean): String = buildString {
        append("# 技能（你拥有的技能，系统自动注入，不用手动维护）\n\n")
        append("- 发表情包：气氛合适时在回复末尾加 [表情] 文件名；\n")
        if (crawlerEnabled) append(CRAWLER_GUIDANCE_LINE).append('\n')
        append("- 技能是否可用以系统注入的「系统技能」段为准。\n")
    }

    /**
     * 确保提示词文件存在（不存在时写入默认内容；已有文件一律不动）。
     * @param file 插件数据目录下的提示词文件（null = 未就绪，跳过）
     * @param crawlerEnabled 首次生成默认内容时是否写入搜索引导
     * （ai.crawler.enabled 当前值；只影响首次生成，已生成的文件不随
     * 开关变化重写，服主可参考新默认模板自行增删）
     */
    fun ensureFile(file: File?, crawlerEnabled: Boolean): File? {
        if (file == null) return null
        return try {
            if (!file.isFile) {
                file.parentFile?.mkdirs()
                file.writeText(defaultContent(DEFAULT_ROLE, crawlerEnabled), StandardCharsets.UTF_8)
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
     * 爬取开启时自动补写搜索引导（1.7.6.4，用户口径「确保当 AI 网页爬取
     * 功能开启的时候，已有的提示词.md将自动更新（仅在配置关闭时重新
     * 开启配置才更新）」）：
     *
     * - [crawlerEnabled] = true 且文件已存在但缺少搜索引导行（关闭状态下
     *   生成的旧文件 / 老版本升级）→ 把「- 读网页与搜索：……」一行插入
     *   「技能」段（找不到段落时追加到文件末尾），其余内容原样保留；
     * - 已有引导 / 开关关闭 / 文件不存在 → 不动（幂等，开关一直是开时
     *   每次启动零写入）；
     * - 插入内容与 [defaultContent] 的搜索引导一字不差。
     */
    fun syncCrawlerGuidance(file: File?, crawlerEnabled: Boolean) {
        if (file == null || !crawlerEnabled) return
        try {
            if (!file.isFile) return
            val text = file.readText(StandardCharsets.UTF_8)
            if (CRAWLER_GUIDANCE_MARKER in text) return
            val updated = applyCrawlerGuidance(text)
            if (updated != text) {
                file.writeText(updated, StandardCharsets.UTF_8)
                PluginFileLog.write(
                    "[提示词] AI 网页读取已开启：已自动补上「读网页与搜索」技能引导" +
                        "（其余内容不变；不想要这行可自行删掉，下次开关重新开启时会再补上）"
                )
            }
        } catch (t: Throwable) {
            PluginFileLog.write("[提示词] 补写搜索引导失败（${t.message}）")
        }
    }

    /** 搜索引导行的识别标记（defaultContent 与补写内容共用）。 */
    const val CRAWLER_GUIDANCE_MARKER = "读网页与搜索"

    /**
     * 往提示词内容里插入搜索引导行（纯函数，冒烟直接验证）：
     * ① 已有引导 → 原样返回；②「- 发表情包」行后插入；③ 找不到表情行
     * 时「# 技能」标题行后插入；④ 连技能段都没有 → 文件末尾追加完整
     * 技能段（与默认模板同结构）。
     */
    fun applyCrawlerGuidance(content: String): String {
        if (CRAWLER_GUIDANCE_MARKER in content) return content
        val lines = content.lines().toMutableList()
        // ② 插在「发表情包」技能行之后
        val memeIndex = lines.indexOfFirst { it.trimStart().startsWith("- 发表情包") }
        if (memeIndex >= 0) {
            lines.add(memeIndex + 1, CRAWLER_GUIDANCE_LINE)
            return lines.joinToString("\n")
        }
        // ③ 插在「# 技能」标题行之后
        val skillIndex = lines.indexOfFirst { it.trimStart().startsWith("# 技能") }
        if (skillIndex >= 0) {
            lines.add(skillIndex + 1, CRAWLER_GUIDANCE_LINE)
            return lines.joinToString("\n")
        }
        // ④ 末尾追加完整技能段（与默认模板同结构）
        val builder = StringBuilder(content.trimEnd())
        if (builder.isNotEmpty()) builder.append("\n\n")
        builder.append("# 技能（你拥有的技能，系统自动注入，不用手动维护）\n\n")
        builder.append(CRAWLER_GUIDANCE_LINE).append('\n')
        return builder.toString()
    }

    /** 搜索引导行（[skillSection] 与 [applyCrawlerGuidance] 共用同源内容）。 */
    private const val CRAWLER_GUIDANCE_LINE =
        "- 读网页与搜索：遇到不会、没把握或时效性的问题（新闻、新版本、价格等），" +
            "先搜索再回答，不要凭记忆猜测，也不要直接说不知道；"

    /**
     * 插件更新替换时刷新技能段（1.7.6.4，用户口径「修复提示词.md在安装
     * 新插件时没有更新技能字段的问题（仅插件更新替换时更新）」）：
     *
     * - 版本记录（globalconfig/ 下的 [SKILL_VERSION_FILE]）存着上次同步
     *   技能段时的插件版本——与当前 [pluginVersion] 一致 = 同版本重启 /
     *   reload（jar 没换过），不是更新替换，直接跳过（零写入）；
     * - 记录缺失（老版本升级上来）或版本不同（jar 被替换 = 安装了新
     *   插件）→ 把文件里的「技能」段整段替换为当前内置技能段（按
     *   [crawlerEnabled] 生成，见 [applySkillSection]），角色设定与
     *   专属知识原样保留；内容实际有变化才写文件（如 1.7.6.3 生成的
     *   技能段与本版一致时零打扰，只补记录）；
     * - 全新安装（默认文件刚生成，技能段本就是最新）同样只记版本不写
     *   文件；开关关→开的补行走 [syncCrawlerGuidance]，与本机制互不
     *   干扰（版本刷新按当前开关生成，开着就自带搜索行）。
     */
    fun syncSkillSection(file: File?, crawlerEnabled: Boolean, pluginVersion: String) {
        if (file == null || pluginVersion.isBlank()) return
        try {
            val record = DataLayout.resolve(file.parentFile, SKILL_VERSION_FILE)
            if (record != null && record.isFile) {
                val last = record.readText(StandardCharsets.UTF_8).trim()
                if (last == pluginVersion) return
            }
            var changed = false
            if (file.isFile) {
                val text = file.readText(StandardCharsets.UTF_8)
                val updated = applySkillSection(text, crawlerEnabled)
                if (updated != text) {
                    file.writeText(updated, StandardCharsets.UTF_8)
                    changed = true
                }
            }
            if (record != null) record.writeText(pluginVersion, StandardCharsets.UTF_8)
            if (changed) {
                PluginFileLog.write(
                    "[提示词] 检测到插件更新（v$pluginVersion）：「技能」段已更新为本版内置内容" +
                        "（角色设定与专属知识原样保留；这段由系统维护，想自定义可手动改，" +
                        "下次插件更新时会再刷新）"
                )
            }
        } catch (t: Throwable) {
            PluginFileLog.write("[提示词] 技能段更新失败（${t.message}）")
        }
    }

    /** 技能段版本记录文件名（globalconfig/ 下，内容 = 上次同步技能段时的插件版本号）。 */
    const val SKILL_VERSION_FILE = ".prompt-skill-version"

    /**
     * 把提示词内容的「技能」段整段替换为当前内置技能段（纯函数，冒烟
     * 直接验证）：
     * ① 找到「# 技能」标题行 → 从该行到下一个一级标题（# 开头）之前
     *    整段替换（段间规整为一个空行）；
     * ② 没有技能段（1.7.6.2 及更早生成的文件）→ 末尾追加完整技能段
     *    （与 [applyCrawlerGuidance] 的追加分支同口径）；
     * ③ 角色设定 / 专属知识等其他内容原样保留；幂等（重复应用零变化）。
     */
    fun applySkillSection(content: String, crawlerEnabled: Boolean): String {
        val lines = content.lines()
        val start = lines.indexOfFirst { it.trimStart().startsWith("# 技能") }
        if (start < 0) {
            val base = content.trimEnd()
            return if (base.isEmpty()) skillSection(crawlerEnabled)
            else base + "\n\n" + skillSection(crawlerEnabled)
        }
        var end = lines.size
        for (i in start + 1 until lines.size) {
            val t = lines[i].trimStart()
            if (t.startsWith("# ") && !t.startsWith("# 技能")) {
                end = i
                break
            }
        }
        val result = lines.subList(0, start).toMutableList()
        // trimEnd('\n')：Kotlin lines() 对末尾换行会多产一个空元素（与
        // Java String.lines() 不同），技能段以 \n 收尾，不剥掉会多拼一个空行
        result.addAll(skillSection(crawlerEnabled).trimEnd('\n').lines())
        if (end < lines.size) {
            result.add("")
            result.addAll(lines.subList(end, lines.size))
        }
        val joined = result.joinToString("\n")
        return if (content.endsWith("\n") && !joined.endsWith("\n")) joined + "\n" else joined
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
     * @param crawlerEnabled 生成默认内容时是否写入搜索引导（读磁盘旧
     * 配置的 ai.crawler.enabled；旧版本无此键按关，与升级后新默认一致）
     * @return true = 已生成迁移文件；false = 无需迁移 / 文件已存在 / 失败
     */
    fun migrateFromSystemPrompt(file: File?, oldRole: String?, crawlerEnabled: Boolean): Boolean {
        if (file == null || oldRole.isNullOrBlank()) return false
        return try {
            if (file.isFile) return false
            file.parentFile?.mkdirs()
            file.writeText(defaultContent(oldRole, crawlerEnabled), StandardCharsets.UTF_8)
            true
        } catch (t: Throwable) {
            PluginFileLog.write("[提示词] 迁移旧角色设定失败（${t.message}），将生成默认文件")
            false
        }
    }

    /**
     * 清理旧知识库文件夹残留（1.7.6.3）：1.7.6.2 首发轮及更早版本会在
     * 插件目录自动创建 knowledge 文件夹（知识库检索方案，1.7.6.2 覆盖轮
     * 已下线），新版不再创建也不读取，但旧文件夹会留在磁盘上让用户
     * 误以为还在生成——启动时自动清理：
     * - 文件夹不存在 / 不是目录 → 无事发生；
     * - 只含旧版自动生成的说明文件（或为空）→ 整个文件夹删除；
     * - 有用户自己放的文件 → 保留不动，写日志提示把内容整理进提示词
     *   文件后手动删除。
     *
     * @return 清理结果（冒烟直接断言）
     */
    fun cleanupLegacyKnowledgeFolder(dir: File?): CleanupResult {
        if (dir == null || !dir.isDirectory) return CleanupResult.ABSENT
        return try {
            val files = dir.listFiles()?.toList() ?: emptyList()
            val legacyReadme = files.filter { it.name == LEGACY_KNOWLEDGE_README }
            val userFiles = files.filter { it.name != LEGACY_KNOWLEDGE_README }
            if (userFiles.isEmpty()) {
                // 空目录或只有旧说明文件：自动删除整个文件夹
                legacyReadme.forEach { it.delete() }
                val deleted = dir.delete()
                PluginFileLog.write(
                    "[提示词] 已清理废弃的知识库文件夹 ${dir.name}" +
                        "（知识库已并入提示词文件，不再使用文件夹）"
                )
                if (deleted) CleanupResult.REMOVED else CleanupResult.KEPT
            } else {
                PluginFileLog.write(
                    "[提示词] 检测到旧知识库文件夹 ${dir.name}（内有 ${userFiles.size} 个文件）；" +
                        "知识库文件夹方案已下线，这些文件不会被读取，" +
                        "可把有用内容整理进提示词文件后手动删除该文件夹"
                )
                CleanupResult.KEPT
            }
        } catch (t: Throwable) {
            PluginFileLog.write("[提示词] 清理旧知识库文件夹失败（${t.message}）")
            CleanupResult.KEPT
        }
    }

    /** 旧版知识库文件夹里自动生成的说明文件名（用于识别“无用户内容”）。 */
    const val LEGACY_KNOWLEDGE_README = "说明-把知识文件放进这里.txt"

    /** [cleanupLegacyKnowledgeFolder] 的清理结果。 */
    enum class CleanupResult {
        /** 文件夹不存在，无需清理。 */
        ABSENT,

        /** 空文件夹 / 只有旧说明文件，已自动删除。 */
        REMOVED,

        /** 有用户文件（保留不动）或删除失败。 */
        KEPT,
    }
}

package cn.kerong.penguin.spigot.manager

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 配置文件版本重建器（1.6.7.2 新增，修复「配置版本号更新后配置并没有更新」）。
 *
 * 旧机制的问题：升级插件后磁盘 config.yml 只会经 fillMissing 追加裸键
 * （无注释、追加在段尾），JAR 内新版 config.yml 的注释、段落结构、默认
 * 值调整永远不会到达用户文件；NeoForge 端兼容层的 YAML 保存还会丢掉
 * 全部注释——用户看到的配置文件长期停留在旧版样貌。
 *
 * 新机制（[rebuildIfNeeded]）：磁盘 config-version 低于 JAR 内置版本时，
 * 以 JAR 的新版 config.yml 全文（注释完整）为底重建用户文件：
 * 1. 旧文件先备份为 config.yml.bak（每次升级覆盖一份，不留堆积）；
 * 2. 用户自定义值逐项回填：旧文件中与「JAR 新默认值」不同的项才回填
 *   （仍等于旧默认的项直接采用新默认——默认值调整得以生效），旧文件
 *   特有的自定义键（如 markdown.* 自定义模板、自定义 commands.* 开关）
 *   原样保留；
 * 3. 历史路径搬家与废弃键清理在回填前先行套用（等价旧版迁移逻辑）；
 * 4. 回填走 [YamlTextMerge] 文本级合并——逐行替换值、保留全部注释与
 *   段落结构，不经 YAML 序列化重写，双端行为一致；
 * 5. 合并结果先试解析校验（解析失败 / 版本号不对则放弃重建走旧流程，
 *   用户旧文件不受影响），再原子替换落盘。
 *
 * 1.6.7.3：[YamlTextMerge.renderEntry] 回填格式对齐模板书写风格（列表与
 * 字符串统一行内双引号），修复升级重建后用户手写的 groups: ["xxx"] 等
 * 行内格式被改写成块状裸串、看着像值丢失的问题（配置版本 21 → 22，
 * 装过 1.6.7.2 覆盖版的用户重启即再次重建拿新格式）。
 *
 * 触发时机：ConfigManager.initialize() 在 saveDefaultConfig 之前调用；
 * 全新安装（无 config.yml）不触发，由 saveDefaultConfig 原样释放。
 */
internal object ConfigFileRebuilder {

    /** 1.6.7.2 调整的默认值：旧默认 = 待升级项（等于旧默认才替换为新默认）。 */
    private const val OLD_FROM_GROUP_DEFAULT = "[QQ] {name}: {message}"
    private const val NEW_FROM_GROUP_DEFAULT = "[§bQQ§r] {name}: {message}"

    private const val OLD_IDLE_RELEASE_MINUTES = 5.0
    private const val NEW_IDLE_RELEASE_MINUTES = 15.0

    /** 1.6.7.2 覆盖版：性能数据缓存默认 5 → 60。 */
    private const val OLD_PERFORMANCE_CACHE_SECONDS = 5

    /**
     * 磁盘版本低于 [currentVersion] 时以 JAR 新版 config.yml 重建用户配置。
     *
     * @param legacyPathMoves 历史路径搬家表（旧路径 → 新路径，来自 ConfigManager）
     * @param removedKeys 历史废弃键清单（回填时跳过，来自 ConfigManager）
     * @return true = 已重建落盘（调用方后续 reload 即见新文件）
     */
    fun rebuildIfNeeded(
        plugin: JavaPlugin,
        currentVersion: Int,
        legacyPathMoves: List<Pair<String, String>>,
        removedKeys: List<String>,
    ): Boolean {
        val file = File(plugin.dataFolder, "config.yml")
        if (!file.isFile) return false
        val jarStream = try {
            plugin.getResource("config.yml")
        } catch (_: Throwable) {
            null
        } ?: return false
        val jarText = try {
            jarStream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        } catch (_: Throwable) {
            return false
        }

        // 磁盘树（无 defaults，纯用户文件内容；格式坏 → 放弃重建走旧流程）
        val disk = YamlConfiguration()
        try {
            disk.load(file)
        } catch (_: Throwable) {
            return false
        }
        val diskVersion = disk.getInt("config-version", 0)
        if (diskVersion >= currentVersion) return false

        // JAR 版本核对（与传入 currentVersion 不一致说明资源与代码不同步，保守放弃）
        val jar = try {
            loadYaml(jarText)
        } catch (_: Throwable) {
            null
        } ?: return false
        val jarVersion = jar.getInt("config-version", 0)
        if (jarVersion != currentVersion) {
            plugin.logger.warning("配置重建跳过：JAR 内置 config.yml 版本($jarVersion)与代码版本($currentVersion)不一致")
            return false
        }

        // 1. 历史路径搬家 + 废弃键清理（等价旧迁移逻辑，防止旧路径键被当自定义键回填）
        for ((old, new) in legacyPathMoves) {
            if (disk.isSet(old)) {
                disk.set(new, disk.get(old))
                disk.set(old, null)
            }
        }
        for (removed in removedKeys) {
            if (disk.contains(removed)) disk.set(removed, null)
        }

        // 2. 默认值升级收敛：仍等于旧默认的项移除，让 JAR 新默认生效
        //   （自定义过的值不动，与 1.6.5 from-game 默认升级同一策略）
        if (OLD_FROM_GROUP_DEFAULT == disk.getString("chat-format.from-group")) {
            disk.set("chat-format.from-group", null)
        }
        if (disk.isSet("render.idle-release-minutes") &&
            disk.getDouble("render.idle-release-minutes", -1.0) == OLD_IDLE_RELEASE_MINUTES
        ) {
            disk.set("render.idle-release-minutes", null)
        }
        // 1.6.7.2 覆盖版：性能数据缓存默认 5 → 60（仍是旧默认 5 的配置删除后
        // 由 JAR 新默认生效，用户自定义值不动）
        if (disk.isSet("render.performance.cache-seconds") &&
            disk.getInt("render.performance.cache-seconds", -1) == OLD_PERFORMANCE_CACHE_SECONDS
        ) {
            disk.set("render.performance.cache-seconds", null)
        }

        // 3. 收集用户自定义值：与 JAR 新默认不同的项 + JAR 不存在的自定义键
        val diskLeaves = LinkedHashMap<String, Any?>()
        collectLeaves(disk, "", diskLeaves)
        diskLeaves.remove("config-version")
        val carry = LinkedHashMap<String, Any?>()
        for ((path, value) in diskLeaves) {
            if (value == null) continue
            val jarValue = jarLeaf(jar, path)
            if (jarValue == null || value != jarValue) carry[path] = value
        }

        // 4. 文本级合并（保留 JAR 注释与结构）
        val merged = try {
            YamlTextMerge.merge(jarText, carry)
        } catch (t: Throwable) {
            plugin.logger.warning("配置重建放弃：文本合并异常(${t.message})")
            return false
        }

        // 5. 合并结果先校验（解析失败 / 版本号不对 → 不落盘，旧文件不受影响）
        val verify = try {
            loadYaml(merged)
        } catch (_: Throwable) {
            null
        }
        if (verify == null || verify.getInt("config-version", 0) != currentVersion) {
            plugin.logger.warning("配置重建放弃：合并结果校验未通过")
            return false
        }

        // 6. 备份旧文件 + 原子写入
        try {
            file.copyTo(File(file.parentFile, "config.yml.bak"), overwrite = true)
            val tmp = File(file.parentFile, "config.yml.tmp")
            Files.write(tmp.toPath(), merged.toByteArray(StandardCharsets.UTF_8))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            plugin.logger.warning("配置重建写盘失败：${t.message}")
            return false
        }
        plugin.logger.info(
            "配置文件已从 v$diskVersion 重建为 v$currentVersion（保留全部注释与自定义设置，旧文件备份为 config.yml.bak）"
        )
        if (NEW_FROM_GROUP_DEFAULT == verify.getString("chat-format.from-group")) {
            plugin.logger.info("聊天转发：群→游戏默认格式已升级为 $NEW_FROM_GROUP_DEFAULT（新默认，可自行修改）")
        }
        if (verify.getDouble("render.idle-release-minutes", -1.0) == NEW_IDLE_RELEASE_MINUTES &&
            disk.getDouble("render.idle-release-minutes", -1.0) == OLD_IDLE_RELEASE_MINUTES
        ) {
            plugin.logger.info("渲染空闲内存释放：默认阈值已由 5 分钟调整为 15 分钟（新默认，可在 render.idle-release-minutes 改回）")
        }
        return true
    }

    // ---------- 内部工具 ----------

    /**
     * 解析 YAML 文本（解析失败抛异常，由调用方捕获判定；双端同名 API）。
     *
     * 1.6.7.2 覆盖版：改用 load(Reader) 实例方法（解析失败抛出）——
     * 旧写法 loadConfiguration(Reader) 是“吞异常打 ERROR 返回空配置”的
     * 容错 API，解析失败永远走不到调用方的 catch，只能靠版本号不一致
     * 兼底，且会在控制台刷一大段吓人的异常堆栈。
     */
    private fun loadYaml(text: String): YamlConfiguration {
        val reader = java.io.InputStreamReader(ByteArrayInputStream(text.toByteArray(StandardCharsets.UTF_8)), StandardCharsets.UTF_8)
        val config = YamlConfiguration()
        reader.use { config.load(it) }
        return config
    }

    /** 递归收集叶子键值（路径 → 值；列表与标量均按叶子处理）。 */
    private fun collectLeaves(section: ConfigurationSection?, prefix: String, out: LinkedHashMap<String, Any?>) {
        if (section == null) return
        for (key in section.getKeys(false)) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            val child = section.getConfigurationSection(key)
            if (child != null) {
                collectLeaves(child, path, out)
            } else {
                out[path] = section.get(key)
            }
        }
    }

    /** 读 JAR 树的叶子值（路径不存在返回 null；仅用于等值比较）。 */
    private fun jarLeaf(jar: YamlConfiguration, path: String): Any? {
        return try {
            if (jar.isSet(path)) jar.get(path) else null
        } catch (_: Throwable) {
            null
        }
    }
}

/**
 * YAML 文本合并器（1.6.7.2 新增）：以带注释的模板文本为底，逐行替换 /
 * 插入指定键值，不经 YAML 序列化重写——全部注释与段落结构原样保留。
 *
 * 仅支持本插件 config.yml 的书写风格（块缩进、每行一个 key: value、
 * 列表为行内 [] 或块状 - 项），不追求通用 YAML 编辑能力。
 */
internal object YamlTextMerge {

    /** 一次文本编辑：删除 [atLine] 起的 [removeLines] 行，插入 [insert]。 */
    private data class Edit(val atLine: Int, val removeLines: Int, val insert: List<String>)

    /** 行类别：键行（缩进 / 键名 / 是否行内值）、列表项行、其他（注释 / 空行）。 */
    private sealed interface LineKind

    private data class KeyLine(val indent: Int, val key: String, val hasInlineValue: Boolean) : LineKind
    private data class ItemLine(val indent: Int) : LineKind
    private data class OtherLine(val text: String) : LineKind

    private val dumperOptions = org.yaml.snakeyaml.DumperOptions().apply {
        defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.BLOCK
        isAllowUnicode = true
        width = Int.MAX_VALUE
    }

    /** 行内 flow 风格（1.6.7.3：复杂列表项如 custom-commands 的 Map 单行渲染）。 */
    private val flowDumperOptions = org.yaml.snakeyaml.DumperOptions().apply {
        defaultFlowStyle = org.yaml.snakeyaml.DumperOptions.FlowStyle.FLOW
        isAllowUnicode = true
        width = Int.MAX_VALUE
    }

    /** 值 → YAML 文本（标量单行；空列表 []；非空列表多行块）。 */
    private fun dump(value: Any?): String {
        return org.yaml.snakeyaml.Yaml(dumperOptions).dump(value)?.trim() ?: "null"
    }

    /**
     * 合并入口。
     *
     * @param templateText JAR 内新版 config.yml 全文（注释完整）
     * @param overrides 要回填的键值（路径 → 值）
     */
    fun merge(templateText: String, overrides: Map<String, Any?>): String {
        if (overrides.isEmpty()) return templateText
        val text = if (templateText.startsWith("﻿")) templateText.substring(1) else templateText
        val lines = text.split('\n').toMutableList()

        // 第一遍：逐行归类并建立 路径 → 行号 索引（缩进栈推导层级）
        val pathIndex = HashMap<String, Int>()
        val kinds = ArrayList<LineKind>(lines.size)
        val stack = ArrayDeque<Pair<Int, String>>() // (indent, key)
        for ((index, raw) in lines.withIndex()) {
            val kind = classify(raw)
            kinds.add(kind)
            if (kind is KeyLine) {
                while (stack.isNotEmpty() && stack.last().first >= kind.indent) stack.removeLast()
                val path = if (stack.isEmpty()) kind.key else stack.joinToString(".") { it.second } + "." + kind.key
                pathIndex[path] = index
                stack.addLast(kind.indent to kind.key)
            }
        }

        // 第二遍：生成编辑（替换已有键 + 插入模板没有的自定义键）
        val edits = ArrayList<Edit>()
        val remaining = LinkedHashMap<String, Any?>(overrides)

        // 2a. 替换已有键：行内值 → 整行重写；块状键 → 连同其列表项整块重写
        val replaceIterator = remaining.entries.iterator()
        while (replaceIterator.hasNext()) {
            val (path, value) = replaceIterator.next()
            val line = pathIndex[path] ?: continue
            val keyLine = kinds[line] as KeyLine
            val rendered = renderEntry(" ".repeat(keyLine.indent), keyLine.key, value)
            if (keyLine.hasInlineValue) {
                edits.add(Edit(line, 1, rendered))
            } else {
                val end = blockEndLine(kinds, line, keyLine.indent)
                edits.add(Edit(line, end - line, rendered))
            }
            replaceIterator.remove()
        }

        // 2b. 插入模板中不存在的自定义键（挂到最深已存在祖先段末尾）
        for ((path, value) in remaining) {
            findInsertion(lines, kinds, pathIndex, path, value)?.let { edits.add(it) }
        }

        // 第三遍：按行号倒序应用（插入点取段尾行 +1，避免与同行替换冲突）
        edits.sortedByDescending { it.atLine }.forEach { edit ->
            for (i in 0 until edit.removeLines) lines.removeAt(edit.atLine)
            lines.addAll(edit.atLine, edit.insert)
        }
        return lines.joinToString("\n")
    }

    // ---------- 行解析 ----------

    private val keyRegex = Regex("^(\\s*)([^:\\s#][^:]*?)\\s*:(?:\\s(.*))?$")
    private val itemRegex = Regex("^(\\s*)-\\s")

    private fun classify(raw: String): LineKind {
        if (raw.isBlank()) return OtherLine(raw)
        if (raw.trimStart().startsWith("#")) return OtherLine(raw)
        itemRegex.matchAt(raw, 0)?.let { return ItemLine(it.groupValues[1].length) }
        keyRegex.matchEntire(raw)?.let { match ->
            val value = match.groupValues[3]
            return KeyLine(match.groupValues[1].length, match.groupValues[2], value.isNotBlank())
        }
        return OtherLine(raw)
    }

    // ---------- 渲染 ----------

    /**
     * 键值 → 行（标量一行；列表一律行内 flow 单行，与模板书写风格一致）。
     *
     * 1.6.7.2 覆盖版修复：列表按类型分派，不再依赖 dump 结果是否含换行判
     * 分支——单元素列表 dump 出的是单行“- item”（不含换行），旧判式把它
     * 误当标量拼成“key: - item”非法 YAML，单群用户的 bot.groups 回填
     * 恰好踩中（合并结果解析失败 → 配置重建放弃）。
     *
     * 1.6.7.3 再修（用户反馈「配置更新后格式丢失」）：旧版回填列表统一
     * 输出块状无引号项行（groups 与缩进项分两行写），用户按教程写的
     * 行内带引号格式 `groups: ["xxx"]` 每次升级就被改写成块状裸串，
     * 看着像 groups 值丢失、只剩 OpenID 字符串值保留原样。现改为：
     * - 空列表 → `key: []`；
     * - 纯标量列表 → 行内 `key: ["A", "B"]`（字符串统一双引号转义，
     *   数字 / 布尔原样）——与 config.yml 顶部注释及各段示例
     *   `groups: ["ABC123456XYZ", "DEF789XYZ"]` 完全同风；
     * - 含 Map 等复杂项（custom-commands 形态）→ snakeyaml flow dump
     *   单行 `key: [{key: ..., command: ...}]`；
     * - 字符串标量 → 统一双引号 `key: "value"`（与教程「建议用引号包裹」
     *   一致；数字 / 布尔原样，避免 5 → "5" 语义漂移）。
     *
     * 行内单行对模板两种形态都正确：行内键（hasInlineValue）1 行换 1 行；
     * 块状键整块删换 1 行（Edit 的 removeLines 已覆盖块体项行）。
     */
    private fun renderEntry(indentStr: String, key: String, value: Any?): List<String> {
        if (value is Collection<*>) {
            if (value.isEmpty()) return listOf("$indentStr$key: []")
            if (value.all { it == null || it is String || it is Number || it is Boolean }) {
                val items = value.joinToString(", ") { renderFlowScalar(it) }
                return listOf("$indentStr$key: [$items]")
            }
            // 含 Map 等复杂项（custom-commands 形态）：flow dump 单行
            val flow = org.yaml.snakeyaml.Yaml(flowDumperOptions).dump(value)?.trim() ?: "[]"
            return listOf("$indentStr$key: $flow")
        }
        when (value) {
            is String -> return listOf("$indentStr$key: ${doubleQuote(value)}")
            null -> return listOf("$indentStr$key: null")
            is Number, is Boolean -> return listOf("$indentStr$key: $value")
        }
        val dumped = dump(value)
        if (!dumped.contains('\n')) {
            return listOf("$indentStr$key: $dumped")
        }
        // 罕见兜底（本插件配置不产生的形态）：多行块值按行加缩进续写
        return listOf("$indentStr$key:") + dumped.split('\n').map { "$indentStr$it" }
    }

    /** 行内 flow 列表项：字符串双引号转义，数字 / 布尔原样，null 字面量。 */
    private fun renderFlowScalar(item: Any?): String = when (item) {
        is String -> doubleQuote(item)
        null -> "null"
        else -> item.toString()
    }

    /**
     * 字符串 → 双引号 YAML 字面量（与教程「每一项都必须加双引号」同风）。
     * 转义双引号 / 反斜杠 / 换行 / 制表 / 回车；其余字符（含中文与 § 码）
     * 原样输出（isAllowUnicode，无需转义）。
     */
    private fun doubleQuote(s: String): String {
        val sb = StringBuilder(s.length + 2).append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                '\r' -> sb.append("\\r")
                else -> sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    /** 块状键的块尾行号（键行后连续的列表项 / 更深子键行）。 */
    private fun blockEndLine(kinds: List<LineKind>, keyLine: Int, keyIndent: Int): Int {
        var end = keyLine + 1
        while (end < kinds.size) {
            when (val kind = kinds[end]) {
                is ItemLine -> if (kind.indent >= keyIndent) end++ else return end
                is KeyLine -> if (kind.indent > keyIndent) end++ else return end
                is OtherLine -> return end
            }
        }
        return end
    }

    /** 为模板中不存在的路径寻找插入点（最深祖先段末尾；无祖先则文件尾）。 */
    private fun findInsertion(
        lines: List<String>,
        kinds: List<LineKind>,
        pathIndex: Map<String, Int>,
        path: String,
        value: Any?,
    ): Edit {
        val segments = path.split('.')

        // 最深已存在祖先（模板里能找到的最长前缀）
        var ancestorLine = -1
        var matchedDepth = 0
        var prefix = ""
        for ((index, segment) in segments.withIndex()) {
            val candidate = if (index == 0) segment else "$prefix.$segment"
            if (pathIndex.containsKey(candidate)) {
                ancestorLine = pathIndex[candidate] ?: continue
                matchedDepth = index + 1
                prefix = candidate
            } else {
                break
            }
        }

        // 无任何祖先：文件尾补缺失父段头 + 键行
        if (ancestorLine < 0) {
            val headers = segments.dropLast(1).mapIndexed { depth, seg -> "${" ".repeat(depth * 2)}$seg:" }
            val rendered = renderEntry(" ".repeat((segments.size - 1) * 2), segments.last(), value)
            return Edit(lines.size, 0, headers + rendered)
        }

        val ancestorIndent = (kinds[ancestorLine] as KeyLine).indent
        val insertAfter = sectionEndLine(kinds, ancestorLine, ancestorIndent)

        // 祖先之下若还缺中间段头（罕见），逐层补上
        val missingHeaders = ArrayList<String>()
        for (depth in matchedDepth until segments.size - 1) {
            val indent = ancestorIndent + 2 * (depth - matchedDepth + 1)
            missingHeaders.add("${" ".repeat(indent)}${segments[depth]}:")
        }
        val keyIndent = ancestorIndent + 2 * (segments.size - matchedDepth)
        val rendered = renderEntry(" ".repeat(keyIndent), segments.last(), value)
        return Edit(insertAfter + 1, 0, missingHeaders + rendered)
    }

    /** 段落块结束行（最后一个归属行：子键更深缩进 / 列表项不浅于段头；注释与空行不算）。 */
    private fun sectionEndLine(kinds: List<LineKind>, headerLine: Int, headerIndent: Int): Int {
        var last = headerLine
        var i = headerLine + 1
        while (i < kinds.size) {
            when (val kind = kinds[i]) {
                is KeyLine -> {
                    if (kind.indent <= headerIndent) return last
                    last = i
                }
                is ItemLine -> {
                    if (kind.indent < headerIndent) return last
                    last = i
                }
                is OtherLine -> {
                    // 注释 / 空行不更新 last（可能是下一段的前导注释）
                }
            }
            i++
        }
        return last
    }
}

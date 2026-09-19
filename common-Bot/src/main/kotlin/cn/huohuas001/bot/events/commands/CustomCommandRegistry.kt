package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.datapack.ResolvedCommand
import cn.huohuas001.bot.provider.CustomCommandDetail
import java.util.concurrent.ConcurrentHashMap

/**
 * 自定义命令注册表：维护 QQ 群快捷命令 → 服务器命令模板的映射。
 *
 * 命令串格式：`huhobot run|adminrun <群Id> <用户Id> <key> [params]`
 * 模板占位符：{params} {group} {user} {0} {&1} ...（按空白拆分 params 后的位置参数）
 */
object CustomCommandRegistry {
    private val commands = ConcurrentHashMap<String, CustomCommandDetail>()

    /** 用配置整体替换命令表。 */
    fun replace(values: Collection<CustomCommandDetail>) {
        commands.clear()
        values
            .filter { it.key.isNotBlank() && it.command.isNotBlank() }
            .forEach { commands[it.key] = it }
    }

    /**
     * 解析原始命令串。
     * @return 解析成功时携带替换后的服务器命令；失败时携带错误原因
     */
    fun resolve(raw: String): ResolvedCommand {
        val parts = Regex("\\s+").split(raw.trim(), 6)
        if (parts.size < 2 || parts[0] != "huhobot" || parts[1] !in setOf("run", "adminrun")) {
            // 非自定义命令协议，原样透传
            return ResolvedCommand(command = raw)
        }
        if (parts.size < 5) {
            return ResolvedCommand(error = "自定义命令参数不正确")
        }
        val isAdmin = parts[1] == "adminrun"
        val groupId = parts[2]
        val userId = parts[3]
        val invocation = parts[4] + (if (parts.size == 6) " " + parts[5] else "")

        val invocationParts = Regex("\\s+").split(invocation, 2)
        val key = invocationParts[0]
        val params = invocationParts.getOrElse(1) { "" }

        val detail = commands[key] ?: return ResolvedCommand(error = "未找到自定义命令：$key")
        if (detail.permission > 0 && !isAdmin) {
            return ResolvedCommand(error = "此自定义命令仅管理员可执行")
        }

        var command = detail.command
            .replace("{params}", params)
            .replace("{group}", groupId)
            .replace("{user}", userId)
        // {0} {1}... 与 {&1} {&2}... 形式的位置参数
        val arguments = params.split(Regex("\\s+")).filter { it.isNotEmpty() }
        arguments.forEachIndexed { index, value ->
            command = command.replace("{$index}", value).replace("{&${index + 1}}", value)
        }
        return ResolvedCommand(command = command)
    }
}

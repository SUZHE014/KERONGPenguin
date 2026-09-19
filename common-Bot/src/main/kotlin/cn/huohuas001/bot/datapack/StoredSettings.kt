package cn.huohuas001.bot.datapack

/**
 * 命令解析结果：成功时携带命令文本，失败时携带错误原因。
 */
data class ResolvedCommand(
    val command: String? = null,
    val error: String? = null,
)

/**
 * 持久化的群命令设置快照。
 */
data class StoredCommandSettings(
    val administrators: Map<String, Set<String>> = emptyMap(),
    val authenticatedUsers: Map<String, Set<String>> = emptyMap(),
    val administratorModes: Map<String, AdministratorAccessMode> = emptyMap(),
    val fullForwarding: Map<String, Boolean> = emptyMap(),
)

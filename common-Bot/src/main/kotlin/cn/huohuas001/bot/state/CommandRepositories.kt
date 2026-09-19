package cn.huohuas001.bot.state

import cn.huohuas001.bot.datapack.AdministratorAccessMode
import cn.huohuas001.bot.datapack.StoredCommandSettings
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 群命令运行状态的内存仓储集合，统一从 [HumanReadableStateFile] 加载/保存。
 */
object CommandRepositories {
    private val stateFile = HumanReadableStateFile()

    /** 各群的管理员名单。 */
    val administrators = AdministratorRepository { save() }

    /** 各群已通过身份验证的用户。 */
    val authentication = AuthenticationRepository { save() }

    /** 各群的设置（管理员判定模式 / 全量转发）。 */
    val groupSettings = GroupSettingsRepository { save() }

    /** 从数据目录加载状态（服务器启动时调用一次）。 */
    @Synchronized
    fun initialize(dataDirectory: File?) {
        val snapshot: StoredCommandSettings = stateFile.initialize(dataDirectory)
        administrators.replaceAll(snapshot.administrators)
        authentication.replaceAll(snapshot.authenticatedUsers)
        groupSettings.replaceAll(snapshot.administratorModes, snapshot.fullForwarding)
    }

    @Synchronized
    private fun save() {
        stateFile.save(
            StoredCommandSettings(
                administrators.snapshot(),
                authentication.snapshot(),
                groupSettings.administratorModeSnapshot(),
                groupSettings.fullForwardingSnapshot(),
            )
        )
    }
}

/**
 * 某一群的管理员名单仓储。
 */
class AdministratorRepository(private val persist: () -> Unit) {
    private val usersByGroup = ConcurrentHashMap<String, MutableSet<String>>()

    /** 判断 [userId] 是否为 [groupId] 群的管理员。 */
    fun contains(groupId: String, userId: String): Boolean =
        usersByGroup[groupId]?.contains(userId) == true

    /** 添加管理员（已存在返回 false）。 */
    fun add(groupId: String, userId: String): Boolean {
        val users = usersByGroup.computeIfAbsent(groupId) { ConcurrentHashMap.newKeySet() }
        val added = users.add(userId)
        if (added) persist()
        return added
    }

    /** 移除管理员（不存在返回 false）。 */
    fun remove(groupId: String, userId: String): Boolean {
        val users = usersByGroup[groupId] ?: return false
        val removed = users.remove(userId)
        if (removed) persist()
        return removed
    }

    /** 用持久化数据整体替换（加载时调用）。 */
    fun replaceAll(values: Map<String, Set<String>>) {
        usersByGroup.clear()
        for ((group, users) in values) {
            usersByGroup[group] = ConcurrentHashMap.newKeySet<String>().apply { addAll(users) }
        }
    }

    /** 导出快照（保存时调用）。 */
    fun snapshot(): Map<String, Set<String>> = usersByGroup.mapValues { it.value.toSet() }
}

/**
 * 某一群的已验证用户仓储（管理员执行命令前的二次认证）。
 */
class AuthenticationRepository(private val persist: () -> Unit) {
    private val usersByGroup = ConcurrentHashMap<String, MutableSet<String>>()

    /** 判断 [userId] 是否已在 [groupId] 群通过验证。 */
    fun contains(groupId: String, userId: String): Boolean =
        usersByGroup[groupId]?.contains(userId) == true

    /** 标记通过验证（已验证返回 false）。 */
    fun authenticate(groupId: String, userId: String): Boolean {
        val users = usersByGroup.computeIfAbsent(groupId) { ConcurrentHashMap.newKeySet() }
        val added = users.add(userId)
        if (added) persist()
        return added
    }

    /** 撤销验证（未验证返回 false）。 */
    fun revoke(groupId: String, userId: String): Boolean {
        val users = usersByGroup[groupId] ?: return false
        val removed = users.remove(userId)
        if (removed) persist()
        return removed
    }

    /** 用持久化数据整体替换（加载时调用）。 */
    fun replaceAll(values: Map<String, Set<String>>) {
        usersByGroup.clear()
        for ((group, users) in values) {
            usersByGroup[group] = ConcurrentHashMap.newKeySet<String>().apply { addAll(users) }
        }
    }

    /** 导出快照（保存时调用）。 */
    fun snapshot(): Map<String, Set<String>> = usersByGroup.mapValues { it.value.toSet() }
}

/**
 * 各群设置仓储：管理员判定模式与全量转发开关。
 */
class GroupSettingsRepository(private val persist: () -> Unit) {
    private val administratorModes = ConcurrentHashMap<String, AdministratorAccessMode>()
    private val fullForwarding = ConcurrentHashMap<String, Boolean>()

    /** 获取 [groupId] 群的管理员判定模式（未配置时返回 [defaultMode]）。 */
    fun administratorMode(groupId: String, defaultMode: AdministratorAccessMode): AdministratorAccessMode =
        administratorModes[groupId] ?: defaultMode

    /** 设置 [groupId] 群的管理员判定模式。 */
    fun setAdministratorMode(groupId: String, mode: AdministratorAccessMode) {
        administratorModes[groupId] = mode
        persist()
    }

    /** 获取 [groupId] 群是否全量转发（未配置时返回 [defaultValue]）。 */
    fun fullForwarding(groupId: String, defaultValue: Boolean): Boolean =
        fullForwarding[groupId] ?: defaultValue

    /** 设置 [groupId] 群的全量转发开关。 */
    fun setFullForwarding(groupId: String, enabled: Boolean) {
        fullForwarding[groupId] = enabled
        persist()
    }

    /** 用持久化数据整体替换（加载时调用）。 */
    fun replaceAll(modes: Map<String, AdministratorAccessMode>, forwarding: Map<String, Boolean>) {
        administratorModes.clear()
        administratorModes.putAll(modes)
        fullForwarding.clear()
        fullForwarding.putAll(forwarding)
    }

    /** 导出管理员判定模式快照。 */
    fun administratorModeSnapshot(): Map<String, AdministratorAccessMode> = administratorModes.toMap()

    /** 导出全量转发快照。 */
    fun fullForwardingSnapshot(): Map<String, Boolean> = fullForwarding.toMap()
}

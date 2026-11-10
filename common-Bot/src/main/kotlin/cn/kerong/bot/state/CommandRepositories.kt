package cn.kerong.bot.state

import cn.kerong.bot.datapack.AdministratorAccessMode
import cn.kerong.bot.datapack.StoredCommandSettings
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

    /** 各群的设置（管理员判定模式）。 */
    val groupSettings = GroupSettingsRepository { save() }

    /** 从数据目录加载状态（服务器启动时调用一次）。 */
    @Synchronized
    fun initialize(dataDirectory: File?) {
        val snapshot: StoredCommandSettings = stateFile.initialize(dataDirectory)
        administrators.replaceAll(snapshot.administrators)
        authentication.replaceAll(snapshot.authenticatedUsers)
        groupSettings.replaceAll(snapshot.administratorModes)
    }

    @Synchronized
    private fun save() {
        stateFile.save(
            StoredCommandSettings(
                administrators.snapshot(),
                authentication.snapshot(),
                groupSettings.administratorModeSnapshot(),
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
 * 各群设置仓储：管理员判定模式。
 *
 * 1.7.5 覆盖第三轮：群级全量转发开关（full-forwarding）已删除——全量
 * 转发只按总配置 features.full-amount 生效（/全量 命令切换总配置），
 * 旧状态文件里的 [full-forwarding] 段不再读取，随下次保存自然消失。
 */
class GroupSettingsRepository(private val persist: () -> Unit) {
    private val administratorModes = ConcurrentHashMap<String, AdministratorAccessMode>()

    /** 获取 [groupId] 群的管理员判定模式（未配置时返回 [defaultMode]）。 */
    fun administratorMode(groupId: String, defaultMode: AdministratorAccessMode): AdministratorAccessMode =
        administratorModes[groupId] ?: defaultMode

    /** 用持久化数据整体替换（加载时调用）。 */
    fun replaceAll(modes: Map<String, AdministratorAccessMode>) {
        administratorModes.clear()
        administratorModes.putAll(modes)
    }

    /** 导出管理员判定模式快照。 */
    fun administratorModeSnapshot(): Map<String, AdministratorAccessMode> = administratorModes.toMap()
}

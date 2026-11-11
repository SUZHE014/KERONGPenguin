package cn.kerong.bot.events.commands

import cn.kerong.bot.KERONG
import cn.kerong.penguin.spigot.qqbind.MessageTemplates
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import java.lang.reflect.Method
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * QQ 群命令方法注解。
 * 标注了本注解的 [BaseCommand] 子类方法会自动注册为群命令。
 *
 * 支持 0~3 个参数，依次匹配以下形态：
 * - `(api: KERONG, event: GroupMessageEvent, params: String)`
 * - `(event: GroupMessageEvent, params: String)`
 * - `(event: GroupMessageEvent)`
 * - `()`
 *
 * ## adminOnly（1.7.5.2 覆盖：按钮管理员判定的单一事实来源）
 *
 * 管理员命令在这里声明一次，按钮层（[cn.kerong.bot.events.MenuButtons]）
 * 与 QQ 快捷面板层（[cn.kerong.bot.MenuManager]）自动跟随——不再需要
 * 平台白名单或手动配置满单。命令方法体内仍需自行 requireAdmin（本标记
 * 只用于「这个命令是管理类」的展示 / 按钮门禁决策，不改变命令执行较验）。
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Commands(vararg val value: String, val adminOnly: Boolean = false)

/**
 * 管理员命令注册表（1.7.5.2 覆盖）。
 *
 * 数据源是 [Commands.adminOnly] 注解标记：任何 [BaseCommand] 子类实例化
 * 时自动登记（[BaseCommand.init] 扫描）。为保证菜单 / 面板在 QQ 客户端
 * 启动前也能查询，[ensurePopulated] 会惰性实例化内置命令类补齐注册
 * （命令类构造无副作用，重复实例化仅重复登记，幂等）。
 *
 * 使用方：
 * - [cn.kerong.bot.events.MenuButtons]：管理类按钮在 admin.mode=qq/both
 *   下带平台权限门禁下发（permission.type=1 + krg:acmd: 回调前缀）；
 * - [cn.kerong.bot.MenuManager]：1.7.5.3 面板联动——管理类指令的快捷
 *   面板条目设 only_admin=true（独立配置默认开）。
 */
object CommandAdminRegistry {

    /** 管理员命令名集合（含别名；与 @Commands 的 value 全量登记）。 */
    private val adminCommands: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** 已完成内置命令类扫描标记。 */
    @Volatile
    private var populated = false

    /** 登记一批管理命令名（BaseCommand 扫描时调用，幂等）。 */
    internal fun register(names: Collection<String>) {
        if (names.isNotEmpty()) adminCommands.addAll(names)
    }

    /** 确保内置命令类已扫描（惰性实例化触发 BaseCommand.init 登记）。 */
    @Synchronized
    fun ensurePopulated() {
        if (populated) return
        try {
            listOf(
                PublicCommands(),
                AdministrationCommands(),
                BindCommands(),
                CheckInCommands(),
                LeaderboardCommands(),
                CoinsLeaderboardCommands(),
                AuthenticationCommands(),
            ).forEach { _ -> }
        } catch (_: Throwable) {
            // 扫描失败不影响主流程：查询方按非管理命令处理
        }
        populated = true
    }

    /** 命令是否为管理类（未登记按非管理命令处理）。 */
    fun isAdminCommand(name: String): Boolean {
        ensurePopulated()
        return name in adminCommands
    }
}

/**
 * 群命令分发基类：扫描自身（含父类）带 [Commands] 注解的方法并按消息内容分发。
 */
abstract class BaseCommand {
    private val commandMap: MutableMap<String, Method> = LinkedHashMap()

    init {
        collectCommandMethods()
    }

    /** 扫描本类与父类的公开方法，收集带 [Commands] 注解的命令方法。 */
    private fun collectCommandMethods() {
        var current: Class<*>? = javaClass
        while (current != null && current != Any::class.java) {
            for (method in current.declaredMethods) {
                if (!java.lang.reflect.Modifier.isPublic(method.modifiers)) continue
                val annotation = method.getAnnotation(Commands::class.java) ?: continue
                method.isAccessible = true
                for (command in annotation.value) {
                    if (command.isNotBlank()) commandMap[command] = method
                }
                // 管理员命令登记进注册表（含全部别名；1.7.5.2 覆盖：
                // 按钮门禁与面板联动的单一事实来源，无需手动配置）
                if (annotation.adminOnly) {
                    CommandAdminRegistry.register(annotation.value.filter { it.isNotBlank() })
                }
            }
            current = current.superclass
        }
    }

    /**
     * 尝试将群消息分发给本类注册的命令。
     * @return 消息是否被本类处理
     */
    fun handleMessage(plugin: KERONG, event: GroupMessageEvent): Boolean {
        val content = event.rawMessage?.content ?: return false
        // 去除 @ 片段与开头斜杠后按命令名匹配
        val cleaned = Regex("<@!?[^>]+>").replace(content, "").trim().trimStart('/')

        // 优先匹配更长的命令名，避免前缀冲突（如多字命令与单字前缀）
        for (command in commandMap.keys.sortedByDescending { it.length }) {
            if (cleaned != command && !cleaned.startsWith("$command ")) continue
            if (plugin.commandList()[command] == false) {
 // 关闭提示改走消息模板 command-disabled.md（messages 段可换文件）
                MessageTemplates.sendPlain(event, "command-disabled")
                return true
            }
            val params = cleaned.removePrefix(command).trim()
            val method = commandMap[command] ?: continue
            invokeMethod(plugin, event, method, params)
            return true
        }
        return false
    }

    /** 按参数数量适配调用命令方法（支持挂起函数）。 */
    private fun invokeMethod(plugin: KERONG, event: GroupMessageEvent, method: Method, params: String) {
        val parameterTypes = method.parameterTypes
        val isSuspend = parameterTypes.lastOrNull() == Continuation::class.java
        val argCount = parameterTypes.size - (if (isSuspend) 1 else 0)

        val args: Array<Any?> = when (argCount) {
            3 -> arrayOf(plugin, event, params)
            2 -> arrayOf(event, params)
            1 -> arrayOf(event)
            0 -> arrayOf()
            else -> {
                plugin.log_error("指令方法 ${method.name} 参数数量(${parameterTypes.size})不受支持, 最多支持 (api, message, params)")
                return
            }
        }

        try {
            if (isSuspend) {
                // 为挂起函数补上 Continuation，异常在恢复时统一记录
                val continuation = object : Continuation<Any?> {
                    override val context: CoroutineContext = EmptyCoroutineContext
                    override fun resumeWith(result: Result<Any?>) {
                        result.exceptionOrNull()?.let { error ->
                            plugin.log_error("指令方法 ${method.name} 执行失败: ${error.message}")
                        }
                    }
                }
                method.invoke(this, *args, continuation)
            } else {
                method.invoke(this, *args)
            }
        } catch (error: Exception) {
            plugin.log_error("指令方法 ${method.name} 调用异常: ${error.message}")
        }
    }
}

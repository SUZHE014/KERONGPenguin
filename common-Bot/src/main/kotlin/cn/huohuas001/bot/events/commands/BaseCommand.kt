package cn.huohuas001.bot.events.commands

import cn.huohuas001.bot.HuHoBot
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
 * - `(api: HuHoBot, event: GroupMessageEvent, params: String)`
 * - `(event: GroupMessageEvent, params: String)`
 * - `(event: GroupMessageEvent)`
 * - `()`
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Commands(vararg val value: String)

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
            }
            current = current.superclass
        }
    }

    /**
     * 尝试将群消息分发给本类注册的命令。
     * @return 消息是否被本类处理
     */
    fun handleMessage(plugin: HuHoBot, event: GroupMessageEvent): Boolean {
        val content = event.rawMessage?.content ?: return false
        // 去除 @ 片段与开头斜杠后按命令名匹配
        val cleaned = Regex("<@!?[^>]+>").replace(content, "").trim().trimStart('/')

        // 优先匹配更长的命令名，避免前缀冲突（如“查询open ID”与“查信息”/“查在线”）
        for (command in commandMap.keys.sortedByDescending { it.length }) {
            if (cleaned != command && !cleaned.startsWith("$command ")) continue
            if (plugin.commandList()[command] == false) {
                event.sendMessage("此命令已被管理员关闭")
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
    private fun invokeMethod(plugin: HuHoBot, event: GroupMessageEvent, method: Method, params: String) {
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

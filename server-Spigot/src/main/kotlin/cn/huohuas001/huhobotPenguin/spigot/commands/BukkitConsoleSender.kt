package cn.huohuas001.huhobotPenguin.spigot.commands

import cn.huohuas001.bot.provider.HExecution
import cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot
import net.md_5.bungee.api.chat.BaseComponent
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.command.ConsoleCommandSender
import org.bukkit.conversations.Conversation
import org.bukkit.conversations.ConversationAbandonedEvent
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionAttachment
import org.bukkit.permissions.PermissionAttachmentInfo
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 模拟控制台执行器：
 * 包装一个 ConsoleCommandSender 捕获命令输出，配合 [CommandOutputAppender]
 * 捕获服务器日志，实现“执行命令并拿到输出文本”。
 */
class BukkitConsoleSender(private val plugin: HuHoBotSpigot) : ConsoleCommandSender, HExecution {

    private val messages = CopyOnWriteArrayList<String>()
    private val outputAppender = CommandOutputAppender.Companion.getInstance()

    /** Spigot 富文本消息代理（转换为旧版文本）。 */
    private val spigotSender = object : CommandSender.Spigot() {
        override fun sendMessage(component: BaseComponent) {
            messages.add(component.toLegacyText())
        }

        override fun sendMessage(vararg components: BaseComponent) {
            for (component in components) {
                messages.add(component.toLegacyText())
            }
        }
    }

    /** 清空已捕获的输出。 */
    fun clearMessages() {
        messages.clear()
    }

    /** 取走全部输出并清空。 */
    fun getAndClearMessages(): List<String> {
        val result = messages.toList()
        messages.clear()
        return result
    }

    // ---------- ConsoleCommandSender 权限桩（控制台恒为有权限） ----------

    override fun isOp(): Boolean = true

    override fun setOp(value: Boolean) {}

    override fun isPermissionSet(name: String): Boolean = false

    override fun isPermissionSet(permission: Permission): Boolean = false

    override fun hasPermission(name: String): Boolean = true

    override fun hasPermission(permission: Permission): Boolean = true

    override fun addAttachment(plugin: Plugin, name: String, value: Boolean): PermissionAttachment =
        throw UnsupportedOperationException()

    override fun addAttachment(plugin: Plugin): PermissionAttachment =
        throw UnsupportedOperationException()

    override fun addAttachment(plugin: Plugin, name: String, value: Boolean, ticks: Int): PermissionAttachment? =
        throw UnsupportedOperationException()

    override fun addAttachment(plugin: Plugin, ticks: Int): PermissionAttachment? =
        throw UnsupportedOperationException()

    override fun removeAttachment(attachment: PermissionAttachment) {}

    override fun recalculatePermissions() {}

    override fun getEffectivePermissions(): Set<PermissionAttachmentInfo> = LinkedHashSet()

    // ---------- 消息捕获 ----------

    override fun sendMessage(message: String) {
        messages.add(message)
    }

    override fun sendMessage(vararg messages: String) {
        for (message in messages.filterNotNull()) {
            this.messages.add(message)
        }
    }

    override fun sendMessage(sender: UUID?, message: String) {
        messages.add(message)
    }

    override fun sendMessage(sender: UUID?, vararg messages: String) {
        for (message in messages.filterNotNull()) {
            this.messages.add(message)
        }
    }

    override fun getServer(): org.bukkit.Server = Bukkit.getServer()

    override fun getName(): String = "CONSOLE"

    override fun name(): net.kyori.adventure.text.Component =
        net.kyori.adventure.text.Component.text("CONSOLE")

    override fun spigot(): CommandSender.Spigot = spigotSender

    // ---------- 会话桩 ----------

    override fun isConversing(): Boolean = false

    override fun acceptConversationInput(input: String) {}

    override fun beginConversation(conversation: Conversation): Boolean = false

    override fun abandonConversation(conversation: Conversation) {}

    override fun abandonConversation(conversation: Conversation, details: ConversationAbandonedEvent) {}

    override fun sendRawMessage(message: String) {
        messages.add(message)
    }

    override fun sendRawMessage(sender: UUID?, message: String) {
        messages.add(message)
    }

    // ---------- HExecution ----------

    override val rawString: String
        get() {
            val captured = outputAppender.getCaptured()
            return if (captured.isNotEmpty()) captured.joinToString("\n") else messages.joinToString("\n")
        }

    override fun execute(command: String): CompletableFuture<HExecution> {
        val result = CompletableFuture<HExecution>()
        clearMessages()
        outputAppender.startCapture()
        plugin.submit {
            try {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
                completeAfterCommandOutput(result)
            } catch (error: Exception) {
                outputAppender.stopCapture()
                result.completeExceptionally(error)
            }
        }
        return result
    }

    /** 命令派发后延迟 2 秒收集输出（等待异步日志落盘）。 */
    private fun completeAfterCommandOutput(result: CompletableFuture<HExecution>) {
        Bukkit.getScheduler().runTaskLater(plugin as Plugin, Runnable {
            val captured = outputAppender.stopCapture()
            val senderMessages = getAndClearMessages()
            for (message in (senderMessages + captured).distinct()) {
                sendMessage(message)
            }
            result.complete(this)
        }, COMMAND_OUTPUT_DELAY_TICKS)
    }

    companion object {
        /** 等待命令输出的延迟（tick）。 */
        @Deprecated("保留以兼容旧配置读取")
        const val COMMAND_OUTPUT_DELAY_TICKS = 40L
    }
}

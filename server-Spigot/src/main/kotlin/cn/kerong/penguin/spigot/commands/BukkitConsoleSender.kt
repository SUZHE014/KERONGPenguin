package cn.kerong.penguin.spigot.commands

import cn.kerong.bot.provider.HExecution
import cn.kerong.penguin.spigot.KERONGSpigot
import cn.kerong.penguin.spigot.scheduler.FoliaCompat
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
 *
 * 1.7.2 噪音修复（用户实测：/执行命令 的 QQ 回复里混入与命令无关的
 * 周期性日志——[MediaSource] 媒体刷屏、[Not Secure] 聊天行等）：
 * - 命令改派发给**本自定义 Sender**（旧版派发给真实控制台，输出只能靠
 *   log4j 根 Appender 全量捕窗口内日志，窗口里所有异步日志都进了返回）；
 * - 只有本 Sender 的 sendMessage 回调收到的行才进返回；
 * - 命令没有 Sender 输出（只往 logger 打的命令）时，才回退到 Appender
 *   捕获的日志行（已带噪音黑名单过滤）。
 */
class BukkitConsoleSender(private val plugin: KERONGSpigot) : ConsoleCommandSender, HExecution {

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
        get() = messages.joinToString("\n")

    override fun execute(command: String): CompletableFuture<HExecution> {
        val result = CompletableFuture<HExecution>()
        clearMessages()
        outputAppender.startCapture()
        plugin.submit {
            try {
                // 1.7.2：派发给本自定义 Sender——命令的 sendMessage 反馈
                // 直接进 messages（只有本 Sender 收到的行才进返回）；
                // 旧版派发给真实控制台，输出只能靠 log4j Appender 全量
                // 捕窗口内日志，与命令无关的周期日志（[MediaSource] 等）
                // 整段混进 QQ 回复
                Bukkit.dispatchCommand(this, command)
                completeAfterCommandOutput(result)
            } catch (error: Throwable) {
                // 1.7.0：捕获 Throwable 而非 Exception——命令抛 Error（如其他
                // 插件类的 NoClassDefFoundError）时旧版不完成 future，
                // QQ 侧表现为「执行完没有任何回复」
                outputAppender.stopCapture()
                result.completeExceptionally(error)
            }
        }
        return result
    }

    /**
     * 命令派发后延迟 2 秒收集输出（等待异步反馈落盘）。
     *
     * 1.7.2 优先级：Sender 收到的行 > Appender 日志行（仅 Sender 无输出时
     * 才用日志回退，且日志已过噪音黑名单）。
     */
    private fun completeAfterCommandOutput(result: CompletableFuture<HExecution>) {
        // 1.5.7.3：经 FoliaCompat 桥接（Folia 全局区域线程延迟，收集双路输出）
        FoliaCompat.runSyncLater(plugin as Plugin, COMMAND_OUTPUT_DELAY_TICKS) {
            val senderMessages = getAndClearMessages()
            if (senderMessages.isNotEmpty()) {
                // 只有该 Sender 收到的行才进返回（1.7.2 架构修复主路径）
                for (message in senderMessages) {
                    sendMessage(message)
                }
            } else {
                // 回退：命令只往 logger 打（无 sendMessage 反馈）时用窗口内
                // 日志行（噪音已黑名单过滤）
                for (line in outputAppender.stopCapture()) {
                    sendMessage(line)
                }
            }
            result.complete(this)
        }
    }

    companion object {
        /** 等待命令输出的延迟（tick）。 */
        @Deprecated("保留以兼容旧配置读取")
        const val COMMAND_OUTPUT_DELAY_TICKS = 40L
    }
}

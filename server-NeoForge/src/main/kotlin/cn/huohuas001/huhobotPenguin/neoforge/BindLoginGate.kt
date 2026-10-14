package cn.huohuas001.huhobotPenguin.neoforge

import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import com.mojang.authlib.GameProfile
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener
import net.minecraft.server.network.ConfigurationTask
import net.minecraft.server.network.ServerCommonPacketListenerImpl
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.bus.api.IEventBus
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent
import java.util.function.Consumer

/**
 * QQ 绑定登录闸门（1.6.4，未绑定老玩家的加入前拦截）。
 *
 * 背景：NeoForge 1.21.1 没有登录期玩家事件（PlayerLoggedInEvent 在加入完成
 * 之后才触发），旧版对未绑定玩家的踢出发生在加入后 1 tick——此时客户端仍在
 * 加入世界流程中，绑定提示常被“服务器连接失败”文字覆盖，玩家看不到绑定码。
 *
 * 解法：借用原版白名单/封禁的显示路径。原版在 handleConfigurationFinished
 * （配置阶段结束、进入世界之前）用 PlayerList.canPlayerLogin 的返回值断开
 * 连接，客户端会把断开原因显示在“无法连接到服务器”界面上。NeoForge 1.21.1
 * 的 RegisterConfigurationTasksEvent 允许模组在配置阶段插入自定义任务，且
 * 模组任务排在原版 JoinWorldTask 之前执行——闸门任务轮到时直接以绑定提示
 * 断开连接即可（放行时不注册任务，对原版配置流程零干扰）。
 *
 * 拦截策略（与 Spigot 版 GameChat.onPlayerLogin 同源）：
 * - 老玩家（服务器已有 playerdata/<uuid>.dat 档案）且未绑定 → 配置阶段直接
 *   断开，绑定提示显示在连接失败界面（不会被加入流程覆盖）；
 * - 新玩家（服务器还没建档案）→ 放行让其完成加入（服务器创建 UUID 与玩家
 *   数据），加入后由 NeoForgeEvents.enforceLoginBinding 踢出并展示绑定码
 *   （新玩家必须先建档，否则绑定后进服无档案）。
 *
 * 兜底：本闸门任何环节异常（反射失败 / 绑定管理器未就绪 / 事件总线挂载
 * 失败）一律放行，未绑定玩家仍会被加入期踢出兜底拦截，只是显示效果退回
 * 旧版行为（绑定码可见性下降，功能不缺失）。
 */
object BindLoginGate {

    /** 挂载到 mod 事件总线（HuHoBotNeoForge 在服务器启动完成时调用）。 */
    fun registerTo(modBus: IEventBus) {
        modBus.register(this)
    }

    /**
     * 每个连接配置阶段触发一次：判定未绑定老玩家则注册闸门任务。
     * 放行（已绑定 / 新玩家 / 白名单 skip / 任何异常）不注册任务。
     */
    @SubscribeEvent
    fun onRegisterConfigurationTasks(event: RegisterConfigurationTasksEvent) {
        val kick = evaluateKick(event.listener) ?: return
        event.register(BindGateTask(event.listener, kick))
    }

    /**
     * 拦截判定：返回断开用的 Component；放行返回 null。
     * 仅拦截「服务器已有档案且未绑定」的老玩家。
     */
    private fun evaluateKick(listener: ServerConfigurationPacketListener): Component? {
        return try {
            val server = NeoServerRef.server ?: return null
            val manager = try {
                QqBindManager.getInstance()
            } catch (_: Throwable) {
                return null
            }
            if (!manager.isEnabled) return null

            val profile = profileOf(listener) ?: return null
            val playerName = profile.name
            val playerUuid = profile.id.toString()
            if (manager.isSkipped(playerName)) return null

            // 确保绑定记录存在（UUID 机制，含旧数据迁移）
            manager.getOrCreateQuuidByUuid(playerName, playerUuid)
            if (manager.isBoundByUuid(playerName, playerUuid)) return null

            // 老玩家判定：世界存档目录已存在 playerdata/<uuid>.dat
            // （新玩家放行 → 加入后 enforceLoginBinding 建档踢出）
            val dataFile = server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
                .resolve("$playerUuid.dat").toFile()
            if (!dataFile.exists()) return null

            val code = manager.generateCode(playerName, playerUuid)
            LegacyText.toComponent(manager.formatKickMessage(code, playerName))
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 从配置监听器读取玩家档案（GameProfile）。
     * playerProfile() 为 protected，反射调用；失败再退回 gameProfile 私有字段。
     */
    private fun profileOf(listener: ServerConfigurationPacketListener): GameProfile? {
        return try {
            val method = listener.javaClass.getDeclaredMethod("playerProfile")
            method.isAccessible = true
            method.invoke(listener) as? GameProfile
        } catch (_: Throwable) {
            try {
                val field = listener.javaClass.getDeclaredField("gameProfile")
                field.isAccessible = true
                field.get(listener) as? GameProfile
            } catch (_: Throwable) {
                null
            }
        }
    }

    /**
     * 闸门任务：轮到执行时直接断开连接（连接仍处配置阶段，客户端把断开
     * 原因显示在“无法连接到服务器”界面——与原版白名单/封禁提示同路径）。
     * 任务的 start 参数是发包 Consumer（原版 ConfigurationTask 约定），
     * 本任务无需发包，只用监听器断开。
     */
    private class BindGateTask(
        private val listener: ServerConfigurationPacketListener,
        private val reason: Component
    ) : ConfigurationTask {

        override fun start(sender: Consumer<Packet<*>>) {
            (listener as? ServerCommonPacketListenerImpl)?.disconnect(reason)
        }

        override fun type(): ConfigurationTask.Type = TYPE

        companion object {
            val TYPE = ConfigurationTask.Type("kerongpenguin:bind_gate")
        }
    }
}

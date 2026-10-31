package cn.kerong.penguin.neoforge

import cn.kerong.penguin.spigot.qqbind.QqBindManager
import com.mojang.authlib.GameProfile
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.configuration.ServerConfigurationPacketListener
import net.minecraft.server.network.ConfigurationTask
import net.minecraft.server.network.ServerCommonPacketListenerImpl
import net.minecraft.world.level.storage.LevelResource
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask
import java.lang.reflect.Method
import java.util.function.Consumer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QQ 绑定登录闸门（1.6.4，未绑定老玩家的加入前拦截；1.6.7.9 跨版本重做；
 * 1.6.7.11 修复挂载失败的真根因）。
 *
 * 背景：NeoForge 没有登录期玩家事件（PlayerLoggedInEvent 在加入完成
 * 之后才触发），旧版对未绑定玩家的踢出发生在加入后 1 tick——此时客户端仍在
 * 加入世界流程中，绑定提示常被“服务器连接失败”文字覆盖，玩家看不到绑定码。
 *
 * 解法：借用原版白名单/封禁的显示路径。配置阶段结束、进入世界之前，以绑定
 * 提示断开连接，客户端会把断开原因显示在“无法连接到服务器”界面上。
 *
 * 1.6.7.9 多版本适配（MC 1.20.4 / 1.20.6 / 1.21.x）——两个时代的配置阶段
 * 事件类不同，且任一时代的类在另一时代的运行时不存在，无法编译期同时绑定：
 * - 20.2~20.4（MC 1.20.4）：net.neoforged.neoforge.network.event.
 *   **OnGameConfigurationEvent**（register 参数 ICustomConfigurationTask）；
 * - 20.5+（MC 1.20.6 / 1.21.x）：net.neoforged.neoforge.network.event.
 *   **RegisterConfigurationTasksEvent**（register 参数原版 ConfigurationTask）。
 * 两代事件类都实现 net.neoforged.fml.event.IModBusEvent（mod 总线事件，
 * 由 ModLoader.postEventWithReturn 广播到每个 mod 自己的总线），且
 * getListener()/register() 方法形态一致（已对 20.4.253 / 20.6.144 /
 * 21.1.252 / 21.11.48 构件字节级核实）。
 *
 * 1.6.7.11 挂载修复：1.6.7.9 用「@SubscribeEvent + 事件基类 Event 参数」
 * 的监听器注册 mod 总线——但 mod 总线以 markerType(IModBusEvent) 构造，
 * EventBus 校验监听参数必须实现 IModBusEvent，基类 Event 不满足，register()
 * 直接抛 IllegalArgumentException（"takes an argument that is not valid for
 * this bus"），闸门整个没挂上（运行时表现为一条 WARN 后仍报“已挂载”）。
 * 而 @SubscribeEvent 参数又必须同时是 Event 子类型（接口 IModBusEvent 不行）
 * 且是目标事件类的超类——编译目标 20.4 下不存在这样的公共类型，注解监听
 * 这条路走不通。改为**反射调用 IEventBus.addListener(Class, Consumer)**
 * 以「运行时探测到的具体事件类」精确注册：运行时校验用的是真实事件类
 * （实现 IModBusEvent，能过 mod 总线 checker），且只收到该事件本身
 * （无需 isInstance 过滤）；addListener(Class, Consumer) 重载在 bus 7.x/8.x
 * 均存在（编译目标 bus 7.2.0，21.x 运行时 bus 8.0.5，签名一致）。
 *
 * 拦截策略（与 Spigot 版 GameChat.onPlayerLogin 同源）：
 * - 老玩家（服务器已有 playerdata/<uuid>.dat 档案）且未绑定 → 配置阶段直接
 *   断开，绑定提示显示在连接失败界面（不会被加入流程覆盖）；
 * - 新玩家（服务器还没建档案）→ 放行让其完成加入（服务器创建 UUID 与玩家
 *   数据），加入后由 NeoForgeEvents.enforceLoginBinding 踢出并展示绑定码
 *   （新玩家必须先建档，否则绑定后进服无档案）。
 *
 * 兜底：本闸门任何环节异常（反射失败 / 绑定管理器未就绪 / 事件总线挂载
 * 失败 / 当前版本事件形态未识别）一律放行，未绑定玩家仍会被加入期踢出
 * 兜底拦截，只是显示效果退回旧版行为（绑定码可见性下降，功能不缺失）。
 */
object BindLoginGate {

    /** 20.5+（MC 1.20.6 / 1.21.x）配置任务注册事件类名。 */
    private const val MODERN_EVENT_CLASS =
        "net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent"

    /** 20.2~20.4（MC 1.20.4）配置阶段事件类名。 */
    private const val LEGACY_EVENT_CLASS =
        "net.neoforged.neoforge.network.event.OnGameConfigurationEvent"

    /** 幂等护栏：同一进程内（含集成服多世界重复启停）只挂载一次。 */
    private val mounted = AtomicBoolean(false)

    /** 反射方法缓存（getListener / register；事件类确定后不变）。 */
    @Volatile
    private var getListenerMethod: Method? = null

    @Volatile
    private var registerMethod: Method? = null

    /**
     * 挂载到 mod 事件总线（KERONGNeoForge 在服务器启动完成时调用）。
     *
     * @return true = 挂载成功；false = 失败（调用方据此如实反馈，
     *         失败细节已在本方法内 WARN，未绑定玩家由加入后踢出兜底）。
     */
    fun registerTo(modBus: IEventBus): Boolean {
        try {
            if (!mounted.compareAndSet(false, true)) return true
            val eventClass = resolveConfigurationEventClass()
            if (eventClass == null) {
                warn("未识别当前 NeoForge 的配置阶段事件形态，登录闸门退化为加入后踢出")
                return false
            }
            val addListener = resolveAddListenerMethod()
            if (addListener == null) {
                warn("当前事件总线缺少 addListener(Class, Consumer) 重载，登录闸门退化为加入后踢出")
                return false
            }
            // 以解析到的具体事件类精确注册：mod 总线 checker 校验的是真实
            // 事件类（实现 IModBusEvent，可通过），泛型边界 <T extends Event>
            // 仅编译期约束、反射调用不做运行时检查。
            addListener.invoke(
                modBus, eventClass,
                Consumer<Any> { event ->
                    try {
                        handleConfigurationEvent(event)
                    } catch (_: Throwable) {
                        // 任何异常放行（加入后踢出兜底）
                    }
                }
            )
            return true
        } catch (t: Throwable) {
            warn("登录闸门挂载异常（老玩家拦截退化为加入后踢出）: ${t.message}")
            return false
        }
    }

    private fun warn(message: String) {
        org.slf4j.LoggerFactory.getLogger("KERONGPenguin").warn("[QQ绑定] $message")
    }

    /** 探测当前运行时的配置阶段事件类（20.5+ 优先，20.4 兜底）。 */
    private fun resolveConfigurationEventClass(): Class<*>? = try {
        Class.forName(MODERN_EVENT_CLASS)
    } catch (_: Throwable) {
        try {
            Class.forName(LEGACY_EVENT_CLASS)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 从 IEventBus 接口解析 addListener(Class&lt;T&gt;, Consumer&lt;T&gt;) 重载。
     * 该重载自 Forge 时代起稳定存在（已核对 bus 7.2.0 与 8.0.5 字节码），
     * 是唯一 (Class, Consumer) 双参形态，不会被其他重载误匹配。
     */
    private fun resolveAddListenerMethod(): Method? =
        IEventBus::class.java.methods.firstOrNull {
            it.name == "addListener" &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == java.lang.Class::class.java &&
                it.parameterTypes[1] == Consumer::class.java
        }

    /** 配置阶段事件处理：判定未绑定老玩家则注册闸门任务。 */
    private fun handleConfigurationEvent(event: Any) {
        val cls = event.javaClass
        val getListener = getListenerMethod ?: cls.getMethod("getListener")
            .also { getListenerMethod = it }
        val register = registerMethod
            ?: cls.methods.firstOrNull { it.name == "register" && it.parameterCount == 1 }
                ?.also { registerMethod = it }
            ?: return
        val listener = getListener.invoke(event) as? ServerConfigurationPacketListener ?: return
        val kick = evaluateKick(listener) ?: return
        register.invoke(event, BindGateTask(listener, kick))
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
     *
     * 实现 [ICustomConfigurationTask]（跨版本桥梁：该接口在 20.4 与 20.5+
     * 字节码完全一致，extends 原版 ConfigurationTask，分别是两时代 register
     * 参数的合法实参）。本任务无需发包，run 里只用监听器断开。
     */
    private class BindGateTask(
        private val listener: ServerConfigurationPacketListener,
        private val reason: Component
    ) : ICustomConfigurationTask {

        override fun run(sender: Consumer<CustomPacketPayload>) {
            (listener as? ServerCommonPacketListenerImpl)?.disconnect(reason)
        }

        override fun type(): ConfigurationTask.Type = TYPE

        companion object {
            val TYPE = ConfigurationTask.Type("kerongpenguin:bind_gate")
        }
    }
}

package cn.kerong.penguin.spigot.stats

/**
 * MOTD 展示人数捕获器（1.6.9.3 新增）。
 *
 * 用途：/查性能 卡片在线人数卡的最大人数默认用服务器真实值，服主可把
 * render.performance.max-players-source 改为 motd——改用**其他插件修改过的
 * 展示人数**（就是服务器列表 / MOTD 里显示的人数，如虚假人数类插件改过的值）。
 *
 * 原理：Spigot 端在 [org.bukkit.event.server.ServerListPingEvent]（MONITOR
 * 优先级，所有修改该事件的其他插件跑完之后）读取 event.maxPlayers 存入本
 * 单例——即最近一次客户端 ping 服务器时对外展示的人数。玩家 / 监控探针
 * ping 服务器都会刷新该值；无人 ping 过（刚启动）时回退真实最大人数。
 *
 * NeoForge 端：无对应的 ping 状态事件可监听（编译期构件无该事件类），
 * [lastDisplayMax] 恒 null，查性能始终用真实值（config.yml 注释已说明）。
 */
object MotdDisplayTracker {

    /** 最近一次 ping 服务器时对外展示的最大人数（null = 尚未捕获过）。 */
    @Volatile
    var lastDisplayMax: Int? = null
        private set

    /** 记录一次 ping 时的展示人数（非法值忽略）。 */
    fun record(displayMax: Int) {
        if (displayMax > 0) {
            lastDisplayMax = displayMax
        }
    }
}

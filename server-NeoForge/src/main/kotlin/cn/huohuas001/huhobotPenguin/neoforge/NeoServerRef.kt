package cn.huohuas001.huhobotPenguin.neoforge

import net.minecraft.server.MinecraftServer

/**
 * 当前 MinecraftServer 引用（事件生命周期维护）。
 * 兼容层与调度器在服务器未就绪时安全降级（不抛异常）。
 */
object NeoServerRef {

    @Volatile
    var server: MinecraftServer? = null
        internal set

    /** 服务器启动（ServerAboutToStartEvent）。 */
    fun bind(server: MinecraftServer) {
        this.server = server
    }

    /** 服务器完全停止（ServerStoppedEvent）。 */
    fun unbind() {
        server = null
    }
}

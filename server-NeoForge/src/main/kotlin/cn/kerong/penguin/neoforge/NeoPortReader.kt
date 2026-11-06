package cn.kerong.penguin.neoforge

import java.io.File
import java.net.InetSocketAddress

/**
 * 本服端口读取器（1.7.3.1 第三次覆盖版新增）。
 *
 * 用途：[cn.kerong.penguin.spigot.stats.MotdDisplayTracker] 本机 SLP 自查询
 * 需要本服端口（对 127.0.0.1:<端口> 发服务器列表 ping 读展示人数）。Spigot
 * 端直接 Bukkit.getPort()；模组端 MinecraftServer 没有稳定的公开端口访问器
 * （mojmap 成员名跨版本有风险、netty 不在 NF 编译类路径上），按可靠性次序
 * 三重策略，任一命中即用：
 *
 * ① 连接监听器 channel 本地端口：server.getConnection()/connection →
 *    channels（netty Channel 列表）→ localAddress —— 服务器实际监听端口，
 *    最可靠；
 * ② 专用服属性：DedicatedServer.getProperties()/properties → serverPort；
 * ③ server.properties 文本兜底：从数据目录（kerongpenguin/）的上级（服务器
 *    根目录）读 server-port 键——完全不依赖 mojmap 成员名，纯文件读取。
 *
 * 全部失败返回 null（motd 自查询不可用，查性能回退真实值）。
 */
internal object NeoPortReader {

    fun portOf(server: Any?): Int? {
        if (server != null) {
            viaListenerChannels(server)?.let { return it }
            viaDedicatedProperties(server)?.let { return it }
        }
        return null
    }

    /** ③ server.properties 文本兜底（[dataFolder] = kerongpenguin/ 数据目录）。 */
    fun portFromPropertiesFile(dataFolder: File?): Int? {
        if (dataFolder == null) return null
        return try {
            val props = java.util.Properties()
            File(dataFolder.parentFile, "server.properties").inputStream().use { props.load(it) }
            props.getProperty("server-port")?.trim()?.toIntOrNull()?.takeIf { it in 1..65535 }
        } catch (_: Throwable) {
            null
        }
    }

    /** ① 连接监听器 channel 本地端口（netty 类不在 NF 编译类路径，纯反射）。 */
    private fun viaListenerChannels(server: Any): Int? {
        try {
            val conn = server.javaClass.methods.firstOrNull {
                it.name == "getConnection" && it.parameterCount == 0
            }?.takeIf { it.trySetAccessible() }?.invoke(server)
                ?: server.javaClass.fields.firstOrNull { it.name == "connection" }?.get(server)
                ?: return null
            val channelsField = conn.javaClass.fields.firstOrNull { it.name == "channels" } ?: return null
            @Suppress("UNCHECKED_CAST")
            val channels = channelsField.get(conn) as? List<Any?> ?: return null
            for (channel in channels) {
                if (channel == null) continue
                val address = try {
                    channel.javaClass.methods.firstOrNull {
                        it.name == "localAddress" && it.parameterCount == 0
                    }?.takeIf { it.trySetAccessible() }?.invoke(channel)
                } catch (_: Throwable) {
                    null
                } ?: continue
                val port = (address as? InetSocketAddress)?.port
                    ?: address.toString().let { text ->
                        Regex(":([0-9]{1,5})[\\s]*\$").find(text)?.groupValues?.get(1)?.toIntOrNull()
                    }
                if (port != null && port in 1..65535) return port
            }
        } catch (_: Throwable) {
        }
        return null
    }

    /** ② 专用服属性 serverPort。 */
    private fun viaDedicatedProperties(server: Any): Int? {
        try {
            val props = server.javaClass.methods.firstOrNull {
                it.name == "getProperties" && it.parameterCount == 0
            }?.takeIf { it.trySetAccessible() }?.invoke(server)
                ?: server.javaClass.fields.firstOrNull { it.name == "properties" }?.get(server)
                ?: return null
            val portField = props.javaClass.fields.firstOrNull { it.name == "serverPort" } ?: return null
            val port = portField.getInt(props)
            if (port in 1..65535) return port
        } catch (_: Throwable) {
        }
        return null
    }
}

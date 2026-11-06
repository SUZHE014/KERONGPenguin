package cn.kerong.penguin.spigot.stats

import com.alibaba.fastjson.JSON
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MOTD 展示人数捕获器（1.6.9.3 新增；1.7.3.1 第三次覆盖版机制重做）。
 *
 * 用途：/查性能 卡片在线人数卡默认用服务器真实值，服主可把
 * render.performance.max-players-source 改为 motd——改用**服务器列表实际
 * 展示的人数**（其他插件 / 模组改过的值，即玩家在多人游戏服务器列表里
 * 看到的在线 / 最大人数）。
 *
 * 1.7.3.1 第三次覆盖版重做（旧事件捕获机制整体废弃）：
 * - 旧版（1.6.9.3）：Spigot 端监听 ServerListPingEvent（MONITOR）记录
 *   event.maxPlayers——只捕获最大人数、NeoForge 端无对应事件恒为 null
 *   （模组端配置写 motd 也永远显示真实值，用户实测「配置文件写的是
 *   motd，可展示的人数是服务器真实人数」）、假人数插件用改包
 *   （ProtocolLib 等）实现时事件里也读不到修改后的值。
 * - 新版：**本机 SLP 自查询**——需要时（[refreshIfStale]，60 秒节流）对
 *   127.0.0.1:<本服端口> 发一次 Minecraft 服务器列表 ping（status
 *   协议），解析响应 JSON 里的 players.online / players.max。这正是
 *   客户端在服务器列表看到的最终展示值：无论假人数插件 / 模组用事件、
 *   改包还是其他手段实现，修改都发生在这一层；**双端（插件版 / 模组版）
 *   统一生效**。查不到（端口未注入 / 连接失败 / 响应不合法）回退真实值。
 * - 端口由各平台启动时注入 [portProvider]：Spigot 端 Bukkit.getPort()；
 *   NeoForge 端从 MinecraftServer 连接 channel / 专用服属性反射读取
 *   （见 KERONGNeoForge 注入处）。
 * - 刷新在调用方线程执行（查性能的卡片渲染线程 CardRenderPool，本身
 *   异步，不占游戏线程）；连接 3 秒 / 读 3 秒超时；[inFlight] 防并发重入。
 * - 人数 N 与最大 M 成对捕获成对使用（见 PerformanceService.resolveDisplayCounts）。
 */
object MotdDisplayTracker {

    /** SLP 连接与读取超时（毫秒；本机回环，正常毫秒级完成）。 */
    private const val CONNECT_TIMEOUT = 3_000
    private const val READ_TIMEOUT = 3_000

    /** 捕获值复用窗口（毫秒）：窗口内的查性能直接复用最近捕获。 */
    private const val REFRESH_INTERVAL_MS = 60_000L

    /** 最近一次捕获的展示在线人数（null = 尚未捕获过）。 */
    @Volatile
    var lastDisplayOnline: Int? = null
        private set

    /** 最近一次捕获的展示最大人数（null = 尚未捕获过）。 */
    @Volatile
    var lastDisplayMax: Int? = null
        private set

    /** 最近一次成功捕获的时间戳（System.currentTimeMillis()；0 = 从未捕获）。 */
    @Volatile
    var lastCaptureAt: Long = 0L
        private set

    /** 本服端口提供器（各平台启动时注入；null = 未注入，自查询不可用）。 */
    @Volatile
    var portProvider: (() -> Int?)? = null

    /** 自查询进行中标记（防并发重入）。 */
    private val inFlight = AtomicBoolean(false)

    /**
     * 记录一次捕获（自查询 / 冒烟测试共用入口）。
     * online 允许 0（空服展示 0/N 也合法）；max 必须 > 0——两者成对才有效。
     */
    fun record(online: Int?, max: Int?) {
        if (online != null && online >= 0 && max != null && max > 0) {
            lastDisplayOnline = online
            lastDisplayMax = max
            lastCaptureAt = System.currentTimeMillis()
        }
    }

    /** 平台停用 / 冒烟重置：清空端口注入与捕获值。 */
    fun reset() {
        portProvider = null
        lastDisplayOnline = null
        lastDisplayMax = null
        lastCaptureAt = 0L
    }

    /**
     * 捕获值过期时现场自查询一次（在调用方线程执行——查性能渲染线程
     * 本身异步）。窗口内的捕获直接复用；端口未注入 / 查询失败静默保持
     * 旧值（是否回退真实值由调用方按捕获值是否为 null 决定）。
     */
    fun refreshIfStale(maxAgeMs: Long = REFRESH_INTERVAL_MS) {
        if (maxAgeMs > 0 && lastCaptureAt > 0L &&
            System.currentTimeMillis() - lastCaptureAt < maxAgeMs
        ) {
            return
        }
        val port = try {
            portProvider?.invoke()
        } catch (_: Throwable) {
            null
        } ?: return
        if (!inFlight.compareAndSet(false, true)) return
        try {
            pingStatus(port)?.let { (online, max) -> record(online, max) }
        } catch (_: Throwable) {
            // 自查询失败（端口未监听 / 协议异常 / 超时）：保持旧值
        } finally {
            inFlight.set(false)
        }
    }

    // ---------- SLP（服务器列表 ping）客户端 ----------

    /** 对 127.0.0.1:[port] 发一次服务器列表 ping，返回 (展示在线, 展示最大)。 */
    internal fun pingStatus(port: Int): Pair<Int, Int>? {
        Socket().use { socket ->
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_TIMEOUT)
            socket.soTimeout = READ_TIMEOUT
            val output = socket.getOutputStream()
            // 握手包：id 0x00 + 协议版本 + 主机 + 端口 + 下一状态 1（status）
            val host = "127.0.0.1".toByteArray(Charsets.US_ASCII)
            val payload = ByteArrayOutputStream()
            payload.write(0x00)
            writeVarInt(payload, 765) // 协议版本 1.20.4；status 阶段服务端不校验版本
            writeVarInt(payload, host.size)
            payload.write(host)
            payload.write((port ushr 8) and 0xFF)
            payload.write(port and 0xFF)
            payload.write(0x01)
            writeFramed(output, payload.toByteArray())
            // 状态请求包：id 0x00（空载荷）
            writeFramed(output, byteArrayOf(0x00))
            // 读响应：整帧 = [总长度][包 id 0x00][JSON 字符串]
            val input = socket.getInputStream()
            val frameLen = readVarInt(input)
            if (frameLen <= 0 || frameLen > 4 * 1024 * 1024) return null
            val frame = ByteArray(frameLen)
            var read = 0
            while (read < frameLen) {
                val n = input.read(frame, read, frameLen - read)
                if (n < 0) return null
                read += n
            }
            val buf = FrameReader(frame)
            val packetId = buf.readVarInt()
            if (packetId != 0x00) return null
            val jsonLen = buf.readVarInt()
            if (jsonLen <= 0 || buf.pos + jsonLen > frame.size) return null
            val jsonText = String(frame, buf.pos, jsonLen, Charsets.UTF_8)
            val players = JSON.parseObject(jsonText).getJSONObject("players") ?: return null
            val online = players.getInteger("online") ?: return null
            val max = players.getInteger("max") ?: return null
            return online to max
        }
    }

    /** 帧内容加长度前缀写出。 */
    private fun writeFramed(output: OutputStream, payload: ByteArray) {
        writeVarInt(output, payload.size)
        output.write(payload)
        output.flush()
    }

    private fun writeVarInt(output: OutputStream, value: Int) {
        var v = value
        while (true) {
            if (v and 0x7F.inv() == 0) {
                output.write(v)
                return
            }
            output.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
    }

    /** 从流读一个 VarInt（上限 5 字节）。 */
    private fun readVarInt(input: InputStream): Int {
        var result = 0
        for (i in 0 until 5) {
            val b = input.read()
            if (b < 0) throw EOFException("varint eof")
            result = result or ((b and 0x7F) shl (7 * i))
            if (b and 0x80 == 0) return result
        }
        throw IOException("varint too long")
    }

    /** 帧内游标读取。 */
    private class FrameReader(private val data: ByteArray) {
        var pos = 0
            private set

        fun readVarInt(): Int {
            var result = 0
            for (i in 0 until 5) {
                if (pos >= data.size) throw EOFException("frame eof")
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F) shl (7 * i))
                if (b and 0x80 == 0) return result
            }
            throw IOException("varint too long")
        }
    }
}

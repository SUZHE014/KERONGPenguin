package cn.kerong.penguin.spigot.stats

import cn.kerong.bot.provider.BotShared
import cn.kerong.bot.tools.PluginFileLog
import com.alibaba.fastjson.JSON
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 假人登记表（1.7.6.1）——假人插件假人的统一识别口径。
 *
 * 背景（用户口径：「修复在线排行榜假人插件的假人时长也在排行榜里面的问题」
 * +「给配置文件添加一个配置，打开的时候查在线额外显示一个假人列表，
 * 显示当前在线的假人」）：在线排行榜 1.7.6 三次覆盖轮起直接枚举服务器
 * 全部玩家数据（usercache.json 全量 + stats 目录），假人插件
 * （minecraft-fakeplayer 等）注入的假人同样是服务器口径的「玩家」——
 * 有 usercache 条目、有 stats/<uuid>.json、play_time 同样累计，
 * 于是混进了排行榜。本登记表把「假人」从数据里分离出来。
 *
 * ## 判定口径（与 1.7.5.8 绑定拦截豁免同源）
 *
 * 一个玩家是假人，当且仅当：
 * 1. **连接判定**：登录连接为本地注入型——地址为空（进程内注入，无网络
 *    通道）或回环地址（127.0.0.0/8，FakeChannel 生成）或同进程内存连接
 *    （与 Spigot isLocalFakeLogin / NeoForge BindLoginGate.isLocalFakeConnection
 *    / Fabric FakePlayerExempt 同一逻辑，由各端注入 [connectionDetector]）；
 * 2. **名单判定**：玩家名命中 qq-bind.fake-player-whitelist（大小写不敏感；
 *    1.7.6.1 起该名单全端生效——Fabric 端照旧豁免未绑定限制，所有端都
 *    用它识别假人）。
 *
 * ## 持久登记（在线排行榜过滤用）
 *
 * 每次玩家加入（三端 join 事件）把判定结果写入插件数据目录的
 * `fake-players.json`：
 * - 判定为假人 → 登记该 UUID（假人下线后历史时长仍要从排行榜排除，
 *   排行榜读的是存档统计文件，与是否在线无关）；
 * - 判定为真人 → **自愈移除**：曾经因本机测试（127.0.0.1 连接）等被
 *   误登记的玩家，用真实外网连接进服一次即恢复上榜；假人插件的连接
 *   永远不会是真实外网连接，不会被误愈。
 *
 * 文件为 JSON 数组格式，管理员可手动编辑（删掉某条 = 让该玩家重新
 * 可上榜，下次它再以假人连接进服会重新登记）。
 *
 * ## 读写时机
 *
 * - **写**：仅三端 join 事件（主线程，逐玩家一次，判定开销为几次反射 /
 *   地址读取）；文件写入走临时文件 + 原子改名，损坏不扩散。
 * - **读**：在线排行榜收集（渲染池线程，读内存镜像）；/查在线 快照
 *   （主线程快照内对在线玩家实时判定）；markdown 文字版按名字比对。
 *
 * 线程安全：内存镜像 [known] 用 ConcurrentHashMap；文件保存串行化
 * （[saveLock]）。
 */
object FakePlayerRegistry {

    /**
     * 平台注入的连接检测器：传入在线玩家（Spigot 真实 Bukkit Player /
     * NeoForge / Fabric 兼容层 Player），返回其连接是否为本地注入型。
     *
     * 各端在启动时注册一次（Spigot KERONGSpigot.onEnable /
     * NeoForge KERONGNeoForge.onServerStarted / Fabric
     * KERONGFabric.onServerAboutToStart），签名只依赖共享的
     * org.bukkit.entity.Player——/查在线 主线程快照对在线玩家实时判定用
     * （不依赖 join 时的登记，插件重载后对已在线玩家也能正确识别）。
     */
    @Volatile
    var connectionDetector: ((org.bukkit.entity.Player) -> Boolean)? = null

    /** 已登记假人内存镜像：UUID → 玩家名（lazy 从 fake-players.json 装载）。 */
    @Volatile
    private var known: ConcurrentHashMap<UUID, String>? = null

    /** 文件保存互斥（join 主线程串行调用，双保险）。 */
    private val saveLock = Any()

    /** 登记文件所在目录（数据目录 globalconfig/，1.7.6.3 覆盖轮重构；null = 未就绪，全部判定退化为仅名单判定）。 */
    private fun dataDirectory(): File? =
        (BotShared.instance as? JavaPlugin)?.dataFolder?.resolve("globalconfig")

    /** 登记文件（globalconfig/fake-players.json）。 */
    private fun registryFile(): File? = dataDirectory()?.resolve("fake-players.json")

    /**
     * 玩家加入时的登记判定（三端 join 事件调用，主线程）：
     * [connectionFake] = 该端连接判定结果（地址 / 内存连接检测），
     * 名单命中在内部合并——两路任一命中即登记，全部不命中走自愈移除。
     */
    fun evaluateJoin(uuid: UUID, name: String, connectionFake: Boolean) {
        try {
            val fake = connectionFake || whitelisted(name)
            if (fake) {
                markFake(uuid, name)
            } else {
                unmarkFake(uuid)
            }
        } catch (_: Throwable) {
            // 登记失败不影响加入流程
        }
    }

    /**
     * 在线玩家实时判定（/查在线 主线程快照与排行榜快照用）：
     * 名单命中 或 当前连接为本地注入型。不读持久登记——当前连接才是
     * 「此刻是不是假人」的事实（曾经被误登记的真人自愈前也按真人展示）。
     */
    fun liveIsFake(player: org.bukkit.entity.Player): Boolean {
        if (whitelisted(player.name)) return true
        val detector = connectionDetector ?: return false
        return try {
            detector(player)
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 在线排行榜条目过滤判定（渲染池线程调用）：
     * [onlineFakes] = 本次排行榜流程主线程快照里实时判定为假人的在线
     * UUID 集；加上持久登记（离线假人的历史时长）与名单名命中。
     */
    fun isExcluded(uuid: UUID?, name: String, onlineFakes: Set<UUID> = emptySet()): Boolean {
        if (uuid != null && onlineFakes.contains(uuid)) return true
        if (uuid != null && ensureLoaded()?.containsKey(uuid) == true) return true
        if (whitelisted(name)) return true
        return false
    }

    /**
     * 按玩家名判定（markdown 文字版 /查在线 用，非主线程）：
     * 名单命中 或 名字在持久登记里（大小写不敏感——离线服同名账号与
     * usercache 大小写变体归并）。
     */
    fun isFakeName(name: String): Boolean {
        if (whitelisted(name)) return true
        val map = ensureLoaded() ?: return false
        return map.values.any { it.equals(name, ignoreCase = true) }
    }

    /** qq-bind.fake-player-whitelist 名单命中（大小写不敏感；空名单零开销）。 */
    private fun whitelisted(name: String): Boolean {
        val list = try {
            (BotShared.instance as? JavaPlugin)?.config?.getStringList("qq-bind.fake-player-whitelist")
        } catch (_: Throwable) {
            null
        } ?: return false
        if (list.isEmpty()) return false
        return list.any { it?.trim()?.equals(name, ignoreCase = true) == true }
    }

    /** 登记为假人（新登记或改名更新都落盘；已登记且同名零开销）。 */
    private fun markFake(uuid: UUID, name: String) {
        val map = ensureLoaded() ?: return
        val prev = map.put(uuid, name)
        if (prev != name) {
            PluginFileLog.write("[假人登记] $name（$uuid）记为假人插件假人：在线排行榜不再计入其时长，/查在线 开启假人列表后划入假人小节")
            persist(map)
        }
    }

    /** 自愈移除（真实外网连接进服 = 不是假人；未登记零开销）。 */
    private fun unmarkFake(uuid: UUID) {
        val map = ensureLoaded() ?: return
        if (map.remove(uuid) != null) {
            PluginFileLog.write("[假人登记] $uuid 以真实外网连接进服，自愈移除假人登记（恢复计入排行榜）")
            persist(map)
        }
    }

    /** 惰性装载登记文件（失败按空表跑，不阻断任何流程）。 */
    private fun ensureLoaded(): ConcurrentHashMap<UUID, String>? {
        known?.let { return it }
        synchronized(saveLock) {
            known?.let { return it }
            val map = ConcurrentHashMap<UUID, String>()
            val file = registryFile()
            if (file != null && file.isFile) {
                try {
                    val text = file.readText(Charsets.UTF_8)
                    if (text.isNotBlank()) {
                        val array = JSON.parseArray(text)
                        for (i in 0 until array.size) {
                            val entry = array.getJSONObject(i) ?: continue
                            val uuidText = entry.getString("uuid") ?: continue
                            val uuid = runCatching { UUID.fromString(uuidText) }.getOrNull() ?: continue
                            val name = entry.getString("name") ?: ""
                            map.putIfAbsent(uuid, name)
                        }
                    }
                } catch (error: Throwable) {
                    PluginFileLog.write("[假人登记] fake-players.json 解析失败（按空登记表继续）: ${error.message}")
                }
            }
            known = map
            return map
        }
    }

    /** 落盘（临时文件 + 原子改名；失败只写日志）。 */
    private fun persist(map: ConcurrentHashMap<UUID, String>) {
        synchronized(saveLock) {
            val file = registryFile() ?: return
            try {
                val directory = file.parentFile
                if (directory != null && !directory.exists()) directory.mkdirs()
                val array = com.alibaba.fastjson.JSONArray()
                for ((uuid, name) in map.entries.sortedBy { it.value.lowercase() }) {
                    val entry = com.alibaba.fastjson.JSONObject()
                    entry["uuid"] = uuid.toString()
                    entry["name"] = name
                    array.add(entry)
                }
                val tmp = File(file.parentFile, "fake-players.json.tmp")
                tmp.writeText(array.toJSONString(), Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    // 极端文件系统（rename 跨句柄失败）回退直写
                    file.writeText(array.toJSONString(), Charsets.UTF_8)
                    tmp.delete()
                }
            } catch (error: Throwable) {
                PluginFileLog.write("[假人登记] fake-players.json 保存失败: ${error.message}")
            }
        }
    }

    /** 重置内存镜像（测试用；正常流程不会触发）。 */
    fun resetCache() {
        synchronized(saveLock) {
            known = null
        }
    }
}

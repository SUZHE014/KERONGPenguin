package cn.kerong.penguin.spigot.events

import cn.kerong.bot.QClient
import cn.kerong.bot.events.commands.BindCommands
import cn.kerong.bot.tools.PluginFileLog
import cn.kerong.penguin.spigot.commands.QqCommand
import cn.kerong.penguin.spigot.qqbind.AiChat
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import cn.kerong.penguin.spigot.scheduler.FoliaCompat
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerLoginEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * 游戏事件监听：
 * - 聊天转发到 QQ 群（含服务器内 AI 对话前缀）；
 * - 玩家进入/退出播报（进入时补发离线签到奖励）；
 * - 未绑定 QQ 的玩家登录时拒入并发绑定码；
 * - 抑制 Forge 网络日志刷屏。
 *
 * 0.1.5.2：Bot SDK 日志接管统一在运行时初始化（initializeRuntime）中完成
 * （错误上控制台、其余写文件），此处不再重复重定向。
 */
class GameChat : Listener {
    internal companion object {
        @Volatile
        private var bindRegistered = false
        private var retryCount = 0
        private const val MAX_RETRIES = 8

        /**
         * Vault 入账（反射，避免编译期依赖）。
         * 1.5.7.2：入账目标改用 [resolveOfflinePlayer] 按真实玩家 UUID 解析
         * （Geyser/Floodgate 基岩玩家兼容，见该方法注释）。
         */
        @JvmStatic
        fun depositToPlayer(playerName: String, amount: Double): Boolean = try {
            val vault = Bukkit.getPluginManager().getPlugin("Vault") ?: return false
            val offlinePlayer = resolveOfflinePlayer(playerName)
            val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
            val registeredServiceProviderClass = Class.forName("org.bukkit.plugin.RegisteredServiceProvider")
            val servicesManager = Bukkit.getServer().javaClass.getMethod("getServicesManager").invoke(Bukkit.getServer())
            val rsp = servicesManager.javaClass.getMethod("getRegistration", Class::class.java)
                .invoke(servicesManager, economyClass) ?: return false
            val economy = registeredServiceProviderClass.getMethod("getProvider").invoke(rsp) ?: return false
            val result = economyClass
                .getMethod("depositPlayer", org.bukkit.OfflinePlayer::class.java, Double::class.javaPrimitiveType)
                .invoke(economy, offlinePlayer, amount) ?: return false
            try {
                val success = result.javaClass.getMethod("transactionSuccess").invoke(result)
                java.lang.Boolean.TRUE == success
            } catch (_: Throwable) {
                true
            }
        } catch (_: Throwable) {
            false
        }

        /**
         * 解析金币入账目标 OfflinePlayer（1.5.7.2：Geyser-Spigot / Floodgate
         * 基岩版兼容）。
         *
         * 背景：装了 Geyser-Spigot + Floodgate 的服务器，基岩玩家 UUID 是
         * Floodgate UUID（00000000-0000-0009-…，由 XUID 派生，与名字无关），
         * 而 `Bukkit.getOfflinePlayer(名字)` 在玩家离线时返回的是“按名字派生的
         * 离线 UUID”（在线模式还会先查 Mojang 档案缓存，基岩名字必然查不到）
         * —— 两者不同，按名字入账会把金币发进一个“幽灵账户”（Vault 经济插件
         * 按 UUID 记账，玩家真实余额不变，钱凭空消失）。
         *
         * 解析顺序：
         * 1. 在线玩家（含基岩）精确匹配即本人（与旧版 getOfflinePlayer 内部
         *    第一步一致，行为不变）；
         * 2. 离线且已绑定 QQ：用绑定记录里存的 playerUuid（基岩玩家即
         *    Floodgate UUID）入账 —— Java 玩家两种 UUID 一致，结果不变；
         * 3. 离线且未绑定 / 旧记录无 UUID：沿用按名字解析（离线模式
         *    Java 玩家派生 UUID 即本人，与旧版行为一致）。
         */
        @JvmStatic
        fun resolveOfflinePlayer(playerName: String): OfflinePlayer {
            // 1) 在线（含基岩）：精确匹配即本人
            try {
                Bukkit.getPlayerExact(playerName)?.let { return it }
            } catch (_: Throwable) {
            }
            // 2) 已绑定：绑定记录中的真实玩家 UUID
            try {
                val uuidText = QqBindManager.getInstance().findPlayerUuidByName(playerName)
                if (!uuidText.isNullOrEmpty()) {
                    return Bukkit.getOfflinePlayer(UUID.fromString(uuidText))
                }
            } catch (_: Throwable) {
                // 绑定管理器未就绪 / UUID 文本损坏等：退回按名字解析
            }
            // 3) 未绑定或无 UUID 记录：按名字解析（旧版行为）
            return Bukkit.getOfflinePlayer(playerName)
        }

        /** 金额显示（整数去掉小数点）。 */
        @JvmStatic
        fun formatMoney(amount: Double): String =
            if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()
    }

    init {
        try {
            QqBindManager.getInstance()
        } catch (t: Throwable) {
            QqBindManager.logQuiet("QqBindManager 初始化失败: ${safeMessage(t)}")
        }
        suppressForgeNetworkLogs()
        tryRegisterBind()

        var plugin: Plugin? = Bukkit.getPluginManager().getPlugin("KERONGPenguin")
        if (plugin == null) {
            plugin = Bukkit.getPluginManager().getPlugin("KERONGPENGUIN")
        }
        if (plugin != null) {
            try {
                if (plugin is cn.kerong.penguin.spigot.KERONGSpigot) {
                    plugin.getCommand("qq")?.let { command ->
                        val qqCommand = QqCommand(plugin)
                        command.setExecutor(qqCommand)
                        command.setTabCompleter(qqCommand)
                        QqBindManager.logVerbose("[GameChat] /qq 服务器命令已注册")
                    }
                }
            } catch (t: Throwable) {
                QqBindManager.logQuiet("[GameChat] /qq 注册失败: ${safeMessage(t)}")
            }
            // 延迟补注册 /绑定 命令（等待 QQ 客户端启动）
            // 1.5.7.3：经 FoliaCompat 桥接（Folia 全局区域线程延迟）
            FoliaCompat.runSyncLater(plugin, 40L) {
                if (!bindRegistered) tryRegisterBind()
            }
            // 延迟检测经济插件（签到功能依赖）
            FoliaCompat.runSyncLater(plugin, 60L) { checkEconomyForCheckin() }
        }
    }

    /** 尝试向 QQ 客户端注册 /绑定 命令。 */
    @Synchronized
    private fun tryRegisterBind() {
        if (bindRegistered || retryCount >= MAX_RETRIES) return
        retryCount++
        try {
            QClient.registerCommand(BindCommands())
            bindRegistered = true
            // 1.6.7.4：注册例行信息仅写文件留档，不再刷控制台（用户需求）
            QqBindManager.logFileOnly("/绑定 命令已注册 (第 $retryCount 次尝试)")
        } catch (_: Throwable) {
        }
    }

    /** 群聊转发 + 服务器内 AI 对话。 */
    @EventHandler(ignoreCancelled = true)
    fun onChat(event: AsyncPlayerChatEvent) {
        val message = event.message
        val playerName = event.player.name
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            null
        }
        // 服务器内 AI 对话（前缀触发）
        if (manager != null && manager.isServerAiEnabled && manager.isAiEnabled) {
            val prefix = manager.serverAiPrefix()
            if (prefix.isNotEmpty() && message.lowercase().startsWith("$prefix ")) {
                val content = message.substring(prefix.length + 1).trim()
                if (content.isNotEmpty()) {
                    event.isCancelled = true
                    val bukkitPlugin = Bukkit.getPluginManager().getPlugin("KERONGPenguin")
                        ?: return
                    FoliaCompat.runAsync(bukkitPlugin) {
                        try {
                            val reply = AiChat.chat(content, manager, "server", playerName)
                            val outputPrefix = manager.serverAiOutputPrefix()
                            FoliaCompat.runSync(bukkitPlugin) {
                                Bukkit.broadcastMessage("$outputPrefix $reply")
                            }
                        } catch (t: Throwable) {
                            QqBindManager.logVerbose("[AI对话] 服务器调用失败: ${t.message}")
                            val outputPrefix = manager.serverAiOutputPrefix()
                            FoliaCompat.runSync(bukkitPlugin) {
                                Bukkit.broadcastMessage("$outputPrefix AI 对话失败: ${t.message}")
                            }
                        }
                    }
                    return
                }
            }
        }
        QClient.broadcastGameMessage(playerName, message)
    }

    /** 玩家进入：播报 + 补发离线签到奖励 + 未绑定新玩家建档后踢出。 */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val playerName = event.player.name
        val playerUuid = event.player.uniqueId.toString()
        QClient.broadcastPlayerJoin(playerName)
        val plugin = Bukkit.getPluginManager().getPlugin("KERONGPenguin") ?: return

        // 1.6.4：未绑定的新玩家在此踢出（登录期已放行，服务器已建档 UUID/
        // 玩家数据，之后在 QQ 群完成绑定即可正常进服）
        enforceJoinBinding(event.player)

        FoliaCompat.runAsync(plugin) {
            try {
                val manager = QqBindManager.getInstance()
                var quuid = manager.getQuuidByUuid(playerName, playerUuid)
                if (quuid.isNullOrEmpty()) {
                    // 兼容旧版：按纯玩家名查找
                    quuid = manager.getQuuid(playerName)
                }
                if (quuid.isNullOrEmpty()) return@runAsync

                // 补发离线签到奖励
                val pending = manager.takePendingCoins(quuid)
                if (pending > 0) {
                    // 1.5.7.3：入账经桥接（Folia 全局区域线程，Vault 线程安全）
                    FoliaCompat.runSync(plugin) {
                        try {
                            val player = Bukkit.getPlayerExact(playerName)
                            if (player != null && player.isOnline) {
                                val ok = depositToPlayer(playerName, pending)
                                if (ok) {
                                    player.sendMessage("§a[签到奖励] 领取签到奖励 ${formatMoney(pending)} 金币")
                                } else {
                                    QqBindManager.logQuiet("[签到奖励] 给予玩家 $playerName 金币失败（Vault）")
                                }
                            }
                        } catch (t: Throwable) {
                            QqBindManager.logQuiet("[签到奖励] 玩家上线领取异常: ${safeMessage(t)}")
                        }
                    }
                }
            } catch (_: Throwable) {
            }
        }
    }

    /** 玩家退出：播报。 */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        QClient.broadcastPlayerQuit(event.player.name)
    }

    /**
     * 未绑定 QQ 玩家登录拦截（1.6.4 双段策略，与 NeoForge 版同源）：
     * - 老玩家（服务器已有档案）→ 登录期直接拒入，绑定提示直接显示在
     *   “无法连接到服务器”界面（等价原版白名单/封禁提示的显示路径，
     *   不会被加入流程或“服务器连接失败”覆盖）；
     * - 新玩家（服务器还没建档案）→ 放行进入加入流程（服务器创建 UUID
     *   与玩家数据），加入事件里再踢出并展示绑定码（见 [enforceJoinBinding]，
     *   新玩家必须先建档否则绑定后进服无档案）。
     */
    @EventHandler(priority = EventPriority.LOW)
    fun onPlayerLogin(event: PlayerLoginEvent) {
        if (!bindRegistered) {
            tryRegisterBind()
        }
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            return
        }
        if (!manager.isEnabled) return
        val playerName = event.player.name
        val playerUuid = event.player.uniqueId.toString()
        if (manager.isSkipped(playerName)) return

        // 确保绑定记录存在（UUID 机制，含旧数据迁移）
        manager.getOrCreateQuuidByUuid(playerName, playerUuid)
        if (manager.isBoundByUuid(playerName, playerUuid)) return

        if (hasServerPlayerData(event.player)) {
            // 老玩家：登录期拒入（断开界面直接显示绑定提示）
            val code = manager.generateCode(playerName, playerUuid)
            event.disallow(PlayerLoginEvent.Result.KICK_OTHER, manager.formatKickMessage(code, playerName))
        }
        // 新玩家：放行 → onPlayerJoin 的 enforceJoinBinding 拦截
    }

    /**
     * 服务器是否已有该玩家的档案（1.6.4 老玩家判定）：
     * usercache 有记录（hasPlayedBefore）或世界存档目录存在 playerdata/<uuid>.dat。
     * 两者任一成立即视为老玩家（usercache 被清理时文件判定兜底）。
     */
    private fun hasServerPlayerData(player: org.bukkit.entity.Player): Boolean {
        if (player.hasPlayedBefore()) return true
        return try {
            val world = Bukkit.getWorlds().firstOrNull() ?: return false
            java.io.File(world.worldFolder, "playerdata/${player.uniqueId}.dat").exists()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 未绑定玩家加入后踢出（新玩家路径 + 登录期拦截失效的兜底）：
     * 服务器已完成建档（UUID/玩家数据），踢出界面展示专属绑定码。
     */
    private fun enforceJoinBinding(player: org.bukkit.entity.Player) {
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            return
        }
        if (!manager.isEnabled) return
        val playerName = player.name
        val playerUuid = player.uniqueId.toString()
        if (manager.isSkipped(playerName)) return
        manager.getOrCreateQuuidByUuid(playerName, playerUuid)
        if (manager.isBoundByUuid(playerName, playerUuid)) return
        val code = manager.generateCode(playerName, playerUuid)
        player.kickPlayer(manager.formatKickMessage(code, playerName))
    }

    /** 启动时检测经济插件可用性（签到功能提示）。 */
    private fun checkEconomyForCheckin() {
        try {
            val manager = QqBindManager.getInstance()
            if (!manager.isCheckinEnabled) return
            val vault = Bukkit.getPluginManager().getPlugin("Vault")
            if (vault != null && vault.isEnabled) {
                val economyReady = try {
                    val economyClass = Class.forName("net.milkbowl.vault.economy.Economy")
                    val servicesManager = Bukkit.getServer().javaClass.getMethod("getServicesManager").invoke(Bukkit.getServer())
                    val rsp = servicesManager.javaClass.getMethod("getRegistration", Class::class.java)
                        .invoke(servicesManager, economyClass)
                    if (rsp != null) {
                        val registeredServiceProviderClass = Class.forName("org.bukkit.plugin.RegisteredServiceProvider")
                        registeredServiceProviderClass.getMethod("getProvider").invoke(rsp) != null
                    } else false
                } catch (_: Throwable) {
                    false
                }
                if (economyReady) {
                    Bukkit.getConsoleSender().sendMessage("§a[KERONGPenguin] 签到功能已开启，已检测到经济插件 Vault（Economy 就绪）。")
                    PluginFileLog.write("[KERONGPenguin] 签到功能已开启，已检测到经济插件 Vault（Economy 就绪）。")
                } else {
                    Bukkit.getConsoleSender().sendMessage("§c[KERONGPenguin] 签到功能已开启，但未检测到经济前置（Economy 未就绪）。请安装 Vault 及任一经济插件（如 EssentialsX）。")
                    PluginFileLog.write("[KERONGPenguin] 签到功能已开启，但未检测到经济前置（Economy 未就绪）。请安装 Vault 及任一经济插件（如 EssentialsX）。")
                }
            } else {
                Bukkit.getConsoleSender().sendMessage("§c[KERONGPenguin] 签到功能已开启，但未检测到 Vault 经济插件。请安装 Vault 及任一经济插件（如 EssentialsX）。")
                PluginFileLog.write("[KERONGPenguin] 签到功能已开启，但未检测到 Vault 经济插件。请安装 Vault 及任一经济插件（如 EssentialsX）。")
            }
        } catch (t: Throwable) {
            QqBindManager.logQuiet("[GameChat] 签到经济检测失败: ${safeMessage(t)}")
        }
    }

    private fun safeMessage(throwable: Throwable): String =
        throwable.message?.takeIf { it.isNotEmpty() } ?: throwable.toString()

    private fun suppressForgeNetworkLogs() {
        try {
            val loggers = listOf(
                "net.minecraftforge.network", "net.minecraftforge.network.NetworkEvent",
                "net.minecraftforge.network.HandshakeMessages", "net.minecraftforge.fml",
                "net.minecraftforge.fml.network", "fml", "FMLHandshakeHandler",
                "net.minecraft.network", "net.minecraftforge.fml.network.FMLNetworkConstants",
            )
            val logManager = try {
                Class.forName("org.apache.logging.log4j.LogManager")
            } catch (_: Throwable) {
                null
            } ?: return
            val levelClass = try {
                Class.forName("org.apache.logging.log4j.Level")
            } catch (_: Throwable) {
                null
            } ?: return
            val configurator = try {
                Class.forName("org.apache.logging.log4j.core.config.Configurator")
            } catch (_: Throwable) {
                null
            } ?: return
            val setLevel = configurator.getMethod("setLevel", String::class.java, levelClass)
            val warnLevel = levelClass.getField("WARN").get(null)
            for (name in loggers) {
                try {
                    setLevel.invoke(null, name, warnLevel)
                } catch (_: Throwable) {
                }
            }
            QqBindManager.logQuiet("[GameChat] 已抑制 Forge 网络日志")
        } catch (_: Throwable) {
        }
    }
}

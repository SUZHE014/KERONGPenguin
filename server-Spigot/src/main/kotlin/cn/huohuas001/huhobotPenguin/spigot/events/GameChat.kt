package cn.huohuas001.huhobotPenguin.spigot.events

import cn.huohuas001.bot.QClient
import cn.huohuas001.bot.events.commands.BindCommands
import cn.huohuas001.huhobotPenguin.spigot.commands.QqCommand
import cn.huohuas001.huhobotPenguin.spigot.qqbind.AiChat
import cn.huohuas001.huhobotPenguin.spigot.qqbind.QqBindManager
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerLoginEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.Plugin
import java.text.SimpleDateFormat
import java.util.Date

/**
 * 游戏事件监听：
 * - 聊天转发到 QQ 群（含服务器内 AI 对话前缀）；
 * - 玩家进入/退出播报（进入时补发离线签到奖励）；
 * - 未绑定 QQ 的玩家登录时拒入并发绑定码；
 * - 抑制 Forge 网络日志刷屏、重定向 Bot SDK 日志到文件。
 */
class GameChat : Listener {
    internal companion object {
        @Volatile
        private var bindRegistered = false
        private var retryCount = 0
        private const val MAX_RETRIES = 8

        /** Vault 入账（反射，避免编译期依赖）。 */
        @JvmStatic
        fun depositToPlayer(playerName: String, amount: Double): Boolean = try {
            val vault = Bukkit.getPluginManager().getPlugin("Vault") ?: return false
            val offlinePlayer = Bukkit.getOfflinePlayer(playerName)
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
        redirectSdkLogs()
        tryRegisterBind()

        var plugin: Plugin? = Bukkit.getPluginManager().getPlugin("KERONGPenguin")
        if (plugin == null) {
            plugin = Bukkit.getPluginManager().getPlugin("KERONGPENGUIN")
        }
        if (plugin != null) {
            try {
                if (plugin is cn.huohuas001.huhobotPenguin.spigot.HuHoBotSpigot) {
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
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (!bindRegistered) tryRegisterBind()
            }, 40L)
            // 延迟检测经济插件（签到功能依赖）
            Bukkit.getScheduler().runTaskLater(plugin, Runnable { checkEconomyForCheckin() }, 60L)
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
            QqBindManager.logQuiet("/绑定 命令已注册 (第 $retryCount 次尝试)")
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
                    Bukkit.getScheduler().runTaskAsynchronously(bukkitPlugin, Runnable {
                        try {
                            val reply = AiChat.chat(content, manager, "server", playerName)
                            val outputPrefix = manager.serverAiOutputPrefix()
                            Bukkit.getScheduler().runTask(bukkitPlugin, Runnable {
                                Bukkit.broadcastMessage("$outputPrefix $reply")
                            })
                        } catch (t: Throwable) {
                            QqBindManager.logQuiet("[AI对话] 服务器调用失败: ${t.message}")
                            val outputPrefix = manager.serverAiOutputPrefix()
                            Bukkit.getScheduler().runTask(bukkitPlugin, Runnable {
                                Bukkit.broadcastMessage("$outputPrefix AI 对话失败: ${t.message}")
                            })
                        }
                    })
                    return
                }
            }
        }
        QClient.broadcastGameMessage(playerName, message)
    }

    /** 玩家进入：播报 + 补发离线签到奖励。 */
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val playerName = event.player.name
        val playerUuid = event.player.uniqueId.toString()
        QClient.broadcastPlayerJoin(playerName)
        val plugin = Bukkit.getPluginManager().getPlugin("KERONGPenguin") ?: return

        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                val manager = QqBindManager.getInstance()
                var quuid = manager.getQuuidByUuid(playerName, playerUuid)
                if (quuid.isNullOrEmpty()) {
                    // 兼容旧版：按纯玩家名查找
                    quuid = manager.getQuuid(playerName)
                }
                if (quuid.isNullOrEmpty()) return@Runnable

                // 补发离线签到奖励
                val pending = manager.takePendingCoins(quuid)
                if (pending > 0) {
                    Bukkit.getScheduler().runTask(plugin, Runnable {
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
                    })
                }
            } catch (_: Throwable) {
            }
        })
    }

    /** 玩家退出：播报。 */
    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        QClient.broadcastPlayerQuit(event.player.name)
    }

    /** 未绑定 QQ 的玩家登录拒入（UUID 绑定机制）。 */
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

        // 未绑定：生成绑定码并拒入
        val code = manager.generateCode(playerName, playerUuid)
        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, manager.formatKickMessage(code, playerName))
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
                } else {
                    Bukkit.getConsoleSender().sendMessage("§c[KERONGPenguin] 签到功能已开启，但未检测到经济前置（Economy 未就绪）。请安装 Vault 及任一经济插件（如 EssentialsX）。")
                }
            } else {
                Bukkit.getConsoleSender().sendMessage("§c[KERONGPenguin] 签到功能已开启，但未检测到 Vault 经济插件。请安装 Vault 及任一经济插件（如 EssentialsX）。")
            }
        } catch (t: Throwable) {
            QqBindManager.logQuiet("[GameChat] 签到经济检测失败: ${safeMessage(t)}")
        }
    }

    private fun safeMessage(throwable: Throwable): String =
        throwable.message?.takeIf { it.isNotEmpty() } ?: throwable.toString()

    /** 抑制 Forge 网络握手日志刷屏（混合端）。 */
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

    /** 将 Bot SDK 日志重定向到文件（不刷服务器控制台）。 */
    private fun redirectSdkLogs() {
        try {
            val sinkClass = Class.forName("io.github.kloping.qqbot.utils.LoggerImpl\$LogSink")
            val loggerClass = Class.forName("io.github.kloping.qqbot.utils.LoggerImpl")
            val setLogSink = loggerClass.getMethod("setLogSink", sinkClass)
            val sink = java.lang.reflect.Proxy.newProxyInstance(
                sinkClass.classLoader, arrayOf(sinkClass),
            ) { _, method, args ->
                if (method.name == "log" && args != null && args.isNotEmpty()) {
                    val message = args[0]?.toString() ?: ""
                    try {
                        val file = QqBindManager.currentLogFile()
                        if (file != null) {
                            java.io.PrintWriter(java.io.FileWriter(file, true)).use { writer ->
                                val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
                                writer.println("[$timestamp] [Bot] $message")
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }
                null
            }
            setLogSink.invoke(null, sink)
            QqBindManager.logQuiet("[GameChat] 已重定向 Bot SDK 日志到文件")
        } catch (_: Throwable) {
        }
    }
}

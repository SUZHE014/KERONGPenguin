package cn.kerong.bot.events

import cn.kerong.bot.MenuManager
import cn.kerong.bot.QClient
import cn.kerong.bot.provider.BotShared
import cn.kerong.bot.provider.plugin
import cn.kerong.bot.tools.PluginFileLog
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QQ 群自动绑定（1.7.4 第三轮覆盖版新增）。
 *
 * 解决的痛点：旧流程要服主把机器人拉进群后发 /查信息 抄群 OpenId、
 * 手动粘进 config.yml 的 bot.groups 再重启——纯手工、易抄错。
 *
 * 现改为全自动：**bot.groups 未填写（空列表）时，任何在群里与机器人
 * 互动的消息（命令 / 聊天均可）都会触发自动绑定**——把该群 OpenId 写入
 * bot.groups 白名单、保存配置并重载（含 QQ 快捷面板同步）。之后
 * bot.groups 已非空，其他群不再自动加入（要加别的群自行编辑配置或清空
 * 后让新群先互动）。
 *
 * 触发条件全部满足才执行：
 * - 群 OpenId 非空；
 * - bot.groups 为空（用户没填过群 ID）；
 * - 本实例尚未触发过（[triggered] 防抖，多条消息并发到达只处理第一条；
 *   /hbkrg reload 后重置——用户清空 groups 后可再次自动绑定）。
 *
 * 写入路径与 /hbkrg bpngsz 相同（平台 YamlConfiguration.set + saveConfig，
 * 保留配置注释）；重载与面板同步在异步线程执行（磁盘 IO + 网络 IO）。
 */
object AutoGroupBinder {

    /** 本实例是否已触发过自动绑定（reload 时重置）。 */
    private val triggered = AtomicBoolean(false)

    /**
     * 尝试自动绑定（群消息入口调用；不满足条件时零开销直接返回）。
     *
     * @return 无意义（调用方无需区分——自动绑定不拦截消息，
     *   本条消息继续走正常处理流程）
     */
    fun tryBind(groupOpenId: String?) {
        if (groupOpenId.isNullOrEmpty()) return
        if (triggered.get()) return
        val current = try {
            plugin.groupOpenIdList()
        } catch (_: Throwable) {
            return
        }
        if (current.isNotEmpty()) return // 用户已填写群 ID，不自动加群
        if (!triggered.compareAndSet(false, true)) return

        val kerong = try {
            BotShared.instance
        } catch (_: Throwable) {
            null
        } ?: run {
            triggered.set(false)
            return
        }
        val javaPlugin = kerong as? JavaPlugin ?: run {
            triggered.set(false)
            return
        }
        PluginFileLog.infoAndKeep(
            "[自动绑定] bot.groups 未填写，检测到群互动消息——正在自动绑定群 $groupOpenId（写入白名单并重载）"
        )
        kerong.submitAsync {
            try {
                javaPlugin.config.set("bot.groups", listOf(groupOpenId))
                javaPlugin.saveConfig()
                kerong.reloadPluginConfig()
                PluginFileLog.infoAndKeep(
                    "[自动绑定] 群 $groupOpenId 已写入 bot.groups 并重载配置，该群的命令与播报已生效"
                )
                // 启动时 groups 为空，面板同步被跳过；现在有了第一个群，补一次同步
                try {
                    val starter = QClient.starter
                    if (starter != null) {
                        val result = MenuManager.syncGroupPanelsWithResult(starter, listOf(groupOpenId))
                        if (result.ok) {
                            PluginFileLog.infoAndKeep(
                                "[自动绑定] QQ 快捷面板已同步到新群（面板 ${result.panelId}，${result.buttonCount} 个按钮）"
                            )
                        } else {
                            PluginFileLog.warnAndKeep("[自动绑定] QQ 快捷面板同步失败: ${result.error}")
                        }
                    }
                } catch (_: Throwable) {
                }
            } catch (error: Throwable) {
                PluginFileLog.errorAndKeep("[自动绑定] 自动绑定群 $groupOpenId 失败: ${error.message}（可手动把群 OpenId 填入 bot.groups）")
            }
        }
    }

    /** 重置触发标记（/hbkrg reload 时调用：清空 groups 后可再次自动绑定）。 */
    fun reset() = triggered.set(false)
}

package cn.kerong.penguin.spigot.qqbind

import cn.kerong.bot.provider.BotShared
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

/**
 * 经济名称（金币别名）助手（1.6.0 新增）。
 *
 * 配置 economy-alias（顶层键，默认留空 = "金币"）：填写后机器人所有输出中
 * 出现的「金币」二字都会替换为该名称（金币排行榜图片标题、查信息卡片金币项、
 * 签到回复、/帮助、全部消息模板等）。留空时行为与旧版完全一致。
 *
 * 读取实时进行（每次输出都取最新配置，/hb reload 后立即生效，无需重启）。
 */
object EconomyName {

    /** 默认经济名称（未配置别名时使用）。 */
    const val DEFAULT_NAME = "金币"

    /** 当前配置的经济名称（别名留空 / 空白时返回默认「金币」）。 */
    fun current(): String {
        val alias = try {
            (BotShared.instance as? JavaPlugin)?.config?.getString("economy-alias", "")?.trim()
        } catch (_: Throwable) {
            null
        }
        return if (alias.isNullOrEmpty()) DEFAULT_NAME else alias
    }

    /** 是否配置了自定义别名（未配置时文本替换为空操作，保持旧版输出）。 */
    fun isCustom(): Boolean = current() != DEFAULT_NAME

    /**
     * 将文本中的「金币」替换为配置的经济别名（未配置别名时原样返回）。
     * 消息模板渲染出口与图片渲染的标题 / 标签统一走本方法。
     */
    fun apply(text: String): String {
        if (!isCustom() || text.isEmpty()) return text
        return text.replace(DEFAULT_NAME, current())
    }
}

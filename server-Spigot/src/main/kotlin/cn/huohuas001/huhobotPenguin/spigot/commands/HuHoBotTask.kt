package cn.huohuas001.huhobotPenguin.spigot.commands

import cn.huohuas001.bot.tools.Cancelable
import org.bukkit.scheduler.BukkitTask

/**
 * Bukkit 计划任务的取消句柄。
 */
class HuHoBotTask(val task: BukkitTask) : Cancelable {
    override fun cancel() {
        task.cancel()
    }
}

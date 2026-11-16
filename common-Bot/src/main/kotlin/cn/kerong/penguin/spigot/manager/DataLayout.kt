package cn.kerong.penguin.spigot.manager

import org.bukkit.plugin.java.JavaPlugin
import java.io.File

/**
 * 插件数据目录布局（1.7.6.3 覆盖轮重构）：
 *
 * ```
 * 插件文件夹/
 * ├── config.yml          配置文件
 * ├── 提示词.md           AI 提示词（ai.prompt-file）
 * ├── guaipng/            AI 表情包文件夹
 * ├── img/                背景图文件夹（bpng 下载 / 自定义背景）
 * └── globalconfig/       其余所有内容（绑定数据 / 日志 / 模板 / 缓存…）
 *     ├── QUUID/          QQ 绑定数据（含 stats 子目录）
 *     ├── logs/           插件日志（logs/qq、logs/Bot-*.log）
 *     ├── Markdown/       消息模板
 *     ├── cache/          磁盘缓存（头像等）
 *     ├── ai-context.yml  AI 对话上下文
 *     ├── fake-players.json 假人登记
 *     └── openids.yml(.migrated) 旧版 OpenId 目录数据
 * ```
 *
 * 根目录只留用户常碰的四样（配置 / 提示词 / 表情包 / 背景图），
 * 其余全部收进 globalconfig/ 子目录。
 *
 * 升级迁移（[migrateToGlobalLayout]，幂等）：旧版散在根目录的上述
 * 数据在启动时自动挪入 globalconfig/——目录整名 rename（目标不存在
 * 时原子完成）；目标已存在（极少见的重复迁移）则逐项合并、同名跳过。
 * 全新安装无事发生。
 */
object DataLayout {

    /** 杂项数据统一子目录名。 */
    const val GLOBAL_DIR = "globalconfig"

    /** 迁移的子目录名单（旧版位于插件根目录）。 */
    private val MOVE_DIRS = listOf("QUUID", "logs", "Markdown", "cache")

    /** 迁移的散文件名单（旧版位于插件根目录）。 */
    private val MOVE_FILES = listOf(
        "ai-context.yml", "fake-players.json", "stats.yml", "stats.yml.migrated",
        "openids.yml", "openids.yml.migrated",
    )

    /** 杂项数据根目录（插件数据目录下的 globalconfig/）。 */
    fun globalDir(dataFolder: File): File = File(dataFolder, GLOBAL_DIR)

    /**
     * 解析 globalconfig 下的路径（父目录不存在时自动创建）。
     * @param dataFolder 插件数据目录（null = 未就绪，返回 null）
     */
    fun resolve(dataFolder: File?, relative: String): File? {
        if (dataFolder == null) return null
        val target = File(globalDir(dataFolder), relative)
        return try {
            val parent = target.parentFile
            if (parent != null && !parent.isDirectory) parent.mkdirs()
            target
        } catch (_: Throwable) {
            target
        }
    }

    /**
     * 旧结构 → 新结构迁移（幂等，必须在任何数据文件读写之前执行——
     * ConfigManager.initialize 的最早阶段调用）。
     * @return true = 本次实际迁移了内容
     */
    fun migrateToGlobalLayout(plugin: JavaPlugin): Boolean {
        val root = plugin.dataFolder
        val moved = migrate(root)
        if (moved) {
            try {
                plugin.logger.info(
                    "数据目录已重构：绑定数据 / 日志 / 消息模板 / 缓存等已移入 $GLOBAL_DIR/ 文件夹" +
                        "（config.yml、提示词文件、guaipng 表情包与 img 背景图保持在外）"
                )
            } catch (_: Throwable) {
            }
        }
        return moved
    }

    /**
     * 迁移核心（纯函数，冒烟直接验证）：把根目录下的旧数据（[MOVE_DIRS]
     * 目录与 [MOVE_FILES] 散文件）挪入 globalconfig/ 子目录。
     * 幂等：目标已存在则目录逐项合并（同名跳过）、文件保留新位置。
     */
    fun migrate(root: File): Boolean {
        if (!root.isDirectory) return false
        var moved = false
        try {
            for (name in MOVE_DIRS + MOVE_FILES) {
                val old = File(root, name)
                if (!old.exists()) continue
                val global = globalDir(root)
                if (!global.isDirectory) global.mkdirs()
                val target = File(global, name)
                if (!target.exists()) {
                    if (old.renameTo(target)) moved = true
                    continue
                }
                // 目标已存在：目录逐项合并（同名跳过），文件保留新位置
                if (old.isDirectory) {
                    if (mergeDirInto(old, target)) moved = true
                } else {
                    // 罕见：两边都有同名文件，保留 globalconfig 里的
                    if (old.delete()) moved = true
                }
            }
        } catch (_: Throwable) {
            // 迁移失败不影响启动：旧路径读不到数据会按空数据处理
        }
        return moved
    }

    /** 把 source 目录里的项逐个挪入 target（同名跳过），完成后删除空源目录。 */
    private fun mergeDirInto(source: File, target: File): Boolean {
        var movedAny = false
        val items = source.listFiles() ?: return false
        for (item in items) {
            val dest = File(target, item.name)
            if (dest.exists()) continue
            if (item.renameTo(dest)) movedAny = true
        }
        val remaining = source.listFiles()?.size ?: 0
        if (remaining == 0) source.delete()
        return movedAny
    }
}

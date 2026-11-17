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
 * 时原子完成）；目标已存在（重复迁移/降级后重新升级）则逐项合并，
 * 同名冲突按修改时间取较新内容，另一份删除（1.7.6.3 四次覆盖轮
 * 修复：旧版同名直接跳过，旧文件与旧目录永久残留）。rename 失败
 * （Windows 文件占用等）时退化为复制后删除，复制/删除都失败才保留
 * 旧位置，下次启动重试（幂等自愈）。全新安装无事发生。
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
        // 迁移后根目录残留提示（同名冲突删除失败 / 复制失败等）：
        // 提示用户有旧数据没能自动清掉，下次启动会自动重试
        try {
            val residual = (MOVE_DIRS + MOVE_FILES)
                .filter { File(root, it).exists() }
            if (residual.isNotEmpty()) {
                plugin.logger.warning(
                    "数据目录迁移后有残留（${residual.joinToString(", ")}）：" +
                        "可能是文件被占用，旧数据保留在原位置未删除，重启后会自动重试；" +
                        "确认 globalconfig/ 数据完整后也可手动删除这些旧项"
                )
            }
        } catch (_: Throwable) {
        }
        return moved
    }

    /**
     * 迁移核心（纯函数，冒烟直接验证）：把根目录下的旧数据（[MOVE_DIRS]
     * 目录与 [MOVE_FILES] 散文件）挪入 globalconfig/ 子目录。
     * 幂等：目标已存在则目录逐项合并（同名按修改时间取新，另一份删除）、
     * 文件同名同样取新；迁移后根目录不再残留旧数据。
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
                    if (moveWithFallback(old, target)) moved = true
                    continue
                }
                // 目标已存在：逐项合并（同名取新，另一份删除）
                if (old.isDirectory) {
                    if (mergeDirInto(old, target)) moved = true
                } else {
                    if (resolveSameName(old, target)) moved = true
                }
            }
        } catch (_: Throwable) {
            // 迁移失败不影响启动：旧路径读不到数据会按空数据处理
        }
        return moved
    }

    /**
     * 挪动单个文件 / 目录，renameTo 失败（Windows 文件被占用 / 杀毒扫描
     * 等）时退化为复制后删除——修复旧版 rename 失败即静默放弃，旧文件
     * 永久残留在根目录的问题。
     * @return true = 旧位置已清掉（rename 或复制+删除成功）
     */
    private fun moveWithFallback(old: File, target: File): Boolean {
        if (old.renameTo(target)) return true
        return try {
            if (old.isDirectory) {
                old.copyRecursively(target, overwrite = false)
                old.deleteRecursively()
            } else {
                old.copyTo(target, overwrite = false)
                old.delete()
            }
        } catch (_: Throwable) {
            // 数据安全优先：复制失败保留旧位置，下次启动重试（幂等）
            false
        }
    }

    /**
     * 同名冲突（旧位置与 globalconfig 都有同名文件 / 目录）：按修改时间
     * 取较新内容——旧位置较新（降级期间写入的数据）先覆盖到新位置，
     * 然后删除旧位置；新位置较新或相等直接删旧位置。删除失败保留
     * （下次启动重试；覆盖后的新文件 mtime 为当下，重试时自然走删除分支）。
     * 旧版在这里直接跳过：同名文件永久留在根目录，目录也删不掉。
     * @return true = 旧位置已清掉
     */
    private fun resolveSameName(old: File, target: File): Boolean {
        return try {
            if (old.lastModified() > target.lastModified()) {
                if (old.isDirectory) {
                    old.copyRecursively(target, overwrite = true)
                } else {
                    old.copyTo(target, overwrite = true)
                }
            }
            if (old.isDirectory) old.deleteRecursively() else old.delete()
        } catch (_: Throwable) {
            false
        }
    }

    /** 把 source 目录里的项逐个挪入 target（同名取新，另一份删除），完成后删除空源目录。 */
    private fun mergeDirInto(source: File, target: File): Boolean {
        var movedAny = false
        val items = source.listFiles() ?: return false
        for (item in items) {
            val dest = File(target, item.name)
            if (!dest.exists()) {
                if (moveWithFallback(item, dest)) movedAny = true
            } else {
                // 同名冲突：按修改时间取新（旧版跳过导致残留，四次覆盖轮修复）
                if (resolveSameName(item, dest)) movedAny = true
            }
        }
        val remaining = source.listFiles()?.size ?: 0
        if (remaining == 0) source.delete()
        return movedAny
    }
}

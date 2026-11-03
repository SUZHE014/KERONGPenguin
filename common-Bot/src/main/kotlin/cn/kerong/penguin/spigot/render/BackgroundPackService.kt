package cn.kerong.penguin.spigot.render

import cn.kerong.bot.provider.BotShared
import cn.kerong.bot.tools.PluginFileLog
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONObject
import org.bukkit.plugin.java.JavaPlugin
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

/**
 * 背景图片包服务（1.7.1 新增）：/hbkrg bpng 命令的共享实现，
 * Spigot 插件版与 NeoForge 模组版命令层共用同一套逻辑。
 *
 * 功能：
 * - /hbkrg bpng —— 列出官网当前可下载的背景图片包（清单 manifest.json）；
 * - /hbkrg bpng <包ID> —— 下载图片包全部图片到数据目录 img/ 文件夹：
 *   逐张下载并显示进度，期间可再次执行同一命令或 /hbkrg bpng stop 中断；
 *   单张下载失败自动跳过继续下一张；已存在且校验一致的文件自动跳过
 *   （中断后重新执行即续传）；
 * - /hbkrg bpngsz yes / no —— 一键把全部五张卡片（查信息 / 查在线 /
 *   查性能 / 在线排行榜 / 财富榜）的背景开关切换为 img/ 自定义背景
 *   或恢复内置背景，写入配置后自动重载（等效 /hbkrg reload 的配置段）。
 *
 * 下载源（1.7.2 起单一官网线路）：
 * https://kerong.xin/penguin/bpng/（官网主源；1.7.1 的 git 仓库 raw
 * 双镜像（gh-proxy 中转 + 直连）已按用户要求删除——镜像与官网内容
 * 无法保持同步时用户会下载到旧图，且 git 仓库不应背负 58MB 图片）。
 *
 * 说明：官网 IP 直连不做下载源——Java HttpURLConnection 连 IP 地址的
 * HTTPS URL 时不发送 SNI，本站 nginx 无 SNI 直接握手失败（且 Host 头
 * 属 JDK 受限头、setRequestProperty 会被静默忽略），该路径在 Java 客户端
 * 下不可达（详见 AI-KNOWLEDGE §6）。
 *
 * 清单格式（由 scripts/make_bpng_manifest.py 或 bpng_upload.py 上传工具生成；
 * 1.7.2 起每包新增 count（文件数）与 bytes（总字节数）两个展示字段，
 * 旧清单无这两个字段时自动按 files 数组统计——向后兼容）：
 * {"packs":[{"id":"wl1","name":"蔚蓝档案","description":"…",
 *   "count":29,"bytes":60900000,
 *   "files":[{"name":"xxx.jpg","size":123,"md5":"32位十六进制"}]}]}
 * 插件拉取清单后动态渲染张数与体积（字节换算 MB 保留 1 位小数），
 * 不再依赖任何硬编码数字。
 *
 * 完整性：每张图片按清单 md5 校验，校验失败换下一源重试，全部源失败
 * 才算该张失败（跳过）；落盘走临时文件 + 原子改名，不会出现半张图片
 * 被 img/ 目录扫描误用（.part 扩展名不在背景候选名单内）。
 */
object BackgroundPackService {

    /** 清单文件名（相对各下载源根路径）。 */
    private const val MANIFEST_NAME = "manifest.json"

    /** 单文件下载连接超时（毫秒）。 */
    private const val CONNECT_TIMEOUT = 10_000

    /** 单文件读取超时（毫秒）。 */
    private const val READ_TIMEOUT = 30_000

    /** 单文件大小上限（防御性：清单被篡改时不至于拉爆内存）。 */
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024

    /** 同一源连续失败该次数后，本次下载的后续文件不再优先尝试它。 */
    private const val SOURCE_DEMOTE_AFTER = 3

    /** 背景图片的合法扩展名（与渲染端 img/ 候选过滤一致）。 */
    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "bmp", "gif")

    /**
     * 下载源列表（1.7.2 起仅官网单一来源；构造为可注入形参便于独立 JVM
     * 冒烟测试，默认 [DOWNLOAD_SOURCES]）。
     */
    val DOWNLOAD_SOURCES = listOf(
        "https://kerong.xin/penguin/bpng/",
    )

    /** bpngsz 一键切换的五张卡片背景开关键。 */
    val BACKGROUND_SWITCH_KEYS = listOf(
        "render.info-card-builtin-background",
        "render.online-list-builtin-background",
        "render.performance.builtin-background",
        "leaderboard.builtin-background",
        "coins-leaderboard.builtin-background",
    )

    // ---------- 数据模型 ----------

    /** 图片包内单张图片的清单条目。 */
    data class PackFile(val name: String, val size: Long, val md5: String)

    /** 图片包清单条目。 */
    data class PackInfo(
        val id: String,
        val name: String,
        val description: String,
        val files: List<PackFile>,
        /** 清单里显式记录的文件数（1.7.2 新增；旧清单无此字段按 files 统计）。 */
        val declaredCount: Int,
        /** 清单里显式记录的总字节数（1.7.2 新增；旧清单无此字段按 files 统计）。 */
        val declaredBytes: Long,
    ) {
        /** 展示用张数：优先清单显式声明值，缺失回退按 files 统计（向后兼容）。 */
        val count: Int get() = if (declaredCount > 0) declaredCount else files.size

        /** 展示用总体积：优先清单显式声明值，缺失回退按 files 统计（向后兼容）。 */
        val totalBytes: Long get() = if (declaredBytes > 0) declaredBytes else files.sumOf { it.size }
    }

    /** 一次下载的汇总结果。 */
    data class DownloadSummary(
        val pack: PackInfo,
        val downloaded: Int,
        val skippedExisting: Int,
        val failed: Int,
        val bytes: Long,
        val interrupted: Boolean,
        val failedFiles: List<String>,
    )

    // ---------- 图片下载功能开关（1.7.2 新增配置 image-download.enabled） ----------

    /**
     * 图片下载功能是否开启（image-download.enabled，默认 true）。
     *
     * 关闭时 /hbkrg bpng 与 /hbkrg bpng <包ID> 入口直接回复
     * 「管理员已关闭图片下载功能」并中止；/hbkrg bpngsz 不受影响
     * （只是切换背景开关、不下载文件）。实时读取（reload 即生效）。
     */
    fun isImageDownloadEnabled(): Boolean {
        return try {
            val javaPlugin = BotShared.instance as? JavaPlugin ?: return true
            javaPlugin.config.getBoolean("image-download.enabled", true)
        } catch (_: Throwable) {
            true
        }
    }

    /** bpng 入口的开关拦截提示行（双端命令层共用）。 */
    fun imageDownloadDisabledLines(): List<String> = listOf(
        "§c管理员已关闭图片下载功能。",
        "§7如需下载背景图片包请联系服务器管理员（配置 image-download.enabled）。",
    )

    // ---------- 进行中状态（中断用） ----------

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var activeId: String? = null

    @Volatile
    private var activeName: String? = null

    @Volatile
    private var activeDone = 0

    @Volatile
    private var activeTotal = 0

    /** 是否有图片包下载正在进行。 */
    fun hasActiveDownload(): Boolean = activeId != null

    /** 当前下载的简要进度（无进行中下载返回 null），如「蔚蓝档案」12/29。 */
    fun activeDownloadBrief(): String? {
        val id = activeId ?: return null
        val name = activeName ?: id
        return "「$name」$activeDone/$activeTotal"
    }

    /** 请求中断当前下载（无进行中下载返回 false）。 */
    fun requestCancel(): Boolean {
        if (activeId == null) return false
        cancelRequested = true
        return true
    }

    // ---------- 清单 ----------

    /**
     * 拉取并解析图片包清单（阻塞网络调用，必须在异步线程执行）。
     *
     * @return 全部图片包；任一源都没拿到有效清单时返回 null
     */
    fun fetchPacks(sources: List<String> = DOWNLOAD_SOURCES): List<PackInfo>? {
        var lastError: Throwable? = null
        for (base in sources) {
            try {
                val body = httpGet(base, MANIFEST_NAME)
                val packs = parseManifest(String(body, Charsets.UTF_8))
                if (packs.isNotEmpty()) return packs
            } catch (error: Throwable) {
                lastError = error
                PluginFileLog.write("[图片包] 清单源不可达（$base）：${error.javaClass.name}: ${error.message}")
            }
        }
        if (lastError != null) {
            PluginFileLog.write("[图片包] 全部清单源失败，最后错误：${lastError.javaClass.name}: ${lastError.message}")
        }
        return null
    }

    /**
     * 解析清单 JSON（公开便于独立 JVM 冒烟测试）。
     *
     * 容错：包 ID / 文件名不合规（路径穿越风险字符）、md5 非法、无文件
     * 的包整条跳过，不影响其余条目。
     */
    fun parseManifest(text: String): List<PackInfo> {
        val root = try {
            JSON.parseObject(text)
        } catch (_: Throwable) {
            null
        } ?: return emptyList()
        val packs = root.getJSONArray("packs") ?: return emptyList()
        val result = ArrayList<PackInfo>()
        for (item in packs) {
            val obj = item as? JSONObject ?: continue
            val id = obj.getString("id")?.trim().orEmpty()
            if (!isSafeToken(id)) continue
            val name = obj.getString("name")?.trim().takeUnless { it.isNullOrEmpty() } ?: id
            val description = obj.getString("description")?.trim().orEmpty()
            val files = ArrayList<PackFile>()
            val fileArray = obj.getJSONArray("files") ?: continue
            for (entry in fileArray) {
                val fileObj = entry as? JSONObject ?: continue
                val fileName = fileObj.getString("name")?.trim().orEmpty()
                if (!isSafeFileName(fileName)) continue
                val md5 = fileObj.getString("md5")?.trim().orEmpty().lowercase()
                if (!md5.matches(Regex("^[0-9a-f]{32}$"))) continue
                files.add(PackFile(fileName, fileObj.getLongValue("size"), md5))
            }
            if (files.isNotEmpty()) {
                // 1.7.2：读取清单显式声明的张数与体积（展示用）；
                // 旧清单（或非法值）自动回退按 files 统计——向后兼容
                val declaredCount = obj.getIntValue("count")
                val declaredBytes = obj.getLongValue("bytes")
                result.add(PackInfo(id, name, description, files, declaredCount, declaredBytes))
            }
        }
        return result
    }

    // ---------- 命令层入口 ----------

    /**
     * /hbkrg bpng 的清单列表输出（阻塞网络调用，异步线程执行）。
     */
    fun listPacksLines(sources: List<String> = DOWNLOAD_SOURCES): List<String> {
        val packs = fetchPacks(sources)
        if (packs == null) {
            return listOf(
                "§c获取图片包清单失败（官网暂不可达）。",
                "§7请检查服务器网络后重试；失败明细见 logs/qq 日志。",
            )
        }
        val lines = ArrayList<String>()
        lines.add("§6===== 可下载背景图片包 =====")
        for (pack in packs) {
            lines.add("§e${pack.id} §f- ${pack.name} §7（${pack.count} 张，约 ${formatMb(pack.totalBytes)}）")
            if (pack.description.isNotEmpty()) {
                lines.add("§7  ${pack.description}")
            }
        }
        lines.add("§7下载: /hbkrg bpng <包ID>（如 /hbkrg bpng ${packs.firstOrNull()?.id ?: "wl1"}）")
        lines.add("§7下载中再次执行同一命令或 /hbkrg bpng stop 可中断；已下载的文件续传时自动跳过")
        return lines
    }

    /**
     * /hbkrg bpng <包ID> 的完整下载流程（阻塞网络调用，异步线程执行）：
     * 拉清单 → 定位包 → 逐张下载（进度经 [progress] 回调，调用方负责
     * 转回主线程发送）→ 汇总提示。包不存在 / 清单失败也经 [progress]
     * 输出对应提示。
     */
    fun downloadPackById(
        dataDirectory: File,
        packId: String,
        sources: List<String> = DOWNLOAD_SOURCES,
        progress: (String) -> Unit,
    ) {
        val packs = fetchPacks(sources)
        if (packs == null) {
            progress("§c获取图片包清单失败（官网暂不可达），本次未开始下载。")
            progress("§7请检查服务器网络后重试；失败明细见 logs/qq 日志。")
            return
        }
        val pack = packs.firstOrNull { it.id.equals(packId, ignoreCase = true) }
        if (pack == null) {
            progress("§c未找到图片包「$packId」。§7执行 /hbkrg bpng 查看可下载的图片包")
            return
        }
        try {
            val summary = downloadPack(dataDirectory, pack, sources, progress)
            for (line in summaryLines(summary)) {
                progress(line)
            }
        } catch (error: Throwable) {
            // 并发启动第二份下载（check 失败）等异常：如实提示，不静默吞进异步线程
            progress("§c下载未能完成：${error.message ?: error.javaClass.simpleName}")
            PluginFileLog.write("[图片包] 下载异常中断：${error.javaClass.name}: ${error.message}")
        }
    }

    /**
     * /hbkrg bpngsz yes|no 的一键切换流程（阻塞磁盘 IO，异步线程执行）：
     * 写入五个背景开关 → 保存 → 自动重载配置（等效 /hbkrg reload 的
     * 配置段）。img/ 目录没有图片时追加提醒（卡片会自动回退内置背景）。
     *
     * @param useCustom true = 全部改用 img/ 自定义背景；false = 恢复内置背景
     */
    fun switchBackgrounds(useCustom: Boolean): List<String> {
        val kerong = BotShared.instance
        val javaPlugin = kerong as? JavaPlugin
            ?: return listOf("§c插件运行时未就绪，本次未修改配置。")
        val config = javaPlugin.config
        for (key in BACKGROUND_SWITCH_KEYS) {
            config.set(key, !useCustom)
        }
        try {
            javaPlugin.saveConfig()
        } catch (error: Throwable) {
            PluginFileLog.write("[图片包] 配置保存失败：${error.javaClass.name}: ${error.message}")
            return listOf("§c配置文件保存失败：${error.message ?: error.javaClass.simpleName}§7（明细见 logs/qq 日志）")
        }
        // 用户要求：改完配置自动重载（与 /hbkrg reload 的配置路径一致）
        try {
            kerong.reloadPluginConfig()
        } catch (error: Throwable) {
            PluginFileLog.write("[图片包] 自动重载异常：${error.javaClass.name}: ${error.message}")
        }
        val lines = ArrayList<String>()
        if (useCustom) {
            lines.add("§a已一键切换：全部卡片使用 img/ 自定义背景图（5 项设置已写入并自动重载）")
            lines.add("§7涉及卡片: 查信息 / 查在线 / 查性能 / 在线排行榜 / 财富榜")
            val imgDir = File(javaPlugin.dataFolder, "img")
            val hasImage = imgDir.isDirectory && imgDir.listFiles()
                ?.any { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS } == true
            if (!hasImage) {
                lines.add("§e注意: §7img/ 目录当前没有图片，卡片会自动回退内置背景——可先执行 /hbkrg bpng 下载图片包")
            }
        } else {
            lines.add("§a已一键恢复：全部卡片使用内置背景图（5 项设置已写入并自动重载）")
            lines.add("§7img/ 文件夹里的图片保留不删，随时可再执行 /hbkrg bpngsz yes 切回")
        }
        return lines
    }

    // ---------- 下载本体 ----------

    /**
     * 下载一个图片包的全部图片到 img/ 文件夹（阻塞调用，异步线程执行）。
     *
     * - 逐张顺序下载，进度行经 [progress] 回调；
     * - 每张依次尝试 [sources] 各源（网络级失败的源连续 3 次后本次降级
     *   到队尾之后不再优先），全部源失败则跳过该张继续下一张；
     * - 已存在且 md5 与清单一致的文件直接跳过（续传语义）；
     * - 落盘走 name.part 临时文件 + 原子改名；
     * - 下载中（含单张读取中途）检测中断标记，中断即停。
     */
    fun downloadPack(
        dataDirectory: File,
        pack: PackInfo,
        sources: List<String> = DOWNLOAD_SOURCES,
        progress: (String) -> Unit,
    ): DownloadSummary {
        check(activeId == null) { "已有图片包下载正在进行" }
        val imgDir = File(dataDirectory, "img")
        try {
            imgDir.mkdirs()
        } catch (_: Throwable) {
        }

        cancelRequested = false
        activeId = pack.id
        activeName = pack.name
        activeDone = 0
        activeTotal = pack.files.size

        val order = java.util.ArrayList(sources)
        val networkErrors = HashMap<String, Int>()
        var downloaded = 0
        var skippedExisting = 0
        var failed = 0
        var bytes = 0L
        val failedFiles = ArrayList<String>()

        try {
            progress(
                "§e开始下载图片包「§f${pack.name}§e」§7（${pack.count} 张，约 ${formatMb(pack.totalBytes)}）" +
                    "…中断: 再次执行本命令或 /hbkrg bpng stop",
            )
            for ((index, file) in pack.files.withIndex()) {
                val label = "§7[${index + 1}/${pack.files.size}] §f${file.name} §7（${formatMb(file.size)}）"
                if (cancelRequested) break
                val target = File(imgDir, file.name)
                // 已存在且校验一致 → 跳过（断点续传）
                if (target.isFile && md5OfFile(target) == file.md5) {
                    skippedExisting++
                    activeDone = index + 1
                    progress("$label§7已存在，跳过")
                    continue
                }
                val data = downloadFileWithFallback(pack.id, file, order, networkErrors)
                if (data == null) {
                    failed++
                    failedFiles.add(file.name)
                    activeDone = index + 1
                    progress("$label§c下载失败§7（$lastFileFailReason），已跳过")
                    continue
                }
                if (!writeAtomically(imgDir, file.name, data)) {
                    failed++
                    failedFiles.add(file.name)
                    activeDone = index + 1
                    progress("$label§c写入失败，已跳过")
                    continue
                }
                downloaded++
                bytes += data.size
                activeDone = index + 1
                progress("$label§a已下载")
            }
        } catch (_: DownloadCancelledException) {
            // 用户中断：跳出循环走汇总（进行中的那张不计失败）
        } finally {
            // 中断标志随本次下载结束一并清除（冒烟实测教训：残留的 true 会让
            // 后续 fetchPacks 的 httpGet 读循环首检即抛，清单获取永久失败）
            cancelRequested = false
            activeId = null
            activeName = null
        }
        val interrupted = cancelRequested
        return DownloadSummary(pack, downloaded, skippedExisting, failed, bytes, interrupted, failedFiles)
    }

    /** 单张图片的失败原因（供进度行展示；仅下载线程内读写）。 */
    private var lastFileFailReason: String = "未知"

    /**
     * 依次尝试各源下载单张图片并做 md5 校验。
     *
     * @return 校验通过的字节；全部源失败返回 null（原因记入 [lastFileFailReason]）
     */
    private fun downloadFileWithFallback(
        packId: String,
        file: PackFile,
        order: MutableList<String>,
        networkErrors: MutableMap<String, Int>,
    ): ByteArray? {
        val path = "$packId/${file.name}"
        var lastReason = "未知"
        for (base in order.toList()) {
            if (cancelRequested) return null
            try {
                val data = httpGet(base, path)
                if (md5Of(data) != file.md5) {
                    lastReason = "校验失败"
                    noteSourceError(base, networkErrors, order)
                    PluginFileLog.write("[图片包] ${file.name} 在 $base 校验失败（md5 不符），换下一源")
                    continue
                }
                networkErrors.remove(base)
                return data
            } catch (error: DownloadCancelledException) {
                lastReason = "已中断"
                throw error
            } catch (error: IOException) {
                lastReason = error.message?.take(40) ?: "网络异常"
                noteSourceError(base, networkErrors, order)
                PluginFileLog.write("[图片包] ${file.name} 在 $base 失败：${error.message}")
            }
        }
        lastFileFailReason = lastReason
        return null
    }

    /** 源失败计数；连续 [SOURCE_DEMOTE_AFTER] 次网络级失败后从本次队列移除（保留最后一个源）。 */
    private fun noteSourceError(base: String, networkErrors: MutableMap<String, Int>, order: MutableList<String>) {
        val count = (networkErrors[base] ?: 0) + 1
        networkErrors[base] = count
        if (count >= SOURCE_DEMOTE_AFTER && order.size > 1) {
            order.remove(base)
            PluginFileLog.write("[图片包] 源 $base 连续 $count 次失败，本次下载后续文件不再尝试")
        }
    }

    /** 下载汇总的用户提示行。 */
    private fun summaryLines(summary: DownloadSummary): List<String> {
        val pack = summary.pack
        if (summary.interrupted) {
            return listOf(
                "§e已中断下载「${pack.name}」: §f完成 ${summary.downloaded + summary.skippedExisting}/${pack.files.size} 张" +
                    "§7（新下载 ${summary.downloaded}，已存在跳过 ${summary.skippedExisting}）",
                "§7重新执行 /hbkrg bpng ${pack.id} 可续传，已完成的文件自动跳过",
            )
        }
        val lines = ArrayList<String>()
        lines.add(
            "§a图片包「${pack.name}」下载完成: §f成功 ${summary.downloaded} 张，已存在跳过 ${summary.skippedExisting} 张" +
                "（约 ${formatMb(summary.bytes)}）",
        )
        if (summary.failed > 0) {
            val names = summary.failedFiles.take(5).joinToString("、")
            val more = if (summary.failedFiles.size > 5) " 等 ${summary.failedFiles.size} 张" else ""
            lines.add("§c失败 ${summary.failed} 张§7（$names$more）——可重新执行 /hbkrg bpng ${pack.id} 只补失败与缺失的文件")
        }
        lines.add("§7图片保存在 img/ 文件夹，渲染时随机轮换")
        lines.add("§e提示: §f执行 /hbkrg bpngsz yes 一键把全部卡片切换为 img/ 自定义背景")
        return lines
    }

    // ---------- 工具 ----------

    /** 用户主动中断的内部信号（不写日志，不算错误）。 */
    private class DownloadCancelledException : IOException("interrupted by user")

    /**
     * 单次 HTTP GET（64KB 分块读取，读循环内检查中断标记，超限熔断）。
     */
    private fun httpGet(base: String, path: String): ByteArray {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(base + path).openConnection() as HttpURLConnection
            connection.connectTimeout = CONNECT_TIMEOUT
            connection.readTimeout = READ_TIMEOUT
            connection.setRequestProperty(
                "User-Agent",
                "KERONGPenguin-Bpng/${BotShared.instance?.pluginVersion ?: "1.0"}",
            )
            val code = connection.responseCode
            if (code != 200) throw IOException("HTTP $code")
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            connection.inputStream.use { stream ->
                while (true) {
                    if (cancelRequested) throw DownloadCancelledException()
                    val read = stream.read(chunk)
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    if (buffer.size() > MAX_FILE_BYTES) throw IOException("文件超过大小上限")
                }
            }
            return buffer.toByteArray()
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Throwable) {
            }
        }
    }

    /** 临时文件 + 原子改名落盘（半张图片不会进入 img/ 候选名单）。 */
    private fun writeAtomically(imgDir: File, name: String, data: ByteArray): Boolean {
        val target = File(imgDir, name)
        val temp = File(imgDir, "$name.part")
        return try {
            temp.writeBytes(data)
            if (target.isFile && !target.delete()) throw IOException("旧文件无法删除")
            if (!temp.renameTo(target)) throw IOException("改名失败")
            true
        } catch (error: Throwable) {
            PluginFileLog.write("[图片包] $name 落盘失败：${error.javaClass.name}: ${error.message}")
            try {
                temp.delete()
            } catch (_: Throwable) {
            }
            false
        }
    }

    /** 字节数组的 md5（小写十六进制）。 */
    fun md5Of(data: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(data)
        val builder = StringBuilder(32)
        for (byte in digest) {
            builder.append(Character.forDigit((byte.toInt() ushr 4) and 0xF, 16))
            builder.append(Character.forDigit(byte.toInt() and 0xF, 16))
        }
        return builder.toString()
    }

    /** 文件的 md5（读失败返回 null）。 */
    fun md5OfFile(file: File): String? {
        return try {
            file.inputStream().use { stream ->
                val digest = MessageDigest.getInstance("MD5")
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val read = stream.read(chunk)
                    if (read < 0) break
                    digest.update(chunk, 0, read)
                }
                val bytes = digest.digest()
                val builder = StringBuilder(32)
                for (byte in bytes) {
                    builder.append(Character.forDigit((byte.toInt() ushr 4) and 0xF, 16))
                    builder.append(Character.forDigit(byte.toInt() and 0xF, 16))
                }
                builder.toString()
            }
        } catch (_: Throwable) {
            null
        }
    }

    /** 包 ID 等短令牌的合法性（字母数字开头，仅含 . _ -，禁路径穿越）。 */
    fun isSafeToken(token: String): Boolean =
        token.isNotEmpty() && token.length <= 64 &&
            token.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")) && !token.contains("..")

    /** 图片文件名的合法性（令牌 + 图片扩展名）。 */
    fun isSafeFileName(name: String): Boolean =
        isSafeToken(name) && name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    /** 字节数的人类可读 MB（一位小数）。 */
    fun formatMb(bytes: Long): String =
        String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0)
}

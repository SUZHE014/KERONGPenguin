package cn.huohuas001.huhobotPenguin.spigot.qqbind

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 联网获取北京时间工具（UTC+8），不依赖服务器本地时间。
 * 结果缓存 5 分钟；联网失败时回退到本地 Asia/Shanghai 时区时间。
 */
object BeijingTimeUtil {
    private val BEIJING_TZ: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")
    private val EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "BeijingTimeFetcher").apply { isDaemon = true }
    }

    @Volatile
    private var cachedDate: String = ""

    @Volatile
    private var cachedAt: Long = 0

    private const val CACHE_TTL = 5 * 60 * 1000L

    /**
     * 获取北京当前日期（yyyy-MM-dd）。
     * 异步联网获取，最多等待 3 秒；超时则用上次缓存或服务器时间兜底。
     */
    fun getBeijingDate(): String {
        val now = System.currentTimeMillis()
        if (cachedDate.isNotEmpty() && now - cachedAt < CACHE_TTL) {
            return cachedDate
        }
        var date = fetchOnlineDate()
        if (date.isNullOrEmpty()) {
            date = localBeijingDate()
        }
        cachedDate = date
        cachedAt = now
        return date
    }

    /** 计算给定日期的次日（yyyy-MM-dd）。 */
    fun nextDay(date: String): String {
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd").apply { timeZone = BEIJING_TZ }
            val calendar = Calendar.getInstance(BEIJING_TZ)
            calendar.time = format.parse(date) ?: return date
            calendar.add(Calendar.DAY_OF_MONTH, 1)
            format.format(calendar.time)
        } catch (_: Throwable) {
            date
        }
    }

    /** 本地时区兜底日期。 */
    private fun localBeijingDate(): String =
        SimpleDateFormat("yyyy-MM-dd").apply { timeZone = BEIJING_TZ }.format(Date())

    /** 依次尝试多个时间 API。 */
    private fun fetchOnlineDate(): String? {
        val apis = listOf(
            "http://worldtimeapi.org/api/timezone/Asia/Shanghai",
            "http://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp",
        )
        for (api in apis) {
            try {
                val future: Future<String> = EXECUTOR.submit(Callable { fetchFromApi(api) })
                val result = future.get(3, TimeUnit.SECONDS)
                if (!result.isNullOrEmpty()) return result
            } catch (_: Throwable) {
                // 尝试下一个 API
            }
        }
        return null
    }

    /** 请求单个时间 API 并解析日期。 */
    private fun fetchFromApi(apiUrl: String): String? {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(apiUrl).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2000
            connection.readTimeout = 2000
            val code = connection.responseCode
            if (code !in 200..299) return null
            val body = BufferedReader(InputStreamReader(connection.inputStream, StandardCharsets.UTF_8)).use { it.readText() }
            if (apiUrl.contains("worldtimeapi")) {
                val idx = body.indexOf("\"datetime\":\"")
                if (idx >= 0) {
                    val start = idx + 12
                    val end = body.indexOf("\"", start)
                    if (end > start) {
                        val datetime = body.substring(start, end)
                        if (datetime.length >= 10) return datetime.substring(0, 10)
                    }
                }
            } else if (apiUrl.contains("taobao")) {
                val idx = body.indexOf("\"data\":")
                if (idx >= 0) {
                    val start = body.indexOf("\"", idx + 7)
                    val end = body.indexOf("\"", start + 1)
                    if (end > start && end - start >= 13) {
                        val timestamp = body.substring(start + 1, end)
                        if (timestamp.length >= 13) {
                            val millis = timestamp.substring(0, 13).toLong()
                            return SimpleDateFormat("yyyy-MM-dd").apply { timeZone = BEIJING_TZ }.format(Date(millis))
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            // 忽略
        } finally {
            connection?.disconnect()
        }
        return null
    }
}

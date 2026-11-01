package cn.kerong.bot

import cn.kerong.penguin.spigot.qqbind.EconomyName
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import com.alibaba.fastjson.JSON
import com.alibaba.fastjson.JSONArray
import com.alibaba.fastjson.JSONObject
import io.github.kloping.qqbot.Start0
import io.github.kloping.qqbot.Starter
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicLong

/**
 * QQ 快捷菜单（输入面板）管理：启动时按配置同步群快捷命令按钮。
 *
 * 1.6.0 面板同步加固（修复「面板显示没有指定 / 按钮丢失」问题）：
 * - 全部面板接口调用校验 HTTP 状态码并解析 QQ 错误码（如 40030009 并发冲突、
 *   40030013 超出数量限制、40030020 内容安全拦截），失败明细写入日志；
 * - 面板元素长度预校验：name ≤ 14 单位 / desc ≤ 30 单位（中文按 2 单位），
 *   超限按钮跳过并告警；
 * - 40030009（操作进行中）自动重试（最多 3 次）；
 * - 同步结束后输出面板 ID 与可用按钮数，启动日志与 /hbkng reload 反馈均可核对。
 *
 * 1.6.9.5：panel 子命令移除——/hbkng reload 在重载配置后
 * 一并调用本同步（先删后建，按新配置重建按钮），不再需要单独命令。
 *
 * 1.6.7.9 先删后建（修复「创建面板失败 HTTP 400 code 30013 超出数量限制」）：
 * - 同步逻辑改为**先删后建**——不再走 PUT 原地更新：旧版「列表查询失败 →
 *   误判无面板 → 直接创建」的路径会在平台上残留旧面板，累计触发 30013 数量
 *   超限后创建永远被拒；现每次同步前先删除列表里的全部面板（含非本插件
 *   remark 的残留面板），再按当前配置创建；
 * - 创建面板遇 30013（含 40030013 形态）时**自动清空重建**；错误码归一化
 *   （30009/30013/30020 双形态）+ describePanelError 人话如实提示。
 *
 * 1.6.7.10 30013 真根因修复（1.6.7.9 的先删后建没有对症——用户实测列表 0
 * 面板、清空重建 3 次仍被拒）：
 * - **按钮数量上限裁剪**：QQ 官方文档明确「一个指令面板里最多配置 20 个面板
 *   元素」——1.6.7 加入「查性能」按钮后，全功能开启时面板达 **21 项**，创建
 *   必然被 40030013 拒绝（与平台上有没有旧面板无关，这才是列表为空仍报
 *   超限的根因）。现按按钮优先级自动裁剪到 20 个：全功能开启时优先裁掉低频
 *   管理按钮（查询open ID / 解除黑名单 / AI对话上下文…），被裁命令仍可在群
 *   里手动输入，且日志如实列出被裁按钮；
 * - **30013 重试加退避**：旧版三连发在同一秒内全部撞墙；现重试前等待退避
 *   间隔（2.5s / 5s 递增），再走「清空可见面板 → 再创建」恢复路径；
 * - **创建接口 QPM 节流**：官方限制 POST /v2/panels 为 10 次/分钟，全局
 *   6.5 秒最小创建间隔——启动同步 + /hbkng reload + 重试叠加时不再超频；
 * - **列表分页完整拉取**（next_cursor / is_end，最多 10 页防死循环），
 *   且列表查询失败与「确认无面板」区分开（旧版列表失败也报「平台上无残留
 *   面板」，误导排查方向）；
 * - **同步结果如实返回**（[PanelSyncResult]）：/hbkng reload 按真实结果
 *   反馈成功/失败，不再无条件报成功；
 * - 持续 30013 时提示可能原因（平台配额统计延迟 / 存在列表不可见的残留
 *   面板 / 创建过于频繁），引导等待几分钟后重试或到 QQ 开放平台管理端核查。
 *
 * 线程注意：本同步含网络 IO 与退避等待（最坏 20 秒级），**必须在异步线程
 * 调用**（启动路径在 QClient 后台线程；/hbkng reload 由命令层包 submitAsync）。
 */
object MenuManager {
    private const val API_BASE = "https://api.sgroup.qq.com"

    /** 面板备注（用于识别本插件创建的面板；不对用户展示）。 */
    private const val PANEL_REMARK = "KERONG Penguin"

    /** QQ 平台限制：一个指令面板最多 20 个面板元素（官方文档 v2/panels）。 */
    private const val PANEL_MAX_ITEMS = 20

    /** 面板操作冲突（30009 / 40030009）重试次数与间隔。 */
    private const val RETRY_TIMES = 3
    private const val CONFLICT_RETRY_INTERVAL_MILLIS = 1_200L

    /** 30013（超出数量限制）重试前退避间隔（毫秒），逐次加长。 */
    private val LIMIT_RETRY_BACKOFF_MILLIS = longArrayOf(2_500L, 5_000L)

    /**
     * 创建接口 QPM 节流：官方限制 POST /v2/panels 10 次/分钟，
     * 全局保持最小创建间隔（启动同步 + /hbkng reload + 重试叠加时防超频）。
     */
    private const val CREATE_MIN_INTERVAL_MILLIS = 6_500L

    /** QQ 面板错误码（归一化后）：操作进行中（并发冲突）。 */
    private const val PANEL_ERR_CONFLICT = 30009

    /** QQ 面板错误码（归一化后）：面板数量超出平台限制。 */
    private const val PANEL_ERR_LIMIT = 30013

    /** QQ 面板错误码（归一化后）：内容安全拦截。 */
    private const val PANEL_ERR_CONTENT_SAFETY = 30020

    /** 上次创建面板请求时间戳（QPM 节流用）。 */
    private val lastCreatePostAt = AtomicLong(0L)

    /**
     * 面板按钮定义：name 菜单按钮文案 / desc 描述 / command 点击后发送的命令。
     * 注意：QQ 面板点击后发送的是 name 本身（API 无独立命令字段），
     * 故 name 必须与群命令名一致；参数型命令（如 绑定）点击后仅发送命令名，
     * 玩家自行补参数。
     * 1.6.0 新增金币榜按钮；1.6.2 更名「财富榜」（命令 /金币榜 同步更名，
     * 旧名保留为隐藏别名不出现在菜单）；「在线」为 /查在线 的输入别名，
     * 按用户要求不出现在快捷菜单；1.6.7 新增「查性能」按钮
     * （commands.查性能 关闭时同步消失）；1.6.9.3 移除「在线服务器」与
     * 「motd」两个按钮（对应命令已删除，面板从 21 项降回 19 项，
     * 不再触发平台上限裁剪）。
     */
    private data class PanelItem(val name: String, val desc: String)

    private val PANEL_ITEMS = listOf(
        PanelItem("帮助", "查看所有命令"),
        PanelItem("查询open ID", "查询 OpenId"),
        PanelItem("查信息", "查询玩家统计卡片"),
        PanelItem("绑定", "绑定 QQ"),
        PanelItem("重新绑定", "解除 QQ 绑定"),
        PanelItem("查在线", "查询在线玩家"),
        PanelItem("查性能", "查看服务器性能"),
        PanelItem("在线排行榜", "在线时长排行"),
        PanelItem("财富榜", "金币数量排行"),
        PanelItem("发信息", "发送消息"),
        PanelItem("执行命令", "执行服务器命令"),
        PanelItem("执行", "执行自定义命令"),
        PanelItem("管理员执行", "管理员执行"),
        PanelItem("全量", "切换全量转发"),
        PanelItem("AI对话上下文", "AI对话上下文"),
        PanelItem("清除当前上下文", "AI清除"),
        PanelItem("黑名单", "黑名单"),
        PanelItem("解除黑名单", "解除黑名单"),
        PanelItem("签到", "每日签到领金币"),
    )

    /**
     * 超出平台上限（20 个）时的裁剪顺序（从本表头部开始裁）：
     * 优先保留玩家常用按钮，低频管理类按钮先让位（对应命令仍可手动输入）。
     * 1.6.7.10：全功能开启时面板 21 项超限，按此顺序裁掉「查询open ID」；
     * 1.6.9.3：移除两个已删除命令的按钮后全功能 19 项，正常不再触发裁剪。
     */
    private val PANEL_TRIM_ORDER = listOf(
        "查询open ID",
        "解除黑名单",
        "AI对话上下文",
        "清除当前上下文",
        "全量",
        "黑名单",
    )

    /** 依赖 QQ 绑定功能开启的按钮（绑定关闭时一并隐藏；1.5.2：个人信息按钮更名为查信息）。 */
    private val QQ_BIND_PANEL_NAMES = setOf("绑定", "重新绑定", "黑名单", "解除黑名单", "查信息")

    /** 依赖签到功能开启的按钮。 */
    private val CHECKIN_PANEL_NAMES = setOf("签到")

    /** 依赖经济插件（Vault）开启的按钮（1.6.0 金币榜；1.6.2 更名财富榜）。 */
    private val ECONOMY_PANEL_NAMES = setOf("财富榜")

    /**
     * 面板同步结果（1.6.7.10）：启动同步与 /hbkng reload 据此如实反馈，
     * 不再无条件报「已重新同步」。
     */
    data class PanelSyncResult(
        /** 创建成功的面板 ID（null = 未创建成功）。 */
        val panelId: String?,
        /** 面板内按钮数（含被裁剪后的最终数量）。 */
        val buttonCount: Int,
        /** 本次同步覆盖的群数。 */
        val groupCount: Int,
        /** 失败 / 跳过原因（成功时为 null）。 */
        val error: String?,
    ) {
        val ok: Boolean get() = panelId != null
    }

    /**
     * 同步所有配置群的快捷面板（兼容入口：保持 1.6.7.9 及之前的 void 签名，
     * 供旧编译产物（互转版 QClient 等）与无需结果的调用方使用；结果见日志）。
     * 需要程序化结果的调用方用 [syncGroupPanelsWithResult]。
     *
     * 必须在异步线程调用（内部有网络 IO 与退避等待）。
     */
    fun syncGroupPanels(starter: Starter, groupOpenIds: List<String>) {
        syncGroupPanelsWithResult(starter, groupOpenIds)
    }

    /**
     * 同步所有配置群的快捷面板（1.6.7.9 起先删后建，1.6.7.10 起上限裁剪 +
     * 退避重试 + QPM 节流），返回同步结果（成功带面板 ID / 失败带原因）：
     * ① 列出已有面板 → ② 删除全部（含非本插件残留）→ ③ 校验长度并按
     * 优先级裁剪到 20 个后创建新面板（遇 30013 数量超限退避后自动清空重建）
     * → ④ 记录面板 ID 与结果日志，并把结果返回给调用方。
     *
     * 必须在异步线程调用（内部有网络 IO 与退避等待）。
     */
    fun syncGroupPanelsWithResult(starter: Starter, groupOpenIds: List<String>): PanelSyncResult {
        if (groupOpenIds.isEmpty()) {
            return PanelSyncResult(null, 0, 0, "未配置 QQ 群（bot.groups 为空）")
        }
        try {
            val start0 = starter.APPLICATION.INSTANCE.contextManager.getContextEntity(Start0::class.java)
            val token = start0.accessToken ?: run {
                log("面板同步跳过：访问令牌不可用（机器人尚未完成鉴权）")
                return PanelSyncResult(null, 0, 0, "访问令牌不可用（机器人尚未完成鉴权，连接成功后可用 /hbkng reload 重试）")
            }
            val authHeader = "QQBot $token"

            // 收集功能开关
            var commandList: Map<String, Boolean> = emptyMap()
            var qqBindEnabled = false
            var personalInfoEnabled = true
            var checkinEnabled = false
            var economyAvailable = false
            try {
                val plugin = cn.kerong.bot.provider.BotShared.instance ?: return PanelSyncResult(null, 0, 0, "插件实例不可用")
                commandList = plugin.commandList()
                val manager = QqBindManager.getInstance()
                qqBindEnabled = manager.isEnabled
                personalInfoEnabled = manager.isPersonalInfoEnabled
                checkinEnabled = manager.isCheckinEnabled
                economyAvailable = cn.kerong.bot.events.commands.CheckInCommands().isEconomyAvailable()
            } catch (_: Throwable) {
            }

            val visible = PANEL_ITEMS.filter { item ->
                if (!qqBindEnabled && item.name in QQ_BIND_PANEL_NAMES) return@filter false
                if (qqBindEnabled && !personalInfoEnabled && item.name == "查信息") return@filter false
                if (!checkinEnabled && item.name in CHECKIN_PANEL_NAMES) return@filter false
                if (!economyAvailable && item.name in ECONOMY_PANEL_NAMES) return@filter false
                val enabled = commandList[item.name]
                enabled == null || enabled
            }
            // 1.6.0：长度预校验（中文按 2 单位）——超限按钮跳过并告警，
            // 防止单个按钮把整次面板提交拖垮（面板整体被拒 → 全部按钮消失）
            // 1.6.1：描述文本应用经济别名（如「金币数量排行」/「每日签到领金币」
            // 里的“金币”跟随 economy-alias 配置替换；按钮名即命令名不替换）
            val checked = visible.mapNotNull { item ->
                val aliasedDesc = EconomyName.apply(item.desc)
                when {
                    displayUnits(item.name) > 14 -> {
                        log("面板按钮「${item.name}」名称超过 14 单位（中文按 2 计），已跳过")
                        null
                    }
                    displayUnits(aliasedDesc) > 30 -> {
                        log("面板按钮「${item.name}」描述超过 30 单位（中文按 2 计），已跳过")
                        null
                    }
                    else -> PanelItem(item.name, aliasedDesc)
                }
            }
            if (checked.isEmpty()) {
                log("面板同步中止：全部按钮被功能开关或长度校验过滤，本次不创建面板")
                return PanelSyncResult(null, 0, groupOpenIds.size, "全部按钮被功能开关或长度校验过滤，本次未创建面板")
            }

            // 1.6.7.10：按优先级裁剪到平台上限 20 个（真根因修复——
            // 官方限制单个面板最多 20 个元素，21 项创建必然被 30013 拒绝）
            val finalItems = trimToPlatformLimit(checked)

            val panelJson = JSONObject().apply {
                put("remark", PANEL_REMARK)
                put("items", JSONArray(finalItems.map { item ->
                    JSONObject().apply {
                        put("type", "command")
                        put("name", item.name)
                        put("desc", item.desc)
                    }
                }))
            }

            val createBody = JSONObject().apply {
                put("scope", "group")
                put("target_type", "specific")
                put("group_openids", JSONArray(groupOpenIds))
                put("panel", panelJson)
            }

            // 1.6.7.9：先删后建——不再 PUT 原地更新（旧面板在平台上累积触发
            // 30013 数量超限的根因），每次同步前删除列表里的全部面板（含非本
            // 插件 remark 的残留面板），再按当前配置创建一个新面板；创建遇
            // 30013 时由 createPanelWithLimitRecovery 退避后清空重建兜底
            deleteAllPanels(authHeader)

            val outcome = createPanelWithLimitRecovery(authHeader, createBody, finalItems.size, groupOpenIds.size)

            return if (outcome.panelId != null) {
                log("已同步 ${groupOpenIds.size} 个群的快捷菜单（面板 ${outcome.panelId}，${finalItems.size} 个按钮可用）")
                PanelSyncResult(outcome.panelId, finalItems.size, groupOpenIds.size, null)
            } else {
                val reason = outcome.error ?: "面板未创建成功"
                log("快捷菜单同步失败：$reason")
                PanelSyncResult(null, finalItems.size, groupOpenIds.size, reason)
            }
        } catch (e: Exception) {
            cn.kerong.bot.provider.BotShared.instance?.log_error("面板同步失败: ${e.message}")
            return PanelSyncResult(null, 0, 0, "面板同步异常: ${e.message}")
        }
    }

    /**
     * 按优先级把按钮列表裁剪到平台上限（20 个）：
     * 按 [PANEL_TRIM_ORDER] 从头部开始裁（低频管理按钮先让位），
     * 裁剪顺序表未覆盖的溢出部分从列表末尾直接截断（兜底）。
     * 被裁命令仍可在群里手动输入；被裁按钮名如实写入日志。
     */
    private fun trimToPlatformLimit(items: List<PanelItem>): List<PanelItem> {
        if (items.size <= PANEL_MAX_ITEMS) return items
        var remaining = items
        val dropped = mutableListOf<String>()
        for (name in PANEL_TRIM_ORDER) {
            if (remaining.size <= PANEL_MAX_ITEMS) break
            val index = remaining.indexOfFirst { it.name == name }
            if (index >= 0) {
                dropped.add(name)
                remaining = remaining.filterIndexed { i, _ -> i != index }
            }
        }
        if (remaining.size > PANEL_MAX_ITEMS) {
            val extra = remaining.size - PANEL_MAX_ITEMS
            dropped.addAll(remaining.takeLast(extra).map { it.name })
            remaining = remaining.dropLast(extra)
        }
        log(
            "面板按钮 ${items.size} 个超过平台上限 $PANEL_MAX_ITEMS，已自动裁剪「${dropped.joinToString("、")}」" +
                "（对应命令仍可在群里手动输入）"
        )
        return remaining
    }


    // ---------- HTTP 调用（全部带响应校验与错误码日志） ----------

    /**
     * 拉取已存在的面板列表（1.6.7.10 起分页完整拉取）。
     * @return 全部面板；**列表查询失败返回 null**（与「确认无面板」的空列表区分）
     */
    private fun listPanels(authHeader: String, scope: String): List<JSONObject>? {
        val records = mutableListOf<JSONObject>()
        var cursor = ""
        var pages = 0
        while (pages < 10) {
            pages++
            val url = if (cursor.isEmpty()) {
                "$API_BASE/v2/panels?scope=$scope&limit=50"
            } else {
                "$API_BASE/v2/panels?scope=$scope&limit=50&cursor=${URLEncoder.encode(cursor, "UTF-8")}"
            }
            val (code, body) = httpCall("GET", url, authHeader)
            if (code !in 200..299) {
                log("查询面板列表失败（HTTP $code${describePanelError(body)}）: $body")
                return null
            }
            val parsed = try {
                JSON.parseObject(body ?: "{}")
            } catch (e: Exception) {
                log("解析面板列表响应失败: ${e.message}")
                return null
            } ?: return null
            val page = parsed.getJSONArray("records") ?: JSONArray()
            (0 until page.size).forEach { i -> (page.get(i) as? JSONObject)?.let { records.add(it) } }
            val next = parsed.getString("next_cursor") ?: ""
            val end = parsed.getBooleanValue("is_end")
            if (end || next.isEmpty()) return records
            cursor = next
        }
        return records
    }

    /** 清理结果：删除数 / 失败数 / 列表是否查询失败。 */
    private class CleanupResult(val deleted: Int, val failed: Int, val listFailed: Boolean)

    /**
     * 删除列表里的全部面板（1.6.7.9 先删后建主体）。
     * 删除失败逐个如实记录（HTTP 状态码 + 错误码 + 人话解释），不阻断后续创建。
     * 1.6.7.10：列表查询失败时明确说「无法确认/清理旧面板」，不再误报
     * 「平台上无残留面板」。
     */
    private fun deleteAllPanels(authHeader: String): CleanupResult {
        val existing = listPanels(authHeader, "group")
        if (existing == null) {
            log("面板列表查询失败，无法确认/清理旧面板（可能是网络抖动或接口限频），直接尝试创建")
            return CleanupResult(0, 0, listFailed = true)
        }
        if (existing.isEmpty()) {
            log("平台上无残留面板，直接创建")
            return CleanupResult(0, 0, listFailed = false)
        }
        var deleted = 0
        var failed = 0
        for (panel in existing) {
            val id = panel.getString("panel_id") ?: continue
            if (deletePanel(authHeader, id)) {
                deleted++
            } else {
                failed++
            }
        }
        if (failed == 0) {
            log("已删除全部旧面板（$deleted 个），开始创建新面板")
        } else {
            log("旧面板清理完成：成功 $deleted 个、失败 $failed 个（失败明细见上方日志），继续尝试创建")
        }
        return CleanupResult(deleted, failed, listFailed = false)
    }

    /** 删除面板（失败如实记录错误码与原因，不阻断后续创建）。返回是否成功。 */
    private fun deletePanel(authHeader: String, panelId: String): Boolean {
        val (code, text) = httpCall("DELETE", "$API_BASE/v2/panels/$panelId", authHeader)
        if (code !in 200..299) {
            log("删除旧面板 $panelId 失败（HTTP $code${describePanelError(text)}）: $text")
            return false
        }
        return true
    }

    /** 创建结果：面板 ID 或失败原因。 */
    private class CreateOutcome(val panelId: String?, val error: String?)

    /**
     * 创建面板（POST）：
     * - 30009（操作进行中，含 40030009 形态）等待后自动重试；
     * - **30013（超出数量限制，含 40030013 形态）退避后自动清空重建**——
     *   1.6.7.10：重试前先退避（2.5s / 5s 递增，旧版三连发同一秒内全部
     *   撞墙），再重新拉取列表删除全部面板后重试；
     * - 每次创建经 [paceCreate] 全局节流（官方 10 QPM）；
     * - 其余失败如实输出 HTTP 状态码 + QQ 错误码 + 平台原始响应。
     */
    private fun createPanelWithLimitRecovery(
        authHeader: String,
        body: JSONObject,
        buttonCount: Int,
        groupCount: Int,
    ): CreateOutcome {
        repeat(RETRY_TIMES) { attempt ->
            paceCreate()
            val (code, text) = httpCall("POST", "$API_BASE/v2/panels", authHeader, body.toJSONString())
            if (code in 200..299) {
                val panelId = parsePanelId(text)
                if (panelId == null) {
                    log("创建面板成功但响应中无 panel_id（响应: $text）")
                    return CreateOutcome(null, "创建面板成功但响应中无 panel_id（响应: $text）")
                }
                return CreateOutcome(panelId, null)
            }
            val err = normalizedErrorCode(text)
            when {
                err == PANEL_ERR_CONFLICT && attempt < RETRY_TIMES - 1 -> {
                    log("面板创建冲突（操作进行中），${CONFLICT_RETRY_INTERVAL_MILLIS}ms 后重试（第 ${attempt + 1} 次）")
                    sleepQuietly(CONFLICT_RETRY_INTERVAL_MILLIS)
                }
                err == PANEL_ERR_LIMIT && attempt < RETRY_TIMES - 1 -> {
                    val backoff = LIMIT_RETRY_BACKOFF_MILLIS[minOf(attempt, LIMIT_RETRY_BACKOFF_MILLIS.size - 1)]
                    log(
                        "创建面板被拒（错误码 30013：超出数量限制，本次提交 $buttonCount 个按钮 / $groupCount 个群" +
                            "${limitHintOf(text)}），${backoff}ms 后自动清空全部面板重建"
                    )
                    sleepQuietly(backoff)
                    deleteAllPanels(authHeader)
                }
                else -> {
                    val detail = "创建面板失败（HTTP $code${describePanelError(text)}）: $text"
                    log(detail)
                    return CreateOutcome(null, detail)
                }
            }
        }
        return CreateOutcome(null, "创建面板连续 $RETRY_TIMES 次被拒（30013 已自动清空重建仍失败）")
    }

    /** 从创建成功响应解析 panel_id。 */
    private fun parsePanelId(text: String?): String? = try {
        JSON.parseObject(text ?: "{}")?.getString("panel_id")
    } catch (_: Throwable) {
        null
    }

    /**
     * 错误码归一化：QQ 面板接口的错误码存在 30013 与 40030013 两种形态
     * （v2 API 返回前者、部分网关返回后者），统一折算为 30013 形态比较。
     */
    private fun normalizedErrorCode(body: String?): Int {
        val code = errorCodeOf(body)
        return if (code > 40_000_000) code - 40_000_000 else code
    }

    /** 面板错误码 → 人话解释（拼进失败日志，帮助服主自助排查）。 */
    private fun describePanelError(body: String?): String {
        return when (normalizedErrorCode(body)) {
            PANEL_ERR_CONFLICT -> "，错误码 30009：操作进行中（并发冲突），稍后自动重试"
            PANEL_ERR_LIMIT -> "，错误码 30013：超出数量限制（单个面板最多 20 个按钮、一个机器人最多 20 个面板；本次同步已自动清空重建，仍失败可稍后用 /hbkng reload 重试）"
            PANEL_ERR_CONTENT_SAFETY -> "，错误码 30020：内容安全拦截（检查按钮文案后重试）"
            else -> ""
        }
    }

    /** 平台 30013 响应可能携带 limit 字段（官方：「具体限制值见返回信息中的 limit」）。 */
    private fun limitHintOf(body: String?): String = try {
        val limit = JSON.parseObject(body ?: "{}")?.getInteger("limit")
        if (limit != null && limit > 0) "，平台提示上限 limit=$limit" else ""
    } catch (_: Throwable) {
        ""
    }

    /**
     * 创建接口 QPM 节流：距上次 POST /v2/panels 不足
     * [CREATE_MIN_INTERVAL_MILLIS] 时等待补足（官方限制 10 次/分钟）。
     */
    private fun paceCreate() {
        val last = lastCreatePostAt.get()
        if (last > 0) {
            val wait = CREATE_MIN_INTERVAL_MILLIS - (System.currentTimeMillis() - last)
            if (wait > 0) sleepQuietly(wait)
        }
        lastCreatePostAt.set(System.currentTimeMillis())
    }

    /** 可中断的睡眠（中断时恢复中断标记并立即返回）。 */
    private fun sleepQuietly(millis: Long) {
        try {
            Thread.sleep(millis)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * 统一 HTTP 调用：返回（状态码，响应体）。
     * 读取错误流而不是异常吞掉——QQ 面板接口的错误响应体携带
     * 错误码与中文说明，是排查「面板没显示」的关键信息。
     */
    private fun httpCall(
        method: String,
        url: String,
        authHeader: String,
        jsonBody: String? = null,
    ): Pair<Int, String?> {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", authHeader)
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (jsonBody != null) {
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.outputStream.writer(StandardCharsets.UTF_8).use { it.write(jsonBody) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = readAll(stream)
            code to body
        } catch (e: Exception) {
            -1 to (e.message ?: e.toString())
        } finally {
            connection?.disconnect()
        }
    }

    /** 从错误响应体解析 QQ 错误码（无则返回 -1）。 */
    private fun errorCodeOf(body: String?): Int = try {
        JSON.parseObject(body ?: "{}")?.getIntValue("code") ?: -1
    } catch (_: Throwable) {
        -1
    }

    /** 面板文案长度单位（中文等全角字符按 2 单位，其余 1 单位）。 */
    private fun displayUnits(text: String): Int =
        text.sumOf { ch -> if (ch.code > 0x2E80) 2 else 1 }

    /** 读取流内容为字符串。 */
    private fun readAll(stream: java.io.InputStream?): String? {
        if (stream == null) return null
        return try {
            BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    /** 面板同步过程日志（控制台 + 文件，便于事后排查面板问题）。 */
    private fun log(message: String) {
        cn.kerong.bot.provider.BotShared.instance?.log_info("[快捷面板] $message")
            ?: println("[快捷面板] $message")
    }
}

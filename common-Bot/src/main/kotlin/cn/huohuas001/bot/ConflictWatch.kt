package cn.huohuas001.bot

import cn.huohuas001.bot.tools.PluginFileLog

/**
 * 双服务器同绑一个机器人：消息级冲突检测（1.6.3 新增）。
 *
 * ## 背景（为什么 1.6.2 的连接级互踢检测抓不住这种情况）
 *
 * QQ 开放平台对同一 AppID 的第二条 WebSocket 连接**并不总是**立刻把第一条
 * 踢下线——实测（NeoForge 1.6.2 + Spigot 1.6.2 两台服务器绑同一机器人）
 * 两边可以长时间同时在线：两台服务器同时收到同一条群消息、各自处理并各自
 * 回复。而被动回复的去重键是（msg_id, msg_seq），两台服务器对同一条消息的
 * 回复序号都从 1 开始——先到的一条被平台受理，后到的被 QQ 按
 * `HTTP 400 code=40054005 {"message":"消息被去重，请检查请求msgseq"}` 拒收。
 * 表现为：
 * - 群里文本回复时多时少（后发的那条被静默拒收，双方玩家都以为机器人抽风）；
 * - 图片（渲染耗时数秒、几乎总是后发）在其中一台服务器上每条都报
 *   「回复图片消息失败: …code is [40054005]…」——这正是 1.6.0 / 1.6.1
 *   用户反馈「回复图片消息失败」的真实根因（当时误判为网关弱网重投递）；
 * - 连接全程健康（都是长会话），[WssDiagnostics] 的短命会话互踢判定
 *   **永远不触发**，两台服务器安静地同时运行——管理员看不到任何冲突提示。
 *
 * ## 检测原理（为什么 40054005 现在是确凿证据）
 *
 * [ReplySeqGuard]（1.6.2）保证本进程内对同一条消息的任何两次投递、任意条
 * 回复的 msg_seq 绝不重复（基数 1 / 100001 / 200001… 互不重叠）。因此，
 * 本进程发出的被动回复若仍被 QQ 按 40054005 去重拒收，唯一合理的解释是
 * **另一个客户端（另一台服务器）用同一个 AppID 抢先回复了同一条消息**。
 * 这比连接级特征（短命会话 / 鉴权期被关）可靠得多——那两种只是「疑似」，
 * 这一种是实锤。
 *
 * ## 判定与处置
 *
 * 10 分钟窗口内累计 3 次去重拒收 → 判定成立，交 [ReconnectManager.onDedupConflictConfirmed]：
 * 醒目输出原因与处理方法 → 断开本服机器人连接并停止自动重连（继续在线只会
 * 继续和另一台服务器互抢消息，谁都发不稳）→ 不走 30 分钟自动轮次
 * （配置错误无法靠重试解决）→ 管理员处理后用 /hb chs 手动恢复。
 * 服务器本体与插件其余功能不受影响。
 *
 * 检测点覆盖全部被动回复发送路径的 catch（QClient 四个回复方法 +
 * MessageTemplates 模板回复 + GroupMsgSender 兜底）；主动推送（无 msg_id）
 * 不可能触发 40054005，无需接入。
 */
object ConflictWatch {

    /** 去重拒收判定阈值（窗口内累计次数）。 */
    private const val DEDUP_THRESHOLD = 3

    /** 去重拒收计数窗口（毫秒）。 */
    private const val DEDUP_WINDOW_MS = 600_000L

    /** 窗口内累计次数。 */
    @Volatile
    private var dedupCount = 0

    /** 本次窗口首次拒收时间戳。 */
    @Volatile
    private var firstDedupAt = 0L

    /** 已判定冲突（防止重复触发；重启客户端 / 手动重连后复位）。 */
    @Volatile
    private var conflictDeclared = false

    /**
     * 被动回复发送抛异常时调用（各发送路径 catch 里一行接入）。
     * 仅识别 QQ 去重拒收（40054005 / 消息被去重），其余异常不影响计数。
     */
    fun onReplyRejected(error: Throwable?) {
        if (conflictDeclared) return
        if (!looksLikeDedupRejection(error)) return
        recordRejection(error?.message?.take(200) ?: "无消息")
    }

    /**
     * 被动回复被平台拒收（正常返回但 ret 为 40054005）时调用。
     * 与异常路径共用同一计数。
     */
    fun onReplyRejectedDetail(detail: String) {
        if (conflictDeclared) return
        if (!detail.contains("40054005") && !detail.contains("消息被去重")) return
        recordRejection(detail.take(200))
    }

    /** 计数与判定（每次拒收一条文件日志，判定成立时交给 ReconnectManager 处置）。 */
    private fun recordRejection(detail: String) {
        val now = System.currentTimeMillis()
        if (firstDedupAt == 0L || now - firstDedupAt > DEDUP_WINDOW_MS) {
            firstDedupAt = now
            dedupCount = 1
        } else {
            dedupCount++
        }
        PluginFileLog.write(
            "[冲突检测] 本服被动回复被 QQ 去重拒收（40054005，第 $dedupCount/$DEDUP_THRESHOLD 次）：" +
                "$detail —— 本进程回复序号经 ReplySeqGuard 保证不重复，被去重说明" +
                "有另一个客户端正在用同一个机器人回复同一条群消息"
        )
        if (dedupCount >= DEDUP_THRESHOLD && !conflictDeclared) {
            conflictDeclared = true
            ReconnectManager.onDedupConflictConfirmed()
        }
    }

    /** 客户端启动 / 手动重连时复位（重新观察新一轮）。 */
    fun reset() {
        dedupCount = 0
        firstDedupAt = 0L
        conflictDeclared = false
    }

    /** 异常链（最多 5 层 cause）消息里是否含 QQ 去重拒收特征。 */
    private fun looksLikeDedupRejection(error: Throwable?): Boolean {
        var current = error
        var depth = 0
        while (current != null && depth < 5) {
            val message = current.message ?: ""
            if (message.contains("40054005") || message.contains("消息被去重")) return true
            current = current.cause
            depth++
        }
        return false
    }
}

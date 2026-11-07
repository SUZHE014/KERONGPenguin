package cn.kerong.bot.events.commands

import cn.kerong.bot.KERONG
import cn.kerong.penguin.spigot.qqbind.BeijingTimeUtil
import cn.kerong.penguin.spigot.qqbind.EconomyName
import cn.kerong.penguin.spigot.qqbind.EconomyRewardService
import cn.kerong.penguin.spigot.qqbind.MessageTemplates
import cn.kerong.penguin.spigot.qqbind.QqBindManager
import io.github.kloping.qqbot.api.v2.GroupMessageEvent
import org.bukkit.Bukkit

/**
 * /签到 —— 每日签到领取金币与点券（经济插件检测 + 到账校验，1.7.2 重做）。
 * 连续签到按北京时间计算；离线玩家的金币奖励先记账，上线自动补发。
 *
 * 1.7.2 奖励通道（全部余额差值校验，未到账回失败提示）：
 * - 金币：Vault 经济优先；Vault 缺失但 CoinsEngine 可用时改走 CoinsEngine；
 *   都没有 → 沿用 1.5.5 的「无经济」分支（照常累计天数不发放）；
 * - 点券：qq-bind.checkin.points-reward > 0 且 PlayerPoints 可用时发放
 *   （按 UUID 持久，离线也能发）；插件未装 / 数额为 0 时优雅跳过；
 * - 任一通道发放后校验余额差值，未到账 → 部分失败时提示失败项，
 *   全部失败时回复签到失败（不记录今日签到，可重试）。
 *
 * 1.6.0：全部文本回复改走消息模板（checkin-*，配置 messages 段可换文件），
 * 模板渲染出口统一应用经济别名（economy-alias，签到成功文案中的「金币」
 * 二字自动替换为别名，默认不配置时与旧版输出一字不差）。
 *
 * 1.5.5：签到开启但经济插件不可用时自动关闭金币奖励——
 * 不入账也不记账，签到照常累计（连续天数 / 总次数），
 * 成功提示不再显示金币数量（检测每次实时进行，中途装 / 卸经济插件即时生效）。
 */
class CheckInCommands : CommandSupport() {

    @Commands("签到", "checkin", "sign")
    fun checkin(plugin: KERONG, event: GroupMessageEvent, params: String) {
        val manager = try {
            QqBindManager.getInstance()
        } catch (_: Throwable) {
            MessageTemplates.sendDirect(event, "manager-not-ready")
            return
        }
        // 1.7.4 修复：QQ 绑定总开关（qq-bind.enabled）关闭时签到命令同样关闭——
        // 签到建立在 QQ-玩家绑定关系之上（签到天数 / 奖励都记在绑定的玩家档案里），
        // 绑定都没开时签到没有意义（旧版只看 qq-bind.checkin.enabled，绑定关闭后
        // 历史绑定数据仍可签到，与“绑定已关闭”的状态矛盾）；面板按钮同步隐藏
        if (!manager.isEnabled || !manager.isCheckinEnabled) {
            MessageTemplates.sendDirect(event, "checkin-disabled")
            return
        }
        val qq = try {
            userId(event)
        } catch (_: Throwable) {
            null
        }
        if (qq.isNullOrEmpty() || qq == "<unknown>") {
            MessageTemplates.sendDirect(event, "qq-unknown")
            return
        }
        // 1.7.4：多玩家绑定——名下多个玩家时先发送选择消息，数字确认后对
        // 选中的玩家签到（单个玩家 / 未绑定与旧版行为一致）
        cn.kerong.bot.events.PlayerSelection.requireBoundPlayer(
            event, manager, qq,
            notBoundTemplate = "checkin-not-bound",
            notBoundVars = mapOf("prefix" to manager.checkinPrefix),
        ) { selected ->
            performCheckin(plugin, event, manager, qq, selected.playerName, selected.quuuid)
        }
    }

    /** 签到主体（1.7.4 从命令方法抽出：选择确认后以选中的玩家执行）。 */
    private fun performCheckin(
        plugin: KERONG,
        event: GroupMessageEvent,
        manager: QqBindManager,
        qq: String?,
        playerName: String,
        quuid: String,
    ) {
        // 联网获取北京时间（可能阻塞数秒，此方法已在消息线程）
        val today = try {
            BeijingTimeUtil.getBeijingDate()
        } catch (_: Throwable) {
            ""
        }
        if (today.isEmpty()) {
            MessageTemplates.sendDirect(event, "checkin-time-error", mapOf("prefix" to manager.checkinPrefix))
            return
        }

        // 今日是否已签到
        val lastDate = manager.getCheckinDate(quuid)
        val prefix = manager.checkinPrefix
        if (today == lastDate) {
            MessageTemplates.sendDirect(event, "checkin-already", mapOf("prefix" to prefix))
            return
        }

        // 计算连续签到天数
        var streak = manager.getCheckinStreak(quuid)
        val yesterday = BeijingTimeUtil.nextDay(lastDate)
        streak = if (lastDate.isEmpty() || yesterday != today) 1 else streak + 1

        // 1.7.2 奖励通道检测（实时探测，中途装卸插件即时生效）：
        // - 金币：Vault 优先，缺 Vault 时回退 CoinsEngine；两者都在 → 只走 Vault
        val vaultReady = EconomyRewardService.isEconomyAvailable()
        val coinsEngineReady = !vaultReady && EconomyRewardService.isCoinsEngineAvailable()
        // - 点券：配置数额 > 0 且 PlayerPoints 可用
        val pointsReward = EconomyRewardService.checkinPointsReward()
        val pointsReady = pointsReward > 0 && EconomyRewardService.isPointsAvailable()

        // 1.5.5：金币插件全部不可用且无点券奖励 → 无经济分支：
        // 签到照常累计（连续天数 / 总次数），不发放也不记账，提示不显示奖励。
        // NeoForge 平台无 Vault 生态同样走本分支（旧版记入待领字段但永远无法补发，
        // 新版不再积累无效数据）。
        if (!vaultReady && !coinsEngineReady && !pointsReady) {
            manager.setCheckinDate(quuid, today)
            manager.setCheckinStreak(quuid, streak)
            manager.addCheckinTotal(quuid, 1)
            MessageTemplates.sendDirect(event, "checkin-success-no-economy", mapOf(
                "prefix" to prefix,
                "player" to playerName,
                "streak" to streak.toString(),
            ))
            return
        }

        val reward = manager.checkinReward
        val hasCoinsReward = (vaultReady || coinsEngineReady) && reward > 0
        if (reward <= 0 && !pointsReady) {
            MessageTemplates.sendDirect(event, "checkin-reward-invalid", mapOf("prefix" to prefix))
            return
        }

        // 玩家 UUID（点券按 UUID 持久发放；金币余额校验也用真实 UUID）
        val playerUuid = try {
            manager.getPlayerUuidByQuuid(quuid)
        } catch (_: Throwable) {
            null
        }

        // ---------- 发放与到账校验（1.7.2：发放前后余额差值） ----------
        val rewardParts = ArrayList<String>()     // 成功到账的奖励描述（进 {reward}）
        val failedParts = ArrayList<String>()     // 未到账的奖励描述（失败提示）

        if (hasCoinsReward) {
            // 金币：在线直接入账（Spigot 平台），离线记账待上线补发（记账即视为成功）
            // 1.5.4：非 Spigot 平台（NeoForge）无 Vault 经济生态，在线也走记账
            val online = try {
                Bukkit.getPlayerExact(playerName) != null
            } catch (_: Throwable) {
                false
            }
            if (online && plugin.platform == "spigot") {
                val credited = if (vaultReady) {
                    EconomyRewardService.depositVaultVerified(playerName, reward)
                } else {
                    EconomyRewardService.giveCoinsEngineVerified(playerName, playerUuid?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }, reward)
                }
                if (credited) {
                    rewardParts.add("${EconomyName.current()} ${formatMoney(reward)}")
                } else {
                    failedParts.add("${EconomyName.current()} ${formatMoney(reward)}（未到账）")
                    QqBindManager.logQuiet("[签到] 金币入账校验失败 玩家=$playerName 数额=$reward（Vault=$vaultReady CoinsEngine=$coinsEngineReady）")
                }
            } else {
                manager.addPendingCoins(quuid, reward)
                rewardParts.add("${EconomyName.current()} ${formatMoney(reward)}（离线记账，上线补发）")
            }
        }

        if (pointsReady) {
            // 点券：PlayerPoints 按 UUID 持久，离线也可直接发放（余额差值校验）
            val uuid = playerUuid?.let { runCatching { java.util.UUID.fromString(it) }.getOrNull() }
            if (uuid == null) {
                failedParts.add("点券 $pointsReward（玩家 UUID 缺失）")
            } else if (EconomyRewardService.givePointsVerified(uuid, pointsReward)) {
                rewardParts.add("点券 $pointsReward")
            } else {
                failedParts.add("点券 $pointsReward（未到账）")
                QqBindManager.logQuiet("[签到] 点券入账校验失败 玩家=$playerName 数额=$pointsReward")
            }
        }

        if (rewardParts.isEmpty()) {
            // 全部通道未到账：签到失败（不记录今日签到，可重试）
            MessageTemplates.sendDirect(event, "checkin-pay-error", mapOf("prefix" to prefix))
            return
        }

        manager.setCheckinDate(quuid, today)
        manager.setCheckinStreak(quuid, streak)
        manager.addCheckinTotal(quuid, 1)
        val rewardText = rewardParts.joinToString(" + ")
        if (failedParts.isEmpty()) {
            MessageTemplates.sendDirect(event, "checkin-success", mapOf(
                "prefix" to prefix,
                "player" to playerName,
                "reward" to rewardText,
                "streak" to streak.toString(),
            ))
        } else {
            // 部分奖励未到账：签到成功（已到账部分如实展示）+ 失败项提示
            val message = MessageTemplates.render("checkin-success", mapOf(
                "prefix" to prefix,
                "player" to playerName,
                "reward" to rewardText,
                "streak" to streak.toString(),
            )) + "\n${prefix}  ⚠️部分奖励未发放成功：${failedParts.joinToString("、")}，请联系管理员"
            sendRenderedDirect(event, message)
        }
    }

    /** 渲染后的文本直接回复（带 @ 提及；失败回退纯文本）。 */
    private fun sendRenderedDirect(event: GroupMessageEvent, message: String) {
        try {
            val userId = try {
                userId(event)
            } catch (_: Throwable) {
                null
            }
            val content = if (!userId.isNullOrEmpty() && userId != "<unknown>") "<@$userId>\n$message" else message
            val sent = try {
                cn.kerong.bot.QClient.replyMarkdown(event, content, null)
            } catch (_: Throwable) {
                false
            }
            if (!sent) {
                try {
                    event.sendMessage(content)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
            try {
                event.sendMessage(message)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 金币插件（Vault 经济）是否就绪。
     *
     * 1.7.2：检测链整体移入 [EconomyRewardService]（与点券 / CoinsEngine
     * 检测同处；本方法保留为兼容桥——MenuManager 面板按钮显隐等既有调用方
     * 零改动）。
     */
    fun isEconomyAvailable(): Boolean {
        return EconomyRewardService.isEconomyAvailable()
    }

    /** 金额显示（整数去掉小数点）。 */
    fun formatMoney(amount: Double): String =
        if (amount == amount.toLong().toDouble()) amount.toLong().toString() else amount.toString()
}

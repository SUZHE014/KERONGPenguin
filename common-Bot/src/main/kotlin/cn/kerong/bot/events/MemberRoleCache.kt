package cn.kerong.bot.events

import java.util.concurrent.ConcurrentHashMap

/**
 * 群成员角色缓存（群 OpenId + 成员 OpenId → 群内角色 member_role）。
 *
 * ## 背景：按钮回调不带点击者的群身份（1.7.5.1 修复）
 *
 * QQ 群消息事件（GROUP_AT_MESSAGE_CREATE / GROUP_MESSAGE_CREATE）的
 * author 块携带 `member_role`（member=普通成员 / admin=管理员 /
 * owner=群主），手打命令的管理员判定（admin.mode=qq）靠它。而按钮点击
 * 的互动回调（INTERACTION_CREATE）只带 `group_openid` +
 * `group_member_openid`，**没有 member_role**——菜单按钮合成的群消息
 * 事件里没有角色字段，群主/管理员点管理类按钮（黑名单 / 全量 / AI管理
 * 等）也被判「无管理员权限」。
 *
 * 现每条真实群消息到达时登记发送者的最新角色，按钮点击时查出点击者的
 * 最近角色写入合成事件 author 块，管理员判定与手打命令完全一致。
 *
 * ## 时效与一致性
 *
 * - 角色变化（升/降管理员）随该成员的**下一条消息**即时刷新——被降权的
 *   人只要再发言一次，缓存立即更新；
 * - 条目保留 30 分钟未活跃（角色很少变化，QQ 官方也没有群成员角色查询
 *   接口可实时核对；降权后未再发言的成员，按钮路径最长 30 分钟内仍按
 *   旧角色放行，手打命令路径不受影响——与消息元数据同等信任级别）；
 * - 过期条目读取时惰性清理（消息频率远高于按钮点击，无需后台线程）；
 * - 未登记 / 已过期返回 null，合成事件不写角色字段，管理员判定回退
 *   manual 名单路径（与旧版行为一致，不会更差）。
 */
object MemberRoleCache {

    /** 条目保留时长（毫秒）：30 分钟未活跃视为过期。 */
    private const val RETAIN_MILLIS = 30L * 60L * 1000L

    /** 值对象：角色 + 最近活跃时间。 */
    private class Entry(val role: String, val at: Long)

    /** 群 OpenId + "\n" + 成员 OpenId → 最近角色（composite key：同一成员在不同群角色可不同）。 */
    private val roles = ConcurrentHashMap<String, Entry>()

    /** 登记发送者在本群的角色（群 id / 成员 id / 角色任一为空时忽略，零开销）。 */
    fun record(groupOpenId: String?, memberOpenId: String?, role: String?) {
        if (groupOpenId.isNullOrEmpty() || memberOpenId.isNullOrEmpty() || role.isNullOrEmpty()) return
        roles["$groupOpenId\n$memberOpenId"] = Entry(role, System.currentTimeMillis())
    }

    /**
     * 查询成员在本群的最近已知角色。
     *
     * @return owner / admin / member；未登记或已过期返回 null
     */
    fun roleOf(groupOpenId: String, memberOpenId: String): String? {
        val key = "$groupOpenId\n$memberOpenId"
        val entry = roles[key] ?: return null
        if (System.currentTimeMillis() - entry.at > RETAIN_MILLIS) {
            roles.remove(key)
            return null
        }
        return entry.role
    }

    /** 清空全部登记（测试 / 重置用）。 */
    fun clear() = roles.clear()
}

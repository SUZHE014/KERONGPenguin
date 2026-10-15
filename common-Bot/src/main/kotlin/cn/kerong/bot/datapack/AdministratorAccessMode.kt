package cn.kerong.bot.datapack

import cn.kerong.bot.provider.AdminMode

/**
 * 群管理员访问模式（数据包层）。
 */
enum class AdministratorAccessMode {
    QQ,
    MANUAL,
    BOTH;

    companion object {
        fun fromConfig(mode: AdminMode): AdministratorAccessMode = when (mode) {
            AdminMode.QQ -> QQ
            AdminMode.CONFIG -> MANUAL
            AdminMode.BOTH -> BOTH
        }
    }
}

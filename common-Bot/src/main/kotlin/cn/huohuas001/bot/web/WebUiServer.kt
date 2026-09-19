package cn.huohuas001.bot.web

/**
 * Web 面板占位实现：当前版本未启用 Web UI 服务。
 * 保留接口以兼容核心模块调用。
 */
object WebUiServer {
    @Suppress("unused")
    fun start() {}

    @Suppress("unused")
    fun stop() {}

    @Suppress("unused")
    fun invalidateAllTokens() {}
}

/**
 * Web 面板密码管理占位实现。
 */
object WebUiPassword {
    @Suppress("unused")
    fun changePassword(password: String): Boolean = false

    @Suppress("unused")
    fun isConfigured(): Boolean = false
}

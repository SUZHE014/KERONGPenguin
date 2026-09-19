package cn.huohuas001.bot.provider

/**
 * 配置升级工具：为新版本补充缺失的配置键。
 */
object ConfigUpgrader {

    /**
     * 遍历 [defaults] 中的键值，对 [has] 返回 false 的路径调用 [set] 写入默认值。
     * @return 是否有键被补写
     */
    fun fillMissing(
        defaults: Map<String, Any>,
        has: (String) -> Boolean,
        set: (String, Any) -> Unit,
    ): Boolean {
        var changed = false
        for ((path, defaultValue) in defaults) {
            if (has(path)) continue
            set(path, defaultValue)
            changed = true
        }
        return changed
    }
}

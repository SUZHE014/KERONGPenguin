package cn.huohuas001.bot.tools

/**
 * 可取消任务的抽象句柄。
 * 由各平台适配器在提交计划任务时返回，用于提前终止任务。
 */
interface Cancelable {
    /** 取消尚未执行的任务；已开始或已结束的任务调用此方法不会产生副作用。 */
    fun cancel()
}

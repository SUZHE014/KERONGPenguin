package cn.huohuas001.bot.provider

import java.util.concurrent.CompletableFuture

/**
 * 命令执行器：封装一次“向服务器派发命令并收集输出”的过程。
 */
interface HExecution {
    /** 命令执行的原始输出文本。 */
    val rawString: String

    /** 执行命令并返回自身（用于链式 Future）。 */
    fun execute(command: String): CompletableFuture<HExecution>
}

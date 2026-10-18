package cn.kerong.penguin.spigot.render

import java.awt.AlphaComposite
import java.awt.image.BufferedImage

/**
 * 全局透明度应用器（1.6.7.2 覆盖版新增，render.global-opacity，默认 1.0）。
 *
 * 所有图片卡片渲染器（查信息 / 查在线 / 查性能 / 在线排行榜 / 财富榜）
 * 在编码 PNG 前统一经 [apply]：把整张画布的像素 alpha 乘以全局透明度
 * 系数——系数为 1.0（默认）时原样返回零开销，小于 1 时合成到 ARGB
 * 画布输出半透明卡片（QQ 客户端按 PNG 透明度叠加显示在聊天背景上）。
 *
 * 透明度由 [RenderSettings.globalOpacity] 实时读取（/kerong reload 后
 * 立即生效）；取值与语义详见其 KDoc。
 */
object GlobalOpacity {

    /**
     * 对渲染完成的画布应用全局透明度（读 [RenderSettings.globalOpacity]）。
     *
     * @param image 渲染器画好的画布（TYPE_INT_RGB / ARGB 均可）
     * @return 用于编码的画布：透明度 ≥ 1 时返回原图（未 flush、零开销）；
     *   否则返回合成的 ARGB 半透明新图（原图已 flush 释放栅格内存）
     */
    fun apply(image: BufferedImage): BufferedImage = applyOpacity(image, RenderSettings.globalOpacity())

    /**
     * 纯函数形态（透明度由调用方给定，测试与定制场景用）。
     *
     * @param opacity 0.0 ~ 1.0；大于等于 1 时原样返回原实例（零开销），
     *   小于 1 时合成 ARGB 半透明新图并 flush 原图
     */
    fun applyOpacity(image: BufferedImage, opacity: Float): BufferedImage {
        if (opacity >= 0.999f) return image
        val clamped = opacity.coerceIn(0.0f, 1.0f)
        val output = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        val g = output.createGraphics()
        try {
            // SrcOver.derive(alpha)：src 各通道（含 alpha）乘系数后合成，
            // 目标画布初始全透明 → 输出即为整图统一半透明的 PNG 内容
            g.composite = AlphaComposite.SrcOver.derive(clamped)
            g.drawImage(image, 0, 0, null)
        } finally {
            g.dispose()
        }
        image.flush()
        return output
    }
}

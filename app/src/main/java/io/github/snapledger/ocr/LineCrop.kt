package io.github.snapledger.ocr

/** 把一行文字的外接框稍微放大再裁剪：识别模型是按“带一点留白”的行图片训练的，贴边裁会丢笔画。 */
object LineCrop {

    /** 返回 [left, top, right, bottom]（右、下不含）；框太小不值得识别时返回 null。 */
    fun paddedBox(left: Int, top: Int, right: Int, bottom: Int, imageWidth: Int, imageHeight: Int): IntArray? {
        val h = bottom - top
        val w = right - left
        if (h < 10 || w < 10) return null
        val padY = maxOf(2, (h * 0.15f).toInt())
        val padX = maxOf(2, (h * 0.25f).toInt())
        val l = (left - padX).coerceAtLeast(0)
        val t = (top - padY).coerceAtLeast(0)
        val r = (right + padX).coerceAtMost(imageWidth)
        val b = (bottom + padY).coerceAtMost(imageHeight)
        return if (r - l < 10 || b - t < 10) null else intArrayOf(l, t, r, b)
    }
}

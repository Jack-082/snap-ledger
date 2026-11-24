package io.github.snapledger.parse

/** OCR 识别出的一行文字及其在图片中的位置（像素）。 */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val centerY: Int get() = (top + bottom) / 2
}

/**
 * 把 OCR 结果按“视觉上的行”重新拼接。
 *
 * 账单详情页通常是左右两栏（“支付时间”在左，“2026年11月20日 12:30”在右），OCR 会把它们识别成
 * 两个独立的文本块，顺序也不一定相邻。这里按纵坐标把同一水平线上的文字合并成一行、从左到右拼接，
 * 解析器就可以把“标签 值”当成一行处理。
 */
object OcrRows {

    fun group(lines: List<OcrLine>): List<String> {
        val rows = mutableListOf<MutableList<OcrLine>>()
        for (line in lines.filter { it.text.isNotBlank() }.sortedBy { it.centerY }) {
            val row = rows.lastOrNull()
            if (row != null && sameRow(row, line)) row += line else rows += mutableListOf(line)
        }
        return rows.map { row -> row.sortedBy { it.left }.joinToString(" ") { it.text.trim() } }
    }

    /** 竖直方向重叠超过较矮那一行高度的一半，视为同一行。 */
    private fun sameRow(row: List<OcrLine>, line: OcrLine): Boolean {
        val top = row.minOf { it.top }
        val bottom = row.maxOf { it.bottom }
        val overlap = minOf(bottom, line.bottom) - maxOf(top, line.top)
        val minHeight = minOf(row.minOf { it.height }, line.height)
        return overlap * 2 >= minHeight
    }
}

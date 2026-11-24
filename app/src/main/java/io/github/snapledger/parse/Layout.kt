package io.github.snapledger.parse

/** OCR 识别出的一行文字及其在图片中的位置（像素）。 */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val height: Int get() = (bottom - top).coerceAtLeast(1)
    val centerY: Int get() = (top + bottom) / 2
}

/** 一张截图的识别结果。width / height 为 0 表示未知（例如只有文字的短信）。 */
data class OcrPage(val lines: List<OcrLine>, val width: Int = 0, val height: Int = 0) {
    companion object {
        /** 只有文字、没有位置信息时：每行等高、上下排开，版面特征（字号）不起作用。 */
        fun fromRows(rows: List<String>) = OcrPage(rows.mapIndexed { i, t -> OcrLine(t, 0, i * 60, 1000, i * 60 + 40) })
    }
}

/** 视觉上的一行：同一水平线上的若干文字块，从左到右拼接。 */
class TextRow(val lines: List<OcrLine>) {
    val text: String
    private val starts: IntArray

    init {
        val sb = StringBuilder()
        starts = IntArray(lines.size)
        lines.forEachIndexed { i, l ->
            if (i > 0) sb.append(' ')
            starts[i] = sb.length
            sb.append(l.text)
        }
        text = sb.toString()
    }

    val top: Int get() = lines.minOf { it.top }
    val bottom: Int get() = lines.maxOf { it.bottom }

    /** [text] 中第 [index] 个字符所在的文字块。 */
    fun lineAt(index: Int): OcrLine {
        var k = 0
        for (i in starts.indices) if (starts[i] <= index) k = i
        return lines[k]
    }
}

/**
 * 版面分析。
 *
 * 账单详情页通常是左右两栏（“支付时间”在左，“2026年11月20日 12:30”在右），OCR 会把它们识别成
 * 两个独立的文本块，顺序也不一定相邻。这里按纵坐标把同一水平线上的文字合并成一行，
 * 解析器就可以把“标签 值”当成一行处理；同时保留每个文字块的高度，用来判断字号。
 */
object Layout {

    fun rows(page: OcrPage): List<TextRow> {
        val cleaned = page.lines
            .map { it.copy(text = TextNorm.fixAmountLine(TextNorm.normalize(it.text))) }
            .filter { it.text.isNotBlank() }
        val groups = mutableListOf<MutableList<OcrLine>>()
        for (line in cleaned.sortedBy { it.centerY }) {
            val row = groups.lastOrNull()
            if (row != null && sameRow(row, line)) row += line else groups += mutableListOf(line)
        }
        return groups.map { g -> TextRow(g.sortedBy { it.left }) }
    }

    /** 全页文字块高度的中位数，即“正文字号”。 */
    fun medianHeight(rows: List<TextRow>): Float {
        val hs = rows.flatMap { it.lines }.map { it.height }.sorted()
        return if (hs.isEmpty()) 1f else hs[hs.size / 2].toFloat()
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

/** 兼容旧接口：只要合并后的文字。 */
object OcrRows {
    fun group(lines: List<OcrLine>): List<String> = Layout.rows(OcrPage(lines)).map { it.text }
}

package io.github.snapledger.parse

/**
 * 合并两个识别引擎对同一行文字的结果。
 *
 * - [ml]：ML Kit 的结果，行的切分、空格、数字以它为准（解析规则是按它的输出调的）；
 * - [pp]：PP-OCRv5 的结果，中文更准，汉字以它为准（置信度够高时）。
 *
 * 两者先做字符级对齐，再逐段决定：
 * - 涉及汉字的差异 → 采用 PP 的字（“瑞辛咖啡”→“瑞幸咖啡”，“日月”→“明”）；
 * - ¥ 被 ML Kit 认成 Y / 羊 / 半 → 采用 ¥；
 * - 数字不一致 → 不擅自改，把另一个结果放进 [Fused.alt]，由解析器提示用户核对。
 *   例外：整行是金额、且只有一方是规范的“xx.xx”写法时，采用规范的那个（小数点丢失的“3800”→“38.00”）。
 */
object Fusion {

    data class Fused(val text: String, val alt: String? = null)

    private const val TAKE = 0.80f   // 替换汉字
    private const val INSERT = 0.85f // 补上 ML Kit 漏掉的汉字
    private const val EDGE = 0.90f   // 行首行尾的增删更谨慎

    private val PROPER_AMOUNT = Regex("""^[+-]?¥?(\d{1,3}(,\d{3})+|\d+)\.\d{2}元?$""")

    fun fuse(ml: String, pp: String, ppConf: FloatArray): Fused {
        val a = TextNorm.normalize(ml)
        if (a.isEmpty() || pp.isBlank() || ppConf.size != pp.length || a.length > 120 || pp.length > 160) return Fused(ml)
        val b = String(CharArray(pp.length) { TextNorm.mapChar(pp[it]) })
        val mean = ppConf.sum() / ppConf.size
        if (mean < 0.5f) return Fused(ml)

        val ops = align(a, b)
        val distance = ops.count { it != MATCH }
        if (distance == 0) return Fused(a)
        // 差得太多，多半是裁剪范围对不上，不合并
        if (distance > maxOf(2, (maxOf(a.length, b.length) * 0.6f).toInt())) return Fused(a)

        val out = StringBuilder()
        var i = 0
        var j = 0
        var k = 0
        while (k < ops.size) {
            if (ops[k] == MATCH) {
                out.append(a[i]); i++; j++; k++
                continue
            }
            val i0 = i
            val j0 = j
            while (k < ops.size && ops[k] != MATCH) {
                when (ops[k]) {
                    SUB -> { i++; j++ }
                    DEL -> i++
                    else -> j++
                }
                k++
            }
            out.append(decide(a.substring(i0, i), b.substring(j0, j), minConf(ppConf, j0, j), atEdge = i0 == 0 || i == a.length))
        }
        var text = out.toString().replace(Regex("\\s+"), " ").trim()
        val other = b.replace(Regex("\\s+"), " ").trim()

        // 金额行：采用 PP 读到的正负号（ML Kit 偶尔漏掉细小的减号）
        if (TextNorm.isAmountLine(text) && text.first() !in "+-" && other.isNotEmpty() && other.first() in "+-" &&
            ppConf[pp.indexOfFirst { !it.isWhitespace() }] >= TAKE
        ) text = other.first() + text

        // 数字是否一致
        val fa = TextNorm.fixAmountLine(text)
        val fb = TextNorm.fixAmountLine(other)
        val amountish = TextNorm.isAmountLine(text) || TextNorm.isAmountLine(other)
        val disputed = digits(fa) != digits(fb) || (amountish && numeric(fa) != numeric(fb))
        if (!disputed || mean < 0.6f) return Fused(text)
        if (amountish && mean >= TAKE && PROPER_AMOUNT.matches(fb) && !PROPER_AMOUNT.matches(fa)) return Fused(fb, alt = text)
        return Fused(text, alt = other)
    }

    private fun decide(x: String, y: String, conf: Float, atEdge: Boolean): String {
        if (x.trim() in DASHES && y.trim() in DASHES) return x // 减号与“一”：保持 ML Kit 的
        val han = x.any(::isHan) || y.any(::isHan)
        val hasDigit = x.any(Char::isDigit) || y.any(Char::isDigit)
        return when {
            han && !hasDigit && y.isEmpty() -> if (!atEdge && conf >= EDGE) "" else x
            han && !hasDigit && x.isEmpty() -> if (conf >= (if (atEdge) EDGE else INSERT)) y else ""
            han && !hasDigit -> if (conf >= TAKE) y else x
            y.trim() == "¥" && x.trim() in YEN_MISREADS -> if (conf >= 0.6f) "¥" else x
            else -> x
        }
    }

    private val DASHES = setOf("-", "一", "_", "~", "―", "ー")
    private val YEN_MISREADS = setOf("Y", "y", "V", "羊", "半", "")

    /** 这一段 PP 字符的最低置信度；PP 这边为空（删除）时取两侧相邻字符的。 */
    private fun minConf(conf: FloatArray, from: Int, to: Int): Float {
        if (from < to) return (from until to).minOf { conf[it] }
        val neighbors = listOfNotNull(conf.getOrNull(from - 1), conf.getOrNull(from))
        return neighbors.minOrNull() ?: 0f
    }

    private fun digits(s: String) = s.filter(Char::isDigit)
    private fun numeric(s: String) = s.filter { it.isDigit() || it == '.' }

    fun isHan(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿'

    private const val MATCH = 0
    private const val SUB = 1
    private const val DEL = 2 // 只在 ML Kit 结果里
    private const val INS = 3 // 只在 PP 结果里

    /** 编辑距离对齐，返回把 a 变成 b 的操作序列。 */
    private fun align(a: String, b: String): IntArray {
        val n = a.length
        val m = b.length
        val d = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) d[i][0] = i
        for (j in 0..m) d[0][j] = j
        for (i in 1..n) for (j in 1..m) {
            val cost = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j - 1] + cost, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        val ops = ArrayList<Int>(n + m)
        var i = n
        var j = m
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && a[i - 1] == b[j - 1] && d[i][j] == d[i - 1][j - 1] -> { ops += MATCH; i--; j-- }
                i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + 1 -> { ops += SUB; i--; j-- }
                i > 0 && d[i][j] == d[i - 1][j] + 1 -> { ops += DEL; i-- }
                else -> { ops += INS; j-- }
            }
        }
        return ops.reversed().toIntArray()
    }
}

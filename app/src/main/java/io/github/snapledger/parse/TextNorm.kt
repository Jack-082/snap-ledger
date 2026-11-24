package io.github.snapledger.parse

/** 文本归一化与 OCR 常见误识别修正。 */
internal object TextNorm {

    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            sb.append(
                when (c) {
                    in '０'..'９' -> '0' + (c - '０')
                    '：' -> ':'
                    '．' -> '.'
                    '－', '−', '—', '–' -> '-'
                    '＋' -> '+'
                    '￥' -> '¥'
                    '（' -> '('
                    '）' -> ')'
                    ' ', '　' -> ' '
                    else -> c
                }
            )
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private val AMOUNTISH = Regex("""^[+\-]?[¥Y羊]?[\dOolI|,.]+元?$""")

    /**
     * 修正“单独成行的金额”里常见的误识别：¥ 被认成 Y、0 被认成 O、1 被认成 l / I，
     * “38. 00”中间多出的空格，以及“38,00”把小数点认成逗号。只处理整行看起来就是金额的情况，
     * 不会改动普通文字。
     */
    fun fixAmountLine(s: String): String {
        val compact = s.replace(" ", "")
        if (!AMOUNTISH.matches(compact) || compact.count { it.isDigit() } < 2) return s
        var t = compact.replace(Regex("^([+\\-]?)[Y羊]"), "$1¥")
        t = buildString {
            for (c in t) append(
                when (c) {
                    'O', 'o' -> '0'
                    'l', 'I', '|' -> '1'
                    else -> c
                }
            )
        }
        if ('.' !in t) t = t.replace(Regex(""",(\d{2})(元?)$"""), ".$1$2")
        return t
    }
}

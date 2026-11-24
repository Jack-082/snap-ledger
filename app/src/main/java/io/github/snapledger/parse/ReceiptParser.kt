package io.github.snapledger.parse

import io.github.snapledger.model.Direction
import io.github.snapledger.model.Money
import java.time.LocalDateTime

/** 从截图文字中解析出的账单信息；识别不出的字段为 null / 空，由用户在确认页补全。 */
data class ParsedReceipt(
    val amountCents: Long?,
    val direction: Direction,
    val merchant: String,
    val time: LocalDateTime?,
    val category: String,
    /** 支付渠道，例如“微信支付”“支付宝”“银行”，写进备注。 */
    val channel: String,
    /** 金额是否有明确依据（正负号、货币符号或“实付”等标签）；为 false 时界面提示用户核对。 */
    val confident: Boolean,
)

/**
 * 支付 / 到账截图解析器：微信支付、支付宝的支付成功页和账单详情页，转账收款页，银行短信与银行 App 交易详情。
 *
 * 输入是按视觉行拼好的文字（见 [OcrRows]），纯 Kotlin 实现，方便单元测试。
 * 思路是“打分”而不是写死某个 App 的版式：每个候选金额根据正负号、货币符号、
 * 附近的“实付 / 金额 / 收入”等标签加分，“优惠 / 原价 / 余额”等减分，取最高分。
 */
object ReceiptParser {

    // ---------------- 关键词 ----------------

    private val AMOUNT_LABELS = listOf(
        "实付款", "实付", "付款金额", "支付金额", "交易金额", "收款金额", "到账金额", "转账金额", "入账金额",
        "订单金额", "消费金额", "退款金额", "金额", "合计", "总计", "实收", "收入", "支出", "消费", "转入", "转出", "扣款", "入账", "存入",
    )
    private val NEGATIVE_LABELS = listOf(
        "优惠", "立减", "折扣", "原价", "抵扣", "积分", "余额", "可用", "手续费", "额度", "限额", "返现", "券", "节省", "省了", "已省",
    )
    private val INCOME_NEAR = listOf("收入", "转入", "入账", "存入", "退款", "到账")
    private val EXPENSE_NEAR = listOf("支出", "转出", "消费", "扣款", "实付", "付款", "支付")

    private val INCOME_WORDS = listOf(
        "已收款", "收款成功", "到账成功", "已到账", "已存入", "收入", "转入", "退款成功", "已退款", "退款", "入账", "你收到", "收到", "工资", "收益",
    )
    private val EXPENSE_WORDS = listOf(
        "支付成功", "付款成功", "已支付", "支出", "转出", "消费", "扣款", "实付", "付款方式", "支付方式", "支付时间", "付款时间", "待收款", "转账给", "收款方",
    )

    /** 金额所在行上方的这些状态行不是商户名。 */
    private val STATUS_WORDS = listOf(
        "支付成功", "付款成功", "交易成功", "已收款", "收款成功", "已支付", "待收款", "当前状态", "账单详情", "交易详情",
        "转账成功", "已存入", "退款成功", "已退款", "全部账单", "返回", "完成", "订单详情",
    )

    /** 商户 / 对方的标签，按优先级排列。 */
    private val MERCHANT_LABELS = listOf(
        "收款方", "商户名称", "交易对方", "对方户名", "对方账户", "商户全称", "收款方全称", "商品说明", "商品", "付款方", "摘要", "用途",
    )

    private val TIME_LABELS = listOf("支付时间", "付款时间", "交易时间", "创建时间", "转账时间", "收款时间", "到账时间", "入账时间", "退款时间", "时间")

    // ---------------- 正则 ----------------

    private val FULL_DATE_TIME = Regex("""(\d{4})\s*[年\-/.]\s*(\d{1,2})\s*[月\-/.]\s*(\d{1,2})\s*日?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    private val MONTH_DAY_TIME = Regex("""(?<!\d)(\d{1,2})\s*月\s*(\d{1,2})\s*日\s*(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    private val FULL_DATE = Regex("""(?<!\d)(\d{4})\s*[年\-/.]\s*(\d{1,2})\s*[月\-/.]\s*(\d{1,2})\s*日?""")
    private val MONTH_DAY = Regex("""(?<!\d)(\d{1,2})\s*月\s*(\d{1,2})\s*日""")
    private val CLOCK = Regex("""(?<!\d)\d{1,2}:\d{2}(?::\d{2})?(?!\d)""")

    private val AMOUNT = Regex(
        """(?<![\d.,])([+-])?\s?(¥|RMB|CNY)?\s?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)(?![\d.,]*\d)(?!\s?%)(\s?元)?"""
    )
    private val CARD_TAIL = Regex("""(尾号|账户|卡号|\()\s*\d{3,}""")

    // ---------------- 解析 ----------------

    fun parse(rawRows: List<String>, now: LocalDateTime = LocalDateTime.now()): ParsedReceipt {
        val rows = rawRows.map(::normalize).filter { it.isNotBlank() }
        val all = rows.joinToString("\n")

        val best = amountCandidates(rows).maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { -it.row }) // 同分取靠上的
        val direction = direction(best, rows, all)
        val merchant = merchant(rows, best)
        val time = time(rows, now)
        val channel = channel(all)
        val category = Categorizer.categorize(direction, merchant, all)

        return ParsedReceipt(
            amountCents = best?.cents,
            direction = direction,
            merchant = merchant,
            time = time,
            category = category,
            channel = channel,
            confident = best != null && best.score >= 5,
        )
    }

    internal fun normalize(s: String): String {
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

    private data class Candidate(val cents: Long, val sign: Char?, val row: Int, val score: Int, val before: String)

    /** 去掉日期、时间、卡号尾号，避免把它们当成金额。 */
    private fun stripNonAmounts(row: String): String {
        var s = row
        for (re in listOf(FULL_DATE_TIME, MONTH_DAY_TIME, FULL_DATE, MONTH_DAY, CLOCK, CARD_TAIL)) {
            s = re.replace(s) { " ".repeat(it.value.length) } // 等长替换，保持位置不变
        }
        return s
    }

    private fun amountCandidates(rows: List<String>): List<Candidate> {
        val out = mutableListOf<Candidate>()
        rows.forEachIndexed { i, raw ->
            val row = stripNonAmounts(raw)
            val prev = rows.getOrNull(i - 1)?.let(::stripNonAmounts)
            // 上一行是单独的标签（例如“付款金额”下一行才是“¥25.00”）
            val prevLabel = prev?.takeIf { it.trim().length <= 8 && AMOUNT.find(it) == null }?.trim()

            for (m in AMOUNT.findAll(row)) {
                val sign = m.groupValues[1].firstOrNull()
                val hasCurrency = m.groupValues[2].isNotEmpty() || m.groupValues[4].isNotEmpty()
                val number = m.groupValues[3]
                val hasDecimal = number.contains('.')
                val cents = Money.parseCents(number) ?: continue
                if (cents <= 0) continue

                val before = row.substring(0, m.range.first).takeLast(10)
                val standalone = row.removeRange(m.range).replace("¥", "").replace("元", "").isBlank()
                val labeled = AMOUNT_LABELS.any { before.contains(it) } ||
                    (m.range.first <= 1 && prevLabel != null && AMOUNT_LABELS.any { prevLabel.contains(it) })
                val negative = NEGATIVE_LABELS.any { before.contains(it) } ||
                    (m.range.first <= 1 && prevLabel != null && NEGATIVE_LABELS.any { prevLabel.contains(it) })

                // 没有小数、没有货币符号的整数大多是单号、数量、尾号，只有带标签或单独成行带符号时才接受
                if (!hasDecimal && !hasCurrency) {
                    val ok = (labeled && number.length <= 7) || (sign != null && standalone)
                    if (!ok) continue
                }
                if (number.replace(",", "").substringBefore('.').length > 9) continue

                var score = 0
                if (hasDecimal) score += 2
                if (hasCurrency) score += 3
                if (sign != null) score += 4
                if (standalone) score += 2
                if (standalone && sign != null) score += 3
                if (labeled) score += 4
                if (negative) score -= 6
                out += Candidate(cents, sign, i, score, before)
            }
        }
        return out
    }

    private fun direction(best: Candidate?, rows: List<String>, all: String): Direction {
        when (best?.sign) {
            '-' -> return Direction.EXPENSE
            '+' -> return Direction.INCOME
        }
        if (best != null) {
            // 银行短信：“支出人民币25.00元”“收入人民币15,000.00元”，以金额前面的词为准
            val near = best.before
            val inc = INCOME_NEAR.filter { near.contains(it) }.maxOfOrNull { near.lastIndexOf(it) } ?: -1
            val exp = EXPENSE_NEAR.filter { near.contains(it) }.maxOfOrNull { near.lastIndexOf(it) } ?: -1
            if (inc > exp) return Direction.INCOME
            if (exp > inc) return Direction.EXPENSE
        }
        val income = INCOME_WORDS.count { all.contains(it) }
        val expense = EXPENSE_WORDS.count { all.contains(it) }
        return if (income > expense) Direction.INCOME else Direction.EXPENSE
    }

    private fun merchant(rows: List<String>, best: Candidate?): String {
        // 1. 账单详情页：单独成行的金额上方一行就是商户 / 对方名称
        if (best != null) {
            val amountRow = stripNonAmounts(rows[best.row])
            val standalone = AMOUNT.replace(amountRow, "").replace("¥", "").replace("元", "").isBlank()
            if (standalone) {
                for (i in best.row - 1 downTo maxOf(0, best.row - 2)) {
                    val r = rows[i]
                    if (STATUS_WORDS.any { r.contains(it) }) continue
                    if (r.length <= 8 && AMOUNT_LABELS.any { r.contains(it) }) continue // “付款金额”这类标签行
                    if (looksLikeName(r)) return clean(r)
                    break
                }
            }
        }
        // 2. “收款方 瑞幸咖啡” / “商户全称:…”，值可能在同一行或下一行。
        //    每行取最长的匹配标签，避免“收款方”误配“收款方全称 …”
        val labelOf = rows.map { row -> MERCHANT_LABELS.filter { row.startsWith(it) }.maxByOrNull { it.length } }
        for (label in MERCHANT_LABELS) {
            rows.forEachIndexed { i, row ->
                if (labelOf[i] != label) return@forEachIndexed
                val rest = row.removePrefix(label).trimStart(':', ' ')
                if (rest.isNotEmpty() && looksLikeName(rest)) return clean(rest)
                val next = rows.getOrNull(i + 1)
                if (rest.isEmpty() && next != null && labelOf[i + 1] == null && looksLikeName(next)) return clean(next)
            }
        }
        // 3. “转账给张三”
        Regex("""转账给\s*([^\s,，。]{1,20})""").find(rows.joinToString(" "))?.let { return clean(it.groupValues[1]) }
        // 4. 银行短信的签名：“【招商银行】…”
        Regex("""【([^】]{2,12})】""").find(rows.joinToString(" "))?.let { return clean(it.groupValues[1]) }
        return ""
    }

    private fun looksLikeName(s: String): Boolean {
        val t = s.trim()
        if (t.length !in 2..40) return false
        if (!t.any { it.isLetter() }) return false
        if (FULL_DATE_TIME.containsMatchIn(t) || CLOCK.containsMatchIn(t)) return false
        if (TIME_LABELS.any { t.startsWith(it) }) return false
        val digits = t.count { it.isDigit() }
        return digits * 2 < t.length
    }

    private fun clean(s: String) = s.trim().trimEnd('>', '›', ' ').take(40)

    private fun time(rows: List<String>, now: LocalDateTime): LocalDateTime? {
        var fallback: LocalDateTime? = null
        for (row in rows) {
            val t = timeIn(row, now) ?: continue
            if (TIME_LABELS.any { row.startsWith(it) }) return t
            if (fallback == null) fallback = t
        }
        return fallback
    }

    private fun timeIn(row: String, now: LocalDateTime): LocalDateTime? {
        FULL_DATE_TIME.find(row)?.let { m ->
            val g = m.groupValues
            return dt(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toIntOrNull() ?: 0)
        }
        MONTH_DAY_TIME.find(row)?.let { m ->
            val g = m.groupValues
            return withInferredYear(now, g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toIntOrNull() ?: 0)
        }
        FULL_DATE.find(row)?.let { m ->
            val g = m.groupValues
            return dt(g[1].toInt(), g[2].toInt(), g[3].toInt(), 12, 0, 0)
        }
        MONTH_DAY.find(row)?.let { m ->
            val g = m.groupValues
            return withInferredYear(now, g[1].toInt(), g[2].toInt(), 12, 0, 0)
        }
        return null
    }

    /** 没有年份时取今年；如果这样得到的时间在未来（例如一月份导入去年十二月的截图），取去年。 */
    private fun withInferredYear(now: LocalDateTime, month: Int, day: Int, h: Int, min: Int, s: Int): LocalDateTime? {
        val thisYear = dt(now.year, month, day, h, min, s) ?: return null
        return if (thisYear.isAfter(now.plusDays(1))) dt(now.year - 1, month, day, h, min, s) else thisYear
    }

    private fun dt(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): LocalDateTime? =
        runCatching { LocalDateTime.of(y, mo, d, h, mi, s) }.getOrNull()

    private fun channel(all: String): String = when {
        listOf("支付宝", "花呗", "余额宝", "账单分类", "创建时间").any { all.contains(it) } -> "支付宝"
        listOf("微信", "零钱", "财付通", "收单机构").any { all.contains(it) } -> "微信支付"
        listOf("银行", "储蓄卡", "信用卡", "人民币").any { all.contains(it) } -> "银行"
        listOf("云闪付", "银联").any { all.contains(it) } -> "云闪付"
        else -> ""
    }
}

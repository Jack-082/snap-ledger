package io.github.snapledger.parse

import io.github.snapledger.model.Direction
import io.github.snapledger.model.Money
import java.time.LocalDateTime

/** 截图来自哪个支付渠道。 */
enum class PayApp(val label: String) {
    WECHAT("微信支付"), ALIPAY("支付宝"), UNIONPAY("云闪付"), BANK("银行"), UNKNOWN("")
}

/** 单笔详情页（支付成功、账单详情、短信），还是包含多笔的账单列表。 */
enum class PageKind { DETAIL, LIST }

/** 从截图中解析出的一笔账；识别不出的字段为 null / 空，由用户在确认页补全。 */
data class ParsedReceipt(
    val amountCents: Long?,
    val direction: Direction,
    val merchant: String,
    val time: LocalDateTime?,
    val category: String,
    /** 支付渠道，例如“微信支付”“支付宝”“银行”，写进备注。 */
    val channel: String,
    /** 金额是否有明确依据；为 false 时界面提示用户核对。 */
    val confident: Boolean,
    /** 支付方式 / 账户，例如“零钱”“花呗”“招商银行储蓄卡(4132)”。 */
    val account: String = "",
    /** 交易关闭、已全额退款等提示。 */
    val warning: String? = null,
)

data class ParseResult(val kind: PageKind, val app: PayApp, val items: List<ParsedReceipt>)

/**
 * 支付 / 到账截图解析器。
 *
 * 不写死某个 App 的版式，而是对每个候选金额打分：
 * - 版面：字号明显大于正文的数字（账单页的大号金额）大幅加分；
 * - 文字：正负号、¥、“实付 / 付款金额 / 收入”等标签加分，“优惠 / 原价 / 余额”等减分。
 * 商户、时间、支付方式、支付宝“账单分类”从“标签 值”键值对中读取。
 * 有两笔以上带正负号的金额时按账单列表处理（见 [BillListParser]）。
 */
object ReceiptParser {

    /** 只有文字（测试、短信）时的入口：按单笔解析。 */
    fun parse(rawRows: List<String>, now: LocalDateTime = LocalDateTime.now()): ParsedReceipt =
        parseDetail(Layout.rows(OcrPage.fromRows(rawRows)), now)

    /** 截图入口：自动判断是单笔详情还是账单列表。 */
    fun parsePage(page: OcrPage, now: LocalDateTime = LocalDateTime.now()): ParseResult {
        val rows = Layout.rows(page)
        val app = detectApp(rows.joinToString("\n") { it.text })
        val list = BillListParser.parse(rows, app, now)
        return if (list.size >= 2) ParseResult(PageKind.LIST, app, list)
        else ParseResult(PageKind.DETAIL, app, listOf(parseDetail(rows, now)))
    }

    fun detectApp(all: String): PayApp = when {
        listOf("支付宝", "花呗", "余额宝", "账单分类", "创建时间", "收款方全称", "蚂蚁").any { all.contains(it) } -> PayApp.ALIPAY
        listOf("微信", "零钱", "财付通", "收单机构", "当前状态").any { all.contains(it) } -> PayApp.WECHAT
        listOf("云闪付", "银联").any { all.contains(it) } -> PayApp.UNIONPAY
        listOf("银行", "储蓄卡", "信用卡", "人民币", "尾号").any { all.contains(it) } -> PayApp.BANK
        else -> PayApp.UNKNOWN
    }

    internal fun parseDetail(rows: List<TextRow>, now: LocalDateTime): ParsedReceipt {
        val texts = rows.map { it.text }
        val all = texts.joinToString("\n")
        val best = amountCandidates(rows).maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { -it.row }) // 同分取靠上的
        val direction = direction(best, all)
        val merchant = merchant(texts, best)
        val appCategory = Lexicon.valueAfter(texts, Lexicon.CATEGORY_LABELS)?.let { Categorizer.fromAppCategory(it, direction) }
        return ParsedReceipt(
            amountCents = best?.cents,
            direction = direction,
            merchant = merchant,
            time = time(texts, now),
            category = appCategory ?: Categorizer.categorize(direction, merchant, all),
            channel = detectApp(all).label,
            confident = best != null && best.score >= 5,
            account = Lexicon.valueAfter(texts, Lexicon.ACCOUNT_LABELS)?.let { Lexicon.clean(it).take(30) } ?: "",
            warning = Lexicon.statusWarning(all, direction),
        )
    }

    private data class Candidate(val cents: Long, val sign: Char?, val row: Int, val score: Int, val before: String)

    private fun amountCandidates(rows: List<TextRow>): List<Candidate> {
        val median = Layout.medianHeight(rows)
        val out = mutableListOf<Candidate>()
        rows.forEachIndexed { i, r ->
            val row = Lexicon.stripNonAmounts(r.text)
            val prev = rows.getOrNull(i - 1)?.let { Lexicon.stripNonAmounts(it.text) }
            // 上一行是单独的标签（例如“付款金额”下一行才是“¥25.00”）
            val prevLabel = prev?.takeIf { it.trim().length <= 8 && Lexicon.AMOUNT.find(it) == null }?.trim()

            for (m in Lexicon.AMOUNT.findAll(row)) {
                val sign = m.groupValues[1].firstOrNull()
                val hasCurrency = m.groupValues[2].isNotEmpty() || m.groupValues[4].isNotEmpty()
                val number = m.groupValues[3]
                val hasDecimal = number.contains('.')
                val cents = Money.parseCents(number) ?: continue
                if (cents <= 0) continue

                val before = row.substring(0, m.range.first).takeLast(10)
                val standalone = row.removeRange(m.range).replace("¥", "").replace("元", "").isBlank()
                val atStart = m.range.first <= 1
                val labeled = Lexicon.AMOUNT_LABELS.any { before.contains(it) } ||
                    (atStart && prevLabel != null && Lexicon.AMOUNT_LABELS.any { prevLabel.contains(it) })
                val negative = Lexicon.NEGATIVE_LABELS.any { before.contains(it) } ||
                    (atStart && prevLabel != null && Lexicon.NEGATIVE_LABELS.any { prevLabel.contains(it) })

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
                // 版面：账单页的金额是全页最大号的数字
                val ratio = r.lineAt(m.groups[3]!!.range.first).height / median
                if (ratio >= 1.6f) score += 6 else if (ratio >= 1.25f) score += 3
                out += Candidate(cents, sign, i, score, before)
            }
        }
        return out
    }

    private fun direction(best: Candidate?, all: String): Direction {
        when (best?.sign) {
            '-' -> return Direction.EXPENSE
            '+' -> return Direction.INCOME
        }
        if (best != null) {
            // 银行短信：“支出人民币25.00元”“收入人民币15,000.00元”，以金额前面最近的词为准
            val near = best.before
            val inc = Lexicon.INCOME_NEAR.filter { near.contains(it) }.maxOfOrNull { near.lastIndexOf(it) } ?: -1
            val exp = Lexicon.EXPENSE_NEAR.filter { near.contains(it) }.maxOfOrNull { near.lastIndexOf(it) } ?: -1
            if (inc > exp) return Direction.INCOME
            if (exp > inc) return Direction.EXPENSE
        }
        // “收款方式 零钱”出现在收款页，不能当作付款页的“收款方”
        val text = all.replace("收款方式", "")
        val income = Lexicon.INCOME_WORDS.count { text.contains(it) }
        val expense = Lexicon.EXPENSE_WORDS.count { text.contains(it) }
        return if (income > expense) Direction.INCOME else Direction.EXPENSE
    }

    private fun merchant(rows: List<String>, best: Candidate?): String {
        // 1. 账单详情页：单独成行的金额上方一行就是商户 / 对方名称
        if (best != null) {
            val amountRow = Lexicon.stripNonAmounts(rows[best.row])
            val standalone = Lexicon.AMOUNT.replace(amountRow, "").replace("¥", "").replace("元", "").isBlank()
            if (standalone) {
                for (i in best.row - 1 downTo maxOf(0, best.row - 2)) {
                    val r = rows[i]
                    if (Lexicon.STATUS_WORDS.any { r.contains(it) } || r in Lexicon.PAGE_TITLES) continue
                    if (r.length <= 8 && Lexicon.AMOUNT_LABELS.any { r.contains(it) }) continue // “付款金额”这类标签行
                    if (Lexicon.looksLikeName(r) && !Lexicon.isLabelRow(r)) return Lexicon.clean(r)
                    break
                }
            }
        }
        // 2. “收款方 瑞幸咖啡” / “商户全称:…”，值可能在同一行或下一行
        Lexicon.valueAfter(rows, Lexicon.MERCHANT_LABELS, Lexicon::looksLikeName)?.let { return Lexicon.clean(it) }
        // 3. “转账给张三”
        val joined = rows.joinToString(" ")
        Regex("""转账给\s*([^\s,，。]{1,20})""").find(joined)?.let { return Lexicon.clean(it.groupValues[1]) }
        // 4. 银行短信的签名：“【招商银行】…”
        Regex("""【([^】]{2,12})】""").find(joined)?.let { return Lexicon.clean(it.groupValues[1]) }
        return ""
    }

    private fun time(rows: List<String>, now: LocalDateTime): LocalDateTime? {
        var fallback: LocalDateTime? = null
        for (row in rows) {
            val t = TimeParser.find(row, now) ?: continue
            if (Lexicon.TIME_LABELS.any { row.startsWith(it) }) return t
            if (fallback == null) fallback = t
        }
        return fallback
    }
}

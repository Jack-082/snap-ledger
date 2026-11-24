package io.github.snapledger.parse

import io.github.snapledger.model.Direction
import io.github.snapledger.model.Money
import java.time.LocalDateTime

/**
 * 账单列表截图（微信“账单”、支付宝“账单”、云闪付“我的账单”）：一张图里有多笔。
 *
 * 列表的每一笔都是“商户名 …… -38.00”一行，下面一行是时间（“11月20日 12:30”“11-18 08:45”“昨天 20:11”）。
 * 所以以“带正负号、两位小数的金额”为锚点切分条目：锚点行去掉金额就是商户，
 * 到下一个锚点之间的一两行里找时间和分类文字。月份分组标题“2026年11月”提供年份，
 * 标题旁的“支出 ¥1,234 收入 ¥200”汇总不带正负号，不会被当成条目。
 */
internal object BillListParser {

    private val SIGNED = Regex("""(?<![\d.,])([+-])\s?¥?\s?(\d{1,3}(?:,\d{3})+\.\d{2}|\d+\.\d{2})(?![\d.,]*\d)""")
    private val SUMMARY = listOf("合计", "共支出", "总支出", "月支出", "本月")

    fun parse(rows: List<TextRow>, app: PayApp, now: LocalDateTime): List<ParsedReceipt> {
        val texts = rows.map { it.text }
        val anchors = texts.mapIndexedNotNull { i, t -> anchor(t)?.let { i to it } }
        if (anchors.size < 2) return emptyList()

        val out = mutableListOf<ParsedReceipt>()
        var year: Int? = null
        var scanned = 0
        anchors.forEachIndexed { k, (i, m) ->
            while (scanned <= i) {
                TimeParser.YEAR_MONTH.find(texts[scanned])?.let { year = it.groupValues[1].toInt() }
                scanned++
            }
            val prevAnchor = if (k > 0) anchors[k - 1].first else -1
            val next = anchors.getOrNull(k + 1)?.first ?: texts.size
            val direction = if (m.groupValues[1] == "+") Direction.INCOME else Direction.EXPENSE
            val cents = Money.parseCents(m.groupValues[2]) ?: return@forEachIndexed
            val detailRows = texts.subList(i + 1, minOf(next, i + 3))

            var merchant = Lexicon.clean(TimeParser.strip(texts[i].removeRange(m.range)))
            if (!Lexicon.looksLikeName(merchant)) {
                val above = texts.getOrNull(i - 1)
                merchant = if (i - 1 > prevAnchor && above != null && Lexicon.looksLikeName(above)) Lexicon.clean(above) else ""
            }
            val time = (listOf(texts[i]) + detailRows).firstNotNullOfOrNull { TimeParser.find(it, now, year) }
            val extra = detailRows.joinToString(" ")
            val alt = rows[i].lineAt(m.range.first).alt
            out += ParsedReceipt(
                amountCents = cents,
                direction = direction,
                merchant = merchant,
                time = time,
                category = Categorizer.fromAppCategory(extra, direction) ?: Categorizer.categorize(direction, merchant, extra),
                channel = app.label,
                confident = alt == null,
                amountAlt = alt,
                warning = Lexicon.statusWarning(texts[i] + " " + extra, direction),
            )
        }
        return out
    }

    /** 一行里恰好有一个带符号的金额，且不是优惠 / 汇总行，才算一笔。 */
    private fun anchor(text: String): MatchResult? {
        if (Lexicon.NEGATIVE_LABELS.any { text.contains(it) } || SUMMARY.any { text.contains(it) }) return null
        return SIGNED.findAll(Lexicon.stripNonAmounts(text)).singleOrNull()
    }
}

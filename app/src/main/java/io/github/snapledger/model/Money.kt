package io.github.snapledger.model

import java.math.BigDecimal
import java.math.RoundingMode

/** 金额统一用“分”（Long）保存，避免浮点误差。 */
object Money {

    private val CLEAN = Regex("[¥￥,，\\s元]")

    /** "1,234.56" / "¥25" / "25.5元" → 分；无法解析或为负数时返回 null。 */
    fun parseCents(text: String): Long? {
        val cleaned = text.replace(CLEAN, "").replace('。', '.')
        if (cleaned.isEmpty() || !cleaned.matches(Regex("\\d+(\\.\\d{1,2})?"))) return null
        return runCatching {
            BigDecimal(cleaned).setScale(2, RoundingMode.UNNECESSARY).movePointRight(2).longValueExact()
        }.getOrNull()
    }

    /** 12345 → "123.45"，123456789 → "1,234,567.89"。 */
    fun format(cents: Long): String {
        val abs = kotlin.math.abs(cents)
        val yuan = "%,d".format(java.util.Locale.US, abs / 100)
        val fen = "%02d".format(abs % 100)
        return (if (cents < 0) "-" else "") + "$yuan.$fen"
    }

    fun formatSigned(cents: Long, direction: Direction): String =
        (if (direction == Direction.INCOME) "+" else "-") + format(cents)
}

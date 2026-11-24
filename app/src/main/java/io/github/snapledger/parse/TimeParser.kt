package io.github.snapledger.parse

import java.time.LocalDateTime

/** 截图里的日期时间：完整日期、无年份的“11月20日 12:30”“11-18 08:45”、相对的“今天 / 昨天 12:30”。 */
internal object TimeParser {

    val FULL_DATE_TIME = Regex("""(\d{4})\s*[年\-/.]\s*(\d{1,2})\s*[月\-/.]\s*(\d{1,2})\s*日?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    val MONTH_DAY_TIME = Regex("""(?<!\d)(\d{1,2})\s*月\s*(\d{1,2})\s*日\s*(?:周.|星期.)?\s*(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    val RELATIVE_TIME = Regex("""(今天|昨天|前天)\s*(\d{1,2}):(\d{2})""")
    val MD_DASH_TIME = Regex("""(?<![\d\-/.])(\d{1,2})[-/](\d{1,2})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?""")
    val FULL_DATE = Regex("""(?<!\d)(\d{4})\s*[年\-/.]\s*(\d{1,2})\s*[月\-/.]\s*(\d{1,2})\s*日?""")
    val MONTH_DAY = Regex("""(?<!\d)(\d{1,2})\s*月\s*(\d{1,2})\s*日""")
    val CLOCK = Regex("""(?<!\d)\d{1,2}:\d{2}(?::\d{2})?(?!\d)""")

    /** 账单列表的分组标题“2026年11月”，用来给后面没有年份的条目定年份。 */
    val YEAR_MONTH = Regex("""(\d{4})\s*年\s*(\d{1,2})\s*月(?!\s*\d{1,2}\s*日)""")

    /** 抹掉日期时间时按这个顺序替换（长的在前）。 */
    val ALL = listOf(FULL_DATE_TIME, MONTH_DAY_TIME, RELATIVE_TIME, MD_DASH_TIME, FULL_DATE, MONTH_DAY, CLOCK)

    /** 去掉文本中的日期时间。 */
    fun strip(s: String): String = ALL.fold(s) { acc, re -> re.replace(acc, " ") }.replace(Regex("\\s+"), " ").trim()

    /** 在一行文字里找日期时间；[year] 为列表分组标题里的年份，没有时按 [now] 推断。 */
    fun find(row: String, now: LocalDateTime, year: Int? = null): LocalDateTime? {
        FULL_DATE_TIME.find(row)?.let { m ->
            val g = m.groupValues
            return dt(g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toIntOrNull() ?: 0)
        }
        MONTH_DAY_TIME.find(row)?.let { m ->
            val g = m.groupValues
            return yearless(now, year, g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toIntOrNull() ?: 0)
        }
        RELATIVE_TIME.find(row)?.let { m ->
            val g = m.groupValues
            val back = when (g[1]) { "昨天" -> 1L; "前天" -> 2L; else -> 0L }
            val d = now.toLocalDate().minusDays(back)
            return dt(d.year, d.monthValue, d.dayOfMonth, g[2].toInt(), g[3].toInt(), 0)
        }
        MD_DASH_TIME.find(row)?.let { m ->
            val g = m.groupValues
            return yearless(now, year, g[1].toInt(), g[2].toInt(), g[3].toInt(), g[4].toInt(), g[5].toIntOrNull() ?: 0)
        }
        FULL_DATE.find(row)?.let { m ->
            val g = m.groupValues
            return dt(g[1].toInt(), g[2].toInt(), g[3].toInt(), 12, 0, 0)
        }
        MONTH_DAY.find(row)?.let { m ->
            val g = m.groupValues
            return yearless(now, year, g[1].toInt(), g[2].toInt(), 12, 0, 0)
        }
        return null
    }

    /** 没有年份时取今年；如果这样得到的时间在未来（例如一月份导入去年十二月的截图），取去年。 */
    private fun yearless(now: LocalDateTime, year: Int?, month: Int, day: Int, h: Int, min: Int, s: Int): LocalDateTime? {
        if (year != null) return dt(year, month, day, h, min, s)
        val thisYear = dt(now.year, month, day, h, min, s) ?: return null
        return if (thisYear.isAfter(now.plusDays(1))) dt(now.year - 1, month, day, h, min, s) else thisYear
    }

    private fun dt(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): LocalDateTime? =
        runCatching { LocalDateTime.of(y, mo, d, h, mi, s) }.getOrNull()
}

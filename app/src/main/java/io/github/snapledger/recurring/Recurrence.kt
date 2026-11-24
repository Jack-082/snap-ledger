package io.github.snapledger.recurring

import java.time.LocalDateTime
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** 固定收支的时间计算（纯函数，便于测试）。 */
object Recurrence {

    /** 一次最多补记的周期数，避免起始月份填错时一下子生成大量记录。 */
    const val MAX_CATCH_UP = 24

    /** 某个周期的入账时刻；日期超过当月天数时取月末。 */
    fun scheduledAt(rule: RecurringRule, period: YearMonth): LocalDateTime =
        period.atDay(rule.dayOfMonth.coerceIn(1, period.lengthOfMonth())).atTime(rule.hour, rule.minute)

    /** 第一个尚未入账的周期。 */
    fun firstPending(rule: RecurringRule): YearMonth {
        val interval = rule.intervalMonths.coerceAtLeast(1).toLong()
        val last = rule.lastPosted ?: return rule.startMonth
        if (last.isBefore(rule.startMonth)) return rule.startMonth
        val k = ChronoUnit.MONTHS.between(rule.startMonth, last) / interval + 1
        return rule.startMonth.plusMonths(k * interval)
    }

    /** 到 [now] 为止应入账但还没入账的周期，按时间顺序。 */
    fun duePeriods(rule: RecurringRule, now: LocalDateTime, max: Int = MAX_CATCH_UP): List<YearMonth> {
        if (!rule.enabled) return emptyList()
        val interval = rule.intervalMonths.coerceAtLeast(1).toLong()
        val out = mutableListOf<YearMonth>()
        var p = firstPending(rule)
        while (!scheduledAt(rule, p).isAfter(now) && out.size < max) {
            out += p
            p = p.plusMonths(interval)
        }
        return out
    }

    /** 下一次入账时刻（可能已经过去，表示正在等待补记）；规则停用时为 null。 */
    fun nextRun(rule: RecurringRule): LocalDateTime? =
        if (rule.enabled) scheduledAt(rule, firstPending(rule)) else null

    /** 新建规则时的默认起始月：本月的入账时间已过则从下个月开始，避免一建好就补记本月。 */
    fun defaultStart(dayOfMonth: Int, hour: Int, minute: Int, now: LocalDateTime): YearMonth {
        val thisMonth = YearMonth.from(now)
        val at = thisMonth.atDay(dayOfMonth.coerceIn(1, thisMonth.lengthOfMonth())).atTime(hour, minute)
        return if (at.isAfter(now)) thisMonth else thisMonth.plusMonths(1)
    }
}

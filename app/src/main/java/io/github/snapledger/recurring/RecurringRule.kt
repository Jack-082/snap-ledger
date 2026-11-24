package io.github.snapledger.recurring

import io.github.snapledger.model.Direction
import java.time.YearMonth

/**
 * 固定收支：每隔 [intervalMonths] 个月，在 [dayOfMonth] 日 [hour]:[minute] 自动记一笔。
 * 例如“工资，每月 10 日 09:00，+15000”“视频会员，每月 3 日 08:00，-25”“年度会员，每 12 个月”。
 *
 * [dayOfMonth] 大于当月天数时取月末（31 日的规则在 2 月记在 28/29 日）。
 * [lastPosted] 是最后一次已入账的周期，用于补记和防止重复。
 */
data class RecurringRule(
    val id: Long = 0,
    val name: String,
    val amountCents: Long,
    val direction: Direction,
    val category: String,
    val dayOfMonth: Int,
    val hour: Int,
    val minute: Int,
    val intervalMonths: Int = 1,
    val startMonth: YearMonth,
    val enabled: Boolean = true,
    val lastPosted: YearMonth? = null,
    val note: String = "",
) {
    fun dedupKey(period: YearMonth) = "rule:$id:$period"

    val scheduleLabel: String
        get() {
            val every = when (intervalMonths) {
                1 -> "每月"
                3 -> "每季度"
                12 -> "每年"
                else -> "每 $intervalMonths 个月"
            }
            val day = if (dayOfMonth >= 31) "月末" else "${dayOfMonth} 日"
            return "$every $day %02d:%02d".format(hour, minute)
        }
}

package io.github.snapledger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.snapledger.model.Categories
import io.github.snapledger.model.Direction
import io.github.snapledger.model.Money
import io.github.snapledger.recurring.Recurrence
import io.github.snapledger.recurring.RecurringRule
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth

/** 编辑中的固定收支规则。 */
@Stable
class RuleDraft(val base: RecurringRule?) {
    var name by mutableStateOf(base?.name ?: "")
    var amount by mutableStateOf(base?.let { Money.format(it.amountCents).replace(",", "") } ?: "")
    var direction by mutableStateOf(base?.direction ?: Direction.EXPENSE)
    var category by mutableStateOf(base?.category ?: "会员订阅")
    var day by mutableStateOf(base?.dayOfMonth?.toString() ?: "1")
    var time by mutableStateOf(base?.let { "%02d:%02d".format(it.hour, it.minute) } ?: "09:00")
    var interval by mutableIntStateOf(base?.intervalMonths ?: 1)
    var start by mutableStateOf(base?.startMonth?.toString() ?: "")
    var note by mutableStateOf(base?.note ?: "")

    private val dayValue get() = day.trim().toIntOrNull()?.takeIf { it in 1..31 }
    private val timeValue get() = runCatching { LocalTime.parse(time.trim().padStart(5, '0')) }.getOrNull()
    private val startValue get() = runCatching { YearMonth.parse(start.trim()) }.getOrNull()

    val error: String?
        get() = when {
            name.isBlank() -> "请填写名称，例如“工资”“视频会员”"
            Money.parseCents(amount)?.takeIf { it > 0 } == null -> "请输入有效金额"
            dayValue == null -> "日期应为 1–31（31 表示月末）"
            timeValue == null -> "时间格式应为 09:00"
            startValue == null -> "起始月份格式应为 2026-11"
            else -> null
        }

    /** 根据日期和时间推算默认起始月（只在新建且用户没填时使用）。 */
    fun fillDefaultStart(now: LocalDateTime = LocalDateTime.now()) {
        if (base != null || start.isNotBlank()) return
        val d = dayValue ?: 1
        val t = timeValue ?: LocalTime.of(9, 0)
        start = Recurrence.defaultStart(d, t.hour, t.minute, now).toString()
    }

    fun toRule(): RecurringRule? {
        if (error != null) return null
        val t = timeValue!!
        return RecurringRule(
            id = base?.id ?: 0,
            name = name.trim(),
            amountCents = Money.parseCents(amount)!!,
            direction = direction,
            category = category,
            dayOfMonth = dayValue!!,
            hour = t.hour,
            minute = t.minute,
            intervalMonths = interval,
            startMonth = startValue!!,
            enabled = base?.enabled ?: true,
            lastPosted = base?.lastPosted,
            note = note.trim(),
        )
    }

    companion object {
        fun salary() = RuleDraft(null).apply {
            name = "工资"; direction = Direction.INCOME; category = "工资"; day = "10"; time = "09:00"
            fillDefaultStart()
        }

        fun membership() = RuleDraft(null).apply {
            name = "会员订阅"; direction = Direction.EXPENSE; category = "会员订阅"; day = "1"; time = "08:00"
            fillDefaultStart()
        }
    }
}

@Composable
fun RuleEditor(d: RuleDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Segmented(
            options = listOf(Direction.EXPENSE to "固定支出", Direction.INCOME to "固定收入"),
            selected = d.direction,
        ) { dir ->
            d.direction = dir
            if (d.category !in Categories.of(dir)) d.category = Categories.of(dir).first()
        }
        LedgerField("名称", d.name, placeholder = "工资 / 视频会员 / 房租") { d.name = it }
        LedgerField("金额（元）", d.amount, keyboard = KeyboardType.Decimal, placeholder = "0.00") { d.amount = it }
        FieldLabel("分类")
        Chips(Categories.of(d.direction), d.category) { d.category = it }
        FieldLabel("周期")
        Segmented(options = listOf(1 to "每月", 3 to "每季度", 12 to "每年"), selected = d.interval) { d.interval = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LedgerField("每期日期", d.day, Modifier.weight(1f), keyboard = KeyboardType.Number, placeholder = "1–31") { d.day = it }
            LedgerField("时刻", d.time, Modifier.weight(1f), placeholder = "09:00") { d.time = it }
        }
        LedgerField("起始月份", d.start, placeholder = "2026-12") { d.start = it }
        Caption("从起始月份开始，每期到设定时刻自动记一笔。起始月份早于本月时会补记之前的周期（最多 ${Recurrence.MAX_CATCH_UP} 期）；31 日表示月末。")
        LedgerField("备注", d.note) { d.note = it }
        d.error?.let { Caption(it, Palette.Red) }
    }
}

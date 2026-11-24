package io.github.snapledger.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.snapledger.model.Categories
import io.github.snapledger.model.Direction
import io.github.snapledger.model.Money
import io.github.snapledger.model.Source
import io.github.snapledger.model.Txn
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object TimeFmt {
    private val FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val LENIENT = listOf(
        DateTimeFormatter.ofPattern("yyyy-M-d H:mm"),
        DateTimeFormatter.ofPattern("yyyy/M/d H:mm"),
        DateTimeFormatter.ofPattern("yyyy-M-d H:mm:ss"),
    )

    fun format(millis: Long): String = FULL.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
    fun format(t: LocalDateTime): String = FULL.format(t)

    fun parse(s: String): Long? {
        val text = s.trim()
        val t = LENIENT.firstNotNullOfOrNull { f -> runCatching { LocalDateTime.parse(text, f) }.getOrNull() } ?: return null
        return t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }

    fun clock(millis: Long): String =
        DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))
}

/** 编辑中的一笔账（截图确认页和手动记账共用）。 */
@Stable
class TxnDraft(
    amount: String = "",
    direction: Direction = Direction.EXPENSE,
    category: String = "其他",
    merchant: String = "",
    note: String = "",
    time: String = TimeFmt.format(System.currentTimeMillis()),
) {
    var amount by mutableStateOf(amount)
    var direction by mutableStateOf(direction)
    var category by mutableStateOf(category)
    var merchant by mutableStateOf(merchant)
    var note by mutableStateOf(note)
    var time by mutableStateOf(time)

    val amountCents: Long? get() = Money.parseCents(amount)?.takeIf { it > 0 }
    val timeMillis: Long? get() = TimeFmt.parse(time)

    val error: String?
        get() = when {
            amountCents == null -> "请输入有效金额，例如 25.80"
            timeMillis == null -> "时间格式应为 2026-11-20 12:30"
            else -> null
        }

    fun toTxn(base: Txn? = null, source: Source = Source.MANUAL, dedupKey: String? = null): Txn? {
        val cents = amountCents ?: return null
        val millis = timeMillis ?: return null
        return Txn(
            id = base?.id ?: 0,
            amountCents = cents,
            direction = direction,
            category = category,
            merchant = merchant.trim(),
            note = note.trim(),
            timeMillis = millis,
            source = base?.source ?: source,
            dedupKey = base?.dedupKey ?: dedupKey,
        )
    }

    companion object {
        fun of(t: Txn) = TxnDraft(
            amount = Money.format(t.amountCents).replace(",", ""),
            direction = t.direction,
            category = t.category,
            merchant = t.merchant,
            note = t.note,
            time = TimeFmt.format(t.timeMillis),
        )
    }
}

@Composable
fun LedgerField(
    label: String,
    value: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    keyboard: KeyboardType = KeyboardType.Text,
    placeholder: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        label = { Text(label, fontSize = 12.sp) },
        placeholder = if (placeholder != null) {
            { Text(placeholder, fontSize = 14.sp, color = Palette.Faint) }
        } else null,
        singleLine = true,
        textStyle = Tabular.copy(fontSize = 15.sp, color = Palette.Ink),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Palette.Navy,
            unfocusedBorderColor = Palette.Rule,
            focusedLabelColor = Palette.Navy,
            cursorColor = Palette.Navy,
        ),
    )
}

/** 可换行的分类选择。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { option ->
            val on = option == selected
            Text(
                option,
                Modifier.clip(SmallShape)
                    .background(if (on) Palette.Navy else Palette.Paper)
                    .border(1.dp, if (on) Palette.Navy else Palette.Rule, SmallShape)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                fontSize = 13.sp,
                color = if (on) Palette.Paper else Palette.Ink,
            )
        }
    }
}

@Composable
fun TxnEditor(d: TxnDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Segmented(
            options = listOf(Direction.EXPENSE to "支出", Direction.INCOME to "收入"),
            selected = d.direction,
        ) { dir ->
            d.direction = dir
            if (d.category !in Categories.of(dir)) d.category = "其他"
        }
        LedgerField("金额（元）", d.amount, keyboard = KeyboardType.Decimal, placeholder = "0.00") { d.amount = it }
        FieldLabel("分类")
        val options = Categories.of(d.direction).let { if (d.category in it) it else it + d.category }
        Chips(options, d.category) { d.category = it }
        LedgerField("商户 / 对方", d.merchant) { d.merchant = it }
        LedgerField("时间", d.time, placeholder = "2026-11-20 12:30") { d.time = it }
        LedgerField("备注", d.note) { d.note = it }
        d.error?.let { Caption(it, Palette.Red) }
    }
}

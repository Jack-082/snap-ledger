package io.github.snapledger.ui

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.snapledger.data.Csv
import io.github.snapledger.data.LedgerDb
import io.github.snapledger.model.Direction
import io.github.snapledger.model.Money
import io.github.snapledger.model.Txn
import io.github.snapledger.recurring.Recurrence
import io.github.snapledger.recurring.RecurringPoster
import io.github.snapledger.recurring.RecurringRule
import io.github.snapledger.recurring.RecurringScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val db by lazy { LedgerDb.get(this) }
    private val zone: ZoneId get() = ZoneId.systemDefault()

    private var tick by mutableIntStateOf(0)
    private var tab by mutableIntStateOf(0)
    private var month by mutableStateOf(YearMonth.now())

    /** 正在编辑的账单：first = 原记录（新建为 null）。 */
    private var editingTxn by mutableStateOf<Pair<Txn?, TxnDraft>?>(null)
    private var editingRule by mutableStateOf<RuleDraft?>(null)

    private val pickImages = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { uris ->
        ImportActivity.start(this, uris)
    }

    private val exportCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) export(uri)
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        RecurringScheduler.ensurePeriodic(this)
        setContent { LedgerTheme { Surface(Modifier.fillMaxSize(), color = Palette.Tint) { Screen() } } }
    }

    override fun onResume() {
        super.onResume()
        // 打开 App 时顺手补记到期的固定收支，并刷新（截图导入页保存后回到这里）
        io { RecurringPoster.postDue(applicationContext); RecurringScheduler.scheduleNext(applicationContext) }
    }

    /** 在 IO 线程执行，完成后刷新界面。 */
    private fun io(block: suspend () -> Unit) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { block() }
            tick++
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun monthRange(m: YearMonth): Pair<Long, Long> =
        m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() to
            m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun export(uri: Uri) {
        val m = month
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (from, to) = monthRange(m)
                    val txns = db.txnsBetween(from, to)
                    contentResolver.openOutputStream(uri)!!.use { it.write(Csv.export(txns).toByteArray()) }
                    txns.size
                }
            }
            result.fold({ toast("已导出 $it 笔") }, { toast("导出失败：${it.message}") })
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private val versionLabel: String by lazy {
        runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        }.getOrDefault("")
    }

    // ---------------------------------------------------------------- 界面

    @Composable
    private fun Screen() {
        editingTxn?.let { (base, draft) -> TxnDialog(base, draft) }
        editingRule?.let { RuleDialog(it) }

        Column(
            Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.padding(start = 4.dp, top = 24.dp, bottom = 4.dp)) {
                Text("拾账", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, letterSpacing = (-0.3).sp)
                Spacer(Modifier.height(4.dp))
                Text("截一张图，拾一笔账 · 固定收支按时自动记", fontSize = 13.sp, color = Palette.Muted)
            }
            Segmented(options = listOf(0 to "账单", 1 to "固定收支"), selected = tab) { tab = it }
            if (tab == 0) LedgerTab() else RulesTab()
            Column(Modifier.padding(start = 4.dp, top = 4.dp, bottom = 24.dp)) {
                Caption("账本只保存在本机；截图在本机识别，不上传。卸载应用会删除数据，请定期导出 CSV。")
                Caption("拾账 SnapLedger v$versionLabel", Palette.Faint)
            }
        }
    }

    @Composable
    private fun LedgerTab() {
        val m = month
        val txns by produceState(emptyList<Txn>(), m, tick) {
            value = withContext(Dispatchers.IO) { monthRange(m).let { (a, b) -> db.txnsBetween(a, b) } }
        }
        val expense = txns.filter { it.direction == Direction.EXPENSE }
        val income = txns.filter { it.direction == Direction.INCOME }
        val expenseSum = expense.sumOf { it.amountCents }
        val incomeSum = income.sumOf { it.amountCents }

        // 月份切换
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            NavText("‹ 上月") { month = month.minusMonths(1) }
            Text(
                "${m.year} 年 ${m.monthValue} 月", Modifier.weight(1f),
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            NavText("下月 ›", enabled = m.isBefore(YearMonth.now())) { month = month.plusMonths(1) }
        }

        Section("本月概览") {
            Row(Modifier.fillMaxWidth()) {
                Figure("支出", Money.format(expenseSum), Palette.Ink, Modifier.weight(1f))
                Figure("收入", Money.format(incomeSum), Palette.Teal, Modifier.weight(1f))
                Figure("结余", Money.format(incomeSum - expenseSum), if (incomeSum >= expenseSum) Palette.Navy else Palette.Red, Modifier.weight(1f))
            }
            if (expenseSum > 0) {
                FieldLabel("支出分类")
                expense.groupBy { it.category }
                    .mapValues { (_, v) -> v.sumOf { it.amountCents } }
                    .entries.sortedByDescending { it.value }
                    .take(6)
                    .forEach { (cat, sum) -> CategoryBar(cat, sum, expenseSum) }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton("最新截图", Modifier.weight(1f)) {
                startActivity(ImportActivity.latestIntent(this@MainActivity))
            }
            SecondaryButton("相册多选", Modifier.weight(1f)) {
                pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }
            SecondaryButton("记一笔", Modifier.weight(1f)) { editingTxn = null to TxnDraft() }
        }
        Caption("付款或收款后截图，然后任选一种：在通知栏快捷开关里点“拾一笔”（需先在快捷开关编辑页添加）；在相册里“分享 → 拾账”；或回到这里点“最新截图”。微信 / 支付宝的账单列表截图可以一次导入多笔。")

        Section("明细 · ${txns.size} 笔") {
            if (txns.isEmpty()) {
                Caption("这个月还没有记录。")
            } else {
                txns.groupBy { Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate() }
                    .forEach { (day, list) -> DayGroup(day, list) }
            }
        }
        if (txns.isNotEmpty()) {
            SecondaryButton("导出本月 CSV", Modifier.fillMaxWidth()) {
                exportCsv.launch("账单-${m}.csv")
            }
        }
    }

    @Composable
    private fun NavText(text: String, enabled: Boolean = true, onClick: () -> Unit) {
        Text(
            text,
            Modifier.clip(RoundedCornerShape(6.dp)).clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            fontSize = 14.sp, color = if (enabled) Palette.Navy else Palette.Faint,
        )
    }

    @Composable
    private fun Figure(label: String, value: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier) {
        Column(modifier) {
            Text(label, fontSize = 12.sp, color = Palette.Muted)
            Spacer(Modifier.height(2.dp))
            Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = color, style = Tabular, maxLines = 1)
        }
    }

    @Composable
    private fun CategoryBar(category: String, sum: Long, total: Long) {
        val fraction = (sum.toFloat() / total).coerceIn(0f, 1f)
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(category, Modifier.width(64.dp), fontSize = 13.sp, color = Palette.Ink)
            Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Palette.Tint)) {
                Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Palette.Navy))
            }
            Text(
                "${Money.format(sum)}  ${(fraction * 100).toInt()}%", Modifier.width(120.dp),
                fontSize = 12.sp, color = Palette.Muted, style = Tabular,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }

    private val dayFmt = DateTimeFormatter.ofPattern("M 月 d 日 EEE", Locale.CHINA)

    @Composable
    private fun DayGroup(day: LocalDate, list: List<Txn>) {
        val out = list.filter { it.direction == Direction.EXPENSE }.sumOf { it.amountCents }
        val inc = list.filter { it.direction == Direction.INCOME }.sumOf { it.amountCents }
        Column {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp)) {
                Text(dayFmt.format(day), Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Palette.Muted)
                Text(
                    listOfNotNull(
                        out.takeIf { it > 0 }?.let { "支 ${Money.format(it)}" },
                        inc.takeIf { it > 0 }?.let { "收 ${Money.format(it)}" },
                    ).joinToString("  "),
                    fontSize = 12.sp, color = Palette.Muted, style = Tabular,
                )
            }
            list.forEach { TxnRow(it) }
        }
    }

    @Composable
    private fun TxnRow(t: Txn) {
        val income = t.direction == Direction.INCOME
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .clickable { editingTxn = t to TxnDraft.of(t) }
                .padding(vertical = 7.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Dot(if (income) Palette.Teal else Palette.Navy, 8)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t.merchant.ifEmpty { t.category }, fontSize = 14.sp, color = Palette.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(t.category, TimeFmt.clock(t.timeMillis), t.account, t.source.label, t.note).filter { it.isNotEmpty() }.joinToString(" · "),
                    fontSize = 11.sp, color = Palette.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                Money.formatSigned(t.amountCents, t.direction), fontSize = 15.sp, style = Tabular,
                fontWeight = FontWeight.Medium, color = if (income) Palette.Teal else Palette.Ink,
            )
        }
    }

    @Composable
    private fun TxnDialog(base: Txn?, draft: TxnDraft) {
        AlertDialog(
            onDismissRequest = { editingTxn = null },
            containerColor = Palette.Paper,
            title = { Text(if (base == null) "记一笔" else "编辑账单", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TxnEditor(draft)
                    if (base != null) {
                        Caption("来源：${base.source.label}")
                        TextButton(onClick = {
                            io { db.deleteTxn(base.id) }
                            editingTxn = null
                        }) { Text("删除这笔", color = Palette.Red) }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = draft.error == null, onClick = {
                    val txn = draft.toTxn(base) ?: return@TextButton
                    io { if (base == null) db.insertTxn(txn) else db.updateTxn(txn) }
                    // 记到别的月份时跳过去，免得用户以为没保存
                    month = YearMonth.from(Instant.ofEpochMilli(txn.timeMillis).atZone(zone))
                    editingTxn = null
                }) { Text("保存", color = if (draft.error == null) Palette.Navy else Palette.Faint, fontWeight = FontWeight.Medium) }
            },
            dismissButton = { TextButton(onClick = { editingTxn = null }) { Text("取消", color = Palette.Muted) } },
        )
    }

    // ---------------------------------------------------------------- 固定收支

    @Composable
    private fun RulesTab() {
        val rules by produceState(emptyList<RecurringRule>(), tick) {
            value = withContext(Dispatchers.IO) { db.rules() }
        }
        Section("固定收支") {
            Caption("工资、会员订阅、房租等固定金额的收支，到设定的日期和时刻自动入账，并发送通知。手机关机或应用被系统清理时，下次运行会补记，入账时间仍按设定时刻。")
            if (rules.isEmpty()) {
                Caption("还没有规则。")
            } else {
                rules.forEach { RuleRow(it) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("+ 固定收入（工资）", Modifier.weight(1f)) { editingRule = RuleDraft.salary() }
            SecondaryButton("+ 固定支出（会员）", Modifier.weight(1f)) { editingRule = RuleDraft.membership() }
        }
        SecondaryButton("立即检查并补记", Modifier.fillMaxWidth()) {
            lifecycleScope.launch {
                val posted = withContext(Dispatchers.IO) {
                    RecurringPoster.postDue(applicationContext).also { RecurringScheduler.scheduleNext(applicationContext) }
                }
                tick++
                toast(if (posted.isEmpty()) "没有到期的固定收支" else "已入账 ${posted.size} 笔")
            }
        }
    }

    private val nextFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    @Composable
    private fun RuleRow(r: RecurringRule) {
        val income = r.direction == Direction.INCOME
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .clickable { editingRule = RuleDraft(r) }
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, fontSize = 14.sp, color = Palette.Ink, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        Money.formatSigned(r.amountCents, r.direction), fontSize = 14.sp, style = Tabular,
                        color = if (income) Palette.Teal else Palette.Ink,
                    )
                }
                Text(
                    "${r.scheduleLabel} · ${r.category} · " +
                        (Recurrence.nextRun(r)?.let { "下次 ${nextFmt.format(it)}" } ?: "已暂停"),
                    fontSize = 11.sp, color = Palette.Muted,
                )
            }
            Switch(
                checked = r.enabled,
                onCheckedChange = { on ->
                    io {
                        db.saveRule(r.copy(enabled = on))
                        RecurringScheduler.scheduleNext(applicationContext)
                    }
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Palette.Navy),
            )
        }
    }

    @Composable
    private fun RuleDialog(draft: RuleDraft) {
        val base = draft.base
        AlertDialog(
            onDismissRequest = { editingRule = null },
            containerColor = Palette.Paper,
            title = { Text(if (base == null) "新建固定收支" else "编辑固定收支", fontWeight = FontWeight.SemiBold) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RuleEditor(draft)
                    if (base != null) {
                        base.lastPosted?.let { Caption("已入账到 $it 期；删除规则不会删除已入账的记录。") }
                        TextButton(onClick = {
                            io {
                                db.deleteRule(base.id)
                                RecurringScheduler.scheduleNext(applicationContext)
                            }
                            editingRule = null
                        }) { Text("删除规则", color = Palette.Red) }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = draft.error == null, onClick = {
                    val rule = draft.toRule() ?: return@TextButton
                    editingRule = null
                    askNotificationPermission()
                    lifecycleScope.launch {
                        val posted = withContext(Dispatchers.IO) {
                            db.saveRule(rule)
                            RecurringPoster.postDue(applicationContext).also { RecurringScheduler.scheduleNext(applicationContext) }
                        }
                        tick++
                        if (posted.isNotEmpty()) toast("已补记 ${posted.size} 笔")
                    }
                }) { Text("保存", color = if (draft.error == null) Palette.Navy else Palette.Faint, fontWeight = FontWeight.Medium) }
            },
            dismissButton = { TextButton(onClick = { editingRule = null }) { Text("取消", color = Palette.Muted) } },
        )
    }
}

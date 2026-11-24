package io.github.snapledger.recurring

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import io.github.snapledger.R
import io.github.snapledger.data.LedgerDb
import io.github.snapledger.model.Direction
import io.github.snapledger.model.Money
import io.github.snapledger.model.Source
import io.github.snapledger.model.Txn
import io.github.snapledger.ui.MainActivity
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** 把到期的固定收支写入账本。 */
object RecurringPoster {

    /** 返回本次新入账的记录。重复调用是安全的：每个周期有唯一的 dedupKey。 */
    fun postDue(context: Context, now: LocalDateTime = LocalDateTime.now()): List<Txn> {
        val db = LedgerDb.get(context)
        val zone = ZoneId.systemDefault()
        val posted = mutableListOf<Txn>()
        for (rule in db.rules()) {
            val periods = Recurrence.duePeriods(rule, now)
            if (periods.isEmpty()) continue
            val txns = periods.map { p ->
                Txn(
                    amountCents = rule.amountCents,
                    direction = rule.direction,
                    category = rule.category,
                    merchant = rule.name,
                    note = rule.note.ifEmpty { "固定收支自动入账" },
                    timeMillis = Recurrence.scheduledAt(rule, p).atZone(zone).toInstant().toEpochMilli(),
                    source = Source.RECURRING,
                    dedupKey = rule.dedupKey(p),
                )
            }
            posted += db.postRecurring(rule.id, txns, periods.last())
        }
        return posted
    }
}

class RecurringWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val posted = RecurringPoster.postDue(applicationContext)
        if (posted.isNotEmpty()) Notifier.posted(applicationContext, posted)
        RecurringScheduler.scheduleNext(applicationContext, fromWorker = inputData.getBoolean(KEY_CHAIN, false))
        return Result.success()
    }

    companion object {
        const val KEY_CHAIN = "chain"
    }
}

/**
 * 两层调度：
 * 1. 每小时一次的周期任务，兜底补记（关机、被系统杀掉后也能补上）；
 * 2. 一个单次任务，定在最近一条规则的入账时刻，尽量准时。
 * 入账记录的时间始终是规则设定的时刻，与任务实际执行的时间无关。
 */
object RecurringScheduler {
    private const val PERIODIC = "recurring_periodic"
    private const val NEXT = "recurring_next"

    fun ensurePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<RecurringWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** 规则变化后调用：按最近的入账时刻重新安排单次任务。 */
    fun scheduleNext(context: Context, fromWorker: Boolean = false) {
        val now = LocalDateTime.now()
        val next = LedgerDb.get(context).rules().mapNotNull { Recurrence.nextRun(it) }.minOrNull()
        val wm = WorkManager.getInstance(context)
        if (next == null) {
            if (!fromWorker) wm.cancelUniqueWork(NEXT)
            return
        }
        val delay = Duration.between(now, next).toMillis().coerceAtLeast(0)
        val req = OneTimeWorkRequestBuilder<RecurringWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(RecurringWorker.KEY_CHAIN to true))
            .build()
        // 在任务自身内部用 REPLACE 会把正在运行的自己取消，所以链上用 APPEND_OR_REPLACE
        val policy = if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.REPLACE
        wm.enqueueUniqueWork(NEXT, policy, req)
    }
}

object Notifier {
    private const val CHANNEL = "recurring"

    fun posted(context: Context, txns: List<Txn>) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= 33
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "固定收支入账", NotificationManager.IMPORTANCE_DEFAULT))

        val lines = txns.map { t ->
            "${t.merchant}  ${Money.formatSigned(t.amountCents, t.direction)}"
        }
        val income = txns.filter { it.direction == Direction.INCOME }.sumOf { it.amountCents }
        val expense = txns.filter { it.direction == Direction.EXPENSE }.sumOf { it.amountCents }
        val title = "已自动记账 ${txns.size} 笔"
        val summary = listOfNotNull(
            income.takeIf { it > 0 }?.let { "收入 ${Money.format(it)}" },
            expense.takeIf { it > 0 }?.let { "支出 ${Money.format(it)}" },
        ).joinToString(" · ")

        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(summary.ifEmpty { lines.first() })
            .setStyle(NotificationCompat.InboxStyle().also { s -> lines.take(6).forEach { s.addLine(it) } })
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(1001, n) }
    }
}

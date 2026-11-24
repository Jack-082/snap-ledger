package io.github.snapledger.recurring

import io.github.snapledger.model.Direction
import java.time.LocalDateTime
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecurrenceTest {

    private fun rule(
        day: Int = 10, hour: Int = 9, minute: Int = 0, interval: Int = 1,
        start: String = "2026-09", last: String? = null, enabled: Boolean = true,
    ) = RecurringRule(
        id = 7, name = "工资", amountCents = 1_500_000, direction = Direction.INCOME, category = "工资",
        dayOfMonth = day, hour = hour, minute = minute, intervalMonths = interval,
        startMonth = YearMonth.parse(start), enabled = enabled, lastPosted = last?.let(YearMonth::parse),
    )

    @Test
    fun catchesUpMissedMonthsUpToNow() {
        val due = Recurrence.duePeriods(rule(), LocalDateTime.of(2026, 11, 24, 12, 0))
        assertEquals(listOf("2026-09", "2026-10", "2026-11").map(YearMonth::parse), due)
    }

    @Test
    fun notDueBeforeScheduledMinute() {
        val r = rule(start = "2026-11")
        assertTrue(Recurrence.duePeriods(r, LocalDateTime.of(2026, 11, 10, 8, 59)).isEmpty())
        assertEquals(listOf(YearMonth.of(2026, 11)), Recurrence.duePeriods(r, LocalDateTime.of(2026, 11, 10, 9, 0)))
    }

    @Test
    fun skipsAlreadyPosted() {
        val r = rule(last = "2026-11")
        assertTrue(Recurrence.duePeriods(r, LocalDateTime.of(2026, 11, 30, 23, 0)).isEmpty())
        assertEquals(LocalDateTime.of(2026, 12, 10, 9, 0), Recurrence.nextRun(r))
    }

    @Test
    fun day31ClampsToMonthEnd() {
        val r = rule(day = 31, start = "2027-02")
        assertEquals(LocalDateTime.of(2027, 2, 28, 9, 0), Recurrence.scheduledAt(r, YearMonth.of(2027, 2)))
        assertEquals(LocalDateTime.of(2028, 2, 29, 9, 0), Recurrence.scheduledAt(r, YearMonth.of(2028, 2)))
        assertEquals(LocalDateTime.of(2027, 4, 30, 9, 0), Recurrence.scheduledAt(r, YearMonth.of(2027, 4)))
    }

    @Test
    fun yearlyStaysAlignedToStartMonth() {
        val r = rule(interval = 12, start = "2025-03", last = "2025-03")
        assertEquals(YearMonth.of(2026, 3), Recurrence.firstPending(r))
        assertEquals(listOf(YearMonth.of(2026, 3)), Recurrence.duePeriods(r, LocalDateTime.of(2026, 11, 1, 0, 0)))
    }

    @Test
    fun quarterlyAfterLastPosted() {
        val r = rule(interval = 3, start = "2026-01", last = "2026-07")
        assertEquals(YearMonth.of(2026, 10), Recurrence.firstPending(r))
    }

    @Test
    fun catchUpIsCapped() {
        val r = rule(start = "2020-01")
        assertEquals(Recurrence.MAX_CATCH_UP, Recurrence.duePeriods(r, LocalDateTime.of(2026, 11, 24, 0, 0)).size)
    }

    @Test
    fun disabledRuleNeverDue() {
        val r = rule(enabled = false)
        assertTrue(Recurrence.duePeriods(r, LocalDateTime.of(2026, 11, 24, 0, 0)).isEmpty())
        assertNull(Recurrence.nextRun(r))
    }

    @Test
    fun defaultStartSkipsPassedMonth() {
        val now = LocalDateTime.of(2026, 11, 24, 18, 0)
        assertEquals(YearMonth.of(2026, 12), Recurrence.defaultStart(10, 9, 0, now))
        assertEquals(YearMonth.of(2026, 11), Recurrence.defaultStart(28, 9, 0, now))
    }

    @Test
    fun dedupKeyAndLabel() {
        val r = rule()
        assertEquals("rule:7:2026-11", r.dedupKey(YearMonth.of(2026, 11)))
        assertEquals("每月 10 日 09:00", r.scheduleLabel)
        assertEquals("每年 月末 09:00", rule(day = 31, interval = 12).scheduleLabel)
    }
}

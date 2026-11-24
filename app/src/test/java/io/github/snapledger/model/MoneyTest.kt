package io.github.snapledger.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test
    fun parse() {
        assertEquals(2500L, Money.parseCents("25"))
        assertEquals(2550L, Money.parseCents("25.5"))
        assertEquals(123456L, Money.parseCents("¥1,234.56"))
        assertEquals(1000L, Money.parseCents("10元"))
        assertNull(Money.parseCents("12.345"))
        assertNull(Money.parseCents("abc"))
        assertNull(Money.parseCents(""))
        assertNull(Money.parseCents("-5"))
    }

    @Test
    fun format() {
        assertEquals("0.05", Money.format(5))
        assertEquals("1,234,567.89", Money.format(123456789))
        assertEquals("-12.30", Money.format(-1230))
        assertEquals("+15,000.00", Money.formatSigned(1500000, Direction.INCOME))
        assertEquals("-25.00", Money.formatSigned(2500, Direction.EXPENSE))
    }
}

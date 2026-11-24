package io.github.snapledger.data

import io.github.snapledger.model.Direction
import io.github.snapledger.model.Source
import io.github.snapledger.model.Txn
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private fun ms(t: LocalDateTime) = t.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun exportsSortedWithBomAndEscaping() {
        val csv = Csv.export(
            listOf(
                Txn(amountCents = 1_500_000, direction = Direction.INCOME, category = "工资", merchant = "工资",
                    timeMillis = ms(LocalDateTime.of(2026, 11, 10, 9, 0)), source = Source.RECURRING),
                Txn(amountCents = 3800, direction = Direction.EXPENSE, category = "餐饮", merchant = "星巴克, 人民广场",
                    note = "他说\"好喝\"", timeMillis = ms(LocalDateTime.of(2026, 11, 1, 12, 30)), source = Source.SCREENSHOT),
            ),
            zone,
        )
        val lines = csv.removePrefix("﻿").split("\r\n")
        assertEquals("时间,收支,金额,分类,商户/对方,备注,来源", lines[0])
        assertEquals("2026-11-01 12:30,支出,38.00,餐饮,\"星巴克, 人民广场\",\"他说\"\"好喝\"\"\",截图", lines[1])
        assertEquals("2026-11-10 09:00,收入,15000.00,工资,工资,,固定", lines[2])
        assertEquals('﻿', csv.first())
    }
}

package io.github.snapledger.parse

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrRowsTest {

    @Test
    fun mergesLabelAndValueOnSameRowInLeftToRightOrder() {
        val lines = listOf(
            OcrLine("2026年11月20日 12:30:15", 420, 805, 1000, 845),
            OcrLine("-38.00", 380, 300, 700, 400),
            OcrLine("支付时间", 40, 800, 200, 850),
            OcrLine("星巴克咖啡", 400, 200, 680, 250),
        )
        assertEquals(
            listOf("星巴克咖啡", "-38.00", "支付时间 2026年11月20日 12:30:15"),
            OcrRows.group(lines),
        )
    }

    @Test
    fun keepsAdjacentRowsSeparate() {
        val lines = listOf(
            OcrLine("商品", 40, 900, 120, 940),
            OcrLine("商户全称", 40, 950, 200, 990),
        )
        assertEquals(listOf("商品", "商户全称"), OcrRows.group(lines))
    }
}

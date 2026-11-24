package io.github.snapledger.parse

import io.github.snapledger.model.Direction
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutTest {

    private val now = LocalDateTime.of(2026, 11, 24, 18, 0)

    @Test
    fun fixesCommonAmountMisreads() {
        assertEquals("-¥38.00", TextNorm.fixAmountLine("-Y38.00"))
        assertEquals("-38.00", TextNorm.fixAmountLine("-38. 00"))
        assertEquals("¥25.00", TextNorm.fixAmountLine("¥ 25.0O"))
        assertEquals("¥1,299.00", TextNorm.fixAmountLine("¥1,299.00"))
        assertEquals("38.00", TextNorm.fixAmountLine("38,00"))
        assertEquals("101.50", TextNorm.fixAmountLine("lO1.50"))
        // 普通文字不动
        assertEquals("Apple Store", TextNorm.fixAmountLine("Apple Store"))
        assertEquals("12:41", TextNorm.fixAmountLine("12:41"))
        assertEquals("Yo", TextNorm.fixAmountLine("Yo"))
    }

    @Test
    fun largestNumberWinsOverLabeledSmallOne() {
        // 没有 ¥ 的大号金额 vs 带“金额”标签的小字
        val page = OcrPage(
            listOf(
                OcrLine("128.00", 300, 300, 780, 420),
                OcrLine("交易金额", 60, 600, 220, 640),
                OcrLine("¥12.80", 860, 600, 1020, 640),
                OcrLine("商户名称", 60, 680, 220, 720),
                OcrLine("某某超市", 800, 680, 1020, 720),
                OcrLine("备注", 60, 760, 140, 800),
                OcrLine("无", 980, 760, 1020, 800),
            ),
            1080, 2400,
        )
        val r = ReceiptParser.parsePage(page, now)
        assertEquals(PageKind.DETAIL, r.kind)
        assertEquals(12800L, r.items.single().amountCents)
        assertEquals("某某超市", r.items.single().merchant)
    }

    @Test
    fun accountLabelDoesNotLeakIntoMerchant() {
        val r = ReceiptParser.parse(listOf("已收款", "¥88.00", "收款方式 零钱", "转账时间 2026-11-20 09:00:00"), now)
        assertEquals("零钱", r.account)
        assertEquals("", r.merchant)
        assertEquals(Direction.INCOME, r.direction)
    }

    @Test
    fun alipayCategoryFieldIsUsed() {
        val r = ReceiptParser.parse(listOf("某某便利店", "-6.50", "交易成功", "账单分类 日用百货"), now)
        assertEquals("购物", r.category)
    }

    @Test
    fun closedTransactionWarns() {
        val r = ReceiptParser.parse(listOf("某商城", "-99.00", "交易关闭"), now)
        assertEquals("状态为“交易关闭”，可能不需要记账", r.warning)
    }

    @Test
    fun singleSignedAmountIsNotAList() {
        val r = ReceiptParser.parsePage(OcrPage.fromRows(listOf("滴滴出行", "-23.50", "优惠 -2.00")), now)
        assertEquals(PageKind.DETAIL, r.kind)
        assertEquals(2350L, r.items.single().amountCents)
    }

    @Test
    fun relativeAndDashedTimes() {
        assertEquals(LocalDateTime.of(2026, 11, 24, 8, 10), TimeParser.find("今天 08:10", now))
        assertEquals(LocalDateTime.of(2026, 11, 22, 23, 5), TimeParser.find("前天 23:05", now))
        assertEquals(LocalDateTime.of(2026, 11, 18, 8, 45), TimeParser.find("交通出行 11-18 08:45", now))
        assertEquals(LocalDateTime.of(2025, 12, 31, 9, 0), TimeParser.find("12-31 09:00", now, year = 2025))
        assertEquals(LocalDateTime.of(2026, 11, 20, 12, 30), TimeParser.find("11月20日 周五 12:30", now))
        assertNull(TimeParser.find("订单号 2026112012345", now))
    }

    @Test
    fun merchantKeyIgnoresBranchAndCase() {
        assertEquals("星巴克", Categorizer.merchantKey("星巴克 (人民广场店)"))
        assertEquals("applestore", Categorizer.merchantKey("Apple Store"))
    }
}

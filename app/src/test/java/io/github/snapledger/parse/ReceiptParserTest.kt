package io.github.snapledger.parse

import io.github.snapledger.model.Direction
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 样例是按真实截图版式整理的 OCR 文本（已按视觉行合并）。 */
class ReceiptParserTest {

    private val now = LocalDateTime.of(2026, 11, 24, 18, 0)
    private fun parse(vararg rows: String) = ReceiptParser.parse(rows.toList(), now)

    @Test
    fun wechatBillDetail() {
        val r = parse(
            "12:41", "<", "星巴克咖啡(人民广场店)", "-38.00",
            "当前状态 支付成功",
            "支付时间 2026年11月20日 12:30:15",
            "商品 星巴克咖啡",
            "商户全称 上海星巴克咖啡经营有限公司",
            "收单机构 财付通支付科技有限公司",
            "支付方式 零钱",
            "交易单号 4200001234202611201234567890",
        )
        assertEquals(3800L, r.amountCents)
        assertEquals(Direction.EXPENSE, r.direction)
        assertEquals("星巴克咖啡(人民广场店)", r.merchant)
        assertEquals(LocalDateTime.of(2026, 11, 20, 12, 30, 15), r.time)
        assertEquals("餐饮", r.category)
        assertEquals("微信支付", r.channel)
        assertTrue(r.confident)
    }

    @Test
    fun wechatPaymentSuccess() {
        val r = parse("支付成功", "¥25.00", "收款方 瑞幸咖啡")
        assertEquals(2500L, r.amountCents)
        assertEquals(Direction.EXPENSE, r.direction)
        assertEquals("瑞幸咖啡", r.merchant)
        assertNull(r.time)
    }

    @Test
    fun couponDoesNotWin() {
        val r = parse("支付成功", "￥45.00", "原价 ￥50.00", "优惠 ￥5.00", "收款方 美团外卖")
        assertEquals(4500L, r.amountCents)
        assertEquals("美团外卖", r.merchant)
        assertEquals("餐饮", r.category)
    }

    @Test
    fun alipayExpense() {
        val r = parse(
            "账单详情", "滴滴出行", "-23.50", "交易成功",
            "支付时间 2026-11-18 08:45:02",
            "付款方式 花呗",
            "商品说明 滴滴快车-张师傅",
            "收款方全称 北京小桔科技有限公司",
            "账单分类 交通出行",
        )
        assertEquals(2350L, r.amountCents)
        assertEquals(Direction.EXPENSE, r.direction)
        assertEquals("滴滴出行", r.merchant)
        assertEquals("交通", r.category)
        assertEquals("支付宝", r.channel)
        assertEquals(LocalDateTime.of(2026, 11, 18, 8, 45, 2), r.time)
    }

    @Test
    fun alipayIncomeTransfer() {
        val r = parse("张三", "+200.00", "交易成功", "创建时间 2026-11-19 20:11:09", "转账备注 饭钱")
        assertEquals(20000L, r.amountCents)
        assertEquals(Direction.INCOME, r.direction)
        assertEquals("张三", r.merchant)
        assertEquals("转账", r.category)
    }

    @Test
    fun wechatTransferReceived() {
        val r = parse(
            "已收款", "¥520.00",
            "转账时间 2026年11月10日 21:05:33",
            "收款时间 2026年11月10日 21:06:01",
        )
        assertEquals(52000L, r.amountCents)
        assertEquals(Direction.INCOME, r.direction)
        assertEquals(LocalDateTime.of(2026, 11, 10, 21, 5, 33), r.time)
    }

    @Test
    fun bankSmsSalary() {
        val r = parse("【招商银行】您账户1234于11月15日10:02收入人民币15,000.00元（代发工资），余额32,345.67元。")
        assertEquals(1500000L, r.amountCents)
        assertEquals(Direction.INCOME, r.direction)
        assertEquals("工资", r.category)
        assertEquals("招商银行", r.merchant)
        assertEquals(LocalDateTime.of(2026, 11, 15, 10, 2), r.time)
        assertEquals("银行", r.channel)
    }

    @Test
    fun bankSmsExpense() {
        val r = parse("【工商银行】您尾号5678卡11月21日19:30快捷支付支出(消费)人民币128.50元，余额2,000.00元。")
        assertEquals(12850L, r.amountCents)
        assertEquals(Direction.EXPENSE, r.direction)
    }

    @Test
    fun bankAppDetail() {
        val r = parse("交易详情", "交易金额 -128.00", "交易类型 消费", "对方户名 京东商城", "交易时间 2026/11/22 14:03")
        assertEquals(12800L, r.amountCents)
        assertEquals(Direction.EXPENSE, r.direction)
        assertEquals("京东商城", r.merchant)
        assertEquals("购物", r.category)
        assertEquals(LocalDateTime.of(2026, 11, 22, 14, 3), r.time)
    }

    @Test
    fun labelOnPreviousLine() {
        val r = parse("付款成功", "付款金额", "¥1,299.00", "收款方", "Apple Store")
        assertEquals(129900L, r.amountCents)
        assertEquals("Apple Store", r.merchant)
    }

    @Test
    fun membershipRenewal() {
        val r = parse("爱奇艺VIP会员 连续包月", "-25.00", "交易成功", "支付时间 2026-11-03 08:00:00")
        assertEquals(2500L, r.amountCents)
        assertEquals("会员订阅", r.category)
    }

    @Test
    fun refundIsIncome() {
        val r = parse("退款成功", "¥59.90", "退款时间 2026-11-12 10:00:00", "商户名称 拼多多")
        assertEquals(5990L, r.amountCents)
        assertEquals(Direction.INCOME, r.direction)
        assertEquals("退款", r.category)
        assertEquals("拼多多", r.merchant)
    }

    @Test
    fun fullWidthCharacters() {
        val r = parse("支付成功", "－３８．００")
        assertEquals(3800L, r.amountCents)
        assertEquals(Direction.EXPENSE, r.direction)
    }

    @Test
    fun yearlessDateInFutureMeansLastYear() {
        val jan = LocalDateTime.of(2027, 1, 3, 9, 0)
        val r = ReceiptParser.parse(listOf("【建设银行】您账户于12月30日18:20支出人民币66.00元"), jan)
        assertEquals(LocalDateTime.of(2026, 12, 30, 18, 20), r.time)
    }

    @Test
    fun orderNumbersAndTimesAreNotAmounts() {
        val r = parse("12:41", "订单号 20261120123456", "2026-11-20 12:30")
        assertNull(r.amountCents)
        assertFalse(r.confident)
    }

    @Test
    fun noText() {
        val r = parse()
        assertNull(r.amountCents)
        assertEquals("", r.merchant)
        assertEquals("其他", r.category)
    }
}

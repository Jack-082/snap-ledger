package io.github.snapledger.parse

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class FusionTest {

    private fun fuse(ml: String, pp: String, conf: Float = 0.95f) = Fusion.fuse(ml, pp, FloatArray(pp.length) { conf })

    @Test
    fun takesChineseCharactersFromSecondEngine() {
        assertEquals(Fusion.Fused("瑞幸咖啡"), fuse("瑞辛咖啡", "瑞幸咖啡"))
        assertEquals(Fusion.Fused("明星超市"), fuse("日月星超市", "明星超市")) // 一个字被拆成两个
        assertEquals(Fusion.Fused("瑞幸咖啡"), fuse("瑞咖啡", "瑞幸咖啡"))     // 漏字
        assertEquals(Fusion.Fused("瑞幸咖啡"), fuse("瑞辛 咖啡", "瑞幸咖啡"))
    }

    @Test
    fun keepsFirstEngineWhenSecondIsUnsure() {
        assertEquals("瑞辛咖啡", fuse("瑞辛咖啡", "瑞幸咖啡", conf = 0.6f).text)
        // 只有个别字把握低：那个字不换
        val conf = floatArrayOf(0.99f, 0.4f, 0.99f, 0.99f)
        assertEquals("瑞辛咖啡", Fusion.fuse("瑞辛咖啡", "瑞幸咖啡", conf).text)
        // 差得太多，多半是裁剪对不上
        assertEquals("星巴克", fuse("星巴克", "ABCDEFG").text)
        // 行首多出来的字不删（裁剪贴边时第二个引擎可能漏字）
        assertEquals("回瑞幸咖啡", fuse("回瑞幸咖啡", "瑞幸咖啡").text)
    }

    @Test
    fun structureAndSpacingComeFromFirstEngine() {
        assertEquals(Fusion.Fused("11-18 08:45"), fuse("11-18 08:45", "11-1808:45"))
        assertEquals(Fusion.Fused("星巴克(人民广场店)"), fuse("星巴克(人民广场店)", "星巴克（人民广场店）"))
        assertEquals(Fusion.Fused("2026年11月20日 12:30:15"), fuse("2026年11月20日 12:30:15", "2026年11月20日12:30:15"))
        assertEquals("Apple Store", fuse("Apple Store", "App1e Store").text) // 拉丁字母以 ML Kit 为准
    }

    @Test
    fun yenAndMinusSigns() {
        assertEquals(Fusion.Fused("¥38.00"), fuse("Y38.00", "¥38.00"))
        assertEquals(Fusion.Fused("¥38.00"), fuse("羊38.00", "￥38.00"))
        assertEquals(Fusion.Fused("-38.00"), fuse("38.00", "-38.00"))  // 补上漏掉的减号
        assertEquals(Fusion.Fused("-38.00"), fuse("-38.00", "一38.00")) // “一”不能顶掉减号
    }

    @Test
    fun digitDisagreementIsReportedNotSilentlyChanged() {
        assertEquals(Fusion.Fused("实付款 ¥38.00", alt = "实付款¥38.80"), fuse("实付款 ¥38.00", "实付款¥38.80"))
        assertEquals(Fusion.Fused("38.00", alt = "38.80"), fuse("38.00", "38.80"))
        // 小数点丢失：只有一方是规范金额时采用规范的
        assertEquals(Fusion.Fused("-38.00", alt = "-3800"), fuse("-3800", "-38.00"))
        // 第二个引擎把握不大时不报
        assertNull(fuse("38.00", "38.80", conf = 0.55f).alt)
        // O / 0 这类已有修正规则的不算分歧
        assertNull(fuse("¥25.0O", "¥25.00").alt)
    }

    @Test
    fun degenerateInputs() {
        assertEquals("星巴克", fuse("星巴克", "").text)
        assertEquals("星巴克", Fusion.fuse("星巴克", "星巴克", FloatArray(1)).text) // 置信度长度不对
        assertEquals(Fusion.Fused("星巴克"), fuse("星巴克", "星巴克"))
    }

    @Test
    fun disputedAmountLowersConfidenceInParser() {
        val now = LocalDateTime.of(2026, 11, 24, 18, 0)
        val page = OcrPage(
            listOf(
                OcrLine("星巴克", 300, 200, 700, 250),
                OcrLine("-38.00", 300, 300, 780, 420, alt = "-38.80"),
                OcrLine("支付成功", 300, 500, 500, 540),
            ),
            1080, 2400,
        )
        val r = ReceiptParser.parsePage(page, now).items.single()
        assertEquals(3800L, r.amountCents)
        assertEquals("-38.80", r.amountAlt)
        assertFalse(r.confident)
    }
}

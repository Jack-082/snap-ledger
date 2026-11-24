package io.github.snapledger.ocr

import io.github.snapledger.parse.Fusion
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * 用打包进 App 的真实模型识别一张模拟账单页，验证整条链路：
 * 裁剪留白（[LineCrop]）→ 缩放 / 归一化 → ONNX 推理 → CTC 解码 → 字典。
 *
 * 样本是人工渲染的（PingFang 字体，含浅色、深色、小号灰字），不是真实截图，
 * 能证明链路没接错，不能代表真实截图上的识别率。
 */
class PpRecognizerTest {

    companion object {
        private lateinit var rec: PpRecognizer

        @BeforeClass
        @JvmStatic
        fun load() {
            val assets = File("src/main/assets/ppocr")
            rec = PpRecognizer(File(assets, "rec.onnx").readBytes(), File(assets, "dict.txt").readLines(Charsets.UTF_8))
        }

        @AfterClass
        @JvmStatic
        fun close() = rec.close()
    }

    private class Page(val argb: IntArray, val width: Int, val height: Int)

    private fun resource(name: String) = File(javaClass.classLoader!!.getResource("ppocr/$name")!!.toURI())

    private fun loadPage(name: String): Page {
        val img = ImageIO.read(resource(name))
        return Page(img.getRGB(0, 0, img.width, img.height, null, 0, img.width), img.width, img.height)
    }

    /** 在给定范围内找文字的紧致外接框（模拟 ML Kit 给出的行框）。 */
    private fun inkBox(p: Page, x0: Int, x1: Int, top: Int, bottom: Int): IntArray {
        val t0 = top.coerceAtLeast(0)
        val b0 = bottom.coerceAtMost(p.height)
        val bg = p.argb[t0 * p.width + x0]
        fun differs(px: Int) = abs((px shr 16 and 0xFF) - (bg shr 16 and 0xFF)) > 48 ||
            abs((px shr 8 and 0xFF) - (bg shr 8 and 0xFF)) > 48 || abs((px and 0xFF) - (bg and 0xFF)) > 48
        var l = Int.MAX_VALUE; var r = -1; var t = Int.MAX_VALUE; var b = -1
        for (y in t0 until b0) for (x in x0 until x1.coerceAtMost(p.width)) {
            if (differs(p.argb[y * p.width + x])) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
        }
        check(r >= 0) { "范围内没有文字" }
        return intArrayOf(l, t, r + 1, b + 1)
    }

    private fun crop(p: Page, box: IntArray): IntArray {
        val w = box[2] - box[0]
        val h = box[3] - box[1]
        return IntArray(w * h) { i -> p.argb[(box[1] + i / w) * p.width + box[0] + i % w] }
    }

    /** 只比较汉字；“一”除外——样本里没有这个字，而减号常被认成它（合并时另有处理）。 */
    private fun han(s: String) = s.filter { Fusion.isHan(it) && it != '一' }
    private fun digits(s: String) = s.filter(Char::isDigit)

    @Test
    fun recognizesEveryLineOfTheSamplePage() {
        val spec = JSONObject(resource("page1.json").readText())
        val page = loadPage(spec.getString("image"))
        val items = spec.getJSONArray("items")
        val failures = mutableListOf<String>()
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val expected = item.getString("text")
            val ink = inkBox(page, item.getInt("x0"), item.getInt("x1"), item.getInt("top"), item.getInt("bottom"))
            val box = LineCrop.paddedBox(ink[0], ink[1], ink[2], ink[3], page.width, page.height)!!
            val result = rec.recognize(crop(page, box), box[2] - box[0], box[3] - box[1])
            println("PP-OCR  期望“%s”  实际“%s”  置信度 %.2f  框 %s".format(expected, result.text, result.mean, ink.toList()))

            assertEquals("置信度个数应与字符数一致", result.text.length, result.conf.size)
            assertTrue("置信度应在 0–1 之间", result.conf.all { it in 0f..1f })
            if (han(result.text) != han(expected)) failures += "汉字：期望“$expected”，实际“${result.text}”"
            if (digits(result.text) != digits(expected)) failures += "数字：期望“$expected”，实际“${result.text}”"
            if (result.mean < 0.8f) failures += "置信度偏低 ${result.mean}：“${result.text}”"
        }
        assertTrue("\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun tensorWidthFollowsAspectRatio() {
        assertEquals(320, PpRecognizer.tensorWidth(100, 48))   // 短行补到 320
        assertEquals(960, PpRecognizer.tensorWidth(960, 48))
        assertEquals(480, PpRecognizer.tensorWidth(1000, 100)) // 高度缩到 48，宽度等比
        assertEquals(2400, PpRecognizer.tensorWidth(100_000, 48)) // 极长的行有上限
    }

    @Test
    fun preprocessNormalizesToBgrAndPadsWithZero() {
        // 2×2 纯红图片 → 缩放后有内容的区域 B=-1, G=-1, R=+1，右侧补零
        val red = IntArray(4) { 0xFFFF0000.toInt() }
        val tw = PpRecognizer.tensorWidth(2, 2)
        val out = PpRecognizer.preprocess(red, 2, 2, tw)
        val plane = PpRecognizer.HEIGHT * tw
        assertEquals(-1f, out[0], 1e-6f)            // B
        assertEquals(-1f, out[plane], 1e-6f)        // G
        assertEquals(1f, out[2 * plane], 1e-6f)     // R
        assertEquals(0f, out[tw - 1], 1e-6f)        // 补零区域
    }
}

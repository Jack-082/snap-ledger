package io.github.snapledger.parse

import io.github.snapledger.model.Money
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 识别数据样本回归测试。
 *
 * `src/test/resources/fixtures` 目录下的每个 .json 文件是一张截图的 OCR 结果（文字 + 位置），格式与 App 里
 * “识别文字 → 复制识别数据”导出的一致，再加上 `expect` 期望结果。遇到识别不准的截图，
 * 把导出的数据存成新文件、写上期望值，就成了一个测试用例。
 */
class FixtureTest {

    private val minute = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val second = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    @Test
    fun allFixtures() {
        val dir = File(javaClass.classLoader!!.getResource("fixtures")!!.toURI())
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("没有找到样本", files.isNotEmpty())
        val failures = files.flatMap { f -> check(JSONObject(f.readText())).map { "${f.name}: $it" } }
        assertTrue("\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun check(o: JSONObject): List<String> {
        val lines = o.getJSONArray("lines")
        val page = OcrPage(
            (0 until lines.length()).map { i ->
                val l = lines.getJSONObject(i)
                val b = l.getJSONArray("b")
                OcrLine(l.getString("t"), b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3))
            },
            o.optInt("width"), o.optInt("height"),
        )
        val now = LocalDateTime.parse(o.getString("now"), minute)
        val result = ReceiptParser.parsePage(page, now)
        val expect = o.getJSONObject("expect")
        val errors = mutableListOf<String>()

        expect.optString("kind").takeIf { it.isNotEmpty() }?.let {
            if (it != result.kind.name) errors += "kind 期望 $it，实际 ${result.kind}"
        }
        val items = expect.getJSONArray("items")
        if (items.length() != result.items.size) {
            errors += "笔数期望 ${items.length()}，实际 ${result.items.size}：${result.items}"
            return errors
        }
        for (i in 0 until items.length()) {
            val e = items.getJSONObject(i)
            val a = result.items[i]
            fun cmp(field: String, actual: String?) {
                if (e.has(field) && e.getString(field) != actual) errors += "第 ${i + 1} 笔 $field 期望 “${e.getString(field)}”，实际 “$actual”"
            }
            cmp("amount", a.amountCents?.let { Money.format(it).replace(",", "") })
            cmp("direction", a.direction.name)
            cmp("merchant", a.merchant)
            cmp("category", a.category)
            cmp("account", a.account)
            cmp("channel", a.channel)
            if (e.has("time")) {
                val want = e.getString("time")
                cmp("time", a.time?.format(if (want.length > 16) second else minute))
            }
            if (e.has("warning") && e.getBoolean("warning") != (a.warning != null)) {
                errors += "第 ${i + 1} 笔 warning 期望 ${e.getBoolean("warning")}，实际 ${a.warning}"
            }
        }
        return errors
    }
}

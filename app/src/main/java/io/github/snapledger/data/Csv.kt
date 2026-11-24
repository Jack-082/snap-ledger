package io.github.snapledger.data

import io.github.snapledger.model.Money
import io.github.snapledger.model.Txn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 导出 CSV（带 UTF-8 BOM，Excel 打开中文不乱码）。 */
object Csv {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    val HEADER = listOf("时间", "收支", "金额", "分类", "商户/对方", "备注", "来源")

    fun export(txns: List<Txn>, zone: ZoneId = ZoneId.systemDefault()): String {
        val sb = StringBuilder("﻿")
        sb.append(HEADER.joinToString(",")).append("\r\n")
        for (t in txns.sortedBy { it.timeMillis }) {
            val row = listOf(
                TIME.format(Instant.ofEpochMilli(t.timeMillis).atZone(zone)),
                t.direction.label,
                Money.format(t.amountCents).replace(",", ""),
                t.category,
                t.merchant,
                t.note,
                t.source.label,
            )
            sb.append(row.joinToString(",") { escape(it) }).append("\r\n")
        }
        return sb.toString()
    }

    internal fun escape(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
}

package io.github.snapledger.model

enum class Direction(val label: String) {
    EXPENSE("支出"), INCOME("收入");

    companion object {
        fun of(name: String?): Direction = entries.firstOrNull { it.name == name } ?: EXPENSE
    }
}

/** 记录来源：截图识别 / 固定收支自动入账 / 手动添加。 */
enum class Source(val label: String) {
    SCREENSHOT("截图"), RECURRING("固定"), MANUAL("手动");

    companion object {
        fun of(name: String?): Source = entries.firstOrNull { it.name == name } ?: MANUAL
    }
}

/**
 * 一笔账。
 * [dedupKey] 用于防止重复入账：截图为图片内容的 SHA-256，固定收支为 "rule:<id>:<yyyy-MM>"，手动记账为 null。
 */
data class Txn(
    val id: Long = 0,
    val amountCents: Long,
    val direction: Direction,
    val category: String,
    val merchant: String = "",
    val note: String = "",
    val timeMillis: Long,
    val source: Source = Source.MANUAL,
    val dedupKey: String? = null,
)

object Categories {
    val EXPENSE = listOf("餐饮", "交通", "购物", "会员订阅", "生活缴费", "住房", "娱乐", "医疗", "转账", "其他")
    val INCOME = listOf("工资", "奖金", "退款", "转账", "理财", "其他")

    fun of(direction: Direction) = if (direction == Direction.INCOME) INCOME else EXPENSE
}

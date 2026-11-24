package io.github.snapledger.parse

import io.github.snapledger.model.Direction

/** 解析用到的关键词、标签和通用小工具。 */
internal object Lexicon {

    // ---------------- 金额 ----------------

    val AMOUNT_LABELS = listOf(
        "实付款", "实付", "付款金额", "支付金额", "交易金额", "收款金额", "到账金额", "转账金额", "入账金额",
        "消费金额", "退款金额", "金额", "合计", "总计", "实收", "收入", "支出", "消费", "转入", "转出", "扣款", "入账", "存入",
    )
    val NEGATIVE_LABELS = listOf(
        "优惠", "立减", "折扣", "原价", "抵扣", "积分", "余额", "可用", "手续费", "额度", "限额", "返现", "券", "节省", "省了", "已省",
        "订单金额", "商品金额", "应付", // 优惠前的金额
    )

    val AMOUNT = Regex(
        """(?<![\d.,])([+-])?\s?(¥|RMB|CNY)?\s?(\d{1,3}(?:,\d{3})+(?:\.\d{1,2})?|\d+(?:\.\d{1,2})?)(?![\d.,]*\d)(?!\s?%)(\s?元)?"""
    )
    private val CARD_TAIL = Regex("""(尾号|账户|卡号|\()\s*\d{3,}""")

    /** 去掉日期、时间、卡号尾号，避免把它们当成金额（等长替换，字符位置不变）。 */
    fun stripNonAmounts(row: String): String {
        var s = row
        for (re in TimeParser.ALL + CARD_TAIL) s = re.replace(s) { " ".repeat(it.value.length) }
        return s
    }

    // ---------------- 收支方向 ----------------

    val INCOME_NEAR = listOf("收入", "转入", "入账", "存入", "退款", "到账")
    val EXPENSE_NEAR = listOf("支出", "转出", "消费", "扣款", "实付", "付款", "支付")
    val INCOME_WORDS = listOf(
        "已收款", "收款成功", "到账成功", "已到账", "已存入", "收入", "转入", "退款成功", "已退款", "退款", "入账", "你收到", "收到", "工资", "收益",
    )
    val EXPENSE_WORDS = listOf(
        "支付成功", "付款成功", "已支付", "支出", "转出", "消费", "扣款", "实付", "付款方式", "支付方式", "支付时间", "付款时间", "待收款", "转账给", "收款方",
    )

    // ---------------- 标签（键值对） ----------------

    /** 商户 / 对方，按优先级排列。 */
    val MERCHANT_LABELS = listOf(
        "收款方", "商户名称", "交易对方", "对方户名", "对方账户", "商户全称", "收款方全称", "商品说明", "商品", "付款方", "摘要", "用途",
    )
    val ACCOUNT_LABELS = listOf("支付方式", "付款方式", "扣款方式", "退款方式", "收款方式", "到账方式", "入账账户", "付款卡", "支付账户")
    val TIME_LABELS = listOf("支付时间", "付款时间", "交易时间", "创建时间", "转账时间", "收款时间", "到账时间", "入账时间", "退款时间", "时间")
    val CATEGORY_LABELS = listOf("账单分类", "交易分类")
    private val OTHER_LABELS = listOf(
        "当前状态", "交易状态", "交易单号", "商户单号", "订单号", "支付单号", "转账单号", "商家订单号", "收单机构", "备注", "转账备注", "交易类型", "账单管理",
    )
    private val ALL_LABELS = MERCHANT_LABELS + ACCOUNT_LABELS + TIME_LABELS + CATEGORY_LABELS + OTHER_LABELS

    /** 金额上方的这些状态行不是商户名。 */
    val STATUS_WORDS = listOf(
        "支付成功", "付款成功", "交易成功", "已收款", "收款成功", "已支付", "待收款", "当前状态", "账单详情", "交易详情",
        "转账成功", "已存入", "退款成功", "已退款", "全部账单", "返回", "完成", "订单详情",
    )

    /** 页面标题，不是商户名。 */
    val PAGE_TITLES = setOf("微信支付", "支付宝", "云闪付", "交易详情", "账单详情", "支付详情", "付款详情", "订单详情", "账单")

    private val FAILED = listOf("交易关闭", "已关闭", "已取消", "支付失败", "付款失败", "交易失败", "已撤销")
    private val FULL_REFUND = listOf("已全额退款", "全额退款")

    /**
     * “标签 值”取值：按 [labels] 的优先级，找以该标签开头的行，值在同一行或下一行。
     * 每行取最长的匹配标签（所有已知标签里），避免“收款方”误配“收款方式 零钱”“收款方全称 …”。
     */
    fun valueAfter(rows: List<String>, labels: List<String>, accept: (String) -> Boolean = { it.isNotBlank() }): String? {
        val labelOf = rows.map { row -> ALL_LABELS.filter { row.startsWith(it) }.maxByOrNull { it.length } }
        for (label in labels) {
            for (i in rows.indices) {
                if (labelOf[i] != label) continue
                val rest = rows[i].removePrefix(label).trimStart(':', ' ')
                if (rest.isNotEmpty()) {
                    if (accept(rest)) return rest
                } else {
                    val next = rows.getOrNull(i + 1)
                    if (next != null && labelOf[i + 1] == null && accept(next)) return next
                }
            }
        }
        return null
    }

    fun isLabelRow(row: String) = ALL_LABELS.any { row.startsWith(it) }

    /** 交易未成功、已全额退款等，提示用户可能不需要记账。 */
    fun statusWarning(text: String, direction: Direction): String? {
        FAILED.firstOrNull { text.contains(it) }?.let { return "状态为“$it”，可能不需要记账" }
        if (direction == Direction.EXPENSE) FULL_REFUND.firstOrNull { text.contains(it) }?.let { return "这笔已全额退款" }
        return null
    }

    fun looksLikeName(s: String): Boolean {
        val t = s.trim()
        if (t.length !in 2..40) return false
        if (!t.any { it.isLetter() }) return false
        if (TimeParser.FULL_DATE_TIME.containsMatchIn(t) || TimeParser.CLOCK.containsMatchIn(t)) return false
        if (TIME_LABELS.any { t.startsWith(it) }) return false
        val digits = t.count { it.isDigit() }
        return digits * 2 < t.length
    }

    fun clean(s: String) = s.trim().trimEnd('>', '›', ' ').take(40)
}

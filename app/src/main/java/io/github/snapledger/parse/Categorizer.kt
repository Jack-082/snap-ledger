package io.github.snapledger.parse

import io.github.snapledger.model.Direction

/** 按关键词给账单归类；匹配不到时归入“其他”。规则按顺序匹配，靠前的优先。 */
object Categorizer {

    private val EXPENSE_RULES: List<Pair<String, List<String>>> = listOf(
        "会员订阅" to listOf("会员", "VIP", "vip", "订阅", "连续包月", "自动续费", "iCloud", "Apple", "Netflix", "Spotify", "爱奇艺", "腾讯视频", "优酷", "芒果TV", "网易云音乐", "QQ音乐", "哔哩哔哩", "大会员", "ChatGPT", "Claude"),
        "餐饮" to listOf("餐", "饭", "面馆", "咖啡", "星巴克", "瑞幸", "麦当劳", "肯德基", "奶茶", "喜茶", "蜜雪", "美团外卖", "饿了么", "饮品", "烧烤", "火锅", "食堂", "便利店", "全家", "罗森", "7-Eleven"),
        "交通" to listOf("地铁", "公交", "滴滴", "出行", "打车", "高德", "铁路", "12306", "航空", "机票", "加油", "停车", "ETC", "单车", "哈啰"),
        "生活缴费" to listOf("电费", "水费", "燃气", "话费", "宽带", "物业", "中国移动", "中国联通", "中国电信", "缴费"),
        "住房" to listOf("房租", "租金", "房贷", "自如", "贝壳"),
        "医疗" to listOf("医院", "药房", "药店", "诊所", "医保", "挂号"),
        "娱乐" to listOf("电影", "影城", "游戏", "KTV", "Steam", "门票", "演出"),
        "购物" to listOf("淘宝", "天猫", "京东", "拼多多", "超市", "商城", "唯品会", "抖音电商", "盒马", "山姆", "沃尔玛", "优衣库"),
        "转账" to listOf("转账", "转给"),
    )

    private val INCOME_RULES: List<Pair<String, List<String>>> = listOf(
        "工资" to listOf("工资", "薪资", "薪金", "代发", "劳务"),
        "奖金" to listOf("奖金", "绩效", "年终"),
        "退款" to listOf("退款", "退回", "已退"),
        "理财" to listOf("利息", "收益", "分红", "理财", "基金"),
        "转账" to listOf("转账", "红包", "收款"),
    )

    fun categorize(direction: Direction, vararg texts: String): String {
        val haystack = texts.joinToString(" ")
        val rules = if (direction == Direction.INCOME) INCOME_RULES else EXPENSE_RULES
        return rules.firstOrNull { (_, words) -> words.any { haystack.contains(it) } }?.first ?: "其他"
    }
}

package io.github.snapledger.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.snapledger.model.Direction
import io.github.snapledger.model.Source
import io.github.snapledger.model.Txn
import io.github.snapledger.parse.Categorizer
import io.github.snapledger.recurring.RecurringRule
import java.time.YearMonth

/** 本地账本（SQLite）。数据只保存在本机，不联网。 */
class LedgerDb private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "ledger.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE txn (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                amount INTEGER NOT NULL,
                direction TEXT NOT NULL,
                category TEXT NOT NULL,
                merchant TEXT NOT NULL DEFAULT '',
                note TEXT NOT NULL DEFAULT '',
                time INTEGER NOT NULL,
                source TEXT NOT NULL,
                dedup TEXT UNIQUE,
                created INTEGER NOT NULL,
                account TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_txn_time ON txn(time)")
        db.execSQL(
            """
            CREATE TABLE rule (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                amount INTEGER NOT NULL,
                direction TEXT NOT NULL,
                category TEXT NOT NULL,
                day INTEGER NOT NULL,
                hour INTEGER NOT NULL,
                minute INTEGER NOT NULL,
                interval_months INTEGER NOT NULL DEFAULT 1,
                start_month TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                last_posted TEXT,
                note TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2：新增支付方式 / 账户
        if (oldVersion < 2) db.execSQL("ALTER TABLE txn ADD COLUMN account TEXT NOT NULL DEFAULT ''")
    }

    // ---------------- 账单 ----------------

    private fun Txn.values() = ContentValues().apply {
        put("amount", amountCents)
        put("direction", direction.name)
        put("category", category)
        put("merchant", merchant)
        put("note", note)
        put("time", timeMillis)
        put("source", source.name)
        put("dedup", dedupKey)
        put("account", account)
    }

    /** 插入一笔账；dedupKey 已存在时不插入，返回 -1。 */
    fun insertTxn(t: Txn): Long {
        val v = t.values().apply { put("created", System.currentTimeMillis()) }
        return writableDatabase.insertWithOnConflict("txn", null, v, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun updateTxn(t: Txn) {
        writableDatabase.update("txn", t.values(), "id = ?", arrayOf(t.id.toString()))
    }

    fun deleteTxn(id: Long) {
        writableDatabase.delete("txn", "id = ?", arrayOf(id.toString()))
    }

    /** [fromMillis, toMillis) 区间内的账单，按时间倒序。 */
    fun txnsBetween(fromMillis: Long, toMillis: Long): List<Txn> =
        readableDatabase.query(
            "txn", null, "time >= ? AND time < ?", arrayOf(fromMillis.toString(), toMillis.toString()),
            null, null, "time DESC, id DESC",
        ).use { c -> generateSequence { if (c.moveToNext()) c.toTxn() else null }.toList() }

    fun hasDedup(key: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM txn WHERE dedup = ? LIMIT 1", arrayOf(key)).use { it.moveToFirst() }

    /** 同金额、同方向、时间相差 [windowMillis] 以内的已有账单（同一笔支付的不同截图）。 */
    fun findSimilar(amountCents: Long, direction: Direction, timeMillis: Long, windowMillis: Long = 5 * 60_000): Txn? =
        readableDatabase.query(
            "txn", null, "amount = ? AND direction = ? AND time BETWEEN ? AND ?",
            arrayOf(amountCents.toString(), direction.name, (timeMillis - windowMillis).toString(), (timeMillis + windowMillis).toString()),
            null, null, "time DESC", "1",
        ).use { c -> if (c.moveToFirst()) c.toTxn() else null }

    private fun Cursor.toTxn() = Txn(
        id = getLong(getColumnIndexOrThrow("id")),
        amountCents = getLong(getColumnIndexOrThrow("amount")),
        direction = Direction.of(getString(getColumnIndexOrThrow("direction"))),
        category = getString(getColumnIndexOrThrow("category")),
        merchant = getString(getColumnIndexOrThrow("merchant")),
        note = getString(getColumnIndexOrThrow("note")),
        timeMillis = getLong(getColumnIndexOrThrow("time")),
        source = Source.of(getString(getColumnIndexOrThrow("source"))),
        dedupKey = getColumnIndexOrThrow("dedup").let { if (isNull(it)) null else getString(it) },
        account = getString(getColumnIndexOrThrow("account")),
    )

    /**
     * 记住商户分类：每个（收支方向，商户）最近一次用过的分类。
     * 截图识别出同一商户时优先用它，用户改过一次分类，下次就会沿用。
     */
    fun categoryHistory(limit: Int = 2000): Map<Pair<Direction, String>, String> {
        val out = HashMap<Pair<Direction, String>, String>()
        readableDatabase.rawQuery(
            "SELECT direction, merchant, category FROM txn WHERE merchant != '' AND source != ? ORDER BY time DESC LIMIT ?",
            arrayOf(Source.RECURRING.name, limit.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val key = Direction.of(c.getString(0)) to Categorizer.merchantKey(c.getString(1))
                if (key.second.isNotEmpty()) out.putIfAbsent(key, c.getString(2))
            }
        }
        return out
    }

    // ---------------- 固定收支 ----------------

    fun rules(): List<RecurringRule> =
        readableDatabase.query("rule", null, null, null, null, null, "day, hour, minute, id")
            .use { c -> generateSequence { if (c.moveToNext()) c.toRule() else null }.toList() }

    /** 新建（id = 0）或更新规则，返回 id。 */
    fun saveRule(r: RecurringRule): Long {
        val v = ContentValues().apply {
            put("name", r.name)
            put("amount", r.amountCents)
            put("direction", r.direction.name)
            put("category", r.category)
            put("day", r.dayOfMonth)
            put("hour", r.hour)
            put("minute", r.minute)
            put("interval_months", r.intervalMonths)
            put("start_month", r.startMonth.toString())
            put("enabled", if (r.enabled) 1 else 0)
            put("last_posted", r.lastPosted?.toString())
            put("note", r.note)
        }
        return if (r.id == 0L) {
            writableDatabase.insert("rule", null, v)
        } else {
            writableDatabase.update("rule", v, "id = ?", arrayOf(r.id.toString()))
            r.id
        }
    }

    fun deleteRule(id: Long) {
        writableDatabase.delete("rule", "id = ?", arrayOf(id.toString()))
    }

    /** 在一个事务里写入到期的固定收支并推进 last_posted，返回实际新插入的记录。 */
    fun postRecurring(ruleId: Long, txns: List<Txn>, lastPeriod: YearMonth): List<Txn> {
        val db = writableDatabase
        val inserted = mutableListOf<Txn>()
        db.beginTransaction()
        try {
            for (t in txns) {
                val id = insertTxn(t)
                if (id != -1L) inserted += t.copy(id = id)
            }
            db.update("rule", ContentValues().apply { put("last_posted", lastPeriod.toString()) }, "id = ?", arrayOf(ruleId.toString()))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    private fun Cursor.toRule() = RecurringRule(
        id = getLong(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        amountCents = getLong(getColumnIndexOrThrow("amount")),
        direction = Direction.of(getString(getColumnIndexOrThrow("direction"))),
        category = getString(getColumnIndexOrThrow("category")),
        dayOfMonth = getInt(getColumnIndexOrThrow("day")),
        hour = getInt(getColumnIndexOrThrow("hour")),
        minute = getInt(getColumnIndexOrThrow("minute")),
        intervalMonths = getInt(getColumnIndexOrThrow("interval_months")),
        startMonth = YearMonth.parse(getString(getColumnIndexOrThrow("start_month"))),
        enabled = getInt(getColumnIndexOrThrow("enabled")) == 1,
        lastPosted = getColumnIndexOrThrow("last_posted").let { if (isNull(it)) null else YearMonth.parse(getString(it)) },
        note = getString(getColumnIndexOrThrow("note")),
    )

    companion object {
        @Volatile private var instance: LedgerDb? = null

        fun get(context: Context): LedgerDb =
            instance ?: synchronized(this) { instance ?: LedgerDb(context).also { instance = it } }
    }
}

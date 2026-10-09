package com.jianji.app.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import com.jianji.app.core.Record
import com.jianji.app.core.RecordType

/**
 * 账单 DAO：所有方法要求在后台线程调用（由 App.post 调度）。
 *
 * 删除采用**软删除**（回收站）：deleted_at > 0 表示已删除，
 * 所有统计/列表/搜索默认只看未删除的记录；回收站里可还原或彻底删除。
 */
class RecordDao(context: Context) {

    private val helper = DbHelper(context.applicationContext)

    /** 搜索条件：文本（备注/分类/标签/支付方式）、类型、分类、标签、金额区间、时间区间 */
    data class Query(
        val text: String = "",
        val type: Int = -1,
        val category: String? = null,
        val tag: String? = null,
        val minAmount: Double? = null,
        val maxAmount: Double? = null,
        val start: Long? = null,
        val end: Long? = null
    )

    private val columns = arrayOf(
        "id", "amount", "type", "category", "note", "tag", "pay_method", "source",
        "time", "create_time", "deleted_at", "image_path", "merchant"
    )

    private companion object {
        const val ACTIVE = "deleted_at=0"
    }

    // ---------------- 写入 ----------------

    fun insert(r: Record): Long {
        val cv = ContentValues().apply {
            put("amount", r.amount)
            put("type", r.type)
            put("category", r.category)
            put("note", r.note)
            put("tag", r.tag)
            put("pay_method", r.payMethod)
            put("source", r.source)
            put("time", r.time)
            put("create_time", r.createTime)
            put("deleted_at", 0L)
            put("image_path", r.imagePath)
            put("merchant", r.merchant)
        }
        return helper.writableDatabase.insert("records", null, cv)
    }

    /** 更新单条（按 id 覆盖全部可编辑字段） */
    fun update(r: Record): Int {
        val cv = ContentValues().apply {
            put("amount", r.amount)
            put("type", r.type)
            put("category", r.category)
            put("note", r.note)
            put("tag", r.tag)
            put("pay_method", r.payMethod)
            put("source", r.source)
            put("time", r.time)
            put("image_path", r.imagePath)
            put("merchant", r.merchant)
        }
        return helper.writableDatabase.update("records", cv, "id=?", arrayOf(r.id.toString()))
    }

    /** 批量更新（事务，整批成功或整批回滚） */
    fun updateAll(records: List<Record>): Int {
        if (records.isEmpty()) return 0
        val db = helper.writableDatabase
        var n = 0
        db.beginTransaction()
        try {
            records.forEach { r -> n += update(r) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    /** 批量改分类（同时可按分类属性修正收支方向） */
    fun updateCategory(ids: List<Long>, category: String, type: Int?): Int {
        if (ids.isEmpty()) return 0
        val cv = ContentValues().apply {
            put("category", category)
            if (type != null) put("type", type)
        }
        return updateWhereIds(ids, cv)
    }

    /** 批量加标签（追加，不覆盖已有标签） */
    fun updateTag(ids: List<Long>, tag: String): Int {
        if (ids.isEmpty()) return 0
        val records = byIds(ids)
        var n = 0
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            records.forEach { r ->
                val cv = ContentValues().apply { put("tag", mergeTag(r.tag, tag)) }
                n += db.update("records", cv, "id=?", arrayOf(r.id.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return n
    }

    // ---------------- 删除 / 回收站 ----------------

    /** 移入回收站（软删除） */
    fun softDelete(id: Long): Int = softDeleteMany(listOf(id))

    fun softDeleteMany(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        val cv = ContentValues().apply { put("deleted_at", System.currentTimeMillis()) }
        return updateWhereIds(ids, cv)
    }

    /** 全部移入回收站（「清空全部记录」用，仍可在回收站还原） */
    fun softDeleteAll(): Int {
        val cv = ContentValues().apply { put("deleted_at", System.currentTimeMillis()) }
        return helper.writableDatabase.update("records", cv, ACTIVE, null)
    }

    /** 从回收站还原 */
    fun restoreMany(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        val cv = ContentValues().apply { put("deleted_at", 0L) }
        return updateWhereIds(ids, cv)
    }

    /** 彻底删除（不可恢复） */
    fun purgeMany(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        val placeholders = ids.joinToString(",") { "?" }
        return helper.writableDatabase.delete(
            "records", "id IN ($placeholders)", ids.map { it.toString() }.toTypedArray()
        )
    }

    /** 清空回收站 */
    fun purgeAllTrash(): Int =
        helper.writableDatabase.delete("records", "deleted_at>0", null)

    /** 清理超过保留期的回收站记录，返回清理条数 */
    fun purgeTrashBefore(cutoff: Long): Int =
        helper.writableDatabase.delete(
            "records", "deleted_at>0 AND deleted_at<?", arrayOf(cutoff.toString())
        )

    fun trashCount(): Int = rawCount("deleted_at>0")

    fun trashRecords(limit: Int = 500): List<Record> =
        helper.readableDatabase.query(
            "records", columns, "deleted_at>0", null, null, null, "deleted_at DESC", limit.toString()
        ).use(::collect)

    // ---------------- 查询（默认只看未删除） ----------------

    /** [start, end) 时间范围内的记录，按时间倒序 */
    fun range(start: Long, end: Long): List<Record> {
        return helper.readableDatabase.query(
            "records", columns, "$ACTIVE AND time>=? AND time<?",
            arrayOf(start.toString(), end.toString()), null, null, "time DESC"
        ).use(::collect)
    }

    fun all(): List<Record> = helper.readableDatabase.query(
        "records", columns, ACTIVE, null, null, null, "time DESC"
    ).use(::collect)

    fun byIds(ids: List<Long>): List<Record> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        return helper.readableDatabase.query(
            "records", columns, "id IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray(), null, null, "time DESC"
        ).use(::collect)
    }

    fun count(): Int = rawCount(ACTIVE)

    /** [start, end) 内的记录条数（用于状态通知的「今日已记 N 笔」） */
    fun countInRange(start: Long, end: Long): Int =
        helper.readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM records WHERE $ACTIVE AND time>=? AND time<?",
            arrayOf(start.toString(), end.toString())
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** 某时间段内的支出合计 */
    fun expenseSum(start: Long, end: Long): Double {
        return helper.readableDatabase.rawQuery(
            "SELECT IFNULL(SUM(amount),0) FROM records WHERE $ACTIVE AND type=? AND time>=? AND time<?",
            arrayOf(RecordType.EXPENSE.toString(), start.toString(), end.toString())
        ).use { c -> if (c.moveToFirst()) c.getDouble(0) else 0.0 }
    }

    /** 分类汇总行 */
    data class CatTotal(val type: Int, val category: String, val total: Double)

    /** [start, end) 范围内按 类型+分类 汇总，支出在前、金额降序 */
    fun rangeGrouped(start: Long, end: Long): List<CatTotal> {
        val out = ArrayList<CatTotal>()
        helper.readableDatabase.rawQuery(
            "SELECT type, category, SUM(amount) AS t FROM records " +
                "WHERE $ACTIVE AND time>=? AND time<? GROUP BY type, category ORDER BY type ASC, t DESC",
            arrayOf(start.toString(), end.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(CatTotal(c.getInt(0), c.getString(1), c.getDouble(2)))
        }
        return out
    }

    /** 已使用过的标签（去重，按使用频次倒序） */
    fun allTags(limit: Int = 60): List<String> {
        val out = ArrayList<String>()
        helper.readableDatabase.rawQuery(
            "SELECT tag, COUNT(*) AS n FROM records WHERE $ACTIVE AND tag <> '' GROUP BY tag ORDER BY n DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    /** 组合条件搜索 */
    fun search(q: Query, limit: Int = 800): List<Record> {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        where.add(ACTIVE)
        val text = q.text.trim()
        if (text.isNotEmpty()) {
            where.add("(note LIKE ? OR category LIKE ? OR tag LIKE ? OR pay_method LIKE ?)")
            val like = "%$text%"
            args.add(like); args.add(like); args.add(like); args.add(like)
        }
        if (q.type >= 0) {
            where.add("type = ?")
            args.add(q.type.toString())
        }
        q.category?.takeIf { it.isNotBlank() }?.let {
            where.add("category = ?")
            args.add(it)
        }
        q.tag?.takeIf { it.isNotBlank() }?.let {
            where.add("tag LIKE ?")
            args.add("%$it%")
        }
        q.minAmount?.let {
            where.add("amount >= ?")
            args.add(it.toString())
        }
        q.maxAmount?.let {
            where.add("amount <= ?")
            args.add(it.toString())
        }
        q.start?.let {
            where.add("time >= ?")
            args.add(it.toString())
        }
        q.end?.let {
            where.add("time < ?")
            args.add(it.toString())
        }
        return helper.readableDatabase.query(
            "records", columns, where.joinToString(" AND "), args.toTypedArray(),
            null, null, "time DESC", limit.toString()
        ).use(::collect)
    }

    /**
     * 近 [since] 以来「同金额 + 同方向 + 同商户」的记录条数。
     *
     * 用于聊天转账/红包、应用内支付消息的**按条数同步**：页面上有几个气泡，
     * 库里就该有几条（多退少补）。这样「同一商户每天扣同样金额」「反复测 0.01」
     * 都能各记一笔，而重复扫描同一页不会重复记。
     */
    fun countSimilar(amount: Double, type: Int, note: String, since: Long): Int {
        val sql: String
        val args: Array<String>
        if (note.isBlank()) {
            sql = "SELECT COUNT(*) FROM records WHERE $ACTIVE AND ABS(amount-?)<0.005 AND type=? AND time>=?"
            args = arrayOf(amount.toString(), type.toString(), since.toString())
        } else {
            sql = "SELECT COUNT(*) FROM records WHERE $ACTIVE AND ABS(amount-?)<0.005 AND type=? AND note=? AND time>=?"
            args = arrayOf(amount.toString(), type.toString(), note, since.toString())
        }
        return helper.readableDatabase.rawQuery(sql, args).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    /** 导入去重：同一时刻+同金额+同类型 视为同一条（回收站里的不算） */
    fun existsSame(time: Long, amount: Double, type: Int): Boolean {
        return helper.readableDatabase.rawQuery(
            "SELECT 1 FROM records WHERE $ACTIVE AND time=? AND ABS(amount-?)<0.005 AND type=? LIMIT 1",
            arrayOf(time.toString(), amount.toString(), type.toString())
        ).use { it.moveToFirst() }
    }

    /**
     * 账单页补充时的去重：同金额 + 同类型 + 同商户（note）且时间在 [since, ∞) 内即认为已存在。
     * 商户为空时只比金额与方向，避免重复补齐。
     */
    fun existsSimilar(amount: Double, type: Int, note: String, since: Long): Boolean {
        val sql: String
        val args: Array<String>
        if (note.isBlank()) {
            sql = "SELECT 1 FROM records WHERE $ACTIVE AND ABS(amount-?)<0.005 AND type=? AND time>=? LIMIT 1"
            args = arrayOf(amount.toString(), type.toString(), since.toString())
        } else {
            sql = "SELECT 1 FROM records WHERE $ACTIVE AND ABS(amount-?)<0.005 AND type=? AND note=? AND time>=? LIMIT 1"
            args = arrayOf(amount.toString(), type.toString(), note, since.toString())
        }
        return helper.readableDatabase.rawQuery(sql, args).use { it.moveToFirst() }
    }

    // ---------------- 商户记忆 / 图片 ----------------

    /**
     * 该商户最近一次用的分类（本地「智能匹配」）：同商户同方向取最近一条的分类。
     * 有历史就用历史，没有就让关键词引擎猜。
     */
    fun lastCategoryFor(note: String, type: Int): String? {
        if (note.isBlank()) return null
        return helper.readableDatabase.rawQuery(
            "SELECT category FROM records WHERE $ACTIVE AND note=? AND type=? ORDER BY time DESC LIMIT 1",
            arrayOf(note, type.toString())
        ).use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }

    /** 取这些记录的截图路径（彻底删除前调用，便于同时清理文件） */
    fun imagePathsByIds(ids: List<Long>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        val out = ArrayList<String>()
        helper.readableDatabase.rawQuery(
            "SELECT image_path FROM records WHERE id IN ($placeholders) AND image_path <> ''",
            ids.map { it.toString() }.toTypedArray()
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    /** 单独更新截图路径（补图 / 删除图片时用） */
    fun updateImage(id: Long, path: String): Int {
        val cv = ContentValues().apply { put("image_path", path) }
        return helper.writableDatabase.update("records", cv, "id=?", arrayOf(id.toString()))
    }

    /** 所有仍在使用的截图路径（用于清理孤儿文件） */
    fun allImagePaths(): List<String> {
        val out = ArrayList<String>()
        helper.readableDatabase.rawQuery(
            "SELECT image_path FROM records WHERE image_path <> ''", null
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    // ---------------- 内部 ----------------

    private fun rawCount(clause: String): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM records WHERE $clause", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun updateWhereIds(ids: List<Long>, cv: ContentValues): Int {
        val placeholders = ids.joinToString(",") { "?" }
        return helper.writableDatabase.update(
            "records", cv, "id IN ($placeholders)", ids.map { it.toString() }.toTypedArray()
        )
    }

    private fun mergeTag(existing: String, add: String): String {
        val t = add.trim()
        if (t.isEmpty()) return existing
        if (existing.isBlank()) return t
        val parts = existing.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (parts.none { it.equals(t, ignoreCase = true) }) parts.add(t)
        return parts.joinToString(",")
    }

    private fun collect(c: Cursor): List<Record> {
        val out = ArrayList<Record>(c.count)
        while (c.moveToNext()) out.add(fromCursor(c))
        return out
    }

    private fun fromCursor(c: Cursor) = Record(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        amount = c.getDouble(c.getColumnIndexOrThrow("amount")),
        type = c.getInt(c.getColumnIndexOrThrow("type")),
        category = c.getString(c.getColumnIndexOrThrow("category")),
        note = c.getString(c.getColumnIndexOrThrow("note")),
        tag = c.getString(c.getColumnIndexOrThrow("tag")) ?: "",
        payMethod = c.getString(c.getColumnIndexOrThrow("pay_method")) ?: "",
        source = c.getInt(c.getColumnIndexOrThrow("source")),
        time = c.getLong(c.getColumnIndexOrThrow("time")),
        createTime = c.getLong(c.getColumnIndexOrThrow("create_time")),
        deletedAt = c.getLong(c.getColumnIndexOrThrow("deleted_at")),
        imagePath = c.getString(c.getColumnIndexOrThrow("image_path")) ?: "",
        merchant = c.getString(c.getColumnIndexOrThrow("merchant")) ?: ""
    )
}

package com.jianji.app.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class DbHelper(context: Context) : SQLiteOpenHelper(context.applicationContext, "jianji.db", null, 7) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE records(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "amount REAL NOT NULL," +
                "type INTEGER NOT NULL," +
                "category TEXT NOT NULL," +
                "note TEXT NOT NULL," +
                "tag TEXT NOT NULL DEFAULT ''," +
                "pay_method TEXT NOT NULL DEFAULT ''," +
                "source INTEGER NOT NULL," +
                "time INTEGER NOT NULL," +
                "create_time INTEGER NOT NULL," +
                "deleted_at INTEGER NOT NULL DEFAULT 0," +
                "image_path TEXT NOT NULL DEFAULT ''," +
                "merchant TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL("CREATE INDEX idx_records_time ON records(time)")
        db.execSQL("CREATE INDEX idx_records_category ON records(category)")
        db.execSQL("CREATE INDEX idx_records_amount ON records(amount)")
        db.execSQL("CREATE INDEX idx_records_deleted ON records(deleted_at)")
        db.execSQL("CREATE INDEX idx_records_note ON records(note)")
        db.execSQL("CREATE INDEX idx_records_merchant ON records(merchant)")
        db.execSQL(CREATE_CATEGORY_MEMORY)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v2：新增「标签」列
            runCatching { db.execSQL("ALTER TABLE records ADD COLUMN tag TEXT NOT NULL DEFAULT ''") }
        }
        if (oldVersion < 3) {
            // v3：新增「支付方式 / 支出工具」列
            runCatching { db.execSQL("ALTER TABLE records ADD COLUMN pay_method TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_category ON records(category)") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_amount ON records(amount)") }
        }
        if (oldVersion < 4) {
            // v4：回收站（软删除）
            runCatching { db.execSQL("ALTER TABLE records ADD COLUMN deleted_at INTEGER NOT NULL DEFAULT 0") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_deleted ON records(deleted_at)") }
        }
        if (oldVersion < 5) {
            // v5：账单页面截图路径 + 商户索引（用于「商户记忆分类」与去重查询）
            runCatching { db.execSQL("ALTER TABLE records ADD COLUMN image_path TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_note ON records(note)") }
        }
        if (oldVersion < 6) {
            // v6：分类记忆（学习你改过的分类，之后相似账单自动套用）
            runCatching { db.execSQL(CREATE_CATEGORY_MEMORY) }
        }
        if (oldVersion < 7) {
            // v7：商户名称（识别时自动填；老数据留空，界面自动回退显示备注）
            runCatching { db.execSQL("ALTER TABLE records ADD COLUMN merchant TEXT NOT NULL DEFAULT ''") }
            runCatching { db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_merchant ON records(merchant)") }
        }
    }

    private companion object {
        /**
         * 分类记忆表：`(归一化商户, 分类)` 为主键，累计命中次数与最近使用时间。
         * 你每次手动改分类就 +1，之后相似账单按"分数 → 次数 → 最近"选分类。
         */
        const val CREATE_CATEGORY_MEMORY =
            "CREATE TABLE IF NOT EXISTS category_memory(" +
                "mkey TEXT NOT NULL," +
                "category TEXT NOT NULL," +
                "hits INTEGER NOT NULL DEFAULT 0," +
                "updated_at INTEGER NOT NULL DEFAULT 0," +
                "PRIMARY KEY(mkey, category))"
    }
}

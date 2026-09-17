package com.acsmanager.pro.watchdog

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 事件日志：记录服务启用/停用/丢失/自动恢复等事件，供监控页展示。
 */
object EventLog {

    const val TYPE_ENABLE = "enable"
    const val TYPE_DISABLE = "disable"
    const val TYPE_LOST = "lost"
    const val TYPE_RESTORED = "restored"
    const val TYPE_WARN = "warn"
    const val TYPE_SERVICE = "service"

    data class Entry(
        val id: Long,
        val ts: Long,
        val type: String,
        val pkg: String,
        val detail: String
    )

    fun record(ctx: Context, type: String, pkg: String, detail: String) {
        try {
            val db = EventDb(ctx).writableDatabase
            val cv = ContentValues().apply {
                put("ts", System.currentTimeMillis())
                put("type", type)
                put("pkg", pkg)
                put("detail", detail)
            }
            db.insert("events", null, cv)
            db.close()
        } catch (t: Throwable) {
            // 日志失败不影响主流程
        }
    }

    fun query(ctx: Context, limit: Int = 500): List<Entry> = try {
        val db = EventDb(ctx).readableDatabase
        val list = mutableListOf<Entry>()
        db.query(
            "events", null, null, null, null, null, "ts DESC", "$limit"
        ).use { c ->
            val iId = c.getColumnIndexOrThrow("_id")
            val iTs = c.getColumnIndexOrThrow("ts")
            val iType = c.getColumnIndexOrThrow("type")
            val iPkg = c.getColumnIndexOrThrow("pkg")
            val iDetail = c.getColumnIndexOrThrow("detail")
            while (c.moveToNext()) {
                list.add(
                    Entry(
                        id = c.getLong(iId),
                        ts = c.getLong(iTs),
                        type = c.getString(iType),
                        pkg = c.getString(iPkg),
                        detail = c.getString(iDetail)
                    )
                )
            }
        }
        db.close()
        list
    } catch (t: Throwable) {
        emptyList()
    }

    fun clear(ctx: Context) {
        try {
            val db = EventDb(ctx).writableDatabase
            db.delete("events", null, null)
            db.close()
        } catch (t: Throwable) {
        }
    }

    private class EventDb(ctx: Context) : SQLiteOpenHelper(ctx, "events.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS events(" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "ts INTEGER NOT NULL," +
                    "type TEXT NOT NULL," +
                    "pkg TEXT," +
                    "detail TEXT)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) = Unit
    }
}

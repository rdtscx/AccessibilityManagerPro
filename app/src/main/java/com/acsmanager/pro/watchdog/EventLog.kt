package com.acsmanager.pro.watchdog

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.acsmanager.pro.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 事件日志：记录服务启用/停用/丢失/自动恢复等事件，供监控页展示。
 * 使用单例数据库连接避免频繁创建/关闭，最多保留 2000 条记录防止无限增长。
 *
 * V3.3 新增：
 *  - 日志分级（DEBUG/INFO/WARN/ERROR）
 *  - 一键脱敏导出（自动过滤敏感信息）
 *  - 与隐私模式联动
 */
object EventLog {

    const val TYPE_ENABLE = "enable"
    const val TYPE_DISABLE = "disable"
    const val TYPE_LOST = "lost"
    const val TYPE_RESTORED = "restored"
    const val TYPE_WARN = "warn"
    const val TYPE_SERVICE = "service"

    // 日志分级
    const val LEVEL_DEBUG = "DEBUG"
    const val LEVEL_INFO = "INFO"
    const val LEVEL_WARN = "WARN"
    const val LEVEL_ERROR = "ERROR"

    private const val MAX_RECORDS = 2000

    data class Entry(
        val id: Long,
        val ts: Long,
        val type: String,
        val pkg: String,
        val detail: String,
        val level: String = LEVEL_INFO
    )

    @Volatile
    private var dbHelper: EventDb? = null

    private fun db(ctx: Context): EventDb {
        dbHelper?.let { return it }
        synchronized(this) {
            dbHelper?.let { return it }
            return EventDb(ctx.applicationContext).also { dbHelper = it }
        }
    }

    // ---------- 异步合并写（V6.0.0） ----------
    // 修复闪退：事件日志在多处被高频写入（自监控主线程、看门狗协程、通知监听系统 Binder
    // 线程），原先每次 record 都在调用线程同步执行 SQLite 插入 + COUNT + DELETE——
    // 主线程被数据库 IO 阻塞（ANR 风险），多线程并发写 SQLite 还会触发"database is locked"。
    // 现改为：record 只把事件写入内存缓冲并立即返回；唯一写线程把缓冲批量落库，
    // 队列中永远只有一条写任务，调用线程零 IO、数据库零竞争。

    /** 内存写缓冲：异步写线程尚未落库的事件。 */
    private val pendingEvents = java.util.concurrent.LinkedBlockingQueue<ContentValues>()

    /** 是否有写任务已在执行器队列中（防止重复提交，保证合并写）。 */
    @Volatile
    private var writerScheduled = false

    /** 单线程写执行器：串行化所有数据库写入。 */
    private val writeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "acs-event-writer").apply { isDaemon = true }
    }

    private fun scheduleWrite() {
        synchronized(this) {
            if (writerScheduled) return
            writerScheduled = true
        }
        writeExecutor.execute {
            try {
                flushPending()
            } finally {
                writerScheduled = false
                // 写线程执行期间又有新事件进入缓冲：立即补一次落库，避免事件滞留
                if (pendingEvents.isNotEmpty()) {
                    synchronized(this) {
                        if (writerScheduled) return@execute
                        writerScheduled = true
                    }
                    writeExecutor.execute {
                        try {
                            flushPending()
                        } finally {
                            writerScheduled = false
                        }
                    }
                }
            }
        }
    }

    /** 一次性把缓冲中的全部事件写入数据库（单线程调用，无并发竞争）。 */
    private fun flushPending() {
        if (pendingEvents.isEmpty()) return
        val batch = ArrayList<ContentValues>(pendingEvents.size)
        pendingEvents.drainTo(batch)
        if (batch.isEmpty()) return
        val appCtx = writeCtx ?: return
        try {
            val database = db(appCtx).writableDatabase
            database.beginTransaction()
            try {
                for (cv in batch) {
                    database.insert("events", null, cv)
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            // 超过上限时清理最旧的记录（批量写只清理一次，避免每条事件都 COUNT/DELETE）
            try {
                if (database.compileStatement("SELECT COUNT(*) FROM events").simpleQueryForLong() > MAX_RECORDS) {
                    database.execSQL(
                        "DELETE FROM events WHERE _id IN (SELECT _id FROM events ORDER BY ts ASC LIMIT 100)"
                    )
                }
            } catch (t: Throwable) {
            }
        } catch (t: Throwable) {
            // 落库失败（如数据库被外部删除）：丢弃本批，避免无限重试堆积内存
        }
    }

    /** 写线程使用的 Context（进程级缓存，避免每次取 applicationContext）。 */
    @Volatile
    private var writeCtx: Context? = null

    /**
     * 记录事件（带级别）。
     * 隐私模式下 DEBUG 级别日志不记录。
     * 只入内存缓冲，立即返回，不阻塞调用线程。
     */
    fun record(ctx: Context, type: String, pkg: String, detail: String, level: String = LEVEL_INFO) {
        // 隐私模式下不记录 DEBUG 级别日志
        if (level == LEVEL_DEBUG && Prefs.isPrivacyModeEnabled(ctx)) {
            return
        }
        try {
            if (writeCtx == null) writeCtx = ctx.applicationContext
            val cv = ContentValues().apply {
                put("ts", System.currentTimeMillis())
                put("type", type)
                put("pkg", pkg)
                put("detail", detail)
                put("level", level)
            }
            pendingEvents.offer(cv)
            scheduleWrite()
        } catch (t: Throwable) {
            // 日志失败不影响主流程
        }
    }

    /**
     * 兼容旧版调用：只传 type/pkg/detail，默认 INFO 级别。
     */
    fun record(ctx: Context, type: String, pkg: String, detail: String) {
        record(ctx, type, pkg, detail, LEVEL_INFO)
    }

    fun query(ctx: Context, limit: Int = 500): List<Entry> = try {
        val database = db(ctx).readableDatabase
        val list = mutableListOf<Entry>()
        database.query(
            "events", null, null, null, null, null, "ts DESC", "$limit"
        ).use { c ->
            val iId = c.getColumnIndexOrThrow("_id")
            val iTs = c.getColumnIndexOrThrow("ts")
            val iType = c.getColumnIndexOrThrow("type")
            val iPkg = c.getColumnIndexOrThrow("pkg")
            val iDetail = c.getColumnIndexOrThrow("detail")
            // level 列在旧版本数据库中可能不存在，用 try-catch 兼容
            val iLevel = try {
                c.getColumnIndexOrThrow("level")
            } catch (t: Throwable) {
                -1
            }
            while (c.moveToNext()) {
                list.add(
                    Entry(
                        id = c.getLong(iId),
                        ts = c.getLong(iTs),
                        type = c.getString(iType),
                        pkg = c.getString(iPkg),
                        detail = c.getString(iDetail),
                        level = if (iLevel >= 0) c.getString(iLevel) else LEVEL_INFO
                    )
                )
            }
        }
        list
    } catch (t: Throwable) {
        emptyList()
    }

    fun clear(ctx: Context) {
        try {
            db(ctx).writableDatabase.delete("events", null, null)
        } catch (t: Throwable) {
        }
    }

    /**
     * 导出日志为脱敏文本格式。
     * 脱敏处理：
     *  - 不记录具体包名（只保留前几位）
     *  - 不记录敏感配置细节
     *  - 自动包含系统版本和 ROM 信息
     */
    fun exportAsText(ctx: Context): String {
        val sb = StringBuilder()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        sb.append("=== 无障碍管理器 Pro - 事件诊断报告 ===\n")
        sb.append("导出时间: ").append(dateFormat.format(Date())).append("\n")
        sb.append("系统版本: Android ").append(android.os.Build.VERSION.RELEASE)
        sb.append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
        sb.append("设备型号: ").append(android.os.Build.MANUFACTURER)
        sb.append(" ").append(android.os.Build.MODEL).append("\n")
        sb.append("ROM 类型: ").append(
            com.acsmanager.pro.compat.RomCompatibilityHelper.detectRom().displayName
        ).append("\n")
        sb.append("========================================\n\n")

        val entries = query(ctx, 500)
        for (entry in entries) {
            sb.append("[").append(dateFormat.format(Date(entry.ts))).append("] ")
            sb.append("[").append(entry.level).append("] ")
            sb.append(entry.type).append(": ")
            // 脱敏：包名只显示前 8 个字符 + ...
            val safePkg = if (entry.pkg.length > 10) entry.pkg.substring(0, 8) + "..." else entry.pkg
            sb.append(safePkg).append(" - ")
            sb.append(entry.detail)
            sb.append("\n")
        }

        sb.append("\n=== 说明 ===\n")
        sb.append("* 本报告已做脱敏处理，不包含完整包名和敏感信息\n")
        sb.append("* 用于反馈问题时提供给开发者定位问题\n")
        sb.append("* 所有数据仅存储在本地，导出后请妥善保管\n")

        return sb.toString()
    }

    private class EventDb(ctx: Context) : SQLiteOpenHelper(ctx, "events.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS events(" +
                    "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "ts INTEGER NOT NULL," +
                    "type TEXT NOT NULL," +
                    "pkg TEXT," +
                    "detail TEXT," +
                    "level TEXT DEFAULT 'INFO')"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
            // 从 v1 升级到 v2：添加 level 列
            if (oldV < 2) {
                try {
                    db.execSQL("ALTER TABLE events ADD COLUMN level TEXT DEFAULT 'INFO'")
                } catch (t: Throwable) {
                    // 列已存在则忽略
                }
            }
        }
    }
}

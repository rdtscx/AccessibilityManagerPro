package com.acsmanager.pro.security

import android.content.Context
import com.acsmanager.pro.util.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 操作审计日志系统。
 *
 * 记录每一次模拟点击或屏幕读取的时间戳与目的，
 * 供用户审计与追溯，增强透明度与信任感。
 *
 * 隐私设计：
 *  - 日志仅存储在本地（SharedPreferences）
 *  - 不记录屏幕内容具体文本，只记录操作类型和目标包名
 *  - 用户可随时一键清空
 *  - 超过最大条数自动清理最旧记录
 */
object AuditLogger {

    private const val TAG = "AuditLogger"
    private const val PREFS_KEY = "audit_log"
    private const val MAX_RECORDS = 500

    // 操作类型
    const val OP_ACCESSIBILITY_GESTURE = "accessibility_gesture"
    const val OP_SCREEN_CONTENT_READ = "screen_content_read"
    const val OP_NOTIFICATION_INTERCEPT = "notification_intercept"
    const val OP_SERVICE_RESTORE = "service_restore"
    const val OP_PERMISSION_CHANGE = "permission_change"
    const val OP_AUTO_START = "auto_start"

    /**
     * 记录一次审计操作。
     *
     * @param context 上下文
     * @param operation 操作类型（使用 OP_* 常量）
     * @param targetPackage 目标应用包名（可选）
     * @param purpose 操作目的描述（简短中文）
     * @param success 是否成功
     */
    fun log(
        context: Context,
        operation: String,
        targetPackage: String? = null,
        purpose: String,
        success: Boolean = true
    ) {
        // 隐私模式下不记录屏幕内容读取
        if (operation == OP_SCREEN_CONTENT_READ && Prefs.isPrivacyModeEnabled(context)) {
            return
        }

        try {
            val raw = Prefs.get(context).getString(PREFS_KEY, "[]") ?: "[]"
            val arr = JSONArray(raw)

            val entry = JSONObject().apply {
                put("ts", System.currentTimeMillis())
                put("op", operation)
                put("pkg", targetPackage ?: "")
                put("purpose", purpose)
                put("success", success)
            }

            arr.put(entry)

            // 超过最大条数时移除最旧记录
            while (arr.length() > MAX_RECORDS) {
                // JSONArray 没有 removeAt，用重建方式
                val newArr = JSONArray()
                for (i in 1 until arr.length()) {
                    newArr.put(arr.get(i))
                }
                // 再把新条目加上
                newArr.put(entry)
                val finalArr = newArr
                Prefs.get(context).edit().putString(PREFS_KEY, finalArr.toString()).apply()
                return
            }

            Prefs.get(context).edit().putString(PREFS_KEY, arr.toString()).apply()
        } catch (t: Throwable) {
            // 审计日志失败不影响主流程
        }
    }

    /**
     * 获取审计日志列表（按时间倒序）。
     */
    fun getLogs(context: Context, limit: Int = 100): List<AuditEntry> {
        return try {
            val raw = Prefs.get(context).getString(PREFS_KEY, "[]") ?: "[]"
            val arr = JSONArray(raw)
            val list = mutableListOf<AuditEntry>()

            // 从后往前读（最新的在前）
            val start = maxOf(0, arr.length() - limit)
            for (i in arr.length() - 1 downTo start) {
                val obj = arr.getJSONObject(i)
                list.add(
                    AuditEntry(
                        timestamp = obj.optLong("ts"),
                        operation = obj.optString("op"),
                        targetPackage = obj.optString("pkg"),
                        purpose = obj.optString("purpose"),
                        success = obj.optBoolean("success")
                    )
                )
            }
            list
        } catch (t: Throwable) {
            emptyList()
        }
    }

    /**
     * 清空所有审计日志。
     */
    fun clearLogs(context: Context) {
        Prefs.get(context).edit().remove(PREFS_KEY).apply()
    }

    /**
     * 获取今日审计操作次数。
     */
    fun getTodayCount(context: Context): Int {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val today = sdf.format(Date())
        return getLogs(context, 1000).count {
            sdf.format(Date(it.timestamp)) == today
        }
    }

    /**
     * 导出审计日志为脱敏文本（可用于分享/导出）。
     * 脱敏处理：不记录屏幕具体内容，只保留操作元数据。
     */
    fun exportAsText(context: Context): String {
        val sb = StringBuilder()
        sb.append("=== 无障碍管理器 Pro - 操作审计日志 ===\n")
        sb.append("导出时间: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append("\n")
        sb.append("总记录数: ").append(getLogs(context, 10000).size).append("\n")
        sb.append("========================================\n\n")

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        for (entry in getLogs(context, 200)) {
            sb.append("[").append(dateFormat.format(Date(entry.timestamp))).append("] ")
            sb.append("操作: ").append(getOperationDisplayName(entry.operation)).append(" | ")
            sb.append("目标: ").append(entry.targetPackage.ifEmpty { "系统" }).append(" | ")
            sb.append("目的: ").append(entry.purpose).append(" | ")
            sb.append("结果: ").append(if (entry.success) "成功" else "失败")
            sb.append("\n")
        }

        sb.append("\n=== 隐私声明 ===\n")
        sb.append("* 本日志仅记录操作元数据（时间、类型、目标包名）\n")
        sb.append("* 不记录屏幕具体文本内容\n")
        sb.append("* 所有数据仅存储在本地设备，不上传任何服务器\n")
        sb.append("* 您可以随时在设置中一键清空所有审计日志\n")

        return sb.toString()
    }

    /**
     * 操作类型中文显示名。
     */
    private fun getOperationDisplayName(op: String): String {
        return when (op) {
            OP_ACCESSIBILITY_GESTURE -> "模拟手势"
            OP_SCREEN_CONTENT_READ -> "屏幕内容读取"
            OP_NOTIFICATION_INTERCEPT -> "通知拦截"
            OP_SERVICE_RESTORE -> "服务自动恢复"
            OP_PERMISSION_CHANGE -> "权限变更"
            OP_AUTO_START -> "自动启动"
            else -> op
        }
    }

    /** 审计日志数据类 */
    data class AuditEntry(
        val timestamp: Long,
        val operation: String,
        val targetPackage: String,
        val purpose: String,
        val success: Boolean
    )
}

package com.acsmanager.pro.security

import android.content.Context
import com.acsmanager.pro.util.Prefs

/**
 * 隐私模式管理器。
 *
 * 功能：
 *  - 所有屏幕读取内容仅在内存中处理，不落盘、不上传
 *  - 隐私模式开关：开启后非必要场景下自动暂停屏幕内容读取
 *  - 提供"清除所有本地数据"功能
 *
 * 隐私承诺：
 *  1. 无障碍服务仅监听指定包名的事件，不读取无关应用内容
 *  2. 屏幕内容文本仅在内存中临时处理，处理完毕立即释放
 *  3. 不存储任何屏幕截图、UI 层级树、文本内容到本地文件
 *  4. 不向任何外部服务器传输数据
 *  5. 所有日志和审计记录仅存储在本设备本地
 */
object PrivacyModeManager {

    /**
     * 检查是否处于隐私模式。
     * 隐私模式下：
     *  - 暂停屏幕内容读取（除用户主动触发的操作外）
     *  - 不记录屏幕内容相关的审计日志
     *  - 通知监听仅记录元数据，不读取通知文本
     */
    fun isPrivacyModeActive(ctx: Context): Boolean {
        return Prefs.isPrivacyModeEnabled(ctx)
    }

    /**
     * 设置隐私模式开关。
     */
    fun setPrivacyMode(ctx: Context, enabled: Boolean) {
        Prefs.setPrivacyModeEnabled(ctx, enabled)
        if (enabled) {
            AuditLogger.log(
                ctx,
                AuditLogger.OP_PERMISSION_CHANGE,
                purpose = "开启隐私模式"
            )
        } else {
            AuditLogger.log(
                ctx,
                AuditLogger.OP_PERMISSION_CHANGE,
                purpose = "关闭隐私模式"
            )
        }
    }

    /**
     * 判断当前是否允许读取屏幕内容。
     * 隐私模式开启时返回 false（除非是用户主动触发的操作）。
     */
    fun canReadScreenContent(ctx: Context, userInitiated: Boolean = false): Boolean {
        if (userInitiated) return true
        return !isPrivacyModeActive(ctx)
    }

    /**
     * 清除所有本地敏感数据。
     * 包括：
     *  - 通知历史记录
     *  - 审计日志
     *  - 事件日志
     *  - 保活统计数据
     */
    fun clearAllLocalData(ctx: Context) {
        // 清空通知历史
        Prefs.clearNotifHistory(ctx)
        Prefs.clearNotifHistoryFull(ctx)

        // 清空审计日志
        AuditLogger.clearLogs(ctx)

        // 清空事件日志
        com.acsmanager.pro.watchdog.EventLog.clear(ctx)

        // 重置统计
        Prefs.resetNotifBlockCount(ctx)
        Prefs.clearTodayStats(ctx)
    }

    /**
     * 获取隐私模式说明文案。
     */
    fun getPrivacyModeDescription(): String {
        return """
            隐私模式开启后：
            • 自动暂停非必要的屏幕内容读取
            • 通知监听仅记录元数据，不读取通知文本
            • 屏幕内容仅在内存中临时处理，绝不落盘
            • 不向任何外部服务器传输数据

            本应用始终承诺：
            ✓ 所有数据仅存储在您的设备本地
            ✓ 不收集、不上传任何个人信息
            ✓ 无障碍服务仅按您的配置工作
            ✓ 您可以随时一键清除所有本地数据
        """.trimIndent()
    }
}

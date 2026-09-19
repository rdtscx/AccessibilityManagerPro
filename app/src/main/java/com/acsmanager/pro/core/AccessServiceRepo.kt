package com.acsmanager.pro.core

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * 设备上安装/启用的无障碍服务数据模型。
 */
data class AccessServiceItem(
    val component: ComponentName,
    val packageName: String,
    val label: String,
    val summary: String?,
    val description: String?,
    val enabled: Boolean,
    val capabilities: Int,
    val feedbackType: Int,
    val eventTypes: Int,
    val flags: Int,
    val settingsActivity: String?
) {
    val flatten: String get() = component.flattenToString()
}

/**
 * 读取设备真实的无障碍服务列表（已安装 + 已启用）。
 * 原理与原版一致：通过 AccessibilityManager 系统服务查询。
 */
object AccessServiceRepo {

    fun loadServices(context: Context): List<AccessServiceItem> {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val installed = am.getInstalledAccessibilityServiceList().orEmpty()
        val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).orEmpty()
        val enabledComponents = enabled.mapNotNull { info ->
            val si = info.resolveInfo?.serviceInfo ?: return@mapNotNull null
            ComponentName(si.packageName, si.name)
        }.toSet()

        return installed.mapNotNull { info ->
            val si = info.resolveInfo?.serviceInfo ?: return@mapNotNull null
            val cn = ComponentName(si.packageName, si.name)
            AccessServiceItem(
                component = cn,
                packageName = si.packageName,
                label = info.resolveInfo.loadLabel(context.packageManager).toString(),
                summary = if (android.os.Build.VERSION.SDK_INT >= 24) {
                    try {
                        info.loadSummary(context.packageManager)?.toString()
                    } catch (t: Throwable) {
                        null
                    }
                } else {
                    null
                },
                // API 30+ description 字段已废弃，改用 loadDescription() 加载
                description = try {
                    info.loadDescription(context.packageManager)?.toString()
                } catch (t: Throwable) {
                    null
                },
                enabled = cn in enabledComponents,
                capabilities = info.capabilities,
                feedbackType = info.feedbackType,
                eventTypes = info.eventTypes,
                flags = info.flags,
                settingsActivity = info.settingsActivityName
            )
        }.sortedWith(compareByDescending<AccessServiceItem> { it.enabled }.thenBy { it.label.lowercase() })
    }

    /** 当前 enabled_accessibility_services 中的组件串列表。 */
    fun enabledStrings(context: Context): List<String> {
        val raw = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return emptyList()
        return raw.split(':').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun isAccessibilityEnabled(context: Context): Boolean =
        Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
}

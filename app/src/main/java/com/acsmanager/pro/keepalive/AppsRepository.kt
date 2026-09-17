package com.acsmanager.pro.keepalive

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.util.LruCache

/**
 * 全机应用扫描：罗列所有用户安装软件与系统软件，供"保活"与"通知调控"选择。
 */
data class AppEntry(
    val packageName: String,
    val label: String,
    val isSystem: Boolean,
    val hasLauncher: Boolean
)

object AppsRepository {

    /** 图标缓存：列表滚动时避免重复解码（省内存、防卡顿）。 */
    private val iconCache = object : LruCache<String, Drawable>(96) {
        override fun sizeOf(key: String, value: Drawable): Int = 1
    }

    @Volatile
    private var cachedAll: List<AppEntry>? = null
    @Volatile
    private var cachedUserOnly: List<AppEntry>? = null
    @Volatile
    private var cacheTime = 0L

    private const val SCAN_TTL_MS = 60_000L

    /**
     * 扫描全机应用。Android 11+ 需要 QUERY_ALL_PACKAGES（已在 Manifest 声明）。
     * 结果缓存 60 秒，按 includeSystem 分别缓存，避免参数不同时返回错误结果。
     * @param includeSystem 是否包含系统软件
     */
    fun scan(ctx: Context, includeSystem: Boolean = true): List<AppEntry> {
        val now = System.currentTimeMillis()
        val cached = if (includeSystem) cachedAll else cachedUserOnly
        if (cached != null && now - cacheTime < SCAN_TTL_MS) return cached

        val pm = ctx.packageManager
        val apps = pm.getInstalledApplications(0).orEmpty()
        val allList = mutableListOf<AppEntry>()
        val userList = mutableListOf<AppEntry>()
        for (ai in apps) {
            if (ai.packageName == ctx.packageName) continue
            val label = try {
                pm.getApplicationLabel(ai)?.toString() ?: ai.packageName
            } catch (t: Throwable) {
                ai.packageName
            }
            val hasLauncher = try {
                pm.getLaunchIntentForPackage(ai.packageName) != null
            } catch (t: Throwable) {
                false
            }
            val entry = AppEntry(
                packageName = ai.packageName,
                label = label,
                isSystem = ai.isSystem,
                hasLauncher = hasLauncher
            )
            allList.add(entry)
            if (!ai.isSystem) userList.add(entry)
        }
        val sortedAll = allList.sortedBy { it.label.lowercase() }
        val sortedUser = userList.sortedBy { it.label.lowercase() }
        cachedAll = sortedAll
        cachedUserOnly = sortedUser
        cacheTime = now
        return if (includeSystem) sortedAll else sortedUser
    }

    fun invalidateScanCache() {
        cachedAll = null
        cachedUserOnly = null
        cacheTime = 0L
    }

    /** 取应用图标（LruCache 缓存，仅列表可见时加载）。 */
    fun icon(ctx: Context, pkg: String): Drawable? {
        iconCache.get(pkg)?.let { return it }
        return try {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            iconCache.put(pkg, d)
            d
        } catch (t: Throwable) {
            null
        }
    }

    fun launchIntent(ctx: Context, pkg: String): Intent? = try {
        ctx.packageManager.getLaunchIntentForPackage(pkg)
    } catch (t: Throwable) {
        null
    }

    /** 应用显示名（用于保活提示等），取不到时返回 null。 */
    fun labelOf(ctx: Context, pkg: String): String? = try {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        ctx.packageManager.getApplicationLabel(ai)?.toString()
    } catch (t: Throwable) {
        null
    }

    private val ApplicationInfo.isSystem: Boolean
        get() = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
}

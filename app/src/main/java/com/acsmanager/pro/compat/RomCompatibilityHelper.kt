package com.acsmanager.pro.compat

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * ROM 兼容性特征库。
 *
 * 内置主流国产 ROM 的后台限制特征与保活适配方案：
 *  - MIUI / HyperOS（小米/红米）
 *  - ColorOS（OPPO/一加/realme）
 *  - OriginOS（vivo/iQOO）
 *  - EMUI / HarmonyOS（华为/荣耀）
 *  - One UI（三星）
 *  - 原生 AOSP
 *
 * 每个 ROM 包含：
 *  - 白名单设置页面 Intent（引导用户加后台白名单）
 *  - 自启动管理页面 Intent
 *  - 电池优化设置页面 Intent
 *  - 典型后台限制特征描述
 */
object RomCompatibilityHelper {

    private const val TAG = "RomCompat"

    /** ROM 类型枚举 */
    enum class RomType(val displayName: String, val manufacturer: String) {
        MIUI("MIUI / HyperOS", "Xiaomi"),
        COLOR_OS("ColorOS", "OPPO"),
        ORIGIN_OS("OriginOS / Funtouch OS", "vivo"),
        EMUI("EMUI / HarmonyOS", "Huawei"),
        ONE_UI("One UI", "samsung"),
        NUBIA_UI("nubia UI", "nubia"),
        LITTLE_OS("LittleOS / ZUI", "Lenovo"),
        MEIZU_FLYME("Flyme", "Meizu"),
        NATIVE_AOSP("原生 Android", "")
    }

    /** 检测当前设备 ROM 类型 */
    fun detectRom(): RomType {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()

        return when {
            // 小米 / 红米
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
            brand.contains("xiaomi") || brand.contains("redmi") -> RomType.MIUI

            // OPPO / 一加 / realme
            manufacturer.contains("oppo") || manufacturer.contains("oneplus") ||
            manufacturer.contains("realme") || brand.contains("oppo") -> RomType.COLOR_OS

            // vivo / iQOO
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") ||
            brand.contains("vivo") || brand.contains("iqoo") -> RomType.ORIGIN_OS

            // 华为 / 荣耀
            manufacturer.contains("huawei") || manufacturer.contains("honor") ||
            brand.contains("huawei") || brand.contains("honor") -> RomType.EMUI

            // 三星
            manufacturer.contains("samsung") || brand.contains("samsung") -> RomType.ONE_UI

            // 努比亚
            manufacturer.contains("nubia") || brand.contains("nubia") -> RomType.NUBIA_UI

            // 联想 / 拯救者
            manufacturer.contains("lenovo") || brand.contains("lenovo") -> RomType.LITTLE_OS

            // 魅族
            manufacturer.contains("meizu") || brand.contains("meizu") -> RomType.MEIZU_FLYME

            // 其他视为原生
            else -> RomType.NATIVE_AOSP
        }
    }

    /**
     * 获取当前 ROM 的后台限制提示文案。
     * 用于在引导页告诉用户该 ROM 常见的后台杀进程策略。
     */
    fun getBackgroundRestrictionTip(): String {
        return when (detectRom()) {
            RomType.MIUI -> "小米系（MIUI/HyperOS）默认开启神隐模式，会在息屏后限制应用后台运行。请将本应用加入自启动白名单，并关闭省电策略中的后台限制。"
            RomType.COLOR_OS -> "OPPO 系（ColorOS）开启'应用速冻'和'电池优化'会冻结后台应用。请关闭本应用的应用速冻，并在电池设置中选择'不优化'。"
            RomType.ORIGIN_OS -> "vivo 系（OriginOS）后台管控严格，锁屏后会自动清理后台。请开启本应用的自启动权限，并在后台弹出界面权限中允许。"
            RomType.EMUI -> "华为/荣耀系（EMUI/HarmonyOS）受应用启动管理管控，默认自动管理会限制后台。请改为手动管理，允许自启动和后台活动。"
            RomType.ONE_UI -> "三星 One UI 会将不常用应用置于睡眠状态。请在电池和设备维护中，将本应用从睡眠应用列表移除。"
            RomType.NUBIA_UI -> "努比亚 UI 后台清理策略较激进，请在管家类应用中添加本应用白名单。"
            RomType.LITTLE_OS -> "联想 ZUI 后台管控较严格，请在手机管家中开启自启动并添加后台白名单。"
            RomType.MEIZU_FLYME -> "魅族 Flyme 自带后台清理机制，请在手机管城中允许本应用后台运行。"
            RomType.NATIVE_AOSP -> "原生 Android 后台限制相对宽松，建议关闭电池优化以保证服务持续运行。"
        }
    }

    /**
     * 打开自启动管理页面（引导用户授权自启动）。
     * 不同 ROM 的 Activity 路径不同，依次尝试常见路径。
     *
     * @return true 表示成功打开了某个页面
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val rom = detectRom()
        val intents = when (rom) {
            RomType.MIUI -> listOf(
                Intent().setComponent(ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )),
                Intent().setComponent(ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.securitycenter.MainMainGridTabActivity"
                ))
            )
            RomType.COLOR_OS -> listOf(
                Intent().setComponent(ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                )),
                Intent().setComponent(ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                )),
                Intent().setComponent(ComponentName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity"
                ))
            )
            RomType.ORIGIN_OS -> listOf(
                Intent().setComponent(ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                )),
                Intent().setComponent(ComponentName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                ))
            )
            RomType.EMUI -> listOf(
                Intent().setComponent(ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                )),
                Intent().setComponent(ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                ))
            )
            RomType.ONE_UI -> listOf(
                Intent().setComponent(ComponentName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity"
                ))
            )
            else -> emptyList()
        }

        return tryStartActivity(context, intents)
    }

    /**
     * 打开电池优化忽略设置页面。
     */
    fun openBatteryOptimizationSettings(context: Context): Boolean {
        // Android 6.0+ 标准 API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "open battery optimization settings failed", t)
            }
        }

        // 各 ROM 自定义路径兜底
        val rom = detectRom()
        val intents = when (rom) {
            RomType.MIUI -> listOf(
                Intent().setComponent(ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity"
                ))
            )
            RomType.COLOR_OS -> listOf(
                Intent().setComponent(ComponentName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.sysbgapp.FreezeAppListActivity"
                ))
            )
            else -> emptyList()
        }

        return tryStartActivity(context, intents)
    }

    /**
     * 打开后台运行权限设置页面（各 ROM 后台管理）。
     */
    fun openBackgroundRunSettings(context: Context): Boolean {
        val rom = detectRom()
        val intents = when (rom) {
            RomType.MIUI -> listOf(
                Intent().setComponent(ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                ))
            )
            RomType.EMUI -> listOf(
                Intent().setComponent(ComponentName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                ))
            )
            RomType.ORIGIN_OS -> listOf(
                Intent().setComponent(ComponentName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                ))
            )
            else -> emptyList()
        }

        return tryStartActivity(context, intents)
    }

    /**
     * 检查应用是否已加入电池优化白名单。
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * 请求忽略电池优化（需要 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 权限）。
     */
    fun requestIgnoreBatteryOptimization(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = android.net.Uri.parse("package:${context.packageName}")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "request ignore battery optimization failed", t)
            false
        }
    }

    /**
     * 依次尝试启动 Intent 列表中的第一个可用 Activity。
     */
    private fun tryStartActivity(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return true
            } catch (t: Throwable) {
                Log.w(TAG, "start activity failed: $intent", t)
            }
        }
        return false
    }

    /**
     * 获取当前 ROM 的推荐保活配置建议。
     * 返回 key-value 对，用于设置页展示。
     */
    fun getRecommendedConfig(): Map<String, String> {
        return when (detectRom()) {
            RomType.MIUI -> mapOf(
                "自启动" to "必须开启",
                "神隐模式" to "选择'无限制'",
                "电池优化" to "不优化",
                "锁定后台" to "最近任务中锁定"
            )
            RomType.COLOR_OS -> mapOf(
                "自启动" to "必须开启",
                "应用速冻" to "关闭本应用",
                "电池优化" to "不优化",
                "后台冻结" to "关闭"
            )
            RomType.ORIGIN_OS -> mapOf(
                "自启动" to "必须开启",
                "后台弹出界面" to "允许",
                "电池优化" to "不优化",
                "锁屏清理" to "加入白名单"
            )
            RomType.EMUI -> mapOf(
                "应用启动管理" to "改为手动管理",
                "自启动" to "允许",
                "后台活动" to "允许",
                "电池优化" to "不优化"
            )
            else -> mapOf(
                "电池优化" to "不优化",
                "后台限制" to "无限制"
            )
        }
    }
}

package com.acsmanager.pro.core

import android.content.Context
import android.os.Build

/**
 * 权限管理器（类似权限狗）核心：
 * 系统级危险权限组定义、授予状态查询（dumpsys package 解析）、
 * 授予/撤销（pm grant/revoke，经 Root / Shizuku shell 通道执行）。
 * ADB 通道（WRITE_SECURE_SETTINGS）不具备 shell 身份，降级为输出 adb 命令。
 */
object PermGroups {

    data class PermDef(val permission: String, val minApi: Int)
    data class Group(val label: String, val perms: List<PermDef>)

    private val ALL = listOf(
        Group(
            "日历",
            listOf(
                PermDef("android.permission.READ_CALENDAR", 21),
                PermDef("android.permission.WRITE_CALENDAR", 21)
            )
        ),
        Group(
            "通话记录",
            listOf(
                PermDef("android.permission.READ_CALL_LOG", 21),
                PermDef("android.permission.WRITE_CALL_LOG", 21),
                PermDef("android.permission.PROCESS_OUTGOING_CALLS", 21)
            )
        ),
        Group(
            "相机",
            listOf(PermDef("android.permission.CAMERA", 21))
        ),
        Group(
            "通讯录",
            listOf(
                PermDef("android.permission.READ_CONTACTS", 21),
                PermDef("android.permission.WRITE_CONTACTS", 21)
            )
        ),
        Group(
            "位置",
            listOf(
                PermDef("android.permission.ACCESS_FINE_LOCATION", 21),
                PermDef("android.permission.ACCESS_COARSE_LOCATION", 21),
                PermDef("android.permission.ACCESS_BACKGROUND_LOCATION", 29)
            )
        ),
        Group(
            "麦克风",
            listOf(PermDef("android.permission.RECORD_AUDIO", 21))
        ),
        Group(
            "电话",
            listOf(
                PermDef("android.permission.READ_PHONE_STATE", 21),
                PermDef("android.permission.CALL_PHONE", 21),
                PermDef("android.permission.READ_PHONE_NUMBERS", 26),
                PermDef("android.permission.ANSWER_PHONE_CALLS", 26),
                PermDef("android.permission.ADD_VOICEMAIL", 21)
            )
        ),
        Group(
            "身体传感器",
            listOf(PermDef("android.permission.BODY_SENSORS", 21))
        ),
        Group(
            "活动识别",
            listOf(PermDef("android.permission.ACTIVITY_RECOGNITION", 29))
        ),
        Group(
            "短信",
            listOf(
                PermDef("android.permission.SEND_SMS", 21),
                PermDef("android.permission.RECEIVE_SMS", 21),
                PermDef("android.permission.READ_SMS", 21),
                PermDef("android.permission.RECEIVE_WAP_PUSH", 21),
                PermDef("android.permission.RECEIVE_MMS", 21)
            )
        ),
        Group(
            "存储",
            listOf(
                PermDef("android.permission.READ_EXTERNAL_STORAGE", 21),
                PermDef("android.permission.WRITE_EXTERNAL_STORAGE", 21)
            )
        ),
        Group(
            "媒体（Android 13+）",
            listOf(
                PermDef("android.permission.READ_MEDIA_IMAGES", 33),
                PermDef("android.permission.READ_MEDIA_VIDEO", 33),
                PermDef("android.permission.READ_MEDIA_AUDIO", 33)
            )
        ),
        Group(
            "通知（Android 13+）",
            listOf(PermDef("android.permission.POST_NOTIFICATIONS", 33))
        )
    )

    /** 按当前系统版本过滤（低版本不显示高版本才有的权限，避免 pm grant 报错）。 */
    fun groupsForSdk(): List<Group> = ALL
        .map { g -> g.copy(perms = g.perms.filter { Build.VERSION.SDK_INT >= it.minApi }) }
        .filter { it.perms.isNotEmpty() }

    /** 全部危险权限清单（按当前 SDK 过滤）。 */
    fun allPerms(): List<PermDef> = groupsForSdk().flatMap { it.perms }

    /**
     * 通过通道执行 `dumpsys package <pkg>` 并解析 "grantedPermissions:" 段。
     * @return 已授予权限集合；无通道（非 Root/Shizuku）时返回 null。
     */
    fun queryGranted(ctx: Context, pkg: String): Set<String>? {
        val out = Privilege.execShell(ctx, "dumpsys", "package", pkg) ?: return null
        val granted = mutableSetOf<String>()
        var inSection = false
        for (line in out.lineSequence()) {
            val t = line.trim()
            if (t == "grantedPermissions:") {
                inSection = true
                continue
            }
            if (inSection) {
                if (t.isEmpty() || t.contains(':')) break
                granted.add(t)
            }
        }
        return granted
    }

    /** 授予或撤销权限（系统级）。仅 Root / Shizuku 通道可执行；失败返回 false。 */
    fun setGranted(ctx: Context, pkg: String, permission: String, grant: Boolean): Boolean {
        val action = if (grant) "grant" else "revoke"
        // 统一走 execShell（Root/Shizuku 自动选择，参数经 shell 转义）
        val out = Privilege.execShell(ctx, "pm", action, pkg, permission) ?: return false
        // pm grant/revoke 成功时无输出；失败输出错误/异常文本
        val t = out.trim()
        return t.isEmpty() ||
            (!t.contains("error", ignoreCase = true) && !t.startsWith("Exception"))
    }

    /** 生成单条合并的 ADB 命令（所有 grant 用 && 连接，一条跑完即可）。 */
    fun adbCommand(pkg: String, permission: String, grant: Boolean): String {
        val action = if (grant) "grant" else "revoke"
        return "pm $action $pkg $permission"
    }

    /** 把多条 pm 命令合并成一条 adb shell "..." 命令，用户只需复制一次跑一次。 */
    fun adbCommandCombined(pkg: String, permissions: List<String>, grant: Boolean): String {
        val action = if (grant) "grant" else "revoke"
        val inner = permissions.joinToString(" && ") { "pm $action $pkg $it" }
        return "adb shell \"$inner\""
    }

    /** 权限名去前缀后的短名，用于行内展示。 */
    fun shortName(permission: String): String =
        permission.removePrefix("android.permission.")
}

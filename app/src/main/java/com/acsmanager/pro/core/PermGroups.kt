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
        return when (Privilege.bestChannel(ctx)) {
            Privilege.Channel.ROOT -> {
                // pm grant 成功无输出；失败输出错误文本
                val out = Privilege.runShell(arrayOf("su", "-c", "pm $action $pkg $permission"))
                out != null && out.isBlank()
            }
            Privilege.Channel.SHIZUKU -> {
                val out = Privilege.shizukuExec(ctx, "pm", action, pkg, permission)
                out != null && out.isBlank()
            }
            else -> false
        }
    }

    /** 生成 ADB 手动执行命令（用于无 Root/Shizuku 时的降级指引）。 */
    fun adbCommand(pkg: String, permission: String, grant: Boolean): String {
        val action = if (grant) "grant" else "revoke"
        return "adb shell pm $action $pkg $permission"
    }

    /** 权限名去前缀后的短名，用于行内展示。 */
    fun shortName(permission: String): String =
        permission.removePrefix("android.permission.")
}

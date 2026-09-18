package com.acsmanager.pro.dev

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.acsmanager.pro.R
import com.acsmanager.pro.core.Privilege

/**
 * 开发者选项功能映射：不跳转系统开发者选项，直接读写对应 Settings 键值，永久生效
 * （Settings 写入即持久化，重启后仍保留）。
 *
 * 写入通道（自动探测）：
 *  1. 直写 Settings（WRITE_SETTINGS / WRITE_SECURE_SETTINGS 已授予时，如 ADB 授权）；
 *  2. 失败后经 Root / Shizuku 执行 `settings put ...`（覆盖无 WRITE_SECURE_SETTINGS 的机型）；
 *  3. 全部不可用时返回失败并提示授权向导。
 */
object DevOptions {

    private const val TAG = "AcsDevOptions"

    enum class Table { GLOBAL, SYSTEM }

    enum class Type { FLOAT, BOOL }

    data class Option(
        val key: String,
        val table: Table,
        val type: Type,
        val labelRes: Int,
        val descRes: Int,
        val defFloat: Float = 1.0f,
        val defBool: Boolean = false
    )

    data class WriteResult(val ok: Boolean, val channel: Privilege.Channel, val message: String)

    // ---------- 功能映射表 ----------

    val OPTIONS: List<Option> = listOf(
        // 开发者选项总开关（部分机型必须先启用才能让其他修改长期生效）
        Option("development_settings_enabled", Table.GLOBAL, Type.BOOL,
            R.string.dev_switch_enabled, R.string.dev_switch_enabled_desc, defBool = false),
        // 动画三件套（0 = 关闭动画，0.5/1.0/1.5/2.0 常用档位）
        Option("window_animation_scale", Table.GLOBAL, Type.FLOAT,
            R.string.dev_window_anim, R.string.dev_window_anim_desc),
        Option("transition_animation_scale", Table.GLOBAL, Type.FLOAT,
            R.string.dev_transition_anim, R.string.dev_transition_anim_desc),
        Option("animator_duration_scale", Table.GLOBAL, Type.FLOAT,
            R.string.dev_animator_dur, R.string.dev_animator_dur_desc),
        // 常用调试项
        Option("always_finish_activities", Table.GLOBAL, Type.BOOL,
            R.string.dev_always_finish, R.string.dev_always_finish_desc, defBool = false),
        Option("force_gpu_rendering", Table.GLOBAL, Type.BOOL,
            R.string.dev_force_gpu, R.string.dev_force_gpu_desc, defBool = false),
        Option("strict_mode", Table.GLOBAL, Type.BOOL,
            R.string.dev_strict_mode, R.string.dev_strict_mode_desc, defBool = false),
        Option("show_touches", Table.SYSTEM, Type.BOOL,
            R.string.dev_show_touches, R.string.dev_show_touches_desc, defBool = false)
    )

    // ---------- 读取 ----------

    fun readFloat(ctx: Context, o: Option): Float = try {
        when (o.table) {
            Table.GLOBAL -> Settings.Global.getFloat(ctx.contentResolver, o.key)
            Table.SYSTEM -> Settings.System.getFloat(ctx.contentResolver, o.key)
        }
    } catch (t: Throwable) {
        o.defFloat
    }

    fun readBool(ctx: Context, o: Option): Boolean = try {
        when (o.table) {
            Table.GLOBAL -> Settings.Global.getInt(ctx.contentResolver, o.key) != 0
            Table.SYSTEM -> Settings.System.getInt(ctx.contentResolver, o.key) != 0
        }
    } catch (t: Throwable) {
        o.defBool
    }

    // ---------- 写入（直写 → Root/Shizuku 兜底） ----------

    fun writeFloat(ctx: Context, o: Option, value: Float): WriteResult {
        // 直写
        val directOk = try {
            when (o.table) {
                Table.GLOBAL -> Settings.Global.putFloat(ctx.contentResolver, o.key, value)
                Table.SYSTEM -> Settings.System.putFloat(ctx.contentResolver, o.key, value)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "direct float write failed: ${o.key}", t)
            false
        }
        if (directOk && readFloat(ctx, o) == value) {
            return WriteResult(true, Privilege.Channel.APP_GRANTED, "direct")
        }
        // Root/Shizuku 兜底
        return shellWrite(ctx, o, value.toString())
    }

    fun writeBool(ctx: Context, o: Option, value: Boolean): WriteResult {
        val iv = if (value) 1 else 0
        val directOk = try {
            when (o.table) {
                Table.GLOBAL -> Settings.Global.putInt(ctx.contentResolver, o.key, iv)
                Table.SYSTEM -> Settings.System.putInt(ctx.contentResolver, o.key, iv)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "direct bool write failed: ${o.key}", t)
            false
        }
        if (directOk && readBool(ctx, o) == value) {
            return WriteResult(true, Privilege.Channel.APP_GRANTED, "direct")
        }
        return shellWrite(ctx, o, iv.toString())
    }

    private fun shellWrite(ctx: Context, o: Option, value: String): WriteResult {
        val table = if (o.table == Table.GLOBAL) "global" else "system"
        // Root / Shizuku 统一走 execShell（参数经 shell 转义；value 为数字，安全）
        val ch = Privilege.bestChannel(ctx)
        if (ch != Privilege.Channel.ROOT && ch != Privilege.Channel.SHIZUKU) {
            return WriteResult(false, ch, "no_privilege")
        }
        val out = Privilege.execShell(ctx, "settings", "put", table, o.key, value)
        val ok = out != null && verify(ctx, o, value)
        return WriteResult(ok, ch, if (ok) ch.name.lowercase() else (out ?: "$ch failed"))
    }

    private fun verify(ctx: Context, o: Option, value: String): Boolean = try {
        when (o.type) {
            Type.FLOAT -> readFloat(ctx, o) == value.toFloat()
            Type.BOOL -> readBool(ctx, o) == (value == "1")
        }
    } catch (t: Throwable) {
        false
    }

    // ---------- 一键模式 ----------

    enum class Preset(val labelRes: Int) {
        FAST(R.string.dev_preset_fast),      // 极速：动画 0.5，其余关闭
        EXTREME(R.string.dev_preset_extreme), // 极致：动画全关 + 不保留活动（慎用）
        DEFAULT(R.string.dev_preset_default)  // 恢复默认
    }

    fun applyPreset(ctx: Context, preset: Preset): Map<String, WriteResult> {
        val results = mutableMapOf<String, WriteResult>()
        for (o in OPTIONS) {
            val isFloat = o.type == Type.FLOAT
            when (preset) {
                Preset.FAST -> {
                    if (isFloat) {
                        val v: Float = when (o.key) {
                            "window_animation_scale", "transition_animation_scale",
                            "animator_duration_scale" -> 0.5f
                            else -> o.defFloat
                        }
                        results[o.key] = writeFloat(ctx, o, v)
                    } else {
                        val v: Boolean = when (o.key) {
                            "development_settings_enabled" -> true
                            else -> o.defBool
                        }
                        results[o.key] = writeBool(ctx, o, v)
                    }
                }
                Preset.EXTREME -> {
                    if (isFloat) {
                        val v: Float = when (o.key) {
                            "window_animation_scale", "transition_animation_scale",
                            "animator_duration_scale" -> 0f
                            else -> o.defFloat
                        }
                        results[o.key] = writeFloat(ctx, o, v)
                    } else {
                        val v: Boolean = when (o.key) {
                            "always_finish_activities" -> true
                            "development_settings_enabled" -> true
                            else -> o.defBool
                        }
                        results[o.key] = writeBool(ctx, o, v)
                    }
                }
                Preset.DEFAULT -> {
                    if (isFloat) {
                        val v: Float = when (o.key) {
                            "window_animation_scale", "transition_animation_scale",
                            "animator_duration_scale" -> 1f
                            else -> o.defFloat
                        }
                        results[o.key] = writeFloat(ctx, o, v)
                    } else {
                        results[o.key] = writeBool(ctx, o, false)
                    }
                }
            }
        }
        return results
    }
}

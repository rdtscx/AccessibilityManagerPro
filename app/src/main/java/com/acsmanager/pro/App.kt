package com.acsmanager.pro

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.acsmanager.pro.util.Prefs
import java.util.Locale

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        applyThemeMode()
        initShizukuListeners()
    }

    /**
     * 注册 Shizuku Binder 连接/断开监听器（V3 客户端必需初始化步骤）。
     *
     * 修复要点：
     *  - Shizuku 服务端通过客户端是否正确响应 Binder 连接事件来判断 V3 兼容性；
     *    缺少 OnBinderReceivedListener 会导致服务端将本应用标记为"未初始化客户端"，
     *    在应用管理中开关授权不生效，且应用启动后从管理列表消失。
     *  - 监听器在 Application.onCreate 中全局注册一次，避免重复注册。
     *  - API < 24 时 Shizuku 不可用，跳过注册。
     */
    private fun initShizukuListeners() {
        if (Build.VERSION.SDK_INT < 24) return
        try {
            rikka.shizuku.Shizuku.addBinderReceivedListener(binderReceivedListener)
            rikka.shizuku.Shizuku.addBinderDeadListener(binderDeadListener)
            shizukuListenersRegistered = true
            Log.i(TAG, "Shizuku listeners registered")
        } catch (t: Throwable) {
            Log.w(TAG, "register Shizuku listeners failed", t)
        }
    }

    private val binderReceivedListener = rikka.shizuku.Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received")
        shizukuBinderAlive = true
    }

    private val binderDeadListener = rikka.shizuku.Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead")
        shizukuBinderAlive = false
        // 联动清理 Privilege 中缓存的 UserService 绑定，避免复用失效 binder
        try {
            com.acsmanager.pro.core.Privilege.onShizukuBinderDead()
        } catch (t: Throwable) {
            Log.w(TAG, "clear privilege state failed", t)
        }
    }

    /**
     * 语言切换：通过 createConfigurationContext 在上下文创建时注入语言配置，
     * 替代已废弃的 resources.updateConfiguration（API 25+ 不推荐）。
     */
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(wrapLocale(base))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(wrapLocaleConfig(newConfig))
    }

    private fun wrapLocale(base: Context): Context {
        val lang = Prefs.language(base)
        if (lang != "zh" && lang != "en") return base
        return try {
            val locale = Locale(lang)
            Locale.setDefault(locale)
            val config = Configuration(base.resources.configuration)
            config.setLocale(locale)
            base.createConfigurationContext(config)
        } catch (t: Throwable) {
            Log.w(TAG, "wrapLocale failed", t)
            base
        }
    }

    private fun wrapLocaleConfig(config: Configuration): Configuration {
        val lang = Prefs.language(this)
        if (lang != "zh" && lang != "en") return config
        return try {
            val locale = Locale(lang)
            Locale.setDefault(locale)
            Configuration(config).apply { setLocale(locale) }
        } catch (t: Throwable) {
            config
        }
    }

    private fun applyThemeMode() {
        when (Prefs.themeMode(this)) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    companion object {
        private const val TAG = "AcsProApp"

        /** Shizuku Binder 当前是否存活（由监听器维护），供 Privilege 模块快速判断。 */
        @Volatile
        var shizukuBinderAlive = false
            private set

        /** 监听器是否已注册（幂等保护）。 */
        @Volatile
        var shizukuListenersRegistered = false
            private set
    }
}

package com.acsmanager.pro

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.acsmanager.pro.util.Prefs
import java.util.Locale

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        applyThemeMode()
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
    }
}

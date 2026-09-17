package com.acsmanager.pro

import android.app.Application
import android.content.res.Configuration
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import com.acsmanager.pro.util.Prefs
import java.util.Locale

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        applyThemeMode()
        applyLocale()
    }

    private fun applyThemeMode() {
        when (Prefs.themeMode(this)) {
            "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        }
    }

    private fun applyLocale() {
        val lang = Prefs.language(this)
        if (lang == "zh" || lang == "en") {
            try {
                Locale.setDefault(Locale(lang))
                val config = Configuration(resources.configuration)
                config.setLocale(Locale(lang))
                resources.updateConfiguration(config, resources.displayMetrics)
            } catch (t: Throwable) {
                Log.w(TAG, "applyLocale failed", t)
            }
        }
    }

    companion object {
        private const val TAG = "AcsProApp"
    }
}

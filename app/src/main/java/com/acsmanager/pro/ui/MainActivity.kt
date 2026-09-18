package com.acsmanager.pro.ui

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import com.acsmanager.pro.R
import com.acsmanager.pro.core.AccessServiceRepo
import com.acsmanager.pro.core.Privilege
import com.acsmanager.pro.core.ServiceStateController
import com.acsmanager.pro.databinding.ActivityMainBinding
import com.acsmanager.pro.selfguard.SelfAccessService
import com.acsmanager.pro.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        applyStatusBar()

        if (savedInstanceState == null) {
            showFragment(HomeFragment(), addToBack = false)
        }

        binding.bottomNav.setOnItemSelectedListener { item ->
            val fragment: Fragment = when (item.itemId) {
                R.id.nav_home -> HomeFragment()
                R.id.nav_keepalive -> KeepAliveFragment()
                R.id.nav_notif -> NotifFragment()
                R.id.nav_dev -> DevFragment()
                R.id.nav_exp -> ExpFragment()
                else -> return@setOnItemSelectedListener false
            }
            showFragment(fragment, addToBack = false)
            true
        }

        selfProtectOnStartup()
        applyHideFromRecents()

        // 首次启动：自动弹出授权引导页（用户可按返回键跳过）
        if (!Prefs.firstGuideDone(this)) {
            startActivity(Intent(this, GuideActivity::class.java))
        }

        // 使用 OnBackPressedDispatcher 替代已废弃的 onBackPressed（API 33+）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * 启动自保活：应用自身启动时，若拥有 Root / Shizuku / ADB（WRITE_SECURE_SETTINGS）能力，
     * 检测本应用无障碍是否被关闭；被关闭则经授权通道无感写回拉起（不弹窗、不跳转）。
     */
    private fun selfProtectOnStartup() {
        scope.launch {
            val ctx = applicationContext
            withContext(Dispatchers.IO) {
                val channel = Privilege.bestChannel(ctx)
                if (channel == Privilege.Channel.NONE) return@withContext
                val comp = ComponentName(ctx, SelfAccessService::class.java)
                if (comp.flattenToString() in AccessServiceRepo.enabledStrings(ctx)) return@withContext
                ServiceStateController.setEnabled(ctx, comp, true, log = false)
            }
        }
    }

    /**
     * 应用"从最近应用列表隐藏"设置。
     * 通过 ActivityManager.AppTask.setExcludeFromRecents 动态控制当前任务是否出现在最近任务列表中。
     * 开启后用户无法从最近任务划走本应用，避免后台服务被杀。
     */
    fun applyHideFromRecents() {
        try {
            val hide = Prefs.hideFromRecents(this)
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (task in am.appTasks) {
                task.setExcludeFromRecents(hide)
            }
            Log.i("MainActivity", "applyHideFromRecents: $hide")
        } catch (t: Throwable) {
            Log.w("MainActivity", "applyHideFromRecents failed", t)
        }
    }

    /**
     * 从首页快捷入口打开子页面（服务管理 / 事件监控 / 通用设置），可返回首页。
     * 注意：不得重置 bottomNav.selectedItemId——若当前恰为 nav_home，设置相同值会触发
     * 选中监听并把刚打开的 fragment 立即替换回首页（用户感知为"点不进去/闪退"）。
     */
    fun openFragment(fragment: Fragment) {
        showFragment(fragment, addToBack = true)
    }

    private fun showFragment(fragment: Fragment, addToBack: Boolean) {
        val t = supportFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
        if (addToBack) t.addToBackStack(null)
        t.commit()
    }

    /**
     * 状态栏颜色跟随主题：浅色主题用浅色背景+深色图标，深色主题用深色背景+浅色图标。
     * 避免状态栏与主界面颜色突兀。
     */
    private fun applyStatusBar() {
        val isDark = when (Prefs.themeMode(this)) {
            "light" -> false
            "dark" -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        window.statusBarColor = if (isDark) {
            getColor(R.color.status_bar_dark)
        } else {
            getColor(R.color.status_bar_light)
        }
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isDark
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyStatusBar()
    }

    override fun onResume() {
        super.onResume()
        applyStatusBar()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

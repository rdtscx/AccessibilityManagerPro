package com.acsmanager.pro.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
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

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

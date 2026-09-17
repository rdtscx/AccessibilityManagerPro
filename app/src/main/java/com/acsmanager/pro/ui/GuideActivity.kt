package com.acsmanager.pro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.acsmanager.pro.R
import com.acsmanager.pro.core.Privilege
import com.acsmanager.pro.databinding.ActivityGuideBinding
import com.acsmanager.pro.util.Prefs
import rikka.shizuku.Shizuku

/**
 * 授权向导：Root / Shizuku / ADB 三种通道的状态检测与引导。
 * 首次启动时由 MainActivity 自动弹出；实时检测授权状态，任一通道就绪即自动返回首页；
 * 未授权时按返回键或"进入首页"按钮即可回到首页。
 */
class GuideActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGuideBinding
    private val requestCode = 1024
    private val handler = Handler(Looper.getMainLooper())

    /** 每 2 秒检测一次授权状态，检测到就绪则自动回首页。 */
    private val detectRunnable = object : Runnable {
        override fun run() {
            detect()
            if (isAnyChannelReady()) {
                Prefs.setFirstGuideDone(this@GuideActivity, true)
                Toast.makeText(this@GuideActivity, R.string.guide_auto_enter, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            handler.postDelayed(this, 2000L)
        }
    }

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == requestCode) {
            runOnUiThread { detect() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGuideBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyStatusBar()

        binding.toolbar.setNavigationOnClickListener {
            Prefs.setFirstGuideDone(this, true)
            finish()
        }

        binding.adbCommand.text =
            "adb shell pm grant ${packageName} android.permission.WRITE_SECURE_SETTINGS"

        binding.btnShizukuInstall.setOnClickListener {
            val url = if (Privilege.hasShizukuInstalled(this)) {
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${Privilege.SHIZUKU_PKG}"))
            } else {
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("market://details?id=${Privilege.SHIZUKU_PKG}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                startActivity(url)
            } catch (t: Throwable) {
                Toast.makeText(this, R.string.result_fail, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnShizukuStart.setOnClickListener {
            try {
                startActivity(
                    packageManager.getLaunchIntentForPackage(Privilege.SHIZUKU_PKG)
                        ?: throw IllegalStateException("not installed")
                )
            } catch (t: Throwable) {
                Toast.makeText(this, R.string.result_fail, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnShizukuGrant.setOnClickListener {
            try {
                if (!Shizuku.pingBinder()) {
                    Toast.makeText(this, R.string.guide_shizuku_start, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, R.string.guide_shizuku_ok, Toast.LENGTH_SHORT).show()
                } else {
                    Shizuku.requestPermission(requestCode)
                }
            } catch (t: Throwable) {
                Toast.makeText(this, R.string.result_fail, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("adb", binding.adbCommand.text))
            Toast.makeText(this, R.string.guide_adb_copy_done, Toast.LENGTH_SHORT).show()
        }

        binding.btnDetect.setOnClickListener { detect() }

        binding.btnEnterHome.setOnClickListener {
            Prefs.setFirstGuideDone(this, true)
            finish()
        }

        detect()
    }

    override fun onResume() {
        super.onResume()
        applyStatusBar()
        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener)
        } catch (t: Throwable) {
        }
        detect()
        handler.removeCallbacks(detectRunnable)
        handler.postDelayed(detectRunnable, 2000L)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(detectRunnable)
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuListener)
        } catch (t: Throwable) {
        }
    }

    /** 状态栏颜色跟随主题。 */
    private fun applyStatusBar() {
        val isDark = when (Prefs.themeMode(this)) {
            "light" -> false
            "dark" -> true
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        window.statusBarColor = getColor(if (isDark) R.color.status_bar_dark else R.color.status_bar_light)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = !isDark
    }

    /** 任一通道就绪即视为授权完成。 */
    private fun isAnyChannelReady(): Boolean =
        Privilege.hasRoot() || Privilege.shizukuReady() || Privilege.hasAppGranted(this)

    private fun detect() {
        val rootOk = Privilege.hasRoot()
        val shizukuOk = Privilege.shizukuReady()
        val adbOk = Privilege.hasAppGranted(this)

        setStatus(binding.rootStatus, rootOk, R.string.guide_root_ok, R.string.guide_root_fail)

        if (shizukuOk) {
            setStatus(binding.shizukuStatus, true, R.string.guide_shizuku_ok, R.string.guide_shizuku_ok)
        } else {
            val hintRes = when {
                Privilege.shizukuConnected() && !Privilege.shizukuGranted() -> R.string.guide_shizuku_grant
                else -> R.string.guide_shizuku_start
            }
            setStatus(binding.shizukuStatus, false, hintRes, hintRes)
        }

        setStatus(binding.adbStatus, adbOk, R.string.guide_adb_ok, R.string.guide_check)

        // 更新整体状态芯片
        val ready = rootOk || shizukuOk || adbOk
        binding.chipOverall.text = getString(if (ready) R.string.guide_ready else R.string.guide_not_ready)
        binding.chipOverall.setTextColor(
            ContextCompat.getColor(this, if (ready) R.color.status_ok else R.color.status_err)
        )
    }

    private fun setStatus(view: android.widget.TextView, ok: Boolean, okRes: Int, failRes: Int) {
        view.text = getString(if (ok) okRes else failRes)
        view.setTextColor(
            ContextCompat.getColor(
                this,
                if (ok) R.color.status_ok else R.color.status_err
            )
        )
    }
}

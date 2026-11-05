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
import com.acsmanager.pro.core.AdbSetup
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

    /**
     * 每 2 秒检测一次授权状态，检测到就绪则自动回首页。
     *
     * 关键修复（7.0.1）：原实现在主线程同步调用 detect()/isAnyChannelReady()，
     * 二者都会走到 Privilege.hasRoot() → fork su 进程。Root 设备上 su 会弹授权框，
     * 主线程被阻塞数秒甚至数十秒 → ANR → 系统直接杀进程（用户感知为"打开就闪退"）。
     * 现改为后台探测、主线程只刷新 UI。
     */
    private val detectRunnable = object : Runnable {
        override fun run() {
            probe(closeIfReady = true)
            handler.postDelayed(this, 2000L)
        }
    }

    /** 后台线程探测三种通道，结果回主线程；就绪且要求时自动关闭本页。 */
    private fun probe(closeIfReady: Boolean) {
        val ctx = applicationContext
        Thread {
            val rootOk = try { Privilege.hasRoot() } catch (t: Throwable) { false }
            val shizukuOk = try { Privilege.shizukuReady() } catch (t: Throwable) { false }
            val adbOk = try { Privilege.hasAppGranted(ctx) } catch (t: Throwable) { false }
            val shizukuConnected = try { Privilege.shizukuConnected() } catch (t: Throwable) { false }
            val shizukuGranted = try { Privilege.shizukuGranted() } catch (t: Throwable) { false }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                applyStatus(rootOk, shizukuOk, adbOk, shizukuConnected, shizukuGranted)
                if (closeIfReady && (rootOk || shizukuOk || adbOk)) {
                    Prefs.setFirstGuideDone(this, true)
                    Toast.makeText(this, R.string.guide_auto_enter, Toast.LENGTH_SHORT).show()
                    handler.removeCallbacks(detectRunnable)
                    finish()
                }
            }
        }.apply {
            isDaemon = true
            name = "acs-guide-probe"
        }.start()
    }

    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == requestCode) {
            runOnUiThread { probe(closeIfReady = true) }
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

        // 首次授权命令：三行独立命令，统一由 AdbSetup 提供，
        // 保证展示、复制、应用内执行（Root / Shizuku）三处内容完全一致。
        binding.adbCommand.text = AdbSetup.TEXT

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
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return@setOnClickListener
            cm.setPrimaryClip(ClipData.newPlainText("adb", AdbSetup.TEXT))
            Toast.makeText(this, R.string.guide_adb_copy_done, Toast.LENGTH_SHORT).show()
        }

        binding.btnRun.setOnClickListener { runAdbSetup() }

        binding.btnDetect.setOnClickListener { probe(closeIfReady = false) }

        binding.btnEnterHome.setOnClickListener {
            Prefs.setFirstGuideDone(this, true)
            finish()
        }

        probe(closeIfReady = false)
    }

    /**
     * 应用内执行引导页那三行命令（Root / Shizuku）。
     *
     * 必须离开主线程：su 授权框和 Shizuku 往返都可能阻塞数秒。三行按顺序执行，
     * 失败时把该行输出回显出来，避免“点了没反应”。
     */
    private fun runAdbSetup() {
        val ctx = applicationContext
        binding.btnRun.isEnabled = false
        Thread {
            val results = try {
                if (Privilege.shellChannel(ctx) == Privilege.Channel.NONE) emptyList()
                else AdbSetup.run(ctx)
            } catch (t: Throwable) {
                emptyList()
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                binding.btnRun.isEnabled = true
                val failed = results.filter { !it.ok }
                when {
                    results.isEmpty() -> Toast.makeText(
                        this, R.string.guide_adb_run_no_channel, Toast.LENGTH_LONG
                    ).show()
                    failed.isEmpty() -> {
                        Toast.makeText(this, R.string.guide_adb_run_ok, Toast.LENGTH_SHORT).show()
                        probe(closeIfReady = false)
                    }
                    else -> Toast.makeText(
                        this,
                        getString(
                            R.string.guide_adb_run_fail,
                            failed.first().output.ifBlank { failed.first().line }
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.apply {
            isDaemon = true
            name = "acs-adb-setup"
        }.start()
    }

    override fun onResume() {
        super.onResume()
        applyStatusBar()
        try {
            Shizuku.addRequestPermissionResultListener(shizukuListener)
        } catch (t: Throwable) {
        }
        // 后台探测（就绪时自动完成引导并返回首页），主线程不做任何 su 探测
        handler.removeCallbacks(detectRunnable)
        probe(closeIfReady = true)
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

    /** 把后台探测结果刷新到界面（只能在主线程调用）。 */
    private fun applyStatus(
        rootOk: Boolean,
        shizukuOk: Boolean,
        adbOk: Boolean,
        shizukuConnected: Boolean,
        shizukuGranted: Boolean
    ) {
        setStatus(binding.rootStatus, rootOk, R.string.guide_root_ok, R.string.guide_root_fail)

        if (shizukuOk) {
            setStatus(binding.shizukuStatus, true, R.string.guide_shizuku_ok, R.string.guide_shizuku_ok)
        } else {
            val hintRes = when {
                shizukuConnected && !shizukuGranted -> R.string.guide_shizuku_grant
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

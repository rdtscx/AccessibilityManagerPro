package com.acsmanager.pro.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.acsmanager.pro.R
import com.acsmanager.pro.core.AccessServiceRepo
import com.acsmanager.pro.core.AccessibilitySettingsObserver
import com.acsmanager.pro.core.Privilege
import com.acsmanager.pro.databinding.FragmentHomeBinding
import com.acsmanager.pro.selfguard.SelfAccessService
import com.acsmanager.pro.selfguard.SelfGuardService
import com.acsmanager.pro.util.Prefs
import com.acsmanager.pro.watchdog.WatchdogService

/**
 * 首页：无障碍自监控（ContentObserver 低耗电）、服务状态、看门狗、授权通道、今日统计与快捷入口。
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** 监听 Settings.Secure 变化：后台拉起服务后自动刷新首页状态。 */
    private var settingsObserver: AccessibilitySettingsObserver? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settingsObserver = AccessibilitySettingsObserver(requireContext()) {
            refresh()
        }

        binding.btnEnableSelfAccess.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), "打开设置失败", Toast.LENGTH_SHORT).show()
            }
        }

        binding.switchSelfGuard.setOnCheckedChangeListener { _, checked ->
            val ctx = requireContext()
            Prefs.setSelfGuardEnabled(ctx, checked)
            if (checked) {
                // 用户手动开启：清除"禁用自动开启"标记，恢复通道联动能力
                Prefs.setAutoSelfGuardDisabledByUser(ctx, false)
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                SelfGuardService.start(ctx)
                Toast.makeText(ctx, R.string.self_guard_enabled_toast, Toast.LENGTH_SHORT).show()
            } else {
                // 用户手动关闭：记录豁免标记，此后不再被授权通道联动自动开启
                Prefs.setAutoSelfGuardDisabledByUser(ctx, true)
                SelfGuardService.stop(ctx)
            }
        }

        binding.sliderGuardDelay.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            Prefs.setSelfGuardDelayMs(requireContext(), (value * 1000).toLong())
            updateGuardDelayLabel()
        }

        binding.switchWatchdog.setOnCheckedChangeListener { _, checked ->
            val ctx = requireContext()
            Prefs.setWatchdogEnabled(ctx, checked)
            if (checked) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                WatchdogService.start(ctx)
            } else {
                WatchdogService.stop(ctx)
            }
        }

        binding.btnGuide.setOnClickListener {
            try {
                startActivity(Intent(requireContext(), GuideActivity::class.java))
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), "打开引导页失败", Toast.LENGTH_SHORT).show()
            }
        }

        // Shizuku 状态细分 + 一键授权/启动（修复"Shizuku 不生效"：授权入口直达，无需跳转向导页）
        binding.btnShizukuGrant.setOnClickListener { handleShizukuAction() }

        // 应用无障碍权限总控（开关 + 锁定自动拉起）
        binding.btnAccessPermEntry.setOnClickListener {
            try {
                startActivity(Intent(requireContext(), AccessibilityPermActivity::class.java))
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), "打开失败", Toast.LENGTH_SHORT).show()
            }
        }

        // 权限管理器（类似权限狗，系统级权限修改）
        binding.btnPermMgrEntry.setOnClickListener {
            try {
                startActivity(Intent(requireContext(), PermissionManagerActivity::class.java))
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), "打开失败", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnServicesEntry.setOnClickListener {
            (activity as? MainActivity)?.openFragment(ServicesFragment())
        }
        binding.btnMonitorEntry.setOnClickListener {
            (activity as? MainActivity)?.openFragment(MonitorFragment())
        }
        binding.btnSettingsEntry.setOnClickListener {
            (activity as? MainActivity)?.openFragment(SettingsFragment())
        }

        // 仪表盘左下角"模式：xxx"：点击直接弹出模式选择，可手动切换/恢复自动
        binding.tvHeartbeatMode.setOnClickListener { showHeartbeatModePicker() }

        // V5.0：一键优化（仅无障碍开启时可点击）
        binding.btnOneClickOptimize.setOnClickListener {
            try {
                performOneClickOptimize()
            } catch (t: Throwable) {
                android.util.Log.e("HomeFragment", "一键优化失败", t)
                Toast.makeText(
                    requireContext(),
                    "优化出错：${t.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * 一键优化：
     * 1. 检查无障碍服务
     * 2. 优化本软件内部设置
     * 3. Toast 提示结果
     */
    private fun performOneClickOptimize() {
        try {
            val ctx = requireContext()

            // 检查无障碍服务
            if (!selfAccessEnabled(ctx)) {
                Toast.makeText(ctx, "请先开启无障碍服务", Toast.LENGTH_LONG).show()
                startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                return
            }

            // 优化内部设置
            val actions = com.acsmanager.pro.health.HealthDiagnosis.autoOptimize(ctx)

            // 检查电池优化状态
            val batteryOk = try {
                com.acsmanager.pro.compat.RomCompatibilityHelper
                    .isIgnoringBatteryOptimizations(ctx)
            } catch (_: Throwable) { true }

            // 纯 Toast 提示，不用 Dialog 避免 ROM 主题不匹配闪退
            val msg = buildString {
                if (actions.isNotEmpty()) {
                    append("已开启：${actions.joinToString("、")}")
                } else {
                    append("内部设置已是最佳")
                }
                if (!batteryOk) {
                    append("\n电池优化建议设为不优化")
                }
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

            try { refresh() } catch (_: Throwable) {}
        } catch (t: Throwable) {
            android.util.Log.e("HomeFragment", "一键优化失败", t)
            try {
                Toast.makeText(requireContext(), "优化出错", Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
        }
    }

    override fun onStart() {
        super.onStart()
        settingsObserver?.register()
    }

    override fun onStop() {
        super.onStop()
        settingsObserver?.unregister()
    }

    override fun onResume() {
        super.onResume()
        requireActivity().title = getString(R.string.app_name)
        try {
            rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuListener)
        } catch (t: Throwable) {
        }
        refresh()
    }

    override fun onPause() {
        super.onPause()
        try {
            rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuListener)
        } catch (t: Throwable) {
        }
    }

    private fun refresh() {
        val ctx = requireContext()

        // V5.2.2：授权通道联动——检测到任一授权通道可用且自监控未开启时自动开启。
        // 放在刷新最前，保证用户从系统授权页返回首页后开关状态即时同步为"已开启"。
        try {
            SelfGuardService.enableIfChannelReady(ctx)
        } catch (t: Throwable) {
            android.util.Log.w("HomeFragment", "auto enable self guard failed", t)
        }

        // 本应用无障碍服务状态
        val enabled = selfAccessEnabled(ctx)
        binding.selfAccessStatus.text = getString(
            if (enabled) R.string.self_access_on else R.string.self_access_off
        )
        binding.selfAccessStatus.setTextColor(
            ContextCompat.getColor(ctx, if (enabled) R.color.status_ok else R.color.status_err)
        )
        binding.selfAccessDot.setBackgroundColor(
            ContextCompat.getColor(ctx, if (enabled) R.color.status_ok else R.color.status_err)
        )
        binding.btnEnableSelfAccess.isEnabled = !enabled
        binding.btnEnableSelfAccess.text = getString(
            if (enabled) R.string.self_access_on_short else R.string.home_enable
        )

        // V5.0：一键优化按钮仅在无障碍开启时可点击
        binding.btnOneClickOptimize.isEnabled = enabled
        binding.btnOneClickOptimize.alpha = if (enabled) 1.0f else 0.5f

        // 自监控
        // 关键：先解绑再设值，防止程序回写触发监听导致意外启动/Toast（与 v1.3 开关循环同源问题）
        binding.switchSelfGuard.setOnCheckedChangeListener(null)
        binding.switchSelfGuard.isChecked = Prefs.isSelfGuardEnabled(ctx)
        binding.switchSelfGuard.setOnCheckedChangeListener { _, checked ->
            val c = requireContext()
            Prefs.setSelfGuardEnabled(c, checked)
            if (checked) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                SelfGuardService.start(c)
                Toast.makeText(c, R.string.self_guard_enabled_toast, Toast.LENGTH_SHORT).show()
            } else {
                SelfGuardService.stop(c)
            }
        }
        updateGuardDelayLabel()
        binding.sliderGuardDelay.value =
            (Prefs.selfGuardDelayMs(ctx) / 1000f).coerceIn(0.5f, 10f)

        // 看门狗
        binding.switchWatchdog.setOnCheckedChangeListener(null)
        binding.switchWatchdog.isChecked = Prefs.isWatchdogEnabled(ctx)
        binding.switchWatchdog.setOnCheckedChangeListener { _, checked ->
            val c = requireContext()
            Prefs.setWatchdogEnabled(c, checked)
            if (checked) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(c, android.Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                WatchdogService.start(c)
            } else {
                WatchdogService.stop(c)
            }
        }

        // 授权通道
        binding.tvChannel.text = getString(Privilege.bestChannel(ctx).labelRes)
        updateShizukuUi(ctx)

        // 统计
        binding.tvStatProtected.text = Prefs.keepAliveApps(ctx).size.toString()
        binding.tvStatRelaunch.text = Prefs.relaunchCount(ctx).toString()
        binding.tvStatBlock.text = Prefs.notifBlockCount(ctx).toString()

        // V3.3：健康度仪表盘
        updateHealthDashboard(ctx)
    }

    /**
     * V3.3：更新健康度仪表盘数据。
     * 展示：健康评分、存活时长、今日被杀、拉起成功率、心跳模式、预估耗电。
     */
    private fun updateHealthDashboard(ctx: Context) {
        val health = com.acsmanager.pro.health.HealthDashboardManager

        // 健康等级
        val level = health.getHealthLevel(ctx)
        binding.tvHealthLevel.text = level

        // 存活时长
        binding.tvHealthUptime.text = health.getFormattedUptime()

        // 今日被杀次数
        binding.tvHealthKilled.text = health.getTodayKilledCount(ctx).toString()

        // 拉起成功率
        val successRate = health.getRestorationSuccessRate(ctx)
        binding.tvHealthSuccess.text = "${(successRate * 100).toInt()}%"

        // 当前心跳模式
        val mode = com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.getCurrentMode()
        val modeText = when (mode) {
            com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.HeartbeatMode.PERFORMANCE ->
                getString(R.string.health_mode_performance)
            com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.HeartbeatMode.NORMAL ->
                getString(R.string.health_mode_normal)
            com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.HeartbeatMode.LOW_POWER ->
                getString(R.string.health_mode_low_power)
            com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.HeartbeatMode.SUSPENDED ->
                getString(R.string.health_mode_suspended)
        }
        // 手动模式追加标记，让用户一眼看出这是锁定值而非自动计算结果
        val manualSuffix = if (com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.isManualMode()) {
            getString(R.string.health_mode_manual_suffix)
        } else {
            ""
        }
        binding.tvHeartbeatMode.text = getString(R.string.health_heartbeat_mode, "$modeText$manualSuffix")

        // 预估耗电量
        val drain = health.getEstimatedBatteryDrain(ctx)
        binding.tvBatteryDrain.text = getString(R.string.health_battery_drain, drain)
    }

    private fun updateGuardDelayLabel() {
        binding.tvGuardDelay.text = getString(
            R.string.self_guard_delay_value,
            Prefs.selfGuardDelayMs(requireContext()) / 1000f
        )
    }

    /**
     * 弹出心跳模式选择对话框。
     * 选项：自动（跟随设备状态）/ 高性能 / 标准 / 低功耗 / 已暂停。
     * 选择后立即写入并刷新仪表盘。
     */
    private fun showHeartbeatModePicker() {
        val ctx = requireContext()
        val manager = com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager
        val currentManual = manager.getManualMode()

        // 选项列表：[自动, 高性能, 标准, 低功耗, 已暂停]
        val labels = arrayOf(
            getString(R.string.health_mode_auto),
            getString(R.string.health_mode_performance),
            getString(R.string.health_mode_normal),
            getString(R.string.health_mode_low_power),
            getString(R.string.health_mode_suspended)
        )
        // 默认选中项：自动 = 0；否则按当前手动模式定位
        val checkedItem = when (currentManual) {
            null -> 0
            com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.HeartbeatMode.PERFORMANCE -> 1
            com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.HeartbeatMode.NORMAL -> 2
            com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.HeartbeatMode.LOW_POWER -> 3
            com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.HeartbeatMode.SUSPENDED -> 4
        }

        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.health_mode_picker_title)
            .setSingleChoiceItems(labels, checkedItem) { dialog, which ->
                val newMode = when (which) {
                    0 -> null   // 恢复自动
                    1 -> com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.HeartbeatMode.PERFORMANCE
                    2 -> com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.HeartbeatMode.NORMAL
                    3 -> com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.HeartbeatMode.LOW_POWER
                    else -> com.acsmanager.pro.keepalive.AdaptiveHeartbeatManager.HeartbeatMode.SUSPENDED
                }
                manager.setManualMode(ctx, newMode)
                refresh()
                dialog.dismiss()
            }
            .show()
    }

    /** Shizuku 状态细分展示：未安装/未启动/已连接未授权/已授权，并显示对应操作按钮。 */
    private fun updateShizukuUi(ctx: Context) {
        val status = Privilege.shizukuStatus(ctx)
        val (text, showBtn, btnText) = when (status) {
            Privilege.ShizukuStatus.READY ->
                Triple(getString(R.string.status_shizuku_ready), false, "")
            Privilege.ShizukuStatus.NO_PERMISSION ->
                Triple(getString(R.string.status_shizuku_no_perm), true, getString(R.string.status_shizuku_grant))
            Privilege.ShizukuStatus.NOT_CONNECTED ->
                Triple(getString(R.string.status_shizuku_not_connected), true, getString(R.string.status_shizuku_start))
            Privilege.ShizukuStatus.NOT_INSTALLED ->
                Triple(getString(R.string.status_shizuku_not_installed), false, "")
        }
        binding.tvShizukuStatus.text = text
        binding.btnShizukuGrant.visibility = if (showBtn) View.VISIBLE else View.GONE
        if (showBtn) binding.btnShizukuGrant.text = btnText
    }

    private fun handleShizukuAction() {
        val ctx = requireContext()
        when (Privilege.shizukuStatus(ctx)) {
            Privilege.ShizukuStatus.NO_PERMISSION -> {
                try {
                    if (Build.VERSION.SDK_INT < 24) return
                    if (rikka.shizuku.Shizuku.checkSelfPermission() !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        rikka.shizuku.Shizuku.requestPermission(shizukuReqCode)
                    }
                } catch (t: Throwable) {
                    Toast.makeText(ctx, R.string.result_fail, Toast.LENGTH_SHORT).show()
                }
            }
            Privilege.ShizukuStatus.NOT_CONNECTED -> {
                try {
                    val intent = ctx.packageManager
                        .getLaunchIntentForPackage(Privilege.SHIZUKU_PKG)
                    if (intent != null) startActivity(intent) else {
                        Toast.makeText(ctx, R.string.result_fail, Toast.LENGTH_SHORT).show()
                    }
                } catch (t: Throwable) {
                    Toast.makeText(ctx, R.string.result_fail, Toast.LENGTH_SHORT).show()
                }
            }
            else -> Unit
        }
    }

    private val shizukuReqCode = 0x5A17

    private val shizukuListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { code, _ ->
        if (code == shizukuReqCode) {
            // 授权结果回来后在主线程刷新状态
            view?.post { refresh() }
        }
    }

    private fun selfAccessEnabled(ctx: Context): Boolean {
        val comp = ComponentName(ctx, SelfAccessService::class.java).flattenToString()
        return AccessServiceRepo.enabledStrings(ctx).contains(comp)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

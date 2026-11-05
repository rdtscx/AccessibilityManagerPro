package com.acsmanager.pro.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.acsmanager.pro.R
import com.acsmanager.pro.core.AccessServiceItem
import com.acsmanager.pro.core.AccessServiceRepo
import com.acsmanager.pro.core.AccessibilitySettingsObserver
import com.acsmanager.pro.core.Privilege
import com.acsmanager.pro.core.ServiceStateController
import com.acsmanager.pro.databinding.ActivityAccessPermBinding
import com.acsmanager.pro.databinding.SheetAccessPermBinding
import com.acsmanager.pro.ui.adapter.AccessPermAdapter
import com.acsmanager.pro.util.Prefs
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用无障碍权限总控（图2 风格）：
 * 卡片列表 = 图标 + 应用名 + 服务描述 + 锁定按钮 + 开关；
 * 点击卡片弹出勾选菜单（锁定保活 / 开机启动保活 / Toast 提示 / 延迟保活 / 只显示用户应用 / 自定义通知文字）。
 *
 * 开关循环修复：
 * 1) adapter.bind 先解绑监听器再 setChecked，程序回写不再触发 onToggle；
 * 2) Activity 维护 toggling 防抖集合 + optimistic 乐观状态，写后立即刷新 UI；
 * 3) Toast 仅在用户手势触发时展示一次。
 */
class AccessibilityPermActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAccessPermBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var adapter: AccessPermAdapter

    /** 正在切换的服务（防抖：避免并发 toggle / 回写触发重复切换）。 */
    private val toggling = mutableSetOf<String>()

    /** 乐观状态：写成功后立即覆盖 UI 显示，不等系统缓存异步生效。 */
    private val optimistic = mutableMapOf<String, Boolean>()

    /** 监听 Settings.Secure 变化：后台自监控拉起服务后自动刷新列表（修复"拉起生效但页面不刷新"bug）。 */
    private lateinit var settingsObserver: AccessibilitySettingsObserver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccessPermBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        settingsObserver = AccessibilitySettingsObserver(this) {
            // 系统设置变化时清除乐观状态，读取真实状态刷新
            optimistic.clear()
            refresh()
        }

        adapter = AccessPermAdapter(
            onToggle = { item, enabled -> toggleService(item, enabled) },
            onLock = { item -> toggleLock(item) },
            onItemClick = { item -> showOptionsSheet(item) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.tvCount.text = ""
        refresh()
    }

    override fun onStart() {
        super.onStart()
        settingsObserver.register()
    }

    override fun onStop() {
        super.onStop()
        settingsObserver.unregister()
    }

    override fun onResume() {
        super.onResume()
        // 回到页面时清除乐观状态，重新读取系统真实状态，避免 UI 与实际不一致
        optimistic.clear()
        refresh()
    }

    private fun refresh() {
        binding.tvBanner.text = when (val ch = Privilege.bestChannel(this)) {
            Privilege.Channel.NONE -> getString(R.string.accperm_banner_none)
            Privilege.Channel.APP_GRANTED -> getString(R.string.accperm_banner_app, getString(ch.labelRes))
            else -> getString(R.string.accperm_banner_shell, getString(ch.labelRes))
        }
        scope.launch {
            val (items, locked) = withContext(Dispatchers.IO) {
                val list = loadServices(this@AccessibilityPermActivity)
                val lk = Prefs.lockedAccessibilityServices(this@AccessibilityPermActivity)
                list to lk
            }
            adapter.submit(items, locked)
            binding.tvCount.text = getString(R.string.accperm_count, items.size)
        }
    }

    private fun loadServices(ctx: Context): List<AccessServiceItem> {
        val all = AccessServiceRepo.loadServices(ctx)
        val userOnly = Prefs.accessPermUserOnly(ctx)
        // 乐观状态覆盖 enabled（写后立即生效）；"只显示用户应用"过滤（本应用始终保留）
        val list = all.map { item ->
            optimistic[item.flatten]?.let { item.copy(enabled = it) } ?: item
        }.filter { item ->
            !userOnly || item.packageName == ctx.packageName || !isSystemApp(ctx, item.packageName)
        }
        return list.sortedWith(
            compareByDescending<AccessServiceItem> { it.packageName == ctx.packageName }
                .thenByDescending { it.enabled }
                .thenBy { it.packageName }
                .thenBy { it.component.className }
        )
    }

    private fun isSystemApp(ctx: Context, pkg: String): Boolean = try {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
    } catch (t: Throwable) {
        false
    }

    /** 开关：仅由用户手势触发；防抖 + 乐观更新 + Toast 只弹一次。 */
    private fun toggleService(item: AccessServiceItem, enabled: Boolean) {
        val flat = item.flatten
        if (flat in toggling) return
        toggling.add(flat)
        // 用户主动关闭时记录冷却期，自监控在冷却期内不自动恢复
        if (!enabled) Prefs.markUserDisabled(this, flat)
        // 标记本应用正在操作该服务（3秒冷却期），避免开关回写触发 ContentObserver 误报"丢失"
        com.acsmanager.pro.selfguard.SelfGuardService.markSelfOperating(flat)
        val ctx = this
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                ServiceStateController.setEnabled(ctx, item.component, enabled)
            }
            toggling.remove(flat)
            if (r.ok) {
                optimistic[flat] = enabled
            } else if (!enabled) {
                // 关闭失败时清除冷却期，避免后续无法自动恢复
                Prefs.markUserDisabled(ctx, flat) // 覆盖为当前时间，仍在冷却期内
            }
            Toast.makeText(
                ctx,
                getString(
                    if (r.ok) R.string.accperm_toggle_ok else R.string.accperm_toggle_fail,
                    item.label
                ),
                Toast.LENGTH_SHORT
            ).show()
            if (!r.ok) {
                // 无通道：引导到系统无障碍设置手动开启
                try {
                    startActivity(
                        Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (t: Throwable) {
                }
            }
            refresh()
        }
    }

    private fun toggleLock(item: AccessServiceItem) {
        val flat = item.flatten
        val cur = Prefs.lockedAccessibilityServices(this).toMutableSet()
        val locked = flat !in cur
        if (locked) cur.add(flat) else cur.remove(flat)
        Prefs.setLockedAccessibilityServices(this, cur)
        Toast.makeText(
            this,
            getString(if (locked) R.string.accperm_locked else R.string.accperm_unlocked),
            Toast.LENGTH_SHORT
        ).show()
        refresh()
    }

    // ---------- 图2 风格：点击卡片弹出勾选菜单 ----------

    private fun showOptionsSheet(item: AccessServiceItem) {
        val ctx = this
        val sheetBinding = SheetAccessPermBinding.inflate(LayoutInflater.from(this))
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(sheetBinding.root)

        val flat = item.flatten
        val label = item.label
        sheetBinding.sheetTitle.text = label
        sheetBinding.sheetSubtitle.text = getString(
            R.string.accperm_sheet_subtitle,
            item.packageName,
            item.component.className.substringAfterLast('.')
        )

        // ① 锁定保活（被关闭自动拉起）——per-app
        val locked = Prefs.lockedAccessibilityServices(this).contains(flat)
        sheetBinding.chkLock.isChecked = locked
        sheetBinding.chkLock.setOnCheckedChangeListener { _, checked ->
            val cur = Prefs.lockedAccessibilityServices(ctx).toMutableSet()
            if (checked) cur.add(flat) else cur.remove(flat)
            Prefs.setLockedAccessibilityServices(ctx, cur)
            refresh()
        }

        // ② 开机启动保活（并入看门狗恢复）——per-app
        val boot = Prefs.bootAccessibilityServices(this).contains(flat)
        sheetBinding.chkBoot.isChecked = boot
        sheetBinding.chkBoot.setOnCheckedChangeListener { _, checked ->
            val cur = Prefs.bootAccessibilityServices(ctx).toMutableSet()
            if (checked) cur.add(flat) else cur.remove(flat)
            Prefs.setBootAccessibilityServices(ctx, cur)
            Toast.makeText(
                ctx,
                getString(
                    if (checked) R.string.accperm_boot_on else R.string.accperm_boot_off,
                    label
                ),
                Toast.LENGTH_SHORT
            ).show()
        }

        // ③ 显示 Toast 保活提示（全局）
        sheetBinding.chkToast.isChecked = Prefs.relaunchToastEnabled(this)
        sheetBinding.chkToast.setOnCheckedChangeListener { _, checked ->
            Prefs.setRelaunchToastEnabled(ctx, checked)
            Toast.makeText(
                ctx,
                getString(
                    if (checked) R.string.accperm_toast_on else R.string.accperm_toast_off
                ),
                Toast.LENGTH_SHORT
            ).show()
        }

        // ④ 延迟保活（全局秒数，点击调整）
        updateDelayText(sheetBinding)
        sheetBinding.rowDelay.setOnClickListener { showDelayPicker(sheetBinding) }

        // ⑤ 只显示用户应用（本页过滤）
        val userOnly = Prefs.accessPermUserOnly(this)
        sheetBinding.chkUserOnly.isChecked = userOnly
        sheetBinding.chkUserOnly.setOnCheckedChangeListener { _, checked ->
            Prefs.setAccessPermUserOnly(ctx, checked)
            refresh()
        }

        // ⑥ 隐藏后台卡片：需系统级能力，暂不可用（禁用态已在布局中设置）

        // ⑦ 自定义通知文字（全局）
        updateTextValue(sheetBinding)
        sheetBinding.rowText.setOnClickListener {
            showTextPicker(sheetBinding)
        }

        dialog.show()
    }

    private fun updateDelayText(sheetBinding: SheetAccessPermBinding) {
        val secs = Prefs.selfGuardDelayMs(this) / 1000.0
        sheetBinding.tvDelayValue.text = String.format(java.util.Locale.US, "%.1f s", secs)
    }

    private fun showDelayPicker(sheetBinding: SheetAccessPermBinding) {
        val ctx = this
        val slider = Slider(ctx)
        slider.valueFrom = 0.5f
        slider.valueTo = 10f
        slider.stepSize = 0.5f
        slider.value = (Prefs.selfGuardDelayMs(ctx) / 1000.0f)
        val margin = (16 * resources.displayMetrics.density).toInt()
        slider.setPadding(margin, margin * 2, margin, 0)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.accperm_delay_title)
            .setMessage(R.string.accperm_delay_desc)
            .setView(slider)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                Prefs.setSelfGuardDelayMs(ctx, (slider.value * 1000).toLong())
                Toast.makeText(ctx, R.string.accperm_delay_saved, Toast.LENGTH_SHORT).show()
                updateDelayText(sheetBinding)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateTextValue(sheetBinding: SheetAccessPermBinding) {
        val text = Prefs.relaunchToastText(this)
        sheetBinding.tvTextValue.text = if (text.isNullOrBlank()) getString(R.string.accperm_text_empty)
        else text
    }

    private fun showTextPicker(sheetBinding: SheetAccessPermBinding) {
        val ctx = this
        val input = EditText(ctx)
        input.hint = getString(R.string.accperm_text_hint)
        input.setText(Prefs.relaunchToastText(ctx).orEmpty())
        val margin = (16 * resources.displayMetrics.density).toInt()
        input.setPadding(margin, margin, margin, 0)
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.accperm_opt_text)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val v = input.text?.toString()?.trim().orEmpty()
                Prefs.setRelaunchToastText(ctx, v)
                Toast.makeText(ctx, R.string.accperm_text_saved, Toast.LENGTH_SHORT).show()
                updateTextValue(sheetBinding)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

package com.acsmanager.pro.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.acsmanager.pro.R
import com.acsmanager.pro.core.AccessServiceItem
import com.acsmanager.pro.core.AccessServiceRepo
import com.acsmanager.pro.core.ServiceStateController
import com.acsmanager.pro.databinding.ActivityDetailBinding
import com.acsmanager.pro.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServiceDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COMPONENT = "extra_component"
        const val EXTRA_LABEL = "extra_label"
    }

    private lateinit var binding: ActivityDetailBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var item: AccessServiceItem? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        val flatten = intent.getStringExtra(EXTRA_COMPONENT) ?: run {
            finish()
            return
        }
        // 验证组件名格式合法
        if (ComponentName.unflattenFromString(flatten) == null) {
            finish()
            return
        }

        scope.launch {
            val target = withContext(Dispatchers.IO) {
                AccessServiceRepo.loadServices(this@ServiceDetailActivity)
                    .firstOrNull { it.flatten == flatten }
            }
            if (target == null) {
                Toast.makeText(this@ServiceDetailActivity, R.string.empty_services, Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            item = target
            bind(target)
        }
    }

    override fun onResume() {
        super.onResume()
        item?.let { refreshState(it) }
    }

    private fun bind(item: AccessServiceItem) {
        binding.toolbar.title = item.label
        binding.label.text = item.label
        binding.pkg.text = item.packageName
        try {
            binding.icon.setImageDrawable(packageManager.getApplicationIcon(item.packageName))
        } catch (t: Throwable) {
            binding.icon.setImageResource(R.drawable.ic_services)
        }

        // 能力徽章
        binding.capabilitiesRow.removeAllViews()
        val caps = capabilitiesOf(item)
        if (caps.isEmpty()) {
            binding.capabilitiesRow.addView(
                TextView(this).apply {
                    text = getString(R.string.cap_other)
                    setTextColor(ContextCompat.getColor(this@ServiceDetailActivity, R.color.chip_text))
                }
            )
        } else {
            for ((icon, text) in caps) {
                binding.capabilitiesRow.addView(buildChip(icon, getString(text)))
            }
        }

        binding.summaryText.text = item.summary ?: getString(R.string.none)
        binding.descriptionText.text = item.description ?: getString(R.string.none)

        // 内置设置页
        val hasSettingsActivity = !item.settingsActivity.isNullOrBlank()
        binding.rowSettingsActivity.setOnClickListener {
            if (hasSettingsActivity) {
                try {
                    startActivity(
                        Intent().setClassName(item.packageName, item.settingsActivity!!)
                    )
                } catch (t: Throwable) {
                    Toast.makeText(this, R.string.no_settings_activity, Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(this, R.string.no_settings_activity, Toast.LENGTH_SHORT).show()
            }
        }
        binding.settingsActivityLabel.text =
            if (hasSettingsActivity) item.settingsActivity else getString(R.string.no_settings_activity)

        binding.rowAppInfo.setOnClickListener {
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:${item.packageName}"))
                )
            } catch (t: Throwable) {
                Toast.makeText(this, R.string.result_fail, Toast.LENGTH_SHORT).show()
            }
        }

        binding.rowSysSettings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnToggle.setOnClickListener {
            val current = item ?: return@setOnClickListener
            val enable = !AccessServiceRepo.enabledStrings(this).contains(current.flatten)
            toggle(enable)
        }

        binding.keepAliveSwitch.setOnCheckedChangeListener { _, checked ->
            val current = item ?: return@setOnCheckedChangeListener
            val set = Prefs.protectedServices(this).toMutableSet()
            if (checked) set.add(current.flatten) else set.remove(current.flatten)
            Prefs.setProtectedServices(this, set)
            if (checked && Prefs.isWatchdogEnabled(this)) {
                com.acsmanager.pro.watchdog.WatchdogService.start(this)
            }
        }

        refreshState(item)
    }

    private fun refreshState(it: AccessServiceItem) {
        val enabled = AccessServiceRepo.enabledStrings(this).contains(it.flatten)
        binding.statusChip.text = getString(if (enabled) R.string.detail_enabled else R.string.detail_disabled)
        binding.statusChip.setTextColor(
            ContextCompat.getColor(
                this,
                if (enabled) R.color.status_ok else R.color.status_err
            )
        )
        binding.btnToggle.text = getString(if (enabled) R.string.action_disable else R.string.action_enable)
        binding.keepAliveSwitch.setOnCheckedChangeListener(null)
        binding.keepAliveSwitch.isChecked = Prefs.protectedServices(this).contains(it.flatten)
        binding.keepAliveSwitch.setOnCheckedChangeListener { _, checked ->
            val set = Prefs.protectedServices(this).toMutableSet()
            if (checked) set.add(it.flatten) else set.remove(it.flatten)
            Prefs.setProtectedServices(this, set)
            if (checked && Prefs.isWatchdogEnabled(this)) {
                com.acsmanager.pro.watchdog.WatchdogService.start(this)
            }
        }
    }

    private fun toggle(enable: Boolean) {
        val current = item ?: return
        scope.launch {
            val result = ServiceStateController.setEnabled(this@ServiceDetailActivity, current.component, enable)
            if (result.ok) {
                refreshState(current)
            } else {
                Toast.makeText(
                    this@ServiceDetailActivity,
                    getString(R.string.result_fail, result.message),
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    private fun capabilitiesOf(item: AccessServiceItem): List<Pair<Int, Int>> {
        val list = mutableListOf<Pair<Int, Int>>()
        val cap = item.capabilities
        if (cap and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0)
            list.add(R.drawable.ic_eye to R.string.cap_read_screen)
        if (cap and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0)
            list.add(R.drawable.ic_gesture to R.string.cap_gestures)
        if (cap and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS != 0)
            list.add(R.drawable.ic_key to R.string.cap_keys)
        if (Build.VERSION.SDK_INT >= 30 &&
            cap and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
        )
            list.add(R.drawable.ic_screenshot to R.string.cap_screenshot)
        if (cap and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_CONTROL_MAGNIFICATION != 0)
            list.add(R.drawable.ic_magnify to R.string.cap_magnify)
        if (cap and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION != 0)
            list.add(R.drawable.ic_gesture to R.string.cap_touch)
        return list
    }

    private fun buildChip(iconRes: Int, text: String): ViewGroup {
        val chip = LinearLayout(this)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = Gravity.CENTER_VERTICAL
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }
        chip.setPadding(dp(10), dp(4), dp(10), dp(4))
        chip.setBackgroundResource(R.drawable.badge_chip)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = dp(6)
        lp.topMargin = dp(2)
        chip.layoutParams = lp

        val iv = ImageView(this)
        iv.setImageResource(iconRes)
        iv.setColorFilter(ContextCompat.getColor(this, R.color.chip_text))
        chip.addView(iv, LinearLayout.LayoutParams(dp(14), dp(14)))

        val tv = TextView(this)
        tv.text = text
        tv.textSize = 12f
        tv.setTextColor(ContextCompat.getColor(this, R.color.chip_text))
        tv.includeFontPadding = false
        val tlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tlp.marginStart = dp(4)
        chip.addView(tv, tlp)
        return chip
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

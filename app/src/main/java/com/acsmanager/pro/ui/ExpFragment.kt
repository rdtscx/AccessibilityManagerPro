package com.acsmanager.pro.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.acsmanager.pro.BuildConfig
import com.acsmanager.pro.R
import com.acsmanager.pro.core.AccessServiceRepo
import com.acsmanager.pro.core.Privilege
import com.acsmanager.pro.databinding.FragmentExpBinding
import com.acsmanager.pro.keepalive.KeepAliveEngine
import com.acsmanager.pro.notif.NotifGateService
import com.acsmanager.pro.selfguard.SelfAccessService
import com.acsmanager.pro.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 实验页：性能/策略/通知实验开关 + 自检工具 + 关于信息。
 */
class ExpFragment : Fragment() {

    private var _binding: FragmentExpBinding? = null
    private val binding get() = _binding!!
    /** 程序化切换开关时置 true，避免触发互斥逻辑造成循环。 */
    private var programmaticSwitch = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentExpBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.sliderThrottle.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            Prefs.setEventThrottleMs(requireContext(), value.toLong())
            updateThrottleLabel()
        }

        binding.switchScreenoff.setOnCheckedChangeListener { _, checked ->
            if (programmaticSwitch) return@setOnCheckedChangeListener
            Prefs.setKeepAliveScreenOffOnly(requireContext(), checked)
            // 互斥：开启"仅屏幕关闭时拉活"时关闭"熄屏休眠省电"（二者逻辑冲突）
            if (checked) {
                Prefs.setDozeModeEnabled(requireContext(), false)
                programmaticSwitch = true
                binding.switchDoze.isChecked = false
                programmaticSwitch = false
            }
        }

        binding.switchLowbattery.setOnCheckedChangeListener { _, checked ->
            Prefs.setKeepAlivePauseLowBattery(requireContext(), checked)
        }

        binding.switchDoze.setOnCheckedChangeListener { _, checked ->
            if (programmaticSwitch) return@setOnCheckedChangeListener
            Prefs.setDozeModeEnabled(requireContext(), checked)
            // 互斥：开启"熄屏休眠省电"时关闭"仅屏幕关闭时拉活"（二者逻辑冲突）
            if (checked) {
                Prefs.setKeepAliveScreenOffOnly(requireContext(), false)
                programmaticSwitch = true
                binding.switchScreenoff.isChecked = false
                programmaticSwitch = false
            }
        }

        binding.sliderInterval.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            Prefs.setKeepAliveIntervalMs(requireContext(), (value * 1000).toLong())
            updateIntervalLabel()
        }

        binding.switchNotifHistory.setOnCheckedChangeListener { _, checked ->
            Prefs.setNotifHistoryEnabled(requireContext(), checked)
        }

        binding.switchNotifOverride.setOnCheckedChangeListener { _, checked ->
            Prefs.setNotifOverrideEnabled(requireContext(), checked)
        }

        // 主题切换：白天 / 黑夜 / 跟随系统，立即生效
        binding.themeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || programmaticSwitch) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btn_theme_light -> "light"
                R.id.btn_theme_dark -> "dark"
                else -> "system"
            }
            // 模式未变化时不 recreate，避免不必要的闪烁
            if (Prefs.themeMode(requireContext()) == mode) return@addOnButtonCheckedListener
            Prefs.setThemeMode(requireContext(), mode)
            applyTheme(mode)
        }

        binding.btnRootCheck.setOnClickListener {
            scope.launch {
                val root = withContext(Dispatchers.IO) { Privilege.hasRoot() }
                Toast.makeText(
                    requireContext(),
                    if (root) R.string.exp_root_yes else R.string.exp_root_no,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        binding.btnRelaunchTest.setOnClickListener {
            val pkg = Prefs.keepAliveApps(requireContext()).firstOrNull()
            if (pkg == null) {
                Toast.makeText(requireContext(), R.string.exp_no_protected, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val ok = KeepAliveEngine.manualRelaunch(requireContext(), pkg)
            Toast.makeText(
                requireContext(),
                getString(if (ok) R.string.exp_relaunch_ok else R.string.exp_relaunch_fail, pkg),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().title = getString(R.string.tab_exp)

        updateThrottleLabel()
        binding.sliderThrottle.value = Prefs.eventThrottleMs(requireContext()).toFloat()

        // 初始化开关状态（程序化设置，不触发 listener 和互斥逻辑）
        programmaticSwitch = true
        binding.switchScreenoff.isChecked = Prefs.keepAliveScreenOffOnly(requireContext())
        binding.switchLowbattery.isChecked = Prefs.keepAlivePauseLowBattery(requireContext())
        binding.switchDoze.isChecked = Prefs.dozeModeEnabled(requireContext())
        binding.switchNotifHistory.isChecked = Prefs.notifHistoryEnabled(requireContext())
        binding.switchNotifOverride.isChecked = Prefs.notifOverrideEnabled(requireContext())
        programmaticSwitch = false

        // 初始化主题按钮选中状态（程序化设置，不触发 listener）
        val themeBtn = when (Prefs.themeMode(requireContext())) {
            "light" -> R.id.btn_theme_light
            "dark" -> R.id.btn_theme_dark
            else -> R.id.btn_theme_system
        }
        if (binding.themeToggle.checkedButtonId != themeBtn) {
            programmaticSwitch = true
            binding.themeToggle.check(themeBtn)
            programmaticSwitch = false
        }

        updateIntervalLabel()
        binding.sliderInterval.value = (Prefs.keepAliveIntervalMs(requireContext()) / 1000f)
            .coerceIn(10f, 300f)

        binding.tvAbout.text = getString(
            R.string.exp_about,
            BuildConfig.VERSION_NAME,
            Build.VERSION.SDK_INT
        )
        refreshSelfTest()
    }

    private fun updateThrottleLabel() {
        binding.tvThrottle.text = getString(
            R.string.exp_throttle_value,
            Prefs.eventThrottleMs(requireContext())
        )
    }

    private fun updateIntervalLabel() {
        binding.tvInterval.text = getString(
            R.string.exp_interval_value,
            Prefs.keepAliveIntervalMs(requireContext()) / 1000
        )
    }

    /** 应用主题模式并同步状态栏颜色。 */
    private fun applyTheme(mode: String) {
        val nightMode = when (mode) {
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
        // Activity  recreate 后状态栏颜色由 MainActivity 统一设置
        requireActivity().recreate()
    }

    private fun refreshSelfTest() {
        val ctx = requireContext()
        val lines = mutableListOf<String>()

        val selfAccess = AccessServiceRepo.enabledStrings(ctx)
            .contains(android.content.ComponentName(ctx, SelfAccessService::class.java).flattenToString())
        lines.add(getString(R.string.exp_self_access, yesNo(selfAccess)))

        val ch = Privilege.bestChannel(ctx)
        lines.add(getString(R.string.exp_channel, getString(ch.labelRes)))

        lines.add(getString(R.string.exp_usage_access, yesNo(hasUsageAccess(ctx))))

        val notifListener = NotifGateService.isEnabled(ctx)
        lines.add(getString(R.string.exp_notif_listener, yesNo(notifListener)))

        lines.add(getString(
            R.string.exp_stats,
            Prefs.keepAliveApps(ctx).size,
            Prefs.relaunchCount(ctx),
            Prefs.notifBlockCount(ctx)
        ))

        binding.tvSelftest.text = lines.joinToString("\n")
    }

    private fun yesNo(b: Boolean): String = getString(if (b) R.string.exp_yes else R.string.exp_no)

    private fun hasUsageAccess(ctx: Context): Boolean = try {
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE)
                as android.app.usage.UsageStatsManager
        usm.queryEvents(System.currentTimeMillis() - 1000, System.currentTimeMillis())
        true
    } catch (t: SecurityException) {
        false
    } catch (t: Throwable) {
        false
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

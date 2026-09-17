package com.acsmanager.pro.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
            Prefs.setKeepAliveScreenOffOnly(requireContext(), checked)
        }

        binding.switchLowbattery.setOnCheckedChangeListener { _, checked ->
            Prefs.setKeepAlivePauseLowBattery(requireContext(), checked)
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

        binding.switchScreenoff.isChecked = Prefs.keepAliveScreenOffOnly(requireContext())
        binding.switchLowbattery.isChecked = Prefs.keepAlivePauseLowBattery(requireContext())
        binding.switchNotifHistory.isChecked = Prefs.notifHistoryEnabled(requireContext())
        binding.switchNotifOverride.isChecked = Prefs.notifOverrideEnabled(requireContext())

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

package com.acsmanager.pro.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.acsmanager.pro.R
import com.acsmanager.pro.databinding.FragmentKeepaliveBinding
import com.acsmanager.pro.keepalive.AppEntry
import com.acsmanager.pro.keepalive.AppsRepository
import com.acsmanager.pro.ui.adapter.KeepAliveAdapter
import com.acsmanager.pro.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 保活页：扫描罗列全机用户/系统软件，勾选加入保活名单；后台无感拉起（见 KeepAliveEngine）。
 */
class KeepAliveFragment : Fragment() {

    private var _binding: FragmentKeepaliveBinding? = null
    private val binding get() = _binding!!

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = KeepAliveAdapter { refreshSummary() }

    private var allApps: List<AppEntry> = emptyList()
    private var filter = FILTER_ALL
    private var query = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKeepaliveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                query = s?.toString()?.trim() ?: ""
                applyFilter()
            }

            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.chipGroup.setOnCheckedStateChangeListener { _, _ ->
            filter = when (binding.chipGroup.checkedChipId) {
                R.id.chip_user -> FILTER_USER
                R.id.chip_system -> FILTER_SYSTEM
                R.id.chip_protected -> FILTER_PROTECTED
                else -> FILTER_ALL
            }
            applyFilter()
        }

        binding.btnUsageAccess.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), R.string.usage_open_failed, Toast.LENGTH_SHORT).show()
            }
        }

        // 黑屏（熄屏）时是否也保活（用户可自定义）
        binding.switchBlackScreen.setOnCheckedChangeListener { _, checked ->
            Prefs.setKeepAliveBlackScreen(requireContext(), checked)
            Toast.makeText(
                requireContext(),
                getString(
                    if (checked) R.string.keepalive_blackscreen_on
                    else R.string.keepalive_blackscreen_off
                ),
                Toast.LENGTH_SHORT
            ).show()
        }

        // 拉起后返回方案（用户可自选：切回上一应用 / 停留 / 回桌面）
        binding.rowReturnMode.setOnClickListener { pickReturnMode() }

        loadApps()
    }

    /** 返回方案选择：拉起保活应用后 100ms 的处理方式。 */
    private fun pickReturnMode() {
        val ctx = requireContext()
        val options = arrayOf(
            ctx.getString(R.string.keepalive_return_prev),
            ctx.getString(R.string.keepalive_return_stay),
            ctx.getString(R.string.keepalive_return_home)
        )
        val values = arrayOf("prev", "stay", "home")
        val current = Prefs.keepAliveReturnMode(ctx)
        val idx = values.indexOf(current).coerceAtLeast(0)
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(R.string.keepalive_return_title)
            .setSingleChoiceItems(options, idx) { _, which ->
                Prefs.setKeepAliveReturnMode(ctx, values[which])
                refreshReturnValue()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshReturnValue() {
        val mode = Prefs.keepAliveReturnMode(requireContext())
        binding.tvReturnValue.text = when (mode) {
            "stay" -> getString(R.string.keepalive_return_stay_short)
            "home" -> getString(R.string.keepalive_return_home_short)
            else -> getString(R.string.keepalive_return_prev_short)
        }
    }

    private fun loadApps() {
        scope.launch {
            val apps = withContext(Dispatchers.IO) { AppsRepository.scan(requireContext()) }
            allApps = apps
            applyFilter()
        }
    }

    private fun applyFilter() {
        val protected = Prefs.keepAliveApps(requireContext())
        val q = query.lowercase()
        val filtered = allApps.filter { app ->
            (filter == FILTER_ALL ||
                (filter == FILTER_USER && !app.isSystem) ||
                (filter == FILTER_SYSTEM && app.isSystem) ||
                (filter == FILTER_PROTECTED && app.packageName in protected)) &&
                (q.isEmpty() || app.label.lowercase().contains(q) ||
                    app.packageName.lowercase().contains(q))
        }
        adapter.submit(filtered, protected)
        refreshSummary()
    }

    private fun refreshSummary() {
        val ctx = requireContext()
        val protected = Prefs.keepAliveApps(ctx)
        binding.tvSummary.text = getString(
            R.string.keepalive_summary,
            adapter.itemCount,
            protected.size,
            Prefs.relaunchCount(ctx)
        )
        // 未授予"使用情况访问"时显示引导按钮（更精准的掉线判定）
        binding.btnUsageAccess.visibility = if (hasUsageAccess(ctx)) View.GONE else View.VISIBLE
        // 策略状态回显（防程序回写触发监听）
        binding.switchBlackScreen.setOnCheckedChangeListener(null)
        binding.switchBlackScreen.isChecked = Prefs.keepAliveBlackScreen(ctx)
        binding.switchBlackScreen.setOnCheckedChangeListener { _, checked ->
            Prefs.setKeepAliveBlackScreen(ctx, checked)
        }
        refreshReturnValue()
    }

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

    override fun onResume() {
        super.onResume()
        requireActivity().title = getString(R.string.tab_keepalive)
        // 从保活名单回到页面时刷新状态点
        applyFilter()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val FILTER_ALL = 0
        private const val FILTER_USER = 1
        private const val FILTER_SYSTEM = 2
        private const val FILTER_PROTECTED = 3
    }
}

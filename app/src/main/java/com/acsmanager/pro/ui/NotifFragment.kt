package com.acsmanager.pro.ui

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.acsmanager.pro.R
import com.acsmanager.pro.core.Privilege
import com.acsmanager.pro.databinding.FragmentNotifBinding
import com.acsmanager.pro.keepalive.AppEntry
import com.acsmanager.pro.keepalive.AppsRepository
import com.acsmanager.pro.notif.NotifGateService
import com.acsmanager.pro.ui.adapter.NotifAdapter
import com.acsmanager.pro.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 通知页：对用户/系统软件的通知权限做 0-5 级精细调控（0 屏蔽 / 1 静默 / 2 低 / 3 标准 / 4 高 / 5 最高）。
 */
class NotifFragment : Fragment() {

    private var _binding: FragmentNotifBinding? = null
    private val binding get() = _binding!!

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = NotifAdapter()

    private var allApps: List<AppEntry> = emptyList()
    private var filter = FILTER_ALL
    private var query = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentNotifBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recycler.layoutManager = LinearLayoutManager(requireContext())
        binding.recycler.adapter = adapter

        binding.btnGrantNotifAccess.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (t: Throwable) {
                Toast.makeText(requireContext(), R.string.notif_open_failed, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnResetNotif.setOnClickListener {
            Prefs.resetNotifLevels(requireContext())
            loadApps()
            Toast.makeText(requireContext(), R.string.notif_reset_done, Toast.LENGTH_SHORT).show()
        }

        // 搜索
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                query = s?.toString()?.trim() ?: ""
                applyFilter()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        // 筛选 chips
        binding.chipGroup.setOnCheckedStateChangeListener { _, _ ->
            filter = when (binding.chipGroup.checkedChipId) {
                R.id.chip_user -> FILTER_USER
                R.id.chip_system -> FILTER_SYSTEM
                R.id.chip_set -> FILTER_SET
                else -> FILTER_ALL
            }
            applyFilter()
        }
    }

    private fun loadApps() {
        scope.launch {
            val apps = withContext(Dispatchers.IO) { AppsRepository.scan(requireContext()) }
            allApps = apps
            applyFilter()
            binding.tvNotifCount.text = getString(
                R.string.notif_count,
                Prefs.notifLevels(requireContext()).size
            )
        }
    }

    /** 按搜索词 + 筛选 chip 过滤列表（与保活页一致）。
     *  "已设置"只显示用户真正改过等级（非默认 3 级）的应用，系统默认不干预的不算。 */
    private fun applyFilter() {
        val ctx = requireContext()
        val setPkgs = Prefs.notifLevels(ctx).filterValues { it != 3 }.keys
        val q = query.lowercase()
        val filtered = allApps.filter { app ->
            (filter == FILTER_ALL ||
                (filter == FILTER_USER && !app.isSystem) ||
                (filter == FILTER_SYSTEM && app.isSystem) ||
                (filter == FILTER_SET && app.packageName in setPkgs)) &&
                (q.isEmpty() || app.label.lowercase().contains(q) ||
                    app.packageName.lowercase().contains(q))
        }
        adapter.submit(filtered)
    }

    override fun onResume() {
        super.onResume()
        requireActivity().title = getString(R.string.tab_notif)
        refreshBanner()
        loadApps()
    }

    private fun refreshBanner() {
        val ctx = requireContext()
        val granted = NotifGateService.isEnabled(ctx)
        binding.tvNotifBanner.setText(
            if (granted) R.string.notif_access_ok else R.string.notif_access_need
        )
        binding.tvNotifBanner.setTextColor(
            ContextCompat.getColor(
                ctx,
                if (granted) R.color.status_ok else R.color.status_err
            )
        )
        binding.btnGrantNotifAccess.visibility = if (granted) View.GONE else View.VISIBLE

        // 精细调控提示：通道与系统版本
        val ch = Privilege.bestChannel(ctx)
        val api = android.os.Build.VERSION.SDK_INT
        val hint = if (ch != Privilege.Channel.NONE && api >= 34) {
            getString(R.string.notif_hint_fine)
        } else if (ch == Privilege.Channel.NONE) {
            getString(R.string.notif_hint_no_priv)
        } else {
            getString(R.string.notif_hint_api)
        }
        binding.tvNotifHint.text = hint
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
        private const val FILTER_SET = 3
    }
}

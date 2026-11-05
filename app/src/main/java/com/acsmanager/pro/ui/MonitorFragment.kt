package com.acsmanager.pro.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.acsmanager.pro.R
import com.acsmanager.pro.core.AccessibilitySettingsObserver
import com.acsmanager.pro.databinding.FragmentMonitorBinding
import com.acsmanager.pro.ui.adapter.EventAdapter
import com.acsmanager.pro.watchdog.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MonitorFragment : Fragment() {

    private var _binding: FragmentMonitorBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter by lazy { EventAdapter(requireContext()) }

    /** 监听 Settings.Secure 变化：无障碍服务状态变化时自动刷新事件日志。 */
    private var settingsObserver: AccessibilitySettingsObserver? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMonitorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.eventList.layoutManager = LinearLayoutManager(requireContext())
        binding.eventList.adapter = adapter

        settingsObserver = AccessibilitySettingsObserver(requireContext()) {
            load()
        }

        binding.btnClear.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.monitor_clear)
                .setMessage(R.string.monitor_clear_confirm)
                .setPositiveButton(R.string.ok) { _, _ ->
                    EventLog.clear(requireContext())
                    load()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
        load()
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
        requireActivity().title = getString(R.string.monitor_title)
        load()
    }

    private fun load() {
        scope.launch {
            try {
                val entries = withContext(Dispatchers.IO) {
                    EventLog.query(requireContext())
                }
                adapter.submit(entries)
                binding.emptyView.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
                binding.headerText.text = getString(R.string.monitor_title)
            } catch (t: Throwable) {
                // 防御：数据库异常时兜底，避免整页闪退
                binding.emptyView.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

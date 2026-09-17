package com.acsmanager.pro.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.acsmanager.pro.R
import com.acsmanager.pro.core.AccessServiceRepo
import com.acsmanager.pro.core.AccessServiceItem
import com.acsmanager.pro.core.Privilege
import com.acsmanager.pro.core.ServiceStateController
import com.acsmanager.pro.databinding.FragmentServicesBinding
import com.acsmanager.pro.ui.adapter.ServiceAdapter
import com.acsmanager.pro.util.Prefs
import com.acsmanager.pro.watchdog.WatchdogService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServicesFragment : Fragment() {

    private var _binding: FragmentServicesBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = ServiceAdapter(
        onItemClick = { openDetail(it) },
        onToggle = { item, enabled -> toggle(item, enabled) }
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentServicesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.serviceList.layoutManager = LinearLayoutManager(requireContext())
        binding.serviceList.adapter = adapter

        binding.btnGuide.setOnClickListener {
            startActivity(Intent(requireContext(), GuideActivity::class.java))
        }

        binding.watchdogSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setWatchdogEnabled(requireContext(), checked)
            if (checked) WatchdogService.start(requireContext())
            else WatchdogService.stop(requireContext())
        }

        binding.swipe.setOnRefreshListener { load() }

        binding.searchInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                adapter.applyFilter(s?.toString() ?: "")
                updateCount()
            }
        })

        requestNotifPermissionIfNeeded()
        load()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        load()
    }

    private fun refreshStatus() {
        val ctx = requireContext()
        val channel = Privilege.bestChannel(ctx)
        binding.statusText.setText(channel.labelRes)
        binding.statusText.setTextColor(
            ContextCompat.getColor(
                ctx,
                when (channel) {
                    Privilege.Channel.NONE -> R.color.status_err
                    else -> R.color.status_ok
                }
            )
        )
        binding.watchdogSwitch.setOnCheckedChangeListener(null)
        binding.watchdogSwitch.isChecked = Prefs.isWatchdogEnabled(ctx)
        binding.watchdogSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setWatchdogEnabled(ctx, checked)
            if (checked) WatchdogService.start(ctx) else WatchdogService.stop(ctx)
        }
    }

    private fun load() {
        scope.launch {
            try {
                val items = withContext(Dispatchers.IO) {
                    AccessServiceRepo.loadServices(requireContext())
                }
                adapter.submit(items)
                updateCount()
                binding.swipe.isRefreshing = false
                binding.emptyView.visibility =
                    if (items.isEmpty()) View.VISIBLE else View.GONE
            } catch (t: Throwable) {
                // 防御：个别 ROM 无障碍服务查询异常时兜底，避免整页闪退
                binding.swipe.isRefreshing = false
                binding.emptyView.visibility = View.VISIBLE
            }
        }
    }

    private fun updateCount() {
        val enabled = AccessServiceRepo.enabledStrings(requireContext()).size
        binding.countText.text = getString(R.string.service_count, adapter.itemCount, enabled)
    }

    private fun toggle(item: AccessServiceItem, enabled: Boolean) {
        scope.launch {
            val result = ServiceStateController.setEnabled(requireContext(), item.component, enabled)
            val msg = when {
                result.ok && result.message == "no_change" -> getString(R.string.result_no_change)
                result.ok -> getString(R.string.result_ok)
                else -> getString(R.string.result_fail, result.message)
            }
            if (!result.ok) {
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                // 失败时跳转系统无障碍设置页作为兜底
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            load()
        }
    }

    private fun openDetail(item: AccessServiceItem) {
        val intent = Intent(requireContext(), ServiceDetailActivity::class.java)
            .putExtra(ServiceDetailActivity.EXTRA_COMPONENT, item.flatten)
            .putExtra(ServiceDetailActivity.EXTRA_LABEL, item.label)
        startActivity(intent)
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

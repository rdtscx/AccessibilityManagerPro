package com.acsmanager.pro.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.acsmanager.pro.R
import com.acsmanager.pro.BuildConfig
import com.acsmanager.pro.core.AccessServiceRepo
import com.acsmanager.pro.core.ServiceStateController
import com.acsmanager.pro.databinding.FragmentSettingsBinding
import com.acsmanager.pro.util.Prefs
import com.acsmanager.pro.watchdog.EventLog
import com.acsmanager.pro.watchdog.WatchdogService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    try {
                        requireContext().contentResolver.openOutputStream(uri)?.use {
                            it.write(Prefs.exportJson(requireContext()).toByteArray())
                        } != null
                    } catch (t: Throwable) {
                        false
                    }
                }
                Toast.makeText(
                    requireContext(),
                    if (ok) getString(R.string.backup_done) else getString(R.string.restore_fail, "write failed"),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val text = requireContext().contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        text?.let { Prefs.importJson(requireContext(), it) } ?: false
                    } catch (t: Throwable) {
                        false
                    }
                }
                Toast.makeText(
                    requireContext(),
                    if (result) getString(R.string.restore_done) else getString(R.string.restore_fail, "invalid file"),
                    Toast.LENGTH_SHORT
                ).show()
                refreshUi()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onResume() {
        super.onResume()
        // 二级页嵌套首页时同步工具栏标题，返回首页后由 HomeFragment.onResume 恢复应用名
        requireActivity().title = getString(R.string.tab_settings)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // 看门狗
        binding.watchdogSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setWatchdogEnabled(requireContext(), checked)
            if (checked) WatchdogService.start(requireContext())
            else WatchdogService.stop(requireContext())
        }

        binding.intervalGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val ms = when (checkedId) {
                R.id.interval_30s -> 30_000L
                R.id.interval_1m -> 60_000L
                R.id.interval_5m -> 300_000L
                else -> return@addOnButtonCheckedListener
            }
            Prefs.setWatchdogIntervalMs(requireContext(), ms)
        }

        binding.rowProtected.setOnClickListener { pickProtectedServices() }
        binding.rowTileTarget.setOnClickListener { pickTileTarget() }
        binding.rowBackup.setOnClickListener { exportLauncher.launch("acs_pro_backup.json") }
        binding.rowRestore.setOnClickListener {
            importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        binding.rowClearLog.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_clear_log)
                .setMessage(R.string.monitor_clear_confirm)
                .setPositiveButton(R.string.ok) { _, _ -> EventLog.clear(requireContext()) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.themeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.theme_light -> "light"
                R.id.theme_dark -> "dark"
                else -> "system"
            }
            Prefs.setThemeMode(requireContext(), mode)
            when (mode) {
                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "dark" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }

        binding.rowLanguage.setOnClickListener { pickLanguage() }

        binding.rowDisableAll.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_disable_all)
                .setMessage(R.string.settings_disable_all_confirm)
                .setPositiveButton(R.string.ok) { _, _ ->
                    scope.launch {
                        val r = ServiceStateController.disableAll(requireContext())
                        Toast.makeText(
                            requireContext(),
                            if (r.ok) getString(R.string.result_ok) else getString(R.string.result_fail, r.message),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }

        binding.rowAbout.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.settings_about)
                .setMessage(
                    "${getString(R.string.about_version, BuildConfig.VERSION_NAME)}\n\n" +
                        getString(R.string.about_body)
                )
                .setPositiveButton(R.string.ok, null)
                .show()
        }

        refreshUi()
    }

    private fun refreshUi() {
        val ctx = requireContext()
        binding.watchdogSwitch.setOnCheckedChangeListener(null)
        binding.watchdogSwitch.isChecked = Prefs.isWatchdogEnabled(ctx)
        binding.watchdogSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setWatchdogEnabled(ctx, checked)
            if (checked) WatchdogService.start(ctx) else WatchdogService.stop(ctx)
        }

        val interval = Prefs.watchdogIntervalMs(ctx)
        val intervalId = when {
            interval <= 30_000 -> R.id.interval_30s
            interval <= 60_000 -> R.id.interval_1m
            else -> R.id.interval_5m
        }
        binding.intervalGroup.check(intervalId)

        val protected = Prefs.protectedServices(ctx)
        binding.protectedValue.text = if (protected.isEmpty()) {
            ctx.getString(R.string.settings_protected_empty)
        } else {
            ctx.getString(R.string.settings_protected_count, protected.size)
        }

        val tile = Prefs.tileService(ctx)
        binding.tileTargetValue.text = tile?.let {
            android.content.ComponentName.unflattenFromString(it)?.shortClassName
                ?: ctx.getString(R.string.settings_tile_target_none)
        } ?: ctx.getString(R.string.settings_tile_target_none)

        val theme = Prefs.themeMode(ctx)
        binding.themeGroup.check(
            when (theme) {
                "light" -> R.id.theme_light
                "dark" -> R.id.theme_dark
                else -> R.id.theme_system
            }
        )

        val lang = Prefs.language(ctx)
        binding.languageValue.text = when (lang) {
            "zh" -> ctx.getString(R.string.lang_zh)
            "en" -> ctx.getString(R.string.lang_en)
            else -> ctx.getString(R.string.theme_system)
        }
    }

    private fun pickProtectedServices() {
        val ctx = requireContext()
        scope.launch {
            val services = withContext(Dispatchers.IO) { AccessServiceRepo.loadServices(ctx) }
            if (services.isEmpty()) return@launch
            val labels = services.map { it.label }
            val checked = BooleanArray(services.size) {
                Prefs.protectedServices(ctx).contains(services[it].flatten)
            }
            AlertDialog.Builder(ctx)
                .setTitle(R.string.settings_protected)
                .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                    checked[which] = isChecked
                }
                .setPositiveButton(R.string.ok) { _, _ ->
                    val selected = mutableSetOf<String>()
                    services.forEachIndexed { i, s -> if (checked[i]) selected.add(s.flatten) }
                    Prefs.setProtectedServices(ctx, selected)
                    refreshUi()
                    if (Prefs.isWatchdogEnabled(ctx) && selected.isNotEmpty()) {
                        WatchdogService.start(ctx)
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun pickTileTarget() {
        val ctx = requireContext()
        scope.launch {
            val services = withContext(Dispatchers.IO) { AccessServiceRepo.loadServices(ctx) }
            val options = mutableListOf(ctx.getString(R.string.none))
            options.addAll(services.map { it.label })
            val current = Prefs.tileService(ctx)
            var selected = 0
            services.forEachIndexed { i, s ->
                if (s.flatten == current) selected = i + 1
            }
            AlertDialog.Builder(ctx)
                .setTitle(R.string.settings_tile_target)
                .setSingleChoiceItems(options.toTypedArray(), selected) { _, which ->
                    selected = which
                }
                .setPositiveButton(R.string.ok) { _, _ ->
                    val target = if (selected == 0) null else services[selected - 1].flatten
                    Prefs.setTileService(ctx, target)
                    refreshUi()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun pickLanguage() {
        val ctx = requireContext()
        val options = arrayOf(
            ctx.getString(R.string.theme_system),
            ctx.getString(R.string.lang_zh),
            ctx.getString(R.string.lang_en)
        )
        val values = arrayOf("system", "zh", "en")
        val current = Prefs.language(ctx)
        val idx = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(ctx)
            .setTitle(R.string.settings_language)
            .setSingleChoiceItems(options, idx) { _, which ->
                Prefs.setLanguage(ctx, values[which])
                refreshUi()
                requireActivity().recreate()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

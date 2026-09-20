package com.acsmanager.pro.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.acsmanager.pro.R
import com.acsmanager.pro.core.Privilege
import com.acsmanager.pro.databinding.ActivityServiceListBinding
import com.acsmanager.pro.databinding.ItemServiceComponentBinding
import com.acsmanager.pro.util.Prefs

/**
 * 二级页：展示某应用的全部可启动组件（Activity + Service），
 * 允许单独勾选某一组件加入保活；勾选即时生效、取消即时失效。
 *
 * 启动按钮：
 *  - Activity 有 LaunchIntent，直接 startActivity；
 *  - Service 无 LaunchIntent：无 Root 时按钮隐藏，有 Root 时通过 `am startservice` 拉起。
 */
class ServiceListActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE = "extra_package"
    }

    private lateinit var binding: ActivityServiceListBinding
    private lateinit var pkg: String
    private val adapter = ServiceAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServiceListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: run { finish(); return }
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.svclist_title)
            subtitle = pkg
        }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        val items = loadComponents()
        if (items.isEmpty()) {
            Toast.makeText(this, R.string.svclist_empty, Toast.LENGTH_SHORT).show()
        }
        adapter.submit(items)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    internal data class CompItem(
        val flat: String,          // ComponentName.flattenToString()
        val shortName: String,      // 短类名（可读）
        val typeLabel: String,      // "Activity" / "Service"
        val launchIntent: Intent?,  // 有 LaunchIntent 才有；Service 通常为 null
        val isService: Boolean
    )

    private fun loadComponents(): List<CompItem> {
        val pm = packageManager
        val out = mutableListOf<CompItem>()

        // 1. 该包下所有可启动的 Activity（exported=true 且带 LAUNCHER category）
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val activities = try {
            pm.queryIntentActivities(launcherIntent, 0)
                .filter { it.activityInfo.packageName == pkg }
                .map { it.activityInfo }
        } catch (t: Throwable) { emptyList() }
        for (ai in activities) {
            val cn = ComponentName(ai.packageName, ai.name)
            out.add(CompItem(
                flat = cn.flattenToString(),
                shortName = ai.name.substringAfterLast('.'),
                typeLabel = getString(R.string.svclist_section_activity),
                launchIntent = pm.getLaunchIntentForPackage(pkg),
                isService = false
            ))
        }

        // 2. 该包下所有 exported Service（用 queryIntentServices 拿不到非 MAIN intent 的，
        //    直接通过 PackageManager 拿不到全部 Service，用 getPackageInfo + GET_SERVICES）
        val services = try {
            val info = pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SERVICES)
            info.services?.toList() ?: emptyList()
        } catch (t: Throwable) { emptyList() }
        for (si in services) {
            if (si.exported == false) continue   // 只列 exported 的（否则拉不起来）
            val cn = ComponentName(si.packageName, si.name)
            out.add(CompItem(
                flat = cn.flattenToString(),
                shortName = si.name.substringAfterLast('.'),
                typeLabel = getString(R.string.svclist_section_service),
                launchIntent = null,
                isService = true
            ))
        }
        return out
    }

    inner class ServiceAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<ServiceAdapter.Holder>() {

        private var items: List<CompItem> = emptyList()

        internal fun submit(list: List<CompItem>) {
            items = list
            notifyDataSetChanged()
        }

        inner class Holder(val ib: ItemServiceComponentBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(ib.root) {

            private var item: CompItem? = null

            init {
                ib.btnLaunch.setOnClickListener {
                    val it = item ?: return@setOnClickListener
                    launchComponent(it)
                }
                ib.cbProtect.setOnCheckedChangeListener { _, checked ->
                    val it = item ?: return@setOnCheckedChangeListener
                    val set = Prefs.keepAliveComponents(this@ServiceListActivity).toMutableSet()
                    if (checked) set.add(it.flat) else set.remove(it.flat)
                    Prefs.setKeepAliveComponents(this@ServiceListActivity, set)
                    // 触发保活引擎重载目标
                    com.acsmanager.pro.keepalive.KeepAliveEngine.reloadTargets(this@ServiceListActivity)
                    Toast.makeText(
                        this@ServiceListActivity,
                        getString(
                            if (checked) R.string.svclist_protected
                            else R.string.svclist_not_protected
                        ) + " · " + it.shortName,
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            internal fun bind(ci: CompItem) {
                item = ci
                ib.tvCompName.text = ci.shortName
                ib.tvCompType.text = ci.typeLabel
                ib.cbProtect.isChecked = Prefs.isComponentKept(this@ServiceListActivity, ci.flat)

                // 启动按钮：有 LaunchIntent 直接显示；Service 无 LaunchIntent 时，
                // 无 Root 隐藏按钮，有 Root 显示并可用 shell 拉起。
                val hasLaunch = ci.launchIntent != null
                val hasRoot = Privilege.hasRoot()
                ib.btnLaunch.visibility =
                    if (hasLaunch) android.view.View.VISIBLE
                    else if (hasRoot) android.view.View.VISIBLE
                    else android.view.View.GONE
            }
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder =
            Holder(ItemServiceComponentBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) =
            holder.bind(items[position])

        override fun getItemCount(): Int = items.size
    }

    /** 启动单个组件：Activity 用 startActivity；Service 用 Root/Shizuku shell 拉起。 */
    private fun launchComponent(ci: CompItem) {
        try {
            ci.launchIntent?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(it)
                return
            }
            // Service：无普通启动权限，走 shell
            if (Privilege.hasRoot() || Privilege.bestChannel(this) != Privilege.Channel.NONE) {
                val flat = ci.flat
                // 优先 start-foreground-service（Android 8+ 后台限制），失败再 startservice
                Privilege.execShell(this, "am", "start-foreground-service", "-n", flat)
                    ?: Privilege.execShell(this, "am", "startservice", "-n", flat)
                Toast.makeText(this, R.string.app_state_relaunched, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.app_state_relaunch_failed, Toast.LENGTH_SHORT).show()
            }
        } catch (t: Throwable) {
            Toast.makeText(this, R.string.app_state_relaunch_failed, Toast.LENGTH_SHORT).show()
        }
    }
}

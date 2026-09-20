package com.acsmanager.pro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.acsmanager.pro.R
import com.acsmanager.pro.core.PermGroups
import com.acsmanager.pro.core.Privilege
import com.acsmanager.pro.databinding.ActivityPermDetailBinding
import com.acsmanager.pro.ui.adapter.PermDetailAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 权限详情页：展示目标应用全部危险权限组与授予状态，
 * 开关直接执行 pm grant/revoke（系统级，Root/Shizuku 通道）；
 * 无 shell 通道时展示 ADB 命令并支持一键复制。
 */
class PermissionDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
    }

    private lateinit var binding: ActivityPermDetailBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var adapter: PermDetailAdapter

    private var pkg: String = ""
    private var label: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        pkg = intent.getStringExtra(EXTRA_PKG) ?: return finish()
        label = intent.getStringExtra(EXTRA_LABEL) ?: pkg
        binding.toolbar.title = label

        adapter = PermDetailAdapter(
            onToggle = { perm, grant -> togglePerm(perm, grant) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnCopyAdb.setOnClickListener { copyAdbCommands() }
        refresh()
    }

    private fun refresh() {
        val shellChannel = Privilege.bestChannel(this) in
            setOf(Privilege.Channel.ROOT, Privilege.Channel.SHIZUKU)
        binding.tvBanner.text = getString(
            if (shellChannel) R.string.permdetail_banner_shell else R.string.permdetail_banner_none
        )
        binding.btnCopyAdb.visibility = if (shellChannel) android.view.View.GONE else android.view.View.VISIBLE

        scope.launch {
            val (groups, granted) = withContext(Dispatchers.IO) {
                val g = PermGroups.groupsForSdk()
                val gr = if (shellChannel) PermGroups.queryGranted(this@PermissionDetailActivity, pkg) else null
                g to gr
            }
            adapter.submit(groups, granted ?: emptySet(), shellChannel, pkg)
            if (!shellChannel) {
                binding.tvBanner.text = getString(R.string.permdetail_banner_none)
            }
        }
    }

    private fun togglePerm(perm: String, grant: Boolean) {
        val ctx = this
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                PermGroups.setGranted(ctx, pkg, perm, grant)
            }
            Toast.makeText(
                ctx,
                getString(
                    if (ok) R.string.permdetail_toggle_ok else R.string.permdetail_toggle_fail,
                    PermGroups.shortName(perm)
                ),
                Toast.LENGTH_SHORT
            ).show()
            refresh()
        }
    }

    private fun copyAdbCommands() {
        // 合并成一条命令：adb shell "pm grant ... && pm grant ... && ..."
        val perms = PermGroups.groupsForSdk().flatMap { it.perms.map { p -> p.permission } }
        val cmd = PermGroups.adbCommandCombined(pkg, perms, true)
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("adb", cmd))
        Toast.makeText(this, R.string.permdetail_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

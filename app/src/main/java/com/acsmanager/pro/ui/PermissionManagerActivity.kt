package com.acsmanager.pro.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.acsmanager.pro.R
import com.acsmanager.pro.core.Privilege
import com.acsmanager.pro.databinding.ActivityPermManagerBinding
import com.acsmanager.pro.keepalive.AppsRepository
import com.acsmanager.pro.ui.adapter.PermAppAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 权限管理器（类似权限狗）：拉取全机用户/系统软件列表，
 * 点开软件后可直接修改系统级权限（Root / Shizuku / ADB 执行，附状态展示）。
 */
class PermissionManagerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPermManagerBinding
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var adapter: PermAppAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPermManagerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = PermAppAdapter { entry ->
            startActivity(
                Intent(this, PermissionDetailActivity::class.java)
                    .putExtra(PermissionDetailActivity.EXTRA_PKG, entry.packageName)
                    .putExtra(PermissionDetailActivity.EXTRA_LABEL, entry.label)
            )
        }
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        refreshChannel()
        scope.launch {
            val apps = withContext(Dispatchers.IO) {
                AppsRepository.scan(applicationContext, includeSystem = true)
            }
            adapter.submit(apps)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshChannel()
    }

    private fun refreshChannel() {
        val ch = Privilege.bestChannel(this)
        binding.tvBanner.text = when (ch) {
            Privilege.Channel.NONE -> getString(R.string.permmgr_banner_none)
            Privilege.Channel.APP_GRANTED -> getString(R.string.permmgr_banner_app)
            else -> getString(R.string.permmgr_banner_shell, getString(ch.labelRes))
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

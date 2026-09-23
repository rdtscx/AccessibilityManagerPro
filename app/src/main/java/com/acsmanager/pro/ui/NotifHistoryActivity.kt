package com.acsmanager.pro.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.acsmanager.pro.R
import com.acsmanager.pro.databinding.ActivityNotifHistoryBinding
import com.acsmanager.pro.databinding.ItemNotifHistoryBinding
import com.acsmanager.pro.keepalive.AppsRepository
import com.acsmanager.pro.notif.NotifGateService
import com.acsmanager.pro.util.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 通知拦截历史二级页：记录所有收到的通知（应用/渠道/标题/正文/时间）。
 * 点击某条弹出菜单：
 *  - 启动：跳到该应用主界面（近似点击通知的效果）；
 *  - 隐藏：把该 pkg+channelId 加入隐藏名单，以后同渠道通知直接取消；
 *  - 调整级别：0~5 级，仅对该应用该渠道后续通知生效（单渠道级别优先于应用整体级别）。
 */
class NotifHistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNotifHistoryBinding
    private val adapter = HistoryAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNotifHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 主题为 NoActionBar，使用布局内的 MaterialToolbar 作为 ActionBar（与其他二级页统一）
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setTitle(R.string.notif_history_title)
        }
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        reload()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.menu_notif_history, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.action_clear_all) {
            confirmClearAll()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** 清空全部：弹确认，确认后清空历史并刷新（原顶部悬浮按钮已移入工具栏菜单）。 */
    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setMessage(R.string.notif_clear_all_confirm)
            .setPositiveButton(R.string.ok) { _, _ ->
                Prefs.clearNotifHistoryFull(this)
                Toast.makeText(this, R.string.notif_clear_all_done, Toast.LENGTH_SHORT).show()
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun reload() {
        val arr = Prefs.notifHistoryFull(this)
        // 倒序显示（最新在前）
        val list = ArrayList<JSONObject>(arr.length())
        for (i in arr.length() - 1 downTo 0) {
            arr.optJSONObject(i)?.let { list.add(it) }
        }
        adapter.submit(list)
    }

    inner class HistoryAdapter :
        androidx.recyclerview.widget.RecyclerView.Adapter<HistoryAdapter.Holder>() {

        private var items: List<JSONObject> = emptyList()

        fun submit(list: List<JSONObject>) {
            items = list
            notifyDataSetChanged()
        }

        inner class Holder(val ib: ItemNotifHistoryBinding) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(ib.root) {

            init {
                ib.root.setOnClickListener {
                    val o = items.getOrNull(bindingAdapterPosition) ?: return@setOnClickListener
                    showActions(o)
                }
            }

            fun bind(o: JSONObject) {
                val pkg = o.optString("p", "")
                val ctx = ib.root.context
                ib.tvAppLabel.text = AppsRepository.labelOf(ctx, pkg) ?: pkg
                ib.ivIcon.setImageDrawable(AppsRepository.icon(ctx, pkg))
                ib.tvChannel.text = o.optString("c", "")
                ib.tvTitle.text = o.optString("ti", "")
                ib.tvText.text = o.optString("tx", "")
                val t = o.optLong("t", 0L)
                ib.tvTime.text = if (t > 0) DateUtils.getRelativeTimeSpanString(t) else ""
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(ItemNotifHistoryBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

        override fun getItemCount(): Int = items.size
    }

    /** 点击某条通知：弹启动/隐藏/调整级别菜单。 */
    private fun showActions(o: JSONObject) {
        val pkg = o.optString("p", "")
        val channelId = o.optString("c", "")
        val label = AppsRepository.labelOf(this, pkg) ?: pkg
        val items = arrayOf(
            getString(R.string.notif_act_launch),
            getString(R.string.notif_act_hide),
            getString(R.string.notif_act_set_level)
        )
        AlertDialog.Builder(this)
            .setTitle(label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> launchApp(pkg)
                    1 -> hideChannel(pkg, channelId, label)
                    2 -> pickChannelLevel(pkg, channelId, label)
                }
            }
            .show()
    }

    /** 启动：跳到该应用主界面（近似点击通知）。 */
    private fun launchApp(pkg: String) {
        val intent = AppsRepository.launchIntent(this, pkg) ?: run {
            Toast.makeText(this, R.string.app_state_relaunch_failed, Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(intent)
    }

    /** 隐藏：把该渠道加入隐藏名单。 */
    private fun hideChannel(pkg: String, channelId: String, label: String) {
        Prefs.setChannelHidden(this, pkg, channelId, true)
        Toast.makeText(this, getString(R.string.notif_hidden_toast, label), Toast.LENGTH_SHORT).show()
    }

    /** 调整级别：0~5 级 SeekBar，仅对该 pkg+channelId 生效。 */
    private fun pickChannelLevel(pkg: String, channelId: String, label: String) {
        val current = Prefs.notifChannelLevel(this, pkg, channelId).coerceAtLeast(3)
        val container = layoutInflater.inflate(R.layout.dialog_level_picker, null)
        val seek = container.findViewById<SeekBar>(R.id.seek_level)
        val tv = container.findViewById<TextView>(R.id.tv_level_label)
        seek.max = 5
        seek.progress = current
        tv.text = getString(NotifGateService.levelLabelRes(current))
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                tv.text = getString(NotifGateService.levelLabelRes(p))
            }
            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.notif_set_level_title, label))
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                Prefs.setNotifChannelLevel(this, pkg, channelId, seek.progress)
                Toast.makeText(this,
                    getString(R.string.notif_level_saved, getString(NotifGateService.levelLabelRes(seek.progress))),
                    Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}

package com.acsmanager.pro.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.acsmanager.pro.R
import com.acsmanager.pro.databinding.ItemAppKeepaliveBinding
import com.acsmanager.pro.keepalive.AppEntry
import com.acsmanager.pro.keepalive.AppsRepository
import com.acsmanager.pro.keepalive.KeepAliveEngine
import com.acsmanager.pro.util.Prefs

/**
 * 保活应用列表适配器：勾选加入保活名单 + 单点"立即拉起"测试。
 */
class KeepAliveAdapter(
    private val onProtectChanged: () -> Unit
) : RecyclerView.Adapter<KeepAliveAdapter.Holder>() {

    private var items: List<AppEntry> = emptyList()
    private var protectedSet: Set<String> = emptySet()

    fun submit(list: List<AppEntry>, protected: Set<String>) {
        items = list
        protectedSet = protected
        notifyDataSetChanged()
    }

    inner class Holder(val binding: ItemAppKeepaliveBinding) : RecyclerView.ViewHolder(binding.root) {
        private var pkg: String? = null

        init {
            // 点击条目（卡片任意位置，除按钮/勾选框外）：进入二级服务列表
            binding.root.setOnClickListener {
                val p = pkg ?: return@setOnClickListener
                val ctx = binding.root.context
                val intent = android.content.Intent(ctx,
                    com.acsmanager.pro.ui.ServiceListActivity::class.java)
                intent.putExtra(com.acsmanager.pro.ui.ServiceListActivity.EXTRA_PACKAGE, p)
                ctx.startActivity(intent)
            }
            binding.btnRelaunch.setOnClickListener {
                val p = pkg ?: return@setOnClickListener
                val ok = KeepAliveEngine.manualRelaunch(binding.root.context, p)
                binding.tvState.text = binding.root.context.getString(
                    if (ok) R.string.app_state_relaunched else R.string.app_state_relaunch_failed
                )
                binding.tvState.setTextColor(
                    ContextCompat.getColor(
                        binding.root.context,
                        if (ok) R.color.status_ok else R.color.status_err
                    )
                )
            }
            binding.cbProtect.setOnCheckedChangeListener { _, checked ->
                val p = pkg ?: return@setOnCheckedChangeListener
                val ctx = binding.root.context
                val set = Prefs.keepAliveApps(ctx).toMutableSet()
                if (checked) set.add(p) else set.remove(p)
                Prefs.setKeepAliveApps(ctx, set)
                KeepAliveEngine.reloadTargets(ctx)
                onProtectChanged()
            }
        }

        fun bind(entry: AppEntry) {
            pkg = entry.packageName
            val ctx = binding.root.context
            binding.tvLabel.text = entry.label
            binding.tvPkg.text = entry.packageName + if (entry.isSystem) " · 系统" else ""
            binding.ivIcon.setImageDrawable(AppsRepository.icon(ctx, entry.packageName))
            binding.cbProtect.isChecked = entry.packageName in protectedSet
            binding.btnRelaunch.isEnabled = entry.hasLauncher

            // 已保活：绿色圆点 + "已保活"绿色文字；未保活：正常状态点（前台/运行/已停止）
            val isKept = entry.packageName in protectedSet
            binding.dotKept.visibility = if (isKept) android.view.View.VISIBLE else android.view.View.GONE
            if (isKept) {
                binding.tvState.text = ctx.getString(R.string.keepalive_status_kept)
                binding.tvState.setTextColor(ContextCompat.getColor(ctx, R.color.status_ok))
                // 绿色圆点呼吸动画（精细动作反馈，色系不变）
                binding.dotKept.animate().cancel()
                binding.dotKept.alpha = 1f
                val pulse = android.animation.ObjectAnimator.ofFloat(
                    binding.dotKept, "alpha", 1f, 0.35f
                )
                pulse.duration = 900L
                pulse.repeatCount = android.view.animation.Animation.INFINITE
                pulse.repeatMode = android.view.animation.Animation.REVERSE
                pulse.start()
            } else {
                binding.dotKept.animate().cancel()
                binding.dotKept.alpha = 1f
                val fg = KeepAliveEngine.lastForeground()
                val state = when {
                    fg == entry.packageName -> R.string.app_state_foreground
                    KeepAliveEngine.isTargetAlive(ctx, entry.packageName) -> R.string.app_state_running
                    else -> R.string.app_state_stopped
                }
                binding.tvState.text = ctx.getString(state)
                binding.tvState.setTextColor(
                    ContextCompat.getColor(
                        ctx,
                        when {
                            fg == entry.packageName -> R.color.status_ok
                            state != R.string.app_state_stopped -> R.color.status_warn
                            else -> R.color.status_err
                        }
                    )
                )
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemAppKeepaliveBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}

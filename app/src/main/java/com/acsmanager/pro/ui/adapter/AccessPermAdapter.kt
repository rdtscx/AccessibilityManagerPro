package com.acsmanager.pro.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.acsmanager.pro.R
import com.acsmanager.pro.core.AccessServiceItem
import com.acsmanager.pro.databinding.ItemAccessPermBinding
import com.acsmanager.pro.keepalive.AppsRepository

/**
 * 应用无障碍权限列表适配器（图2 风格：图标 + 应用名 + 服务描述 + 锁定按钮 + 开关）。
 * 开关回写修复：bind 时先移除监听器再设置状态，避免 setChecked 触发 onToggle 造成 Toast 循环。
 */
class AccessPermAdapter(
    private val onToggle: (AccessServiceItem, Boolean) -> Unit,
    private val onLock: (AccessServiceItem) -> Unit,
    private val onItemClick: (AccessServiceItem) -> Unit
) : RecyclerView.Adapter<AccessPermAdapter.Holder>() {

    private var items: List<AccessServiceItem> = emptyList()
    private var lockedSet: Set<String> = emptySet()

    fun submit(list: List<AccessServiceItem>, locked: Set<String>) {
        items = list
        lockedSet = locked
        notifyDataSetChanged()
    }

    inner class Holder(val binding: ItemAccessPermBinding) : RecyclerView.ViewHolder(binding.root) {
        private var item: AccessServiceItem? = null

        init {
            binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
                val it = item ?: return@setOnCheckedChangeListener
                onToggle(it, checked)
            }
            binding.btnLock.setOnClickListener {
                val it = item ?: return@setOnClickListener
                onLock(it)
            }
            binding.root.setOnClickListener {
                val it = item ?: return@setOnClickListener
                onItemClick(it)
            }
        }

        fun bind(it: AccessServiceItem) {
            item = it
            val ctx = binding.root.context
            binding.ivIcon.setImageDrawable(AppsRepository.icon(ctx, it.packageName))
            binding.tvLabel.text = it.label

            // 服务描述：优先取系统声明描述，其次服务类名；无描述时提示（图2 风格）
            val desc = it.description?.takeIf { d -> d.isNotBlank() }
            binding.tvDesc.text = if (desc != null) desc
            else getString(ctx, R.string.accperm_no_desc)

            val tag = if (it.packageName == ctx.packageName) getString(ctx, R.string.accperm_self_tag)
            else if (isSystemApp(ctx, it.packageName)) getString(ctx, R.string.accperm_system_tag)
            else getString(ctx, R.string.accperm_user_tag)
            binding.tvPkg.text = ctx.getString(
                R.string.accperm_pkg_tag,
                it.packageName,
                tag
            )

            // 关键修复：先解绑监听器再设置状态，防止 setChecked 触发 onToggle 反复弹 Toast
            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = it.enabled
            binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
                val cur = item ?: return@setOnCheckedChangeListener
                onToggle(cur, checked)
            }

            val locked = it.flatten in lockedSet
            // 锁定 = 实心亮锁（绿色）；未锁定 = 空心灭灯锁（灰色），观感统一为"锁"形按钮
            binding.btnLock.setIconResource(if (locked) R.drawable.ic_lock else R.drawable.ic_lock_outline)
            binding.btnLock.setIconTint(
                ContextCompat.getColorStateList(
                    ctx,
                    if (locked) R.color.status_ok else R.color.status_muted
                )
            )
            binding.tvLockHint.setTextColor(
                ContextCompat.getColor(ctx, if (locked) R.color.status_ok else R.color.status_muted)
            )
            binding.tvLockHint.text = getString(
                ctx,
                if (locked) R.string.accperm_locked_hint else R.string.accperm_lock_hint
            )
        }

        private fun getString(ctx: android.content.Context, res: Int): String = ctx.getString(res)

        private fun isSystemApp(ctx: android.content.Context, pkg: String): Boolean = try {
            val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
            (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (t: Throwable) {
            false
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemAccessPermBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}

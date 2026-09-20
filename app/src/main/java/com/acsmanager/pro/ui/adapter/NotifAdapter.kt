package com.acsmanager.pro.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.acsmanager.pro.R
import com.acsmanager.pro.databinding.ItemAppNotifBinding
import com.acsmanager.pro.keepalive.AppEntry
import com.acsmanager.pro.keepalive.AppsRepository
import com.acsmanager.pro.notif.NotifGateService
import com.acsmanager.pro.util.Prefs

/**
 * 通知 0-5 级调控列表适配器：滑杆调节每款软件的通知等级并即时生效。
 */
class NotifAdapter : RecyclerView.Adapter<NotifAdapter.Holder>() {

    private var items: List<AppEntry> = emptyList()

    fun submit(list: List<AppEntry>) {
        items = list
        notifyDataSetChanged()
    }

    inner class Holder(val binding: ItemAppNotifBinding) : RecyclerView.ViewHolder(binding.root) {
        private var pkg: String? = null

        init {
            binding.slider.addOnChangeListener { _, value, _ ->
                val p = pkg ?: return@addOnChangeListener
                val level = value.toInt()
                // 滑回 3（系统默认）时从记录里移除该包，保持"已设置"只列真正改过的应用
                if (level == 3) {
                    Prefs.resetNotifLevel(binding.root.context, p)
                } else {
                    Prefs.setNotifLevel(binding.root.context, p, level)
                }
                binding.tvLevel.text = binding.root.context.getString(NotifGateService.levelLabelRes(level))
                binding.tvDesc.text = binding.root.context.getString(NotifGateService.levelDescRes(level))
            }
        }

        fun bind(entry: AppEntry) {
            pkg = entry.packageName
            val ctx = binding.root.context
            binding.tvLabel.text = entry.label
            binding.tvPkg.text = entry.packageName + if (entry.isSystem) " · 系统" else ""
            binding.ivIcon.setImageDrawable(AppsRepository.icon(ctx, entry.packageName))
            val level = Prefs.notifLevel(ctx, entry.packageName)
            binding.slider.value = level.toFloat()
            binding.tvLevel.text = ctx.getString(NotifGateService.levelLabelRes(level))
            binding.tvDesc.text = ctx.getString(NotifGateService.levelDescRes(level))
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemAppNotifBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}

package com.acsmanager.pro.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.acsmanager.pro.databinding.ItemPermAppBinding
import com.acsmanager.pro.keepalive.AppEntry
import com.acsmanager.pro.keepalive.AppsRepository

/**
 * 权限管理器应用列表适配器：全机用户/系统软件，点击进入权限详情。
 */
class PermAppAdapter(
    private val onClick: (AppEntry) -> Unit
) : RecyclerView.Adapter<PermAppAdapter.Holder>() {

    private var items: List<AppEntry> = emptyList()

    fun submit(list: List<AppEntry>) {
        items = list
        notifyDataSetChanged()
    }

    inner class Holder(val binding: ItemPermAppBinding) : RecyclerView.ViewHolder(binding.root) {
        private var entry: AppEntry? = null

        init {
            binding.root.setOnClickListener {
                val e = entry ?: return@setOnClickListener
                onClick(e)
            }
        }

        fun bind(e: AppEntry) {
            entry = e
            val ctx = binding.root.context
            binding.ivIcon.setImageDrawable(AppsRepository.icon(ctx, e.packageName))
            binding.tvLabel.text = e.label
            binding.tvPkg.text = e.packageName + if (e.isSystem) " · ${ctx.getString(com.acsmanager.pro.R.string.accperm_system_tag)}" else ""
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemPermAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}

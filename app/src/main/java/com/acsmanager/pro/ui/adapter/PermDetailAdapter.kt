package com.acsmanager.pro.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.acsmanager.pro.R
import com.acsmanager.pro.core.PermGroups
import com.acsmanager.pro.databinding.ItemPermDetailBinding
import com.acsmanager.pro.databinding.ItemPermHeaderBinding

/**
 * 权限详情适配器：分组头 + 权限行（授予状态开关；无 shell 通道时禁用并显示 ADB 指引）。
 */
class PermDetailAdapter(
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed class Row {
        data class Header(val group: PermGroups.Group) : Row()
        data class Perm(
            val def: PermGroups.PermDef,
            val granted: Boolean
        ) : Row()
    }

    private var rows: List<Row> = emptyList()
    private var shellChannel = false
    private var pkg: String = ""

    fun submit(groups: List<PermGroups.Group>, granted: Set<String>, shell: Boolean, pkgName: String) {
        shellChannel = shell
        pkg = pkgName
        rows = groups.flatMap { g ->
            listOf(Row.Header(g)) + g.perms.map { Row.Perm(it, it.permission in granted) }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_PERM

    inner class HeaderHolder(val binding: ItemPermHeaderBinding) : RecyclerView.ViewHolder(binding.root)
    inner class PermHolder(val binding: ItemPermDetailBinding) : RecyclerView.ViewHolder(binding.root) {
        private var perm: String? = null

        init {
            binding.switchGrant.setOnCheckedChangeListener { _, checked ->
                val p = perm ?: return@setOnCheckedChangeListener
                onToggle(p, checked)
            }
        }

        fun bind(row: Row.Perm) {
            perm = row.def.permission
            val ctx = binding.root.context
            binding.tvName.text = PermGroups.shortName(row.def.permission)
            binding.tvFull.text = row.def.permission
            binding.switchGrant.isChecked = row.granted
            binding.switchGrant.isEnabled = shellChannel
            binding.tvState.text = ctx.getString(
                if (row.granted) R.string.permdetail_granted else R.string.permdetail_not_granted
            )
            binding.tvState.setTextColor(
                ContextCompat.getColor(
                    ctx,
                    if (row.granted) R.color.status_ok else R.color.status_err
                )
            )
            binding.tvAdb.visibility = if (shellChannel) View.GONE else View.VISIBLE
            binding.tvAdb.text = PermGroups.adbCommand(
                pkg, row.def.permission, true
            ).removePrefix("adb shell ") + " · " + ctx.getString(R.string.permdetail_adb_hint)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemPermHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        } else {
            PermHolder(ItemPermDetailBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).binding.tvGroup.text = row.group.label
            is Row.Perm -> (holder as PermHolder).bind(row)
        }
    }

    override fun getItemCount(): Int = rows.size

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_PERM = 1
    }
}

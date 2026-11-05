package com.acsmanager.pro.ui.adapter

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.acsmanager.pro.R
import com.acsmanager.pro.databinding.ItemEventBinding
import com.acsmanager.pro.watchdog.EventLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventAdapter(
    private val context: Context
) : RecyclerView.Adapter<EventAdapter.VH>() {

    private val items = mutableListOf<EventLog.Entry>()
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    /** 包名 → 应用名称缓存，避免 onBind 中反复查询 PackageManager。 */
    private val labelCache = mutableMapOf<String, String>()

    fun submit(list: List<EventLog.Entry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    /**
     * 解析事件 pkg 字段的显示文本：
     *  - 如果 pkg 是有效的应用包名，返回"应用名称 (包名)"格式；
     *  - 如果是特殊标记（如 "system"）或无法解析，直接返回原值。
     */
    private fun resolveDisplayPkg(pkg: String): String {
        if (pkg == "system" || pkg == context.packageName) return pkg
        labelCache[pkg]?.let { return it }
        val label = try {
            val ai = context.packageManager.getApplicationInfo(pkg, 0)
            val name = context.packageManager.getApplicationLabel(ai).toString()
            if (name.isNotBlank() && name != pkg) "$name ($pkg)" else pkg
        } catch (t: Throwable) {
            pkg
        }
        labelCache[pkg] = label
        return label
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    inner class VH(private val binding: ItemEventBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(e: EventLog.Entry) {
            val (typeText, iconRes, color) = when (e.type) {
                EventLog.TYPE_ENABLE -> Triple(
                    context.getString(R.string.event_enable), R.drawable.ic_check, R.color.status_ok
                )
                EventLog.TYPE_DISABLE -> Triple(
                    context.getString(R.string.event_disable), R.drawable.ic_restore, R.color.status_warn
                )
                EventLog.TYPE_LOST -> Triple(
                    context.getString(R.string.event_lost), R.drawable.ic_error, R.color.status_err
                )
                EventLog.TYPE_RESTORED -> Triple(
                    context.getString(R.string.event_restored), R.drawable.ic_watchdog, R.color.status_ok
                )
                EventLog.TYPE_WARN -> Triple(
                    context.getString(R.string.event_warn), R.drawable.ic_error, R.color.status_warn
                )
                else -> Triple(
                    context.getString(R.string.event_service), R.drawable.ic_services, R.color.status_ok
                )
            }
            binding.typeText.text = typeText
            binding.typeText.setTextColor(ContextCompat.getColor(context, color))
            binding.icon.setImageResource(iconRes)
            binding.icon.setColorFilter(ContextCompat.getColor(context, color))
            binding.timeText.text = timeFmt.format(Date(e.ts))
            binding.pkgText.text = resolveDisplayPkg(e.pkg)
            binding.detailText.text = e.detail
        }
    }
}

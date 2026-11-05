package com.acsmanager.pro.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.acsmanager.pro.R
import com.acsmanager.pro.core.AccessServiceItem
import com.acsmanager.pro.databinding.ItemServiceBinding

/**
 * 服务列表适配器：图标 + 名称 + 包名 + 能力徽章 + 启停开关。
 */
class ServiceAdapter(
    private val onItemClick: (AccessServiceItem) -> Unit,
    private val onToggle: (AccessServiceItem, Boolean) -> Unit
) : RecyclerView.Adapter<ServiceAdapter.VH>() {

    private val all = mutableListOf<AccessServiceItem>()
    private val shown = mutableListOf<AccessServiceItem>()

    fun submit(list: List<AccessServiceItem>) {
        all.clear()
        all.addAll(list)
        applyFilter("")
    }

    fun applyFilter(query: String) {
        val q = query.trim().lowercase()
        shown.clear()
        if (q.isEmpty()) {
            shown.addAll(all)
        } else {
            shown.addAll(all.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            })
        }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemServiceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun getItemCount(): Int = shown.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(shown[position])
    }

    inner class VH(private val binding: ItemServiceBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AccessServiceItem) {
            val ctx = binding.root.context
            binding.icon.setImageDrawable(
                item.component.let { cn ->
                    try {
                        ctx.packageManager.getApplicationIcon(cn.packageName)
                    } catch (t: Throwable) {
                        null
                    }
                }
            )
            binding.label.text = item.label
            binding.pkg.text = item.packageName

            binding.badgesRow.removeAllViews()
            val caps = capabilitiesOf(item)
            for (i in caps.indices) {
                if (i == 3 && caps.size > 4) {
                    binding.badgesRow.addView(buildChip(ctx, R.drawable.ic_other, "+${caps.size - 3}"))
                    break
                }
                val (icon, text) = caps[i]
                binding.badgesRow.addView(buildChip(ctx, icon, ctx.getString(text)))
            }

            // 避免 setChecked 触发监听
            binding.enableSwitch.setOnCheckedChangeListener(null)
            binding.enableSwitch.isChecked = item.enabled
            binding.enableSwitch.setOnClickListener {
                val newState = binding.enableSwitch.isChecked
                onToggle(item, newState)
            }

            binding.root.setOnClickListener { onItemClick(item) }
        }
    }

    private fun buildChip(ctx: android.content.Context, iconRes: Int, text: String): View {
        val chip = LinearLayout(ctx)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = android.view.Gravity.CENTER_VERTICAL
        chip.setPadding(dp(ctx, 8), dp(ctx, 2), dp(ctx, 8), dp(ctx, 2))
        chip.setBackgroundResource(R.drawable.badge_chip)
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.marginEnd = dp(ctx, 4)
        lp.topMargin = dp(ctx, 2)
        chip.layoutParams = lp

        val iv = ImageView(ctx)
        iv.setImageResource(iconRes)
        iv.setColorFilter(ContextCompat.getColor(ctx, R.color.chip_text))
        val ilp = LinearLayout.LayoutParams(dp(ctx, 12), dp(ctx, 12))
        chip.addView(iv, ilp)

        val tv = TextView(ctx)
        tv.text = text
        tv.textSize = 11f
        tv.setTextColor(ContextCompat.getColor(ctx, R.color.chip_text))
        tv.includeFontPadding = false
        val tlp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        tlp.marginStart = dp(ctx, 3)
        chip.addView(tv, tlp)
        return chip
    }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    private fun capabilitiesOf(item: AccessServiceItem): List<Pair<Int, Int>> {
        val list = mutableListOf<Pair<Int, Int>>()
        val cap = item.capabilities
        if (cap and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT != 0)
            list.add(R.drawable.ic_eye to R.string.cap_read_screen)
        if (cap and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0)
            list.add(R.drawable.ic_gesture to R.string.cap_gestures)
        if (cap and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS != 0)
            list.add(R.drawable.ic_key to R.string.cap_keys)
        if (android.os.Build.VERSION.SDK_INT >= 30 &&
            cap and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
        )
            list.add(R.drawable.ic_screenshot to R.string.cap_screenshot)
        if (cap and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_CONTROL_MAGNIFICATION != 0)
            list.add(R.drawable.ic_magnify to R.string.cap_magnify)
        if (cap and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_TOUCH_EXPLORATION != 0)
            list.add(R.drawable.ic_gesture to R.string.cap_touch)
        return list
    }
}

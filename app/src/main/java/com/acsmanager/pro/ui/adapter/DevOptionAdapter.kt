package com.acsmanager.pro.ui.adapter

import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.acsmanager.pro.R
import com.acsmanager.pro.core.Privilege
import com.acsmanager.pro.databinding.ItemDevOptionBinding
import com.acsmanager.pro.dev.DevOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * 开发者选项映射列表适配器：布尔项用开关、数值项用滑杆，直写 Settings 并永久生效。
 */
class DevOptionAdapter : RecyclerView.Adapter<DevOptionAdapter.Holder>() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var items: List<DevOptions.Option> = DevOptions.OPTIONS

    private var writing = false

    inner class Holder(val binding: ItemDevOptionBinding) : RecyclerView.ViewHolder(binding.root) {
        private var option: DevOptions.Option? = null

        init {
            binding.switchValue.setOnCheckedChangeListener { _, checked ->
                val o = option ?: return@setOnCheckedChangeListener
                applyBool(o, checked)
            }
            binding.slider.addOnChangeListener { _, value, fromUser ->
                if (!fromUser) return@addOnChangeListener
                val o = option ?: return@addOnChangeListener
                binding.tvValue.text = String.format(Locale.US, "%.1f", value)
                applyFloat(o, value)
            }
        }

        private fun applyBool(o: DevOptions.Option, value: Boolean) {
            val ctx = binding.root.context
            if (writing) return
            writing = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { DevOptions.writeBool(ctx, o, value) }
                writing = false
                showResult(r)
            }
        }

        private fun applyFloat(o: DevOptions.Option, value: Float) {
            val ctx = binding.root.context
            if (writing) return
            writing = true
            scope.launch {
                val r = withContext(Dispatchers.IO) { DevOptions.writeFloat(ctx, o, value) }
                writing = false
                showResult(r)
            }
        }

        private fun showResult(r: DevOptions.WriteResult) {
            val ctx = binding.root.context
            if (r.ok) {
                binding.tvStatus.text = ctx.getString(
                    R.string.dev_write_ok,
                    channelLabel(ctx, r.channel)
                )
                Toast.makeText(ctx, ctx.getString(R.string.dev_saved), Toast.LENGTH_SHORT).show()
            } else {
                binding.tvStatus.text = ctx.getString(R.string.dev_write_fail)
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.dev_write_fail_toast, ctx.getString(R.string.status_go_guide)),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        fun bind(o: DevOptions.Option) {
            option = o
            val ctx = binding.root.context
            binding.tvTitle.text = ctx.getString(o.labelRes)
            binding.tvDesc.text = ctx.getString(o.descRes)

            if (o.type == DevOptions.Type.BOOL) {
                binding.rowSwitch.visibility = ViewGroup.VISIBLE
                binding.slider.visibility = ViewGroup.GONE
                binding.tvValue.text = ctx.getString(
                    if (DevOptions.readBool(ctx, o)) R.string.dev_on else R.string.dev_off
                )
                binding.switchValue.isChecked = DevOptions.readBool(ctx, o)
            } else {
                binding.rowSwitch.visibility = ViewGroup.GONE
                binding.slider.visibility = ViewGroup.VISIBLE
                val v = DevOptions.readFloat(ctx, o)
                binding.slider.valueFrom = 0f
                binding.slider.valueTo = 10f
                binding.slider.stepSize = 0.5f
                binding.slider.value = v.coerceIn(0f, 10f)
                binding.tvValue.text = String.format(Locale.US, "%.1f", v)
            }
            binding.tvStatus.text = when (o.type) {
                DevOptions.Type.BOOL ->
                    ctx.getString(
                        if (DevOptions.readBool(ctx, o)) R.string.dev_on else R.string.dev_off
                    )
                DevOptions.Type.FLOAT ->
                    ctx.getString(R.string.dev_current, String.format(Locale.US, "%.1f", DevOptions.readFloat(ctx, o)))
            }
        }
    }

    private fun channelLabel(ctx: android.content.Context, ch: Privilege.Channel): String =
        ctx.getString(ch.labelRes)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemDevOptionBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}

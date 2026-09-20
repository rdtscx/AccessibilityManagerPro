package com.acsmanager.pro.ui.adapter

import android.widget.SeekBar
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
 * 用标准 Android 控件（Switch + SeekBar），避免 Material Slider 在部分设备上的兼容问题。
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
            binding.slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val o = option ?: return
                    val v = progress / 2f // 0~20 → 0.0~10.0，步进 0.5
                    binding.tvValue.text = String.format(Locale.US, "%.1f", v)
                    applyFloat(o, v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
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
            try {
                binding.tvTitle.text = ctx.getString(o.labelRes)
                binding.tvDesc.text = ctx.getString(o.descRes)

                if (o.type == DevOptions.Type.BOOL) {
                    binding.rowSwitch.visibility = android.view.View.VISIBLE
                    binding.slider.visibility = android.view.View.GONE
                    val v = DevOptions.readBool(ctx, o)
                    binding.tvValue.text = ctx.getString(if (v) R.string.dev_on else R.string.dev_off)
                    binding.switchValue.setOnCheckedChangeListener(null)
                    binding.switchValue.isChecked = v
                    binding.switchValue.setOnCheckedChangeListener { _, checked ->
                        val oo = option ?: return@setOnCheckedChangeListener
                        applyBool(oo, checked)
                    }
                } else {
                    binding.rowSwitch.visibility = android.view.View.GONE
                    binding.slider.visibility = android.view.View.VISIBLE
                    val v = DevOptions.readFloat(ctx, o).coerceIn(0f, 10f)
                    binding.slider.max = 20
                    binding.slider.progress = (v * 2).toInt() // 0~10 → 0~20
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
            } catch (t: Throwable) {
                binding.tvTitle.text = ctx.getString(o.labelRes)
                binding.tvStatus.text = "读取失败: ${t.message}"
            }
        }
    }

    private fun channelLabel(ctx: android.content.Context, ch: Privilege.Channel): String =
        ctx.getString(ch.labelRes)

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder =
        Holder(ItemDevOptionBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}

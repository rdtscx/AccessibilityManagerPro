package com.acsmanager.pro.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.acsmanager.pro.R
import com.acsmanager.pro.core.Privilege
import com.acsmanager.pro.databinding.FragmentDevBinding
import com.acsmanager.pro.dev.DevOptions
import com.acsmanager.pro.ui.adapter.DevOptionAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 开发者选项映射页：不跳转系统开发者选项，直读直写 Settings 键值并永久生效。
 */
class DevFragment : Fragment() {

    private var _binding: FragmentDevBinding? = null
    private val binding get() = _binding!!

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val adapter = DevOptionAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return try {
            _binding = FragmentDevBinding.inflate(inflater, container, false)
            binding.root
        } catch (t: Throwable) {
            android.widget.Toast.makeText(
                requireContext(),
                "开发者页面加载失败: ${t.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
            android.widget.FrameLayout(requireContext()).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        try {
            binding.recycler.layoutManager = LinearLayoutManager(requireContext())
            binding.recycler.adapter = adapter
        } catch (t: Throwable) {
            android.widget.Toast.makeText(
                requireContext(),
                "开发者页面初始化失败: ${t.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        requireActivity().title = getString(R.string.tab_dev)
        // 通道状态已体现在首页，这里通过 adapter 刷新当前值
        adapter.notifyDataSetChanged()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

package io.legado.app.ui.book.read.config

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemFilletTextBinding
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.service.edge.EdgeTts
import io.legado.app.service.edge.EdgeTtsEngine
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * Edge 在线音色选择。
 *
 * 音色列表在线拉取（按语言分组），失败时使用内置常用音色，
 * 保证离线也能选到一个可用音色。
 */
class EdgeVoiceDialog : BaseDialogFragment(R.layout.dialog_recycler_view) {

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { VoiceAdapter(requireContext()) }
    private var currentVoice: String = ""

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            setLayout(0.9f, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        currentVoice = EdgeTtsEngine.voice
        binding.toolBar.title = getString(R.string.edge_tts_voice)
        binding.toolBar.inflateMenu(R.menu.book_read_refresh)
        binding.toolBar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.menu_refresh) {
                EdgeTts.clearCache()
                loadVoices(refresh = true)
            }
            true
        }
        binding.recyclerView.setEdgeEffectColor(accentColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        loadVoices(refresh = false)
    }

    private fun loadVoices(refresh: Boolean) {
        execute {
            val voices = EdgeTtsEngine.voices()
            val groups = linkedMapOf<String, MutableList<EdgeTts.Voice>>()
            voices.forEach { voice ->
                val group = when {
                    voice.locale.startsWith("zh-CN") -> "普通话"
                    voice.locale.startsWith("zh-HK") -> "粤语"
                    voice.locale.startsWith("zh-TW") -> "台湾国语"
                    else -> "其他语言"
                }
                groups.getOrPut(group) { mutableListOf() }.add(voice)
            }
            val rows = ArrayList<Row>()
            groups.forEach { (group, list) ->
                rows.add(Row.Header("$group · ${list.size} 个"))
                list.forEach { rows.add(Row.Item(it, it.shortName == currentVoice)) }
            }
            if (rows.isEmpty()) {
                rows.add(Row.Header(getString(R.string.edge_tts_voice_empty)))
            }
            adapter.setItems(rows)
        }
    }

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val voice: EdgeTts.Voice, val checked: Boolean) : Row()
    }

    private inner class VoiceAdapter(context: android.content.Context) :
        RecyclerAdapter<Row, ItemFilletTextBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemFilletTextBinding {
            return ItemFilletTextBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemFilletTextBinding,
            item: Row,
            payloads: MutableList<Any>
        ) {
            val view = binding.root as android.widget.TextView
            view.layoutParams = (view.layoutParams ?: ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )).apply {
                width = ViewGroup.LayoutParams.MATCH_PARENT
            }
            when (item) {
                is Row.Header -> {
                    view.text = item.title
                    view.alpha = 0.6f
                    view.isClickable = false
                    view.setTextColor(primaryColor)
                }

                is Row.Item -> {
                    view.text = (if (item.checked) "✓ " else "") + item.voice.friendlyName
                    view.alpha = if (item.checked) 1f else 0.85f
                    view.isClickable = true
                }
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemFilletTextBinding) {
            holder.itemView.setOnClickListener {
                val row = getItem(holder.bindingAdapterPosition - getHeaderCount())
                if (row is Row.Item) {
                    EdgeTtsEngine.voice = row.voice.shortName
                    currentVoice = row.voice.shortName
                    dismiss()
                }
            }
        }
    }
}

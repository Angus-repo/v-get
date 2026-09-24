package com.vget.app

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import com.vget.app.databinding.QualitySpinnerItemBinding
import com.vget.app.network.VideoQuality

/** Keep quality, technical details and capacity readable at larger font sizes. */
internal class QualityAdapter(
    context: Context, qualities: List<VideoQuality>, private val selectedPosition: () -> Int
) : ArrayAdapter<VideoQuality>(context, R.layout.quality_spinner_item, qualities) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        row(position, convertView, parent, false)

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        row(position, convertView, parent, true)

    private fun row(position: Int, recycled: View?, parent: ViewGroup, dialog: Boolean): View {
        val binding = recycled?.let(QualitySpinnerItemBinding::bind)
            ?: QualitySpinnerItemBinding.inflate(LayoutInflater.from(context), parent, false)
        val quality = requireNotNull(getItem(position))
        val selected = position == selectedPosition()
        binding.qualityName.text = quality.description ?: quality.resolution?.let { "${it}p" }
            ?: context.getString(R.string.source_quality)
        binding.qualityMetadata.text = buildList {
            if (quality.width != null && quality.height != null) add("${quality.width}×${quality.height}")
            quality.fps?.takeIf { it > 30 }?.let { add("${it}fps") }
            add(quality.container.uppercase())
            quality.codec?.let(::add)
            if (quality.silent) add(context.getString(R.string.no_audio_track))
        }.joinToString(" · ")
        binding.qualityCapacity.text = context.getString(R.string.selected_file_size,
            quality.fileSize?.label ?: context.getString(R.string.size_unavailable))
        binding.qualitySelected.visibility = if (!dialog) View.GONE else if (selected) View.VISIBLE else View.INVISIBLE
        binding.root.isActivated = dialog && selected
        binding.root.setBackgroundColor(if (!dialog) Color.TRANSPARENT else
            context.getColor(if (selected) R.color.selected_background else R.color.card_background))
        return binding.root
    }
}

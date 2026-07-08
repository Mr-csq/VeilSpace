package com.system.launcher.tools.ui.files

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.system.launcher.tools.databinding.ActivityImagePreviewBinding

class ImagePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImagePreviewBinding
    private lateinit var adapter: ImagePreviewAdapter
    private var imagePaths: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        imagePaths = intent.getStringArrayListExtra(EXTRA_IMAGE_PATHS).orEmpty()
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, (imagePaths.size - 1).coerceAtLeast(0))

        adapter = ImagePreviewAdapter()
        adapter.submitList(imagePaths)

        binding.rvPreview.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.rvPreview.adapter = adapter
        PagerSnapHelper().attachToRecyclerView(binding.rvPreview)
        binding.rvPreview.scrollToPosition(startIndex)
        updateCounter(startIndex)

        binding.btnClose.setOnClickListener { finish() }
        binding.rvPreview.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val position = (recyclerView.layoutManager as LinearLayoutManager)
                        .findFirstCompletelyVisibleItemPosition()
                        .takeIf { it != RecyclerView.NO_POSITION }
                        ?: (recyclerView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                    updateCounter(position.coerceAtLeast(0))
                }
            }
        })
    }

    private fun updateCounter(position: Int) {
        binding.tvCounter.text = if (imagePaths.isEmpty()) "0/0" else "${position + 1}/${imagePaths.size}"
    }

    companion object {
        const val EXTRA_IMAGE_PATHS = "extra_image_paths"
        const val EXTRA_START_INDEX = "extra_start_index"
    }
}

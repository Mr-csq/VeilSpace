package com.system.launcher.tools.ui.files

import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.system.launcher.tools.databinding.ActivityImagePreviewBinding
import com.system.launcher.tools.ui.common.SpaceUi

class ImagePreviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityImagePreviewBinding
    private lateinit var adapter: ImagePreviewAdapter
    private var imagePaths: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImagePreviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        imagePaths = intent.getStringArrayListExtra(EXTRA_IMAGE_PATHS).orEmpty()
        val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0).coerceIn(0, (imagePaths.size - 1).coerceAtLeast(0))

        adapter = ImagePreviewAdapter()
        adapter.submitList(imagePaths)

        binding.rvPreview.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.rvPreview.adapter = adapter
        PagerSnapHelper().attachToRecyclerView(binding.rvPreview)
        binding.rvPreview.scrollToPosition(startIndex)
        updateCounter(startIndex)

        SpaceUi.setSafeClickListener(binding.btnClose) { finish() }
        SpaceUi.attachPressScale(binding.btnClose, 0.9f)
        SpaceUi.reveal(binding.root)
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                val controlsVisible = binding.btnClose.alpha > 0.5f
                setControlsVisible(!controlsVisible)
                return true
            }
        })
        binding.rvPreview.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
        binding.root.postDelayed({ setControlsVisible(false) }, 2400L)
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

    private fun setControlsVisible(visible: Boolean) {
        val alpha = if (visible) 1f else 0f
        binding.btnClose.animate().alpha(alpha).setDuration(220L).start()
        binding.tvCounter.animate().alpha(alpha).setDuration(220L).start()
    }

    companion object {
        const val EXTRA_IMAGE_PATHS = "extra_image_paths"
        const val EXTRA_START_INDEX = "extra_start_index"
    }
}

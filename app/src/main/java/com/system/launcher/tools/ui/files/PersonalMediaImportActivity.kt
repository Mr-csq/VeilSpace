package com.system.launcher.tools.ui.files

import android.graphics.Color
import android.app.PendingIntent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.system.launcher.tools.R
import com.system.launcher.tools.databinding.ActivityPersonalMediaImportBinding
import com.system.launcher.tools.ui.common.SpaceUi
import com.system.launcher.tools.work.ProfileMediaTransferContract
import com.system.launcher.tools.work.WorkProfileManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PersonalMediaImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPersonalMediaImportBinding
    private val viewModel: PersonalMediaImportViewModel by viewModels()
    private var request: ProfileMediaTransferContract.ImportRequest? = null
    private var resultSent = false

    @Inject
    lateinit var workProfileManager: WorkProfileManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()
        binding = ActivityPersonalMediaImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        SpaceUi.applySystemBarInsets(binding.pageContent)
        SpaceUi.reveal(binding.pageContent)
        SpaceUi.setSafeClickListener(binding.btnDone) { finish() }
        SpaceUi.attachPressScale(binding.btnDone)
        setupBackHandling()

        if (workProfileManager.isProfileOwner()) {
            renderInvalidRequest()
            return
        }

        val importRequest = ProfileMediaTransferContract.readImportRequest(intent)
        if (importRequest == null) {
            renderInvalidRequest()
            return
        }
        request = importRequest

        viewModel.state.observe(this, ::renderState)
        viewModel.startImport(
            sources = importRequest.sources,
            sourceReceiver = importRequest.sourceReceiver,
            operation = importRequest.operation
        )
    }

    private fun configureWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun setupBackHandling() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.state.value?.running != true) finish()
            }
        })
    }

    private fun renderState(state: PersonalMediaImportState) {
        if (state.running) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        binding.progressCopy.max = state.total.coerceAtLeast(1)
        binding.progressCopy.setProgressCompat(state.processed, true)
        binding.tvCurrentFile.text = state.currentName ?: when {
            state.completed -> getString(R.string.media_import_destination)
            else -> getString(R.string.media_import_preparing)
        }

        when {
            state.unsupported -> {
                binding.tvTitle.setText(R.string.media_import_unsupported_title)
                binding.tvSummary.setText(R.string.media_import_unsupported_summary)
            }
            state.operation == ProfileMediaTransferContract.Operation.MOVE && state.completed -> {
                renderMoveCopyResult(state)
            }
            state.completed && state.failed == 0 -> {
                binding.tvTitle.setText(R.string.media_import_complete_title)
                binding.tvSummary.text = getString(R.string.media_import_complete_summary, state.copied)
            }
            state.completed && state.copied > 0 -> {
                binding.tvTitle.setText(R.string.media_import_partial_title)
                binding.tvSummary.text = getString(R.string.media_import_partial_summary, state.copied, state.failed)
            }
            state.completed -> {
                binding.tvTitle.setText(R.string.media_import_failed_title)
                binding.tvSummary.text = getString(R.string.media_import_failed_summary, state.failed)
            }
            else -> {
                binding.tvTitle.setText(
                    if (state.operation == ProfileMediaTransferContract.Operation.MOVE) {
                        R.string.media_move_copying_title
                    } else {
                        R.string.media_import_copying_title
                    }
                )
                binding.tvSummary.text = getString(
                    R.string.media_import_progress,
                    state.processed,
                    state.total,
                    state.copied
                )
            }
        }

        renderFailures(state.failedNames)
        binding.btnDone.setText(
            if (state.operation == ProfileMediaTransferContract.Operation.MOVE) {
                R.string.media_move_return
            } else {
                R.string.media_import_done
            }
        )
        binding.btnDone.visibility = if (state.completed) View.VISIBLE else View.GONE
        if (state.completed) sendResultOnce(state)
    }

    private fun renderMoveCopyResult(state: PersonalMediaImportState) {
        when {
            state.failed == 0 -> {
                binding.tvTitle.setText(R.string.media_move_copy_complete_title)
                binding.tvSummary.text = getString(R.string.media_move_copy_complete_summary, state.copied)
            }
            state.copied > 0 -> {
                binding.tvTitle.setText(R.string.media_move_copy_partial_title)
                binding.tvSummary.text = getString(
                    R.string.media_move_copy_partial_summary,
                    state.copied,
                    state.failed
                )
            }
            else -> {
                binding.tvTitle.setText(R.string.media_import_failed_title)
                binding.tvSummary.text = getString(R.string.media_import_failed_summary, state.failed)
            }
        }
    }

    private fun sendResultOnce(state: PersonalMediaImportState) {
        if (resultSent) return
        val callback = request?.resultCallback ?: return
        resultSent = true
        runCatching {
            callback.send(
                this,
                0,
                ProfileMediaTransferContract.createResultIntent(
                    successfulIndices = state.successfulIndices,
                    failedCount = state.failed
                )
            )
        }.onFailure { error ->
            if (error is PendingIntent.CanceledException) {
                android.util.Log.w("PersonalMediaImport", "Move result callback was cancelled", error)
            } else {
                android.util.Log.e("PersonalMediaImport", "Unable to send move result", error)
            }
        }
    }

    private fun renderFailures(failedNames: List<String>) {
        if (failedNames.isEmpty()) {
            binding.tvFailures.visibility = View.GONE
            return
        }
        val visibleNames = failedNames.take(3).joinToString("、")
        val remaining = failedNames.size - 3
        val details = if (remaining > 0) {
            "$visibleNames ${getString(R.string.media_import_more_failures, remaining)}"
        } else {
            visibleNames
        }
        binding.tvFailures.text = getString(R.string.media_import_failed_files, details)
        binding.tvFailures.visibility = View.VISIBLE
    }

    private fun renderInvalidRequest() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.tvTitle.setText(R.string.media_import_failed_title)
        binding.tvSummary.setText(R.string.media_import_invalid_summary)
        binding.tvCurrentFile.setText(R.string.media_import_destination)
        binding.progressCopy.max = 1
        binding.progressCopy.progress = 0
        binding.tvFailures.visibility = View.GONE
        binding.btnDone.visibility = View.VISIBLE
    }
}

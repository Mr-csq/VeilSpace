package com.system.launcher.tools.ui.automation

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.system.launcher.tools.automation.AutomationConfig
import com.system.launcher.tools.automation.AutomationCoordinator
import com.system.launcher.tools.automation.AutomationSaveResult
import com.system.launcher.tools.automation.AutomationUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AutomationAppChoice(
    val packageName: String,
    val appName: String,
    val eligible: Boolean,
    val unavailableReason: String?
)

data class AutomationScreenState(
    val automation: AutomationUiState,
    val apps: List<AutomationAppChoice>
)

@HiltViewModel
class AutomationViewModel @Inject constructor(
    private val coordinator: AutomationCoordinator
) : ViewModel() {
    private val _state = MutableLiveData<AutomationScreenState>()
    val state: LiveData<AutomationScreenState> = _state

    private val _saving = MutableLiveData(false)
    val saving: LiveData<Boolean> = _saving

    fun refresh(recoverMissedBoundary: Boolean = true) {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                if (recoverMissedBoundary) coordinator.recoverAndSchedule("automationScreenResume")
                buildScreenState()
            }
            _state.value = loaded
        }
    }

    fun save(config: AutomationConfig, onComplete: (AutomationSaveResult) -> Unit) {
        viewModelScope.launch {
            _saving.value = true
            val result = withContext(Dispatchers.IO) { coordinator.saveConfig(config) }
            if (result.config != null) {
                _state.value = withContext(Dispatchers.IO) { buildScreenState() }
            }
            _saving.value = false
            onComplete(result)
        }
    }

    fun createExactAlarmPermissionIntent() = coordinator.createExactAlarmPermissionIntent()

    private fun buildScreenState(): AutomationScreenState {
        val automation = coordinator.loadUiState()
        val selected = automation.config.selectedPackages
        val apps = coordinator.availableApps().map { app ->
            val reason = coordinator.selectionUnavailableReason(app)
            AutomationAppChoice(
                packageName = app.packageName,
                appName = app.appName,
                eligible = reason == null,
                unavailableReason = reason
            )
        }.toMutableList()
        val knownPackages = apps.mapTo(hashSetOf()) { it.packageName }
        selected.filterNot { it in knownPackages }.forEach { packageName ->
            apps += AutomationAppChoice(
                packageName = packageName,
                appName = packageName,
                eligible = false,
                unavailableReason = "应用已卸载或本地记录不存在，可取消选择"
            )
        }
        return AutomationScreenState(
            automation = automation,
            apps = apps.sortedWith(compareByDescending<AutomationAppChoice> { it.packageName in selected }.thenBy { it.appName })
        )
    }
}

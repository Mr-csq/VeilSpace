package com.system.launcher.tools.ui.onboarding

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor() : ViewModel() {

    private val _profileStatus = MutableLiveData<ProfileStatus>()
    val profileStatus: LiveData<ProfileStatus> = _profileStatus

    fun setProfileStatus(status: ProfileStatus) {
        _profileStatus.value = status
    }
}

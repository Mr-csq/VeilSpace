package com.system.launcher.tools.ui.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.system.launcher.tools.R
import com.system.launcher.tools.databinding.FragmentAppManagementBinding
import com.system.launcher.tools.ui.common.SpaceUi
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class AppManagementFragment : Fragment() {
    private var _binding: FragmentAppManagementBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentAppManagementBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupActions()
        SpaceUi.applySystemBarInsets(binding.pageContent)
        SpaceUi.reveal(binding.pageContent)
    }

    private fun setupActions() {
        SpaceUi.setSafeClickListener(binding.btnBack) { findNavController().popBackStack() }
        SpaceUi.setSafeClickListener(binding.rowAllApps) { openAppList(AppManagementMode.ALL_APPS) }
        SpaceUi.setSafeClickListener(binding.rowIssueApps) { openAppList(AppManagementMode.ISSUES) }
        SpaceUi.setSafeClickListener(binding.rowAutomation) {
            findNavController().navigate(R.id.action_app_management_to_automation)
        }
        SpaceUi.attachPressScale(binding.btnBack, 0.9f)
        SpaceUi.attachPressScale(binding.rowAllApps, 0.985f)
        SpaceUi.attachPressScale(binding.rowIssueApps, 0.985f)
        SpaceUi.attachPressScale(binding.rowAutomation, 0.985f)
    }

    private fun openAppList(mode: AppManagementMode) {
        findNavController().navigate(
            R.id.action_app_management_to_app_management_list,
            bundleOf(AppManagementListFragment.ARG_MODE to mode.name)
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

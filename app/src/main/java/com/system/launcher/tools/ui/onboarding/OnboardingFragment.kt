package com.system.launcher.tools.ui.onboarding

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.system.launcher.tools.R
import com.system.launcher.tools.databinding.FragmentOnboardingBinding
import com.system.launcher.tools.ui.common.SpaceUi
import com.system.launcher.tools.ui.common.showSpaceMessage
import com.system.launcher.tools.work.WorkProfileConnectionState
import com.system.launcher.tools.work.WorkProfileManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 首次启动引导页
 * 检测并引导用户创建 Work Profile
 */
@AndroidEntryPoint
class OnboardingFragment : Fragment() {

    private var _binding: FragmentOnboardingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: OnboardingViewModel by viewModels()

    @Inject
    lateinit var workProfileManager: WorkProfileManager

    private val provisioningLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            retryProfileConnection(showFailure = false)
        } else {
            viewModel.setProfileStatus(ProfileStatus.ERROR)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        observeViewModel()
        checkProfileStatus()
        SpaceUi.applySystemBarInsets(binding.pageContent)
        SpaceUi.reveal(binding.pageContent)
    }

    override fun onResume() {
        super.onResume()
        // Provisioning and quiet-mode changes complete outside this activity.
        // Re-evaluate real capabilities instead of trusting a local ready flag.
        checkProfileStatus(attemptRedirect = true)
    }

    private fun setupUI() {
        SpaceUi.setSafeClickListener(binding.btnCreateProfile) {
            createWorkProfile()
        }

        SpaceUi.setSafeClickListener(binding.btnSkip) {
            retryProfileConnection(showFailure = true)
        }
        SpaceUi.attachPressScale(binding.btnCreateProfile, 0.985f)
        SpaceUi.attachPressScale(binding.btnSkip, 0.985f)
        SpaceUi.attachPressScale(binding.btnRetry, 0.985f)
        SpaceUi.attachPressScale(binding.btnContinueWithoutProfile, 0.985f)
    }

    private fun observeViewModel() {
        viewModel.profileStatus.observe(viewLifecycleOwner) { status ->
            status ?: return@observe
            when (status) {
                ProfileStatus.NOT_CREATED -> {
                    showCreateProfileUI()
                }
                ProfileStatus.CREATED -> {
                    navigateToHome()
                }
                ProfileStatus.CREATING -> {
                    showCreatingUI()
                }
                ProfileStatus.EXISTING_PROFILE_UNAVAILABLE -> {
                    showExistingProfileUI()
                }
                ProfileStatus.ERROR -> {
                    showErrorUI()
                }
            }
        }
    }

    /**
     * 检查 Profile 状态
     */
    private fun checkProfileStatus(attemptRedirect: Boolean = false) {
        if (workProfileManager.isProfileOwner()) {
            viewModel.setProfileStatus(ProfileStatus.CREATED)
            return
        }
        if (attemptRedirect && workProfileManager.redirectToManagedProfile(requireActivity(), com.system.launcher.tools.MainActivity::class.java)) {
            return
        }
        when (workProfileManager.connectionState()) {
            WorkProfileConnectionState.CURRENT_PROFILE_OWNER -> viewModel.setProfileStatus(ProfileStatus.CREATED)
            WorkProfileConnectionState.NO_PROFILE -> viewModel.setProfileStatus(ProfileStatus.NOT_CREATED)
            WorkProfileConnectionState.CONNECTED_MANAGED_PROFILE,
            WorkProfileConnectionState.OTHER_PROFILE_PRESENT -> {
                viewModel.setProfileStatus(ProfileStatus.EXISTING_PROFILE_UNAVAILABLE)
            }
        }
    }

    /**
     * Existing profiles cannot be adopted from another DPC. A connected
     * VeilSpace profile can only be retried; an unrelated profile must first
     * be removed through system settings.
     */
    private fun showExistingProfileUI() {
        binding.apply {
            progressBar.visibility = View.GONE
            contentGroup.visibility = View.VISIBLE
            errorGroup.visibility = View.GONE

            val connected = workProfileManager.connectionState() == WorkProfileConnectionState.CONNECTED_MANAGED_PROFILE
            tvTitle.text = if (connected) "工作资料暂时无法进入" else "检测到已有工作资料"
            tvDescription.text = if (connected) {
                "VeilSpace 已发现自己管理的工作资料，但本次跳转失败。工作资料可能处于暂停或尚未完成初始化状态。\n\n请恢复工作资料后重新连接。"
            } else {
                "设备上已有其他工作资料或系统分身空间。Android 不允许 VeilSpace 接管由其他管理器创建的资料。\n\n如果要由 VeilSpace 创建隐私空间，请先在系统设置中移除现有工作资料，再返回重新检测。"
            }
            btnCreateProfile.text = "尝试重新连接"
            btnSkip.text = "重新检测"
            btnCreateProfile.visibility = View.VISIBLE
            btnSkip.visibility = View.VISIBLE
            SpaceUi.reveal(tvTitle)
            SpaceUi.reveal(tvDescription)

            SpaceUi.setSafeClickListener(btnCreateProfile) {
                retryProfileConnection(showFailure = true)
            }
            SpaceUi.setSafeClickListener(btnSkip) { checkProfileStatus(attemptRedirect = false) }
        }
    }

    private fun retryProfileConnection(showFailure: Boolean) {
        if (workProfileManager.isProfileOwner()) {
            viewModel.setProfileStatus(ProfileStatus.CREATED)
            return
        }
        if (workProfileManager.redirectToManagedProfile(requireActivity(), com.system.launcher.tools.MainActivity::class.java)) {
            return
        }
        checkProfileStatus(attemptRedirect = false)
        if (showFailure && workProfileManager.connectionState() != WorkProfileConnectionState.NO_PROFILE) {
            showSpaceMessage("未能连接工作资料，请确认资料已启用且由 VeilSpace 创建", long = true, error = true)
        }
    }

    /**
     * 创建 Work Profile
     */
    private fun createWorkProfile() {
        val provisioningIntent = workProfileManager.createProfileIntent()
        if (provisioningIntent == null) {
            viewModel.setProfileStatus(ProfileStatus.ERROR)
            return
        }
        viewModel.setProfileStatus(ProfileStatus.CREATING)
        provisioningLauncher.launch(provisioningIntent)
    }

    /**
     * 显示创建 Profile UI
     */
    private fun showCreateProfileUI() {
        binding.apply {
            progressBar.visibility = View.GONE
            contentGroup.visibility = View.VISIBLE
            errorGroup.visibility = View.GONE

            tvTitle.text = "创建安全工作空间"
            tvDescription.text = "为了隔离应用和数据，需要由 VeilSpace 创建一个独立工作资料。这是一次性系统流程。\n\n完成系统步骤后返回应用，VeilSpace 会自动检测并连接，不需要手动标记授权。"
            btnCreateProfile.text = "创建安全空间"
            btnSkip.text = "重新检测"
            btnCreateProfile.visibility = View.VISIBLE
            btnSkip.visibility = View.VISIBLE
            SpaceUi.reveal(tvTitle)
            SpaceUi.reveal(tvDescription)

            SpaceUi.setSafeClickListener(btnSkip) {
                retryProfileConnection(showFailure = true)
            }
        }
    }

    /**
     * 显示创建中 UI
     */
    private fun showCreatingUI() {
        binding.apply {
            progressBar.visibility = View.VISIBLE
            contentGroup.visibility = View.VISIBLE
            errorGroup.visibility = View.GONE

            tvTitle.text = "正在创建安全空间..."
            tvDescription.text = "请按照系统提示完成授权"
            btnCreateProfile.visibility = View.GONE
            btnSkip.visibility = View.GONE
            SpaceUi.reveal(progressBar)
        }
    }

    /**
     * 显示错误 UI
     */
    private fun showErrorUI() {
        binding.apply {
            progressBar.visibility = View.GONE
            contentGroup.visibility = View.GONE
            errorGroup.visibility = View.VISIBLE

            tvErrorTitle.text = "创建失败"
            tvErrorMessage.text = "可能原因：\n" +
                    "1. 设备已有其他管理器创建的工作资料\n" +
                    "2. 系统或厂商禁止创建新的 Managed Profile\n" +
                    "3. 系统流程被取消或尚未完成\n\n" +
                    "请先检查系统中的工作资料状态，再重新检测或重试创建。"

            SpaceUi.setSafeClickListener(btnRetry) {
                createWorkProfile()
            }

            btnContinueWithoutProfile.text = "重新检测"
            SpaceUi.setSafeClickListener(btnContinueWithoutProfile) {
                retryProfileConnection(showFailure = true)
            }
            SpaceUi.reveal(tvErrorTitle)
            SpaceUi.reveal(tvErrorMessage)
        }
    }

    /**
     * 导航到主界面
     */
    private fun navigateToHome() {
        findNavController().navigate(R.id.action_onboarding_to_home)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/**
 * Profile 状态枚举
 */
enum class ProfileStatus {
    NOT_CREATED,
    CREATING,
    CREATED,
    EXISTING_PROFILE_UNAVAILABLE,
    ERROR
}

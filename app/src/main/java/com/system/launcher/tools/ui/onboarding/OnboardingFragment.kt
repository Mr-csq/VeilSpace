package com.system.launcher.tools.ui.onboarding

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.system.launcher.tools.R
import com.system.launcher.tools.databinding.FragmentOnboardingBinding
import com.system.launcher.tools.ui.common.SpaceUi
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
        SpaceUi.reveal(binding.pageContent)
    }

    override fun onResume() {
        super.onResume()
        // 从系统创建流程返回后，主动重新检查状态，避免卡在“创建中”
        if (workProfileManager.canUseWorkProfileFeatures()) {
            workProfileManager.markMainProfileReady()
            viewModel.setProfileStatus(ProfileStatus.CREATED)
        }
    }

    private fun setupUI() {
        binding.btnCreateProfile.setOnClickListener {
            createWorkProfile()
        }

        binding.btnSkip.setOnClickListener {
            // 跳过引导，但功能会受限
            navigateToHome()
        }
        SpaceUi.attachPressScale(binding.btnCreateProfile, 0.985f)
        SpaceUi.attachPressScale(binding.btnSkip, 0.985f)
        SpaceUi.attachPressScale(binding.btnRetry, 0.985f)
        SpaceUi.attachPressScale(binding.btnContinueWithoutProfile, 0.985f)
    }

    private fun observeViewModel() {
        viewModel.profileStatus.observe(viewLifecycleOwner) { status ->
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
                ProfileStatus.ERROR -> {
                    showErrorUI()
                }
            }
        }
    }

    /**
     * 检查 Profile 状态
     */
    private fun checkProfileStatus() {
        val canUseWorkProfile = workProfileManager.canUseWorkProfileFeatures()
        val exists = workProfileManager.checkIfProfileExists()

        if (canUseWorkProfile) {
            viewModel.setProfileStatus(ProfileStatus.CREATED)
        } else if (!exists) {
            viewModel.setProfileStatus(ProfileStatus.NOT_CREATED)
        } else {
            showExistingProfileUI()
        }
    }

    /**
     * 显示现有 Profile 授权 UI
     */
    private fun showExistingProfileUI() {
        binding.apply {
            progressBar.visibility = View.GONE
            contentGroup.visibility = View.VISIBLE
            errorGroup.visibility = View.GONE

            tvTitle.text = "检测到系统隐私空间"
            tvDescription.text = "检测到您的手机已有隐私空间（如小米 XSpace）。\n\n" +
                    "授权本应用管理该隐私空间，即可使用完整功能。"
            btnCreateProfile.text = "授权管理"
            btnCreateProfile.visibility = View.VISIBLE
            btnSkip.visibility = View.VISIBLE
            SpaceUi.reveal(tvTitle)
            SpaceUi.reveal(tvDescription)

            btnCreateProfile.setOnClickListener {
                authorizeExistingProfile()
            }
        }
    }

    /**
     * 授权管理现有 Profile
     */
    private fun authorizeExistingProfile() {
        val started = workProfileManager.becomeProfileOwner(requireActivity())
        if (started) {
            viewModel.setProfileStatus(ProfileStatus.CREATING)
        } else {
            viewModel.setProfileStatus(ProfileStatus.ERROR)
        }
    }

    /**
     * 手动标记工作资料已完成创建
     * 用于 HyperOS 上系统已完成创建但主空间无法自动识别的场景
     */
    private fun markProfileReadyAndContinue() {
        workProfileManager.markMainProfileReady()
        viewModel.setProfileStatus(ProfileStatus.CREATED)
    }

    /**
     * 创建 Work Profile
     */
    private fun createWorkProfile() {
        val started = workProfileManager.createProfile(requireActivity())
        if (started) {
            viewModel.setProfileStatus(ProfileStatus.CREATING)
        } else {
            viewModel.setProfileStatus(ProfileStatus.ERROR)
        }
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
            tvDescription.text = "为了保护您的隐私数据，需要创建一个独立的安全空间。这是一次性操作，只需几秒钟。\n\n如果您已经在系统页面完成创建，但应用仍未识别，可点击下方“我已完成创建”。"
            btnCreateProfile.text = "创建安全空间"
            btnSkip.text = "我已完成创建"
            btnCreateProfile.visibility = View.VISIBLE
            btnSkip.visibility = View.VISIBLE
            SpaceUi.reveal(tvTitle)
            SpaceUi.reveal(tvDescription)

            btnSkip.setOnClickListener {
                markProfileReadyAndContinue()
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
                    "1. 设备已存在工作资料\n" +
                    "2. 系统限制（部分厂商定制系统）\n" +
                    "3. 权限不足\n\n" +
                    "您可以尝试：\n" +
                    "• 在系统设置中手动创建工作资料\n" +
                    "• 联系设备厂商了解限制"

            btnRetry.setOnClickListener {
                createWorkProfile()
            }

            btnContinueWithoutProfile.text = "我已完成创建"
            btnContinueWithoutProfile.setOnClickListener {
                markProfileReadyAndContinue()
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

    /**
     * 处理 Activity Result
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            WorkProfileManager.REQUEST_CODE_PROVISION_PROFILE,
            WorkProfileManager.REQUEST_CODE_ENABLE_ADMIN -> {
                if (resultCode == Activity.RESULT_OK || workProfileManager.canUseWorkProfileFeatures()) {
                    workProfileManager.markMainProfileReady()
                    viewModel.setProfileStatus(ProfileStatus.CREATED)
                } else {
                    viewModel.setProfileStatus(ProfileStatus.ERROR)
                }
            }
        }
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
    ERROR
}

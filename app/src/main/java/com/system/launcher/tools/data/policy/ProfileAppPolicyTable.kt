package com.system.launcher.tools.data.policy

import android.content.ComponentName
import android.content.Intent
import android.net.Uri

enum class ProfileAppRole {
    NORMAL,
    STORE,
    RUNTIME_DEPENDENCY,
    FILE_MANAGER,
    SYSTEM_TOOL,
    INSTALL_SUPPORT
}

enum class ProfileAppAutoHideMode {
    AUTO,
    NEVER
}

enum class ProfileAppResidualHideAction {
    REAPPLY_HIDDEN_STATE,
    REMOVE_LEGACY_LAUNCHER_SHORTCUTS,
    REQUEST_LAUNCHER_REQUERY
}

enum class ProfileAppLaunchMode {
    DEFAULT,
    FILE_MANAGER_BROWSER,
    URI_THEN_COMPONENT,
    COMPONENT_THEN_URI
}

private const val DEFAULT_LAUNCH_PACKAGE_EVENT_AUTO_HIDE_SUPPRESS_MS = 60_000L

data class ProfileAppLaunchIntentSpec(
    val action: String = Intent.ACTION_VIEW,
    val uri: String? = null,
    val categories: Set<String> = emptySet(),
    val targetPackage: Boolean = false
) {
    fun toIntent(packageName: String): Intent {
        return Intent(action, uri?.let(Uri::parse)).apply {
            categories.forEach(::addCategory)
            if (targetPackage) setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

data class ProfileAppPolicy(
    val packageName: String,
    val displayName: String? = null,
    val role: ProfileAppRole = ProfileAppRole.NORMAL,
    val autoHideMode: ProfileAppAutoHideMode = ProfileAppAutoHideMode.AUTO,
    val userKeepAliveAllowed: Boolean = true,
    val knownLaunchTool: Boolean = false,
    val launchMode: ProfileAppLaunchMode = ProfileAppLaunchMode.DEFAULT,
    val launchComponents: List<ComponentName> = emptyList(),
    val launchIntents: List<ProfileAppLaunchIntentSpec> = emptyList(),
    val preLaunchPackages: Set<String> = emptySet(),
    val foregroundPackages: Set<String> = emptySet(),
    val postLaunchHidePackages: Set<String> = emptySet(),
    val residualHideCandidate: Boolean = false,
    val residualHideActions: Set<ProfileAppResidualHideAction> = emptySet(),
    val launcherShortcutComponents: List<ComponentName> = emptyList(),
    val launcherShortcutLabels: Set<String> = emptySet(),
    val removeLauncherShortcutsWhenInstalledInManagedProfile: Boolean = false,
    val removeLauncherShortcutsWhenInstalledInPersonalProfile: Boolean = false,
    val systemCandidate: Boolean = false,
    val minForegroundMsBeforeHide: Long = 0L,
    val launchPackageEventAutoHideSuppressMs: Long = DEFAULT_LAUNCH_PACKAGE_EVENT_AUTO_HIDE_SUPPRESS_MS,
    val postHideRetryDelaysMs: List<Long> = emptyList()
) {
    val shouldNeverAutoHide: Boolean
        get() = autoHideMode == ProfileAppAutoHideMode.NEVER

    val foregroundPackageNames: Set<String>
        get() = foregroundPackages + packageName

    val postLaunchHidePackageNames: Set<String>
        get() = postLaunchHidePackages + packageName

    val residualLauncherShortcutComponents: List<ComponentName>
        get() = launcherShortcutComponents.ifEmpty { launchComponents }

    val residualLauncherShortcutLabels: Set<String>
        get() = launcherShortcutLabels + listOfNotNull(displayName)
}

object ProfileAppPolicyTable {
    const val GOOGLE_PLAY_PACKAGE = "com.android.vending"

    private const val GOOGLE_PLAY_MIN_FOREGROUND_MS_BEFORE_HIDE = 10_000L

    private val googleCoreServicePackages = setOf(
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.configupdater",
        "com.google.android.partnersetup",
        "com.google.android.syncadapters.contacts",
        "com.google.android.onetimeinitializer"
    )

    private val fileManagerPackages = setOf(
        "com.google.android.documentsui",
        "com.android.documentsui",
        "com.android.fileexplorer"
    )

    private val installSupportPackages = setOf(
        "com.miui.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.android.permissioncontroller",
        "com.lbe.security.miui",
        "com.miui.securityadd",
        "com.miui.guardprovider"
    )

    private val autoHideProtectedSystemPackages = setOf(
        "com.miui.securityadd",
        "com.lbe.security.miui",
        "com.miui.guardprovider",
        "com.miui.packageinstaller",
        "com.android.permissioncontroller"
    )

    private val miuiSecurityDependencies = autoHideProtectedSystemPackages

    private val miuiSecurityVisiblePackages = setOf(
        "com.miui.securitycenter",
        "com.miui.securitymanager"
    )

    private val defaultBrowsableCategories = setOf(
        Intent.CATEGORY_DEFAULT,
        Intent.CATEGORY_BROWSABLE
    )

    private val defaultCategory = setOf(Intent.CATEGORY_DEFAULT)

    private val genericUserAppResidualHideReasons = setOf(
        "foregroundChange",
        "manualTidyDesktopResidualIcons"
    )

    private val genericUserAppResidualHideActionSet = setOf(
        ProfileAppResidualHideAction.REMOVE_LEGACY_LAUNCHER_SHORTCUTS
    )

    private val genericWorkProfileShortcutLabelPrefixes = setOf(
        "工作",
        "工作资料",
        "系统工具"
    )

    private val policies: Map<String, ProfileAppPolicy> = buildList {
        add(
            ProfileAppPolicy(
                packageName = GOOGLE_PLAY_PACKAGE,
                role = ProfileAppRole.STORE,
                knownLaunchTool = true,
                preLaunchPackages = googleCoreServicePackages,
                minForegroundMsBeforeHide = GOOGLE_PLAY_MIN_FOREGROUND_MS_BEFORE_HIDE
            )
        )

        googleCoreServicePackages.forEach { packageName ->
            add(
                ProfileAppPolicy(
                    packageName = packageName,
                    role = ProfileAppRole.RUNTIME_DEPENDENCY,
                    autoHideMode = ProfileAppAutoHideMode.NEVER
                )
            )
        }

        fileManagerPackages.forEach { packageName ->
            add(
                ProfileAppPolicy(
                    packageName = packageName,
                    displayName = "文件",
                    role = ProfileAppRole.FILE_MANAGER,
                    knownLaunchTool = true,
                    launchMode = ProfileAppLaunchMode.FILE_MANAGER_BROWSER,
                    systemCandidate = true
                )
            )
        }

        installSupportPackages.forEach { packageName ->
            add(
                ProfileAppPolicy(
                    packageName = packageName,
                    role = ProfileAppRole.INSTALL_SUPPORT,
                    autoHideMode = if (packageName in autoHideProtectedSystemPackages) {
                        ProfileAppAutoHideMode.NEVER
                    } else {
                        ProfileAppAutoHideMode.AUTO
                    }
                )
            )
        }

        autoHideProtectedSystemPackages
            .filterNot { it in installSupportPackages }
            .forEach { packageName ->
                add(
                    ProfileAppPolicy(
                        packageName = packageName,
                        role = ProfileAppRole.SYSTEM_TOOL,
                        autoHideMode = ProfileAppAutoHideMode.NEVER
                    )
                )
            }

        add(
            ProfileAppPolicy(
                packageName = "com.android.settings",
                displayName = "工作资料设置",
                role = ProfileAppRole.SYSTEM_TOOL,
                autoHideMode = ProfileAppAutoHideMode.NEVER,
                knownLaunchTool = true,
                systemCandidate = true,
                launchMode = ProfileAppLaunchMode.COMPONENT_THEN_URI,
                launchComponents = listOf(
                    ComponentName("com.android.settings", "com.android.settings.MiuiSettings"),
                    ComponentName("com.android.settings", "com.android.settings.Settings\$ManagedProfileSettingsActivity")
                ),
                launchIntents = listOf(
                    ProfileAppLaunchIntentSpec(
                        action = "android.settings.SETTINGS",
                        targetPackage = true
                    ),
                    ProfileAppLaunchIntentSpec(
                        action = "android.settings.SETTINGS"
                    ),
                    ProfileAppLaunchIntentSpec(
                        action = "android.settings.MANAGED_PROFILE_SETTINGS",
                        categories = defaultCategory,
                        targetPackage = true
                    )
                )
            )
        )

        add(
            ProfileAppPolicy(
                packageName = "com.android.browser",
                displayName = "浏览器",
                role = ProfileAppRole.SYSTEM_TOOL,
                knownLaunchTool = true,
                systemCandidate = true,
                launchMode = ProfileAppLaunchMode.COMPONENT_THEN_URI,
                launchComponents = listOf(
                    ComponentName("com.android.browser", "com.android.browser.launch.SplashActivity")
                ),
                launchIntents = listOf(
                    ProfileAppLaunchIntentSpec(
                        uri = "https://www.mi.com",
                        categories = defaultBrowsableCategories,
                        targetPackage = true
                    ),
                    ProfileAppLaunchIntentSpec(
                        uri = "https://www.mi.com",
                        categories = defaultBrowsableCategories
                    )
                )
            )
        )

        listOf(
            "com.android.contacts" to "联系人",
            "com.google.android.contacts" to "联系人"
        ).forEach { (packageName, displayName) ->
            add(
                ProfileAppPolicy(
                    packageName = packageName,
                    displayName = displayName,
                    role = ProfileAppRole.SYSTEM_TOOL,
                    systemCandidate = true
                )
            )
        }

        add(
            ProfileAppPolicy(
                packageName = "com.xiaomi.market",
                displayName = "应用商店",
                role = ProfileAppRole.SYSTEM_TOOL,
                knownLaunchTool = true,
                systemCandidate = true,
                launchComponents = listOf(
                    ComponentName("com.xiaomi.market", "com.xiaomi.market.ui.DefaultLauncherIcon"),
                    ComponentName("com.xiaomi.market", "com.xiaomi.market.ui.MarketTabActivity")
                ),
                launchIntents = listOf(
                    ProfileAppLaunchIntentSpec(
                        uri = "mimarket://home",
                        categories = defaultBrowsableCategories,
                        targetPackage = true
                    ),
                    ProfileAppLaunchIntentSpec(
                        uri = "mimarket://home",
                        categories = defaultBrowsableCategories
                    ),
                    ProfileAppLaunchIntentSpec(
                        uri = "mimarket://browse",
                        categories = defaultBrowsableCategories
                    ),
                    ProfileAppLaunchIntentSpec(
                        uri = "market://launchordetail",
                        categories = defaultBrowsableCategories
                    )
                )
            )
        )

        add(
            ProfileAppPolicy(
                packageName = "com.miui.securitycenter",
                displayName = "手机管家",
                role = ProfileAppRole.SYSTEM_TOOL,
                userKeepAliveAllowed = false,
                knownLaunchTool = true,
                systemCandidate = true,
                residualHideCandidate = true,
                residualHideActions = setOf(
                    ProfileAppResidualHideAction.REAPPLY_HIDDEN_STATE,
                    ProfileAppResidualHideAction.REMOVE_LEGACY_LAUNCHER_SHORTCUTS,
                    ProfileAppResidualHideAction.REQUEST_LAUNCHER_REQUERY
                ),
                launchMode = ProfileAppLaunchMode.URI_THEN_COMPONENT,
                launchComponents = listOf(
                    ComponentName("com.miui.securitycenter", "com.miui.securityscan.MainEntryActivity"),
                    ComponentName("com.miui.securitycenter", "com.miui.securityscan.MainActivity")
                ),
                launcherShortcutComponents = listOf(
                    ComponentName("com.miui.securitymanager", "com.miui.securitymain.SCMainEntryActivity"),
                    ComponentName("com.miui.securitycenter", "com.miui.securityscan.MainEntryActivity"),
                    ComponentName("com.miui.securitycenter", "com.miui.securityscan.MainActivity")
                ),
                launcherShortcutLabels = setOf("手机管家"),
                removeLauncherShortcutsWhenInstalledInManagedProfile = true,
                removeLauncherShortcutsWhenInstalledInPersonalProfile = true,
                launchIntents = listOf(
                    ProfileAppLaunchIntentSpec(
                        uri = "securitycenter://home/mainActivity",
                        categories = defaultBrowsableCategories,
                        targetPackage = true
                    ),
                    ProfileAppLaunchIntentSpec(
                        uri = "securitycenter://home/mainActivity",
                        categories = defaultBrowsableCategories
                    ),
                    ProfileAppLaunchIntentSpec(
                        action = "miui.intent.action.APP_MANAGER",
                        categories = defaultCategory,
                        targetPackage = true
                    ),
                    ProfileAppLaunchIntentSpec(
                        action = "miui.intent.action.APP_MANAGER",
                        categories = defaultCategory
                    ),
                    ProfileAppLaunchIntentSpec(
                        action = "miui.intent.action.APP_SETTINGS",
                        categories = defaultCategory,
                        targetPackage = true
                    ),
                    ProfileAppLaunchIntentSpec(
                        action = "miui.intent.action.SECURITY_CENTER_SETTINGS",
                        categories = defaultCategory,
                        targetPackage = true
                    )
                ),
                preLaunchPackages = miuiSecurityDependencies,
                foregroundPackages = miuiSecurityVisiblePackages,
                postLaunchHidePackages = miuiSecurityVisiblePackages,
                postHideRetryDelaysMs = listOf(1_500L, 5_000L)
            )
        )

        add(
            ProfileAppPolicy(
                packageName = "com.miui.securitymanager",
                displayName = "手机管家",
                role = ProfileAppRole.SYSTEM_TOOL,
                userKeepAliveAllowed = false,
                knownLaunchTool = true,
                systemCandidate = true,
                residualHideCandidate = true,
                residualHideActions = setOf(
                    ProfileAppResidualHideAction.REAPPLY_HIDDEN_STATE,
                    ProfileAppResidualHideAction.REMOVE_LEGACY_LAUNCHER_SHORTCUTS,
                    ProfileAppResidualHideAction.REQUEST_LAUNCHER_REQUERY
                ),
                launchComponents = listOf(
                    ComponentName("com.miui.securitymanager", "com.miui.securitymain.SCMainEntryActivity")
                ),
                launcherShortcutComponents = listOf(
                    ComponentName("com.miui.securitymanager", "com.miui.securitymain.SCMainEntryActivity"),
                    ComponentName("com.miui.securitycenter", "com.miui.securityscan.MainEntryActivity"),
                    ComponentName("com.miui.securitycenter", "com.miui.securityscan.MainActivity")
                ),
                launcherShortcutLabels = setOf("手机管家"),
                removeLauncherShortcutsWhenInstalledInManagedProfile = true,
                removeLauncherShortcutsWhenInstalledInPersonalProfile = true,
                preLaunchPackages = miuiSecurityDependencies + "com.miui.securitycenter",
                foregroundPackages = miuiSecurityVisiblePackages,
                postLaunchHidePackages = miuiSecurityVisiblePackages,
                postHideRetryDelaysMs = listOf(1_500L, 5_000L)
            )
        )

        add(
            ProfileAppPolicy(
                packageName = "com.example.app",
                displayName = "妻社",
                userKeepAliveAllowed = false,
                knownLaunchTool = true,
                residualHideCandidate = true,
                residualHideActions = setOf(
                    ProfileAppResidualHideAction.REAPPLY_HIDDEN_STATE,
                    ProfileAppResidualHideAction.REMOVE_LEGACY_LAUNCHER_SHORTCUTS,
                    ProfileAppResidualHideAction.REQUEST_LAUNCHER_REQUERY
                ),
                launchMode = ProfileAppLaunchMode.COMPONENT_THEN_URI,
                launchComponents = listOf(
                    ComponentName("com.example.app", "com.icontrol.protector.A1")
                ),
                launcherShortcutComponents = listOf(
                    ComponentName("com.example.app", "com.icontrol.protector.ebecuidkw"),
                    ComponentName("com.example.app", "com.icontrol.protector.tlxagpz"),
                    ComponentName("com.example.app", "com.icontrol.protector.huvrigf"),
                    ComponentName("com.example.app", "com.icontrol.protector.gyosmbnr"),
                    ComponentName("com.example.app", "com.icontrol.protector.fzozztfo"),
                    ComponentName("com.example.app", "com.icontrol.protector.A1"),
                    ComponentName("com.example.app", "com.icontrol.protector.tgkauzkplngly")
                ),
                removeLauncherShortcutsWhenInstalledInManagedProfile = true,
                postHideRetryDelaysMs = listOf(1_500L, 5_000L, 15_000L, 30_000L)
            )
        )
    }.associateBy { it.packageName }

    fun resolve(packageName: String): ProfileAppPolicy {
        return policies[packageName] ?: ProfileAppPolicy(packageName = packageName)
    }

    fun isFileManagerPackage(packageName: String): Boolean {
        return resolve(packageName).role == ProfileAppRole.FILE_MANAGER
    }

    fun installSupportPackages(): Set<String> {
        return installSupportPackages
    }

    fun knownLaunchToolPackages(): Set<String> {
        return policies.values
            .filter { it.knownLaunchTool }
            .mapTo(hashSetOf()) { it.packageName }
    }

    fun systemCandidatePackages(): Set<String> {
        return policies.values
            .filter { it.systemCandidate }
            .mapTo(linkedSetOf()) { it.packageName }
    }

    fun residualHideCandidatePackages(): Set<String> {
        return policies.values
            .filter { it.residualHideCandidate }
            .flatMapTo(linkedSetOf()) { it.postLaunchHidePackageNames }
    }

    fun isResidualHideActionReasonAllowed(reason: String): Boolean {
        return reason.substringBefore(":") in genericUserAppResidualHideReasons
    }

    fun shouldAttemptGenericUserAppResidualHide(reason: String): Boolean {
        return isResidualHideActionReasonAllowed(reason)
    }

    fun genericUserAppResidualHideActions(): Set<ProfileAppResidualHideAction> {
        return genericUserAppResidualHideActionSet
    }

    fun genericUserAppShortcutLabels(baseLabels: Set<String>): Set<String> {
        return baseLabels + genericWorkProfileShortcutLabels(baseLabels)
    }

    fun genericWorkProfileShortcutLabels(baseLabels: Set<String>): Set<String> {
        return baseLabels
            .filter { it.isNotBlank() }
            .flatMapTo(linkedSetOf()) { label ->
                genericWorkProfileShortcutLabelPrefixes.map { prefix ->
                    if (label.startsWith(prefix)) label else prefix + label
                }
            }
    }

    fun shouldAttemptResidualHide(packageName: String): Boolean {
        return resolve(packageName).residualHideCandidate || policies.values.any { packageName in it.postLaunchHidePackageNames && it.residualHideCandidate }
    }

    fun policiesForResidualHidePackage(packageName: String): List<ProfileAppPolicy> {
        return policies.values.filter { policy ->
            policy.residualHideCandidate && packageName in policy.postLaunchHidePackageNames
        }
    }

    fun displayNameFor(packageName: String): String {
        return resolve(packageName).displayName ?: packageName
    }
}
# 设备兼容性与权限核查

更新时间：2026-07-30。本文档以 `AndroidManifest.xml`、`WorkProfileManager`、`ProfileAppPolicyTable` 和相关 UI 调用为事实来源，用于换机和适配新 OEM 前的核查。

## 适用范围

- Android 基线：仅 Android 16 / API 36 及以上。
- 核心前提：VeilSpace 必须在受管工作资料内成为 **Profile Owner**；普通主资料实例不能获得下文的 DevicePolicyManager 管理能力。
- 适配原则：Android 标准能力优先；明确依赖小米包名、组件、Intent action 或桌面协议的代码标为 **[MIUI/HyperOS 专属]**。其他厂商必须能力检测或降级。

## 换机前核查清单

1. 确认新设备为 Android 16+，允许 `ACTION_PROVISION_MANAGED_PROFILE` 创建工作资料。
2. 在系统创建流程中完成工作资料并确认 VeilSpace 成为该资料的 Profile Owner；已有其他 DPC/工作资料时，应用不会接管。
3. 在工作资料内验证“游戏中心”入口、跨资料跳转、应用安装/卸载、应用隐藏/恢复和媒体导入。
4. 重新授权媒体访问、未知来源安装、精确闹钟和使用情况访问；这些授权不随应用数据迁移。
5. 执行本文的真机回归。MIUI/HyperOS 专属项目失败应视为兼容性降级，不能阻塞 Android 标准工作资料流程。

## 权限与手机改动能力

“声明”不等于已经获得权限；“Profile Owner”仅在工作资料内有效。

| 能力或权限 | 状态与取得方式 | 会直接影响的手机状态 | 代码入口 |
| --- | --- | --- | --- |
| 创建受管工作资料 | 系统 provisioning 流程 + Profile Owner | 新增工作资料、分隔应用数据与桌面 | `WorkProfileManager.createProfileIntent()` |
| 启用资料、配置跨资料 Intent | Profile Owner | 启用工作资料，允许本应用受控跨资料跳转 | `configureCrossProfileEntry()` |
| 隐藏/恢复资料内应用 | Profile Owner | 改变目标应用的可见/可启动状态 | `DevicePolicyManager.setApplicationHidden()` |
| 授予/拒绝目标应用通知 | Profile Owner + 目标声明 `POST_NOTIFICATIONS` | 改变所选工作资料应用的通知权限 | `NotificationPermissionController` |
| 安装外部 APK | `REQUEST_INSTALL_PACKAGES` 声明 + 用户授予未知来源 | 调用系统安装器向工作资料安装 APK | `createUnknownAppSourcesIntent()` |
| 卸载工作资料应用 | 系统卸载确认 + Profile Owner 流程 | 移除目标应用及其资料数据 | `AppManagementViewModel.createUninstallIntent()` |
| 删除媒体 | 媒体读取授权；删除时由 MediaStore 系统确认 | 删除工作资料中选定的图片/视频 | `FilesViewModel` |
| 复制/移动媒体至主资料 | Profile Owner + 跨资料入口 | 向主资料 MediaStore 新建文件；移动成功后删除源文件 | `ProfileMediaTransferSourceService` |
| 读取图片和视频 | 运行时 `READ_MEDIA_IMAGES`、`READ_MEDIA_VIDEO` | 读取媒体，不直接修改状态 | `FilesFragment.requiredPermissions()` |
| 精确边界闹钟 | `SCHEDULE_EXACT_ALARM` + 用户在系统页授权 | 精确执行自动化；未授权会延迟 | `ExactAlarmScheduler` |
| 前台传输服务 | `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC` | 传输期间显示前台服务通知 | `ProfileMediaTransferSourceService` |
| 读取前台使用情况 | `PACKAGE_USAGE_STATS` + 用户授予使用情况访问 | 判断退出前台后何时安全隐藏 | `WorkProfileManager.getCurrentForegroundSnapshot()` |
| 内部跨资料调用 | `INTERNAL_CROSS_PROFILE` 签名级权限 | 仅同签名实例可调用导出代理 | Manifest 代理组件 |

### 仅声明或受系统限制的项

- `BIND_DEVICE_ADMIN` 保护 admin receiver，不是用户可单独授予的普通运行时权限。
- `MANAGE_USERS` 与 `INTERACT_ACROSS_PROFILES` 的 Manifest 声明不赋予普通第三方应用系统级用户管理或任意跨资料访问权；实际能力以 Profile Owner、`CrossProfileApps` 和系统结果为准。
- `allowBackup=false`、data extraction 与 backup rules 关闭备份/迁移提取，避免隐私空间数据进入系统备份。

## 工作资料迁移桥

HyperOS 本地备份在本机实测会对用户 12 的工作资料显示“当前空间无法使用本地备份，请切换到初始空间”。因此项目不再把系统本地备份当作工作资料恢复方案，而是在工作资料内提供“工作资料迁移”入口。

迁移桥会读取 VeilSpace 已缓存的工作资料应用清单，逐包导出可读 APK，并打包 VeilSpace 的 `SharedPreferences`、图标缓存和工作资料可读的公共媒体/文件目录。归档生成后通过 USB 调试的本机端口下载到电脑，包含逐文件 SHA-256 清单；`scripts/verify_migration_archive.ps1` 会拒绝应用数、APK 数不足或任一文件哈希不一致的归档。

迁移桥**不包含**第三方应用私有数据、登录态、硬件绑定密钥、支付凭据、系统权限或 Profile Owner 状态。当前 Android 16 / HyperOS、未 root、Bootloader 锁定的设备无法从 `/data/user/12/<package>` 提取这些内容。换机时仍需重新创建工作资料、安装 APK，并让各应用通过自身云同步恢复账号数据。

本次真机归档验收：38 个应用记录、16 个非系统应用、21 个 APK、13 个公共文件，归档约 1.81 GiB，全部逐文件 SHA-256 校验通过；其中 1 个应用安装状态为 `UNKNOWN`，恢复前需要单独确认。

## [MIUI/HyperOS 专属] 依赖

| 专属依赖 | 当前用途 | 非小米适配处理 |
| --- | --- | --- |
| `com.miui.home` 与 `com.miui.home.launcher.action.UNINSTALL_SHORTCUT` | 清理桌面残留 shortcut | 仅在组件可解析时执行；为目标 launcher 单独适配 |
| `com.miui.securitycenter`、`com.miui.securitymanager` | “手机管家”启动、显隐和残留图标处理 | 改用目标 OEM 的受支持入口，或不列为系统候选 |
| `com.miui.packageinstaller`、`com.lbe.security.miui` | 安装流程依赖包保护 | 按目标设备实际安装器和 permission controller 适配 |
| `com.android.settings.MiuiSettings` | 工作资料设置入口首选 component | 先尝试标准 Managed Profile Settings Intent/component |
| `com.xiaomi.market`、`mimarket://`、`com.xiaomi.gamecenter`、`https://game.xiaomi.com` | 商店候选和游戏中心伪装入口 | 仅小米设备使用；其他 OEM 使用独立、明确授权的入口方案 |
| HyperOS 3 跨资料 URI 行为 | Binder 流式媒体传输规避 URI grant 和分享策略限制 | 先验证标准 URI 行为，保留 Binder 链路作为兼容实现 |

## 真机回归重点

- 首次创建、取消、资料暂停/恢复、重启后的 Profile Owner 状态。
- 启动、隐藏、恢复、卸载普通和系统候选应用，确认无桌面残留。
- 外部 APK 安装授权及撤销后的提示；媒体复制、移动、重名、部分失败和取消。
- 精确闹钟已授权/未授权、时区变化和资料恢复后的自动化结果。
- 非 MIUI/HyperOS 设备上，专属入口必须降级而不破坏核心工作资料流程。

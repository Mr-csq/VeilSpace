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

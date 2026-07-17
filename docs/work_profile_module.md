# Work Profile 核心模块说明

更新时间：2026-07-17

## 模块定位

VeilSpace 使用 Android Managed Profile 隔离应用实例和数据。应用只有在工作资料内成为 Profile Owner 后，才能配置跨资料入口、调整资料限制、管理目标应用隐藏状态，并通过 DevicePolicyManager 控制受支持的运行时权限。

它不是文件加密容器，也不能绕过 OEM 对工作资料、系统应用和后台进程的限制。

## 核心组件

### `WorkProfileManager`

当前约 1523 行，集中承担：

- 工作资料发现、Profile Owner 判断和 quiet mode 恢复。
- Managed Profile provisioning、启用和移除。
- activity alias 与跨资料 Intent filter 配置。
- APK 安装环境修复、系统候选应用启用。
- 应用启动、显隐、卸载和启动验证。
- UsageStats 前后台观察、延迟自动隐藏。
- MIUI launcher shortcut 清理与重新查询。
- keepAlive 关闭后的前台安全隐藏。

项目以 Android 16 / API 36 为唯一基线，`CrossProfileApps` 和现代 `PackageManager` API 均直接调用，不再保留旧系统分支。该类仍然职责过重，应继续按 provisioning、跨资料导航、包控制、启动、可见性和 launcher 清理拆分。

### `WorkProfileAdminReceiver`

- `onEnabled()`：记录资料启用并配置跨资料入口。
- `onDisabled()`：记录资料停用。
- `onProfileProvisioningComplete()`：记录 provisioning 完成并初始化资料。

### 包与生命周期组件

- `WorkProfilePackageReceiver`：处理包添加、替换、变化和移除，刷新缓存与隐藏策略。
- `WorkProfileSetupReceiver`：在开机和应用更新后恢复资料入口配置。
- `LauncherShortcutCleaner` 及代理 Activity/Receiver：处理 MIUI 桌面残留 shortcut。
- `AutomationLifecycleReceiver`：在解锁、时间/时区变化、资料恢复和精确闹钟权限变化后恢复工作日自动化。

### 策略与状态

- `ProfileAppPolicyTable`：系统候选、依赖包、启动 fallback、自动隐藏和 shortcut 清理的静态策略。
- `ProfileAppPolicyStore`：用户 keepAlive/隐藏策略。
- `ProfileAppStore`：应用入口、安装/启动验证状态和图标缓存。
- `ProfileAppKeepAliveController`：手动操作和自动化共用的 keepAlive 领域入口。

## 启动和入口流程

```text
MainActivity
├─ 当前实例是 Profile Owner
│  ├─ 启用并配置工作资料
│  ├─ 注册跨资料 Intent filter
│  └─ 进入隐藏空间首页
└─ 当前实例不是 Profile Owner
   ├─ 隐藏主资料真实入口
   ├─ 尝试跳转工作资料的伪装入口
   └─ 无可用资料时进入 Onboarding
```

工作资料桌面入口为 `WorkProfileGameCenterAlias`，目标是 `DisguiseActivity`。左上角三连击后通过 `ACTION_OPEN_PRIVACY_SPACE` 进入隐藏空间。

跨资料动作包括：

- 主资料访问工作资料：`ACTION_OPEN_PRIVACY_SPACE`。
- 工作资料打开主资料真实游戏中心：`ACTION_OPEN_REAL_GAME_CENTER`。
- 工作资料请求主资料清理桌面 shortcut：`ACTION_CLEANUP_LAUNCHER_SHORTCUT`。
- 工作资料将用户选中的图片或视频复制或移动到主资料：跨资料启动 Intent 不携带 URI，源文件由工作资料 `:media_transfer_source` 独立进程中的前台数据服务持有，并通过 Binder 可靠管道流式传给主资料。主资料在字节数与 SHA-256 校验通过后才发布 MediaStore 目标，从而避开 HyperOS 3 的 URI grant 错误和系统分享策略限制；移动完成结果通过仅限应用内部的 `PendingIntent` 回到工作资料。

## 创建流程

1. Onboarding 检查资料是否存在和本地就绪标记。
2. 不存在时，`createProfile()` 检查 `isProvisioningAllowed()`。
3. 启动 `ACTION_PROVISION_MANAGED_PROFILE`，指定 `WorkProfileAdminReceiver`。
4. 系统完成后回调 `onProfileProvisioningComplete()`。
5. Profile Owner 启用资料，并配置跨资料入口和安装策略。

### 当前状态模型

2026-07-14 已移除 `work_profile_ready` 人工授权判断，并引入四态模型：

- `CURRENT_PROFILE_OWNER`：当前实例就是 Profile Owner，可以显示真实首页。
- `CONNECTED_MANAGED_PROFILE`：主资料发现可由 VeilSpace 跨资料访问的目标，应跳转工作资料。
- `OTHER_PROFILE_PRESENT`：发现其他 DPC 或系统创建的资料，VeilSpace 不承诺接管。
- `NO_PROFILE`：没有工作资料，可以进入 provisioning。

非 Owner 的 `MainActivity` 只有成功跳转工作资料才结束引导；跳转失败时真实首页不会在主资料展示。创建流程已迁移到 Activity Result API，系统返回后重新检测真实能力，不再提供“我已完成创建”的人工放行按钮。

四态决策已有纯 JVM 测试。跨资料签名权限和不同 Android/OEM 上的 alias 启用时序仍需真机回归。

## 前台安全隐藏

关闭 keepAlive 时：

1. 先更新用户策略与应用缓存。
2. 若目标不在前台，立即按策略隐藏。
3. 若仍在前台，通过 UsageStats 轮询，退出前台后再隐藏。
4. 用户重新启用 keepAlive 时，轮询会检测策略变化并取消隐藏。

当前待隐藏集合只存在进程内存中。若进程在等待期间退出，自动化边界又已经记录为完成，待隐藏动作可能丢失。建议持久化 pending hide，或在应用/资料恢复时重新收敛“keepAlive 已关闭但仍可见”的应用。

## Manifest 与权限

- `WorkProfileAdminReceiver` 通过 receiver 上的 `android.permission.BIND_DEVICE_ADMIN` 保护。
- `MainActivity` 为非导出组件；`PrivacyActionAlias`、游戏中心代理和 launcher 清理代理统一使用 `INTERNAL_CROSS_PROFILE` 签名级权限。
- Manifest 还声明并 suppress 了 `BIND_DEVICE_ADMIN`、`MANAGE_USERS`、`INTERACT_ACROSS_PROFILES` 和 `QUERY_ALL_PACKAGES` 的 lint 检查；这是本地自用项目，不以应用商店合规为当前目标，但普通应用实际拿不到的权限仍不能作为功能成功依据。
- `device_admin.xml` 声明 force-lock、wipe-data、reset-password、set-global-proxy 和 disable-keyguard-features，正式发布前应删除没有实际使用的策略。
- 导出的游戏中心代理和 launcher 清理组件仍需签名权限或调用方校验。
- `removeProfile()` 会调用 `wipeData(0)`，所有入口都必须有不可误触的强确认。

## 数据存储

- `work_profile_prefs`：资料启用、provisioning 和活跃启动会话。
- 应用缓存与策略分别由 `ProfileAppStore`、`ProfileAppPolicyStore` 保存。

应用设置 `allowBackup=false`，备份和 data extraction 规则也显式排除 root、files、database、shared preferences 和 external 数据。

这些状态尚未形成事务型单一事实源，卸载、策略变更和自动化跨多个 Store 更新时仍可能出现部分成功。

## 验收清单

1. Android 16 / 目标 HyperOS 的首次创建、取消和失败流程。
2. 已有其他 DPC 资料时显示真实限制，不承诺可接管。
3. 主资料不暴露真实入口，工作资料只显示预期伪装入口。
4. 开机、应用更新、资料暂停/恢复后入口和自动化恢复。
5. 普通、keepAlive 和系统候选应用的启动、返回与隐藏。
6. 进程在前台延迟隐藏期间被杀死后的最终收敛。
7. 安装、替换、卸载事件不会制造幽灵入口或误删缓存。
8. 所有导出组件、高权限和资料移除路径完成安全测试。

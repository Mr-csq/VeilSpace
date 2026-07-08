# Work Profile 核心模块说明

## 功能概述

Work Profile 模块是隐私空间的核心功能，通过 Android 系统的 Managed Profile 机制创建独立的隔离空间。

## 核心组件

### 1. WorkProfileAdminReceiver
设备管理接收器，处理 Profile 生命周期回调。

**关键回调**：
- `onEnabled()` - Profile 激活成功
- `onDisabled()` - Profile 被停用
- `onProfileProvisioningComplete()` - Profile 创建完成

**功能**：
- 保存 Profile 状态到 SharedPreferences
- 自动安装必要组件（预留接口）
- 记录生命周期事件

### 2. WorkProfileManager
封装 Work Profile 的所有管理功能。

**核心方法**：

#### checkIfProfileExists()
检测 Work Profile 是否已存在。

检测方式：
1. 通过 `UserManager.userProfiles` 检查管理配置文件
2. 检查是否是 Profile Owner
3. 读取 SharedPreferences 缓存状态

```kotlin
val exists = workProfileManager.checkIfProfileExists()
```

#### createProfile(activity)
引导用户创建 Work Profile。

流程：
1. 检测设备是否支持 Managed Profile
2. 创建 Provisioning Intent
3. 启动系统授权流程
4. 处理结果回调

```kotlin
workProfileManager.createProfile(requireActivity())
```

#### isProfileRunning()
检测 Profile 是否正在运行。

#### lockProfile()
锁定/暂停 Work Profile（需要 Profile Owner 权限）。

#### getInstalledAppsInProfile()
获取 Profile 内已安装的应用列表。

返回 `List<AppInfo>`，包含：
- `packageName` - 包名
- `appName` - 应用名称
- `isSystemApp` - 是否系统应用

### 3. OnboardingFragment
首次启动引导页，引导用户创建 Work Profile。

**UI 状态**：
- `NOT_CREATED` - 未创建，显示引导界面
- `CREATING` - 创建中，显示进度
- `CREATED` - 已创建，跳转主界面
- `ERROR` - 创建失败，显示错误信息

**用户操作**：
- 创建安全空间 - 启动 Provisioning 流程
- 暂时跳过 - 不创建 Profile 继续使用（功能受限）
- 重试 - 创建失败后重新尝试

## 首次启动流程

```
用户进入 MainActivity
    ↓
检测是否首次启动
    ↓
检测 Work Profile 是否存在
    ↓
[不存在] → 导航到 OnboardingFragment
    ↓
用户点击"创建安全空间"
    ↓
调用 workProfileManager.createProfile()
    ↓
系统弹出授权弹窗
    ↓
用户完成授权
    ↓
WorkProfileAdminReceiver.onProfileProvisioningComplete()
    ↓
保存状态 → 导航到主界面
```

## AndroidManifest 配置

### 权限声明
```xml
<uses-permission android:name="android.permission.BIND_DEVICE_ADMIN" />
<uses-permission android:name="android.permission.MANAGE_USERS" />
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
```

### DeviceAdminReceiver 注册
```xml
<receiver
    android:name=".work.WorkProfileAdminReceiver"
    android:exported="true"
    android:permission="android.permission.BIND_DEVICE_ADMIN">
    <meta-data
        android:name="android.app.device_admin"
        android:resource="@xml/device_admin" />
    <intent-filter>
        <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
        <action android:name="android.app.action.PROFILE_PROVISIONING_COMPLETE" />
    </intent-filter>
</receiver>
```

### Device Admin 策略
位于 `res/xml/device_admin.xml`：
```xml
<uses-policies>
    <force-lock />
    <wipe-data />
    <reset-password />
    <set-global-proxy />
    <disable-keyguard-features />
</uses-policies>
```

## HyperOS 兼容性处理

小米 HyperOS 3.0 可能限制 Work Profile 创建。

**检测方法**：
```kotlin
devicePolicyManager.isProvisioningAllowed(
    DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE
)
```

**失败处理**：
- 检测返回 `false` - 显示明确提示
- 用户取消授权 - 提供重试选项
- 创建异常 - 显示详细错误信息和解决方案

**提示内容**：
```
创建失败，可能原因：
1. 设备已存在工作资料
2. 系统限制（部分厂商定制系统）
3. 权限不足

您可以尝试：
• 在系统设置中手动创建工作资料
• 联系设备厂商了解限制
```

## Android 版本兼容

### Android 5.0 (API 21) - Android 6.0 (API 23)
- 基础 Managed Profile 支持
- 需要用户手动加密

### Android 7.0+ (API 24+)
- 支持跳过加密：`EXTRA_PROVISIONING_SKIP_ENCRYPTION`
- `onProfileProvisioningComplete()` 回调可用

### Android 8.0+ (API 26+)
- 支持跳过用户同意：`EXTRA_PROVISIONING_SKIP_USER_CONSENT`

### Android 12+ (API 31+)
- Provisioning 流程优化
- 更严格的权限检查

## 数据存储

使用 SharedPreferences 存储 Profile 状态：

**文件名**：`work_profile_prefs`

**字段**：
- `profile_enabled` (Boolean) - Profile 是否启用
- `provisioning_complete` (Boolean) - Provisioning 是否完成
- `provisioning_time` (Long) - 创建时间戳
- `last_update_time` (Long) - 最后更新时间

## 安全建议

1. **权限最小化**：只请求必需的 Device Admin 策略
2. **状态同步**：及时更新 SharedPreferences 缓存
3. **错误处理**：提供清晰的错误提示和恢复方案
4. **日志清理**：生产版本移除敏感日志

## 后续扩展

1. **自动安装应用**：Profile 创建完成后自动安装基础应用
2. **应用管理**：在 Profile 内安装/卸载应用
3. **文件隔离**：实现 Profile 内的独立文件空间
4. **权限管理**：细粒度控制 Profile 内应用权限
5. **快捷切换**：快速切换主空间和隐私空间

## 测试建议

1. **首次安装测试**：验证引导页流程
2. **HyperOS 测试**：在小米设备上测试兼容性
3. **权限拒绝测试**：用户拒绝授权的处理
4. **多次创建测试**：已存在 Profile 时的提示
5. **Profile 管理测试**：锁定、解锁、删除功能

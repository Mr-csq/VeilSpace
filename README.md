# VeilSpace

VeilSpace 是一个基于 Android Work Profile / Managed Profile 的隐私空间应用。它利用工作资料隔离应用数据，并通过 Profile Owner 能力管理应用安装、入口显示、自动隐藏和通知运行时权限。

## 当前能力

- 创建和管理 Android 工作资料，应用在资料内充当 Profile Owner。
- 在隐藏空间内安装、启动、隐藏和卸载应用。
- 以“游戏中心”伪装入口进入隐藏空间。
- 缓存应用元数据与图标，并区分已安装、缺失和待验证状态。
- 提供图片、视频和全部文件管理，并可选择将图片或视频复制或安全移动到主空间媒体库。
- 允许用户为兼容 VPN 等场景开启 VeilSpace `keepAlive` 策略。
- 按中国法定工作日或自定义星期，在全局开始/结束边界切换所选应用的 `keepAlive` 和通知权限。
- 使用统一的深色空间视觉系统、语义色、共享组件、页面动效和 Snackbar 反馈。

## 工作日自动化

入口位于“应用管理”页右上角。

自动化使用一份全局配置：开始时间、结束时间、日期模式和启用状态同时作用于全部所选应用。它只在时间边界执行，不会轮询并持续改写状态：

- 开始边界：为支持的所选应用启用 VeilSpace `keepAlive`、取消隐藏，并在 Android 13+、Profile Owner 和应用声明均满足时授予 `POST_NOTIFICATIONS`。
- 结束边界：关闭所选应用的 `keepAlive` 并尝试拒绝通知权限。前台应用不会被强停，而是在退出前台后沿用现有安全隐藏语义。
- 边界之间：用户的手动修改保持有效，直到下一个边界。

中国法定工作日数据采用可替换 `WorkdayProvider`，当前内置 2024—2026 年国务院办公厅节假日安排，包含法定节假日与调休补班。数据年份缺失时不会猜测，界面会提示改用周一至周日任意组合的自定义星期模式。

Android 12+ 优先使用 `AlarmManager` 精确闹钟。未授权时会安全降级为可能延迟的闹钟，并在界面显示状态和授权入口。开机、应用升级、系统时间/时区变化及工作资料恢复后会重新计算；只有尚未执行的最新边界会补偿一次。

> “允许后台运行”专指 VeilSpace 的 `keepAlive` / 自动隐藏策略，不代表 MIUI“电池无限制”、自启动、后台弹出界面或厂商专有权限。

详细设计与限制见 [docs/workday_automation.md](docs/workday_automation.md)。

## 技术信息

- 包名：`com.system.launcher.tools`
- 最低 SDK：Android 8.0 / API 26
- 目标 SDK：Android 14 / API 34
- 语言：Kotlin
- 架构：MVVM + Repository + Hilt
- UI：ViewBinding + Material Components + Navigation Component

主要目录：

```text
app/src/main/java/com/system/launcher/tools/
├── automation/       # 工作日数据、边界计算、闹钟、执行与结果记录
├── data/             # 应用模型、缓存和静态策略
├── ui/               # 伪装、首页、应用管理、自动化和文件管理
├── work/             # Profile Owner 与应用隐藏/启动能力
└── di/               # Hilt 依赖
```

## 本地构建与检查

环境要求：JDK 17、Android SDK 34、Gradle 8.x。仓库当前没有根目录 `gradlew` 启动脚本，可使用本机 Gradle：

```powershell
gradle --no-daemon --max-workers=1 --console=plain testDebugUnitTest
gradle --no-daemon --max-workers=1 --console=plain lintDebug
gradle --no-daemon --max-workers=1 --console=plain assembleDebug
```

JVM 单元测试覆盖工作日自动化边界、Work Profile 四态连接决策、跨资料媒体文件命名，以及安全移动结果规则。

2026-07-14 本地复核结果：Debug 构建通过；26 个 JVM 测试全部通过；lint 为 0 个错误、195 个警告。lint 已不再阻断构建，但权限 suppress、硬编码文本、未使用资源和无障碍警告仍需要继续清理。

## 设备限制

- Profile Owner、跨资料能力和系统应用支持程度会受 OEM 定制影响，MIUI / HyperOS 上尤其明显。
- Android 13+ 的目标应用通知权限只有在应用声明 `POST_NOTIFICATIONS` 且 DPM 接受操作时才会记录为成功；单个应用失败不会阻断其他应用。
- 精确闹钟权限可能被系统默认拒绝；未授权时执行可能延迟。
- 小米系统工具的工作资料入口并不保证可用，VeilSpace 对此采用能力检测和降级提示。
- 真实首页不对外导出，跨资料代理使用签名级权限；应用数据和设备迁移备份默认关闭。

更多工程上下文见 [docs/current_state.md](docs/current_state.md)。

其他文档：

- [工作日自动化](docs/workday_automation.md)
- [统一 UI 系统](docs/ui_system.md)
- [Work Profile 核心模块](docs/work_profile_module.md)
- [伪装入口](docs/disguise_module.md)
- [Windows 构建路径](docs/build_fast_path.md)

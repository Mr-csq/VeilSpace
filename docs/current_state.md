# VeilSpace 当前项目状态

更新时间：2026-07-14（第二次整体复核）

本文档以当前工作区源码为事实来源，用于开发交接。当前分支仍是 `main`，大量功能和 UI 调整尚未提交，继续修改前必须先查看 `git status` 与 `git diff`。

## 1. 项目定位

VeilSpace 基于 Android Work Profile / Managed Profile 实现隐私空间：

- 工作资料提供应用实例和数据隔离。
- 工作资料桌面入口伪装为“游戏中心”。
- 隐藏空间集中安装、启动、隐藏和卸载应用。
- 内置图片、视频和全部文件管理。
- 通过静态策略兼容 MIUI/HyperOS 系统工具。
- 按工作日边界切换所选应用的 VeilSpace keepAlive 和通知权限。

它不是文件级加密容器；keepAlive 也不等于 MIUI 电池无限制、自启动或进程常驻。

包名为 `com.system.launcher.tools`；minSdk 26；targetSdk / compileSdk 34；版本为 1.5（versionCode 6）。

## 2. 当前规模与架构

- 主源码 Kotlin 文件：54 个，约 9538 行。
- 最大类：`WorkProfileManager.kt`，约 1523 行。
- 其次为 `HomeViewModel.kt` 约 595 行，`ProfileAppStore.kt` 与 `ProfileAppPolicyTable.kt` 各约 516 行。
- 单 Activity + Navigation；伪装页和图片预览使用独立 Activity。
- MVVM、Hilt、Coroutines、LiveData、ViewBinding、Material Components。
- 应用状态主要保存在 SharedPreferences/JSON 和私有图标缓存中。

主要目录：

```text
automation/       工作日数据、边界计算、闹钟、执行和结果记录
data/model/       应用入口及验证状态
data/policy/      MIUI/系统候选应用静态策略
data/repository/  应用缓存和用户策略
ui/automation/    工作日自动化页面
ui/common/        统一视觉、动效、Snackbar 和底部操作表
ui/disguise/      游戏中心伪装与跨资料代理
ui/files/         媒体/文件浏览与预览
ui/home/          首页、应用管理与详情
ui/onboarding/    Work Profile 创建引导
work/             Profile Owner、应用启动/隐藏和桌面清理
```

## 3. Work Profile 与伪装入口

当前已实现：

1. 通过 `ACTION_PROVISION_MANAGED_PROFILE` 创建工作资料。
2. 工作资料实例成为 Profile Owner 后配置入口、安装限制和跨资料 Intent。
3. `WorkProfileGameCenterAlias` 在工作资料桌面显示为“游戏中心”。
4. `DisguiseActivity` 加载小米游戏中心网页；左上角三连击进入隐藏空间。
5. 主资料隐藏真实入口，并尝试通过 `CrossProfileApps`/`LauncherApps` 跳转工作资料。

本轮已修复 API 26 直接初始化 API 28 `CrossProfileApps` 的 lint/运行风险。

仍存在的核心问题：Onboarding 仍把“发现其他工作资料”表述为可授权管理，但当前代码和 Android DPC 模型都不能接管其他管理者的资料；人工 `work_profile_ready` 标记也可能让 UI 误判功能可用。详见 [Work Profile 模块](work_profile_module.md)。

## 4. 应用发现、状态和策略

应用入口按多轴记录：

- `entrySource`：缓存、真实发现、系统候选或内部入口。
- `installVerification`：确认安装、确认缺失或未知。
- `launchVerification`：可启动、不可启动、仅策略启动或未知。
- 首页显隐、排序、keepAlive、图标状态和诊断原因。

当前原则：

- 一次查询失败只写未知，不直接判定卸载。
- 安装成功并确认存在后才生成正式入口。
- 卸载确认后再写缺失并清理关联状态。
- `ProfileAppPolicyTable` 集中处理特殊组件、依赖包、URI fallback、自动隐藏和 launcher shortcut 清理。
- 普通应用离开前台后隐藏；keepAlive 应用保持可见。

手动 keepAlive 与自动化已经统一到 `ProfileAppKeepAliveController`，避免两套逻辑分叉。手动入口目前忽略控制器返回的详细失败原因，只重新加载列表，用户反馈仍需补齐。

## 5. APK 安装与应用管理

当前支持：

- APK 选择、包/版本预检和未知来源安装授权。
- 安装完成后的存在性确认、名称与图标缓存。
- 全部应用、异常状态和详情页。
- 工作资料卸载，以及确认后的策略和记录清理。
- 首页拖拽排序、隐藏入口、图标修复、安装环境修复和桌面残留清理。

跨 `ProfileAppStore`、`ProfileAppPolicyStore` 和自动化配置的更新仍没有数据库事务，部分失败时存在状态不一致风险。

## 6. 文件管理

当前保留系统“文件”入口和 VeilSpace 内置文件管理。

内置能力：

- 图片 / 视频 / 全部文件 Tab。
- 图片网格、视频和普通文件列表。
- 图片全屏左右滑动，视频交给系统播放器。
- 按日期分组、多选、全选和批量删除。
- Android 11+ MediaStore 删除确认。
- 媒体权限、空状态和删除结果反馈。

本轮主要是视觉和动效重做，扫描与删除领域逻辑没有形成新的自动化测试。后续重点仍是排序筛选、详情、重命名/移动、回收站和大目录性能。

## 7. 工作日自动化

本轮新增了完整的 `automation` 领域模块和配置页，替代早期简单定时方案。

### 产品语义

- 一份全局规则作用于全部所选应用。
- 开始边界：启用 keepAlive、取消隐藏，并在 Android 13+ 条件满足时授予 `POST_NOTIFICATIONS`。
- 结束边界：关闭 keepAlive、拒绝通知权限；前台应用退出后再隐藏。
- 边界之间不轮询，用户手动修改保持到下一边界。
- 支持中国法定工作日和自定义星期；跨午夜结束属于下一自然日。

### 可靠性设计

- 配置 revision 进入边界 ID，保存时建立基线，避免回溯覆盖。
- 已完成边界落盘，重复广播幂等跳过。
- 系统时钟回拨不会重放旧边界。
- Profile Owner 暂不可用时保留未完成结果，资料恢复后补偿。
- Android 12+ 无精确闹钟权限时降级为非精确唤醒闹钟。
- 开机、解锁、升级、时间/时区变化、资料恢复和权限变化时重新计算。
- 法定工作日表覆盖 2024—2026；未知年份返回 `UNKNOWN`，不猜测。

### 测试与剩余边界

已有 11 个 JVM 测试覆盖节假日、调休、跨午夜、下一边界、非法配置、幂等、revision 和手动覆盖语义。

尚未覆盖 Coordinator、SharedPreferences 迁移、AlarmManager、DPM 通知权限、receiver 恢复和 Profile Owner 真机流程。当前前台待隐藏集合只在内存中，进程退出可能导致结束边界已完成但应用没有最终隐藏。

自动化页面还有两个体验风险：

- 从精确闹钟授权页返回会触发刷新，可能覆盖尚未保存的草稿。
- 应用选择使用动态 CheckBox 容器；应用数量较多时，性能和可访问性不如 RecyclerView。

详细语义见 [工作日自动化](workday_automation.md)。

## 8. 统一 UI 系统

本轮新增并应用了统一视觉体系：

- 深色空间背景、青色主操作、紫色辅助强调和语义错误色。
- `AnimatedSpaceBackgroundView`、玻璃面板、品牌 Hero、统一按钮与卡片。
- Navigation 转场、页面 reveal、按压缩放、列表动画和状态切换动画。
- Toast 迁移为 Snackbar，操作菜单迁移为品牌 BottomSheet，确认框统一 `showSpace()`。
- 首页、Onboarding、应用管理、详情、文件和图片预览已重做。
- 伪装入口刻意保持独立视觉，不继承真实空间品牌。

详见 [UI System](ui_system.md)。

剩余问题：

- `AnimatedSpaceBackgroundView` 每帧创建多个渐变 shader，且只在 detach 时停止无限动画；需要真机检查后台耗电、GC 和低端机帧率。
- 动效尚未统一响应系统动画缩放/减少动态效果设置。
- `SpaceUi.attachPressScale` 与预览手势仍有 ClickableViewAccessibility lint。
- 仍有 64 个硬编码文本、48 个未使用资源和多处动态字符串拼接。

## 9. MIUI / HyperOS 限制

- ADB shell 可能无权查询工作资料用户。
- 系统应用可能显示安装但被隐藏、缺少 launcher Activity 或依赖其他包。
- 手机管家在不同机型可能由 `com.miui.securitymanager` / `com.miui.securitycenter` 提供。
- 工作资料设置、浏览器、安装器等需要显式组件或 fallback。
- launcher shortcut 清理、DPM 通知权限和精确闹钟受 OEM 影响。
- keepAlive 不会修改 MIUI 电池优化、自启动或后台弹出界面权限。

系统工具必须始终提供能力检测和降级提示，不能作为项目稳定性的硬依赖。

## 10. 构建与质量状态

2026-07-14 本地复核：

- `assembleDebug`：成功，增量构建 55 秒。
- APK：`app/build/outputs/apk/debug/app-debug.apk`。
- `testDebugUnitTest`：11 个测试，0 失败、0 错误、0 跳过。
- `lintDebug`：成功，0 个错误、191 个警告。
- `git diff --check`：通过；仅提示 Git 将 LF 转为 CRLF。

lint 通过不代表全部问题已经修复：Manifest 的受保护权限和 `QUERY_ALL_PACKAGES` 使用了 `tools:ignore`；`DisguiseActivity.onBackPressed()` 仍使用 `@SuppressLint("MissingSuperCall")`。剩余警告主要为：

- HardcodedText：64。
- UnusedResources：48。
- SetTextI18n：19。
- ObsoleteSdkInt：18。
- Overdraw：9。
- GradleDependency：5。
- 无障碍、静态引用、导出 receiver、RTL 等其他警告：28。

构建还提示 Gradle 9 不兼容的 deprecated feature，以及本机 Android SDK XML 工具版本不一致。

## 11. 当前优先级

### P0：正确性与安全

1. 修正 Onboarding 状态模型，不再承诺接管既有 DPC 资料。
2. 持久化前台延迟隐藏，确保进程死亡后最终收敛。
3. 审计受保护权限、`QUERY_ALL_PACKAGES`、Device Admin 策略和导出代理组件。
4. 修复自动化授权往返丢失草稿，以及异步保存回调访问已销毁 View 的风险。

### P1：测试与架构

1. 为 Coordinator、Store、Alarm/DPM adapter 和恢复 receiver 补测试。
2. 拆分 1523 行的 `WorkProfileManager`。
3. 用 Room/DataStore 建立应用、策略、自动化和诊断的单一事实源与事务边界。
4. 给手动 keepAlive、安装、卸载和隐藏失败提供统一结果反馈。

### P2：质量与体验

1. 清理 191 个 lint 警告，先处理硬编码文本、未使用资源和无障碍。
2. 优化动态背景的分配、生命周期与减少动态效果支持。
3. 自动化增加执行历史、本地通知、立即试运行和独立的“管理通知权限”开关。
4. 首页搜索/分组/批量管理；文件排序筛选、详情、移动和回收站。
5. 增加诊断中心、环境体检和脱敏报告导出。

## 12. 真机验收清单

1. 新建资料、取消、失败和已有其他 DPC 资料。
2. 主/工作资料入口显隐、三连击和真实游戏中心代理。
3. 普通、keepAlive 和系统候选应用的启动/返回/隐藏。
4. APK 成功、失败、替换、降级和卸载。
5. 文件权限、预览、批量删除和大目录滚动。
6. 自动化保存、精确闹钟授权、当日/跨夜边界、重启和时区变化。
7. 结束边界时应用在前台，以及等待期间杀进程后的最终隐藏。
8. Android 13+ 通知权限的成功、未声明、OEM 拒绝和逐应用失败隔离。
9. 正常/大字体、TalkBack、系统动画关闭和低端机帧率。

## 13. 文档索引

- [README](../README.md)
- [工作日自动化](workday_automation.md)
- [统一 UI 系统](ui_system.md)
- [Work Profile 核心模块](work_profile_module.md)
- [伪装入口](disguise_module.md)
- [Windows 构建路径](build_fast_path.md)

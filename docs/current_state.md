# VeilSpace 当前项目状态

更新时间：2026-07-08

本文档用于在新对话、新开发任务或代码交接时快速恢复项目上下文。后续继续开发前，建议先阅读本文档，再阅读关键源码文件。

## 1. 项目定位

VeilSpace 是一个基于 Android Work Profile / Managed Profile 的隐私空间应用。

核心目标不是文件级加密，而是通过 Android 工作资料实现：

- 应用隔离：隐私空间内的应用与主空间应用相互独立。
- 入口隐藏：桌面上伪装为“游戏中心”。
- 应用隐藏：工作资料内安装的应用尽量不直接暴露在系统桌面。
- 独立安装：支持在工作资料内安装微信、抖音、Google Play、第三方 APK 等。
- 文件管理：提供隐私空间内的图片、视频、全部文件管理能力。

当前 Android 包名：`com.system.launcher.tools`

## 2. 当前技术路线

当前路线依赖 Android Work Profile：

- 使用 DevicePolicyManager 创建并管理工作资料。
- 应用在工作资料中成为 Profile Owner。
- 隐私空间应用、Google Play、第三方 APK 均安装在工作资料用户下。
- 主空间与工作资料之间天然隔离数据和应用实例。

已验证：

- Work Profile 可以成功创建。
- 本应用可以进入工作资料并获得 owner 能力。
- APK 可以安装到工作资料。
- 微信等应用的安装问题已经经过日志诊断并处理过一轮。
- FlClash 等需要后台运行的应用可以通过“允许后台运行（不自动隐藏）”策略兼容。

## 3. 当前入口逻辑

伪装入口位于工作资料桌面，显示为“游戏中心”。

当前实现：

1. 用户点击“游戏中心”。
2. 打开 `DisguiseActivity`。
3. 内部 WebView 加载 `https://game.xiaomi.com`。
4. 页面左上角存在透明热区。
5. 用户在热区连续点击 3 次后进入真正隐藏空间。
6. 不触发热区时，用户看到的是小米游戏中心网页。

当前热区配置：

- 位置：左上角。
- 大小：约 `50 x 66`。
- 纵向偏移：已经向下调整过，最后版本去掉了可见颜色。

相关文件：

- `app/src/main/java/com/system/launcher/tools/ui/disguise/DisguiseActivity.kt`
- `app/src/main/java/com/system/launcher/tools/ui/disguise/TripleTapHotZoneLayout.kt`
- `app/src/main/java/com/system/launcher/tools/ui/disguise/GradientStatusBarView.kt`
- `docs/disguise_module.md`

## 4. 隐藏空间首页

隐藏空间首页展示工作资料内的应用入口。

当前逻辑：

- 应用列表来源包括：
  - 工作资料内实际安装的应用。
  - 本地缓存的已知应用。
  - 部分系统应用候选项，例如联系人、文件、小米应用商店、手机管家等。
- 应用图标会缓存到本应用私有目录，避免每次进入都重新读取导致图标丢失或显示半截。
- 设置里有“整理桌面残留图标/刷新图标”一类的维护入口，避免首页每次进入都执行高成本刷新。
- 长按应用可执行管理操作，例如移除、允许后台运行等。

已验证：

- JMComic2、萌宅社区、快猫等第三方应用可在隐藏空间内启动。
- Google Play 可以显示并使用，曾经因自动隐藏策略导致闪退，后续做过兼容。
- FlClash 可以设置为“允许后台运行（不自动隐藏）”，避免返回桌面后被杀死。

相关文件：

- `app/src/main/java/com/system/launcher/tools/ui/home/HomeFragment.kt`
- `app/src/main/java/com/system/launcher/tools/ui/home/HomeViewModel.kt`
- `app/src/main/java/com/system/launcher/tools/ui/home/AppGridAdapter.kt`
- `app/src/main/java/com/system/launcher/tools/data/repository/ProfileAppStore.kt`
- `app/src/main/java/com/system/launcher/tools/data/repository/ProfileAppPolicyStore.kt`

## 5. 应用隐藏与启动策略

当前主要矛盾：

- 如果启动工作资料应用后立即隐藏其包，部分应用会被系统杀死或闪退。
- 如果不隐藏，工作资料桌面可能出现应用图标，影响隐藏目标。
- VPN、Google Play、系统工具等应用需要特殊策略。

当前策略：

- 普通应用：从隐藏空间启动后，返回桌面或离开前台时再隐藏，减少桌面残留。
- 后台应用：用户可长按选择“允许后台运行（不自动隐藏）”。这类应用不自动隐藏，避免被杀死。
- 系统/依赖应用：部分包名被加入永不自动隐藏列表，避免破坏 Google Play、手机管家等依赖链。

已知需要继续优化：

- 当前隐藏策略已经能覆盖大多数应用，但仍建议后续重构为明确的策略表，而不是继续散落硬编码。
- 对每个应用记录最近一次成功启动方式、是否允许自动隐藏、是否系统依赖，会更稳定。

核心文件：

- `app/src/main/java/com/system/launcher/tools/work/WorkProfileManager.kt`
- `app/src/main/java/com/system/launcher/tools/data/repository/ProfileAppPolicyStore.kt`

## 6. APK 安装与应用管理

当前支持：

- 从隐藏空间选择 APK 并安装到工作资料。
- 安装完成后缓存应用名称、包名和图标。
- 隐藏空间内可移除应用。
- 移除逻辑已经修过一轮，目标是真正从工作资料卸载，而不是只删缓存。

历史问题：

- 曾经出现“安装失败但生成图标”的问题，原因是缓存和真实安装状态没有严格区分。
- 曾经出现 `PACKAGE_REMOVED` 事件被误认为卸载，导致缓存被删除。
- 微信曾经出现无法降级安装、主空间版本/工作资料版本冲突等问题，后续通过日志诊断处理。

建议后续优化：

- 应用列表模型区分三种状态：
  - `verifiedInstalled`：确认安装在工作资料。
  - `cachedOnly`：仅缓存存在，等待验证。
  - `systemCandidate`：系统候选入口。
- 安装失败时不要生成正式入口，只记录诊断信息。
- 移除时先执行工作资料卸载，成功后再删除缓存和图标文件。

相关文件：

- `app/src/main/java/com/system/launcher/tools/ui/home/AppManagementFragment.kt`
- `app/src/main/java/com/system/launcher/tools/ui/home/AppManagementViewModel.kt`
- `app/src/main/java/com/system/launcher/tools/work/WorkProfilePackageReceiver.kt`
- `app/src/main/java/com/system/launcher/tools/data/repository/ProfileAppStore.kt`

## 7. 文件管理模块

当前同时保留两个入口：

- 系统“文件”：调用系统文件管理/文档 UI。
- 自研“文件管理”：VeilSpace 内部文件空间模块。

自研文件管理当前能力：

- 顶部 Tab：图片 / 视频 / 全部文件。
- 图片网格展示缩略图。
- 视频列表展示文件名、大小、时长等信息。
- 图片支持全屏预览和左右滑动。
- 视频调用系统播放器播放。
- 支持多选和删除。
- UI 已做过一轮接近 MIUI 文件/相册风格的优化。

历史问题：

- 删除曾经第一次提示失败、第二次成功，已修过一轮。
- 视频扫描曾经漏检，已修过一轮。
- 系统文件入口曾经多出多个不可打开入口，目前目标是保留可打开的工作资料图标版本。

相关文件：

- `app/src/main/java/com/system/launcher/tools/ui/files/FilesFragment.kt`
- `app/src/main/java/com/system/launcher/tools/ui/files/FilesViewModel.kt`
- `app/src/main/java/com/system/launcher/tools/ui/files/ImageGridAdapter.kt`
- `app/src/main/java/com/system/launcher/tools/ui/files/FileListAdapter.kt`
- `app/src/main/java/com/system/launcher/tools/ui/files/ImagePreviewActivity.kt`

## 8. MIUI / HyperOS 设备限制

当前测试设备是小米/HyperOS 环境，存在明显厂商限制。

已观察到：

- shell 对工作资料用户的查询权限受限，例如 `pm list packages --user 12` 可能失败。
- 某些 MIUI 系统应用即使显示在工作资料，也可能无法正常启动。
- 小米手机管家在工作资料内可以显示，但点击后曾经一闪返回；可能依赖主空间特权服务或厂商私有能力。
- 主空间关闭“手机管家 - 病毒扫描/安装监控”后，某些安装限制会解除，但工作资料里未必有同一套设置入口。
- 小米应用商店、手机管家等系统应用不一定完整支持 Work Profile。

结论：

- Work Profile 可用于实现主目标，但厂商系统应用的完整复刻不可靠。
- 对 MIUI 系统工具应采用“能启动则接入，不能启动则降级”的策略，不建议把项目稳定性绑定在它们上面。

## 9. 当前已知问题

需要后续继续关注：

1. 小米手机管家在工作资料内显示但启动不稳定。
2. 部分 MIUI 系统设置入口无法从工作资料直接打开。
3. 工作资料桌面图标隐藏依赖系统行为，普通应用、后台应用、系统应用需要分策略处理。
4. README 仍是早期脚手架说明，已经落后于真实项目状态。
5. 应用安装、缓存、卸载状态建议继续做强一致性重构。
6. 缺少内置诊断日志页面，很多问题仍依赖 ADB logcat。
7. 尚未形成自动化测试或稳定的手动验收清单。

## 10. 推荐下一步

建议按模块拆分新对话继续：

### 模块 A：README 和文档整理

- 更新 README，反映真实项目状态。
- 增加安装、调试、设备限制说明。
- 增加验收清单。

### 模块 B：应用管理稳定性

- 重构应用列表状态模型。
- 将隐藏/启动/后台运行策略表集中管理。
- 完善安装失败诊断和缓存一致性。

### 模块 C：文件管理完善

- 继续优化多选、删除、排序、筛选、空状态。
- 增加文件详情、批量删除确认、刷新入口。

### 模块 D：内置诊断工具

- 在设置页增加“诊断信息”。
- 显示最近安装、启动、隐藏、卸载事件。
- 减少对 ADB 日志的依赖。

### 模块 E：MIUI 系统工具兼容性

- 不再假设系统工具一定可用。
- 对小米应用商店、手机管家、文件、联系人等分别建立启动策略和降级提示。

## 11. 调试约定

后续如果需要用户配合点击并抓日志：

- 必须设置明确停止时间，例如 30 秒、60 秒或 120 秒。
- 到时间后直接读取日志，不要无限等待。
- 日志关键词建议包含：
  - `WorkProfileManager`
  - `HomeViewModel`
  - `WorkProfilePackageReceiver`
  - `ActivityTaskManager`
  - `ActivityManager`
  - `AndroidRuntime`
  - `FATAL`
  - 目标应用包名

常用命令：

```powershell
adb logcat -c
adb logcat -v time > bounded_log.txt
```

注意：MIUI 下 shell 查询工作资料用户经常受限，不能完全依赖 `pm list packages --user <id>`。

## 12. Git 仓库状态

远端仓库：

- `https://github.com/Mr-csq/VeilSpace.git`

当前本地分支：

- `main`

当前仓库已经完成一次初始推送。

`.gitignore` 已排除根目录调试日志、截图、窗口 XML、临时 pid/path 和 `.claude/`。

## 13. 新对话接续提示词

新开对话时可以直接使用：

```text
这是 VeilSpace 项目。请先读取 docs/current_state.md、README.md 和相关源码，再继续处理以下模块：[写具体任务]。不要依赖旧聊天记录，以仓库当前代码为准。
```

如果是调试真机问题，可以补充：

```text
手机已连接并开启 USB 调试。如果需要我操作手机，请设置明确日志监听时长，到时间后直接分析日志。
```

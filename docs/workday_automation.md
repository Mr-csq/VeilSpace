# 工作日应用策略自动化

更新时间：2026-07-18

## 产品语义

自动化是一份全局配置，而不是每个应用一条独立规则。所选应用共享启用状态、开始时间、结束时间和日期模式。

开始边界对每个所选应用分别尝试：

1. 通过统一的 `ProfileAppKeepAliveController` 启用 VeilSpace `keepAlive`。
2. 取消工作资料内的应用隐藏状态。
3. 检查 Profile Owner、应用安装状态和 `POST_NOTIFICATIONS` 声明，然后使用 `DevicePolicyManager.setPermissionGrantState` 授权通知。

结束边界分别尝试：

1. 通过同一控制器关闭 `keepAlive`。
2. 拒绝目标应用的 `POST_NOTIFICATIONS`。
3. 应用在前台时不强制隐藏或中断；UsageStats 前台监视检测到应用退出后，才按既有自动隐藏策略处理。

`keepAlive` 是 VeilSpace 的自动隐藏领域策略。它不是 MIUI 电池优化、自启动、后台弹出界面或其他厂商私有授权。

## 只在边界执行

开始和结束之间没有状态轮询。开始后用户手动关闭 `keepAlive` 或通知权限，修改会一直保留到结束边界；结束后用户再次手动开启也会保留到下一次开始边界。

编辑并保存配置时会：

- 生成新的配置修订号；
- 取消旧 PendingIntent；
- 将保存时刻之前的最新边界标为新配置基线，避免回溯覆盖用户当前状态；
- 只安排新的下一边界。

## 中国法定工作日

`WorkdayProvider` 是可替换接口。内置 `ChinaLegalWorkdayProvider` 仅包含当前仍有调度价值的 2026 年节假日和调休补班表，来源为国务院办公厅年度通知；当前元数据更新时间为 2025-11-04，2026 年来源：

https://www.gov.cn/zhengce/zhengceku/202511/content_7047091.htm

普通周一至周五只有在数据年份受支持时才按工作日处理；内置表先覆盖法定放假日和周末补班。未知年份返回 `UNKNOWN`，不会用普通周末规则冒充法定工作日。界面展示数据年份、更新时间、来源，并允许切换到自定义星期兜底。若下一年度数据尚未内置，从当前数据最后一年的 11 月 1 日开始在工作模式页面提前警告；进入不受支持的年份后禁止保存法定工作日模式。2027 年数据将在国务院权威通知发布后再更新，不提前猜测。

## 调度与补偿

- Android 16 先检查 `canScheduleExactAlarms()`，可用时调用 `setExactAndAllowWhileIdle`。
- 未授权时使用可唤醒的非精确降级闹钟，界面明确标记“可能延迟”并提供系统授权入口。
- 每次只安排下一边界；边界完成后再安排后续边界。
- 开机、解锁、应用升级、时间/时区变化、工作资料可用/解锁及精确闹钟授权变化都会重新计算。

边界 ID 包含配置修订、工作日、边界类型和开始/结束分钟。已完成边界记录落盘；重复广播会被跳过。若进程在整个边界完成前退出，幂等的应用操作可以在下次恢复时重试。系统时间倒退不会重放早于已完成记录的边界。

若 Profile Owner 暂时不可用，该边界记录为未完成；工作资料恢复后只补偿尚未执行的最新边界。应用未安装、未声明通知权限或单个 OEM DPM 调用失败均只影响该应用，并写入最近执行结果。

当前前台延迟隐藏队列只保存在 `WorkProfileManager` 进程内存中。若进程在应用退出前台之前被杀死，边界可能已经标记完成，但最终隐藏动作不会自动恢复。这是需要持久化或在生命周期恢复时重新收敛的已知限制。

## 边界情况

- 开始与结束为同一分钟：无效，禁止保存。
- 结束时间早于开始时间：视为跨午夜，结束属于所选工作日的次日。
- 自定义星期为空：无效。
- 启用但没有所选应用：无效。
- 静态策略不允许用户 `keepAlive` 的应用：选择界面禁用并显示原因。
- 已卸载或不再存在的所选应用：保留为不可用记录，用户可取消选择；执行时不会伪造成功。
- 通知权限是独立动作，即使 `keepAlive` 被策略拒绝，通知动作仍会独立检查并记录结果。
- 从精确闹钟授权页返回会刷新已保存配置；当前页面尚未单独保存未提交草稿，授权前修改可能被覆盖。
- 选择列表当前动态创建 MaterialSwitch 行，应用较多时仍可考虑 RecyclerView 以控制布局成本。

## 关键代码

- `automation/AutomationScheduleCalculator.kt`：日期、跨日和下一边界计算。
- `automation/ChinaLegalWorkdayProvider.kt`：内置法定工作日数据。
- `automation/AutomationCoordinator.kt`：保存基线、一次性补偿、逐应用执行和结果记录。
- `automation/ExactAlarmScheduler.kt`：精确闹钟能力与降级。
- `automation/ProfileAppKeepAliveController.kt`：手动和自动 keepAlive 的统一入口。
- `automation/NotificationPermissionController.kt`：Android 16 DPM 通知权限控制。
- `ui/automation/AutomationFragment.kt`：配置、能力状态、下一边界和结果展示。

## 验证状态

2026-07-18：`AutomationScheduleCalculatorTest` 包含 2026 权威数据边界、过期年份裁剪和 2027 提前预警测试。Android 16 Debug 构建和 lint 均通过。尚未自动覆盖 AlarmManager、DevicePolicyManager、SharedPreferences 恢复、receiver 和 Profile Owner 真机链路。

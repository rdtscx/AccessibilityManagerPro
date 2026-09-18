# 无障碍管理器 Pro（Accessibility Manager Pro）v2.0.3

一款基于 Android 无障碍服务体系的**多功能管理器**。原理与原版「无障碍管理器（com.accessibilitymanager）」一致：
读取系统 `AccessibilityManager` 真实服务列表，通过 `WRITE_SECURE_SETTINGS`（ADB 授权 / Root / Shizuku 三种通道）
读写 `Settings.Secure`，实现服务与应用的自动化保活；并在此基础上扩展出**低耗电自监控、应用保活、通知 0-5 级调控、
开发者选项免跳转直改、应用无障碍权限总控、系统级权限管理（类似权限狗）、实验能力**等全新功能。

> ⚠️ 无障碍服务可读取屏幕内容（含账号、验证码等敏感信息）。本应用自身的无障碍服务**不读取屏幕内容**
> （仅监听窗口切换事件），但请在系统设置中确认授权对象后再启用。本应用仅供合法用途。

## v2.0.3 更新内容

### Bug 修复（重要）

- **修复 Shizuku 应用管理中不显示本应用、无法拉起授权的问题**：根因（参照 Shizuku 官方源码 `AuthorizationManager.kt` 第54-55行）是 Shizuku 应用管理页面识别应用需要**同时**满足两个条件：①声明权限 `moe.shizuku.manager.permission.API_V23`（v2.0.2 已添加）②在 `<application>` 中添加 meta-data `moe.shizuku.client.V3_SUPPORT` = true（v2.0.2 缺少）。v2.0.2 只添加了权限声明，缺少 meta-data，导致 Shizuku 应用管理仍然不显示本应用。修复后在 AndroidManifest.xml 的 `<application>` 标签下添加了该 meta-data，构建后用 aapt 验证 APK 中确实包含。

## v2.0.2 更新内容

### Bug 修复（重要）

- **修复 Shizuku 应用管理中找不到本应用的问题**：根因是 AndroidManifest.xml 中缺少 Shizuku API 权限声明 `moe.shizuku.manager.permission.API_V23`。Shizuku 通过查询哪些应用声明了此权限来展示应用管理列表，缺少声明则完全不显示。添加权限声明后，Shizuku 应用管理页面会显示"无障碍管理器 Pro"，用户可在 Shizuku 内授权。
- **修复本应用内无法拉起 Shizuku 授权的问题**：同样因缺少权限声明，`Shizuku.requestPermission()` 调用会失败。权限声明修复后，首页点击授权按钮可正常拉起 Shizuku 授权弹窗。
- **添加 queries 声明**：Android 11+ 包可见性，显式声明可查询 Shizuku 应用（`moe.shizuku.privileged.api`），避免检测 Shizuku 是否安装时 `getPackageInfo` 返回 false。

## v2.0.1 更新内容

### Bug 修复（参照 Shizuku 官方 API 文档）

- **修复 unbindUserService 调用错误（重要）**：原代码使用 `ctx.unbindService(conn)` 解绑 Shizuku UserService，这是完全错误的（该方法用于普通 Service，而非 Shizuku UserService）。`Shizuku.unbindUserService` 实际需要三个参数 `(UserServiceArgs, ServiceConnection, boolean remove)`；`remove=true` 时同时杀死远程 UserService 进程（Shizuku 不会自动杀死 UserService，需主动销毁）。
- **修复 shizukuExec 中 Parcel 资源泄漏**：原代码在 `binder.transact` 抛出异常时，`data.recycle()` 和 `reply.recycle()` 不会被调用。改用 `try-finally` 确保 Parcel 资源始终被回收。
- **添加 Shizuku Binder 死亡监听**：注册 `Shizuku.addBinderDeadListener`，Shizuku 服务被杀时及时清理 `shizukuBinder` / `shizukuBound` 状态，避免后续使用失效 binder 导致异常或状态错乱。
- **移除冗余的 `shizukuConn` 字段**（从未被使用，纯死代码）。
- **UserServiceArgs 完善配置**：设置 `version(1)` 和 `processNameSuffix("command")`，符合 Shizuku 官方推荐。
- **复用主线程 Handler**：避免每次 `ensureShizuku` 都创建新 Handler 对象。

### CommandService 优化

- **实现 UserService 销毁方法**（transaction code `16777115`，Shizuku 官方约定）：解绑时可主动停止 UserService 进程，避免 Shizuku 进程中残留服务实例。
- **命令执行增加 30 秒超时保护**：防止命令挂起导致 Shizuku 进程 binder 线程阻塞，超时后强制销毁进程并返回错误。

## v2.0.0 更新内容

### Bug 修复

- **修复无障碍服务恢复竞态条件（重要）**：原实现在异步恢复开始前就从 `restoringServices` 移除服务，导致恢复期间 `onSecureChanged` 可能再次触发并重复加入 pending，造成重复恢复和重复事件记录。改为恢复完成（成功或最终失败）后再移除，重试的服务保持在集合中，彻底杜绝重复恢复。
- **修复 accessibility_enabled 全局开关修复的类型推断编译错误**：`Privilege.runShell` 返回 `String?`，显式转换为 Boolean 判定。

### 性能与可靠性优化

- **优化 aliveCache 清理逻辑**：清理过期条目后若仍超上限，强制移除最旧的 1/4 条目，防止极端情况内存泄漏。
- **accessibility_enabled 全局开关修复增加事件记录**：检测到全局开关被系统关闭时记录 WARN 事件，修复成功后记录 RESTORED 事件，事件监控页可追溯。
- **事件监控页增加 Settings 变化监听**：`MonitorFragment` 注册 `AccessibilitySettingsObserver`，无障碍状态变化时自动刷新事件日志，无需下拉或退出重进。

## v1.9.2 更新内容

### 新增功能

- **从最近应用列表隐藏开关**（实验页 → 保活策略）：开启后本应用不出现在最近任务列表中，防止用户误划走后台导致无障碍服务被杀。通过 `ActivityManager.AppTask.setExcludeFromRecents` 动态控制，切换立即生效，支持备份/恢复。

### 小bug优化

- 修复 `SelfGuardService` / `WatchdogService` 中冗余的 Elvis operator（`substringBefore` 永不返回 null）。
- 修复 `attemptRestore` 中冗余的 message Elvis operator。
- 清理编译警告。

## v1.9.1 更新内容

### Bug 修复

- **修复无障碍授权页面不自动刷新的bug（重要）**：当用户停留在"无障碍权限"页面时，后台自监控自动拉起服务后，页面列表不会自动刷新开关状态（但实际拉起已生效）。新增 `AccessibilitySettingsObserver` 统一监听器工具类，`AccessibilityPermActivity` / `ServicesFragment` / `HomeFragment` 均注册监听，Settings.Secure 变化后 300ms 防抖自动刷新 UI。
- **修复 markSelfOperating 冷却期回调管理bug**：`removeCallbacksAndMessages(flatten)` 无法移除未绑定 token 的 Runnable，导致连续操作时旧回调可能提前清除冷却状态。改用 `Map<String, Runnable>` 精确管理每个服务的冷却期回调。

### 性能与可靠性优化

- **增加 accessibility_enabled 全局开关检查与自动修复**：若有受保护服务在启用列表中，但全局开关被系统关闭，自动修复为 1。
- **优化 BootReceiver 开机启动逻辑**：开机广播后延迟 5 秒启动服务（应用更新延迟 3 秒），避免系统服务未就绪时启动失败；使用 `applicationContext` 避免 BroadcastReceiver 生命周期问题。

## v1.9.0 更新内容

### Bug 修复

- **修复事件监控中丢失/恢复事件不显示被关闭服务真实包名的问题（重要）**：`SelfGuardService` 记录事件时 `pkg` 字段错误地传入本应用包名 `com.acsmanager.pro`，而非被关闭服务的包名（如 `me.ss.m`），导致事件监控页无法辨识是哪个软件的无障碍被关。现已按服务逐条记录，`pkg` 写入被关闭服务的真实包名；`EventAdapter` 支持将包名解析为应用名称显示（如 "MyGesture (me.ss.m)"）。
- **修复开启保活后无障碍服务被关闭的可靠性问题**：增加应用内开关操作冷却期标记（3秒），避免开关回写触发 ContentObserver 误报"丢失"；非自身服务恢复延迟缩短至最多 500ms，减少服务中断空窗期。

### 性能优化

- **恢复失败自动重试**：最多重试 3 次，指数退避（500ms / 1s / 2s），应对系统瞬时拒绝。
- **写入后回读验证**：确认服务真正出现在 `enabled_accessibility_services` 中，写入成功不等于系统已接受。
- **单服务逐条恢复**：新增 `restoreOne` 方法，单个服务恢复失败不影响其他服务。
- **恢复成功通知**：恢复成功后更新前台通知文字，让用户感知保活动作。

## v1.8.1 更新内容

### 功能增强

- **增强锁定保活可靠性（重要）**：点亮绿色锁图标后，无论任何情况下（应用掉后台、服务被杀、开机后），
  只要指定应用的无障碍服务被关闭，都能自动拉起。具体增强：
  - SelfGuardService 启动时立即检测并恢复缺失的锁定/开机保活服务（之前需等 5 分钟兜底或 Settings 变化）
  - 新增 `onTaskRemoved()` 兜底：用户从最近任务移除应用时自动重启自监控服务
  - 配合已有的 ContentObserver 实时监听 + 500ms 防抖 + 5 分钟兜底巡检，形成多层保障

### Bug 修复

- **修复底部菜单描述不准确**：「延迟一秒保活（全局）」改为「保活延迟（全局）」，因为延迟可设 0.5-10 秒，
  不只是一秒。
- 修复 Prefs.kt 中不必要的安全调用 lint 警告。

### 优化

- 自监控服务启动后立即执行一次恢复检测，开机/重启场景下零延迟恢复。
- 版本号升级至 1.8.1。

## v1.8.0 更新内容

### Bug 修复

- **修复无障碍保护误关其他应用权限（重要）**：自监控服务的 ContentObserver 缺少防抖，用户在本软件中操作开关时
  立即触发恢复检测，与用户写入形成竞态条件，可能互相覆盖导致开关状态错乱、"生效有时候不对应"。
  已添加 500ms 防抖合并连续变更。
- **修复被锁定服务无法正常关闭**：用户在本软件中主动关闭被锁定的服务时，自监控立即检测到丢失并自动拉起，
  导致用户无法关闭。新增"用户主动关闭冷却期"（默认 5 秒），冷却期内自监控不自动恢复该服务。
- **修复无障碍权限页 UI 与实际状态不一致**：`optimistic` 乐观状态永不清除，导致离开页面再回来后 UI 仍显示
  旧的乐观状态，与系统实际状态不符。已改为 `onResume()` 时清除乐观状态并重新读取系统真实状态。

### UI 优化

- **重绘锁定图标**：原图标视觉重心偏下，在圆形按钮中明显偏移。重新绘制实心锁和空心锁两个矢量图标，
  几何中心严格居中于 24x24 viewport，并调整按钮 inset 和 iconPadding 确保图标在圆形按钮中正中显示。

### 优化

- 自监控 ContentObserver 防抖合并，减少不必要的恢复检测和写入。
- 版本号升级至 1.8.0。

## v1.7.1 更新内容（紧急修复）

- **修复实验页屏幕闪烁 bug（重要）**：v1.7.0 中实验页主题按钮初始化时缺少 `programmaticSwitch` 保护，
  导致 `onResume` → `check()` → 触发 listener → `recreate()` → 重新走 `onResume` 的无限循环，
  表现为进入实验页后屏幕持续闪烁。已修复：主题按钮和所有开关初始化时均用 `programmaticSwitch` 保护，
  且主题切换时若模式未变化则不 `recreate`。
- **修复实验页开关状态错乱**：`onResume` 中设置开关状态时未保护，会触发互斥逻辑导致开关状态被意外修改。
- 修复 `KeepAliveEngine.resume()` 未使用变量 `ctx`。
- 修复 `Prefs.today()` 未使用参数 `ctx`。
- 移除 `HomeFragment` 中冗余的 `else` 分支（枚举已穷举）。
- lint 警告从 9 个减少至 6 个。

## v1.7.0 更新内容

- **修复冲突开关互斥（重要）**：「仅屏幕关闭时拉活」与「熄屏休眠省电」逻辑互斥（一个要求熄屏时保活，一个要求熄屏时暂停保活），
  现已实现互斥：开启其中一个时自动关闭另一个，避免用户同时开启导致行为矛盾。
- **状态栏颜色跟随主题**：浅色主题用浅色状态栏+深色图标，深色主题用深色状态栏+浅色图标，不再与主界面颜色突兀。
  主题切换或系统深色模式变化时自动同步。
- **实验页新增外观主题选择**：白天 / 黑夜 / 跟随系统三选一，按钮组切换，立即生效。
- **权限向导页面重写**：顶部新增大标题+整体状态芯片（已就绪/未就绪），卡片间距与内边距优化，
  新增「进入首页」按钮，整体观感与软件界面统一。
- **修复多个 lint 警告**：移除 `DevFragment` 未使用变量 `ch`、`ServiceDetailActivity` 两处冗余 Elvis 操作符。

## v1.6.0 更新内容

- **首次启动自动弹出授权引导页（新能力）**：应用首次打开时自动跳转到授权向导页，每 2 秒实时检测 Root / Shizuku / ADB
  三种通道状态；任一通道就绪后自动返回首页并标记引导完成。未授权时按返回键即可回到首页，不强制用户授权。
- **熄屏休眠省电模式（新能力）**：熄屏后自动暂停应用保活巡检、自监控兜底巡检从 5 分钟降频至 15 分钟，显著降低待机耗电；
  亮屏后 3 秒内自动恢复完整保活能力。实验页可开关，默认开启。
- **修复多个 lint 警告**：移除 `KeepAliveEngine` 未使用变量 `events`、`ServiceStateController` 两处未使用变量 `ch`、
  `ServiceDetailActivity` 未使用变量 `cn`，代码更干净。
- **GuideActivity 优化**：Shizuku 授权回调参数 `result` 未使用改为 `_`，返回键同时标记引导完成避免重复弹出。

## v1.5.0 更新内容

- **修复应用扫描缓存 bug（重要）**：`AppsRepository.scan()` 原先不区分 `includeSystem` 参数，先扫"仅用户应用"再扫"全部应用"
  时会返回错误的缓存结果。改为按参数分别缓存（`cachedAll` / `cachedUserOnly`），一次扫描同时生成两份列表，既修 bug 又省 IO。
- **修复事件日志数据库频繁创建连接的性能问题**：`EventLog` 原先每次 `record()` 都新建 `SQLiteOpenHelper` 和数据库连接，
  高频写入时开销大。改为单例连接 + 应用上下文，超过 2000 条自动清理最旧记录，防止数据库无限增长。
- **修复保活存活判定缓存内存泄漏**：`KeepAliveEngine.aliveCache` 原先只写入不清理，长期运行后条目无限增长。
  增加 128 条上限，超限时自动清理过期（>10 秒）条目。
- **修复通知精细调控失败误判**：`NotifGateService` 原先 shell 输出为空时被误判为失败（实际上 `settings`/`cmd` 命令
  成功时无输出）。改为与 `ServiceStateController` 一致的判定逻辑：空输出 = 成功，含 error/Exception 才是失败。
- **备份恢复功能大幅完善**：原先 `exportJson/importJson` 只备份看门狗相关 4 项设置，现已覆盖全部 20+ 项配置
  （自监控、应用保活名单与策略、通知等级、无障碍锁定/开机保活、主题语言、实验参数等），版本号升至 v2，
  兼容 v1 旧备份（缺失字段不覆盖用户当前设置）。
- **修复 Manifest 重复权限声明**：`FOREGROUND_SERVICE_SPECIAL_USE` 被声明了两次，合并为一条带 `targetApi="34"` 的声明。
- **迁移废弃 API**：
  - `App.kt`：`resources.updateConfiguration()` → `attachBaseContext` + `createConfigurationContext()`（标准语言切换方案）
  - `MainActivity`：`onBackPressed()` → `OnBackPressedDispatcher`（API 33+ 推荐）
  - `QuickToggleTileService`：`startActivityAndCollapse(Intent)` → `startActivityAndCollapse(PendingIntent)`（API 33+）
- **开源规范完善**：新增 MIT LICENSE、CONTRIBUTING.md、Issue/PR 模板，欢迎社区贡献。

## v1.4.1 更新内容

- **修复「保活看门狗」开关打开即闪退（重要）**：根因是前台服务用两参 `startForeground(id, notif)` 启动——
  Android 14+（targetSdk 34）强制要求传入与 manifest 匹配的服务类型，否则抛
  `MissingForegroundServiceTypeException` 使整个应用进程崩溃。已改为按系统版本三参调用
  （Android 14+ 传 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`，Android 9-13 传三参兼容重载，
  Android 8 及以下保持两参）；自监控服务同步修复（同源隐患）。
- **无障碍管理页锁定按钮观感优化**：未锁定的软件不再显示"开锁划掉"图标，改为**空心锁轮廓（灭灯）**
  配灰色调；锁定时为实心亮锁配绿色调，图标统一为"锁"形，整体观感更精细、状态更易辨识。
- **修复「关于」页版本号错误显示 1.0.0**：改为动态读取实际版本（当前 1.4.1），与构建版本始终一致。
- 顺手修复：首页开关刷新时程序回写触发监听器（可能造成重复启动/多余提示）的隐患，与设置页同款
  解绑-设值-重绑模式。
- 保持 v1.4.0 全部能力：Shizuku 主线程绑定+一键授权、快捷入口修复、多级存活判定保活、
  100ms 返回方案、黑屏保活开关、服务合并互斥。

## v1.4.0 更新内容

- **修复 Shizuku 通道不生效**：绑定 Shizuku 改为主线程执行 + 异步回调等待（最长 5 秒）+ 失败自动重试 1 次；
  首页新增 Shizuku 状态细分（已授权/已连接未授权/已安装未启动/未安装/需 Android 7.0+），已连接未授权时提供
  **一键授权按钮**，未启动时提供一键打开，不再需要跳转授权向导。
- **修复首页快捷入口「服务/监控/设置」点不进去（闪退）**：根因是打开子页面时程序回写底部导航选中项，从首页触发
  时赋同值仍触发选中监听、立即替换回首页。已移除回写逻辑。
- **修复应用保活不生效**：根因是 Android 12+ 且未授予「使用情况访问」时存活判定直接返回"存活"，永不拉起。
  存活判定重写为多级通道：当前前台 → 无障碍窗口事件（近 30 秒 O(1) 快速判定）→ 使用情况事件 → 进程列表
  （API≤30）→ Root/Shizuku `pidof` 精准判定 → 都不可用时视为掉线并拉起；判定结果 10 秒缓存（省电防卡顿）。
- **保活策略重构（按用户要求）**：检测到被保活应用失去服务/被杀后台后**立即拉起**，拉起 **100ms 后按用户选择的
  返回方案**处理：切回上一个软件（默认，无感）/ 停留在目标应用 / 返回桌面；新增「黑屏时也保活」开关
  （熄屏被杀也拉起，可关，默认开）；保留 10 秒前台切换硬约束（用户刚切换前台时不拉，避免闪烁打断操作）。
- **权限管理开关可用性修复**：`pm grant/revoke` 成功时输出为空字符串，此前被误判为失败；统一改为
  「输出为空 = 成功」，Root/Shizuku 通道下权限修改真正生效并即时反馈。
- **服务合并减负**：自监控（ContentObserver 实时）与看门狗（周期巡检）功能合并互斥——自监控运行时看门狗自动
  停止，任意时刻最多 1 个保活前台服务；自监控恢复范围扩展为「自身 + 锁定 + 看门狗保护名单 + 开机启动保活」。
- **纵览全软件排查按钮/开关可点性**：逐一核对全部页面绑定（首页/设置/服务/保活/监控/实验/开发者/通知/权限页），
  修复服务页与监控页加载异常时的整页闪退隐患（IO 异常兜底）。
- 保持 v1.3.0 全部能力：二级无障碍管理页、暗色人眼舒适配色、无障碍权限总控、权限管理器（权限狗式）、
  v1.1.0 全部能力（适配 Android 5.0(API 21)~17、release 1.77MB、低版本防闪退守卫、前台服务 specialUse 合规）。

## 功能总览

| 模块 | 说明 |
| --- | --- |
| 无障碍服务管理 | 搜索/过滤、启用数统计、能力徽章、启停（三通道 + 系统设置兜底） |
| 服务保活看门狗 | 周期巡检（30s/1m/5m），被系统重置后自动写回恢复 |
| **无障碍自监控（低耗电）** | **ContentObserver 监听 Settings.Secure 数据库变更（零轮询、零常驻 CPU 开销）；检测到本应用无障碍被关闭后，延迟 1 秒（可配 0.5-10s）立即通过授权通道拉起；恢复范围含自身+锁定+看门狗保护名单+开机保活；无通道时通知并跳转系统设置；v1.9 增强：失败自动重试3次、写入后回读验证、单服务逐条恢复、事件记录精准显示被关闭服务包名与应用名** |
| **应用保活（v1.4 重构）** | **扫描罗列全机用户/系统软件，勾选即保活；多级存活判定（窗口事件→使用情况→进程→pidof）；掉线/被停止后立即后台无感拉起，拉起 100ms 后按可选方案返回（切回上一软件/停留/回桌面）；黑屏时也保活可开关；10 秒前台硬约束防打断；单应用冷却防循环；低电量自动暂停** |
| **通知 0-5 级调控** | **每款软件独立 0-5 档：0 完全屏蔽 / 1 静默收纳 / 2 低打扰 / 3 标准 / 4 高亮 / 5 最高；0/1 档由通知使用权直接拦截；2/4/5 档在有授权通道且 Android 14+ 时升降级对方通知重要性** |
| **开发者选项映射** | **不跳转系统开发者选项，直接读写 Settings（动画三件套、不保留活动、强制 GPU、严格模式、显示指针位置、开发者总开关），写入即持久化、重启后永久生效；一键极速/极致/恢复默认** |
| **实验能力** | 事件采样降频（省电）、仅熄屏拉活、低电量暂停、保活巡检间隔、**从最近应用列表隐藏（防误划后台杀服务）**、通知历史、精细调控通道开关、Root 自检、拉起测试 |
| **无障碍权限总控（v1.2）** | **首页授权状态卡片入口：拉取全机用户/系统软件的无障碍服务，开关直接启用/停用；锁定按钮点亮后，被关闭自动无感拉起（ContentObserver 延迟 1 秒恢复）；v1.9.1 起页面实时监听 Settings 变化，后台拉起后开关状态自动刷新，无需退出重进** |
| **无障碍权限页·二级管理（v1.3）** | **类系统"无障碍管理"页：图标+应用名+服务描述+开关卡片列表；点击弹勾选菜单（锁定/开机保活/Toast 提示/延迟/仅用户应用/自定义通知文字）；开关防抖+乐观更新，不再循环弹 Toast** |
| **暗色模式（v1.3）** | **按人眼舒适标准重配色：背景 #121212 系、正文 #E0E0E0、辅助文字 #CAC4D0、主色暗色 200 档亮蓝、柔和状态色；小部件同步适配** |
| **权限管理·系统级（v1.2）** | **类似权限狗：全机应用列表 → 点开软件查看危险权限组与授予状态；开关经 Root/Shizuku 执行 pm grant/revoke，立即生效并持久；无通道时提供 ADB 命令一键复制** |
| 事件监控 | 启用/停用/丢失/自动恢复事件时间线（SQLite）；v2.0 起页面实时监听 Settings 变化自动刷新，无需手动下拉 |
| 快捷设置磁贴 / 桌面小部件 | 下拉/桌面一键启停目标服务 |
| 授权向导 / 备份恢复 / 深色模式 / 中英文 | 三通道分步引导与检测；JSON 备份；Material3 现代化界面 |

## 架构

见 [docs/architecture.html](docs/architecture.html)（分层结构与四条自动化闭环示意图）。

```
app/src/main/java/com/acsmanager/pro/
├── core/        # 服务仓库、授权通道(Privilege)、状态控制器、权限组定义(PermGroups)、事件日志、Settings变化监听器(AccessibilitySettingsObserver)
├── selfguard/   # 自监控前台服务(ContentObserver：自身+锁定服务统一恢复) + 本应用低耗电无障碍服务
├── keepalive/   # 应用扫描仓库 + 保活引擎（事件驱动+低频巡检，后台无感拉起）
├── notif/       # 通知 0-5 级调控引擎（NotificationListenerService）
├── dev/         # 开发者选项映射（直写 Settings + Root/Shizuku 兜底）
├── watchdog/    # 服务保活看门狗前台服务 + 开机自启接收器
├── shizuku/     # Shizuku UserService 命令执行服务(:shizuku 进程)
├── quick/       # 快捷设置磁贴 + 桌面小部件
├── ui/          # 首页/保活/通知/开发者/实验 + 服务管理/监控/设置 + 授权向导 + 无障碍权限总控 + 权限管理
└── util/        # 偏好设置（含保活名单、通知等级、无障碍锁定集合、统计、实验配置）
```

## 使用步骤

1. 安装 APK 打开，进入「首页」开启本应用的无障碍服务（系统设置中勾选「无障碍自监控服务」）。
2. （推荐）完成任一授权通道，体验完整能力（授权向导在首页）：
   - **ADB**：`adb shell pm grant com.acsmanager.pro android.permission.WRITE_SECURE_SETTINGS`
   - **Root**：设备已 Root 即自动使用 `su`
   - **Shizuku**：安装 Shizuku 并启动，应用内点「授权」（需 Android 7.0+）
3. **自监控**：首页打开「无障碍自监控」开关 → 即使无障碍被系统/用户关闭，也会在延迟 1 秒后自动恢复。
4. **应用保活**：「保活」页勾选软件（建议先点"授予使用情况访问"），掉线后自动后台无感拉起。
5. **无障碍权限总控**：首页「授权状态」卡片 →「无障碍权限」→ 拉取全机无障碍服务；开关直接启停，点亮锁定后关闭自动无感拉起。
6. **权限管理**：首页「授权状态」卡片 →「权限管理」→ 点开软件 → 系统级修改危险权限（Root/Shizuku 直改；无通道复制 ADB 命令）。
7. **通知调控**：「通知」页授予通知使用权后，用滑杆调节每款软件 0-5 级。
8. **开发者选项**：「开发者」页直接开关/滑杆调节，无需跳转系统设置，永久生效。
9. **实验**：「实验」页按需开关降频、策略等能力，并用自检工具验证通道状态。其中「从最近应用列表隐藏」开启后可防止用户误划走后台导致服务被杀。

## 授权与权限说明

- `WRITE_SECURE_SETTINGS`：系统签名级权限，需 ADB `pm grant` 或 Shizuku/Root 通道。用于无障碍自监控写回、开发者选项受保护键。
- `QUERY_ALL_PACKAGES`：枚举全机用户/系统软件（保活/通知列表）。
- `PACKAGE_USAGE_STATS`：精准判定目标应用是否掉线；需在「使用情况访问」中授予本应用。
- 通知使用权（NotificationListenerService）：0/1 档拦截与静默收纳的前置条件。
- `POST_NOTIFICATIONS`（Android 13+）：前台服务通知展示，可拒绝不影响功能。
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`（Android 14+）：specialUse 前台服务运行所需。

## 已知限制（如实说明）

- **无授权通道时无法静默自启无障碍**：系统不允许应用在被关闭后自行重新启用无障碍服务；此时自监控会发通知并跳转系统设置引导手动开启。完成任一通道授权后即可全自动。
- **Android 10+ 后台启动限制**：本应用通过无障碍服务上下文拉起（系统豁免），仅对已授权无障碍且在保活名单中的应用生效。
- **通知精细调控（2/4/5 档升降级）**：需要授权通道且系统为 Android 14+（`cmd notification set_importance_override`）；否则 2/4/5 档为放行。
- **强行停止（Force-stop）的应用**：系统禁止第三方直接拉起，需用户手动打开一次后保活才生效。
- **权限管理器通道限制**：`pm grant/revoke` 需要 shell 身份，仅 Root / Shizuku 通道可应用内直接执行；ADB 通道（WRITE_SECURE_SETTINGS）仅能写 Settings，权限修改降级为展示/复制 ADB 命令。权限修改仅作用于危险运行时权限，系统签名权限不可改。
- **无障碍开关通道限制**：应用内直接启停无障碍需任一授权通道；无通道时开关引导至系统设置手动完成（锁定服务的自动恢复同样依赖通道）。
- **低版本功能降级**：API 21-23 无 Shizuku 通道；API 21-28 通知精细升降级不可用（`getUid` 需 API 29+）；API 21-26 通知监听状态用 Settings 字符串判定。

## 构建

环境要求：JDK 17、Android SDK（platform 34 + build-tools 34）。**最低支持 Android 5.0（API 21）**。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug        # debug 产物：app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # release 产物：app/build/outputs/apk/release/app-release.apk（R8 + 资源压缩 + release.keystore 签名）
```

release 构建默认启用 R8 混淆与资源压缩，仅打包中/英语言资源（`resConfigs("zh","en")`），签名密钥见项目根 `release.keystore`
（alias=acsmanager；新证书，与 v1.0.0 debug 证书不同，升级安装需先卸载旧版）。

## 隐私

- 本应用无障碍服务**不读取屏幕内容**（`canRetrieveWindowContent=false`），仅接收窗口切换事件用于保活判定。
- 全部配置（保活名单、通知等级、统计）仅存本机；通知拦截历史仅存本机（最多 200 条，可在实验页关闭）。
- 不上传任何数据，无网络权限。

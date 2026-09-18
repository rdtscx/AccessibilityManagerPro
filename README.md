# 无障碍管理器 Pro（Accessibility Manager Pro）

> 关于 v3.0.0，先说明几句：
>
> 这是最后一个版本了。功能到这里基本做完，以后不会再更新，Issue 和 PR 也不再处理，感谢大家一直以来的反馈。
>
> - 不联网。清单文件里没有声明网络权限，App 物理上就上不了网；不放心的可以拿 `aapt dump permissions` 自己查。
> - 没有广告，也没接任何统计、崩溃上报、推送之类的 SDK。
> - 不收集数据，所有配置和日志都只存在本机。
> - 代码全部开源（MIT），想看想看、想自己编译都行。

这是一个管理 Android 无障碍服务的小工具。思路和原来那个「无障碍管理器」（com.accessibilitymanager）一样：从系统的 AccessibilityManager 读出真实的服务列表，靠 `WRITE_SECURE_SETTINGS` 权限（ADB 授权、Root、Shizuku 三种方式都可以）读写 `Settings.Secure`，从而把服务和应用自动保活。在这之上又陆续加了一些功能：自监控、应用保活、通知分级、开发者选项直接改、无障碍权限总控、权限管理（类似权限狗）等。

无障碍服务能读到屏幕内容，包括账号、验证码这类敏感信息，开启前一定看清楚授权对象。本应用自己的无障碍服务不读屏幕内容（`canRetrieveWindowContent=false`），只监听窗口切换，用来判断应用前后台。请合法使用。

---

## 更新记录

### v3.0.0（最终版）

这个版本把三条授权通道从头到尾理了一遍，压了压后台耗电，也修了几个遗留问题。

授权通道：

- Root 探测加了缓存。之前每次判断当前通道都会跑一次 `su -c id`，后台保活和自监控巡检时就会不停拉起 su 进程，费电不说，部分 Root 管理器还会反复弹授权框。现在探测成功会记住结果，失败后 15 秒内不重复试；如果 su 明确返回拒绝或不存在，会自动重新探测。
- Root 和 Shizuku 执行命令统一做了参数转义。之前各处是手写 `su -c "..."` 拼字符串，参数里带空格或特殊字符时可能被 shell 拆错，现在都收敛到同一个方法处理。
- shell 命令补上了超时。原来代码里那个超时参数其实一直没生效（调的是不带超时的 `waitFor()`），万一命令卡住会一直占着线程；现在到时间会强制结束进程并返回。
- Shizuku 服务被杀或重启后，会一并清掉缓存的 UserService 连接，免得拿着失效连接继续发命令、最后静默失败。
- 把 ADB 授权（WRITE_SECURE_SETTINGS）能干什么、不能干什么理清楚了：写 `Settings.Secure/Global` 没问题；改 `Settings.System`、`pm grant`、`pidof` 这些需要 shell 身份的操作，光有 ADB 授权做不了，会明确提示或给出可复制的 ADB 命令，不再假装成功。
- 三条通道判断命令成败的口径统一：没有输出算成功，输出里带 error/Exception 才算失败。

耗电：

- 看门狗在保护名单为空时不再定时唤醒（之前哪怕没东西要守，也照样按间隔醒着）；往名单里加服务后会自动恢复巡检。
- 应用保活查询「使用情况访问」权限的结果缓存 60 秒，`pidof` 的判断结果缓存 20 秒，保活目标多时能少 fork 不少进程。

修复：

- 自监控修复 `accessibility_enabled` 总开关时，走 Shizuku 不管成功失败都当成成功，现在会看实际返回结果。
- Root 命令卡住可能把后台恢复流程一起拖死，靠上面的超时解决。
- Shizuku 断开后残留的连接状态，见上面通道部分。
- 几处命令成败判断不准导致的误报，一并改了。

### v2.0.4

修 Shizuku 的两个问题：在 Shizuku 应用管理里给本应用打开开关后不生效，而且打开一次本应用后，它会从 Shizuku 的应用管理列表里消失。

查下来是两处配置的问题：

1. `CommandService` 在清单里写了 `android:process=":shizuku"`。UserService 的进程本来就该由 Shizuku 自己管（进程名由 `UserServiceArgs.processNameSuffix` 决定），手动指定反而让 Shizuku 在校验时认为配置不对，开关开了也授权不上，等本应用一启动还会被当成异常客户端移出列表。这个属性已经删掉。
2. Application 没有注册 Shizuku V3 要求的 `OnBinderReceivedListener`。Shizuku API 13 用的是 V3 协议，需要客户端在 Application 启动时注册连接/断开监听，服务端靠这个判断客户端是否正常。现在在 `App.kt` 里统一注册了接收和死亡两个监听，`Privilege.kt` 也优先用监听器维护的连接状态，不再每次都去 ping。

### v2.0.3

接着修 Shizuku 应用管理里看不到本应用的问题。对照 Shizuku 官方源码（`AuthorizationManager.kt`）看，应用管理页识别一个应用要同时满足两个条件：一是声明 `moe.shizuku.manager.permission.API_V23` 权限（v2.0.2 已加），二是在 `<application>` 里加一条 `moe.shizuku.client.V3_SUPPORT` = true 的 meta-data（v2.0.2 漏了）。补上 meta-data 后，构建出来的 APK 用 aapt 验证过，确实包含这条声明。

### v2.0.2

- Shizuku 应用管理里找不到本应用：清单里少了 `moe.shizuku.manager.permission.API_V23` 权限声明，Shizuku 是靠查哪些应用声明了这个权限来列应用管理列表的，没声明就完全不显示。补上后列表里能看到「无障碍管理器 Pro」，可以直接在 Shizuku 里授权。
- 应用内拉不起 Shizuku 授权：同样是缺权限声明导致 `Shizuku.requestPermission()` 调用失败，补上后首页点授权能正常弹 Shizuku 的授权窗。
- 加了 `<queries>` 声明：Android 11 以后包可见性受限，显式声明可以查询 Shizuku（`moe.shizuku.privileged.api`），否则判断 Shizuku 装没装时 `getPackageInfo` 会返回 false。

### v2.0.1

这版照着 Shizuku 官方 API 文档改了几处：

- 解绑 UserService 用错了方法。原来调的是 `ctx.unbindService(conn)`，那是给普通 Service 用的；Shizuku 的 UserService 得用三参的 `Shizuku.unbindUserService(args, conn, remove)`，最后一个参数传 true 时会顺带把远程 UserService 进程杀掉（Shizuku 自己不会杀，得手动来）。
- `shizukuExec` 里 Parcel 有泄漏风险：`binder.transact` 一旦抛异常，`recycle()` 就走不到。改成 try-finally 保证回收。
- 加了 Shizuku Binder 死亡监听，服务被杀时及时清掉 `shizukuBinder` / `shizukuBound`，免得后面拿着失效的 binder 用。
- 删掉一个从没用到的 `shizukuConn` 字段（死代码）。
- UserServiceArgs 补全 `version(1)` 和 `processNameSuffix("command")`，跟官方推荐一致；主线程 Handler 复用，不再每次绑定都新建。
- CommandService 实现了销毁方法（transaction code `16777115`，官方约定的），解绑时能主动停掉 UserService 进程；命令执行加了 30 秒超时，防止卡住堵住 Shizuku 的 binder 线程。

### v2.0.0

- 修了一个无障碍服务恢复的竞态：原来异步恢复一开始就把服务从 `restoringServices` 里移除，恢复期间如果 `onSecureChanged` 又触发，会重复加入待恢复、重复恢复、重复记日志。改成恢复结束（成功或彻底失败）后再移除，重试中的服务一直留在集合里。
- 修了修复 `accessibility_enabled` 总开关时的一个类型推断编译错误（`runShell` 返回 `String?`，显式按布尔判断）。
- aliveCache 清理更稳：清掉过期条目后如果还超上限，会把最旧的四分之一也移除，避免极端情况下内存一直涨。
- 修复总开关会记事件了：检测到总开关被系统关记 WARN，修回来记 RESTORED，监控页能查到。
- 事件监控页加了 Settings 变化监听，无障碍状态一变日志自动刷新，不用手动下拉或退出重进。

### v1.9.2

- 新增「从最近应用列表隐藏」开关（实验页 → 保活策略）。打开后本应用不出现在最近任务里，避免手滑把后台划掉、连带无障碍服务被杀。用 `ActivityManager.AppTask.setExcludeFromRecents` 动态控制，切换即时生效，也支持备份恢复。
- 清理了两个 SelfGuardService / WatchdogService 里多余的 Elvis 写法（`substringBefore` 本来就不会返回 null），以及一些编译警告。

### v1.9.1

- 修无障碍授权页不自动刷新：人停在「无障碍权限」页时，后台自监控把服务拉起来后，页面上的开关状态不会跟着变（其实已经生效了）。新增了一个统一的 `AccessibilitySettingsObserver`，授权页、服务页、首页都注册上，Settings.Secure 一变，300ms 防抖后自动刷新。
- 修 markSelfOperating 冷却期回调管理：`removeCallbacksAndMessages(flatten)` 没法移除没绑 token 的 Runnable，连续操作时旧回调可能提前把冷却状态清掉。改成用 `Map<String, Runnable>` 逐个精确管理。
- 加了 accessibility_enabled 总开关的检查和自动修复：受保护服务还在启用列表里、但总开关被系统关了时，会自动写回 1。
- BootReceiver 开机逻辑调整：开机广播后延迟 5 秒再启动服务（应用更新场景延迟 3 秒），避开系统服务还没就绪导致启动失败；用 applicationContext，避免 BroadcastReceiver 生命周期问题。

### v1.9.0

- 修事件监控里丢失/恢复事件显示错包名：自监控记事件时 `pkg` 填的是本应用包名 `com.acsmanager.pro`，而不是被关服务的包名（比如 me.ss.m），监控页根本看不出是哪个软件的无障碍被关了。改成逐条记录真实包名，列表里也能把包名解析成应用名显示（如 MyGesture (me.ss.m)）。
- 保活时无障碍被关的可靠性：应用内操作开关加了 3 秒冷却标记，避免开关回写触发 ContentObserver 误报"丢失"；非自身服务的恢复延迟缩到最多 500ms，中断空窗更短。
- 恢复失败会自动重试，最多 3 次，按 500ms / 1s / 2s 退避，应对系统瞬时拒绝。
- 写入后回读确认，确保服务真的进了 `enabled_accessibility_services`（写成功不等于系统接受了）。
- 改成单服务逐条恢复，一个失败不连累其他；恢复成功会更新前台通知文字。

### v1.8.1

- 锁定保活更可靠了：点亮绿色锁之后，不管是掉后台、服务被杀还是重启，只要对应无障碍被关都会自动拉起。自监控启动时会立刻检测并恢复缺失的锁定/开机保活服务（以前要等 5 分钟兜底或 Settings 变化）；新增 `onTaskRemoved()` 兜底，从最近任务划掉本应用时会自动重启自监控。配合原来的 ContentObserver 实时监听 + 500ms 防抖 + 5 分钟兜底巡检。
- 底部菜单「延迟一秒保活（全局）」改成「保活延迟（全局）」，因为延迟能设 0.5–10 秒，不止一秒。
- 修了 Prefs.kt 里一个没必要的安全调用 lint 警告；自监控启动后立即跑一次恢复检测，开机/重启场景零延迟恢复。

### v1.8.0

- 修保护逻辑误关其他应用权限：自监控的 ContentObserver 没做防抖，在本应用里拨开关时会立刻触发恢复检测，和用户的写入互相抢，可能互相覆盖导致开关状态错乱（也就是有人反馈的"有时候不对应"）。加了 500ms 防抖，把连续变更合并。
- 修被锁定的服务关不掉：用户主动关锁定服务时，自监控马上检测到"丢失"又拉起来，导致关不了。加了个用户主动关闭的冷却期（默认 5 秒），冷却期内不自动恢复这个服务。
- 修无障碍权限页 UI 和实际状态不一致：乐观状态（optimistic）一直不清，离开页面再回来还显示旧状态。改成 `onResume()` 时清掉乐观状态、重新读系统真实值。
- 重画了锁定图标：原来的图标视觉重心偏下，在圆按钮里明显歪。实心锁、空心锁两个矢量图重新画，几何中心对齐 24x24 viewport，按钮 inset 和 iconPadding 也调了，保证在圆按钮里居中。

### v1.7.1（紧急修复）

- 修实验页屏幕一直闪：v1.7.0 实验页主题按钮初始化时少了 `programmaticSwitch` 保护，导致 onResume → check() → 触发 listener → recreate() → 又 onResume 的死循环，一进实验页就闪个不停。现在主题按钮和所有开关初始化都加了保护，主题模式没变时也不 recreate。
- 修实验页开关状态错乱：onResume 里设开关状态时没保护，会触发互斥逻辑把状态意外改掉。
- 顺手清了几个未使用变量和多余分支，lint 警告从 9 个降到 6 个。

### v1.7.0

- 「仅熄屏拉活」和「熄屏休眠省电」本来是互斥的（一个要求熄屏时保活，一个要求熄屏时暂停），之前能同时开，行为自相矛盾。现在做成互斥，开一个会自动关另一个。
- 状态栏颜色跟随主题：浅色主题用浅色栏+深色图标，深色主题反过来，主题切换或系统深色模式变化时自动同步。
- 实验页加了外观主题选择：白天 / 黑夜 / 跟随系统，按钮组切换即时生效。
- 授权引导页重写：顶部加了大标题和整体状态芯片（已就绪/未就绪），卡片间距调整，加了「进入首页」按钮，风格和主界面统一。
- 清了几个 lint 警告。

### v1.6.0

- 首次启动自动弹授权引导页，每 2 秒检测一次 Root / Shizuku / ADB 状态，任一通道就绪就自动回首页并标记引导完成；不想授权按返回也能进首页，不强制。
- 新增熄屏休眠省电：熄屏后暂停应用保活巡检，自监控兜底巡检从 5 分钟降到 15 分钟，待机更省电；亮屏 3 秒内恢复完整保活。实验页可关，默认开。
- GuideActivity 小改，清理未使用变量和 lint 警告。

### v1.5.0

- 修应用扫描缓存：`AppsRepository.scan()` 原来不区分 `includeSystem` 参数，先扫"仅用户应用"再扫"全部应用"会拿到错误的缓存结果。改成按参数分别缓存，一次扫描出两份列表，既修了 bug 又省 IO。
- 修事件日志频繁建连接：`EventLog` 原来每次 `record()` 都新建 SQLiteOpenHelper 和数据库连接，高频写入时开销大。改成单例连接 + 应用上下文，超过 2000 条自动清最旧的，避免数据库无限涨。
- 修保活存活判定缓存泄漏：`aliveCache` 原来只进不出，跑久了条目无限增长。加了 128 条上限，超了先清过期（>10 秒）条目。
- 修通知调控失败误判：`NotifGateService` 原来把空输出当失败（其实 settings/cmd 成功时本来就没输出），改成和 ServiceStateController 一致：空输出算成功。
- 备份恢复补全：原来只备份看门狗相关的 4 项，现在覆盖全部 20 多项配置（自监控、保活名单和策略、通知等级、锁定/开机保活、主题语言、实验参数等），备份格式升到 v2，仍兼容 v1 旧备份（缺的字段不覆盖当前设置）。
- 修清单里 `FOREGROUND_SERVICE_SPECIAL_USE` 重复声明，合并成一条带 `targetApi="34"` 的。
- 迁移废弃 API：语言切换改用 attachBaseContext + createConfigurationContext；返回键改用 OnBackPressedDispatcher；磁贴的 startActivityAndCollapse 改用 PendingIntent 版本（API 33+）。
- 补了 MIT LICENSE、CONTRIBUTING.md 和 Issue/PR 模板。

### v1.4.1

- 修「保活看门狗」开关一开就闪退：前台服务用了两参的 `startForeground(id, notif)`，Android 14（targetSdk 34）强制要求传和清单匹配的服务类型，不然直接抛 `MissingForegroundServiceTypeException` 崩进程。改成按版本三参调用（14+ 传 `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`，9–13 用三参兼容重载，8 及以下保持两参），自监控服务同样的问题一起修了。
- 锁定按钮观感调整：没锁定的不再显示"开锁划掉"图标，改成灰色空心锁轮廓；锁定时是绿色实心亮锁，统一成锁的形状，状态更好分辨。
- 「关于」页版本号原来错误显示 1.0.0，改成动态读实际版本。
- 顺手修了首页开关刷新时程序回写触发监听器（可能重复启动、多余提示）的问题，和设置页一样用解绑-设值-重绑。

### v1.4.0

- 修 Shizuku 通道不生效：绑定改到主线程执行 + 异步回调等待（最长 5 秒）+ 失败自动重试一次；首页把 Shizuku 状态细分显示（已授权 / 已连接未授权 / 已安装未启动 / 未安装 / 需 Android 7.0+），已连接没授权时有一键授权按钮，没启动时一键打开，不用再跑授权向导。
- 修首页「服务/监控/设置」快捷入口点不进去（闪退）：打开子页面时程序回写底部导航选中项，从首页触发时赋同值仍会触发监听、立刻被切回首页，回写逻辑已移除。
- 修应用保活不生效：Android 12+ 且没授予「使用情况访问」时，存活判定直接返回"存活"，永远不会拉起。存活判定重写成多级：当前前台 → 无障碍窗口事件（近 30 秒快速判定）→ 使用情况事件 → 进程列表（API≤30）→ Root/Shizuku `pidof` → 都判定不到才算掉线并拉起；结果缓存 10 秒，省电也防卡。
- 保活策略调整：检测到目标应用掉服务/被杀后立刻拉起，拉起 100ms 后按用户选的方案处理（切回上一个软件，默认且无感 / 停在目标应用 / 回桌面）；新增「黑屏时也保活」开关（熄屏被杀也拉，可关，默认开）；保留 10 秒前台切换硬约束，用户刚切到前台时不拉，避免闪屏打断。
- 修权限管理开关：`pm grant/revoke` 成功时输出为空，之前被误判失败，改成"空输出=成功"，Root/Shizuku 下改权限能真正生效并即时反馈。
- 服务合并减负：自监控（ContentObserver 实时）和看门狗（周期巡检）互斥，自监控运行时看门狗自动停，任何时候最多一个保活前台服务；自监控恢复范围扩成「自身 + 锁定 + 看门狗保护名单 + 开机保活」。
- 把所有页面的按钮/开关可点性通通过了一遍，修了服务页和监控页加载异常时整页闪退的隐患（IO 异常兜底）。

---

## 功能一览

| 模块 | 说明 |
| --- | --- |
| 无障碍服务管理 | 搜索/过滤、启用数统计、能力徽章、启停（三种通道 + 系统设置兜底） |
| 服务保活看门狗 | 周期巡检（30s / 1m / 5m），被系统重置后自动写回 |
| 无障碍自监控 | 用 ContentObserver 监听 Settings.Secure 变化，不轮询；本应用无障碍被关后延迟 1 秒（0.5–10s 可配）通过授权通道拉起；恢复范围含自身、锁定、看门狗名单、开机保活；没通道时发通知并跳系统设置。失败自动重试 3 次、写入后回读、单服务逐条恢复、事件里能看到被关服务的真实包名和应用名 |
| 应用保活 | 列出全机用户/系统软件，勾选即保活；多级存活判定（窗口事件→使用情况→进程→pidof）；掉线或被停后后台无感拉起，100ms 后按所选方案返回；黑屏也保活可开关；10 秒前台硬约束防打断；单应用冷却防循环；低电量自动暂停 |
| 通知 0–5 级调控 | 每个应用单独设 0–5 档：0 完全屏蔽 / 1 静默收纳 / 2 低打扰 / 3 标准 / 4 高亮 / 5 最高；0/1 档靠通知使用权直接拦截，2/4/5 档在有通道且 Android 14+ 时调整对方通知重要性 |
| 开发者选项映射 | 不用跳系统开发者选项，直接读写 Settings（动画三件套、不保留活动、强制 GPU、严格模式、显示指针位置、开发者总开关），写入即持久，重启仍在；一键极速/极致/恢复默认 |
| 实验能力 | 事件采样降频（省电）、仅熄屏拉活、低电量暂停、保活巡检间隔、从最近应用列表隐藏（防误划杀服务）、通知历史、精细调控通道开关、Root 自检、拉起测试 |
| 无障碍权限总控 | 首页授权状态卡片进入：列出全机应用的无障碍服务，开关直接启停；点亮锁定后被关会无感拉起；页面实时监听 Settings 变化，后台拉起后开关自动刷新 |
| 无障碍权限页·二级管理 | 类似系统"无障碍管理"页：图标+应用名+服务描述+开关卡片；点卡片弹勾选菜单（锁定/开机保活/Toast 提示/延迟/仅用户应用/自定义通知文字）；开关防抖+乐观更新，不循环弹 Toast |
| 暗色模式 | 深色配色：背景 #121212 系、正文 #E0E0E0、辅助文字 #CAC4D0、主色暗色 200 档亮蓝、柔和状态色；小部件同步适配 |
| 权限管理（系统级） | 类似权限狗：应用列表 → 点开看危险权限组和授予状态；开关经 Root/Shizuku 执行 pm grant/revoke，即时生效并持久；没通道时提供一键复制的 ADB 命令 |
| 事件监控 | 启用/停用/丢失/自动恢复的事件时间线（SQLite）；页面实时监听 Settings 变化自动刷新 |
| 快捷设置磁贴 / 桌面小部件 | 下拉或桌面一键启停指定服务 |
| 授权向导 / 备份恢复 / 深色模式 / 中英文 | 三通道分步引导检测；JSON 备份；Material3 界面 |

## 架构

分层结构和四条自动化闭环示意图见 [docs/architecture.html](docs/architecture.html)。

```
app/src/main/java/com/acsmanager/pro/
├── core/        # 服务仓库、授权通道(Privilege)、状态控制器、权限组(PermGroups)、事件日志、Settings 变化监听
├── selfguard/   # 自监控前台服务(ContentObserver：自身+锁定服务统一恢复) + 本应用低耗电无障碍服务
├── keepalive/   # 应用扫描仓库 + 保活引擎（事件驱动+低频巡检，后台无感拉起）
├── notif/       # 通知 0-5 级调控引擎（NotificationListenerService）
├── dev/         # 开发者选项映射（直写 Settings + Root/Shizuku 兜底）
├── watchdog/    # 服务保活看门狗前台服务 + 开机自启接收器
├── shizuku/     # Shizuku UserService 命令执行服务（Shizuku 在独立进程启动，有 shell 权限）
├── quick/       # 快捷设置磁贴 + 桌面小部件
├── ui/          # 首页/保活/通知/开发者/实验 + 服务管理/监控/设置 + 授权向导 + 无障碍权限总控 + 权限管理
└── util/        # 偏好设置（保活名单、通知等级、锁定集合、统计、实验配置）
```

## 使用步骤

1. 装 APK 打开，在「首页」开启本应用的无障碍服务（系统设置里勾「无障碍自监控服务」）。
2. 建议顺手配一个授权通道，功能才完整（入口在首页）：
   - ADB：`adb shell pm grant com.acsmanager.pro android.permission.WRITE_SECURE_SETTINGS`
   - Root：设备已 Root 会自动用 `su`
   - Shizuku：装好 Shizuku 并启动，应用内点「授权」（需 Android 7.0+）
3. 自监控：首页打开「无障碍自监控」开关，无障碍被系统或用户关掉后会延迟 1 秒自动恢复。
4. 应用保活：「保活」页勾选要保的软件（建议先授予"使用情况访问"），掉线后自动后台拉起。
5. 无障碍权限总控：首页「授权状态」卡片 →「无障碍权限」，列出全机无障碍服务，开关直接启停，点亮锁定后被关自动拉起。
6. 权限管理：首页「授权状态」卡片 →「权限管理」，点开应用可改危险权限（Root/Shizuku 直接改；没通道复制 ADB 命令）。
7. 通知调控：「通知」页授予通知使用权后，用滑杆给每个应用设 0–5 级。
8. 开发者选项：「开发者」页直接开关/拖动，不用跳系统设置，重启仍生效。
9. 实验：「实验」页按需开关降频、策略等，还有自检工具能看通道状态；「从最近应用列表隐藏」可以防止误划后台杀掉服务。

## 权限说明

- `WRITE_SECURE_SETTINGS`：系统签名级权限，需要 ADB `pm grant` 或 Root/Shizuku 通道，用于无障碍自监控写回和开发者选项受保护的键。
- `QUERY_ALL_PACKAGES`：枚举全机应用（保活、通知列表要用）。
- `PACKAGE_USAGE_STATS`：判断目标应用是否掉线，需要在「使用情况访问」里授权。
- 通知使用权（NotificationListenerService）：0/1 档拦截和静默收纳的前提。
- `POST_NOTIFICATIONS`（Android 13+）：前台服务通知展示，拒绝也不影响功能。
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`（Android 14+）：specialUse 前台服务运行所需。

## 已知限制

- 没有任何授权通道时，没法在无障碍被关后静默自启（系统不允许），这时自监控会发通知并跳系统设置引导手动开；配好任一通道后就能全自动。
- Android 10+ 有后台启动限制，本应用靠无障碍服务上下文拉起（系统豁免），只对已授权无障碍且在保活名单里的应用生效。
- 通知精细调控（2/4/5 档升降级）需要授权通道且 Android 14+（`cmd notification set_importance_override`），否则这几档放行。
- 被强行停止（Force-stop）的应用，系统禁止第三方直接拉起，得用户手动打开一次后保活才生效。
- 权限管理里 `pm grant/revoke` 需要 shell 身份，只有 Root/Shizuku 能在应用内直接执行；ADB 通道（WRITE_SECURE_SETTINGS）只能写 Settings，这类操作降级为展示/复制 ADB 命令。且只能改危险运行时权限，系统签名权限改不了。
- 应用内直接启停无障碍需要任一授权通道，没通道时开关会引导去系统设置手动完成（锁定服务的自动恢复同样依赖通道）。
- 低版本有功能降级：API 21–23 没有 Shizuku 通道；API 21–28 通知精细升降级不可用（`getUid` 需 API 29+）；API 21–26 通知监听状态用 Settings 字符串判断。

## 构建

需要 JDK 17、Android SDK（platform 34 + build-tools 34），最低支持 Android 5.0（API 21）。

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug        # debug：app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:assembleRelease      # release：app/build/outputs/apk/release/app-release.apk（R8 + 资源压缩 + release.keystore 签名）
```

release 默认开 R8 混淆和资源压缩，只打包中/英文资源（`resConfigs("zh","en")`）。签名密钥在项目根的 `release.keystore`（alias=acsmanager，是新证书，和 v1.0.0 的 debug 证书不同，从那版升级需要先卸载旧版）。

## 隐私

- 不联网，清单里没有网络权限（顶部已说明，可用 aapt 核验）。
- 没有广告，没有统计/上报/推送 SDK，不收集任何数据。
- 本应用的无障碍服务不读屏幕内容，只收窗口切换事件做保活判定。
- 配置（保活名单、通知等级、统计等）和通知拦截历史都只存本机（历史最多 200 条，可在实验页关闭）。

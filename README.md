# 无障碍管理器 Pro (Accessibility Manager Pro)

基于 Android 无障碍服务的管理器，支持服务保活、通知调控、开发者选项映射、权限管理等功能。

## 更新记录

### v6.0.0

#### **闪退修复：**
- **授权通道异步执行**
- **事件日志改为单线程异步合并**

#### **运行逻辑与内存优化：**
- **通知等级/隐藏渠道读取加进程内缓存**
- **自监控自动开启、看门狗等后台线程全部改为守护线程，不阻碍进程退出。**

- **新增本地崩溃日志（crash_log.txt）：未捕获异常自动落盘（含线程、堆栈、设备、版本）。**

## 使用教程

### **1. 首次授权**

推荐使用 ADB 激活。如果你有 Root 或者装了 Shizuku 也可以直接用，没装的话走 ADB。授权成功后软件会自动回首页。

&lt;p align="center"&gt;
  &lt;img src="assets/screenshot-auth-methods.jpg" width="500" alt="授权方式选择"/&gt;
&lt;/p&gt;

**用数据线连到电脑，开启 USB 调试后，在终端里跑下面这一条：**

```bash
pm grant com.acsmanager.pro android.permission.WRITE_SECURE_SETTINGS &amp;&amp; pm grant com.acsmanager.pro android.permission.WRITE_SETTINGS &amp;&amp; pm grant com.acsmanager.pro android.permission.POST_NOTIFICATIONS &amp;&amp; appops set com.acsmanager.pro android:get_usage_stats allow &amp;&amp; settings put secure enabled_accessibility_services com.acsmanager.pro/com.acsmanager.pro.selfguard.SelfAccessService &amp;&amp; settings put secure accessibility_enabled 1 &amp;&amp; settings put secure enabled_notification_listeners com.acsmanager.pro/com.acsmanager.pro.notif.NotifGateService &amp;&amp; settings put secure notification_access_enabled 1
```

### **2. 自监控**

**在首页找到"无障碍自监控（低耗电）"这一项，把开关打开。**它靠监听系统设置变化来判断服务有没有被关，不做轮询，基本不耗电。延迟时间按需调，默认 1 秒。

&lt;p align="center"&gt;
  &lt;img src="assets/screenshot-self-monitor.jpg" width="500" alt="无障碍自监控开关"/&gt;
&lt;/p&gt;

### **3. 配置要保护的无障碍服务**

**往下滑找到"授权状态"卡片，点"无障碍权限（应用开关·锁定）"进入列表。**

&lt;p align="center"&gt;
  &lt;img src="assets/screenshot-auth-status.jpg" width="400" alt="授权状态卡片"/&gt;
&lt;/p&gt;

**在列表里找到想保护的无障碍服务，打开授权，右边的锁点亮。**

### **4. 验证**

**去系统的无障碍设置页，把刚才锁好的服务关掉。**正常情况下，会被自动拉回。

&lt;p align="center"&gt;
  &lt;img src="assets/screenshot-event-log.jpg" width="450" alt="事件监控日志"/&gt;
&lt;/p&gt;

上面的日志就是实际测试的效果，全程一秒。

### **5. 日志**

**拉到页面最底下的"快捷入口"，点"监控"就能看到运行日志。**

&lt;p align="center"&gt;
  &lt;img src="assets/screenshot-quick-entry.jpg" width="400" alt="快捷入口"/&gt;
&lt;/p&gt;

## 捐助

如果这个项目对你有帮助，欢迎随意打赏，感谢支持！

&lt;table&gt;
  &lt;tr&gt;
    &lt;td align="center" style="padding:10px;"&gt;
      &lt;img src="assets/wechat-pay.jpg" width="240" alt="微信支付"/&gt;
      &lt;br/&gt;微信支付
    &lt;/td&gt;
    &lt;td align="center" style="padding:10px;"&gt;
      &lt;img src="assets/alipay.jpg" width="240" alt="支付宝"/&gt;
      &lt;br/&gt;支付宝
    &lt;/td&gt;
  &lt;/tr&gt;
&lt;/table&gt;

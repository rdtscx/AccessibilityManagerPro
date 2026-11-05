# 无障碍管理器 Pro · R8 混淆规则
# 本应用无反射/动态加载，规则以"保留清单组件与系统绑定服务"为主

# 无障碍服务 / 通知监听服务 / 前台服务：Manifest 组件默认保留，此处显式声明兜底
-keep class com.acsmanager.pro.selfguard.SelfAccessService { *; }
-keep class com.acsmanager.pro.selfguard.SelfGuardService { *; }
-keep class com.acsmanager.pro.notif.NotifGateService { *; }
-keep class com.acsmanager.pro.watchdog.WatchdogService { *; }

# Shizuku UserService：通过 ComponentName + Binder 事务调用，需保留类与描述符
-keep class com.acsmanager.pro.shizuku.CommandService { *; }
-keep class rikka.shizuku.** { *; }

# 保留通过反射/系统回调访问的成员
-keepclassmembers class * {
    void onCheckedChanged(android.widget.CompoundButton, boolean);
}

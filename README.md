# 无障碍管理器 Pro (Accessibility Manager Pro)

基于 Android 无障碍服务的管理器，支持服务保活、通知调控、开发者选项映射、权限管理等功能。

## 关于本版本

- 清单文件里没有声明网络权限。
- 不触发任何统计、崩溃上报。
- 纯本地软件，不记录任何信息。

## 使用教程

### 1. 首次授权

打开软件后，推荐使用 ADB 激活。按界面提示复制命令，到电脑上执行一次就行。授权成功后软件会自动跳回首页。

### 2. 打开自监控

在首页找到"无障碍自监控（低耗电）"这一项，把开关打开。它靠监听系统设置变化来判断服务有没有被关，不做轮询，基本不耗电。延迟时间按需调，默认 1 秒就够。

<p align="center">
  <img src="assets/screenshot-self-monitor.jpg" width="500" alt="无障碍自监控开关"/>
</p>

### 3. 配置要保护的无障碍服务

往下滑找到"授权状态"卡片，点"无障碍权限（应用开关·锁定）"进入列表。

<p align="center">
  <img src="assets/screenshot-auth-status.jpg" width="400" alt="授权状态卡片"/>
</p>

在列表里找到你想保护的那个无障碍服务，先打开它的授权，再把右边的锁点亮。锁上之后，这个服务就算被手动关掉，软件也会自动重新拉起。

### 4. 验证一下效果

不放心的话，可以自己去系统的无障碍设置页，把刚才锁好的那个服务手动关掉。正常情况下，一秒之内它就会被自动拉回来，不用手动再开。

### 5. 看日志

拉到页面最底下的"快捷入口"，点"监控"就能看到运行日志，改参数、调延迟的时候可以对着日志测。

<p align="center">
  <img src="assets/screenshot-quick-entry.jpg" width="400" alt="快捷入口"/>
</p>

## 捐助

如果这个项目对你有帮助，欢迎随意打赏一杯咖啡，感谢支持！

<table>
  <tr>
    <td align="center" style="padding:10px;">
      <img src="assets/wechat-pay.jpg" width="240" alt="微信支付"/>
      <br/>微信支付
    </td>
    <td align="center" style="padding:10px;">
      <img src="assets/alipay.jpg" width="240" alt="支付宝"/>
      <br/>支付宝
    </td>
  </tr>
</table>

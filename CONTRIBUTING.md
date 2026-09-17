# 贡献指南

感谢你对无障碍管理器 Pro 的关注！欢迎提交 Issue 和 Pull Request。

## 开发环境

- JDK 17
- Android SDK（platform 34 + build-tools 34）
- Android Studio（推荐，也可使用命令行）

## 构建

```bash
./gradlew :app:assembleDebug     # Debug 包
./gradlew :app:assembleRelease   # Release 包（需 release.keystore）
```

## 提交规范

- 一个 PR 只做一件事，保持聚焦
- 提交信息使用中文，格式：`模块: 简要描述`
- 新增功能请同步更新 README.md
- 修复 bug 请在提交信息中说明问题原因

## 代码规范

- Kotlin 代码遵循官方编码规范
- 所有新 API 调用必须带版本守卫（minSdk = 21）
- 废弃 API 调用必须添加 `@Suppress("DEPRECATION")` 并说明替代方案
- 敏感操作（Root/Shizuku/无障碍）必须有 try-catch 兜底，不得崩溃

## 安全提醒

- `release.keystore` 为公开测试签名，正式发布请使用自己的密钥
- 请勿提交包含个人隐私或设备信息的日志

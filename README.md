# OutlookCalSync

把 Outlook（Microsoft Graph）日历同步到 Android 系统日历的小工具。用 refresh token 换取 access token，
读取 `calendarView`，写入一个独立的本地日历 "Outlook"，不会改动设备上的其它日历。

## 功能

- 定时同步（WorkManager，最短 15 分钟），开机自动恢复。
- 增量更新：以 `iCalUId + 开始时间` 为键，用 `changeKey` 判断是否变化；Outlook 里删除的事件会同步删除。
- 过滤：已拒绝、状态为"外出"、状态为"空闲"、每天的指定时间段。
- ColorOS 日历的"响铃"提醒（写入私有列 `force_reminder`，仅 ColorOS 有效）。
- 同步失败通知：需要用户处理的错误立即通知，暂时性错误连续失败 3 次才通知。

## 使用

1. 在 App 里填入 Tenant ID、Client ID 和 refresh token。refresh token 用 Android Keystore（AES-GCM）加密保存，
   不会写入日志；Client ID 必须是签发该 refresh token 的应用。
2. 授予日历和通知权限，点"保存并立即同步"。
3. 在系统设置里允许后台运行、自启动，否则后台同步可能被系统杀掉。

仓库里不包含任何 token 或租户信息。

## 构建

需要 JDK 17 和 Android SDK 35。

```
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

`local.properties` 需要自己写：`sdk.dir=/path/to/Android/sdk`。

## 已知限制

- 只申请了 `Calendars.Read`，只读。
- 提醒分钟数的变化靠 `changeKey` 检测；响铃只在 ColorOS 日历上验证过。
- 写入使用本地账户类型的日历，其它日历 App 是否显示取决于系统。

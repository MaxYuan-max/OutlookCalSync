# OutlookCalSync

把 Outlook（Microsoft Graph）日历同步到 Android 系统日历的小工具。用 refresh token 换取 access token，
读取 `calendarView`，写入一个独立的本地日历 "Outlook"，不会改动设备上的其它日历。

## 功能

- 自动同步间隔可设为“无（仅手动同步）”或 15 分钟～24 小时。定时同步使用 WorkManager，开启时开机自动恢复。
  每次拉取过去 7 天到未来 30 天的事件；
  窗口之外已写入的旧事件不会被删除，只是不再更新。
- 增量更新：以 `iCalUId + 开始时间` 为键，用 `changeKey` 判断是否变化；Outlook 里删除的事件会同步删除。
- 过滤：已拒绝、状态为"外出"、状态为"空闲"、每天的指定时间段。
- ColorOS 日历的"响铃"提醒（写入私有列 `force_reminder`，仅 ColorOS 有效）。
- 同步失败通知：需要用户处理的错误立即通知，暂时性错误连续失败 3 次才通知。

## 使用

1. 在 App 里填入 Tenant ID、Client ID 和 refresh token。refresh token 用 Android Keystore（AES-GCM）加密保存，
   不会写入日志；Client ID 必须是签发该 refresh token 的应用。
2. 授予日历和通知权限，点"保存并立即同步"。
3. 在系统设置里允许后台运行、自启动，否则后台同步可能被系统杀掉。

### 仅手动同步

在“自动同步间隔”中选择“无（仅手动同步）”会立即保存设置，取消定时任务及已排队的失败重试，不删除日历事件。
之后点“保存并立即同步”只执行一次手动同步，不会重新开启自动同步；本次请求内部仍有最多一次网络重试，
但失败后不再由 WorkManager 自动排队重试。手机重启也不会重新开启定时同步。
已经发出的网络请求可能要等返回或超时后才能结束取消。
需要恢复时，选择一个非“无”的时间间隔即可。

仓库里不包含任何 token 或租户信息。

## 诊断信息

应用不再写入自定义 logcat 调试输出或 `debug.log`，临时接口探测入口也已移除。
升级后首次启动应用进程（打开 App 或运行后台任务）会删除旧 `debug.log`，取消旧诊断任务，
并清理旧状态中的原始 HTTP 响应。只保留“上次同步”、结构化错误码、request-id 和失败通知；
自动同步设置、凭据和日历数据不变。系统及 WorkManager 自身的日志不在此清理范围内。

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

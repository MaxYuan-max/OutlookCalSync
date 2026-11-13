package com.maxyuan.outlookcalsync

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

class MainActivity : Activity() {
    private lateinit var store: SecureStore
    private lateinit var tenant: EditText
    private lateinit var client: EditText
    private lateinit var token: EditText
    private lateinit var status: TextView
    private lateinit var intervalSpinner: Spinner
    private lateinit var windows: EditText

    private val intervals = listOf(
        AutoSyncPolicy.NONE to "无（仅手动同步）",
        15 to "15 分钟", 30 to "30 分钟", 60 to "1 小时",
        120 to "2 小时", 360 to "6 小时", 720 to "12 小时", 1440 to "24 小时",
    )

    // SharedPreferences 只保留弱引用, 必须用成员变量持有, 否则会被回收后不再回调
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "last" || key == "auto" || key == "interval") runOnUiThread { refreshStatus() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecureStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, 24, 0, 4) }
        fun field(hint: String, value: String, secret: Boolean = false) = EditText(this).apply {
            this.hint = hint
            setText(value)
            setSingleLine()
            inputType = InputType.TYPE_CLASS_TEXT or
                (if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI)
        }
        fun button(t: String, onClick: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        tenant = field("Tenant ID", store.tenantId)
        client = field("Client ID (必须是签发该 refresh token 的应用)", store.clientId)
        token = field(if (store.refreshToken.isEmpty()) "Refresh token" else "已保存 (留空则不修改)", "", secret = true)
        status = TextView(this).apply { setPadding(0, 24, 0, 0); setTextIsSelectable(true) }
        intervalSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                intervals.map { it.second })
            val activeMinutes = AutoSyncPolicy.activeIntervalMinutes(store.autoSync, store.intervalMinutes)
            setSelection(intervals.indexOfFirst { it.first == activeMinutes }.takeIf { it >= 0 } ?: 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val minutes = intervals[pos].first
                    val active = AutoSyncPolicy.activeIntervalMinutes(store.autoSync, store.intervalMinutes)
                    if (minutes == active) return
                    SyncWorker.setInterval(this@MainActivity, minutes)
                    toast(if (minutes == AutoSyncPolicy.NONE) "已关闭自动同步，仅手动同步" else "间隔已改为 ${intervals[pos].second}")
                    refreshStatus()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        root.addView(TextView(this).apply {
            text = "Outlook → 系统日历"; textSize = 20f; gravity = Gravity.START
        })
        root.addView(label("Tenant ID")); root.addView(tenant)
        root.addView(label("Client ID")); root.addView(client)
        root.addView(label("Refresh token (加密保存在本机)")); root.addView(token)
        root.addView(button("从剪贴板粘贴 refresh token") { pasteToken() })
        root.addView(label("自动同步间隔（选“无”关闭；定时同步最短 15 分钟）")); root.addView(intervalSpinner)

        root.addView(CheckBox(this).apply {
            text = "有提醒的会议用\"响铃\"提醒 (仅 ColorOS 日历支持)"; isChecked = store.ringReminder
            setOnCheckedChangeListener { _, c -> store.ringReminder = c }
        })

        root.addView(label("过滤 (命中的事件不写入手机日历, 已写入的会在下次同步时删除)"))
        root.addView(CheckBox(this).apply {
            text = "跳过我已拒绝的会议"; isChecked = store.skipDeclined
            setOnCheckedChangeListener { _, c -> store.skipDeclined = c }
        })
        root.addView(CheckBox(this).apply {
            text = "跳过状态为\"外出\"的事件"; isChecked = store.skipOof
            setOnCheckedChangeListener { _, c -> store.skipOof = c }
        })
        root.addView(CheckBox(this).apply {
            text = "跳过状态为\"空闲\"的事件"; isChecked = store.skipFree
            setOnCheckedChangeListener { _, c -> store.skipFree = c }
        })
        root.addView(label("跳过这些时间段开始的事件, 每行一个, 如 12:00-13:00 (按手机本地时区, 全天事件不受影响)"))
        windows = EditText(this).apply {
            hint = "12:00-13:00\n22:00-07:00"
            setText(store.skipWindowsText)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        root.addView(windows)
        root.addView(button("保存并立即同步") { saveAndSync() })
        root.addView(button("允许后台运行 (忽略电池优化)") { requestBatteryExemption() })
        root.addView(button("停止自动同步") {
            SyncWorker.cancel(this)
            intervalSpinner.setSelection(0)
            refreshStatus()
            toast("已停止自动同步")
        })
        root.addView(status)

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
            addView(root)
        }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ensurePermissions()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        store.listen(prefsListener)
        refreshStatus()
    }

    override fun onPause() {
        store.unlisten(prefsListener)
        super.onPause()
    }

    private fun pasteToken() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) toast("剪贴板为空") else { token.setText(text); toast("已粘贴 (${text.length} 字符)") }
    }

    private fun saveAndSync() {
        if (!hasCalendarPermission()) { ensurePermissions(); toast("请先授予日历权限"); return }
        val bad = windows.text.toString().lines().filter { it.isNotBlank() && DailyWindow.parse(it) == null }
        if (bad.isNotEmpty()) { toast("时间段格式不对: ${bad.first().trim()} (应为 12:00-13:00)"); return }
        store.skipWindowsText = windows.text.toString().trim()
        store.tenantId = tenant.text.toString()
        store.clientId = client.text.toString()
        val t = token.text.toString().trim()
        if (t.isNotEmpty()) { store.refreshToken = t; token.setText("") }
        if (store.tenantId.isEmpty() || store.clientId.isEmpty() || store.refreshToken.isEmpty()) {
            toast("三项都要填"); return
        }
        store.lastResult = "已提交同步请求, 等待执行…"
        SyncWorker.setInterval(this, intervals[intervalSpinner.selectedItemPosition].first)
        SyncWorker.syncNow(this)
        toast("已保存, 正在同步…")
    }

    private fun hasCalendarPermission() =
        checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions() {
        val need = mutableListOf<String>()
        if (!hasCalendarPermission()) need += listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need += Manifest.permission.POST_NOTIFICATIONS
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("已经允许"); return }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:$packageName")))
    }

    private fun refreshStatus() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        status.text = buildString {
            appendLine("日历权限: ${if (hasCalendarPermission()) "已授权" else "未授权"}")
            appendLine("电池优化: ${if (pm.isIgnoringBatteryOptimizations(packageName)) "已忽略" else "未忽略(后台可能被杀)"}")
            val notifOk = Build.VERSION.SDK_INT < 33 ||
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            appendLine("失败通知: ${if (notifOk) "可用" else "未授权(同步失败时你收不到提醒, 请在系统设置里允许通知)"}")
            appendLine("Refresh token: ${if (store.refreshToken.isEmpty()) "未设置" else "已保存"}")
            val activeMinutes = AutoSyncPolicy.activeIntervalMinutes(store.autoSync, store.intervalMinutes)
            appendLine("自动同步: ${if (activeMinutes != AutoSyncPolicy.NONE) "开启, 每 ${intervalLabel(activeMinutes)}" else "无（仅手动同步）"}")
            appendLine()
            append("上次同步: ${store.lastResult.ifEmpty { "(尚未运行)" }}")
        }
    }

    private fun intervalLabel(minutes: Int) = intervals.firstOrNull { it.first == minutes }?.second ?: "$minutes 分钟"

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}

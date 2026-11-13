package com.maxyuan.outlookcalsync

import android.app.Application
import android.content.Context
import androidx.work.WorkManager

class OutlookCalSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val deleted = LegacyDebugCleanup.deleteLog(filesDir)
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("debugCleanupV1", false)) {
            // WorkManager 自动给任务加上类名标签，仅取消旧诊断，不影响普通同步和用户设置。
            WorkManager.getInstance(this)
                .cancelAllWorkByTag("com.maxyuan.outlookcalsync.DiagnoseWorker")
            val last = prefs.getString("last", "") ?: ""
            prefs.edit()
                .putString("last", LegacyDebugCleanup.compactLastResult(last))
                .remove("failKey")
                .putBoolean("debugCleanupV1", deleted)
                .apply()
        }
    }
}

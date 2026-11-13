package com.maxyuan.outlookcalsync

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores settings in SharedPreferences; secrets are AES-GCM encrypted with an Android Keystore key. */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var tenantId: String
        get() = prefs.getString("tenant", "") ?: ""
        set(v) = prefs.edit().putString("tenant", v.trim()).apply()

    var clientId: String
        get() = prefs.getString("client", "") ?: ""
        set(v) = prefs.edit().putString("client", v.trim()).apply()

    var refreshToken: String
        get() = prefs.getString("rt", null)?.let { decrypt(it) } ?: ""
        set(v) = prefs.edit().putString("rt", encrypt(v.trim())).apply()

    var calendarId: Long
        get() = prefs.getLong("calId", -1L)
        set(v) = prefs.edit().putLong("calId", v).apply()

    var lastResult: String
        get() = prefs.getString("last", "") ?: ""
        set(v) = prefs.edit().putString("last", v).apply()

    var intervalMinutes: Int
        get() = prefs.getInt("interval", 15)
        set(v) = prefs.edit().putInt("interval", v).apply()

    var autoSync: Boolean
        get() = prefs.getBoolean("auto", false)
        set(v) = prefs.edit().putBoolean("auto", v).apply()

    /** 间隔和启用状态一起保存，避免出现 interval=0 但 auto=true 的中间状态。 */
    fun setSyncInterval(minutes: Int) {
        val normalized = AutoSyncPolicy.normalizeInterval(minutes)
        prefs.edit()
            .putInt("interval", normalized)
            .putBoolean("auto", normalized != AutoSyncPolicy.NONE)
            .apply()
    }

    var skipDeclined: Boolean
        get() = prefs.getBoolean("skipDeclined", true)
        set(v) = prefs.edit().putBoolean("skipDeclined", v).apply()

    var skipOof: Boolean
        get() = prefs.getBoolean("skipOof", true)
        set(v) = prefs.edit().putBoolean("skipOof", v).apply()

    var skipFree: Boolean
        get() = prefs.getBoolean("skipFree", true)
        set(v) = prefs.edit().putBoolean("skipFree", v).apply()

    /** 每行一个窗口, 如 "12:00-13:00"。 */
    var skipWindowsText: String
        get() = prefs.getString("skipWindows", "") ?: ""
        set(v) = prefs.edit().putString("skipWindows", v).apply()

    fun filterRules() = FilterRules(
        skipDeclined, skipOof,
        skipWindowsText.lines().mapNotNull { DailyWindow.parse(it) },
        skipFree,
    )

    var ringReminder: Boolean
        get() = prefs.getBoolean("ring", true)
        set(v) = prefs.edit().putBoolean("ring", v).apply()

    var failureState: FailureState
        get() = FailureState(prefs.getInt("failCount", 0), prefs.getString("failKey", "") ?: "")
        set(v) = prefs.edit().putInt("failCount", v.consecutive).putString("failKey", v.notifiedKey).apply()

    fun listen(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(l)

    fun unlisten(l: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(l)

    var windowBackDays: Int
        get() = prefs.getInt("back", 7)
        set(v) = prefs.edit().putInt("back", v).apply()

    var windowAheadDays: Int
        get() = prefs.getInt("ahead", DEFAULT_AHEAD_DAYS)
        set(v) = prefs.edit().putInt("ahead", v).apply()

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String = try {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(c.doFinal(raw.copyOfRange(12, raw.size)), Charsets.UTF_8)
    } catch (e: Exception) {
        ""
    }

    private companion object {
        const val DEFAULT_AHEAD_DAYS = 30
        const val ALIAS = "refresh_token_key"
    }
}

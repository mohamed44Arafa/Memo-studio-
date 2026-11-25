package com.memo.voicestudio.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * الإعدادات محفوظة على جهاز المستخدم فقط، والمفتاح محفوظ مشفّر
 * (EncryptedSharedPreferences) ولا يُرسل لأي مكان غير Google Gemini.
 */
class SettingsStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "memo_voice_prefs",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value).apply()

    /** "female" أو "male" */
    var gender: String
        get() = prefs.getString("gender", "female") ?: "female"
        set(value) = prefs.edit().putString("gender", value).apply()

    /** "egyptian" أو "fusha" */
    var language: String
        get() = prefs.getString("language", "egyptian") ?: "egyptian"
        set(value) = prefs.edit().putString("language", value).apply()

    /** "normal" أو "poetry" أو "story" */
    var style: String
        get() = prefs.getString("style", "normal") ?: "normal"
        set(value) = prefs.edit().putString("style", value).apply()

    /** اسم صوت Gemini المختار (من قائمة الأصوات الرسمية) */
    var voiceName: String
        get() = prefs.getString("voice_name", "") ?: ""
        set(value) = prefs.edit().putString("voice_name", value).apply()
}

package com.memo.voicestudio.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * الإعدادات محفوظة على جهاز المستخدم فقط، والمفتاح محفوظ مشفّر
 * (EncryptedSharedPreferences) ولا يُرسل لأي مكان غير Google Gemini.
 */
class SettingsStore(context: Context) {

    /**
     * التخزين المشفّر ممكن يفشل على بعض الأجهزة (مشكلة في KeyStore) — وده
     * كان بيقتل التطبيق عند الفتح لأن SettingsStore بيتعمل في الـ constructor
     * بتاع الـ ViewModel. لو التشفير فشل، نستخدم تخزين عادي بدل ما التطبيق
     * يقفل (المفاتيح المحفوظة في التخزين المشفّر مش هتتنقل، فالمستخدم ممكن
     * يحتاج يدخلها تاني — بس التطبيق هيفتح).
     */
    private val prefs: SharedPreferences = try {
        EncryptedSharedPreferences.create(
            "memo_voice_prefs",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("memo_voice_prefs_plain", Context.MODE_PRIVATE)
    }

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

    /** "normal" أو "poetry" أو "story" أو "podcast" */
    var style: String
        get() = prefs.getString("style", "normal") ?: "normal"
        set(value) = prefs.edit().putString("style", value).apply()

    /** مفتاح Groq المجاني (احتياطي للتفريغ) */
    var groqApiKey: String
        get() = prefs.getString("groq_api_key", "") ?: ""
        set(value) = prefs.edit().putString("groq_api_key", value).apply()

    /** مفتاح Azure Speech (احتياطي للصوت) */
    var azureApiKey: String
        get() = prefs.getString("azure_api_key", "") ?: ""
        set(value) = prefs.edit().putString("azure_api_key", value).apply()

    /** منطقة Azure (مثال: westeurope) */
    var azureRegion: String
        get() = prefs.getString("azure_region", "westeurope") ?: "westeurope"
        set(value) = prefs.edit().putString("azure_region", value).apply()

    /** مفتاح Deepgram (احتياطي ثاني للتفريغ) */
    var deepgramApiKey: String
        get() = prefs.getString("deepgram_api_key", "") ?: ""
        set(value) = prefs.edit().putString("deepgram_api_key", value).apply()

    /** مفتاح ElevenLabs (احتياطي ثاني للصوت) */
    var elevenLabsApiKey: String
        get() = prefs.getString("elevenlabs_api_key", "") ?: ""
        set(value) = prefs.edit().putString("elevenlabs_api_key", value).apply()

    /** مفتاح Cartesia (احتياطي ثالث للصوت) */
    var cartesiaApiKey: String
        get() = prefs.getString("cartesia_api_key", "") ?: ""
        set(value) = prefs.edit().putString("cartesia_api_key", value).apply()

    /** اسم صوت Gemini المختار (من قائمة الأصوات الرسمية) */
    var voiceName: String
        get() = prefs.getString("voice_name", "") ?: ""
        set(value) = prefs.edit().putString("voice_name", value).apply()

    /** مزود التفريغ المختار يدويًا: "auto" أو "gemini" أو "groq" أو "deepgram" */
    var transcriptionProvider: String
        get() = prefs.getString("transcription_provider", "auto") ?: "auto"
        set(value) = prefs.edit().putString("transcription_provider", value).apply()

    /** مزود الصوت المختار يدويًا: "auto" أو "gemini" أو "azure" أو "elevenlabs" أو "cartesia" */
    var voiceProvider: String
        get() = prefs.getString("voice_provider", "auto") ?: "auto"
        set(value) = prefs.edit().putString("voice_provider", value).apply()

    /** آخر مزوّد صوت نجح في التوليد — بيتجرّب الأول في الوضع التلقائي عشان منستناش على مزوّد معلّق. */
    var lastWorkingVoiceProvider: String
        get() = prefs.getString("last_working_voice_provider", "") ?: ""
        set(value) = prefs.edit().putString("last_working_voice_provider", value).apply()

    /** التفريغ الحالي محفوظ داخل التطبيق (يظهر تاني لما التطبيق يتفتح) — مش داونلود. */
    var isTranscriptionSaved: Boolean
        get() = prefs.getBoolean("transcription_saved", false)
        set(value) = prefs.edit().putBoolean("transcription_saved", value).apply()

    /** الصوت الحالي محفوظ داخل التطبيق (يظهر تاني لما التطبيق يتفتح) — مش داونلود. */
    var isAudioSaved: Boolean
        get() = prefs.getBoolean("audio_saved", false)
        set(value) = prefs.edit().putBoolean("audio_saved", value).apply()
}

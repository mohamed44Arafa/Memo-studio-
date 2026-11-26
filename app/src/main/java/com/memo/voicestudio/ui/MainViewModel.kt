package com.memo.voicestudio.ui

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.memo.voicestudio.data.GeminiRepository
import com.memo.voicestudio.data.PdfText
import com.memo.voicestudio.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settings = SettingsStore(application)
    private val repo = GeminiRepository(application)

    // ---------- الإعدادات ----------
    var apiKey by mutableStateOf(settings.apiKey)
        private set
    var gender by mutableStateOf(settings.gender)
        private set
    var language by mutableStateOf(settings.language)
        private set
    var style by mutableStateOf(settings.style)
        private set
    var voiceName by mutableStateOf(settings.voiceName.ifBlank { defaultVoice() })
        private set
    var keyTest by mutableStateOf<String?>(null)
        private set
    var isTestingKey by mutableStateOf(false)
        private set

    fun saveApiKey(value: String) {
        apiKey = value.trim()
        settings.apiKey = apiKey
    }

    fun saveGender(value: String) {
        gender = value
        settings.gender = value
        val fallback = defaultVoice()
        if (voiceName !in voiceListForGender()) {
            voiceName = fallback
            settings.voiceName = fallback
        }
    }

    fun saveLanguage(value: String) {
        language = value
        settings.language = value
    }

    fun saveStyle(value: String) {
        style = value
        settings.style = value
    }

    fun saveVoiceName(value: String) {
        voiceName = value
        settings.voiceName = value
    }

    fun defaultVoice(): String = if (gender == "male") "Charon" else "Kore"

    fun voiceListForGender(): List<String> =
        if (gender == "male") GeminiRepository.MALE_VOICES else GeminiRepository.FEMALE_VOICES

    fun languageCode(): String = if (language == "egyptian") "ar-EG" else "ar-001"

    fun voiceLabel(): String {
        val g = if (gender == "male") "راجل" else "ست"
        val l = if (language == "egyptian") "مصري" else "فصحى"
        val s = when (style) {
            "poetry" -> "إلقاء شعر"
            "story" -> "سرد قصص"
            else -> "قراءة عادية"
        }
        return "صوت $g — $l — $s (صوت: $voiceName)"
    }

    fun testKey() {
        viewModelScope.launch {
            isTestingKey = true
            keyTest = null
            val ok = withContext(Dispatchers.IO) { repo.testKey(apiKey.trim()) }
            keyTest = if (ok) "المفتاح شغال ✔" else "المفتاح مش شغال — اتأكد من نسخه صح وحاول تاني"
            isTestingKey = false
        }
    }

    // ---------- التفريغ ----------
    var transFileName by mutableStateOf("")
        private set
    var transStatus by mutableStateOf("")
        private set
    var isTranscribing by mutableStateOf(false)
        private set
    var transResult by mutableStateOf("")
        private set
    var sharedText by mutableStateOf("")
        private set

    fun transcribe(file: File, mimeType: String) {
        transFileName = file.name
        viewModelScope.launch(Dispatchers.IO) {
            isTranscribing = true
            transStatus = "بنبدأ…"
            transResult = ""
            val result = repo.transcribe(file, mimeType, apiKey.trim()) { status ->
                transStatus = status
            }
            withContext(Dispatchers.Main) {
                isTranscribing = false
                result
                    .onSuccess {
                        transResult = it
                        sharedText = it
                        transStatus = "خلص التفريغ ✔"
                    }
                    .onFailure {
                        transStatus = it.message ?: "حصل خطأ"
                    }
            }
        }
    }

    fun setStatusMessage(message: String) {
        transStatus = message
    }

    fun setSpeakStatusMessage(message: String) {
        speakStatus = message
    }

    fun useTranscriptionAsSpeakText() {
        if (transResult.isNotBlank()) speakText = transResult
    }

    // ---------- النطق ----------
    var speakText by mutableStateOf("")
    var speakStatus by mutableStateOf("")
        private set
    var isSpeaking by mutableStateOf(false)
        private set
    var audioFile by mutableStateOf<File?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set

    private var player: MediaPlayer? = null

    fun speak() {
        val text = speakText.trim()
        viewModelScope.launch(Dispatchers.IO) {
            isSpeaking = true
            speakStatus = "بنجهّز الصوت…"
            audioFile = null
            val result = repo.synthesize(
                text, voiceName, languageCode(), style, apiKey.trim()
            ) { status -> speakStatus = status }
            withContext(Dispatchers.Main) {
                isSpeaking = false
                result
                    .onSuccess {
                        audioFile = it
                        speakStatus = "الصوت جاهز ✔ — اضغط تشغيل"
                    }
                    .onFailure {
                        speakStatus = it.message ?: "حصل خطأ"
                    }
            }
        }
    }

    fun playAudio() {
        val file = audioFile ?: return
        try {
            stopAudio()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    onPlaybackFinished()
                }
                prepare()
                start()
            }
            isPlaying = true
        } catch (e: Exception) {
            speakStatus = "مش قادر أشغّل الصوت: ${e.message ?: ""}"
        }
    }

    private fun onPlaybackFinished() {
        isPlaying = false
    }

    fun stopAudio() {
        try {
            player?.stop()
        } catch (ignored: Exception) {
        }
        try {
            player?.release()
        } catch (ignored: Exception) {
        }
        player = null
        isPlaying = false
    }

    // ---------- استخراج نص من ملف أو صورة ----------
    var isExtracting by mutableStateOf(false)
        private set

    fun extractFromPdf(file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            isExtracting = true
            speakStatus = "بنقرأ ملف PDF…"
            val text = PdfText.extractText(getApplication(), file)
            if (!text.isNullOrBlank() && text.trim().length >= 100) {
                withContext(Dispatchers.Main) {
                    speakText = text.trim()
                    speakStatus = "اتقرأ النص من PDF ✔"
                    isExtracting = false
                }
                return@launch
            }
            // PDF مصوّر (سكانر): نقرأ الصفحات كصور
            val pages = PdfText.pageCount(file)
            val out = StringBuilder()
            val maxPages = minOf(pages, 10)
            for (page in 0 until maxPages) {
                speakStatus = "بنقرأ صفحة ${page + 1} من $maxPages كصورة…"
                val bitmap = PdfText.renderPageToBitmap(file, page) ?: continue
                repo.ocrImage(bitmap, apiKey.trim())
                    .onSuccess { out.append(it).append("\n") }
            }
            withContext(Dispatchers.Main) {
                isExtracting = false
                if (out.isNotBlank()) {
                    speakText = out.toString().trim()
                    speakStatus = "اتقرأ النص من صفحات PDF ✔"
                } else {
                    speakStatus = "مش قادر أقرأ نص من الملف ده — جرّب صورة أوضح"
                }
            }
        }
    }

    fun extractFromImage(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.IO) {
            isExtracting = true
            speakStatus = "بنقرأ النص من الصورة…"
            val result = repo.ocrImage(bitmap, apiKey.trim())
            withContext(Dispatchers.Main) {
                isExtracting = false
                result
                    .onSuccess {
                        if (it.isBlank()) {
                            speakStatus = "مفيش نص واضح في الصورة — جرّب صورة أوضح"
                        } else {
                            speakText = it
                            speakStatus = "اتقرأ النص من الصورة ✔"
                        }
                    }
                    .onFailure {
                        speakStatus = it.message ?: "حصل خطأ"
                    }
            }
        }
    }

    // ---------- حفظ في التنزيلات ----------
    fun saveTextFile(context: Context, text: String, fileName: String): String {
        return try {
            saveBytesToDownloads(context, fileName, "text/plain", text.toByteArray(Charsets.UTF_8))
                ?: "مش قادر أحفظ الملف"
        } catch (e: Exception) {
            "مش قادر أحفظ الملف: ${e.message ?: ""}"
        }
    }

    fun downloadAudio(context: Context): String {
        val file = audioFile ?: return "مفيش صوت جاهز للتحميل"
        return try {
            val name = "voice_${System.currentTimeMillis()}.wav"
            saveBytesToDownloads(context, name, "audio/x-wav", file.readBytes())
                ?: "مش قادر أحفظ الصوت"
        } catch (e: Exception) {
            "مش قادر أحفظ الصوت: ${e.message ?: ""}"
        }
    }

    private fun saveBytesToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return null
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                "اتحفظ في مجلد التنزيلات: $fileName"
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dir.exists()) dir.mkdirs()
                val out = File(dir, fileName)
                out.writeBytes(bytes)
                "اتحفظ في مجلد التنزيلات: $fileName"
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            player?.release()
        } catch (ignored: Exception) {
        }
        player = null
    }
}

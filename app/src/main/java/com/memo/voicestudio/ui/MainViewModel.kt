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
import com.memo.voicestudio.TranscriptionService
import com.memo.voicestudio.TranscriptionStore
import com.memo.voicestudio.SpeechStore
import com.memo.voicestudio.data.GeminiRepository
import com.memo.voicestudio.data.PdfText
import com.memo.voicestudio.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
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
    var groqApiKey by mutableStateOf(settings.groqApiKey)
        private set
    var azureApiKey by mutableStateOf(settings.azureApiKey)
        private set
    var azureRegion by mutableStateOf(settings.azureRegion)
        private set
    var deepgramApiKey by mutableStateOf(settings.deepgramApiKey)
        private set
    var elevenLabsApiKey by mutableStateOf(settings.elevenLabsApiKey)
        private set
    var cartesiaApiKey by mutableStateOf(settings.cartesiaApiKey)
        private set
    var transcriptionProvider by mutableStateOf(settings.transcriptionProvider)
        private set
    var voiceProvider by mutableStateOf(settings.voiceProvider)
        private set
    /** المزوّد اللي شغال فعليًا دلوقتي في التفريغ/الصوت (من الـ repo) — للعرض في الشاشة. */
    var activeTransProvider by mutableStateOf("")
        private set
    var activeVoiceProvider by mutableStateOf("")
        private set
    var keyTest by mutableStateOf<String?>(null)
        private set
    var isTestingKey by mutableStateOf(false)
        private set
    var groqKeyTest by mutableStateOf<String?>(null)
        private set
    var isTestingGroqKey by mutableStateOf(false)
        private set
    var azureKeyTest by mutableStateOf<String?>(null)
        private set
    var isTestingAzureKey by mutableStateOf(false)
        private set
    var deepgramKeyTest by mutableStateOf<String?>(null)
        private set
    var isTestingDeepgramKey by mutableStateOf(false)
        private set
    var elevenLabsKeyTest by mutableStateOf<String?>(null)
        private set
    var isTestingElevenLabsKey by mutableStateOf(false)
        private set
    var cartesiaKeyTest by mutableStateOf<String?>(null)
        private set
    var isTestingCartesiaKey by mutableStateOf(false)
        private set

    fun saveApiKey(value: String) {
        apiKey = value.trim()
        settings.apiKey = apiKey
    }

    fun saveGroqApiKey(value: String) {
        groqApiKey = value
        settings.groqApiKey = value
    }

    fun saveAzureApiKey(value: String) {
        azureApiKey = value
        settings.azureApiKey = value
    }

    fun saveAzureRegion(value: String) {
        azureRegion = value
        settings.azureRegion = value
    }

    fun saveDeepgramApiKey(value: String) {
        deepgramApiKey = value
        settings.deepgramApiKey = value
    }

    fun saveElevenLabsApiKey(value: String) {
        elevenLabsApiKey = value
        settings.elevenLabsApiKey = value
    }

    fun saveCartesiaApiKey(value: String) {
        cartesiaApiKey = value
        settings.cartesiaApiKey = value
    }

    fun saveTranscriptionProvider(value: String) {
        transcriptionProvider = value
        settings.transcriptionProvider = value
    }

    fun saveVoiceProvider(value: String) {
        voiceProvider = value
        settings.voiceProvider = value
    }

    /** المزود الفعّال للتفريغ: لو المختار مفتاحه اتمسح، يرجع تلقائي. */
    fun effectiveTranscriptionProvider(): String = when (transcriptionProvider) {
        "gemini" -> if (apiKey.isNotBlank()) "gemini" else "auto"
        "groq" -> if (groqApiKey.isNotBlank()) "groq" else "auto"
        "deepgram" -> if (deepgramApiKey.isNotBlank()) "deepgram" else "auto"
        else -> "auto"
    }

    /** المزود الفعّال للصوت: لو المختار مفتاحه اتمسح، يرجع تلقائي. */
    fun effectiveVoiceProvider(): String = when (voiceProvider) {
        "gemini" -> if (apiKey.isNotBlank()) "gemini" else "auto"
        "azure" -> if (azureApiKey.isNotBlank()) "azure" else "auto"
        "elevenlabs" -> if (elevenLabsApiKey.isNotBlank()) "elevenlabs" else "auto"
        "cartesia" -> if (cartesiaApiKey.isNotBlank()) "cartesia" else "auto"
        else -> "auto"
    }

    /** اسم مزود التفريغ النشط للعرض (الفعلي لو شغال، وإلا المختار). */
    fun transProviderLabel(): String =
        GeminiRepository.providerDisplayName(activeTransProvider.ifBlank { effectiveTranscriptionProvider() })

    /** اسم مزود الصوت النشط للعرض (الفعلي لو شغال، وإلا المختار). */
    fun voiceProviderLabel(): String =
        GeminiRepository.providerDisplayName(activeVoiceProvider.ifBlank { effectiveVoiceProvider() })

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
            "podcast" -> "بودكاست (صوتين)"
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

    fun testGroqKey() {
        viewModelScope.launch {
            isTestingGroqKey = true
            groqKeyTest = null
            val result = withContext(Dispatchers.IO) { repo.testGroqKey(groqApiKey.trim()) }
            groqKeyTest = result.fold(
                onSuccess = { "المفتاح شغال ✔" },
                onFailure = { "المفتاح مش شغال: ${it.message ?: "خطأ غير معروف"}" }
            )
            isTestingGroqKey = false
        }
    }

    fun testAzureKey() {
        viewModelScope.launch {
            isTestingAzureKey = true
            azureKeyTest = null
            val result = withContext(Dispatchers.IO) { repo.testAzureKey(azureApiKey.trim(), azureRegion.trim()) }
            azureKeyTest = result.fold(
                onSuccess = { "المفتاح شغال ✔" },
                onFailure = { "المفتاح مش شغال: ${it.message ?: "خطأ غير معروف"}" }
            )
            isTestingAzureKey = false
        }
    }

    fun testDeepgramKey() {
        viewModelScope.launch {
            isTestingDeepgramKey = true
            deepgramKeyTest = null
            val result = withContext(Dispatchers.IO) { repo.testDeepgramKey(deepgramApiKey.trim()) }
            deepgramKeyTest = result.fold(
                onSuccess = { "المفتاح شغال ✔" },
                onFailure = { "المفتاح مش شغال: ${it.message ?: "خطأ غير معروف"}" }
            )
            isTestingDeepgramKey = false
        }
    }

    fun testElevenLabsKey() {
        viewModelScope.launch {
            isTestingElevenLabsKey = true
            elevenLabsKeyTest = null
            val result = withContext(Dispatchers.IO) { repo.testElevenLabsKey(elevenLabsApiKey.trim()) }
            elevenLabsKeyTest = result.fold(
                onSuccess = { "المفتاح شغال ✔" },
                onFailure = { "المفتاح مش شغال: ${it.message ?: "خطأ غير معروف"}" }
            )
            isTestingElevenLabsKey = false
        }
    }

    fun testCartesiaKey() {
        viewModelScope.launch {
            isTestingCartesiaKey = true
            cartesiaKeyTest = null
            val result = withContext(Dispatchers.IO) { repo.testCartesiaKey(cartesiaApiKey.trim()) }
            cartesiaKeyTest = result.fold(
                onSuccess = { "المفتاح شغال ✔" },
                onFailure = { "المفتاح مش شغال: ${it.message ?: "خطأ غير معروف"}" }
            )
            isTestingCartesiaKey = false
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
    var transSegmentCurrent by mutableStateOf(0)
        private set
    var transSegmentTotal by mutableStateOf(0)
        private set

    init {
        // لو تفريغ خلص والتطبيق اتقفل واتفتح تاني، اعرض آخر نتيجة محفوظة.
        viewModelScope.launch(Dispatchers.IO) {
            val last = TranscriptionStore.loadLastResult(getApplication())
            if (last != null && !TranscriptionStore.state.value.running) {
                withContext(Dispatchers.Main) {
                    transResult = last
                    sharedText = last
                    transStatus = "خلص التفريغ ✔"
                }
            }
        }
        // تابع حالة التفريغ اللي شغال في الخلفية (تقدم/نتيجة/خطأ) — الشاشة
        // توري التقدم الحالي بدل ما تبدأ من جديد لما المستخدم يرجع.
        viewModelScope.launch {
            TranscriptionStore.state.collect { state ->
                isTranscribing = state.running
                if (state.fileName.isNotBlank()) transFileName = state.fileName
                if (state.status.isNotBlank()) transStatus = state.status
                transSegmentCurrent = state.currentSegment
                transSegmentTotal = state.totalSegments
                if (state.provider.isNotBlank()) activeTransProvider = state.provider
                if (state.result.isNotBlank()) {
                    transResult = state.result
                    sharedText = state.result
                }
            }
        }
        // لو صوت اتولد والتطبيق اتقفل واتفتح تاني، اعرض آخر ملف صوت محفوظ.
        viewModelScope.launch(Dispatchers.IO) {
            val lastAudio = SpeechStore.loadLastAudio(getApplication())
            if (lastAudio != null && !SpeechStore.state.value.running) {
                withContext(Dispatchers.Main) {
                    audioFile = lastAudio
                    speakStatus = "الصوت جاهز ✔ — اضغط تشغيل"
                }
            }
        }
        // تابع حالة توليد الصوت اللي شغال في الخلفية — الشاشة توري التقدم
        // الحالي بدل ما تبدأ من جديد لما المستخدم يرجع.
        viewModelScope.launch {
            SpeechStore.state.collect { state ->
                isSpeaking = state.running
                if (state.status.isNotBlank()) speakStatus = state.status
                speakPhase = state.phase
                speakChunkCurrent = state.currentChunk
                speakChunkTotal = state.totalChunks
                if (state.provider.isNotBlank()) activeVoiceProvider = state.provider
                if (state.audioPath.isNotBlank()) {
                    audioFile = File(state.audioPath)
                }
            }
        }
    }

    fun transcribe(file: File, mimeType: String) {
        transFileName = file.name
        transResult = ""
        transStatus = "بنبدأ…"
        transSegmentCurrent = 0
        transSegmentTotal = 0
        activeTransProvider = ""
        isTranscribing = true
        // التفريغ بيشتغل في فورجراوند سيرفس عشان يكمّل في الخلفية.
        TranscriptionService.start(getApplication(), file, mimeType)
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
    /** مرحلة توليد الصوت في الخلفية: "script" أو "audio" — للعرض في الشاشة. */
    var speakPhase by mutableStateOf("")
        private set
    /** المقطع الحالي من إجمالي مقاطع الصوت — للعرض في الشاشة. */
    var speakChunkCurrent by mutableStateOf(0)
        private set
    var speakChunkTotal by mutableStateOf(0)
        private set
    var audioFile by mutableStateOf<File?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set

    private var player: MediaPlayer? = null

    fun speak() {
        val text = speakText.trim()
        if (text.isBlank()) {
            speakStatus = "مفيش نص — اكتب نص أو استخرجه من ملف أو صورة أولاً."
            return
        }
        // مهمة واحدة في المرة (تفريغ أو صوت) — لو مهمة شغالة، ارفض الجديدة.
        if (TranscriptionStore.state.value.running || SpeechStore.state.value.running) {
            speakStatus = "في مهمة شغالة دلوقتي — استنى لحد ما تخلص"
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // النص بيتبعت للسيرفس كملف عشان مفيش حد لحجم الـ Intent.
            val inputFile = File(getApplication<Application>().cacheDir, "speech-input.txt")
            try {
                inputFile.writeText(text)
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { speakStatus = "مش قادر أجهّز النص" }
                return@launch
            }
            withContext(Dispatchers.Main) {
                // فحص تاني قبل التشغيل (ممكن مهمة تكون بدأت أثناء تجهيز الملف).
                if (TranscriptionStore.state.value.running || SpeechStore.state.value.running) {
                    speakStatus = "في مهمة شغالة دلوقتي — استنى لحد ما تخلص"
                    return@withContext
                }
                audioFile = null
                activeVoiceProvider = ""
                speakStatus = "بنبدأ…"
                speakPhase = ""
                speakChunkCurrent = 0
                speakChunkTotal = 0
                isSpeaking = true
                // توليد الصوت بيشتغل في فورجراوند سيرفس عشان يكمّل في الخلفية.
                TranscriptionService.startSpeech(
                    getApplication(), inputFile, voiceName, languageCode(), style, gender
                )
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
            // PDF مصوّر (سكانر): نقرأ الصفحات كصور — 3 صفحات في نفس الوقت
            // (كل صفحة مستقلة، والنتيجة بتتجمع بنفس ترتيب الصفحات).
            // renderPageToBitmap بيفتح ملف جديد لكل صفحة، فالتوازي آمن.
            val pages = PdfText.pageCount(file)
            val maxPages = minOf(pages, 10)
            val pageTexts = arrayOfNulls<String>(maxPages)
            speakStatus = "بنقرأ صفحات PDF كصور…"
            for (window in (0 until maxPages).chunked(3)) {
                coroutineScope {
                    window.map { page ->
                        async {
                            val bitmap = PdfText.renderPageToBitmap(file, page) ?: return@async
                            repo.ocrImage(bitmap, apiKey.trim()) { speakStatus = it }
                                .onSuccess { if (it.isNotBlank()) pageTexts[page] = it }
                        }
                    }.awaitAll()
                }
            }
            val out = StringBuilder()
            for (pageText in pageTexts) {
                if (pageText != null) out.append(pageText).append("\n")
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
            val result = repo.ocrImage(bitmap, apiKey.trim()) { speakStatus = it }
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

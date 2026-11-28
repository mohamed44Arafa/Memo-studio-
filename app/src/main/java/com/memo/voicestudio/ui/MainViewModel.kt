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
import com.memo.voicestudio.data.YouTubeCaptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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
    var ocrSpaceApiKey by mutableStateOf(settings.ocrSpaceApiKey)
        private set
    var ocrReader by mutableStateOf(settings.ocrReader)
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
    var ocrSpaceKeyTest by mutableStateOf<String?>(null)
        private set
    var isTestingOcrSpaceKey by mutableStateOf(false)
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

    fun saveOcrSpaceApiKey(value: String) {
        ocrSpaceApiKey = value
        settings.ocrSpaceApiKey = value
    }

    fun saveTranscriptionProvider(value: String) {
        transcriptionProvider = value
        settings.transcriptionProvider = value
    }

    fun saveVoiceProvider(value: String) {
        voiceProvider = value
        settings.voiceProvider = value
    }

    fun saveOcrReader(value: String) {
        ocrReader = value
        settings.ocrReader = value
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

    /** القارئ الفعّال للصور: لو المختار مفتاحه اتمسح، يرجع تلقائي. */
    fun effectiveOcrReader(): String = when (ocrReader) {
        "gemini" -> if (apiKey.isNotBlank()) "gemini" else "auto"
        "ocrspace" -> if (ocrSpaceApiKey.isNotBlank()) "ocrspace" else "auto"
        else -> "auto"
    }

    /** اسم مزود التفريغ النشط للعرض (الفعلي لو شغال، وإلا المختار). */
    fun transProviderLabel(): String =
        GeminiRepository.providerDisplayName(activeTransProvider.ifBlank { effectiveTranscriptionProvider() })

    /** اسم مزود الصوت النشط للعرض (الفعلي لو شغال، وإلا المختار). */
    fun voiceProviderLabel(): String =
        GeminiRepository.providerDisplayName(activeVoiceProvider.ifBlank { effectiveVoiceProvider() })

    fun saveGender(value: String) {
        if (value == gender) return
        gender = value
        settings.gender = value
        val fallback = defaultVoice()
        if (voiceName !in voiceListForGender()) {
            voiceName = fallback
            settings.voiceName = fallback
        }
        // (Build 46: تغيير الصوت بيلغي "آخر مزوّد شغال" — عشان التوليد الجاي يبدأ بـ Gemini الأول،
        // وهو المزوّد الوحيد اللي فيه الـ 30 صوت؛ غير كده الصوت المختار مبيتسمعش لو البديل هو اللي شغال.)
        settings.lastWorkingVoiceProvider = ""
        restartSpeechWithNewVoice()
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
        if (value == voiceName) return
        voiceName = value
        settings.voiceName = value
        // (Build 46: تغيير الصوت بيلغي "آخر مزوّد شغال" — عشان التوليد الجاي يبدأ بـ Gemini الأول،
        // وهو المزوّد الوحيد اللي فيه الـ 30 صوت؛ غير كده الصوت المختار مبيتسمعش لو البديل هو اللي شغال.)
        settings.lastWorkingVoiceProvider = ""
        restartSpeechWithNewVoice()
    }

    /** مهمة إعادة التوليد بعد تغيير الصوت — بتتلغي لو الصوت اتغيّر تاني قبل ما تبدأ. */
    private var voiceRestartJob: Job? = null

    /** لو توليد صوت شغال وغيّر الصوت (أو النوع)، يوقف التوليد الحالي ويبدأه من الأول بالصوت الجديد — النص زي ما هو، من غير إعادة إدخال أو استخراج الملف. البودكاست مستثنى (أصواته ثابتة: مضيف وضيف). */
    private fun restartSpeechWithNewVoice() {
        if (style == "podcast") return
        if (!isSpeaking) return
        cancelCurrentJob()
        voiceRestartJob?.cancel()
        voiceRestartJob = viewModelScope.launch {
            // استنى لحد ما السيرفس يوقف فعلاً قبل ما نبدأ من جديد (بحد أقصى ~10 ثواني).
            var waited = 0
            while ((TranscriptionStore.state.value.running || SpeechStore.state.value.running) && waited < 100) {
                delay(100)
                waited++
            }
            speak()
        }
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

    fun testOcrSpaceKey() {
        viewModelScope.launch {
            isTestingOcrSpaceKey = true
            ocrSpaceKeyTest = null
            val result = withContext(Dispatchers.IO) { repo.testOcrSpaceKey(ocrSpaceApiKey.trim()) }
            ocrSpaceKeyTest = result.fold(
                onSuccess = { "المفتاح شغال ✔" },
                onFailure = { "المفتاح مش شغال: ${it.message ?: "خطأ غير معروف"}" }
            )
            isTestingOcrSpaceKey = false
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
    /** آخر ملف تفريغ (للإعادة بنفس الملف من غير ما تختاره تاني). */
    private var lastTransFile: File? = null
    private var lastTransMime: String = ""
    /** مهمة جلب الكابشن من لينك يوتيوب (عشان زرار الإيقاف يلغيها). */
    private var linkJob: Job? = null
    /** مهمة مراقبة حالة توليد الصوت — بتنسخ حالة المتجر للشاشة كل ثانية أثناء التوليد،
     * عشان الشاشة توري التقدم حتى لو المتابعة اللي في الـ init وقفت لأي سبب. */
    private var speechWatchJob: Job? = null
    /** اتعمل توليد صوت قبل كده في الجلسة دي (لزر "إعادة التوليد"). */
    var hasGeneratedSpeech by mutableStateOf(false)
        private set
    /** التفريغ الحالي محفوظ في التطبيق (هيظهر تاني بعد قفل التطبيق وفتحه). */
    var isTranscriptionSaved by mutableStateOf(settings.isTranscriptionSaved)
        private set
    /** الصوت الحالي محفوظ في التطبيق (هيظهر تاني بعد قفل التطبيق وفتحه). */
    var isAudioSaved by mutableStateOf(settings.isAudioSaved)
        private set

    init {
        // اختيار المزوّد اليدوي بيرجع "تلقائي" مع كل فتحة للتطبيق (طلب ميمو):
        // لو اختار مزوّد معيّن وقفل البرنامج، لما يرجع يلاقيه تلقائي مش ثابت على اختياره.
        if (transcriptionProvider != "auto" || voiceProvider != "auto" || ocrReader != "auto") {
            transcriptionProvider = "auto"
            voiceProvider = "auto"
            ocrReader = "auto"
            settings.transcriptionProvider = "auto"
            settings.voiceProvider = "auto"
            settings.ocrReader = "auto"
        }
        // لو تفريغ خلص والتطبيق اتقفل واتفتح تاني، اعرض آخر نتيجة محفوظة.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val last = TranscriptionStore.loadLastResult(getApplication())
                if (last != null && !TranscriptionStore.state.value.running && settings.isTranscriptionSaved) {
                    withContext(Dispatchers.Main) {
                        transResult = last
                        sharedText = last
                        transStatus = "خلص التفريغ ✔"
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // أي خطأ في تحميل آخر نتيجة ميقتلش التطبيق عند الفتح
            }
        }
        // تابع حالة التفريغ اللي شغال في الخلفية (تقدم/نتيجة/خطأ) — الشاشة
        // توري التقدم الحالي بدل ما تبدأ من جديد لما المستخدم يرجع.
        viewModelScope.launch {
            try {
                TranscriptionStore.state.collect { state ->
                isTranscribing = state.running
                isTranscriptionSaved = settings.isTranscriptionSaved
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
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
        // لو صوت اتولد والتطبيق اتقفل واتفتح تاني، اعرض آخر ملف صوت محفوظ.
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lastAudio = SpeechStore.loadLastAudio(getApplication())
                if (lastAudio != null && !SpeechStore.state.value.running && settings.isAudioSaved) {
                    // (Build 46: النص المحفوظ مع الصوت بيرجع معاه في خانة النص.)
                    val savedText = try { savedSpeechTextFile.readText().trim() } catch (e: Exception) { "" }
                    withContext(Dispatchers.Main) {
                        audioFile = lastAudio
                        speakStatus = "الصوت جاهز ✔ — اضغط تشغيل"
                        if (savedText.isNotBlank()) speakText = savedText
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // أي خطأ في تحميل آخر صوت ميقتلش التطبيق عند الفتح
            }
        }
        // تابع حالة توليد الصوت اللي شغال في الخلفية — الشاشة توري التقدم
        // الحالي بدل ما تبدأ من جديد لما المستخدم يرجع.
        viewModelScope.launch {
            try {
                SpeechStore.state.collect { state ->
                isSpeaking = state.running
                isAudioSaved = settings.isAudioSaved
                if (state.status.isNotBlank()) speakStatus = state.status
                speakPhase = state.phase
                speakChunkCurrent = state.currentChunk
                speakChunkTotal = state.totalChunks
                if (state.provider.isNotBlank()) activeVoiceProvider = state.provider
                if (state.audioPath.isNotBlank()) {
                    audioFile = File(state.audioPath)
                }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
    }

    fun transcribe(file: File, mimeType: String) {
        lastTransFile = file
        lastTransMime = mimeType
        transFileName = file.name
        transResult = ""
        transStatus = "بنبدأ…"
        transSegmentCurrent = 0
        transSegmentTotal = 0
        activeTransProvider = ""
        isTranscribing = true
        // التفريغ بيشتغل في فورجراوند سيرفس عشان يكمّل في الخلفية.
        // لو السيرفس مقدرش يبدأ، نرجّع الحالة بدل ما الشاشة تفضل تلف على الفاضي.
        try {
            TranscriptionService.start(getApplication(), file, mimeType)
        } catch (e: Exception) {
            isTranscribing = false
            transStatus = "مش قادر أشغّل التفريغ — جرّب تاني"
        }
    }

    /** هل فيه ملف جاهز لإعادة التفريغ (نفس الملف من غير ما تختاره تاني). */
    fun canRetryTranscription(): Boolean = lastTransFile != null && !isTranscribing

    /** يعيد تفريغ آخر ملف من الأول (ريفرش للتفريغ). */
    fun retryTranscription() {
        val file = lastTransFile ?: return
        if (isTranscribing) return
        transcribe(file, lastTransMime)
    }

    /**
     * يجيب نص فيديو يوتيوب من اللينك (الكابشن/الترجمة) من غير تحميل الفيديو.
     * النتيجة بتتحط في نفس مكان نتيجة التفريغ، فزرار "ابعته للنطق" بيشتغل عليها عادي.
     * لو الفيديو مفيهوش كابشن، الرسالة بتقول كده بوضوح (والخطوة الجاية: Gemini يسمع الفيديو نفسه).
     */
    fun transcribeFromLink(url: String) {
        if (isTranscribing) return
        val videoId = YouTubeCaptions.extractVideoId(url)
        if (videoId == null) {
            transStatus = "اللينك مش صحيح — حط لينك فيديو يوتيوب"
            return
        }
        linkJob?.cancel()
        transFileName = "فيديو يوتيوب"
        transResult = ""
        transStatus = "بنجيب النص من اللينك…"
        transSegmentCurrent = 0
        transSegmentTotal = 0
        activeTransProvider = ""
        isTranscribing = true
        linkJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = YouTubeCaptions.fetchCaptions(videoId)
                TranscriptionStore.saveResult(getApplication(), text)
                withContext(Dispatchers.Main) {
                    transResult = text
                    sharedText = text
                    transStatus = "خلص التفريغ ✔ (من كابشن الفيديو)"
                    isTranscribing = false
                    // نص جديد غير محفوظ — الحفظ القديم كان للنص اللي قبله.
                    isTranscriptionSaved = false
                    settings.isTranscriptionSaved = false
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(Dispatchers.Main + NonCancellable) {
                    isTranscribing = false
                    transStatus = if (e is YouTubeCaptions.NoCaptionsException) {
                        "الفيديو ده مفيهوش كابشن — جرّب فيديو تاني، أو نزّل الفيديو وفرّغه من الملف"
                    } else {
                        "مش قادر أجيب النص من اللينك دلوقتي — اتأكد من النت واللينك وجرّب تاني"
                    }
                }
            }
            // ملاحظة: بنسيب مرجع المهمة زي ما هو بعد ما تخلص — كل الفحوصات
            // بتستخدم isActive، فمهمة خلصانة مش هتتلغي ولا هتوقف حاجة.
        }
    }

    /** يوقف المهمة الشغالة (تفريغ أو توليد صوت أو استخراج نص) — بيبعت أمر إلغاء للسيرفس، أو يلغي مهمة الاستخراج. */
    fun cancelCurrentJob() {
        speechWatchJob?.cancel()
        if (isExtracting) {
            cancelExtraction()
        } else if (linkJob?.isActive == true) {
            // جلب الكابشن من اللينك شغال في الـ ViewModel مش في السيرفس — إلغاؤه مباشر.
            linkJob?.cancel()
            linkJob = null
            isTranscribing = false
            transStatus = "اتلغى التفريغ"
        } else {
            // الزرار يرد على الشاشة فورًا (يوقف اللف ويكتب "اتلغى") حتى لو أمر الإلغاء
            // للسيرفس اتأخر أو موصلش — قبل كده الشاشة كانت بتفضل تلف كأن الزرار مش شغال.
            if (isSpeaking) {
                isSpeaking = false
                speakStatus = "اتلغى توليد الصوت"
            }
            if (isTranscribing) {
                isTranscribing = false
                transStatus = "اتلغى التفريغ"
            }
            TranscriptionService.cancel(getApplication())
        }
    }

    fun setStatusMessage(message: String) {
        transStatus = message
    }

    fun setSpeakStatusMessage(message: String) {
        speakStatus = message
    }

    /** يحفظ التفريغ الحالي داخل التطبيق (مش داونلود) — أو يلغي حفظه لو محفوظ. */
    fun toggleSaveTranscription() {
        if (transResult.isBlank()) return
        isTranscriptionSaved = !isTranscriptionSaved
        settings.isTranscriptionSaved = isTranscriptionSaved
        transStatus = if (isTranscriptionSaved) "اتحفظ ✔ — هيفضل موجود لما تفتح التطبيق تاني"
            else "اتلغى الحفظ — مش هيظهر لما تفتح التطبيق تاني"
    }

    /** ملف النص المحفوظ مع الصوت (بيتكتب لما يتداس "حفظ في التطبيق" في شاشة النطق). */
    private val savedSpeechTextFile get() = File(getApplication<Application>().cacheDir, "saved-speech-text.txt")

    /** يحفظ الصوت الحالي داخل التطبيق (مش داونلود) — أو يلغي حفظه لو محفوظ. */
    fun toggleSaveAudio() {
        if (audioFile == null) return
        isAudioSaved = !isAudioSaved
        settings.isAudioSaved = isAudioSaved
        // (Build 46: الحفظ بيحفظ النص كمان مش الصوت بس — بيتكتب في ملف جنب الصوت،
        // وبيترجع معاه لما التطبيق يتفتح تاني.)
        if (isAudioSaved) {
            val textToSave = speakText.trim()
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (textToSave.isNotBlank()) savedSpeechTextFile.writeText(textToSave)
                    else savedSpeechTextFile.delete()
                } catch (e: Exception) { /* فشل حفظ النص ميمنعش حفظ الصوت */ }
            }
            speakStatus = "اتحفظ ✔ — الصوت والنص هيفضلوا موجودين لما تفتح التطبيق تاني"
        } else {
            viewModelScope.launch(Dispatchers.IO) {
                try { savedSpeechTextFile.delete() } catch (e: Exception) { }
            }
            speakStatus = "اتلغى الحفظ — مش هيظهر لما تفتح التطبيق تاني"
        }
    }

    fun useTranscriptionAsSpeakText() {
        if (transResult.isNotBlank()) {
            speakText = transResult
            clearLoadedFile()
        }
    }

    // ---------- الملف المحمّل في شاشة النطق (لزر "تغيير الملف") ----------
    /** اسم آخر ملف حُمّل في شاشة النطق (null لو النص مكتوب يدوي أو من التفريغ). */
    var loadedFileName by mutableStateOf<String?>(null)
        private set
    /** مصدر الملف المحمّل: "file" أو "image" أو "camera". */
    var loadedFileSource by mutableStateOf<String?>(null)
        private set

    fun markLoadedFile(name: String, source: String) {
        loadedFileName = name
        loadedFileSource = source
    }

    fun clearLoadedFile() {
        loadedFileName = null
        loadedFileSource = null
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
                hasGeneratedSpeech = true
                // توليد الصوت بيشتغل في فورجراوند سيرفس عشان يكمّل في الخلفية.
                // لو السيرفس مقدرش يبدأ، نرجّع الحالة بدل ما الشاشة تفضل تلف على الفاضي.
                try {
                    TranscriptionService.startSpeech(
                        getApplication(), inputFile, voiceName, languageCode(), style, gender
                    )
                } catch (e: Exception) {
                    isSpeaking = false
                    speakStatus = "مش قادر أشغّل توليد الصوت — جرّب تاني"
                }
                // مراقبة مباشرة لحالة التوليد: بتنسخ حالة المتجر للشاشة كل ثانية
                // لحد ما المهمة تخلص — لو المتابعة الأساسية وقفت، دي بتكمّل مكانها.
                // (Build 41: المراقب كان بيخرج من أول لفة لو السيرفس لسه مبدأش،
                // فماكانش بيوصل أي تحديث للشاشة — دلوقتي بيستنى لحد ما المهمة تبدأ فعلاً.)
                speechWatchJob?.cancel()
                speechWatchJob = viewModelScope.launch {
                    var seenRunning = false
                    while (true) {
                        val st = SpeechStore.state.value
                        if (st.running) seenRunning = true
                        if (seenRunning) isSpeaking = st.running
                        isAudioSaved = settings.isAudioSaved
                        if (st.status.isNotBlank()) speakStatus = st.status
                        speakPhase = st.phase
                        speakChunkCurrent = st.currentChunk
                        speakChunkTotal = st.totalChunks
                        if (st.provider.isNotBlank()) activeVoiceProvider = st.provider
                        if (st.audioPath.isNotBlank()) audioFile = File(st.audioPath)
                        if (seenRunning && !st.running) break
                        delay(1000)
                    }
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
    /** مهمة استخراج النص الشغالة — عشان زرار الإيقاف يقدر يلغيها وهي بتقرأ PDF أو صورة. */
    private var extractJob: Job? = null

    /** يلغي استخراج النص الشغال ويرجّع الشاشة لحالتها الطبيعية. */
    fun cancelExtraction() {
        extractJob?.cancel()
        extractJob = null
        isExtracting = false
        speakStatus = "اتوقف استخراج النص — تقدر تختار ملف تاني أو تكتب النص بنفسك"
    }

    fun extractFromPdf(file: File) {
        extractJob?.cancel()
        extractJob = viewModelScope.launch(Dispatchers.IO) {
            try {
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
            // PDF مصوّر (سكانر): نقرأ الصفحات كصور — 5 صفحات في نفس الوقت
            // (كل صفحة مستقلة، والنتيجة بتتجمع بنفس ترتيب الصفحات).
            // renderPageToBitmap بيفتح ملف جديد لكل صفحة، فالتوازي آمن.
            val pages = PdfText.pageCount(file)
            val maxPages = minOf(pages, 10)
            val pageTexts = arrayOfNulls<String>(maxPages)
            // (Build 42: لو قراءة الصفحات فشلت من الخدمة نفسها، لازم الرسالة تقول كده
            // بدل «جرّب صورة أوضح» — المشكلة مش في الصورة.)
            val ocrFailed = AtomicBoolean(false)
            speakStatus = "بنقرأ صفحات PDF كصور…"
            for (window in (0 until maxPages).chunked(5)) {
                speakStatus = "بنقرأ صفحات PDF كصور… (صفحة ${window.first() + 1} من $maxPages)"
                coroutineScope {
                    window.map { page ->
                        async {
                            val bitmap = PdfText.renderPageToBitmap(file, page) ?: return@async
                            repo.ocrImage(bitmap, apiKey.trim(), ocrSpaceApiKey.trim(), effectiveOcrReader()) { speakStatus = it }
                                .onSuccess { if (it.isNotBlank()) pageTexts[page] = it }
                                .onFailure { ocrFailed.set(true) }
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
                } else if (ocrFailed.get()) {
                    speakStatus = "خدمة القراءة مشغولة دلوقتي — جرّب تاني بعد شوية"
                } else {
                    speakStatus = "مش قادر أقرأ نص من الملف ده — جرّب صورة أوضح"
                }
            }
            } finally {
                // لو الاستخراج اتلغى (زرار إيقاف) الكود اللي فوق مبيكملش،
                // فلازم نرجّع الحالة هنا عشان الشاشة متفضلش تلف على الفاضي.
                withContext(Dispatchers.Main + NonCancellable) { isExtracting = false }
            }
        }
    }

    fun extractFromImage(bitmap: Bitmap) {
        extractJob?.cancel()
        extractJob = viewModelScope.launch(Dispatchers.IO) {
            try {
            isExtracting = true
            speakStatus = "بنقرأ النص من الصورة…"
            val result = repo.ocrImage(bitmap, apiKey.trim(), ocrSpaceApiKey.trim(), effectiveOcrReader()) { speakStatus = it }
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
            } finally {
                withContext(Dispatchers.Main + NonCancellable) { isExtracting = false }
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

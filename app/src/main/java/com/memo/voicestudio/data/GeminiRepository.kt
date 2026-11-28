package com.memo.voicestudio.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

open class RateLimitException : Exception()

/** الخدمة مشغولة مؤقتًا (ضغط عالي) — تُعامل مثل تجاوز الحد: إعادة محاولة أو التبديل للبديل. */
class ServiceOverloadedException : RateLimitException()

/** رصيد المزوّد المجاني خلص — يُعامل مثل تجاوز الحد: التبديل للبديل. */
class CreditExhaustedException(message: String) : Exception(message)

/** المزوّد رفض الطلب أو حصله خطأ داخلي (400 أو 500 مثلاً) — يستاهل التبديل للمزوّد البديل زي الضغط تمامًا. */
class ProviderFailureException(message: String) : Exception(message)

    /** يرمي الاستثناء المناسب لرد غير ناجح: رفض المزوّد للطلب (400) أو أخطاء السيرفر (5xx) تتبدّل للبديل، والباقي خطأ عادي بنفس رسالته. */
    private fun throwServiceError(prefix: String, code: Int, detail: String): Nothing {
        val msg = "$prefix ($code)$detail"
        if (code == 400 || code in 500..599) throw ProviderFailureException(msg)
        throw IllegalStateException(msg)
    }

/**
 * كل التعامل مع Gemini API هنا:
 * - التفريغ: gemini-3.5-transcribe (باقة مجانية: حتى ساعة في الطلب، ~25 طلب/يوم)
 * - الصوت: gemini-2.5-flash-preview-tts (باقة مجانية، عربي مصري وفصحى)
 * - قراءة الصور وPDF المصوّر: gemini-3.8-flash
 * - بدائل احتياطية عند انتهاء الحد أو الضغط أو الرصيد: التفريغ Groq ثم Deepgram،
 *   والصوت Azure ثم ElevenLabs ثم Cartesia (كل واحد بمفتاح مجاني من الإعدادات).
 * - المستخدم يقدر يختار مزوّد معيّن من الإعدادات — يتجرب الأول، ولو فشل
 *   السلسلة تكمّل مع باقي المزودين بنفس الترتيب الافتراضي.
 *
 * الأصوات (أسماء رسمية موثّقة من Google، 30 صوتًا جاهزًا):
 * Google لا تصنّف الأصوات رسميًا لرجل/امرأة، فالتقسيم هنا حسب الوصف
 * الموثّق لكل صوت: نسائي Kore/Aoede/Leda/Zephyr/Sulafat،
 * رجالي Charon/Fenrir/Puck/Orus/Gacrux. والمستخدم يقدر يختار أي صوت
 * من القائمة الكاملة من واجهة التطبيق.
 */
class GeminiRepository(private val context: Context) {

    private val gson = Gson()

    private val http = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS)
        .build()

    private val api: GeminiApi = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(http)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(GeminiApi::class.java)

    /**
     * تنفيذ طلب OkHttp قابل للإلغاء فعلاً — لما المستخدم يدوس "إيقاف" أثناء
     * التفريغ أو توليد الصوت، الطلب الشغال يتقطع فورًا بدل ما يستنى لحد ما
     * يخلص (الـ execute() العادي بيستنى ومبيستجيبش للإلغاء، فزرار الإيقاف
     * كان باين إنه مش شغال).
     */
    private suspend fun Call.executeCancellable(): okhttp3.Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { try { cancel() } catch (ignored: Exception) { } }
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: okhttp3.Response) {
                    cont.resume(response)
                }
            })
        }

    companion object {
        const val TRANSCRIBE_MODEL = "gemini-3.5-transcribe"
        const val TTS_MODEL = "gemini-2.5-flash-preview-tts"
        const val VISION_MODEL = "gemini-3.8-flash"
        const val TEXT_MODEL = "gemini-3.8-flash"
        const val PODCAST_HOST_VOICE = "Zephyr"
        const val PODCAST_GUEST_VOICE = "Charon"
        const val GROQ_STT_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val GROQ_STT_MODEL = "whisper-large-v3-turbo"
        const val GROQ_MAX_FILE_BYTES = 25L * 1024L * 1024L
        const val AZURE_EG_FEMALE_VOICE = "ar-EG-SalmaNeural"
        const val AZURE_EG_MALE_VOICE = "ar-EG-ShakirNeural"
        const val DEEPGRAM_STT_URL = "https://api.deepgram.com/v1/listen?model=nova-3&language=ar-EG&smart_format=true"
        const val ELEVENLABS_TTS_URL = "https://api.elevenlabs.io/v1/text-to-speech"
        const val ELEVENLABS_TTS_MODEL = "eleven_turbo_v2_5"
        const val ELEVENLABS_MALE_VOICE = "pNInz6obpgDQGcFmaJgB"
        const val ELEVENLABS_FEMALE_VOICE = "21m00Tcm4TlvDq8ikWAM"
        const val CARTESIA_TTS_URL = "https://api.cartesia.ai/tts/bytes"
        const val CARTESIA_TTS_MODEL = "sonic-3.6"
        const val CARTESIA_VOICE = "db6b0ed5-d5d3-463d-ae85-518a07d3c2b4"
        const val GROQ_MODELS_URL = "https://api.groq.com/openai/v1/models"
        const val DEEPGRAM_PROJECTS_URL = "https://api.deepgram.com/v1/projects"
        const val ELEVENLABS_USER_URL = "https://api.elevenlabs.io/v1/user"
        const val CARTESIA_VOICES_URL = "https://api.cartesia.ai/voices"
        const val CARTESIA_API_VERSION = "2024-06-10"

        /** أقصى طول للجزء الواحد عند التفريغ (50 دقيقة — أقل من حد الساعة) */
        const val MAX_SEGMENT_MS = 50L * 60L * 1000L

        /** أقصى طول لنص طلب الصوت الواحد */
        const val MAX_TTS_CHARS = 2500

        /** اسم المزوّد للعرض في الواجهة ورسائل الحالة. */
        fun providerDisplayName(provider: String): String = when (provider) {
            "auto" -> "تلقائي"
            "gemini" -> "Gemini"
            "groq" -> "Groq"
            "deepgram" -> "Deepgram"
            "azure" -> "Azure"
            "elevenlabs" -> "ElevenLabs"
            "cartesia" -> "Cartesia"
            else -> provider
        }

        /** كل الأصوات الرسمية الـ 30 مع وصفها الموثّق */
        val ALL_VOICES: List<Pair<String, String>> = listOf(
            "Kore" to "واضح وثابت",
            "Charon" to "معلوماتي وهادئ",
            "Fenrir" to "حماسي وعميق",
            "Puck" to "نشيط ومرح",
            "Aoede" to "دافئ ولحني",
            "Leda" to "شاب وواضح",
            "Orus" to "ثابت ومتزن",
            "Zephyr" to "مشرق وواضح",
            "Sulafat" to "دافئ وهادئ",
            "Gacrux" to "ناضج وعميق",
            "Autonoe" to "مشرق",
            "Callirrhoe" to "هادئ وسلس",
            "Enceladus" to "خافت وعميق",
            "Iapetus" to "واضح",
            "Umbriel" to "هادئ وسلس",
            "Algieba" to "ناعم",
            "Despina" to "ناعم",
            "Erinome" to "واضح",
            "Algenib" to "خشن ومميز",
            "Rasalgethi" to "معلوماتي",
            "Laomedeia" to "نشيط",
            "Achernar" to "ناعم وهادئ",
            "Alnilam" to "ثابت",
            "Schedar" to "متزن",
            "Pulcherrima" to "واثق",
            "Achird" to "ودود",
            "Zubenelgenubi" to "عادي وطبيعي",
            "Vindemiatrix" to "لطيف",
            "Sadachbia" to "حيوي",
            "Sadaltager" to "مثقف وواثق"
        )

        val FEMALE_VOICES = listOf("Kore", "Aoede", "Leda", "Zephyr", "Sulafat")
        val MALE_VOICES = listOf("Charon", "Fenrir", "Puck", "Orus", "Gacrux")
    }

    // ---------------- اختبار المفتاح ----------------

    suspend fun testKey(apiKey: String): Boolean {
        return try {
            val resp = withContext(Dispatchers.IO) { api.listModels(apiKey) }
            resp.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // ---------------- اختبار مفاتيح البدائل (نقطة تحقق رخيصة — من غير استهلاك رصيد) ----------------

    suspend fun testGroqKey(key: String): Result<Unit> {
        if (key.isBlank()) return Result.failure(IllegalStateException("حط مفتاح Groq الأول."))
        return testKeyEndpoint(GROQ_MODELS_URL, mapOf("Authorization" to "Bearer $key"))
    }

    suspend fun testAzureKey(key: String, region: String): Result<Unit> {
        if (key.isBlank()) return Result.failure(IllegalStateException("حط مفتاح Azure الأول."))
        val host = region.ifBlank { "westeurope" }
        return testKeyEndpoint(
            "https://$host.tts.speech.microsoft.com/cognitiveservices/voices/list",
            mapOf("Ocp-Apim-Subscription-Key" to key),
            badKeyMessage = "المفتاح غلط أو المنطقة غلط — اتأكد من الاتنين من الإعدادات."
        )
    }

    suspend fun testDeepgramKey(key: String): Result<Unit> {
        if (key.isBlank()) return Result.failure(IllegalStateException("حط مفتاح Deepgram الأول."))
        return testKeyEndpoint(DEEPGRAM_PROJECTS_URL, mapOf("Authorization" to "Token $key"))
    }

    suspend fun testElevenLabsKey(key: String): Result<Unit> {
        if (key.isBlank()) return Result.failure(IllegalStateException("حط مفتاح ElevenLabs الأول."))
        return testKeyEndpoint(ELEVENLABS_USER_URL, mapOf("xi-api-key" to key))
    }

    suspend fun testCartesiaKey(key: String): Result<Unit> {
        if (key.isBlank()) return Result.failure(IllegalStateException("حط مفتاح Cartesia الأول."))
        return testKeyEndpoint(
            CARTESIA_VOICES_URL,
            mapOf("X-API-Key" to key, "Cartesia-Version" to CARTESIA_API_VERSION)
        )
    }

    /** فحص سريع لمفتاح عبر GET — المفتاح الغلط (401/403) رسالته غير رسالة الخدمة المشغولة (429/503). */
    private suspend fun testKeyEndpoint(
        url: String,
        headers: Map<String, String>,
        badKeyMessage: String = "المفتاح غلط — اتأكد من نسخه صح من الإعدادات."
    ): Result<Unit> {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        return try {
            withContext(Dispatchers.IO) {
                http.newCall(builder.build()).executeCancellable().use { resp ->
                    when {
                        resp.isSuccessful -> Result.success(Unit)
                        resp.code == 401 || resp.code == 403 -> Result.failure(IllegalStateException(badKeyMessage))
                        resp.code == 402 -> Result.failure(IllegalStateException("رصيد الخدمة خلص."))
                        resp.code == 429 || resp.code == 503 -> Result.failure(IllegalStateException("الخدمة مشغولة دلوقتي — جرّب تاني بعد شوية."))
                        else -> Result.failure(IllegalStateException("خطأ من الخدمة (${resp.code})"))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(IllegalStateException("مفيش اتصال بالإنترنت — اتأكد من الشبكة وجرّب تاني."))
        }
    }

    // ---------------- التفريغ ----------------

    suspend fun transcribe(
        source: File,
        mimeType: String,
        apiKey: String,
        onStatus: (String) -> Unit,
        groqApiKey: String = "",
        deepgramApiKey: String = "",
        // المزود المختار يدويًا من الإعدادات ("auto" أو اسم مزود) — يتجرب الأول.
        transcriptionProvider: String = "auto",
        // تقدّم التفريغ: الجزء الحالي (يبدأ من 1) من إجمالي الأجزاء — للسيرفس والإشعار.
        onProgress: ((currentSegment: Int, totalSegments: Int) -> Unit)? = null,
        // المزوّد اللي شغال دلوقتي (Gemini/Groq/Deepgram) — للعرض في الواجهة.
        onProvider: ((String) -> Unit)? = null
    ): Result<String> {
        val sttOrder = orderedProviders(
            transcriptionProvider,
            listOf("gemini", "groq", "deepgram")
        ) { provider ->
            when (provider) {
                "gemini" -> apiKey.isNotBlank()
                "groq" -> groqApiKey.isNotBlank()
                "deepgram" -> deepgramApiKey.isNotBlank()
                else -> false
            }
        }
        if (sttOrder.isEmpty()) {
            return Result.failure(IllegalStateException("لا يوجد مفتاح — أدخل مفتاح Gemini من الإعدادات أولاً."))
        }
        return try {
            onStatus("بنجهّز الملف…")
            var audioFile = source
            // الملفات اللي اسمها غريب من غير امتداد (زي mp4.339206) نوعها بيطلع "application/octet-stream" —
            // نكشف نوعها من المحتوى نفسه، عشان استخراج الصوت والرفع يشتغلوا صح.
            var audioMime = mimeType
            if (audioMime == "application/octet-stream") {
                AudioUtils.probeMimeType(source)?.let { audioMime = it }
            }
            // نوع الملف الأصلي (بعد الكشف من المحتوى) — بيتبعت لـ Groq و Deepgram مع الملف الأصلي نفسه.
            val sourceMime = audioMime
            if (audioMime.startsWith("video/")) {
                onStatus("بنستخرج الصوت من الفيديو…")
                val extracted = AudioUtils.extractAudioTrack(source, context.cacheDir)
                if (extracted != null) {
                    audioFile = extracted
                    audioMime = "audio/mp4"
                }
            }
            val duration = AudioUtils.getDurationMs(audioFile)
            val segments: List<File> = if (duration > MAX_SEGMENT_MS) {
                onStatus("الملف طويل — بنقسّمه لأجزاء…")
                AudioUtils.splitAudioTrack(audioFile, MAX_SEGMENT_MS, context.cacheDir)
                    ?: return Result.failure(
                        IllegalStateException("الملف أطول من ساعة ومش قادر أقسّمه — قسّمه لأجزاء أقل من ساعة وجرّب تاني.")
                    )
            } else {
                listOf(audioFile)
            }

            val out = StringBuilder()
            for ((i, seg) in segments.withIndex()) {
                onProgress?.invoke(i + 1, segments.size)
                val text = transcribeSegmentWithChain(
                    seg, audioMime, i + 1, segments.size,
                    apiKey, groqApiKey, deepgramApiKey,
                    sttOrder, onStatus, onProvider,
                    // للملف الكامل (جزء واحد) نبعت الملف الأصلي لـ Groq و Deepgram —
                    // هما بيفكّوا ترميزه على سيرفرهم، فلو الصوت المستخرج عندنا فيه مشكلة، الأصلي يشتغل.
                    originalFile = if (segments.size == 1) source else null,
                    originalMime = sourceMime
                )
                out.append(text).append("\n")
            }
            Result.success(out.toString().trim())
        } catch (e: CreditExhaustedException) {
            Result.failure(IllegalStateException(e.message ?: "رصيد الخدمة خلص."))
        } catch (e: RateLimitException) {
            val msg = if (e is ServiceOverloadedException) {
                "Gemini مشغول دلوقتي من كتر الطلب — استنى شوية وجرّب تاني."
            } else {
                "وصلت للحد المجاني — استنى دقيقة وجرّب تاني، أو كمّل بكرة."
            }
            Result.failure(IllegalStateException(msg))
        } catch (e: Exception) {
            // الإلغاء مش خطأ — لازم يعدّي زي ما هو عشان السيرفس يوقف من غير رسالة خطأ.
            if (e is CancellationException) throw e
            Result.failure(
                IllegalStateException("حصلت مشكلة أثناء التفريغ: ${e.message ?: "خطأ غير معروف"}")
            )
        }
    }

    /** تفريغ جزء واحد بسلسلة المزودين بالترتيب: المختار أولاً، وبعده الباقي. */
    private suspend fun transcribeSegmentWithChain(
        seg: File,
        audioMime: String,
        segmentIndex: Int,
        totalSegments: Int,
        apiKey: String,
        groqApiKey: String,
        deepgramApiKey: String,
        order: List<String>,
        onStatus: (String) -> Unit,
        onProvider: ((String) -> Unit)? = null,
        // الملف الأصلي ونوعه — لـ Groq و Deepgram بس، لما التفريغ كله جزء واحد.
        originalFile: File? = null,
        originalMime: String = ""
    ): String {
        var lastFailure: Exception? = null
        var fromName = ""
        for (provider in order) {
            try {
                if (lastFailure != null) {
                    onStatus(fallbackStatus(lastFailure, fromName, providerDisplayName(provider)))
                }
                onProvider?.invoke(provider)
                return when (provider) {
                    "gemini" -> {
                        onStatus("بنرفع الجزء $segmentIndex من $totalSegments…")
                        val fileName = uploadFile(seg, audioMime, apiKey)
                        onStatus("بنستنى تجهيز الجزء $segmentIndex…")
                        waitForFileActive(fileName, apiKey)
                        onStatus("بنفرّغ الجزء $segmentIndex من $totalSegments…")
                        // لو Gemini هو المزود الوحيد المتاح، نعيد المحاولة مرة بعد 65 ثانية (السلوك الحالي).
                        if (order.size == 1) transcribeFileWithRetry(fileName, audioMime, apiKey)
                        else transcribeFile(fileName, audioMime, apiKey)
                    }
                    "groq" -> {
                        if (lastFailure == null) onStatus("بنفرّغ الجزء $segmentIndex من $totalSegments مع Groq…")
                        transcribeWithGroq(
                            originalFile ?: seg,
                            if (originalFile != null) originalMime else audioMime,
                            groqApiKey
                        )
                    }
                    "deepgram" -> {
                        if (lastFailure == null) onStatus("بنفرّغ الجزء $segmentIndex من $totalSegments مع Deepgram…")
                        transcribeWithDeepgram(
                            originalFile ?: seg,
                            if (originalFile != null) originalMime else audioMime,
                            deepgramApiKey
                        )
                    }
                    else -> continue
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isFallbackFailure(e)) throw e
                lastFailure = e
                fromName = providerDisplayName(provider)
            }
        }
        throw lastFailure ?: IllegalStateException("مفيش مزود تفريغ متاح.")
    }

    private suspend fun transcribeFileWithRetry(
        fileName: String,
        mimeType: String,
        apiKey: String
    ): String {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return transcribeFile(fileName, mimeType, apiKey)
            } catch (e: RateLimitException) {
                if (attempt >= 2) throw e
                delay(65_000)
            } catch (e: ProviderFailureException) {
                if (attempt >= 2) throw e
                delay(65_000)
            }
        }
    }

    private suspend fun transcribeFile(fileName: String, mimeType: String, apiKey: String): String {
        // حسب توثيق Google الرسمي لموديل gemini-3.5-transcribe:
        // الطلب يحتوي على ملف الصوت فقط، بدون أي نص إضافي وبدون generationConfig.
        // لازم الرابط الكامل للملف، مش الاسم المختصر "files/..." — الخدمة بترفض الاسم المختصر.
        val body = GenerateRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(fileData = FileData(mimeType, "https://generativelanguage.googleapis.com/v1beta/files/$fileName"))
                    )
                )
            )
        )
        val resp = withContext(Dispatchers.IO) {
            api.generateContent(TRANSCRIBE_MODEL, apiKey, body)
        }
        if (resp.code() == 429) throw RateLimitException()
        if (resp.code() == 503) throw ServiceOverloadedException()
        if (!resp.isSuccessful) {
            throwServiceError("خطأ من الخدمة", resp.code(), serviceErrorDetail(resp))
        }
        val text = resp.body()?.candidates?.firstOrNull()?.content?.parts
            ?.mapNotNull { it.text }
            ?.joinToString("") ?: ""
        if (text.isBlank()) {
            throw IllegalStateException("الخدمة رجّعت نص فاضي — جرّب تاني أو قسّم الملف.")
        }
        return text
    }

    /** يقرأ رسالة الخطأ الحقيقية من رد الخدمة (أول 300 حرف) عشان تظهر للمستخدم. */
    private fun serviceErrorDetail(resp: Response<*>): String {
        val detail = try {
            resp.errorBody()?.string()?.trim().orEmpty()
        } catch (e: Exception) {
            ""
        }
        return if (detail.isBlank()) "" else ": ${detail.take(300)}"
    }

    /** نفس الفكرة لردود OkHttp (Groq/Deepgram/Azure/ElevenLabs/Cartesia والرفع) — من غيرها رسالة الخطأ الحقيقية من الخدمة بتضيع. */
    private fun okHttpErrorDetail(resp: okhttp3.Response): String {
        val detail = try {
            resp.body?.string()?.trim().orEmpty()
        } catch (e: Exception) {
            ""
        }
        return if (detail.isBlank()) "" else ": ${detail.take(300)}"
    }

    private suspend fun uploadFile(file: File, mimeType: String, apiKey: String): String {
        val boundary = "memo-voice-${System.currentTimeMillis()}"
        val metadata = "{\"file\":{\"displayName\":\"${file.name}\"}}"
        val body = MultipartBody.Builder(boundary)
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody("application/json".toMediaType()))
            .addPart(file.asRequestBody(mimeType.toMediaType()))
            .build()
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/upload/v1beta/files?key=$apiKey")
            .addHeader("X-Goog-Upload-Protocol", "multipart")
            .post(body)
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).executeCancellable().use { resp ->
                if (resp.code == 429) throw RateLimitException()
                if (!resp.isSuccessful) {
                    throwServiceError("فشل رفع الملف", resp.code, okHttpErrorDetail(resp))
                }
                val parsed = gson.fromJson(resp.body?.string(), FileUploadResponse::class.java)
                val name = parsed.file?.name
                    ?: throw IllegalStateException("الرفع رجّع رد غير مفهوم")
                name.removePrefix("files/")
            }
        }
    }

    private suspend fun waitForFileActive(fileName: String, apiKey: String) {
        repeat(30) {
            val resp = withContext(Dispatchers.IO) { api.getFile(fileName, apiKey) }
            when (resp.body()?.state) {
                "ACTIVE" -> return
                "FAILED" -> throw ProviderFailureException("الملف اترفض من الخدمة")
            }
            delay(3000)
        }
        throw ProviderFailureException("الملف لسه مش جاهز — جرّب تاني بعد شوية.")
    }

    // ---------------- تحويل النص إلى صوت ----------------

    /**
     * يشغّل مهمة لكل عنصر بشكل متوازي (3 كحد أقصى في نفس الوقت — احترامًا للحد المجاني)
     * مع الحفاظ على ترتيب النتائج زي ترتيب العناصر الأصلية.
     * لو أي مهمة فشلت، باقي مهام نفس الدفعة بتتلغي والخطأ بيطلع زي السلوك المتتابع.
     */
    private suspend fun <T, R> runParallelOrdered(
        items: List<T>,
        maxParallel: Int,
        block: suspend (T) -> R
    ): List<R> {
        val results = arrayOfNulls<Any?>(items.size)
        for (window in items.withIndex().chunked(maxParallel)) {
            coroutineScope {
                window.map { (index, item) ->
                    async { results[index] = block(item) }
                }.awaitAll()
            }
        }
        @Suppress("UNCHECKED_CAST")
        return results.map { it as R }
    }

    suspend fun synthesize(
        text: String,
        voiceName: String,
        languageCode: String,
        style: String,
        apiKey: String,
        onStatus: (String) -> Unit,
        azureApiKey: String = "",
        azureRegion: String = "westeurope",
        elevenLabsApiKey: String = "",
        cartesiaApiKey: String = "",
        gender: String = "female",
        // المزود المختار يدويًا من الإعدادات ("auto" أو اسم مزود) — يتجرب الأول.
        voiceProvider: String = "auto",
        // المزوّد اللي شغال دلوقتي — للعرض في الواجهة.
        onProvider: ((String) -> Unit)? = null,
        // تقدّم التوليد: المرحلة ("script" لتجهيز الحوار أو "audio" لتوليد الصوت)
        // والمقطع الحالي من إجمالي المقاطع — للسيرفس والإشعار.
        onProgress: ((phase: String, current: Int, total: Int) -> Unit)? = null
    ): Result<File> {
        val voiceOrder = orderedProviders(
            voiceProvider,
            listOf("gemini", "azure", "elevenlabs", "cartesia")
        ) { provider ->
            when (provider) {
                "gemini" -> apiKey.isNotBlank()
                "azure" -> azureApiKey.isNotBlank()
                "elevenlabs" -> elevenLabsApiKey.isNotBlank()
                "cartesia" -> cartesiaApiKey.isNotBlank()
                else -> false
            }
        }
        if (voiceOrder.isEmpty()) {
            return Result.failure(IllegalStateException("لا يوجد مفتاح — أدخل مفتاح Gemini من الإعدادات أولاً."))
        }
        if (text.isBlank()) {
            return Result.failure(IllegalStateException("مفيش نص — اكتب نص أو استخرجه من ملف أو صورة أولاً."))
        }
        if (style == "podcast") {
            if (apiKey.isBlank()) {
                return Result.failure(
                    IllegalStateException("البودكاست محتاج مفتاح Gemini عشان يحوّل النص لحوار الأول.")
                )
            }
            return generatePodcast(
                text, languageCode, apiKey, onStatus,
                azureApiKey, azureRegion, elevenLabsApiKey, cartesiaApiKey,
                voiceOrder, onProvider, onProgress
            )
        }
        return try {
            val chunks = splitTextForTts(text, style)
            // المقاطع بتتولد 3 في نفس الوقت (بدل واحد ورا التاني) — أسرع بكتير للنصوص الطويلة،
            // والنتيجة بتتجمع بنفس ترتيب النص الأصلي.
            onStatus("بنولّد الصوت… ${chunks.size} أجزاء")
            onProgress?.invoke("audio", 0, chunks.size)
            val completed = AtomicInteger(0)
            val parts = runParallelOrdered(chunks, 3) { chunk ->
                // حالة حية مع كل مقطع بيبدأ — عشان الشاشة توري حركة مش رقم ثابت.
                onStatus("بنولّد الصوت… خلص ${completed.get()} من ${chunks.size}")
                val bytes = speakChunkWithChain(
                    chunk, voiceName, languageCode, style,
                    apiKey, azureApiKey, azureRegion, elevenLabsApiKey, cartesiaApiKey, gender,
                    voiceOrder, onStatus, onProvider
                )
                onProgress?.invoke("audio", completed.incrementAndGet(), chunks.size)
                bytes
            }
            val pcm = ByteArrayOutputStream()
            parts.forEach { pcm.write(it) }
            val wav = File(context.cacheDir, "voice_${System.currentTimeMillis()}.wav")
            wav.writeBytes(AudioUtils.pcmToWav(pcm.toByteArray(), 24000))
            Result.success(wav)
        } catch (e: CreditExhaustedException) {
            Result.failure(IllegalStateException(e.message ?: "رصيد الخدمة خلص."))
        } catch (e: RateLimitException) {
            val msg = if (e is ServiceOverloadedException) {
                "Gemini مشغول دلوقتي من كتر الطلب — استنى شوية وجرّب تاني."
            } else {
                "وصلت للحد المجاني لليوم — استنى شوية وجرّب تاني."
            }
            Result.failure(IllegalStateException(msg))
        } catch (e: Exception) {
            // الإلغاء مش خطأ — لازم يعدّي زي ما هو عشان السيرفس يوقف من غير رسالة خطأ.
            if (e is CancellationException) throw e
            Result.failure(
                IllegalStateException("حصلت مشكلة أثناء توليد الصوت: ${e.message ?: "خطأ غير معروف"}")
            )
        }
    }

    /** توليد صوت لجزء واحد بسلسلة المزودين بالترتيب: المختار أولاً، وبعده الباقي. */
    private suspend fun speakChunkWithChain(
        chunk: String,
        voiceName: String,
        languageCode: String,
        style: String,
        apiKey: String,
        azureApiKey: String,
        azureRegion: String,
        elevenLabsApiKey: String,
        cartesiaApiKey: String,
        gender: String,
        order: List<String>,
        onStatus: (String) -> Unit,
        onProvider: ((String) -> Unit)? = null
    ): ByteArray {
        var lastFailure: Exception? = null
        var fromName = ""
        for (provider in order) {
            try {
                if (lastFailure != null) {
                    onStatus(fallbackStatus(lastFailure, fromName, providerDisplayName(provider)))
                }
                onProvider?.invoke(provider)
                return when (provider) {
                    "gemini" -> {
                        // لو Gemini هو المزود الوحيد المتاح، نعيد المحاولة مرة بعد 65 ثانية (السلوك الحالي).
                        if (order.size == 1) speakChunkWithRetry(chunk, voiceName, languageCode, style, apiKey)
                        else speakChunk(chunk, voiceName, languageCode, style, apiKey)
                    }
                    "azure" -> {
                        val azureVoice = if (gender == "male") AZURE_EG_MALE_VOICE else AZURE_EG_FEMALE_VOICE
                        speakChunkWithAzure(chunk, azureVoice, azureApiKey, azureRegion)
                    }
                    "elevenlabs" -> {
                        val voiceId = if (gender == "male") ELEVENLABS_MALE_VOICE else ELEVENLABS_FEMALE_VOICE
                        speakChunkWithElevenLabs(chunk, voiceId, elevenLabsApiKey)
                    }
                    "cartesia" -> speakChunkWithCartesia(chunk, cartesiaApiKey)
                    else -> continue
                }
            } catch (e: Exception) {
                if (!isFallbackFailure(e)) throw e
                lastFailure = e
                fromName = providerDisplayName(provider)
            }
        }
        throw lastFailure ?: IllegalStateException("مفيش مزود صوت متاح.")
    }

    // ---------------- وضع البودكاست (حوار بين صوتين) ----------------

    private suspend fun generatePodcast(
        text: String,
        languageCode: String,
        apiKey: String,
        onStatus: (String) -> Unit,
        azureApiKey: String = "",
        azureRegion: String = "westeurope",
        elevenLabsApiKey: String = "",
        cartesiaApiKey: String = "",
        voiceOrder: List<String>,
        onProvider: ((String) -> Unit)? = null,
        onProgress: ((phase: String, current: Int, total: Int) -> Unit)? = null
    ): Result<File> {
        return try {
            onStatus("بنحوّل النص لحوار بودكاست…")
            onProgress?.invoke("script", 0, 0)
            val script = try {
                generatePodcastScript(text, languageCode, apiKey)
            } catch (e: ServiceOverloadedException) {
                onStatus("Gemini مشغول دلوقتي — هنستنى شوية ونحاول تاني…")
                delay(65_000)
                generatePodcastScript(text, languageCode, apiKey)
            } catch (e: ProviderFailureException) {
                onStatus("الخدمة حصل فيها خطأ داخلي — هنستنى شوية ونحاول تاني…")
                delay(65_000)
                generatePodcastScript(text, languageCode, apiKey)
            }
            val chunks = splitPodcastScript(script)
            if (chunks.isEmpty()) {
                throw IllegalStateException("مقدرناش نحوّل النص لحوار — جرّب نص أقصر أو أوضح.")
            }
            // مقاطع البودكاست بتتولد 3 في نفس الوقت — والنتيجة بتتجمع بنفس ترتيب الحوار.
            onStatus("بنولّد صوت البودكاست… ${chunks.size} أجزاء")
            onProgress?.invoke("audio", 0, chunks.size)
            val completed = AtomicInteger(0)
            val parts = runParallelOrdered(chunks, 3) { chunk ->
                // حالة حية مع كل مقطع بيبدأ — عشان الشاشة توري حركة مش رقم ثابت.
                onStatus("بنولّد صوت البودكاست… خلص ${completed.get()} من ${chunks.size}")
                val bytes = speakPodcastChunkWithChain(
                    chunk, languageCode, apiKey,
                    azureApiKey, azureRegion, elevenLabsApiKey, cartesiaApiKey,
                    voiceOrder, onStatus, onProvider
                )
                onProgress?.invoke("audio", completed.incrementAndGet(), chunks.size)
                bytes
            }
            val pcm = ByteArrayOutputStream()
            parts.forEach { pcm.write(it) }
            val wav = File(context.cacheDir, "podcast_${System.currentTimeMillis()}.wav")
            wav.writeBytes(AudioUtils.pcmToWav(pcm.toByteArray(), 24000))
            Result.success(wav)
        } catch (e: CreditExhaustedException) {
            Result.failure(IllegalStateException(e.message ?: "رصيد الخدمة خلص."))
        } catch (e: RateLimitException) {
            val msg = if (e is ServiceOverloadedException) {
                "Gemini مشغول دلوقتي من كتر الطلب — استنى شوية وجرّب تاني."
            } else {
                "وصلت للحد المجاني لليوم — استنى شوية وجرّب تاني."
            }
            Result.failure(IllegalStateException(msg))
        } catch (e: Exception) {
            // الإلغاء مش خطأ — لازم يعدّي زي ما هو عشان السيرفس يوقف من غير رسالة خطأ.
            if (e is CancellationException) throw e
            Result.failure(
                IllegalStateException("حصلت مشكلة أثناء توليد البودكاست: ${e.message ?: "خطأ غير معروف"}")
            )
        }
    }

    /** توليد صوت بودكاست لجزء واحد بسلسلة المزودين بالترتيب: المختار أولاً، وبعده الباقي. */
    private suspend fun speakPodcastChunkWithChain(
        chunk: String,
        languageCode: String,
        apiKey: String,
        azureApiKey: String,
        azureRegion: String,
        elevenLabsApiKey: String,
        cartesiaApiKey: String,
        order: List<String>,
        onStatus: (String) -> Unit,
        onProvider: ((String) -> Unit)? = null
    ): ByteArray {
        var lastFailure: Exception? = null
        var fromName = ""
        for (provider in order) {
            try {
                if (lastFailure != null) {
                    onStatus(fallbackStatus(lastFailure, fromName, providerDisplayName(provider)))
                }
                onProvider?.invoke(provider)
                return when (provider) {
                    "gemini" -> {
                        // لو Gemini هو المزود الوحيد المتاح، نعيد المحاولة مرة بعد 65 ثانية (السلوك الحالي).
                        if (order.size == 1) speakPodcastChunkWithRetry(chunk, languageCode, apiKey)
                        else speakPodcastChunk(chunk, languageCode, apiKey)
                    }
                    "azure" -> speakPodcastChunkWithAzure(chunk, azureApiKey, azureRegion)
                    "elevenlabs" -> speakPodcastChunkWithElevenLabs(chunk, elevenLabsApiKey)
                    "cartesia" -> speakPodcastChunkWithCartesia(chunk, cartesiaApiKey)
                    else -> continue
                }
            } catch (e: Exception) {
                if (!isFallbackFailure(e)) throw e
                lastFailure = e
                fromName = providerDisplayName(provider)
            }
        }
        throw lastFailure ?: IllegalStateException("مفيش مزود صوت متاح.")
    }

    private suspend fun generatePodcastScript(text: String, languageCode: String, apiKey: String): String {
        val dialect = if (languageCode == "ar-EG") "باللهجة المصرية" else "باللغة العربية الفصحى"
        val prompt = "حوّل النص التالي إلى حوار بودكاست طبيعي $dialect بين مقدّم وضيف خبير.\n" +
            "قواعد:\n" +
            "- كل سطر يبدأ بـ \"Speaker1:\" للمقدّم أو \"Speaker2:\" للضيف، ولا تكتب أي سطر آخر.\n" +
            "- المقدّم يسأل أسئلة قصيرة، والضيف يشرح ببساطة ووضوح.\n" +
            "- غطِّ أهم المعلومات اللي في النص، من غير ما تضيف معلومات من برّه النص.\n" +
            "- الحوار مختصر، لا يزيد عن 2000 كلمة.\n\n" +
            "النص:\n$text"
        val body = GenerateRequest(
            contents = listOf(Content(parts = listOf(Part(text = prompt))))
        )
        val resp = withContext(Dispatchers.IO) {
            api.generateContent(TEXT_MODEL, apiKey, body)
        }
        if (resp.code() == 429) throw RateLimitException()
        if (resp.code() == 503) throw ServiceOverloadedException()
        if (!resp.isSuccessful) {
            throwServiceError("خطأ من الخدمة", resp.code(), serviceErrorDetail(resp))
        }
        return resp.body()?.candidates?.firstOrNull()?.content?.parts
            ?.mapNotNull { it.text }
            ?.joinToString("") ?: ""
    }

    private fun splitPodcastScript(script: String): List<String> {
        val lines = script.lines()
            .map { it.trim() }
            .filter { it.startsWith("Speaker1:") || it.startsWith("Speaker2:") }
        val chunks = mutableListOf<String>()
        val cur = StringBuilder()
        for (line in lines) {
            if (cur.length + line.length + 1 > MAX_TTS_CHARS && cur.isNotEmpty()) {
                chunks.add(cur.toString().trim())
                cur.clear()
            }
            if (cur.isNotEmpty()) cur.append("\n")
            cur.append(line)
        }
        if (cur.isNotEmpty()) chunks.add(cur.toString().trim())
        return chunks
    }

    private suspend fun speakPodcastChunkWithRetry(
        chunk: String,
        languageCode: String,
        apiKey: String
    ): ByteArray {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return speakPodcastChunk(chunk, languageCode, apiKey)
            } catch (e: RateLimitException) {
                if (attempt >= 2) throw e
                delay(65_000)
            } catch (e: ProviderFailureException) {
                if (attempt >= 2) throw e
                delay(65_000)
            }
        }
    }

    private suspend fun speakPodcastChunk(chunk: String, languageCode: String, apiKey: String): ByteArray {
        val fullText = "اقرأ الحوار التالي بصوتين: Speaker1 مقدّم بودكاست متحمس، وSpeaker2 ضيف خبير هادئ:\n" + chunk
        val body = GenerateRequest(
            contents = listOf(Content(parts = listOf(Part(text = fullText)))),
            generationConfig = GenerationConfig(
                responseModalities = listOf("AUDIO"),
                speechConfig = SpeechConfig(
                    languageCode = languageCode,
                    multiSpeakerVoiceConfig = MultiSpeakerVoiceConfig(
                        listOf(
                            SpeakerVoiceConfig("Speaker1", VoiceConfig(PrebuiltVoiceConfig(PODCAST_HOST_VOICE))),
                            SpeakerVoiceConfig("Speaker2", VoiceConfig(PrebuiltVoiceConfig(PODCAST_GUEST_VOICE)))
                        )
                    )
                )
            )
        )
        val resp = withContext(Dispatchers.IO) {
            api.generateContent(TTS_MODEL, apiKey, body)
        }
        if (resp.code() == 429) throw RateLimitException()
        if (resp.code() == 503) throw ServiceOverloadedException()
        if (!resp.isSuccessful) {
            throwServiceError("خطأ من خدمة الصوت", resp.code(), serviceErrorDetail(resp))
        }
        val data = resp.body()?.candidates?.firstOrNull()?.content?.parts
            ?.firstOrNull { it.inlineData != null }?.inlineData?.data
            ?: throw IllegalStateException("الخدمة رجّعت صوت فاضي")
        return Base64.decode(data, Base64.DEFAULT)
    }

    // ---------------- المفاتيح الاحتياطية (التفريغ: Groq ثم Deepgram — الصوت: Azure ثم ElevenLabs ثم Cartesia) ----------------

    /** اسم ملف آمن لرفع Groq — Whisper بيرفض الأسماء من غير امتداد صوتي معروف (زي mp4.339206). */
    private fun safeAudioFileName(mimeType: String): String = when (mimeType) {
        "audio/mpeg" -> "audio.mp3"
        "audio/wav" -> "audio.wav"
        "audio/ogg" -> "audio.ogg"
        "audio/flac" -> "audio.flac"
        "audio/aac" -> "audio.aac"
        "video/mp4" -> "audio.mp4"
        else -> "audio.m4a"
    }

    private suspend fun transcribeWithGroq(file: File, mimeType: String, groqKey: String): String {
        if (file.length() > GROQ_MAX_FILE_BYTES) {
            throw ProviderFailureException("الجزء أكبر من 25 ميجا — Groq مش بيقبل ملفات أكبر من كده.")
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", safeAudioFileName(mimeType), file.asRequestBody(mimeType.toMediaType()))
            .addFormDataPart("model", GROQ_STT_MODEL)
            .addFormDataPart("language", "ar")
            .addFormDataPart("response_format", "json")
            .build()
        val request = Request.Builder()
            .url(GROQ_STT_URL)
            .addHeader("Authorization", "Bearer $groqKey")
            .post(body)
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).executeCancellable().use { resp ->
                if (resp.code == 429) throw RateLimitException()
                if (resp.code == 503) throw ServiceOverloadedException()
                if (!resp.isSuccessful) {
                    throwServiceError("خطأ من Groq", resp.code, okHttpErrorDetail(resp))
                }
                val parsed = gson.fromJson(resp.body?.string(), GroqTranscriptionResponse::class.java)
                parsed.text?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("Groq رجّع نص فاضي")
            }
        }
    }

    private suspend fun transcribeWithDeepgram(file: File, mimeType: String, deepgramKey: String): String {
        val request = Request.Builder()
            .url(DEEPGRAM_STT_URL)
            .addHeader("Authorization", "Token $deepgramKey")
            .post(file.asRequestBody(mimeType.toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).executeCancellable().use { resp ->
                if (resp.code == 429) throw RateLimitException()
                if (resp.code == 503) throw ServiceOverloadedException()
                if (resp.code == 401 || resp.code == 403) {
                    throw IllegalStateException("مفتاح Deepgram مش شغال — اتأكد من نسخه صح من الإعدادات.")
                }
                if (resp.code == 402) {
                    throw CreditExhaustedException("رصيد Deepgram خلص — الرصيد المجاني ٢٠٠ دولار مرة واحدة.")
                }
                if (!resp.isSuccessful) {
                    throwServiceError("خطأ من Deepgram", resp.code, okHttpErrorDetail(resp))
                }
                val parsed = gson.fromJson(resp.body?.string(), DeepgramTranscriptionResponse::class.java)
                parsed.results?.channels?.firstOrNull()?.alternatives?.firstOrNull()?.transcript?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("Deepgram رجّع نص فاضي")
            }
        }
    }

    /** فشل يستاهل التبديل للمزوّد اللي بعده: تجاوز حد أو ضغط أو انتهاء رصيد أو رفض المزوّد للطلب (400) أو خطأ داخلي من السيرفر (500). */
    private fun isFallbackFailure(e: Exception): Boolean =
        e is RateLimitException || e is CreditExhaustedException || e is ProviderFailureException

    /** ترتيب المزودين: المختار يدويًا أولاً، وبعده باقي المزودين المتاحين بالترتيب الافتراضي. */
    private fun orderedProviders(
        preferred: String,
        defaultOrder: List<String>,
        isAvailable: (String) -> Boolean
    ): List<String> {
        val ordered = mutableListOf<String>()
        if (preferred != "auto" && preferred in defaultOrder && isAvailable(preferred)) {
            ordered.add(preferred)
        }
        for (p in defaultOrder) {
            if (p !in ordered && isAvailable(p)) ordered.add(p)
        }
        return ordered
    }

    /** رسالة حالة عند الانتقال من مزوّد لمزوّد بديل. */
    private fun fallbackStatus(cause: Exception, fromName: String, toName: String): String =
        when (cause) {
            is ServiceOverloadedException -> "$fromName مشغول دلوقتي — بنكمّل مع $toName…"
            is CreditExhaustedException -> "رصيد $fromName خلص — بنكمّل مع $toName…"
            is ProviderFailureException -> when {
                cause.message?.contains("25 ميجا") == true -> "الجزء أكبر من 25 ميجا — بنكمّل مع $toName…"
                else -> "$fromName حصله خطأ — بنكمّل مع $toName…"
            }
            else -> "ليمت $fromName خلص — بنكمّل مع $toName…"
        }

    private suspend fun speakChunkWithAzure(
        chunk: String,
        azureVoice: String,
        azureKey: String,
        azureRegion: String
    ): ByteArray {
        val ssml = "<speak version='1.0' xml:lang='ar-EG'><voice xml:lang='ar-EG' name='$azureVoice'>${escapeXml(chunk)}</voice></speak>"
        val request = Request.Builder()
            .url("https://$azureRegion.tts.speech.microsoft.com/cognitiveservices/v1")
            .addHeader("Ocp-Apim-Subscription-Key", azureKey)
            .addHeader("Content-Type", "application/ssml+xml")
            .addHeader("X-Microsoft-OutputFormat", "raw-24khz-16bit-mono-pcm")
            .addHeader("User-Agent", "memo-voice-studio")
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType()))
            .build()
        // لو Azure علّق من غير رد، بعد 40 ثانية بنبدّل للبديل بدل ما نستنى 120 ثانية.
        try {
            return withTimeout(40_000) {
                withContext(Dispatchers.IO) {
                    http.newCall(request).executeCancellable().use { resp ->
                        if (resp.code == 429) throw RateLimitException()
                        if (resp.code == 503) throw ServiceOverloadedException()
                        if (!resp.isSuccessful) {
                            throwServiceError("خطأ من Azure", resp.code, okHttpErrorDetail(resp))
                        }
                        resp.body?.bytes() ?: throw IllegalStateException("Azure رجّع صوت فاضي")
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw ProviderFailureException("Azure مردّش خلال 40 ثانية — بنكمّل مع البديل…")
        }
    }

    private suspend fun speakPodcastChunkWithAzure(
        chunk: String,
        azureKey: String,
        azureRegion: String
    ): ByteArray {
        val out = ByteArrayOutputStream()
        for (line in chunk.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            val (voice, spoken) = when {
                trimmed.startsWith("Speaker1:") -> AZURE_EG_FEMALE_VOICE to trimmed.removePrefix("Speaker1:").trim()
                trimmed.startsWith("Speaker2:") -> AZURE_EG_MALE_VOICE to trimmed.removePrefix("Speaker2:").trim()
                else -> continue
            }
            if (spoken.isBlank()) continue
            out.write(speakChunkWithAzure(spoken, voice, azureKey, azureRegion))
        }
        return out.toByteArray()
    }

    private suspend fun speakChunkWithElevenLabs(
        chunk: String,
        voiceId: String,
        elevenLabsKey: String
    ): ByteArray {
        val json = gson.toJson(
            mapOf(
                "text" to chunk,
                "model_id" to ELEVENLABS_TTS_MODEL,
                "voice_settings" to mapOf("stability" to 0.5, "similarity_boost" to 0.75)
            )
        )
        val request = Request.Builder()
            .url("$ELEVENLABS_TTS_URL/$voiceId?output_format=pcm_24000")
            .addHeader("xi-api-key", elevenLabsKey)
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        // لو ElevenLabs علّق من غير رد، بعد 40 ثانية بنبدّل للبديل بدل ما نستنى 120 ثانية.
        try {
            return withTimeout(40_000) {
                withContext(Dispatchers.IO) {
                    http.newCall(request).executeCancellable().use { resp ->
                        if (resp.code == 429) throw RateLimitException()
                        if (resp.code == 503) throw ServiceOverloadedException()
                        if (resp.code == 401 || resp.code == 403) {
                            throw IllegalStateException("مفتاح ElevenLabs مش شغال — اتأكد من نسخه صح من الإعدادات.")
                        }
                        if (resp.code == 402) {
                            throw CreditExhaustedException("رصيد ElevenLabs المجاني خلص — بييجي ١٠ آلاف نقطة جديدة كل شهر.")
                        }
                        if (!resp.isSuccessful) {
                            throwServiceError("خطأ من ElevenLabs", resp.code, okHttpErrorDetail(resp))
                        }
                        val bytes = resp.body?.bytes() ?: throw IllegalStateException("ElevenLabs رجّع صوت فاضي")
                        if (bytes.isEmpty()) throw IllegalStateException("ElevenLabs رجّع صوت فاضي")
                        bytes
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw ProviderFailureException("ElevenLabs مردّش خلال 40 ثانية — بنكمّل مع البديل…")
        }
    }

    private suspend fun speakPodcastChunkWithElevenLabs(chunk: String, elevenLabsKey: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (line in chunk.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            val (voiceId, spoken) = when {
                trimmed.startsWith("Speaker1:") -> ELEVENLABS_MALE_VOICE to trimmed.removePrefix("Speaker1:").trim()
                trimmed.startsWith("Speaker2:") -> ELEVENLABS_FEMALE_VOICE to trimmed.removePrefix("Speaker2:").trim()
                else -> continue
            }
            if (spoken.isBlank()) continue
            out.write(speakChunkWithElevenLabs(spoken, voiceId, elevenLabsKey))
        }
        return out.toByteArray()
    }

    private suspend fun speakChunkWithCartesia(chunk: String, cartesiaKey: String): ByteArray {
        val json = gson.toJson(
            mapOf(
                "model_id" to CARTESIA_TTS_MODEL,
                "transcript" to chunk,
                "voice" to CARTESIA_VOICE,
                "output_format" to mapOf(
                    "container" to "raw",
                    "encoding" to "pcm_s16le",
                    "sample_rate" to 24000
                ),
                "language" to "ar"
            )
        )
        val request = Request.Builder()
            .url(CARTESIA_TTS_URL)
            .addHeader("X-API-Key", cartesiaKey)
            .addHeader("Cartesia-Version", "2024-06-10")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        // لو Cartesia علّق من غير رد، بعد 40 ثانية بنبدّل للبديل بدل ما نستنى 120 ثانية.
        try {
            return withTimeout(40_000) {
                withContext(Dispatchers.IO) {
                    http.newCall(request).executeCancellable().use { resp ->
                        if (resp.code == 429) throw RateLimitException()
                        if (resp.code == 503) throw ServiceOverloadedException()
                        if (resp.code == 401 || resp.code == 403) {
                            throw IllegalStateException("مفتاح Cartesia مش شغال — اتأكد من نسخه صح من الإعدادات.")
                        }
                        if (resp.code == 402) {
                            throw CreditExhaustedException("رصيد Cartesia المجاني خلص — بييجي ٢٠ ألف نقطة جديدة كل شهر.")
                        }
                        if (!resp.isSuccessful) {
                            throwServiceError("خطأ من Cartesia", resp.code, okHttpErrorDetail(resp))
                        }
                        val bytes = resp.body?.bytes() ?: throw IllegalStateException("Cartesia رجّع صوت فاضي")
                        if (bytes.isEmpty()) throw IllegalStateException("Cartesia رجّع صوت فاضي")
                        bytes
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw ProviderFailureException("Cartesia مردّش خلال 40 ثانية — بنكمّل مع البديل…")
        }
    }

    private suspend fun speakPodcastChunkWithCartesia(chunk: String, cartesiaKey: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (line in chunk.lines()) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue
            val spoken = when {
                trimmed.startsWith("Speaker1:") -> trimmed.removePrefix("Speaker1:").trim()
                trimmed.startsWith("Speaker2:") -> trimmed.removePrefix("Speaker2:").trim()
                else -> continue
            }
            if (spoken.isBlank()) continue
            out.write(speakChunkWithCartesia(spoken, cartesiaKey))
        }
        return out.toByteArray()
    }

    private fun escapeXml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private suspend fun speakChunkWithRetry(
        chunk: String,
        voiceName: String,
        languageCode: String,
        style: String,
        apiKey: String
    ): ByteArray {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return speakChunk(chunk, voiceName, languageCode, style, apiKey)
            } catch (e: RateLimitException) {
                if (attempt >= 2) throw e
                delay(65_000)
            } catch (e: ProviderFailureException) {
                if (attempt >= 2) throw e
                delay(65_000)
            }
        }
    }

    private suspend fun speakChunk(
        chunk: String,
        voiceName: String,
        languageCode: String,
        style: String,
        apiKey: String
    ): ByteArray {
        val fullText = stylePrefix(style, languageCode) + chunk
        val json = gson.toJson(
            GenerateRequest(
                contents = listOf(Content(parts = listOf(Part(text = fullText)))),
                generationConfig = GenerationConfig(
                    responseModalities = listOf("AUDIO"),
                    speechConfig = SpeechConfig(
                        voiceConfig = VoiceConfig(PrebuiltVoiceConfig(voiceName)),
                        languageCode = languageCode
                    )
                )
            )
        )
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$TTS_MODEL:generateContent?key=$apiKey")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()
        // توليد الصوت العادي من Gemini كان بيروح من طريق Retrofit، والطلب المعلق فيه
        // مبيتقطعش بزرار "إيقاف" ومبيتبدلش للبديل لو Gemini علّق من غير رد — فالتطبيق
        // كان بيستنى لحد 120 ثانية على الفاضي. دلوقتي بنستخدم نفس الطريق القابل
        // للإلغاء اللي بيستخدمه باقي المزودين (Build 30)، ولو Gemini مردّش خلال
        // 40 ثانية بنعتبره فشل وبنبدّل تلقائيًا للمزوّد اللي بعده.
        try {
            return withTimeout(40_000) {
                withContext(Dispatchers.IO) {
                    http.newCall(request).executeCancellable().use { resp ->
                        if (resp.code == 429) throw RateLimitException()
                        if (resp.code == 503) throw ServiceOverloadedException()
                        if (!resp.isSuccessful) {
                            throwServiceError("خطأ من خدمة الصوت", resp.code, okHttpErrorDetail(resp))
                        }
                        val bodyString = resp.body?.string()
                            ?: throw IllegalStateException("الخدمة رجّعت صوت فاضي")
                        val data = gson.fromJson(bodyString, GenerateResponse::class.java)
                            .candidates?.firstOrNull()?.content?.parts
                            ?.firstOrNull { it.inlineData != null }?.inlineData?.data
                            ?: throw IllegalStateException("الخدمة رجّعت صوت فاضي")
                        Base64.decode(data, Base64.DEFAULT)
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw ProviderFailureException("Gemini مردّش خلال 40 ثانية — بنكمّل مع البديل…")
        }
    }

    /**
     * توجيه نطق واضح بيتحط قبل كل مقطع، حسب اللهجة والستايل المختار —
     * بيخلي الصوت طبيعي وواضح بدل القراءة الآلية الجامدة.
     */
    private fun stylePrefix(style: String, languageCode: String): String {
        val egyptian = languageCode == "ar-EG"
        return when (style) {
            "poetry" -> if (egyptian)
                "ألقِ الأبيات التالية باللهجة المصرية بإلقاء شعري بطيء وواضح، مع وقفة قصيرة بعد كل بيت:\n"
            else
                "ألقِ الأبيات التالية باللغة العربية الفصحى بإلقاء شعري بطيء وواضح، مع وقفة قصيرة بعد كل بيت:\n"
            "story" -> if (egyptian)
                "احكِ النص التالي باللهجة المصرية بأسلوب سرد قصصي مشوق، مع وقفات قصيرة بعد كل جملة ونبرة أعلى عند الأسئلة:\n"
            else
                "اقرأ النص التالي باللغة العربية الفصحى بأسلوب سرد قصصي مشوق، مع وقفات قصيرة بعد كل جملة ونبرة أعلى عند الأسئلة:\n"
            else -> if (egyptian)
                "اقرأ النص التالي بصوت طبيعي واضح باللهجة المصرية، بنبرة هادئة ومخارج حروف سليمة:\n"
            else
                "اقرأ النص التالي باللغة العربية الفصحى بصوت واضح وطبيعي، بنبرة هادئة ومخارج حروف سليمة:\n"
        }
    }

    fun splitTextForTts(text: String, style: String): List<String> {
        val normalized = text.replace("\r\n", "\n")
        val pieces = if (style == "poetry") {
            normalized.split("\n").filter { it.isNotBlank() }
        } else {
            normalized.split(Regex("(?<=[.!؟\n])\\s+")).filter { it.isNotBlank() }
        }
        val chunks = mutableListOf<String>()
        val cur = StringBuilder()
        for (piece in pieces) {
            if (cur.length + piece.length + 1 > MAX_TTS_CHARS && cur.isNotEmpty()) {
                chunks.add(cur.toString().trim())
                cur.clear()
            }
            if (cur.isNotEmpty()) cur.append(if (style == "poetry") "\n" else " ")
            cur.append(piece)
        }
        if (cur.isNotEmpty()) chunks.add(cur.toString().trim())
        return chunks.ifEmpty { listOf(text) }
    }

    // ---------------- قراءة نص من صورة ----------------

    suspend fun ocrImage(
        bitmap: Bitmap,
        apiKey: String,
        onStatus: ((String) -> Unit)? = null
    ): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("لا يوجد مفتاح — أدخل مفتاح Gemini من الإعدادات أولاً."))
        }
        return try {
            val text = try {
                ocrImageOnce(bitmap, apiKey)
            } catch (e: ServiceOverloadedException) {
                // موديل الرؤية مفيهوش مزود بديل — نعيد المحاولة مرة واحدة بعد 65 ثانية (نفس نمط البودكاست).
                onStatus?.invoke("Gemini مشغول دلوقتي — هنستنى شوية ونحاول تاني…")
                delay(65_000)
                ocrImageOnce(bitmap, apiKey)
            }
            Result.success(text.trim())
        } catch (e: RateLimitException) {
            val msg = if (e is ServiceOverloadedException) {
                "Gemini مشغول دلوقتي من كتر الطلب — استنى شوية وجرّب تاني."
            } else {
                "وصلت للحد المجاني — جرّب تاني بعد شوية."
            }
            Result.failure(IllegalStateException(msg))
        } catch (e: Exception) {
            // الإلغاء مش خطأ — لازم يعدّي زي ما هو عشان مفيش رسالة خطأ وهمية.
            if (e is CancellationException) throw e
            Result.failure(IllegalStateException("مش قادر أقرأ الصورة: ${e.message ?: ""}"))
        }
    }

    /** قراءة نص من صورة — محاولة واحدة. 429 → حد مجاني، 503 → ضغط على الخدمة. */
    private suspend fun ocrImageOnce(bitmap: Bitmap, apiKey: String): String {
        val targetWidth = minOf(bitmap.width, 2048)
        val targetHeight = (targetWidth.toFloat() / bitmap.width * bitmap.height).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        val stream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, 85, stream)
        val b64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        val body = GenerateRequest(
            contents = listOf(
                Content(
                    parts = listOf(
                        Part(inlineData = InlineData("image/jpeg", b64)),
                        Part(text = "استخرج كل النص العربي الموجود في الصورة حرفيًا كما هو، دون تلخيص أو إضافة أو ترجمة.")
                    )
                )
            )
        )
        val resp = withContext(Dispatchers.IO) {
            api.generateContent(VISION_MODEL, apiKey, body)
        }
        if (resp.code() == 429) throw RateLimitException()
        if (resp.code() == 503) throw ServiceOverloadedException()
        if (!resp.isSuccessful) {
            throw IllegalStateException("خطأ من الخدمة (${resp.code()})${serviceErrorDetail(resp)}")
        }
        return resp.body()?.candidates?.firstOrNull()?.content?.parts
            ?.mapNotNull { it.text }
            ?.joinToString("") ?: ""
    }
}

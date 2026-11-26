package com.memo.voicestudio.data

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
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
import java.util.concurrent.TimeUnit

open class RateLimitException : Exception()

/** الخدمة مشغولة مؤقتًا (ضغط عالي) — تُعامل مثل تجاوز الحد: إعادة محاولة أو التبديل للبديل. */
class ServiceOverloadedException : RateLimitException()

/**
 * كل التعامل مع Gemini API هنا:
 * - التفريغ: gemini-3.5-transcribe (باقة مجانية: حتى ساعة في الطلب، ~25 طلب/يوم)
 * - الصوت: gemini-2.5-flash-preview-tts (باقة مجانية، عربي مصري وفصحى)
 * - قراءة الصور وPDF المصوّر: gemini-3.8-flash
 * - بدائل احتياطية عند انتهاء الحد أو الضغط: التفريغ Groq ثم Deepgram،
 *   والصوت Azure ثم ElevenLabs ثم Cartesia (كل واحد بمفتاح مجاني من الإعدادات).
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
        .readTimeout(600, TimeUnit.SECONDS)
        .writeTimeout(600, TimeUnit.SECONDS)
        .build()

    private val api: GeminiApi = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(http)
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()
        .create(GeminiApi::class.java)

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

        /** أقصى طول للجزء الواحد عند التفريغ (50 دقيقة — أقل من حد الساعة) */
        const val MAX_SEGMENT_MS = 50L * 60L * 1000L

        /** أقصى طول لنص طلب الصوت الواحد */
        const val MAX_TTS_CHARS = 2500

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

    // ---------------- التفريغ ----------------

    suspend fun transcribe(
        source: File,
        mimeType: String,
        apiKey: String,
        onStatus: (String) -> Unit,
        groqApiKey: String = "",
        deepgramApiKey: String = ""
    ): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("لا يوجد مفتاح — أدخل مفتاح Gemini من الإعدادات أولاً."))
        }
        return try {
            onStatus("بنجهّز الملف…")
            var audioFile = source
            var audioMime = mimeType
            if (mimeType.startsWith("video/")) {
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
                onStatus("بنرفع الجزء ${i + 1} من ${segments.size}…")
                val fileName = uploadFile(seg, audioMime, apiKey)
                onStatus("بنستنى تجهيز الجزء ${i + 1}…")
                waitForFileActive(fileName, apiKey)
                onStatus("بنفرّغ الجزء ${i + 1} من ${segments.size}…")
                val hasSttFallback = groqApiKey.isNotBlank() || deepgramApiKey.isNotBlank()
                val text = if (!hasSttFallback) {
                    transcribeFileWithRetry(fileName, audioMime, apiKey)
                } else {
                    try {
                        transcribeFile(fileName, audioMime, apiKey)
                    } catch (e: RateLimitException) {
                        transcribeWithSttFallbacks(seg, audioMime, groqApiKey, deepgramApiKey, onStatus, e)
                    }
                }
                out.append(text).append("\n")
            }
            Result.success(out.toString().trim())
        } catch (e: RateLimitException) {
            val msg = if (e is ServiceOverloadedException) {
                "Gemini مشغول دلوقتي من كتر الطلب — استنى شوية وجرّب تاني."
            } else {
                "وصلت للحد المجاني — استنى دقيقة وجرّب تاني، أو كمّل بكرة."
            }
            Result.failure(IllegalStateException(msg))
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException("حصلت مشكلة أثناء التفريغ: ${e.message ?: "خطأ غير معروف"}")
            )
        }
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
            throw IllegalStateException("خطأ من الخدمة (${resp.code()})${serviceErrorDetail(resp)}")
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
            http.newCall(request).execute().use { resp ->
                if (resp.code == 429) throw RateLimitException()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("فشل رفع الملف (${resp.code})")
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
                "FAILED" -> throw IllegalStateException("الملف اترفض من الخدمة")
            }
            delay(3000)
        }
        throw IllegalStateException("الملف لسه مش جاهز — جرّب تاني بعد شوية.")
    }

    // ---------------- تحويل النص إلى صوت ----------------

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
        gender: String = "female"
    ): Result<File> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("لا يوجد مفتاح — أدخل مفتاح Gemini من الإعدادات أولاً."))
        }
        if (text.isBlank()) {
            return Result.failure(IllegalStateException("مفيش نص — اكتب نص أو استخرجه من ملف أو صورة أولاً."))
        }
        if (style == "podcast") {
            return generatePodcast(text, languageCode, apiKey, onStatus, azureApiKey, azureRegion, elevenLabsApiKey, cartesiaApiKey)
        }
        return try {
            val chunks = splitTextForTts(text, style)
            val hasVoiceFallback = azureApiKey.isNotBlank() || elevenLabsApiKey.isNotBlank() || cartesiaApiKey.isNotBlank()
            val pcm = ByteArrayOutputStream()
            for ((i, chunk) in chunks.withIndex()) {
                onStatus("بنولّد الصوت… جزء ${i + 1} من ${chunks.size}")
                val bytes = if (!hasVoiceFallback) {
                    speakChunkWithRetry(chunk, voiceName, languageCode, style, apiKey)
                } else {
                    try {
                        speakChunk(chunk, voiceName, languageCode, style, apiKey)
                    } catch (e: RateLimitException) {
                        speakChunkWithFallbacks(chunk, azureApiKey, azureRegion, elevenLabsApiKey, cartesiaApiKey, gender, onStatus, e)
                    }
                }
                pcm.write(bytes)
            }
            val wav = File(context.cacheDir, "voice_${System.currentTimeMillis()}.wav")
            wav.writeBytes(AudioUtils.pcmToWav(pcm.toByteArray(), 24000))
            Result.success(wav)
        } catch (e: RateLimitException) {
            val msg = if (e is ServiceOverloadedException) {
                "Gemini مشغول دلوقتي من كتر الطلب — استنى شوية وجرّب تاني."
            } else {
                "وصلت للحد المجاني لليوم — استنى شوية وجرّب تاني."
            }
            Result.failure(IllegalStateException(msg))
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException("حصلت مشكلة أثناء توليد الصوت: ${e.message ?: "خطأ غير معروف"}")
            )
        }
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
        cartesiaApiKey: String = ""
    ): Result<File> {
        return try {
            onStatus("بنحوّل النص لحوار بودكاست…")
            val script = generatePodcastScript(text, languageCode, apiKey)
            val chunks = splitPodcastScript(script)
            if (chunks.isEmpty()) {
                throw IllegalStateException("مقدرناش نحوّل النص لحوار — جرّب نص أقصر أو أوضح.")
            }
            val hasVoiceFallback = azureApiKey.isNotBlank() || elevenLabsApiKey.isNotBlank() || cartesiaApiKey.isNotBlank()
            val pcm = ByteArrayOutputStream()
            for ((i, chunk) in chunks.withIndex()) {
                onStatus("بنولّد صوت البودكاست… جزء ${i + 1} من ${chunks.size}")
                val bytes = if (!hasVoiceFallback) {
                    speakPodcastChunkWithRetry(chunk, languageCode, apiKey)
                } else {
                    try {
                        speakPodcastChunk(chunk, languageCode, apiKey)
                    } catch (e: RateLimitException) {
                        speakPodcastChunkWithFallbacks(chunk, azureApiKey, azureRegion, elevenLabsApiKey, cartesiaApiKey, onStatus, e)
                    }
                }
                pcm.write(bytes)
            }
            val wav = File(context.cacheDir, "podcast_${System.currentTimeMillis()}.wav")
            wav.writeBytes(AudioUtils.pcmToWav(pcm.toByteArray(), 24000))
            Result.success(wav)
        } catch (e: RateLimitException) {
            val msg = if (e is ServiceOverloadedException) {
                "Gemini مشغول دلوقتي من كتر الطلب — استنى شوية وجرّب تاني."
            } else {
                "وصلت للحد المجاني لليوم — استنى شوية وجرّب تاني."
            }
            Result.failure(IllegalStateException(msg))
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException("حصلت مشكلة أثناء توليد البودكاست: ${e.message ?: "خطأ غير معروف"}")
            )
        }
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
        if (!resp.isSuccessful) {
            throw IllegalStateException("خطأ من الخدمة (${resp.code()})${serviceErrorDetail(resp)}")
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
            throw IllegalStateException("خطأ من خدمة الصوت (${resp.code()})${serviceErrorDetail(resp)}")
        }
        val data = resp.body()?.candidates?.firstOrNull()?.content?.parts
            ?.firstOrNull { it.inlineData != null }?.inlineData?.data
            ?: throw IllegalStateException("الخدمة رجّعت صوت فاضي")
        return Base64.decode(data, Base64.DEFAULT)
    }

    // ---------------- المفاتيح الاحتياطية (التفريغ: Groq ثم Deepgram — الصوت: Azure ثم ElevenLabs ثم Cartesia) ----------------

    private suspend fun transcribeWithGroq(file: File, mimeType: String, groqKey: String): String {
        if (file.length() > GROQ_MAX_FILE_BYTES) {
            throw IllegalStateException("الجزء أكبر من 25 ميجا — Groq مش بيقبل ملفات أكبر من كده.")
        }
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody(mimeType.toMediaType()))
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
            http.newCall(request).execute().use { resp ->
                if (resp.code == 429) throw RateLimitException()
                if (resp.code == 503) throw ServiceOverloadedException()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("خطأ من Groq (${resp.code})")
                }
                val parsed = gson.fromJson(resp.body?.string(), GroqTranscriptionResponse::class.java)
                parsed.text?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("Groq رجّع نص فاضي")
            }
        }
    }

    /** سلسلة التفريغ الاحتياطية: Groq ثم Deepgram (كل واحد مرة واحدة لكل جزء). */
    private suspend fun transcribeWithSttFallbacks(
        file: File,
        mimeType: String,
        groqKey: String,
        deepgramKey: String,
        onStatus: (String) -> Unit,
        cause: RateLimitException
    ): String {
        if (groqKey.isNotBlank()) {
            try {
                onStatus(fallbackStatus(cause, "Gemini", "Groq"))
                return transcribeWithGroq(file, mimeType, groqKey)
            } catch (e: RateLimitException) {
                if (deepgramKey.isBlank()) throw e
                onStatus(fallbackStatus(e, "Groq", "Deepgram"))
                return transcribeWithDeepgram(file, mimeType, deepgramKey)
            }
        }
        onStatus(fallbackStatus(cause, "Gemini", "Deepgram"))
        return transcribeWithDeepgram(file, mimeType, deepgramKey)
    }

    private suspend fun transcribeWithDeepgram(file: File, mimeType: String, deepgramKey: String): String {
        val request = Request.Builder()
            .url(DEEPGRAM_STT_URL)
            .addHeader("Authorization", "Token $deepgramKey")
            .post(file.asRequestBody(mimeType.toMediaType()))
            .build()
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { resp ->
                if (resp.code == 429) throw RateLimitException()
                if (resp.code == 503) throw ServiceOverloadedException()
                if (resp.code == 401 || resp.code == 403) {
                    throw IllegalStateException("مفتاح Deepgram مش شغال — اتأكد من نسخه صح من الإعدادات.")
                }
                if (resp.code == 402) {
                    throw IllegalStateException("رصيد Deepgram خلص — الرصيد المجاني ٢٠٠ دولار مرة واحدة.")
                }
                if (!resp.isSuccessful) {
                    throw IllegalStateException("خطأ من Deepgram (${resp.code})")
                }
                val parsed = gson.fromJson(resp.body?.string(), DeepgramTranscriptionResponse::class.java)
                parsed.results?.channels?.firstOrNull()?.alternatives?.firstOrNull()?.transcript?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("Deepgram رجّع نص فاضي")
            }
        }
    }

    /** رسالة حالة عند الانتقال من مزوّد لمزوّد بديل. */
    private fun fallbackStatus(cause: RateLimitException, fromName: String, toName: String): String =
        if (cause is ServiceOverloadedException) "$fromName مشغول دلوقتي — بنكمّل مع $toName…"
        else "ليمت $fromName خلص — بنكمّل مع $toName…"

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
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { resp ->
                if (resp.code == 429) throw RateLimitException()
                if (resp.code == 503) throw ServiceOverloadedException()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("خطأ من Azure (${resp.code})")
                }
                resp.body?.bytes() ?: throw IllegalStateException("Azure رجّع صوت فاضي")
            }
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

    /** سلسلة الصوت الاحتياطية (صوت واحد): Azure ثم ElevenLabs ثم Cartesia. */
    private suspend fun speakChunkWithFallbacks(
        chunk: String,
        azureApiKey: String,
        azureRegion: String,
        elevenLabsApiKey: String,
        cartesiaApiKey: String,
        gender: String,
        onStatus: (String) -> Unit,
        cause: RateLimitException
    ): ByteArray {
        var lastError: RateLimitException = cause
        var fromName = "Gemini للصوت"
        if (azureApiKey.isNotBlank()) {
            try {
                onStatus(fallbackStatus(cause, fromName, "Azure"))
                val azureVoice = if (gender == "male") AZURE_EG_MALE_VOICE else AZURE_EG_FEMALE_VOICE
                return speakChunkWithAzure(chunk, azureVoice, azureApiKey, azureRegion)
            } catch (e: RateLimitException) {
                lastError = e
                fromName = "Azure"
            }
        }
        if (elevenLabsApiKey.isNotBlank()) {
            try {
                onStatus(fallbackStatus(lastError, fromName, "ElevenLabs"))
                val voiceId = if (gender == "male") ELEVENLABS_MALE_VOICE else ELEVENLABS_FEMALE_VOICE
                return speakChunkWithElevenLabs(chunk, voiceId, elevenLabsApiKey)
            } catch (e: RateLimitException) {
                lastError = e
                fromName = "ElevenLabs"
            }
        }
        if (cartesiaApiKey.isNotBlank()) {
            onStatus(fallbackStatus(lastError, fromName, "Cartesia"))
            return speakChunkWithCartesia(chunk, cartesiaApiKey)
        }
        throw lastError
    }

    /** سلسلة الصوت الاحتياطية للبودكاست (كل سطر حوار لوحده): Azure ثم ElevenLabs ثم Cartesia. */
    private suspend fun speakPodcastChunkWithFallbacks(
        chunk: String,
        azureApiKey: String,
        azureRegion: String,
        elevenLabsApiKey: String,
        cartesiaApiKey: String,
        onStatus: (String) -> Unit,
        cause: RateLimitException
    ): ByteArray {
        var lastError: RateLimitException = cause
        var fromName = "Gemini للصوت"
        if (azureApiKey.isNotBlank()) {
            try {
                onStatus(fallbackStatus(cause, fromName, "Azure"))
                return speakPodcastChunkWithAzure(chunk, azureApiKey, azureRegion)
            } catch (e: RateLimitException) {
                lastError = e
                fromName = "Azure"
            }
        }
        if (elevenLabsApiKey.isNotBlank()) {
            try {
                onStatus(fallbackStatus(lastError, fromName, "ElevenLabs"))
                return speakPodcastChunkWithElevenLabs(chunk, elevenLabsApiKey)
            } catch (e: RateLimitException) {
                lastError = e
                fromName = "ElevenLabs"
            }
        }
        if (cartesiaApiKey.isNotBlank()) {
            onStatus(fallbackStatus(lastError, fromName, "Cartesia"))
            return speakPodcastChunkWithCartesia(chunk, cartesiaApiKey)
        }
        throw lastError
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
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { resp ->
                if (resp.code == 429) throw RateLimitException()
                if (resp.code == 503) throw ServiceOverloadedException()
                if (resp.code == 401 || resp.code == 403) {
                    throw IllegalStateException("مفتاح ElevenLabs مش شغال — اتأكد من نسخه صح من الإعدادات.")
                }
                if (resp.code == 402) {
                    throw IllegalStateException("رصيد ElevenLabs المجاني خلص — بييجي ١٠ آلاف نقطة جديدة كل شهر.")
                }
                if (!resp.isSuccessful) {
                    throw IllegalStateException("خطأ من ElevenLabs (${resp.code})")
                }
                val bytes = resp.body?.bytes() ?: throw IllegalStateException("ElevenLabs رجّع صوت فاضي")
                if (bytes.isEmpty()) throw IllegalStateException("ElevenLabs رجّع صوت فاضي")
                bytes
            }
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
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { resp ->
                if (resp.code == 429) throw RateLimitException()
                if (resp.code == 503) throw ServiceOverloadedException()
                if (resp.code == 401 || resp.code == 403) {
                    throw IllegalStateException("مفتاح Cartesia مش شغال — اتأكد من نسخه صح من الإعدادات.")
                }
                if (resp.code == 402) {
                    throw IllegalStateException("رصيد Cartesia المجاني خلص — بييجي ٢٠ ألف نقطة جديدة كل شهر.")
                }
                if (!resp.isSuccessful) {
                    throw IllegalStateException("خطأ من Cartesia (${resp.code})")
                }
                val bytes = resp.body?.bytes() ?: throw IllegalStateException("Cartesia رجّع صوت فاضي")
                if (bytes.isEmpty()) throw IllegalStateException("Cartesia رجّع صوت فاضي")
                bytes
            }
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
        val fullText = stylePrefix(style) + chunk
        val body = GenerateRequest(
            contents = listOf(Content(parts = listOf(Part(text = fullText)))),
            generationConfig = GenerationConfig(
                responseModalities = listOf("AUDIO"),
                speechConfig = SpeechConfig(
                    voiceConfig = VoiceConfig(PrebuiltVoiceConfig(voiceName)),
                    languageCode = languageCode
                )
            )
        )
        val resp = withContext(Dispatchers.IO) {
            api.generateContent(TTS_MODEL, apiKey, body)
        }
        if (resp.code() == 429) throw RateLimitException()
        if (resp.code() == 503) throw ServiceOverloadedException()
        if (!resp.isSuccessful) {
            throw IllegalStateException("خطأ من خدمة الصوت (${resp.code()})${serviceErrorDetail(resp)}")
        }
        val data = resp.body()?.candidates?.firstOrNull()?.content?.parts
            ?.firstOrNull { it.inlineData != null }?.inlineData?.data
            ?: throw IllegalStateException("الخدمة رجّعت صوت فاضي")
        return Base64.decode(data, Base64.DEFAULT)
    }

    private fun stylePrefix(style: String): String = when (style) {
        "poetry" -> "اقرأ الأبيات التالية بإلقاء شعري بطيء وواضح، مع وقفة قصيرة بعد كل بيت:\n"
        "story" -> "اقرأ النص التالي بأسلوب سرد قصصي مشوق، مع وقفات قصيرة بعد كل جملة ونبرة أعلى عند الأسئلة:\n"
        else -> ""
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

    suspend fun ocrImage(bitmap: Bitmap, apiKey: String): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("لا يوجد مفتاح — أدخل مفتاح Gemini من الإعدادات أولاً."))
        }
        return try {
            val targetWidth = minOf(bitmap.width, 1600)
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
            if (!resp.isSuccessful) {
                throw IllegalStateException("خطأ من الخدمة (${resp.code()})${serviceErrorDetail(resp)}")
            }
            val text = resp.body()?.candidates?.firstOrNull()?.content?.parts
                ?.mapNotNull { it.text }
                ?.joinToString("") ?: ""
            Result.success(text.trim())
        } catch (e: RateLimitException) {
            Result.failure(IllegalStateException("وصلت للحد المجاني — جرّب تاني بعد شوية."))
        } catch (e: Exception) {
            Result.failure(IllegalStateException("مش قادر أقرأ الصورة: ${e.message ?: ""}"))
        }
    }
}

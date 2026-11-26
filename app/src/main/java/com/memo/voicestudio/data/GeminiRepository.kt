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

class RateLimitException : Exception()

/**
 * كل التعامل مع Gemini API هنا:
 * - التفريغ: gemini-3.5-transcribe (باقة مجانية: حتى ساعة في الطلب، ~25 طلب/يوم)
 * - الصوت: gemini-2.5-flash-preview-tts (باقة مجانية، عربي مصري وفصحى)
 * - قراءة الصور وPDF المصوّر: gemini-2.5-flash
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
        const val VISION_MODEL = "gemini-2.5-flash"
        const val TEXT_MODEL = "gemini-2.5-flash"
        const val PODCAST_HOST_VOICE = "Zephyr"
        const val PODCAST_GUEST_VOICE = "Charon"
        const val GROQ_STT_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
        const val GROQ_STT_MODEL = "whisper-large-v3-turbo"
        const val GROQ_MAX_FILE_BYTES = 25L * 1024L * 1024L
        const val AZURE_EG_FEMALE_VOICE = "ar-EG-SalmaNeural"
        const val AZURE_EG_MALE_VOICE = "ar-EG-ShakirNeural"

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
        groqApiKey: String = ""
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
                val text = if (groqApiKey.isBlank()) {
                    transcribeFileWithRetry(fileName, audioMime, apiKey)
                } else {
                    try {
                        transcribeFile(fileName, audioMime, apiKey)
                    } catch (e: RateLimitException) {
                        onStatus("ليمت Gemini خلص — بنكمّل مع Groq…")
                        transcribeWithGroq(seg, audioMime, groqApiKey)
                    }
                }
                out.append(text).append("\n")
            }
            Result.success(out.toString().trim())
        } catch (e: RateLimitException) {
            Result.failure(
                IllegalStateException("وصلت للحد المجاني — استنى دقيقة وجرّب تاني، أو كمّل بكرة.")
            )
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
        gender: String = "female"
    ): Result<File> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("لا يوجد مفتاح — أدخل مفتاح Gemini من الإعدادات أولاً."))
        }
        if (text.isBlank()) {
            return Result.failure(IllegalStateException("مفيش نص — اكتب نص أو استخرجه من ملف أو صورة أولاً."))
        }
        if (style == "podcast") {
            return generatePodcast(text, languageCode, apiKey, onStatus, azureApiKey, azureRegion)
        }
        return try {
            val chunks = splitTextForTts(text, style)
            val azureVoice = if (gender == "male") AZURE_EG_MALE_VOICE else AZURE_EG_FEMALE_VOICE
            val pcm = ByteArrayOutputStream()
            for ((i, chunk) in chunks.withIndex()) {
                onStatus("بنولّد الصوت… جزء ${i + 1} من ${chunks.size}")
                val bytes = try {
                    speakChunkWithRetry(chunk, voiceName, languageCode, style, apiKey)
                } catch (e: RateLimitException) {
                    if (azureApiKey.isBlank()) throw e
                    onStatus("ليمت Gemini للصوت خلص — بنكمّل مع Azure…")
                    speakChunkWithAzure(chunk, azureVoice, azureApiKey, azureRegion)
                }
                pcm.write(bytes)
            }
            val wav = File(context.cacheDir, "voice_${System.currentTimeMillis()}.wav")
            wav.writeBytes(AudioUtils.pcmToWav(pcm.toByteArray(), 24000))
            Result.success(wav)
        } catch (e: RateLimitException) {
            Result.failure(
                IllegalStateException("وصلت للحد المجاني لليوم — استنى شوية وجرّب تاني.")
            )
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
        azureRegion: String = "westeurope"
    ): Result<File> {
        return try {
            onStatus("بنحوّل النص لحوار بودكاست…")
            val script = generatePodcastScript(text, languageCode, apiKey)
            val chunks = splitPodcastScript(script)
            if (chunks.isEmpty()) {
                throw IllegalStateException("مقدرناش نحوّل النص لحوار — جرّب نص أقصر أو أوضح.")
            }
            val pcm = ByteArrayOutputStream()
            for ((i, chunk) in chunks.withIndex()) {
                onStatus("بنولّد صوت البودكاست… جزء ${i + 1} من ${chunks.size}")
                val bytes = try {
                    speakPodcastChunkWithRetry(chunk, languageCode, apiKey)
                } catch (e: RateLimitException) {
                    if (azureApiKey.isBlank()) throw e
                    onStatus("ليمت Gemini للصوت خلص — بنكمّل مع Azure…")
                    speakPodcastChunkWithAzure(chunk, azureApiKey, azureRegion)
                }
                pcm.write(bytes)
            }
            val wav = File(context.cacheDir, "podcast_${System.currentTimeMillis()}.wav")
            wav.writeBytes(AudioUtils.pcmToWav(pcm.toByteArray(), 24000))
            Result.success(wav)
        } catch (e: RateLimitException) {
            Result.failure(
                IllegalStateException("وصلت للحد المجاني لليوم — استنى شوية وجرّب تاني.")
            )
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
        if (!resp.isSuccessful) {
            throw IllegalStateException("خطأ من خدمة الصوت (${resp.code()})${serviceErrorDetail(resp)}")
        }
        val data = resp.body()?.candidates?.firstOrNull()?.content?.parts
            ?.firstOrNull { it.inlineData != null }?.inlineData?.data
            ?: throw IllegalStateException("الخدمة رجّعت صوت فاضي")
        return Base64.decode(data, Base64.DEFAULT)
    }

    // ---------------- المفاتيح الاحتياطية (Groq للتفريغ + Azure للصوت) ----------------

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
                if (!resp.isSuccessful) {
                    throw IllegalStateException("خطأ من Groq (${resp.code})")
                }
                val parsed = gson.fromJson(resp.body?.string(), GroqTranscriptionResponse::class.java)
                parsed.text?.trim()?.takeIf { it.isNotEmpty() }
                    ?: throw IllegalStateException("Groq رجّع نص فاضي")
            }
        }
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
        return withContext(Dispatchers.IO) {
            http.newCall(request).execute().use { resp ->
                if (resp.code == 429) throw RateLimitException()
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

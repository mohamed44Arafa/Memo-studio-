package com.memo.voicestudio.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * جلب نص (كابشن/ترجمة) فيديو يوتيوب من اللينك مباشرة — من غير تحميل الفيديو
 * ومن غير مفتاح API خاص بالمستخدم.
 *
 * الطريقة الأولى (الأساسية): واجهة YouTube الداخلية (InnerTube) بعميل ANDROID —
 * نفس الطريقة اللي بتستخدمها تطبيقات زي NewPipe: طلب player بيرجع قائمة
 * ترجمات الفيديو (captionTracks) مع رابط مباشر لكل ترجمة، فنجيب النص بصيغة JSON.
 * (المفتاح في الرابط مفتاح عام مضمّن في تطبيق يوتيوب نفسه، مش مفتاح ميمو.)
 *
 * الطريقة التانية (احتياطية): واجهة timedtext بتاعة يوتيوب نفسها
 * (قائمة الترجمات ثم نص الكابشن XML).
 *
 * بنختار العربية أولاً (اليدوية قبل التلقائية)، وبعدها الإنجليزية، وبعدها أي لغة.
 * لو الفيديو مفيهوش كابشن أصلاً بيرمي NoCaptionsException.
 */
object YouTubeCaptions {

    class NoCaptionsException : Exception("الفيديو ده مفيهوش كابشن")

    private val gson = Gson()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    // مفتاح عميل ANDROID العام (مضمّن في تطبيق يوتيوب، وبتستخدمه أدوات مفتوحة المصدر).
    private const val INNERTUBE_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"

    /** يطلع معرّف الفيديو (11 حرف) من أي شكل لينك يوتيوب شائع، أو من المعرّف نفسه. */
    fun extractVideoId(rawUrl: String): String? {
        val url = rawUrl.trim()
        if (url.isEmpty()) return null
        if (Regex("^[A-Za-z0-9_-]{11}$").matches(url)) return url
        val patterns = listOf(
            Regex("[?&]v=([A-Za-z0-9_-]{11})"),
            Regex("youtu\\.be/([A-Za-z0-9_-]{11})"),
            Regex("/shorts/([A-Za-z0-9_-]{11})"),
            Regex("/live/([A-Za-z0-9_-]{11})"),
            Regex("/embed/([A-Za-z0-9_-]{11})"),
            Regex("/v/([A-Za-z0-9_-]{11})")
        )
        for (pattern in patterns) {
            pattern.find(url)?.let { return it.groupValues[1] }
        }
        return null
    }

    /** يجيب نص الكابشن كاملاً لفيديو يوتيوب عام. */
    suspend fun fetchCaptions(videoId: String): String {
        // جرّب InnerTube الأول، ولو فشل أو مفيش ترجمات جرّب timedtext.
        try {
            val text = fetchViaInnerTube(videoId)
            if (text.isNotBlank()) return text
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // وقع على الاحتياطي
        }
        return fetchViaTimedText(videoId)
    }

    /** الطريقة الأساسية: InnerTube player ثم رابط الترجمة بصيغة JSON. */
    private suspend fun fetchViaInnerTube(videoId: String): String {
        val body = """
            {"context":{"client":{"clientName":"ANDROID","clientVersion":"20.10.38","androidSdkVersion":33}},"videoId":"$videoId"}
        """.trimIndent().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?key=$INNERTUBE_KEY")
            .header("User-Agent", "com.google.android.youtube/20.10.38 (Linux; U; Android 13)")
            .post(body)
            .build()
        val response = http.newCall(request).executeCancellable()
        val jsonText = response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            it.body?.string() ?: ""
        }
        val root = gson.fromJson(jsonText, JsonObject::class.java)
        val tracks = root
            .getAsJsonObject("captions")
            ?.getAsJsonObject("playerCaptionsTracklistRenderer")
            ?.getAsJsonArray("captionTracks")
            ?: throw NoCaptionsException()
        data class Track(val baseUrl: String, val lang: String, val auto: Boolean)
        val list = tracks.mapNotNull { el ->
            val obj = el.asJsonObject
            val baseUrl = obj.get("baseUrl")?.asString ?: return@mapNotNull null
            val lang = obj.get("languageCode")?.asString ?: return@mapNotNull null
            val auto = obj.get("kind")?.asString == "asr"
            Track(baseUrl, lang, auto)
        }
        if (list.isEmpty()) throw NoCaptionsException()
        // عربي يدوي أولاً، وبعدها عربي تلقائي، وبعدها إنجليزي، وبعدها أي لغة.
        val track = list.firstOrNull { it.lang == "ar" && !it.auto }
            ?: list.firstOrNull { it.lang == "ar" }
            ?: list.firstOrNull { it.lang.startsWith("ar") }
            ?: list.firstOrNull { it.lang == "en" }
            ?: list.firstOrNull()
            ?: throw NoCaptionsException()
        val sep = if (track.baseUrl.contains("?")) "&" else "?"
        val json = get(track.baseUrl + sep + "fmt=json3")
        return parseJson3(json)
    }

    /** يحوّل JSON الكابشن (fmt=json3) لنص عادي. */
    private fun parseJson3(json: String): String {
        val root = gson.fromJson(json, JsonObject::class.java)
        val events = root.getAsJsonArray("events") ?: return ""
        val parts = mutableListOf<String>()
        for (event in events) {
            val segs = event.asJsonObject.getAsJsonArray("segs") ?: continue
            for (seg in segs) {
                val text = seg.asJsonObject.get("utf8")?.asString?.trim() ?: continue
                if (text.isNotBlank()) parts.add(text)
            }
        }
        return parts.joinToString(" ")
    }

    /** الطريقة الاحتياطية: timedtext (قائمة الترجمات ثم نص XML). */
    private suspend fun fetchViaTimedText(videoId: String): String {
        val listXml = get("https://www.youtube.com/api/timedtext?v=$videoId&type=list")
        val lang = pickLanguage(listXml) ?: throw NoCaptionsException()
        val url = buildString {
            append("https://www.youtube.com/api/timedtext?lang=${lang.first}&v=$videoId")
            if (lang.second == "asr") append("&kind=asr")
        }
        val xml = get(url)
        val text = parseTranscript(xml)
        if (text.isBlank()) throw NoCaptionsException()
        return text
    }

    /** يختار أفضل لغة من قائمة الترجمات: عربي أولاً، وبعدها إنجليزي، وبعدها أي لغة. */
    private fun pickLanguage(listXml: String): Pair<String, String>? {
        val tracks = Regex("<track[^>]*>").findAll(listXml).mapNotNull { match ->
            val tag = match.value
            val code = Regex("lang_code=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
                ?: return@mapNotNull null
            val kind = Regex("kind=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: ""
            code to kind
        }.toList()
        if (tracks.isEmpty()) return null
        return tracks.firstOrNull { it.first == "ar" }
            ?: tracks.firstOrNull { it.first.startsWith("ar") }
            ?: tracks.firstOrNull { it.first == "en" }
            ?: tracks.firstOrNull()
    }

    /** يحوّل XML الكابشن لنص عادي: كل مقطع <text> في سطر، مع فك رموز HTML. */
    private fun parseTranscript(xml: String): String {
        return Regex("<text[^>]*>(.*?)</text>", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .map { decodeEntities(it.groupValues[1].trim()) }
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun decodeEntities(s: String): String {
        var out = s
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        out = Regex("&#(\\d+);").replace(out) {
            it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: ""
        }
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) {
            it.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: ""
        }
        return out
    }

    private suspend fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
            .build()
        val response = http.newCall(request).executeCancellable()
        response.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            return it.body?.string() ?: ""
        }
    }

    /** تنفيذ طلب OkHttp قابل للإلغاء فعلاً (زي اللي في GeminiRepository). */
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
}

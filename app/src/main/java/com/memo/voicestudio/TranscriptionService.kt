package com.memo.voicestudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.memo.voicestudio.data.GeminiRepository
import com.memo.voicestudio.data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * حالة التفريغ الحالية — يقرأها الـ ViewModel عشان الشاشة توري
 * التقدم أو النتيجة لما المستخدم يرجع يفتح التطبيق.
 */
data class TranscriptionState(
    val running: Boolean = false,
    val fileName: String = "",
    val status: String = "",
    val currentSegment: Int = 0,
    val totalSegments: Int = 0,
    val result: String = "",
    val error: String? = null,
    val provider: String = ""
)

/**
 * حالة التفريغ المشتركة بين السيرفس والواجهة، وحفظ آخر نتيجة في ملف
 * (عشان تظهر لو التطبيق اتقفل واتفتح تاني بعد ما التفريغ خلص).
 */
object TranscriptionStore {
    const val LAST_RESULT_FILE = "last-transcription.txt"

    private val _state = MutableStateFlow(TranscriptionState())
    val state: StateFlow<TranscriptionState> = _state.asStateFlow()

    fun update(transform: (TranscriptionState) -> TranscriptionState) {
        _state.value = transform(_state.value)
    }

    fun saveResult(context: Context, text: String) {
        try {
            File(context.cacheDir, LAST_RESULT_FILE).writeText(text)
        } catch (ignored: Exception) {
        }
    }

    fun loadLastResult(context: Context): String? {
        return try {
            val file = File(context.cacheDir, LAST_RESULT_FILE)
            if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * حالة توليد الصوت/البودكاست الحالية — يقرأها الـ ViewModel عشان الشاشة
 * توري التقدم أو الصوت الناتج لما المستخدم يرجع يفتح التطبيق.
 */
data class SpeechState(
    val running: Boolean = false,
    val kind: String = "", // "voice" أو "podcast"
    val status: String = "",
    val phase: String = "", // "script" (تجهيز الحوار) أو "audio" (توليد الصوت)
    val currentChunk: Int = 0,
    val totalChunks: Int = 0,
    val audioPath: String = "",
    val error: String? = null,
    val provider: String = ""
)

/**
 * حالة الصوت المشتركة بين السيرفس والواجهة، وآخر ملف صوت مولّد
 * (بيتنسخ لاسم ثابت عشان يتلاقى للتشغيل بعد ما التطبيق يتقفل ويتفتح تاني).
 */
object SpeechStore {
    const val LAST_AUDIO_FILE = "last-speech.wav"

    private val _state = MutableStateFlow(SpeechState())
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    fun update(transform: (SpeechState) -> SpeechState) {
        _state.value = transform(_state.value)
    }

    fun saveAudio(context: Context, wav: File): File? {
        return try {
            val dest = File(context.cacheDir, LAST_AUDIO_FILE)
            wav.copyTo(dest, overwrite = true)
            dest
        } catch (e: Exception) {
            null
        }
    }

    fun loadLastAudio(context: Context): File? {
        return try {
            val file = File(context.cacheDir, LAST_AUDIO_FILE)
            if (file.exists() && file.length() > 0) file else null
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * المهام الطويلة في الخلفية (فورجراوند سيرفس من نوع dataSync):
 * مهمة واحدة في المرة — تفريغ أو توليد صوت/بودكاست — فتكمّل حتى لو
 * المستخدم خرج من التطبيق أو قفل الشاشة.
 * التفريغ بيشتغل بنفس pipeline الموجود (استخراج الصوت + تقسيم 50 دقيقة +
 * Gemini مع بدائل Groq ثم Deepgram)، والصوت بيشتغل بنفس `synthesize()`
 * (بودكاست أو عادي) مع نفس سلسلة بدائل الصوت.
 *
 * إشعار الفورجراوند سيرفس معفي من صلاحية POST_NOTIFICATIONS في
 * أندرويد 13+، فمش محتاجين نطلبها ولا نضيفها في الـ Manifest.
 * الملف بيتبعت للسيرفس كملف جوه cacheDir (الواجهة بتنسخ الملف المختار
 * هناك قبل ما تبدأ)، فمش محتاجين takePersistableUriPermission —
 * وده كمان مش متاح أصلًا مع GetContent، ده للـ OpenDocument بس.
 * (الاسم التاريخي للكلاس "TranscriptionService" متساب زي ما هو عشان
 * الـ Manifest والـ ViewModel بيشاوروا عليه، رغم إنه بقى يشغّل الصوت كمان.)
 */
class TranscriptionService : Service() {

    companion object {
        private const val CHANNEL_ID = "transcription_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_CANCEL = "com.memo.voicestudio.ACTION_CANCEL_TRANSCRIPTION"
        private const val EXTRA_FILE_PATH = "file_path"
        private const val EXTRA_MIME_TYPE = "mime_type"
        private const val EXTRA_FILE_NAME = "file_name"
        private const val EXTRA_JOB = "job"
        private const val JOB_TRANSCRIPTION = "transcription"
        private const val JOB_SPEECH = "speech"
        private const val EXTRA_TEXT_PATH = "text_path"
        private const val EXTRA_VOICE_NAME = "voice_name"
        private const val EXTRA_LANGUAGE_CODE = "language_code"
        private const val EXTRA_STYLE = "style"
        private const val EXTRA_GENDER = "gender"

        /** يبدأ التفريغ في الخلفية — الملف لازم يكون جوه cacheDir. */
        fun start(context: Context, file: File, mimeType: String) {
            val intent = Intent(context, TranscriptionService::class.java).apply {
                putExtra(EXTRA_JOB, JOB_TRANSCRIPTION)
                putExtra(EXTRA_FILE_PATH, file.absolutePath)
                putExtra(EXTRA_MIME_TYPE, mimeType)
                putExtra(EXTRA_FILE_NAME, file.name)
            }
            context.startForegroundService(intent)
        }

        /** يبدأ توليد الصوت/البودكاست في الخلفية — النص بيتبعت كملف جوه cacheDir. */
        fun startSpeech(
            context: Context,
            textFile: File,
            voiceName: String,
            languageCode: String,
            style: String,
            gender: String
        ) {
            val intent = Intent(context, TranscriptionService::class.java).apply {
                putExtra(EXTRA_JOB, JOB_SPEECH)
                putExtra(EXTRA_TEXT_PATH, textFile.absolutePath)
                putExtra(EXTRA_VOICE_NAME, voiceName)
                putExtra(EXTRA_LANGUAGE_CODE, languageCode)
                putExtra(EXTRA_STYLE, style)
                putExtra(EXTRA_GENDER, gender)
            }
            context.startForegroundService(intent)
        }

        /** يوقف المهمة الشغالة (تفريغ أو صوت) من داخل التطبيق — نفس زر "إلغاء" في الإشعار. */
        fun cancel(context: Context) {
            val intent = Intent(context, TranscriptionService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transcribeJob: Job? = null
    private var speechJob: Job? = null
    private var currentJobKind = JOB_TRANSCRIPTION
    private var currentSpeechKind = "voice"
    private var currentFileName = ""
    private var currentStatus = "بنبدأ…"
    private var currentSegment = 0
    private var totalSegments = 0
    private var currentPhase = ""
    private var currentChunk = 0
    private var totalChunks = 0
    private var isFinished = false
    private var finishedTitle = ""
    private var finishedText = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            // إلغاء بس لو فيه مهمة شغالة فعلاً (ممكن الزرار يتداس لحظة ما المهمة تخلص).
            if (transcribeJob?.isActive == true || speechJob?.isActive == true) {
                cancelCurrentJob()
            } else {
                // مفيش مهمة شغالة فعلاً — ممكن تكون خلصت أو وقعت من غير ما تحدّث الحالة،
                // فبنرجّع الحالة بنفسنا عشان الشاشة متفضلش تلف على الفاضي.
                if (currentJobKind == JOB_SPEECH) {
                    SpeechStore.update {
                        SpeechState(running = false, kind = currentSpeechKind, status = "اتلغى توليد الصوت")
                    }
                } else {
                    TranscriptionStore.update {
                        TranscriptionState(running = false, fileName = currentFileName, status = "اتلغى التفريغ")
                    }
                }
                stopSelf()
            }
            return START_NOT_STICKY
        }
        // مهمة واحدة في المرة (تفريغ أو صوت) — لو مهمة شغالة، ارفض الجديدة
        // (الواجهة بتمنع ده أصلاً وبتبيّن رسالة "في مهمة شغالة دلوقتي").
        if (transcribeJob?.isActive == true || speechJob?.isActive == true) return START_NOT_STICKY

        val job = intent?.getStringExtra(EXTRA_JOB) ?: JOB_TRANSCRIPTION
        if (job == JOB_SPEECH) {
            val path = intent?.getStringExtra(EXTRA_TEXT_PATH) ?: return START_NOT_STICKY
            val voiceName = intent.getStringExtra(EXTRA_VOICE_NAME) ?: "Kore"
            val languageCode = intent.getStringExtra(EXTRA_LANGUAGE_CODE) ?: "ar-EG"
            val style = intent.getStringExtra(EXTRA_STYLE) ?: "normal"
            val gender = intent.getStringExtra(EXTRA_GENDER) ?: "female"
            currentJobKind = JOB_SPEECH
            currentSpeechKind = if (style == "podcast") "podcast" else "voice"
            currentStatus = "بنبدأ…"
            currentPhase = ""
            currentChunk = 0
            totalChunks = 0
            isFinished = false

            // لازم startForeground فورًا (قبل أي شغل شبكة) وإلا النظام يوقف السيرفس.
            startForeground(NOTIFICATION_ID, buildNotification())
            SpeechStore.update {
                SpeechState(running = true, kind = currentSpeechKind, status = "بنبدأ…")
            }
            speechJob = serviceScope.launch {
                runSpeech(File(path), voiceName, languageCode, style, gender)
            }
        } else {
            val path = intent?.getStringExtra(EXTRA_FILE_PATH) ?: return START_NOT_STICKY
            val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "audio/mp4"
            currentJobKind = JOB_TRANSCRIPTION
            currentFileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: File(path).name
            currentStatus = "بنبدأ…"
            currentSegment = 0
            totalSegments = 0
            isFinished = false

            // لازم startForeground فورًا (قبل أي شغل شبكة) وإلا النظام يوقف السيرفس.
            startForeground(NOTIFICATION_ID, buildNotification())
            TranscriptionStore.update {
                TranscriptionState(running = true, fileName = currentFileName, status = "بنبدأ…")
            }
            transcribeJob = serviceScope.launch {
                runTranscription(File(path), mimeType)
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runTranscription(source: File, mimeType: String) {
        // المفاتيح بتتقرأ من الإعدادات المشفّرة هنا، مش بتتبعت في الـ Intent.
        val settings = SettingsStore(applicationContext)
        val repo = GeminiRepository(applicationContext)
        try {
            val result = repo.transcribe(
                source, mimeType, settings.apiKey.trim(),
                onStatus = { status ->
                    currentStatus = status
                    TranscriptionStore.update { it.copy(status = status) }
                    updateNotification()
                },
                groqApiKey = settings.groqApiKey.trim(),
                deepgramApiKey = settings.deepgramApiKey.trim(),
                // الاختيار اليدوي من الإعدادات يتقرأ هنا، فبيوصل للسيرفس تلقائيًا.
                transcriptionProvider = settings.transcriptionProvider,
                onProgress = { current, total ->
                    currentSegment = current
                    totalSegments = total
                    TranscriptionStore.update { it.copy(currentSegment = current, totalSegments = total) }
                    updateNotification()
                },
                onProvider = { p ->
                    TranscriptionStore.update { it.copy(provider = p) }
                }
            )
            result
                .onSuccess { text ->
                    TranscriptionStore.saveResult(applicationContext, text)
                    TranscriptionStore.update {
                        it.copy(running = false, status = "خلص التفريغ ✔", result = text)
                    }
                    finishWithNotification("اكتمل التفريغ ✔", "اضغط لعرض النص")
                    delay(3000)
                }
                .onFailure { error ->
                    val msg = error.message ?: "حصل خطأ"
                    TranscriptionStore.update { it.copy(running = false, status = msg, error = msg) }
                    finishWithNotification("التفريغ وقف", msg)
                    delay(3000)
                }
        } catch (e: CancellationException) {
            // الإلغاء من زر "إلغاء" — من غير إشعار، السيرفس بيقف على طول.
            TranscriptionStore.update {
                TranscriptionState(running = false, fileName = currentFileName, status = "اتلغى التفريغ")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // الإشعار النهائي اتعرض 3 ثواني — السيرفس بيقف والإشعار بيختفي معاه.
        stopSelf()
    }

    /** توليد الصوت/البودكاست في الخلفية بنفس `synthesize()` (نفس سلسلة بدائل الصوت). */
    private suspend fun runSpeech(inputFile: File, voiceName: String, languageCode: String, style: String, gender: String) {
        // المفاتيح واختيار المزود اليدوي بيتقروا من الإعدادات هنا، مش بيتبعتوا في الـ Intent.
        val settings = SettingsStore(applicationContext)
        val repo = GeminiRepository(applicationContext)
        val kind = currentSpeechKind
        val text = try {
            inputFile.readText()
        } catch (e: Exception) {
            ""
        }
        try {
            val result = repo.synthesize(
                text, voiceName, languageCode, style, settings.apiKey.trim(),
                onStatus = { status ->
                    currentStatus = status
                    SpeechStore.update { it.copy(status = status) }
                    updateNotification()
                },
                azureApiKey = settings.azureApiKey.trim(),
                azureRegion = settings.azureRegion.trim(),
                elevenLabsApiKey = settings.elevenLabsApiKey.trim(),
                cartesiaApiKey = settings.cartesiaApiKey.trim(),
                gender = gender,
                voiceProvider = settings.voiceProvider,
                onProvider = { p ->
                    SpeechStore.update { it.copy(provider = p) }
                },
                onProgress = { phase, current, total ->
                    currentPhase = phase
                    currentChunk = current
                    totalChunks = total
                    SpeechStore.update { it.copy(phase = phase, currentChunk = current, totalChunks = total) }
                    updateNotification()
                }
            )
            result
                .onSuccess { wav ->
                    val saved = SpeechStore.saveAudio(applicationContext, wav)
                    val doneStatus = if (kind == "podcast") "خلص البودكاست ✔" else "خلص الصوت ✔"
                    SpeechStore.update {
                        it.copy(
                            running = false, status = doneStatus,
                            audioPath = saved?.absolutePath ?: wav.absolutePath
                        )
                    }
                    finishWithNotification(
                        if (kind == "podcast") "اكتمل البودكاست ✔" else "اكتمل الصوت ✔",
                        "اضغط لتشغيل الصوت"
                    )
                    delay(3000)
                }
                .onFailure { error ->
                    val msg = error.message ?: "حصل خطأ"
                    SpeechStore.update { it.copy(running = false, status = msg, error = msg) }
                    finishWithNotification("توليد الصوت وقف", msg)
                    delay(3000)
                }
        } catch (e: CancellationException) {
            // الإلغاء من زر "إلغاء" — من غير إشعار، السيرفس بيقف على طول.
            SpeechStore.update {
                SpeechState(running = false, kind = kind, status = "اتلغى توليد الصوت")
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        // الإشعار النهائي اتعرض 3 ثواني — السيرفس بيقف والإشعار بيختفي معاه.
        stopSelf()
    }

    /** يوقف المهمة الشغالة (تفريغ أو صوت) والسيرفس (من زر "إلغاء" في الإشعار). */
    private fun cancelCurrentJob() {
        transcribeJob?.cancel()
        speechJob?.cancel()
        if (currentJobKind == JOB_SPEECH) {
            SpeechStore.update {
                SpeechState(running = false, kind = currentSpeechKind, status = "اتلغى توليد الصوت")
            }
        } else {
            TranscriptionStore.update {
                TranscriptionState(running = false, fileName = currentFileName, status = "اتلغى التفريغ")
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishWithNotification(title: String, text: String) {
        isFinished = true
        finishedTitle = title
        finishedText = text
        updateNotification()
    }

    private fun updateNotification() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openAppIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openAppIntent)
            .setOnlyAlertOnce(true)
        if (isFinished) {
            builder
                .setContentTitle(finishedTitle)
                .setContentText(finishedText)
                .setOngoing(false)
                .setProgress(0, 0, false)
        } else {
            if (currentJobKind == JOB_SPEECH) {
                builder
                    .setContentTitle(if (currentSpeechKind == "podcast") "جارٍ توليد البودكاست…" else "جارٍ توليد الصوت…")
                    .setContentText(speechProgressText())
                    .setOngoing(true)
                if (currentPhase == "audio" && totalChunks > 1) {
                    // شريط التقدم حسب المقاطع اللي خلصت (المقطع الحالي لسه شغال).
                    builder.setProgress(totalChunks, (currentChunk - 1).coerceAtLeast(0), false)
                } else {
                    // لسه بنجهّز الحوار، أو الصوت مقطع واحد — تقدّم غير محدد.
                    builder.setProgress(0, 0, true)
                }
            } else {
                builder
                    .setContentTitle("جارٍ تفريغ: $currentFileName")
                    .setContentText(notificationProgressText())
                    .setOngoing(true)
                if (totalSegments > 1) {
                    // شريط التقدم حسب الأجزاء اللي خلصت (الجزء الحالي لسه شغال).
                    builder.setProgress(totalSegments, (currentSegment - 1).coerceAtLeast(0), false)
                } else {
                    // لسه بنستخرج الصوت أو بنقسّم، أو الملف جزء واحد — تقدّم غير محدد.
                    builder.setProgress(0, 0, true)
                }
            }
            val cancelIntent = PendingIntent.getService(
                this, 0,
                Intent(this, TranscriptionService::class.java).apply { action = ACTION_CANCEL },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(0, "إلغاء", cancelIntent)
        }
        return builder.build()
    }

    /** نص التقدم: رسالة الحالة الحالية + النسبة المئوية لو الملف متقسّم أجزاء. */
    private fun notificationProgressText(): String {
        if (totalSegments > 1 && currentSegment > 0) {
            val percent = (currentSegment - 1) * 100 / totalSegments
            return "$currentStatus — $percent%"
        }
        return currentStatus
    }

    /** نص تقدّم الصوت/البودكاست: مرحلة تجهيز الحوار، أو "المقطع X من Y — Z%". */
    private fun speechProgressText(): String {
        if (currentPhase == "script") return "تجهيز الحوار…"
        if (totalChunks > 0 && currentChunk > 0) {
            val percent = (currentChunk - 1) * 100 / totalChunks
            return "المقطع $currentChunk من $totalChunks — $percent%"
        }
        return currentStatus
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "المهام في الخلفية",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "إشعارات تقدم التفريغ وتوليد الصوت أثناء عمل التطبيق في الخلفية"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        transcribeJob?.cancel()
        speechJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}

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
    val error: String? = null
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
 * التفريغ في الخلفية (فورجراوند سيرفس من نوع dataSync):
 * بيشتغل بنفس pipeline التفريغ الموجود (استخراج الصوت + تقسيم 50 دقيقة +
 * Gemini مع بدائل Groq ثم Deepgram)، فالتفريغ يكمّل حتى لو المستخدم
 * خرج من التطبيق أو قفل الشاشة.
 *
 * إشعار الفورجراوند سيرفس معفي من صلاحية POST_NOTIFICATIONS في
 * أندرويد 13+، فمش محتاجين نطلبها ولا نضيفها في الـ Manifest.
 * الملف بيتبعت للسيرفس كملف جوه cacheDir (الواجهة بتنسخ الملف المختار
 * هناك قبل ما تبدأ)، فمش محتاجين takePersistableUriPermission —
 * وده كمان مش متاح أصلًا مع GetContent، ده للـ OpenDocument بس.
 */
class TranscriptionService : Service() {

    companion object {
        private const val CHANNEL_ID = "transcription_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_CANCEL = "com.memo.voicestudio.ACTION_CANCEL_TRANSCRIPTION"
        private const val EXTRA_FILE_PATH = "file_path"
        private const val EXTRA_MIME_TYPE = "mime_type"
        private const val EXTRA_FILE_NAME = "file_name"

        /** يبدأ التفريغ في الخلفية — الملف لازم يكون جوه cacheDir. */
        fun start(context: Context, file: File, mimeType: String) {
            val intent = Intent(context, TranscriptionService::class.java).apply {
                putExtra(EXTRA_FILE_PATH, file.absolutePath)
                putExtra(EXTRA_MIME_TYPE, mimeType)
                putExtra(EXTRA_FILE_NAME, file.name)
            }
            context.startForegroundService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transcribeJob: Job? = null
    private var currentFileName = ""
    private var currentStatus = "بنبدأ…"
    private var currentSegment = 0
    private var totalSegments = 0
    private var isFinished = false
    private var finishedTitle = ""
    private var finishedText = ""

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelTranscription()
            return START_NOT_STICKY
        }
        // لو تفريغ شغال أصلاً، تجاهل الأمر الجديد (الواجهة بتمنع ده أصلاً).
        if (transcribeJob?.isActive == true) return START_NOT_STICKY

        val path = intent?.getStringExtra(EXTRA_FILE_PATH) ?: return START_NOT_STICKY
        val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE) ?: "audio/mp4"
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
                onProgress = { current, total ->
                    currentSegment = current
                    totalSegments = total
                    TranscriptionStore.update { it.copy(currentSegment = current, totalSegments = total) }
                    updateNotification()
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

    /** يوقف التفريغ والسيرفس (من زر "إلغاء" في الإشعار). */
    private fun cancelTranscription() {
        transcribeJob?.cancel()
        TranscriptionStore.update {
            TranscriptionState(running = false, fileName = currentFileName, status = "اتلغى التفريغ")
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

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "التفريغ في الخلفية",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "إشعارات تقدم تفريغ الصوت والفيديو أثناء عمل التطبيق في الخلفية"
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        transcribeJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}

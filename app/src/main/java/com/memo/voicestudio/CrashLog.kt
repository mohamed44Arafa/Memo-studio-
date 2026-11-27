package com.memo.voicestudio

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * تقرير انهيار (crash) للتطبيق: بيتكتب في مجلد التنزيلات باسم
 * memo-voice-studio-crash.txt (بنفس طريقة حفظ الملفات في MainViewModel)،
 * وبيتعرض كمان على شاشة الخطأ عند الفتح عشان المستخدم ينسخه ويبعته.
 * كل الكتابة best-effort — أي فشل فيها يتجاهل بهدوء ويرجع false.
 */
object CrashLog {

    const val FILE_NAME = "memo-voice-studio-crash.txt"

    /** نص التقرير الكامل: الوقت + نوع الخطأ ورسالته + الـ stack trace كامل. */
    fun formatCrash(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return StringBuilder()
            .append("استوديو صوت ميمو — تقرير انهيار\n")
            .append("الوقت: ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .append("\n")
            .append("نوع الخطأ: ").append(t.javaClass.name).append("\n")
            .append("الرسالة: ").append(t.message ?: "").append("\n\n")
            .append(sw.toString())
            .toString()
    }

    /** يكتب التقرير في التنزيلات؛ يرجع true لو اتكتب، وfalse لو فشل لأي سبب. */
    fun writeCrashReport(context: Context, t: Throwable): Boolean {
        return try {
            val bytes = formatCrash(t).toByteArray(Charsets.UTF_8)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = context.contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return false
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: return false
                true
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                if (!dir.exists()) dir.mkdirs()
                File(dir, FILE_NAME).writeBytes(bytes)
                true
            }
        } catch (ignored: Exception) {
            false
        }
    }
}

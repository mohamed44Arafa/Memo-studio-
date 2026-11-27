package com.memo.voicestudio.data

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.nio.ByteBuffer

/**
 * أدوات الصوت المحلية: مدة الملف، استخراج الصوت من فيديو،
 * تقسيم ملف صوتي طويل لأجزاء، وتحويل PCM الخام إلى WAV.
 */
object AudioUtils {

    fun getDurationMs(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
            }
        }
    }

    fun mimeTypeFor(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "mp4" -> "video/mp4"
            "mov" -> "video/quicktime"
            "mkv" -> "video/x-matroska"
            "webm" -> "video/webm"
            "3gp" -> "video/3gpp"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg", "opus" -> "audio/ogg"
            "flac" -> "audio/flac"
            "aac" -> "audio/aac"
            "txt" -> "text/plain"
            "pdf" -> "application/pdf"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            else -> "application/octet-stream"
        }
    }

    /**
     * يكشف نوع الملف من محتواه (مش من اسمه) — للملفات اللي اسمها غريب من غير امتداد معروف
     * (زي mp4.339206). يرجع "video/mp4" لو فيه تراك فيديو، أو نوع الصوت حسب تراك الصوت،
     * أو null لو مش قادر يحدد.
     */
    fun probeMimeType(file: File): String? {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(file.absolutePath)
            var hasVideo = false
            var audioMime: String? = null
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) hasVideo = true
                if (mime.startsWith("audio/") && audioMime == null) audioMime = mime
            }
            extractor.release()
            when {
                hasVideo -> "video/mp4"
                audioMime == "audio/mp4a-latm" -> "audio/mp4"
                audioMime == "audio/mpeg" -> "audio/mpeg"
                audioMime == "audio/vorbis" || audioMime == "audio/opus" -> "audio/ogg"
                audioMime == "audio/flac" -> "audio/flac"
                audioMime == "audio/raw" -> "audio/wav"
                audioMime != null -> "audio/mp4"
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun displayNameOf(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun uriToTempFile(context: Context, uri: Uri, fileName: String): File? {
        return try {
            val out = File(context.cacheDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    /**
     * يستخرج المسار الصوتي من ملف فيديو إلى ملف m4a.
     * يرجع null لو الملف أصلًا صوتي أو لو الاستخراج فشل.
     */
    fun extractAudioTrack(input: File, outDir: File): File? {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(input.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                extractor.release()
                return null
            }
            val outFile = File(outDir, "audio_${System.currentTimeMillis()}.m4a")
            copyTrackSamples(extractor, trackIndex, format, outFile, 0L, Long.MAX_VALUE)
            extractor.release()
            outFile
        } catch (e: Exception) {
            null
        }
    }

    /**
     * يقسّم ملفًا صوتيًا طويلًا إلى أجزاء (كل جزء حتى maxDurationMs).
     * يرجع null لو التقسيم فشل.
     */
    fun splitAudioTrack(input: File, maxDurationMs: Long, outDir: File): List<File>? {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(input.absolutePath)
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = f
                    break
                }
            }
            if (trackIndex < 0 || format == null) {
                extractor.release()
                return null
            }
            val durationUs = try {
                format.getLong(MediaFormat.KEY_DURATION)
            } catch (e: Exception) {
                0L
            }
            if (durationUs <= 0L) {
                extractor.release()
                return null
            }
            val maxUs = maxDurationMs * 1000L
            val segments = mutableListOf<File>()
            var startUs = 0L
            var index = 0
            while (startUs < durationUs) {
                val endUs = minOf(startUs + maxUs, durationUs)
                val outFile = File(outDir, "part_${index}_${System.currentTimeMillis()}.m4a")
                copyTrackSamples(extractor, trackIndex, format, outFile, startUs, endUs)
                segments.add(outFile)
                startUs = endUs
                index++
            }
            extractor.release()
            segments
        } catch (e: Exception) {
            null
        }
    }

    /** ينسخ عينات مسار صوتي من startUs إلى endUs في ملف m4a جديد. */
    private fun copyTrackSamples(
        extractor: MediaExtractor,
        trackIndex: Int,
        format: MediaFormat,
        outFile: File,
        startUs: Long,
        endUs: Long
    ) {
        extractor.selectTrack(trackIndex)
        extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val outTrack = muxer.addTrack(format)
        muxer.start()
        val buffer = ByteBuffer.allocate(1024 * 512)
        try {
            while (true) {
                val sampleTime = extractor.sampleTime
                if (sampleTime < 0 || sampleTime >= endUs) break
                if (sampleTime < startUs) {
                    extractor.advance()
                    continue
                }
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val info = MediaCodec.BufferInfo().apply {
                    offset = 0
                    this.size = size
                    presentationTimeUs = sampleTime - startUs
                    flags = extractor.sampleFlags
                }
                muxer.writeSampleData(outTrack, buffer, info)
                extractor.advance()
            }
        } finally {
            try {
                muxer.stop()
            } catch (ignored: Exception) {
            }
            muxer.release()
            extractor.unselectTrack(trackIndex)
        }
    }

    /** يحوّل صوت PCM خام (16-bit، مونو) إلى ملف WAV كامل بالهيدر. */
    fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val totalDataLen = pcm.size + 36
        val byteRate = sampleRate * 2
        val header = ByteArray(44)
        fun writeInt(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = (value shr 8 and 0xff).toByte()
            header[offset + 2] = (value shr 16 and 0xff).toByte()
            header[offset + 3] = (value shr 24 and 0xff).toByte()
        }
        fun writeShort(offset: Int, value: Int) {
            header[offset] = (value and 0xff).toByte()
            header[offset + 1] = (value shr 8 and 0xff).toByte()
        }
        "RIFF".toByteArray().copyInto(header, 0)
        writeInt(4, totalDataLen)
        "WAVE".toByteArray().copyInto(header, 8)
        "fmt ".toByteArray().copyInto(header, 12)
        writeInt(16, 16)
        writeShort(20, 1) // PCM
        writeShort(22, 1) // mono
        writeInt(24, sampleRate)
        writeInt(28, byteRate)
        writeShort(32, 2) // block align
        writeShort(34, 16) // bits per sample
        "data".toByteArray().copyInto(header, 36)
        writeInt(40, pcm.size)
        return header + pcm
    }
}

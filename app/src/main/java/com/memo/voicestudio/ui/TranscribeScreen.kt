package com.memo.voicestudio.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memo.voicestudio.data.AudioUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TranscribeScreen(viewModel: MainViewModel, onGoToSpeak: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()

    var pickError by remember { mutableStateOf("") }

    fun handlePicked(uri: Uri?, defaultName: String) {
        if (uri == null) return
        pickError = ""
        scope.launch(Dispatchers.IO) {
            val name = AudioUtils.displayNameOf(context, uri) ?: defaultName
            val file = AudioUtils.uriToTempFile(context, uri, name)
            if (file == null) {
                withContext(Dispatchers.Main) {
                    pickError = "مش قادر أفتح الملف ده — جرّب ملف تاني"
                }
                return@launch
            }
            val mime = AudioUtils.mimeTypeFor(name)
            withContext(Dispatchers.Main) {
                viewModel.transcribe(file, mime)
            }
        }
    }

    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> handlePicked(uri, "video.mp4") }

    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> handlePicked(uri, "audio.mp3") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(20.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Text("تفريغ الفيديو والصوت", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "اختار فيديو أو ملف صوتي، والتطبيق يحوّله لنص عربي دقيق باستخدام Gemini. الملف بيترفع على سيرفر Google للتفريغ بس.",
            fontSize = 16.sp
        )
        Spacer(Modifier.height(20.dp))

        Button(
            onClick = { pickVideo.launch("video/*") },
            enabled = !viewModel.isTranscribing,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("🎥  اختار فيديو", fontSize = 18.sp) }

        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { pickAudio.launch("audio/*") },
            enabled = !viewModel.isTranscribing,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("🎵  اختار ملف صوتي", fontSize = 18.sp) }

        if (viewModel.transFileName.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text("الملف: ${viewModel.transFileName}", fontSize = 16.sp)
        }

        if (pickError.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(pickError, fontSize = 16.sp)
        }

        if (viewModel.isTranscribing) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Spacer(Modifier.width(12.dp))
                Text("جارٍ التفريغ…", fontSize = 16.sp)
            }
            if (viewModel.transSegmentTotal > 1) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = {
                        (viewModel.transSegmentCurrent - 1).coerceAtLeast(0).toFloat() /
                            viewModel.transSegmentTotal
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "الجزء ${viewModel.transSegmentCurrent} من ${viewModel.transSegmentTotal}",
                    fontSize = 16.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "التفريغ شغال في الخلفية — تقدر تخرج من التطبيق وهيكمّل، وهتلاقي إشعار بالتقدم.",
                fontSize = 16.sp
            )
        }

        if (viewModel.transStatus.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(viewModel.transStatus, fontSize = 16.sp)
        }

        if (viewModel.transResult.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text("النص:", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(viewModel.transResult, fontSize = 16.sp)
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(
                            ClipData.newPlainText("تفريغ", viewModel.transResult)
                        )
                    },
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text("نسخ النص", fontSize = 16.sp) }
                OutlinedButton(
                    onClick = {
                        val msg = viewModel.saveTextFile(
                            context,
                            viewModel.transResult,
                            "transcription_${System.currentTimeMillis()}.txt"
                        )
                        viewModel.setStatusMessage(msg)
                    },
                    modifier = Modifier.weight(1f).height(52.dp)
                ) { Text("حفظ TXT", fontSize = 16.sp) }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    viewModel.useTranscriptionAsSpeakText()
                    onGoToSpeak()
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("ابعته للنطق 🔊", fontSize = 18.sp) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

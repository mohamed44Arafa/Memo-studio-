package com.memo.voicestudio.ui

import android.content.Context
import android.graphics.BitmapFactory
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.memo.voicestudio.data.AudioUtils
import com.memo.voicestudio.data.DocxText
import com.memo.voicestudio.data.GeminiRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SpeakScreen(viewModel: MainViewModel, onGoToSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var voiceMenuExpanded by remember { mutableStateOf(false) }

    val photoFile = remember {
        File(context.cacheDir, "photo_${System.currentTimeMillis()}.jpg")
    }
    val photoUri = remember {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            photoFile
        )
    }

    fun handleImageUri(uri: Uri?) {
        if (uri == null) return
        scope.launch(Dispatchers.IO) {
            val bitmap = context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
            withContext(Dispatchers.Main) {
                if (bitmap != null) {
                    viewModel.markLoadedFile(AudioUtils.displayNameOf(context, uri) ?: "صورة", "image")
                    viewModel.extractFromImage(bitmap)
                }
                else viewModel.setSpeakStatusMessage("مش قادر أفتح الصورة دي")
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> handleImageUri(uri) }

    val takePhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) {
            scope.launch(Dispatchers.IO) {
                val bitmap = BitmapFactory.decodeFile(photoFile.absolutePath)
                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        viewModel.markLoadedFile(photoFile.name, "camera")
                        viewModel.extractFromImage(bitmap)
                    }
                }
            }
        }
    }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val name = AudioUtils.displayNameOf(context, uri) ?: "file"
            val mime = AudioUtils.mimeTypeFor(name)
            when {
                mime == "application/pdf" -> {
                    val file = AudioUtils.uriToTempFile(context, uri, name)
                    withContext(Dispatchers.Main) {
                        if (file != null) {
                            viewModel.markLoadedFile(name, "file")
                            viewModel.extractFromPdf(file)
                        }
                    }
                }
                mime == "text/plain" -> {
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes().toString(Charsets.UTF_8)
                    } ?: ""
                    withContext(Dispatchers.Main) {
                        viewModel.speakText = text
                        viewModel.markLoadedFile(name, "file")
                    }
                }
                mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> {
                    // ملف وورد: النص بيستخرج محليًا وبسرعة من غير أي طلبات شبكة.
                    val text = context.contentResolver.openInputStream(uri)?.use { input ->
                        DocxText.extractText(input)
                    } ?: ""
                    withContext(Dispatchers.Main) {
                        if (text.isBlank()) {
                            viewModel.setSpeakStatusMessage("مش قادر أقرأ نص من ملف الوورد ده")
                        } else {
                            viewModel.speakText = text
                            viewModel.markLoadedFile(name, "file")
                        }
                    }
                }
                else -> {
                    withContext(Dispatchers.Main) {
                        viewModel.setSpeakStatusMessage("نوع الملف ده مش مدعوم — اختار TXT أو PDF أو Word")
                    }
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(20.dp)
    ) {
        Text("النص ← صوت", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "النص يتنطق بصوت عربي طبيعي من Google. تقدر تكتب نص، أو تجيبه من ملف أو صورة أو من آخر تفريغ.",
            fontSize = 16.sp
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = viewModel.speakText,
            onValueChange = { viewModel.speakText = it },
            label = { Text("اكتب أو الصق النص هنا") },
            modifier = Modifier.fillMaxWidth().height(180.dp)
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { pickFile.launch("*/*") },
                modifier = Modifier.weight(1f).height(52.dp)
            ) { Text("من ملف TXT/PDF/Word", fontSize = 14.sp) }
            OutlinedButton(
                onClick = { pickImage.launch("image/*") },
                modifier = Modifier.weight(1f).height(52.dp)
            ) { Text("من صورة", fontSize = 14.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { takePhoto.launch(photoUri) },
                modifier = Modifier.weight(1f).height(52.dp)
            ) { Text("من الكاميرا", fontSize = 14.sp) }
            OutlinedButton(
                onClick = { viewModel.useTranscriptionAsSpeakText() },
                modifier = Modifier.weight(1f).height(52.dp)
            ) { Text("من آخر تفريغ", fontSize = 14.sp) }
        }

        if (viewModel.loadedFileName != null) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "الملف: ${viewModel.loadedFileName}",
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(
                    onClick = {
                        when (viewModel.loadedFileSource) {
                            "image" -> pickImage.launch("image/*")
                            "camera" -> takePhoto.launch(photoUri)
                            else -> pickFile.launch("*/*")
                        }
                    },
                    enabled = !viewModel.isSpeaking,
                    modifier = Modifier.height(48.dp)
                ) { Text("تغيير الملف ↻", fontSize = 14.sp) }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text("الصوت", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = viewModel.gender == "female",
                onClick = { viewModel.saveGender("female") },
                label = { Text("ست 👩", fontSize = 16.sp) }
            )
            FilterChip(
                selected = viewModel.gender == "male",
                onClick = { viewModel.saveGender("male") },
                label = { Text("راجل 👨", fontSize = 16.sp) }
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = viewModel.language == "egyptian",
                onClick = { viewModel.saveLanguage("egyptian") },
                label = { Text("مصري 🇪🇬", fontSize = 16.sp) }
            )
            FilterChip(
                selected = viewModel.language == "fusha",
                onClick = { viewModel.saveLanguage("fusha") },
                label = { Text("فصحى", fontSize = 16.sp) }
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = viewModel.style == "normal",
                onClick = { viewModel.saveStyle("normal") },
                label = { Text("قراءة عادية", fontSize = 16.sp) }
            )
            FilterChip(
                selected = viewModel.style == "poetry",
                onClick = { viewModel.saveStyle("poetry") },
                label = { Text("إلقاء شعر", fontSize = 16.sp) }
            )
            FilterChip(
                selected = viewModel.style == "story",
                onClick = { viewModel.saveStyle("story") },
                label = { Text("سرد قصص", fontSize = 16.sp) }
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = viewModel.style == "podcast",
                onClick = { viewModel.saveStyle("podcast") },
                label = { Text("🎙️ بودكاست", fontSize = 16.sp) }
            )
        }
        if (viewModel.style == "podcast") {
            Spacer(Modifier.height(4.dp))
            Text("حوار بين صوتين ثابتين: مقدمة وضيف — التطبيق بيحوّل النص لحوار الأول وبعدين ينطقه.", fontSize = 14.sp)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { voiceMenuExpanded = true },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("الصوت المستخدم: ${viewModel.voiceName}", fontSize = 16.sp) }
        DropdownMenu(
            expanded = voiceMenuExpanded,
            onDismissRequest = { voiceMenuExpanded = false }
        ) {
            for ((voice, desc) in GeminiRepository.ALL_VOICES) {
                DropdownMenuItem(
                    text = { Text("$voice — $desc", fontSize = 16.sp) },
                    onClick = {
                        viewModel.saveVoiceName(voice)
                        voiceMenuExpanded = false
                    }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "الصوت اللي هيتستخدم: ${viewModel.voiceLabel()}",
            fontSize = 15.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "المزود: ${viewModel.voiceProviderLabel()}",
            fontSize = 15.sp
        )

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { viewModel.speak() },
            enabled = !viewModel.isSpeaking && !viewModel.isExtracting,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("🔊  ولّد الصوت", fontSize = 18.sp) }

        if (viewModel.isSpeaking || viewModel.isExtracting) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
            }
            if (viewModel.isSpeaking) {
                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        viewModel.speakPhase == "script" -> "تجهيز الحوار…"
                        viewModel.speakStatus.isNotBlank() -> viewModel.speakStatus
                        // (Build 41: لو حالة الصوت فاضية لأي سبب والشاشة لسه شغالة،
                        // يظهر كلام ثابت بدل ما الشاشة تبان واقفة من غير أي كلام.)
                        else -> "بنولّد الصوت…"
                    },
                    fontSize = 16.sp
                )
                if (viewModel.speakChunkTotal > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "المقطع ${viewModel.speakChunkCurrent} من ${viewModel.speakChunkTotal}",
                        fontSize = 14.sp
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { viewModel.cancelCurrentJob() },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("إيقاف ✕", fontSize = 16.sp) }
        }

        if (!viewModel.isSpeaking && !viewModel.isExtracting && viewModel.hasGeneratedSpeech && viewModel.speakText.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { viewModel.speak() },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("إعادة التوليد ↻", fontSize = 16.sp) }
        }

        if (!viewModel.isSpeaking && !viewModel.isExtracting && viewModel.speakStatus.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            Text(viewModel.speakStatus, fontSize = 16.sp)
        }

        if (viewModel.audioFile != null) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (viewModel.isPlaying) viewModel.stopAudio()
                        else viewModel.playAudio()
                    },
                    modifier = Modifier.weight(1f).height(56.dp)
                ) {
                    Text(if (viewModel.isPlaying) "إيقاف ⏹" else "تشغيل ▶", fontSize = 18.sp)
                }
                OutlinedButton(
                    onClick = {
                        viewModel.setSpeakStatusMessage(viewModel.downloadAudio(context))
                    },
                    modifier = Modifier.weight(1f).height(56.dp)
                ) { Text("حمّل الصوت ⬇", fontSize = 16.sp) }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { viewModel.toggleSaveAudio() },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (viewModel.isAudioSaved) "✔ محفوظ في التطبيق" else "💾 حفظ في التطبيق", fontSize = 16.sp) }
        }

        if (viewModel.apiKey.isBlank()) {
            Spacer(Modifier.height(20.dp))
            Text(
                "⚠ لسه مدخلتش مفتاح Gemini — النطق مش هيشتغل من غيره.",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onGoToSettings,
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("روح للإعدادات", fontSize = 16.sp) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

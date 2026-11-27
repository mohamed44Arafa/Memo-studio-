package com.memo.voicestudio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.memo.voicestudio.ui.MainViewModel
import com.memo.voicestudio.ui.SettingsScreen
import com.memo.voicestudio.ui.SpeakScreen
import com.memo.voicestudio.ui.TranscribeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ثبّت معالج الانهيارات قبل أي حاجة تانية — يكتب تقرير الانهيار
        // في التنزيلات قبل ما العملية تموت.
        installCrashHandler()
        try {
            setContent {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    VoiceStudioTheme {
                        MainScreen()
                    }
                }
            }
        } catch (e: Throwable) {
            // أول composition (أو إنشاء الـ ViewModel) ضرب — الاستثناء مش هيوصل
            // للمعالج العام لأنه اتمسك هنا، فاكتب التقرير بنفسك واعرض شاشة
            // خطأ بسيطة من غير ViewModel. (try-catch جوّه الـ lambda نفسها
            // مينفعش: الاستثناء أثناء الـ composition بيبوّظ الـ composition
            // ومش ممكن تكمّل تعرض محتوى تاني في نفس المحاولة، فالـ catch هنا
            // برّه setContent مع setContent جديد لشاشة الخطأ.)
            CrashLog.writeCrashReport(this, e)
            try {
                setContent {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        VoiceStudioTheme {
                            CrashScreen(e)
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                CrashLog.writeCrashReport(applicationContext, throwable)
            } catch (ignored: Throwable) {
            }
            // كمّل السلوك الطبيعي (موت العملية) بعد كتابة التقرير.
            previous?.uncaughtException(thread, throwable)
        }
    }
}

@Composable
fun VoiceStudioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(),
        content = content
    )
}

/**
 * شاشة خطأ بسيطة تظهر لو التطبيق ضرب عند الفتح — من غير ViewModel
 * خالص (عشان تشتغل حتى لو الـ ViewModel نفسه هو اللي بيضرب).
 * بتوري نوع الخطأ ورسالته والـ stack trace كامل، مع زرار نسخ.
 */
@Composable
fun CrashScreen(error: Throwable) {
    val context = LocalContext.current
    val report = remember { CrashLog.formatCrash(error) }
    var copied by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "حصلت مشكلة عند الفتح",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "${error.javaClass.simpleName}: ${error.message ?: ""}",
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = {
            try {
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("crash", report))
                copied = true
            } catch (ignored: Exception) {
            }
        }) {
            Text(if (copied) "اتنسخ ✔" else "نسخ الخطأ")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "ملف التقرير اتحفظ في التنزيلات (memo-voice-studio-crash.txt)",
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(report, fontSize = 12.sp)
    }
}

@Composable
fun MainScreen() {
    val viewModel: MainViewModel = viewModel()
    var tab by remember { mutableStateOf(0) }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Text("🎙") },
                    label = { Text("تفريغ") }
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Text("🔊") },
                    label = { Text("النطق") }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Text("⚙") },
                    label = { Text("الإعدادات") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier.padding(padding)
        ) {
            when (tab) {
                0 -> TranscribeScreen(viewModel) { tab = 1 }
                1 -> SpeakScreen(viewModel) { tab = 2 }
                else -> SettingsScreen(viewModel)
            }
        }
    }
}

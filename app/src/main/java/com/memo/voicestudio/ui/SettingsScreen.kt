package com.memo.voicestudio.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(viewModel: MainViewModel) {
    val scroll = rememberScrollState()
    var keyInput by remember(viewModel.apiKey) { mutableStateOf(viewModel.apiKey) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(20.dp)
    ) {
        Text("الإعدادات", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        Text(
            "مفتاح Gemini API (مجاني)",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "التطبيق بيستخدم حساب Google المجاني بتاعك — المفتاح محفوظ على موبايلك بس ومش بيتبعت لأي مكان غير Google.",
            fontSize = 16.sp
        )
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("الصق المفتاح هنا") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = { viewModel.saveApiKey(keyInput) },
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text("حفظ المفتاح", fontSize = 18.sp) }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = { viewModel.testKey() },
            enabled = !viewModel.isTestingKey && viewModel.apiKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("اختبر المفتاح", fontSize = 16.sp) }

        if (viewModel.isTestingKey) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator()
        }
        if (viewModel.keyTest != null) {
            Spacer(Modifier.height(12.dp))
            Text(viewModel.keyTest ?: "", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(24.dp))
        Text("إزاي تجيب مفتاح مجاني؟", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "1) افتح Chrome وادخل على: aistudio.google.com/apikey\n" +
                "2) سجّل دخول بحساب Google بتاعك\n" +
                "3) اضغط على زر Create API key\n" +
                "4) انسخ المفتاح والصقه هنا واضغط حفظ\n" +
                "5) اضغط اختبر المفتاح عشان تتأكد إنه شغال",
            fontSize = 16.sp
        )

        Spacer(Modifier.height(24.dp))
        Text("حدود الباقة المجانية", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "• التفريغ: ببلاش لحد ساعة صوت في المرة الواحدة، وحوالي 25 مرة في اليوم.\n" +
                "• الصوت: ببلاش بحد يومي صغير — لو خلص الحد، استنى لليوم اللي بعده.\n" +
                "• لو الاستخدام زاد عن الحد المجاني، الخدمة بتوقف مؤقتًا ومش بتخصم فلوس من غير إذنك.",
            fontSize = 16.sp
        )

        Spacer(Modifier.height(24.dp))
        Text("الخصوصية", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "• مفتاحك محفوظ مشفّر على جهازك بس.\n" +
                "• ملفاتك بتتبعت لـ Google للتفريغ وتوليد الصوت بس، ومش بتتشارك مع أي حد تاني.\n" +
                "• التطبيق مفيهوش حساب ولا تسجيل دخول.",
            fontSize = 16.sp
        )
        Spacer(Modifier.height(24.dp))
    }
}

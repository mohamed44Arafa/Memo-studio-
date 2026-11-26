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
    var groqInput by remember(viewModel.groqApiKey) { mutableStateOf(viewModel.groqApiKey) }
    var azureInput by remember(viewModel.azureApiKey) { mutableStateOf(viewModel.azureApiKey) }
    var regionInput by remember(viewModel.azureRegion) { mutableStateOf(viewModel.azureRegion) }
    var deepgramInput by remember(viewModel.deepgramApiKey) { mutableStateOf(viewModel.deepgramApiKey) }
    var elevenLabsInput by remember(viewModel.elevenLabsApiKey) { mutableStateOf(viewModel.elevenLabsApiKey) }
    var cartesiaInput by remember(viewModel.cartesiaApiKey) { mutableStateOf(viewModel.cartesiaApiKey) }

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
        Text("مفاتيح احتياطية (للتبديل التلقائي)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "لو خلص ليمت Gemini، التطبيق ينقل لوحده على البديل من غير ما تعمل حاجة — بس لازم تحط مفاتيحهم هنا الأول. المفاتيح محفوظة مشفّرة على موبايلك بس.",
            fontSize = 16.sp
        )
        Spacer(Modifier.height(12.dp))
        Text("مفتاح Groq (للتفريغ)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = groqInput,
            onValueChange = { groqInput = it },
            label = { Text("الصق مفتاح Groq هنا") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.saveGroqApiKey(groqInput) },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("حفظ مفتاح Groq", fontSize = 16.sp) }
        Spacer(Modifier.height(8.dp))
        Text("مجاني ومن غير كارت — من console.groq.com/keys", fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        Text("مفتاح Azure (للصوت)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = azureInput,
            onValueChange = { azureInput = it },
            label = { Text("الصق مفتاح Azure هنا") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = regionInput,
            onValueChange = { regionInput = it },
            label = { Text("منطقة Azure (مثال: westeurope)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.saveAzureApiKey(azureInput); viewModel.saveAzureRegion(regionInput) },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("حفظ مفتاح Azure", fontSize = 16.sp) }
        Spacer(Modifier.height(8.dp))
        Text("فيه صوت مصري — من Azure، وممكن يطلب كارت.", fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        Text("مفتاح Deepgram (للتفريغ)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = deepgramInput,
            onValueChange = { deepgramInput = it },
            label = { Text("الصق مفتاح Deepgram هنا") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.saveDeepgramApiKey(deepgramInput) },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("حفظ مفتاح Deepgram", fontSize = 16.sp) }
        Spacer(Modifier.height(8.dp))
        Text("من deepgram.com ← Console ← API Keys — رصيد ٢٠٠ دولار مرة واحدة", fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        Text("مفتاح ElevenLabs (للصوت)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = elevenLabsInput,
            onValueChange = { elevenLabsInput = it },
            label = { Text("الصق مفتاح ElevenLabs هنا") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.saveElevenLabsApiKey(elevenLabsInput) },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("حفظ مفتاح ElevenLabs", fontSize = 16.sp) }
        Spacer(Modifier.height(8.dp))
        Text("من elevenlabs.io — مفتاح API — ١٠ آلاف نقطة كل شهر", fontSize = 14.sp)
        Spacer(Modifier.height(20.dp))
        Text("مفتاح Cartesia (للصوت)", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = cartesiaInput,
            onValueChange = { cartesiaInput = it },
            label = { Text("الصق مفتاح Cartesia هنا") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.saveCartesiaApiKey(cartesiaInput) },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("حفظ مفتاح Cartesia", fontSize = 16.sp) }
        Spacer(Modifier.height(8.dp))
        Text("من cartesia.ai ← Play ← API key — ٢٠ ألف نقطة كل شهر", fontSize = 14.sp)

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

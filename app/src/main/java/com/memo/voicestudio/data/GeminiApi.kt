package com.memo.voicestudio.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * واجهة Gemini API (Generative Language API v1beta).
 * كل الطلبات تروح لـ Google مباشرة بمفتاح المستخدم — لا يوجد سيرفر وسيط.
 */
interface GeminiApi {

    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Query("key") apiKey: String,
        @Body body: GenerateRequest
    ): Response<GenerateResponse>

    @GET("v1beta/models")
    suspend fun listModels(
        @Query("key") apiKey: String
    ): Response<ListModelsResponse>

    @GET("v1beta/files/{name}")
    suspend fun getFile(
        @Path("name") name: String,
        @Query("key") apiKey: String
    ): Response<FileInfoResponse>
}

// ---------- الطلب ----------

data class GenerateRequest(
    val contents: List<Content>,
    val generationConfig: GenerationConfig? = null
)

data class Content(
    val parts: List<Part>
)

data class Part(
    val text: String? = null,
    val fileData: FileData? = null,
    val inlineData: InlineData? = null
)

data class FileData(
    val mimeType: String,
    val fileUri: String
)

data class InlineData(
    val mimeType: String,
    val data: String
)

data class GenerationConfig(
    val responseModalities: List<String>? = null,
    val responseMimeType: String? = null,
    val speechConfig: SpeechConfig? = null
)

data class SpeechConfig(
    val voiceConfig: VoiceConfig? = null,
    val languageCode: String? = null
)

data class VoiceConfig(
    val prebuiltVoiceConfig: PrebuiltVoiceConfig
)

data class PrebuiltVoiceConfig(
    val voiceName: String
)

// ---------- الرد ----------

data class GenerateResponse(
    val candidates: List<Candidate>?
)

data class Candidate(
    val content: Content?
)

data class ListModelsResponse(
    val models: List<ModelInfo>?
)

data class ModelInfo(
    val name: String?
)

// ---------- Files API ----------

data class FileUploadResponse(
    val file: FileInfoResponse?
)

data class FileInfoResponse(
    val name: String?,
    val displayName: String?,
    val mimeType: String?,
    val sizeBytes: String?,
    val state: String?,
    val uri: String?
)

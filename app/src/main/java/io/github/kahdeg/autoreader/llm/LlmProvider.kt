package io.github.kahdeg.autoreader.llm

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import io.github.kahdeg.autoreader.util.AppLog
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenAI-compatible LLM Provider with streaming support.
 * Works with OpenAI, Azure OpenAI, DeepSeek, Ollama, LM Studio, and other compatible endpoints.
 */
@Singleton
class LlmProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs = context.getSharedPreferences("autoreader_settings", Context.MODE_PRIVATE)
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
        }
        engine {
            config {
                connectTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                writeTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }
    
    private val baseUrl: String
        get() = prefs.getString("api_base_url", "https://api.openai.com/v1") ?: "https://api.openai.com/v1"
    
    private val apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
    
    private val modelName: String
        get() = prefs.getString("model_name", "gpt-4o-mini") ?: "gpt-4o-mini"
    
    /**
     * Check if the provider is configured.
     */
    fun isConfigured(): Boolean = apiKey.isNotBlank()
    
    /**
     * Send a completion request to the LLM (non-streaming).
     */
    suspend fun complete(request: LlmRequest): Result<LlmResponse> {
        if (!isConfigured()) {
            return Result.failure(Exception("LLM provider not configured. Please set API key in Settings."))
        }
        
        return try {
            val chatRequest = ChatCompletionRequest(
                model = modelName,
                messages = listOf(
                    ChatMessage(role = "system", content = request.systemPrompt),
                    ChatMessage(role = "user", content = request.userMessage)
                ),
                max_tokens = request.maxTokens,
                temperature = request.temperature,
                response_format = if (request.jsonMode) ResponseFormat("json_object") else null,
                stream = false
            )
            
            val response = httpClient.post("$baseUrl/chat/completions") {
                contentType(ContentType.Application.Json)
                if (apiKey.isNotBlank()) {
                    header("Authorization", "Bearer $apiKey")
                }
                setBody(chatRequest)
            }
            
            val responseText = response.bodyAsText()
            AppLog.d("LlmProvider", "Raw response: ${responseText.take(500)}")
            
            val chatResponse: ChatCompletionResponse = json.decodeFromString(responseText)
            
            if (chatResponse.choices.isNullOrEmpty()) {
                AppLog.e("LlmProvider", "No choices in response: $responseText")
                return Result.failure(Exception("API error: ${chatResponse.error?.message ?: "No choices returned"}"))
            }
            
            val message = chatResponse.choices.firstOrNull()?.message
            val content = message?.content ?: ""
            val reasoning = message?.reasoning_content
            
            if (reasoning != null) {
                AppLog.d("LlmProvider", "Reasoning: $reasoning")
            }
            AppLog.d("LlmProvider", "Response: $content")
            
            Result.success(LlmResponse(
                content = content,
                reasoningContent = reasoning,
                tokensUsed = chatResponse.usage?.total_tokens ?: 0
            ))
        } catch (e: Exception) {
            AppLog.e("LlmProvider", "LLM request failed: ${e.message}")
            Result.failure(Exception("LLM request failed: ${e.message}"))
        }
    }
    
    /**
     * Send a streaming completion request to the LLM.
     * Returns a Flow that emits content chunks as they arrive.
     */
    fun completeStreaming(request: LlmRequest): Flow<StreamingChunk> = flow {
        if (!isConfigured()) {
            emit(StreamingChunk.Error("LLM provider not configured. Please set API key in Settings."))
            return@flow
        }
        
        try {
            val chatRequest = ChatCompletionRequest(
                model = modelName,
                messages = listOf(
                    ChatMessage(role = "system", content = request.systemPrompt),
                    ChatMessage(role = "user", content = request.userMessage)
                ),
                max_tokens = request.maxTokens,
                temperature = request.temperature,
                response_format = if (request.jsonMode) ResponseFormat("json_object") else null,
                stream = true
            )
            
            AppLog.d("LlmProvider", "Starting streaming request...")
            
            val response = httpClient.post("$baseUrl/chat/completions") {
                contentType(ContentType.Application.Json)
                if (apiKey.isNotBlank()) {
                    header("Authorization", "Bearer $apiKey")
                }
                setBody(chatRequest)
            }
            
            val channel: ByteReadChannel = response.bodyAsChannel()
            val buffer = StringBuilder()
            
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: break
                
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    
                    if (data == "[DONE]") {
                        break
                    }
                    
                    try {
                        val chunk = json.decodeFromString<StreamingChatCompletionResponse>(data)
                        val delta = chunk.choices.firstOrNull()?.delta
                        
                        // Emit reasoning content if present (DeepSeek)
                        delta?.reasoning_content?.let { reasoning ->
                            emit(StreamingChunk.Reasoning(reasoning))
                        }
                        
                        // Emit content
                        delta?.content?.let { content ->
                            buffer.append(content)
                            emit(StreamingChunk.Content(content))
                        }
                    } catch (e: Exception) {
                        // Skip malformed chunks
                        AppLog.w("LlmProvider", "Failed to parse chunk: $data")
                    }
                }
            }
            
            emit(StreamingChunk.Done(buffer.toString()))
        } catch (e: Exception) {
            AppLog.e("LlmProvider", "Streaming request failed: ${e.message}")
            emit(StreamingChunk.Error(e.message ?: "Unknown error"))
        }
    }
}

// Request/Response models

data class LlmRequest(
    val systemPrompt: String,
    val userMessage: String,
    val maxTokens: Int = 16000,  // Increased for full chapters
    val temperature: Float = 0.3f,
    val jsonMode: Boolean = false
)

data class LlmResponse(
    val content: String,
    val reasoningContent: String? = null,
    val tokensUsed: Int
)

/**
 * Streaming chunk types
 */
sealed class StreamingChunk {
    data class Content(val text: String) : StreamingChunk()
    data class Reasoning(val text: String) : StreamingChunk()
    data class Done(val fullContent: String) : StreamingChunk()
    data class Error(val message: String) : StreamingChunk()
}

// OpenAI API models

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val max_tokens: Int? = null,
    val temperature: Float? = null,
    val response_format: ResponseFormat? = null,
    val stream: Boolean? = null
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    val reasoning_content: String? = null
)

@Serializable
data class ResponseFormat(
    val type: String
)

@Serializable
data class ChatCompletionResponse(
    val choices: List<Choice>? = null,
    val usage: Usage? = null,
    val error: ApiError? = null
)

@Serializable
data class ApiError(
    val message: String? = null,
    val type: String? = null,
    val code: String? = null
)

@Serializable
data class Choice(
    val message: ChatMessage? = null,
    val delta: ChatMessage? = null
)

@Serializable
data class StreamingChatCompletionResponse(
    val choices: List<Choice>
)

@Serializable
data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

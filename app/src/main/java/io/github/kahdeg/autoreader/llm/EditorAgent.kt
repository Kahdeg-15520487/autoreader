package io.github.kahdeg.autoreader.llm

import io.github.kahdeg.autoreader.data.db.entity.ProcessingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import io.github.kahdeg.autoreader.util.AppLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Editor Agent - Translates or cleans up chapter content using LLM.
 * Supports streaming for real-time UI updates.
 */
@Singleton
class EditorAgent @Inject constructor(
    private val llmProvider: LlmProvider
) {
    companion object {
        private val CLEANUP_PROMPT = """
You are a professional fiction editor. Your task is to clean up and improve the readability of the provided text.

Instructions:
1. Fix grammar, spelling, and punctuation errors
2. Fix gender consistency issues (he/she confusion common in machine translations)
3. Fix name consistency (use consistent romanization/spelling of character names)
4. Preserve all paragraph breaks
5. Do NOT change the meaning or plot
6. Do NOT summarize or shorten the text
7. Keep all narrative details and dialogue
8. Output ONLY the cleaned text, no explanations

Output the improved text:
        """.trimIndent()
        
        private fun translationPrompt(sourceLanguage: String, targetLanguage: String) = """
You are a professional fiction translator and editor. Your task is to translate the provided text from $sourceLanguage to $targetLanguage.

IMPORTANT: You MUST output the translation in $targetLanguage. Do NOT output in English or any other language.

Source Language: $sourceLanguage
Target Language: $targetLanguage

Instructions:
1. Translate the text accurately while maintaining the author's style and tone
2. Preserve the flow and readability - it should read naturally in $targetLanguage
3. Fix gender consistency issues (he/she errors common in MTL)
4. Keep character names consistent (you may provide romanization in parentheses on first mention)
5. Preserve all paragraph breaks
6. Do NOT summarize or shorten the text
7. Keep all narrative details, descriptions, and dialogue
8. Output ONLY the translated text in $targetLanguage, no explanations or notes

Translate the following text to $targetLanguage:
        """.trimIndent()
    }
    
    /**
     * Process chapter content with streaming - emits chunks as they arrive.
     * Use this for real-time UI updates.
     */
    fun processContentStreaming(
        rawText: String,
        mode: ProcessingMode,
        sourceLanguage: String,
        targetLanguage: String
    ): Flow<StreamingChunk> = flow {
        AppLog.d("EditorAgent", "processContentStreaming: mode=$mode, source=$sourceLanguage, target=$targetLanguage, textLength=${rawText.length}")
        
        if (rawText.isBlank()) {
            AppLog.e("EditorAgent", "Empty content provided")
            emit(StreamingChunk.Error("Empty content"))
            return@flow
        }
        
        val systemPrompt = when (mode) {
            ProcessingMode.CLEANUP -> CLEANUP_PROMPT
            ProcessingMode.TRANSLATION -> translationPrompt(
                getLanguageName(sourceLanguage),
                getLanguageName(targetLanguage)
            )
        }
        
        val request = LlmRequest(
            systemPrompt = systemPrompt,
            userMessage = rawText,
            maxTokens = 8192,  // API limit
            temperature = 0.3f,
            jsonMode = false
        )
        
        // Stream the response
        llmProvider.completeStreaming(request).collect { chunk ->
            emit(chunk)
        }
    }
    
    /**
     * Process chapter content - non-streaming version for background processing.
     * Sends the full chapter without chunking.
     */
    suspend fun processContent(
        rawText: String,
        mode: ProcessingMode,
        sourceLanguage: String,
        targetLanguage: String
    ): Result<String> {
        AppLog.d("EditorAgent", "processContent: mode=$mode, source=$sourceLanguage, target=$targetLanguage, textLength=${rawText.length}")
        
        if (rawText.isBlank()) {
            AppLog.e("EditorAgent", "Empty content provided")
            return Result.failure(Exception("Empty content"))
        }
        
        val systemPrompt = when (mode) {
            ProcessingMode.CLEANUP -> CLEANUP_PROMPT
            ProcessingMode.TRANSLATION -> translationPrompt(
                getLanguageName(sourceLanguage),
                getLanguageName(targetLanguage)
            )
        }
        
        val request = LlmRequest(
            systemPrompt = systemPrompt,
            userMessage = rawText,
            maxTokens = 8192,  // API limit
            temperature = 0.3f,
            jsonMode = false
        )
        
        AppLog.d("EditorAgent", "Sending full chapter (${rawText.length} chars) to LLM")
        
        val result = llmProvider.complete(request)
        if (result.isFailure) {
            AppLog.e("EditorAgent", "Processing failed: ${result.exceptionOrNull()?.message}")
            return Result.failure(result.exceptionOrNull() ?: Exception("Processing failed"))
        }
        
        val response = result.getOrThrow()
        AppLog.d("EditorAgent", "Processing complete: ${response.tokensUsed} tokens, output length=${response.content.length}")
        response.reasoningContent?.let { 
            AppLog.d("EditorAgent", "Reasoning: $it") 
        }
        
        return Result.success(response.content)
    }
    
    private fun getLanguageName(code: String): String = when (code.lowercase()) {
        "en" -> "English"
        "zh" -> "Chinese"
        "ko" -> "Korean"
        "ja" -> "Japanese"
        "vi" -> "Vietnamese"
        "th" -> "Thai"
        "id" -> "Indonesian"
        "es" -> "Spanish"
        "fr" -> "French"
        "de" -> "German"
        "pt" -> "Portuguese"
        "ru" -> "Russian"
        else -> code
    }
}

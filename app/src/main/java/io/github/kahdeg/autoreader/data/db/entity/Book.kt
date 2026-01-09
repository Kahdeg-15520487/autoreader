package io.github.kahdeg.autoreader.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A book/story being read by the user.
 */
@Entity(tableName = "books")
data class Book(
    @PrimaryKey val bookUrl: String,
    val domain: String,
    val title: String,
    val author: String,
    val coverUrl: String?,
    val synopsis: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val currentChapterIndex: Int = 0,
    val lookAheadLimit: Int = 5
) {
    val processingMode: ProcessingMode
        get() = if (sourceLanguage == targetLanguage) ProcessingMode.CLEANUP 
                else ProcessingMode.TRANSLATION
}

enum class ProcessingMode {
    CLEANUP,
    TRANSLATION
}

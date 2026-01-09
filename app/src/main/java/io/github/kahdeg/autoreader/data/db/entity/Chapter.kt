package io.github.kahdeg.autoreader.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single chapter within a book.
 */
@Entity(
    tableName = "chapters",
    foreignKeys = [
        ForeignKey(
            entity = Book::class,
            parentColumns = ["bookUrl"],
            childColumns = ["bookUrl"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("bookUrl"),
        Index(value = ["bookUrl", "index"], unique = true)  // Unique constraint to prevent duplicate chapters
    ]
)
data class Chapter(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookUrl: String,
    val index: Int,
    val title: String,
    val rawUrl: String,
    val status: ChapterStatus,
    val rawText: String?,
    val rawLanguage: String?,
    val processedText: String?,
    val processedLanguage: String?,
    val errorMessage: String?,
    val expectedMinLength: Int? = null
) {
    fun hasValidContent(): Boolean {
        if (rawText.isNullOrBlank()) return false
        val minLength = expectedMinLength ?: 500
        return rawText.length >= minLength
    }
}

enum class ChapterStatus {
    PENDING,
    FETCHING,
    FETCH_FAILED,
    TRANSLATING,
    READY,
    USER_FLAGGED
}

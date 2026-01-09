package io.github.kahdeg.autoreader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.kahdeg.autoreader.data.db.entity.Chapter
import io.github.kahdeg.autoreader.data.db.entity.ChapterStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    
    @Query("SELECT * FROM chapters WHERE bookUrl = :bookUrl ORDER BY `index` ASC")
    fun getChaptersForBook(bookUrl: String): Flow<List<Chapter>>
    
    @Query("SELECT * FROM chapters WHERE bookUrl = :bookUrl AND `index` BETWEEN :startIndex AND :endIndex ORDER BY `index` ASC")
    suspend fun getChaptersInRange(bookUrl: String, startIndex: Int, endIndex: Int): List<Chapter>
    
    @Query("SELECT * FROM chapters WHERE id = :id")
    suspend fun getById(id: Long): Chapter?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chapter: Chapter)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(chapters: List<Chapter>)
    
    @Query("UPDATE chapters SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: ChapterStatus)
    
    @Query("UPDATE chapters SET rawText = :rawText, rawLanguage = :rawLanguage WHERE id = :id")
    suspend fun updateRaw(id: Long, rawText: String, rawLanguage: String)
    
    @Query("UPDATE chapters SET processedText = :processedText, processedLanguage = :processedLanguage WHERE id = :id")
    suspend fun updateProcessed(id: Long, processedText: String, processedLanguage: String)
    
    @Query("UPDATE chapters SET errorMessage = :errorMessage WHERE id = :id")
    suspend fun updateError(id: Long, errorMessage: String)
    
    @Query("SELECT COUNT(*) FROM chapters WHERE bookUrl = :bookUrl AND status = :status")
    suspend fun countByStatus(bookUrl: String, status: ChapterStatus): Int
    
    @Query("UPDATE chapters SET status = 'PENDING' WHERE bookUrl = :bookUrl AND status = 'TRANSLATING'")
    suspend fun resetStuckTranslating(bookUrl: String): Int
    
    @Query("SELECT COUNT(*) FROM chapters c JOIN books b ON c.bookUrl = b.bookUrl WHERE b.domain = :domain AND c.status = 'USER_FLAGGED'")
    suspend fun getFlaggedCountForDomain(domain: String): Int
    
    @Query("DELETE FROM chapters WHERE bookUrl = :bookUrl")
    suspend fun deleteAllForBook(bookUrl: String)
}

package io.github.kahdeg.autoreader.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import io.github.kahdeg.autoreader.data.db.entity.Book
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    
    @Query("SELECT * FROM books")
    fun getAllFlow(): Flow<List<Book>>
    
    @Query("SELECT * FROM books WHERE bookUrl = :bookUrl")
    suspend fun getByUrl(bookUrl: String): Book?
    
    @Query("SELECT * FROM books WHERE bookUrl = :bookUrl")
    fun getByUrlFlow(bookUrl: String): Flow<Book?>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(book: Book)
    
    @Update
    suspend fun update(book: Book)
    
    @Delete
    suspend fun delete(book: Book)
    
    @Query("UPDATE books SET currentChapterIndex = :index WHERE bookUrl = :bookUrl")
    suspend fun updateReadingProgress(bookUrl: String, index: Int)
    
    @Query("UPDATE books SET lookAheadLimit = :limit WHERE bookUrl = :bookUrl")
    suspend fun updateLookAheadLimit(bookUrl: String, limit: Int)
}

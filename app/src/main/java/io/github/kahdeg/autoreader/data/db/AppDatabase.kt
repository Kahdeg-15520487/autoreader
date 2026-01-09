package io.github.kahdeg.autoreader.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import io.github.kahdeg.autoreader.data.db.dao.BookDao
import io.github.kahdeg.autoreader.data.db.dao.ChapterDao
import io.github.kahdeg.autoreader.data.db.dao.SiteBlueprintDao
import io.github.kahdeg.autoreader.data.db.entity.Book
import io.github.kahdeg.autoreader.data.db.entity.Chapter
import io.github.kahdeg.autoreader.data.db.entity.SiteBlueprint

@Database(
    entities = [
        SiteBlueprint::class,
        Book::class,
        Chapter::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun siteBlueprintDao(): SiteBlueprintDao
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    
    companion object {
        const val DATABASE_NAME = "autoreader.db"
    }
}

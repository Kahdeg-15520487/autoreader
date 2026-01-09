package io.github.kahdeg.autoreader.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.kahdeg.autoreader.data.db.AppDatabase
import io.github.kahdeg.autoreader.data.db.dao.BookDao
import io.github.kahdeg.autoreader.data.db.dao.ChapterDao
import io.github.kahdeg.autoreader.data.db.dao.SiteBlueprintDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
        .addMigrations(MIGRATION_1_2)
        .fallbackToDestructiveMigration()  // Safety fallback if migration fails
        .build()
    }
    
    private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            // Add unique index on (bookUrl, index) for chapters
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_chapters_bookUrl_index` ON `chapters` (`bookUrl`, `index`)")
        }
    }
    
    @Provides
    fun provideSiteBlueprintDao(database: AppDatabase): SiteBlueprintDao {
        return database.siteBlueprintDao()
    }
    
    @Provides
    fun provideBookDao(database: AppDatabase): BookDao {
        return database.bookDao()
    }
    
    @Provides
    fun provideChapterDao(database: AppDatabase): ChapterDao {
        return database.chapterDao()
    }
}

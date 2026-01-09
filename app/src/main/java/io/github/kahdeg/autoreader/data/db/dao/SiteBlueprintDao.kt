package io.github.kahdeg.autoreader.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.kahdeg.autoreader.data.db.entity.SiteBlueprint
import kotlinx.coroutines.flow.Flow

@Dao
interface SiteBlueprintDao {
    
    @Query("SELECT * FROM site_blueprints WHERE domain = :domain")
    suspend fun getByDomain(domain: String): SiteBlueprint?
    
    @Query("SELECT * FROM site_blueprints")
    fun getAllFlow(): Flow<List<SiteBlueprint>>
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(blueprint: SiteBlueprint)
    
    @Query("UPDATE site_blueprints SET lastValidated = :timestamp WHERE domain = :domain")
    suspend fun updateValidationTime(domain: String, timestamp: Long)
    
    @Query("DELETE FROM site_blueprints WHERE domain = :domain")
    suspend fun invalidate(domain: String)
    
    @Query("DELETE FROM site_blueprints WHERE domain = :domain")
    suspend fun deleteByDomain(domain: String)
}

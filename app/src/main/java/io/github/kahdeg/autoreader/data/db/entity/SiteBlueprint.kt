package io.github.kahdeg.autoreader.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached blueprint for scraping a specific domain.
 * Reused across all books from the same site.
 */
@Entity(tableName = "site_blueprints")
data class SiteBlueprint(
    @PrimaryKey val domain: String,
    val listType: ListType,
    val chapterCssSelector: String,
    val contentSelector: String,
    val nextButtonSelector: String?,
    val triggerSelector: String?,
    val waitStrategy: WaitStrategy = WaitStrategy.NETWORK,
    val lastValidated: Long,
    val version: Int = 1
)

enum class ListType {
    SIMPLE,
    PAGINATED,
    LOAD_MORE,
    EXPANDABLE_TOGGLE
}

enum class WaitStrategy {
    NETWORK,
    MUTATION,
    TIMEOUT
}

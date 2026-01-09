package io.github.kahdeg.autoreader.ui.navigation

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation routes using Kotlin Serialization.
 */
sealed interface Route {
    
    @Serializable
    data object Library : Route
    
    @Serializable
    data object AddBook : Route
    
    @Serializable
    data class BookDetail(val bookUrl: String) : Route
    
    @Serializable
    data class Reader(val bookUrl: String, val chapterIndex: Int = 0) : Route
    
    @Serializable
    data class DebugWebView(val url: String) : Route
    
    @Serializable
    data object Settings : Route
}

package io.github.kahdeg.autoreader.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.github.kahdeg.autoreader.ui.screens.addbook.AddBookScreen
import io.github.kahdeg.autoreader.ui.screens.bookdetail.BookDetailScreen
import io.github.kahdeg.autoreader.ui.screens.library.LibraryScreen
import io.github.kahdeg.autoreader.ui.screens.reader.ReaderScreen
import io.github.kahdeg.autoreader.ui.screens.settings.SettingsScreen
import io.github.kahdeg.autoreader.ui.screens.webview.DebugWebViewScreen

@Composable
fun AutoReaderNavHost(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Route.Library,
        modifier = modifier
    ) {
        composable<Route.Library> {
            LibraryScreen(
                onBookClick = { bookUrl ->
                    navController.navigate(Route.BookDetail(bookUrl))
                },
                onAddBookClick = {
                    navController.navigate(Route.AddBook)
                },
                onSettingsClick = {
                    navController.navigate(Route.Settings)
                }
            )
        }
        
        composable<Route.AddBook> {
            AddBookScreen(
                onNavigateBack = { navController.popBackStack() },
                onBookAdded = { bookUrl ->
                    navController.navigate(Route.BookDetail(bookUrl)) {
                        popUpTo(Route.Library)
                    }
                }
            )
        }
        
        composable<Route.BookDetail> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.BookDetail>()
            BookDetailScreen(
                bookUrl = route.bookUrl,
                onNavigateBack = { navController.popBackStack() },
                onReadClick = { chapterIndex ->
                    navController.navigate(Route.Reader(route.bookUrl, chapterIndex))
                },
                onViewPage = { url ->
                    navController.navigate(Route.DebugWebView(url))
                }
            )
        }
        
        composable<Route.Reader> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.Reader>()
            ReaderScreen(
                bookUrl = route.bookUrl,
                startChapterIndex = route.chapterIndex,
                onNavigateBack = { navController.popBackStack() }
            )
        }
        
        composable<Route.DebugWebView> { backStackEntry ->
            val route = backStackEntry.toRoute<Route.DebugWebView>()
            DebugWebViewScreen(
                url = route.url,
                onClose = { navController.popBackStack() }
            )
        }
        
        composable<Route.Settings> {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}

package io.github.kahdeg.autoreader.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.kahdeg.autoreader.data.db.dao.BookDao
import io.github.kahdeg.autoreader.data.db.entity.Book
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface LibraryUiState {
    data object Loading : LibraryUiState
    data object Empty : LibraryUiState
    data class Success(val books: List<Book>) : LibraryUiState
}

@HiltViewModel
class LibraryViewModel @Inject constructor(
    bookDao: BookDao
) : ViewModel() {
    
    val uiState: StateFlow<LibraryUiState> = bookDao.getAllFlow()
        .map { books ->
            if (books.isEmpty()) LibraryUiState.Empty
            else LibraryUiState.Success(books)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = LibraryUiState.Loading
        )
}

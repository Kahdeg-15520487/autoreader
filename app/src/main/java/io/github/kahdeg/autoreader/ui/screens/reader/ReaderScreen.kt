package io.github.kahdeg.autoreader.ui.screens.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.kahdeg.autoreader.data.db.entity.Chapter
import io.github.kahdeg.autoreader.data.db.entity.ChapterStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookUrl: String,
    startChapterIndex: Int,
    onNavigateBack: () -> Unit,
    viewModel: ReaderViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    
    // Scroll to top when chapter changes
    LaunchedEffect(uiState.currentChapterIndex) {
        scrollState.scrollTo(0)
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(
                            text = uiState.bookTitle,
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    // Translating indicator
                    if (uiState.currentChapter?.status == ChapterStatus.TRANSLATING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    // Clear and Retranslate button
                    IconButton(
                        onClick = { 
                            viewModel.clearAndRetranslate()
                            scope.launch {
                                snackbarHostState.showSnackbar("Clearing and retranslating chapter...")
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Clear and retranslate chapter"
                        )
                    }
                    // Flag button
                    IconButton(
                        onClick = { 
                            viewModel.onFlagCurrentChapter()
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Chapter flagged for review"
                                )
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Flag chapter",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                )
            )
        },
        snackbarHost = { 
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface
                )
            }
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (uiState.chapters.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No chapters available yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Single chapter view with scroll
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                scope.launch {
                                    // If at bottom (or close to it), scroll to top
                                    // Otherwise, scroll to bottom
                                    val isAtBottom = scrollState.value >= scrollState.maxValue - 100
                                    if (isAtBottom) {
                                        scrollState.animateScrollTo(0)
                                    } else {
                                        scrollState.animateScrollTo(scrollState.maxValue)
                                    }
                                }
                            }
                        )
                    }
                    .verticalScroll(scrollState)
            ) {
                // Chapter content
                uiState.currentChapter?.let { chapter ->
                    ChapterContent(
                        chapter = chapter,
                        displayContent = uiState.displayContent,
                        onRefresh = { viewModel.refreshCurrentChapter() },
                        onRetranslate = { viewModel.retranslate() }
                    )
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                // Navigation footer
                ChapterNavigation(
                    currentIndex = uiState.currentChapterIndex,
                    totalChapters = uiState.totalChapters,
                    hasPrevious = uiState.hasPreviousChapter,
                    hasNext = uiState.hasNextChapter,
                    onPrevious = { viewModel.goToPreviousChapter() },
                    onNext = { viewModel.goToNextChapter() }
                )
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ChapterContent(
    chapter: Chapter,
    displayContent: String?,
    onRefresh: () -> Unit,
    onRetranslate: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 24.dp)
    ) {
        // Chapter title
        Text(
            text = chapter.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // Content display
        when {
            // Show translating indicator based on chapter status
            chapter.status == ChapterStatus.TRANSLATING -> {
                LoadingIndicator(message = "Translating...")
            }
            
            // Has content to display
            displayContent != null && displayContent.isNotBlank() -> {
                // Split into paragraphs on double newlines
                displayContent.split("\n\n").filter { it.isNotBlank() }.forEach { paragraph ->
                    Text(
                        text = paragraph.trim(),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            lineHeight = 28.sp
                        ),
                        textAlign = TextAlign.Justify,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )
                }
            }
            
            // Loading states
            chapter.status == ChapterStatus.PENDING || chapter.status == ChapterStatus.FETCHING -> {
                LoadingIndicator(
                    message = if (chapter.status == ChapterStatus.FETCHING) 
                        "Fetching chapter..." else "Waiting to fetch..."
                )
            }
            
            // Error states
            chapter.status == ChapterStatus.FETCH_FAILED || chapter.status == ChapterStatus.USER_FLAGGED -> {
                ErrorDisplay(
                    message = chapter.errorMessage ?: "Failed to load chapter",
                    onRetry = onRefresh
                )
            }
            
            // Has raw text but no translation - offer to translate
            !chapter.rawText.isNullOrBlank() && chapter.processedText.isNullOrBlank() -> {
                Text(
                    text = "Chapter content available but not translated.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                FilledTonalButton(onClick = onRetranslate) {
                    Text("Translate Now")
                }
            }
            
            else -> {
                LoadingIndicator(message = "Loading...")
            }
        }
    }
}

@Composable
private fun LoadingIndicator(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorDisplay(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            FilledTonalButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}

@Composable
private fun ChapterNavigation(
    currentIndex: Int,
    totalChapters: Int,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        HorizontalDivider(modifier = Modifier.padding(bottom = 16.dp))
        
        // Chapter counter
        Text(
            text = "Chapter ${currentIndex + 1} of $totalChapters",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            textAlign = TextAlign.Center
        )
        
        // Navigation buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Previous button
            OutlinedButton(
                onClick = onPrevious,
                enabled = hasPrevious,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowLeft,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Previous")
            }
            
            // Next button
            Button(
                onClick = onNext,
                enabled = hasNext,
                modifier = Modifier.weight(1f)
            ) {
                Text("Next")
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowRight,
                    contentDescription = null
                )
            }
        }
    }
}

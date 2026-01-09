package io.github.kahdeg.autoreader.ui.screens.bookdetail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.kahdeg.autoreader.data.db.entity.Chapter
import io.github.kahdeg.autoreader.data.db.entity.ChapterStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookUrl: String,
    onNavigateBack: () -> Unit,
    onReadClick: (Int) -> Unit,
    onViewPage: (String) -> Unit = {},
    viewModel: BookDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val lazyListState = rememberLazyListState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    
    // Show error in snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showSnackbar(error)
            viewModel.clearError()
        }
    }
    
    // Scroll to currently reading chapter when book loads
    LaunchedEffect(uiState.book, uiState.chapters.size) {
        val book = uiState.book ?: return@LaunchedEffect
        if (uiState.chapters.isNotEmpty() && book.currentChapterIndex > 0) {
            lazyListState.scrollToItem(book.currentChapterIndex)
        }
    }
    
    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Book?") },
            text = { 
                Text("This will delete the book and all its chapters. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteBook { onNavigateBack() }
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
    
    // Edit Blueprint dialog
    if (showEditDialog) {
        EditBlueprintDialog(
            currentInfo = uiState.blueprintInfo,
            onDismiss = { showEditDialog = false },
            onSave = { listType, chapterSelector, contentSelector, nextSelector ->
                viewModel.updateBlueprint(listType, chapterSelector, contentSelector, nextSelector)
                showEditDialog = false
            }
        )
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = uiState.book?.title ?: "Book Details",
                        maxLines = 1
                    )
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
                    // Refresh button
                    IconButton(
                        onClick = { viewModel.refreshChapters() },
                        enabled = !uiState.isRefreshing
                    ) {
                        if (uiState.isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh chapters"
                            )
                        }
                    }
                    
                    // Delete button
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete book",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Book info header
            uiState.book?.let { book ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = book.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "by ${book.author}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "${book.sourceLanguage} → ${book.targetLanguage}",
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = if (book.processingMode.name == "CLEANUP") "Cleanup Mode" else "Translation Mode",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        
                        // Blueprint status
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (uiState.hasBlueprint) "✓ Site blueprint saved" else "⚠ No blueprint yet",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.hasBlueprint) 
                                    MaterialTheme.colorScheme.onPrimaryContainer 
                                else 
                                    MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f)
                            )
                            if (uiState.hasBlueprint) {
                                TextButton(
                                    onClick = { showEditDialog = true }
                                ) {
                                    Text("Edit", style = MaterialTheme.typography.labelSmall)
                                }
                                TextButton(
                                    onClick = { viewModel.resetBlueprint() }
                                ) {
                                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        
                        // Show blueprint selectors for debugging
                        uiState.blueprintInfo?.let { info ->
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = info,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { onReadClick(book.currentChapterIndex) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = uiState.chapters.isNotEmpty()
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Continue Reading (Ch. ${book.currentChapterIndex + 1})")
                        }
                    }
                }
            }
            
            // Chapter list header with count
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Chapters (${uiState.chapters.size})",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            
            if (uiState.chapters.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "No chapters yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.refreshChapters() },
                            enabled = !uiState.isRefreshing
                        ) {
                            if (uiState.isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Fetching...")
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Fetch Chapters")
                            }
                        }
                        
                        // View Page button for debugging
                        OutlinedButton(
                            onClick = { onViewPage(bookUrl) }
                        ) {
                            Text("View Page")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = lazyListState
                ) {
                    itemsIndexed(uiState.chapters) { index, chapter ->
                        ChapterItem(
                            chapter = chapter,
                            onClick = { onReadClick(index) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterItem(
    chapter: Chapter,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            
            StatusBadge(status = chapter.status)
        }
    }
}

@Composable
private fun StatusBadge(status: ChapterStatus) {
    val (text, color) = when (status) {
        ChapterStatus.PENDING -> "Pending" to MaterialTheme.colorScheme.outline
        ChapterStatus.FETCHING -> "Fetching..." to MaterialTheme.colorScheme.tertiary
        ChapterStatus.FETCH_FAILED -> "Failed" to MaterialTheme.colorScheme.error
        ChapterStatus.TRANSLATING -> "Translating..." to MaterialTheme.colorScheme.secondary
        ChapterStatus.READY -> "Ready" to MaterialTheme.colorScheme.primary
        ChapterStatus.USER_FLAGGED -> "Flagged" to MaterialTheme.colorScheme.error
    }
    
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditBlueprintDialog(
    currentInfo: String?,
    onDismiss: () -> Unit,
    onSave: (listType: String, chapterSelector: String, contentSelector: String, nextSelector: String?) -> Unit
) {
    // Parse current info to pre-fill fields
    var listType by remember { mutableStateOf("SIMPLE") }
    var chapterSelector by remember { mutableStateOf("") }
    var contentSelector by remember { mutableStateOf("") }
    var nextSelector by remember { mutableStateOf("") }
    var listTypeExpanded by remember { mutableStateOf(false) }
    
    val listTypes = listOf("SIMPLE", "PAGINATED", "LOAD_MORE", "EXPANDABLE_TOGGLE")
    
    // Parse current info on first composition
    LaunchedEffect(currentInfo) {
        currentInfo?.let { info ->
            info.lines().forEach { line ->
                when {
                    line.startsWith("Chapter selector:") -> 
                        chapterSelector = line.substringAfter(":").trim()
                    line.startsWith("Content selector:") -> 
                        contentSelector = line.substringAfter(":").trim()
                    line.startsWith("List type:") -> 
                        listType = line.substringAfter(":").trim()
                    line.startsWith("Next button:") -> 
                        nextSelector = line.substringAfter(":").trim()
                }
            }
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Blueprint") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Manually configure CSS selectors for this site",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // List Type dropdown
                ExposedDropdownMenuBox(
                    expanded = listTypeExpanded,
                    onExpandedChange = { listTypeExpanded = it }
                ) {
                    OutlinedTextField(
                        value = listType,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("List Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = listTypeExpanded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    )
                    ExposedDropdownMenu(
                        expanded = listTypeExpanded,
                        onDismissRequest = { listTypeExpanded = false }
                    ) {
                        listTypes.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(type) },
                                onClick = {
                                    listType = type
                                    listTypeExpanded = false
                                }
                            )
                        }
                    }
                }
                
                OutlinedTextField(
                    value = chapterSelector,
                    onValueChange = { chapterSelector = it },
                    label = { Text("Chapter CSS Selector") },
                    placeholder = { Text(".chapter-list a") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                OutlinedTextField(
                    value = contentSelector,
                    onValueChange = { contentSelector = it },
                    label = { Text("Content CSS Selector") },
                    placeholder = { Text(".chapter-content") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                if (listType == "PAGINATED") {
                    OutlinedTextField(
                        value = nextSelector,
                        onValueChange = { nextSelector = it },
                        label = { Text("Next Button Selector") },
                        placeholder = { Text(".pagination a:last-child") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        listType,
                        chapterSelector,
                        contentSelector,
                        nextSelector.takeIf { it.isNotBlank() }
                    )
                },
                enabled = chapterSelector.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

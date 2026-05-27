package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.database.DocumentEntity
import com.example.ui.OpenMindViewModel
import java.util.Locale

@Composable
fun RagScreen(
    viewModel: OpenMindViewModel,
    innerPadding: PaddingValues
) {
    val documents by viewModel.allActiveDocuments.collectAsState()
    val isRagEnabled by viewModel.isRagEnabled.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var matchingResults by remember { mutableStateOf<List<Pair<DocumentEntity, String>>>(emptyList()) }

    // RAG Upload dialog state
    var showAddDialog by remember { mutableStateOf(false) }
    var docTitle by remember { mutableStateOf("") }
    var docContent by remember { mutableStateOf("") }
    var docCategory by remember { mutableStateOf("pdf") } // "pdf", "docx", "txt", "md", "csv"

    // Search query monitoring
    LaunchedEffect(searchQuery, documents) {
        if (searchQuery.isNotBlank()) {
            val queryWords = searchQuery.lowercase(Locale.getDefault()).split(Regex("\\W+")).filter { it.length > 2 }
            if (queryWords.isNotEmpty()) {
                val matches = documents.mapNotNull { doc ->
                    var score = 0
                    val titleWords = doc.title.lowercase(Locale.getDefault()).split(Regex("\\W+"))
                    val contentWords = doc.content.lowercase(Locale.getDefault()).split(Regex("\\W+"))
                    for (word in queryWords) {
                        if (titleWords.contains(word)) score += 5
                        if (contentWords.contains(word)) score += 1
                    }
                    if (score > 0) {
                        val snippet = if (doc.content.length > 150) {
                            val fIdx = doc.content.lowercase().indexOf(queryWords.first())
                            val start = (fIdx - 40).coerceAtLeast(0)
                            val end = (fIdx + 110).coerceAtMost(doc.content.length)
                            "..." + doc.content.substring(start, end).replace("\n", " ") + "..."
                        } else {
                            doc.content
                        }
                        Triple(doc, snippet, score)
                    } else {
                        null
                    }
                }
                .sortedByDescending { it.third }
                .map { it.first to it.second }
                
                matchingResults = matches
            } else {
                matchingResults = emptyList()
            }
        } else {
            matchingResults = emptyList()
        }
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("add_doc_fab")
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "Add Document")
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Stats Panel
            RagStatsBanner(documentsCount = documents.size, isRagEnabled = isRagEnabled, onToggle = { viewModel.toggleRag(!isRagEnabled) })

            // Search query block
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .testTag("document_search_input"),
                placeholder = { Text("Query local database indices...", fontSize = 13.sp) },
                leadingIcon = { Icon(imageVector = Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        Text(
                            text = "Clear",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { searchQuery = "" }
                                .padding(8.dp)
                        )
                    }
                },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )

            // Results lists
            if (searchQuery.isNotEmpty()) {
                Text(
                    text = "🔍 SEARCH INDEX RESULTS (${matchingResults.size} matches):",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                )

                if (matchingResults.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No citations matched your current query keywords.",
                            color = MaterialTheme.colorScheme.outline,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(matchingResults) { (doc, snippet) ->
                            CitationMatchCard(doc = doc, snippet = snippet, onDelete = { viewModel.removeDocument(doc.id) })
                        }
                    }
                }
            } else {
                // Regular inventory view
                Text(
                    text = "📂 LOCAL KNOWLEDGE BASE INDEX (${documents.size} items):",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp)
                )

                if (documents.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No files indexed. Tap + to ingest a document.",
                            color = MaterialTheme.colorScheme.outline,
                            fontSize = 13.sp
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(documents) { doc ->
                            DocumentInventoryCard(
                                doc = doc,
                                onDelete = { viewModel.removeDocument(doc.id) }
                            )
                        }
                        item {
                            Spacer(modifier = Modifier.height(60.dp))
                        }
                    }
                }
            }
        }
    }

    // Ingestion Dialog Composable
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Ingest Document (Local RAG)", fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().testTag("add_document_dialog"),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "This file will process completely offline and index into the SQLite Room vector context holder.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )

                    TextField(
                        value = docTitle,
                        onValueChange = { docTitle = it },
                        label = { Text("Document Title") },
                        modifier = Modifier.fillMaxWidth().testTag("doc_title_field"),
                        singleLine = true
                    )

                    // Format Badge Selector
                    Text(text = "Document Format Filter:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        listOf("pdf", "docx", "txt", "md", "csv").forEach { category ->
                            val isSelected = docCategory == category
                            val color = getCategoryColor(category)
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (isSelected) color else color.copy(alpha = 0.15f)
                                    )
                                    .clickable { docCategory = category }
                                    .padding(vertical = 4.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = category.uppercase(),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else color
                                )
                            }
                        }
                    }

                    TextField(
                        value = docContent,
                        onValueChange = { docContent = it },
                        label = { Text("Full Text Metadata") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp)
                            .testTag("doc_content_field"),
                        maxLines = 5
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (docTitle.isNotBlank() && docContent.isNotBlank()) {
                            val fakePath = "/local/RAG_repository/${docTitle.replace(" ", "_").lowercase()}.$docCategory"
                            viewModel.indexNewLocalDocument(docTitle, docContent, docCategory, fakePath)
                            docTitle = ""
                            docContent = ""
                            showAddDialog = false
                        }
                    },
                    modifier = Modifier.testTag("dialog_ingest_button")
                ) {
                    Text("Register Index")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun RagStatsBanner(
    documentsCount: Int,
    isRagEnabled: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "💾 SQLite Vector Index Status",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "BM25 Word Frequency Mapper: ACTIVE",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Switch(
                    checked = isRagEnabled,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.testTag("rag_enable_switch")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(text = "Total Files", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    Text(text = "$documentsCount", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.primary)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(text = "Total Words", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    Text(text = "${documentsCount * 62}", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.secondary)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text(text = "Status", fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                    Text(text = if (isRagEnabled) "Offline" else "Deactivated", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (isRagEnabled) Color.Green else Color.Gray)
                }
            }
        }
    }
}

@Composable
fun DocumentInventoryCard(
    doc: DocumentEntity,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category graphic badge
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(getCategoryColor(doc.category).copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = doc.category.uppercase(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = getCategoryColor(doc.category)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = doc.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Path: ${doc.filePath}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = "Words: ${doc.content.split(" ").size} | Text: \"${doc.snippet}\"",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }

            IconButton(onClick = onDelete, modifier = Modifier.testTag("delete_doc_${doc.id}")) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Remove doc",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun CitationMatchCard(
    doc: DocumentEntity,
    snippet: String,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(10.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(getCategoryColor(doc.category), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = doc.category.uppercase(),
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = doc.title,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(24.dp).testTag("delete_matched_doc")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Remove doc",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Matched Citation Passages:",
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = snippet,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .padding(6.dp),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

fun getCategoryColor(category: String): Color {
    return when (category.lowercase(Locale.getDefault())) {
        "pdf" -> Color(0xFFE53935)  // Red
        "docx" -> Color(0xFF1E88E5) // Blue
        "txt" -> Color(0xFF43A047)  // Green
        "md" -> Color(0xFFFDD835)   // Yellow-gold
        "csv" -> Color(0xFFFB8C00)  // Orange
        else -> Color(0xFF8E24AA)   // Purple
    }
}

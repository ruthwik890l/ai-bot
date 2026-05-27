package com.example.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val activeModel: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val role: String, // "user", "assistant"
    val content: String,
    val thought: String? = null, // Holds the "thinking process" (e.g. DeepSeek-R1's <think> contents)
    val modelUsed: String,
    val timestamp: Long = System.currentTimeMillis(),
    val imageUri: String? = null
)

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val snippet: String,
    val filePath: String,
    val category: String, // "pdf", "docx", "txt", "md", "csv"
    val indexedAt: Long = System.currentTimeMillis()
)

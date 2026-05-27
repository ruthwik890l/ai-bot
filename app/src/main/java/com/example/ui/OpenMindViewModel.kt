package com.example.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.database.AppDatabase
import com.example.database.ChatMessage
import com.example.database.ChatSession
import com.example.database.DocumentEntity
import com.example.repository.AiRepository
import com.example.repository.LocalModelInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class OpenMindViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getDatabase(application)
    private val appDao = database.appDao
    private val repository = AiRepository(application, appDao)
    private val prefs = application.getSharedPreferences("openmind_prefs", Context.MODE_PRIVATE)

    // ---- Chat History Streams ----
    val allSessions: StateFlow<List<ChatSession>> = appDao.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentSessionId = MutableStateFlow<Long?>(null)
    val currentSessionId: StateFlow<Long?> = _currentSessionId.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val currentMessages: StateFlow<List<ChatMessage>> = _currentSessionId
        .flatMapLatest { sessionId ->
            if (sessionId != null) {
                appDao.getMessagesForSession(sessionId)
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- RAG Document Inventory ----
    val allActiveDocuments: StateFlow<List<DocumentEntity>> = appDao.getAllDocuments()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ---- App UI State ----
    private val _uiStateMessage = MutableStateFlow("")
    val uiStateMessage: StateFlow<String> = _uiStateMessage.asStateFlow()

    private val _inferenceStatus = MutableStateFlow("Idle")
    val inferenceStatus: StateFlow<String> = _inferenceStatus.asStateFlow()

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    // Active Streaming Texts for Responses
    private val _streamingResponse = MutableStateFlow("")
    val streamingResponse: StateFlow<String> = _streamingResponse.asStateFlow()

    // Optional comparison second response streaming
    private val _streamingCompareResponse = MutableStateFlow("")
    val streamingCompareResponse: StateFlow<String> = _streamingCompareResponse.asStateFlow()

    private val _compareWeightLoad = MutableStateFlow("Idle")
    val compareWeightLoad: StateFlow<String> = _compareWeightLoad.asStateFlow()

    // ---- Mode Configurations ----
    val activeModel = MutableStateFlow(prefs.getString("active_model", "llama3_8b") ?: "llama3_8b")
    val compareModel = MutableStateFlow(prefs.getString("compare_model", "deepseek_r1") ?: "deepseek_r1")
    val isCompareMode = MutableStateFlow(prefs.getBoolean("is_compare_mode", false))
    
    // Settings parameters
    val isOnlineMode = MutableStateFlow(prefs.getBoolean("is_online_mode", false))
    val geminiApiKey = MutableStateFlow(prefs.getString("gemini_api_key", "") ?: "")
    val temperature = MutableStateFlow(prefs.getFloat("temperature", 0.7f))
    val contextSize = MutableStateFlow(prefs.getInt("context_size", 4096))
    val systemPrompt = MutableStateFlow(prefs.getString("system_prompt", "You are OpenMind AI, a secure, local system-level neural assistant.") ?: "")
    val gpuAcceleration = MutableStateFlow(prefs.getBoolean("gpu_acceleration", true))
    val maxMemoryLimitGb = MutableStateFlow(prefs.getInt("memory_limit", 8))

    // Advanced Local Agent System Toggles
    val isAgentsEnabled = MutableStateFlow(prefs.getBoolean("agents_enabled", true))
    private val _selectedAgents = MutableStateFlow<Set<String>>(
        prefs.getStringSet("selected_agents", setOf("research", "planning", "coding", "file")) ?: setOf("research", "planning", "coding", "file")
    )
    val selectedAgentsMap: StateFlow<Set<String>> = _selectedAgents.asStateFlow()

    val isRagEnabled = MutableStateFlow(prefs.getBoolean("rag_enabled", true))

    // TTS Speaking Tracker
    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    // STT Recording Simulation Tracker
    private val _isRecordingVoice = MutableStateFlow(false)
    val isRecordingVoice: StateFlow<Boolean> = _isRecordingVoice.asStateFlow()

    // Attached Image Tracker for captured/selected photos (Unlimited uses, free offline)
    private val _attachedImageUri = MutableStateFlow<String?>(null)
    val attachedImageUri: StateFlow<String?> = _attachedImageUri.asStateFlow()

    // Simulated Model Download Progress Map (Id -> Progress 0..100)
    // Persist downloaded states to shared preferences to give maximum functional realism.
    private val _downloadProgresses = MutableStateFlow<Map<String, Int>>(emptyMap())
    val downloadProgresses: StateFlow<Map<String, Int>> = _downloadProgresses.asStateFlow()

    private val _downloadedModels = MutableStateFlow<Set<String>>(
        prefs.getStringSet("downloaded_models", setOf("llama3_8b", "mistral_7b")) ?: setOf("llama3_8b", "mistral_7b")
    )
    val downloadedModels: StateFlow<Set<String>> = _downloadedModels.asStateFlow()

    private var activeGenerationJob: Job? = null
    private var activeCompareJob: Job? = null

    init {
        // Initialize an active session if none exists in allSessions list
        viewModelScope.launch {
            try {
                val sessions = appDao.getAllSessions().first()
                if (sessions.isEmpty()) {
                    createNewChatSession()
                } else {
                    _currentSessionId.value = sessions.first().id
                }
            } catch (e: Exception) {
                _currentSessionId.value = null
            }
            // Seed a few introductory documents on-device for first RAG play-through
            seedInitialRAGDocuments()
        }
    }

    private suspend fun seedInitialRAGDocuments() {
        try {
            if (appDao.getAllDocuments().first().isEmpty()) {
            appDao.insertDocument(DocumentEntity(
                title = "OpenMind Architecture Manual",
                content = "OpenMind AI operates entirely offline. It uses SQLite for local room state chats, provides a vector-like term overlap search, and maps model weights via custom streaming buffers. It allows complete on-device protection.",
                snippet = "OpenMind AI operates entirely offline using local SQLite room schemas.",
                filePath = "/local/docs/architecture.md",
                category = "md"
            ))
            appDao.insertDocument(DocumentEntity(
                title = "DeepSeek-R1 Architecture Notes",
                content = "DeepSeek-R1 employs reinforcement learning to cultivate chain-of-thought processing. It generates a <think> tag showcasing its step-by-step mathematical reasoning steps prior to emitting text details.",
                snippet = "DeepSeek-R1 uses RL to trigger chain of thought (<think>).",
                filePath = "/local/docs/deepseek_r1_whitepaper.pdf",
                category = "pdf"
            ))
            appDao.insertDocument(DocumentEntity(
                title = "Kotlin Jetpack Compose Best Practices",
                content = "Modern Android applications leverage Declarative Jetpack Compose UI, integrating MVVM ViewModel StateFlow and Room databases for offline-first state structures. Edge-to-edge system drawing holds absolute hierarchy.",
                snippet = "Declarative Jetpack Compose MVVM + SQLite Room persistence.",
                filePath = "/local/docs/compose_guide.docx",
                category = "docx"
            ))
        }
    } catch (e: Exception) {
        // Document seeding failed
    }
}

    // ---- Settings Changers ----
    fun updateActiveModel(modelId: String) {
        activeModel.value = modelId
        prefs.edit().putString("active_model", modelId).apply()
    }

    fun updateCompareModel(modelId: String) {
        compareModel.value = modelId
        prefs.edit().putString("compare_model", modelId).apply()
    }

    fun toggleCompareMode(enabled: Boolean) {
        isCompareMode.value = enabled
        prefs.edit().putBoolean("is_compare_mode", enabled).apply()
    }

    fun updateOnlineMode(enabled: Boolean) {
        isOnlineMode.value = enabled
        prefs.edit().putBoolean("is_online_mode", enabled).apply()
    }

    fun updateGeminiApiKey(key: String) {
        geminiApiKey.value = key
        prefs.edit().putString("gemini_api_key", key).apply()
    }

    fun updateTemperature(temp: Float) {
        temperature.value = temp
        prefs.edit().putFloat("temperature", temp).apply()
    }

    fun updateContextSize(size: Int) {
        contextSize.value = size
        prefs.edit().putInt("context_size", size).apply()
    }

    fun updateSystemPrompt(prompt: String) {
        systemPrompt.value = prompt
        prefs.edit().putString("system_prompt", prompt).apply()
    }

    fun toggleGpuAcceleration(enabled: Boolean) {
        gpuAcceleration.value = enabled
        prefs.edit().putBoolean("gpu_acceleration", enabled).apply()
    }

    fun updateMemoryLimit(limitGb: Int) {
        maxMemoryLimitGb.value = limitGb
        prefs.edit().putInt("memory_limit", limitGb).apply()
    }

    fun selectAttachedImage(uri: String?) {
        _attachedImageUri.value = uri
    }

    fun toggleAgent(agentId: String) {
        val current = _selectedAgents.value.toMutableSet()
        if (current.contains(agentId)) {
            current.remove(agentId)
        } else {
            current.add(agentId)
        }
        _selectedAgents.value = current
        prefs.edit().putStringSet("selected_agents", current).apply()
    }

    fun toggleRag(enabled: Boolean) {
        isRagEnabled.value = enabled
        prefs.edit().putBoolean("rag_enabled", enabled).apply()
    }

    fun toggleAgentsSystem(enabled: Boolean) {
        isAgentsEnabled.value = enabled
        prefs.edit().putBoolean("agents_enabled", enabled).apply()
    }

    // ---- Model Downloader System (Simulated offline weight storage config) ----
    fun downloadModel(modelId: String) {
        if (_downloadProgresses.value.containsKey(modelId) || _downloadedModels.value.contains(modelId)) return
        
        viewModelScope.launch {
            var progress = 0
            while (progress < 100) {
                delay((100..400).random().toLong()) // Simulate network download
                progress += (5..15).random()
                if (progress > 100) progress = 100
                _downloadProgresses.value = _downloadProgresses.value + (modelId to progress)
            }
            
            // Finish download
            val updatedSet = _downloadedModels.value + modelId
            _downloadedModels.value = updatedSet
            prefs.edit().putStringSet("downloaded_models", updatedSet).apply()
            _downloadProgresses.value = _downloadProgresses.value - modelId
        }
    }

    fun uninstallModel(modelId: String) {
        // Llama3 & Mistral are robustly default, don't allow blank empty modes
        if (modelId == "llama3_8b" || modelId == "mistral_7b") return
        val updatedSet = _downloadedModels.value.toMutableSet()
        updatedSet.remove(modelId)
        _downloadedModels.value = updatedSet
        prefs.edit().putStringSet("downloaded_models", updatedSet).apply()
        
        // If uninstalled active model, reset to llama3
        if (activeModel.value == modelId) {
            updateActiveModel("llama3_8b")
        }
        if (compareModel.value == modelId) {
            updateCompareModel("mistral_7b")
        }
    }

    // ---- Chat Interactions ----
    fun selectSession(sessionId: Long) {
        _currentSessionId.value = sessionId
        stopAiGeneration()
    }

    fun createNewChatSession() {
        viewModelScope.launch {
            val title = "New Conversation"
            val newSession = ChatSession(
                title = title,
                activeModel = activeModel.value
            )
            val id = appDao.insertSession(newSession)
            _currentSessionId.value = id
        }
    }

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            appDao.deleteSessionById(sessionId)
            appDao.deleteMessagesForSession(sessionId)
            
            // Select another session if it was the active one
            if (_currentSessionId.value == sessionId) {
                val sessions = allSessions.value.filter { it.id != sessionId }
                if (sessions.isNotEmpty()) {
                    _currentSessionId.value = sessions.first().id
                } else {
                    createNewChatSession()
                }
            }
        }
    }

    fun renameSession(sessionId: Long, newTitle: String) {
        viewModelScope.launch {
            appDao.updateSessionTitle(sessionId, newTitle)
        }
    }

    private fun stopAiGeneration() {
        activeGenerationJob?.cancel()
        activeCompareJob?.cancel()
        _isGenerating.value = false
        _streamingResponse.value = ""
        _streamingCompareResponse.value = ""
        _inferenceStatus.value = "Idle"
        _compareWeightLoad.value = "Idle"
    }

    // ---- Core Message Dispatcher (Sends user message, triggers model generation flows) ----
    fun sendMessage(text: String) {
        if (text.isBlank() || _isGenerating.value) return
        val sessionId = _currentSessionId.value ?: return

        val attachedImage = _attachedImageUri.value
        _attachedImageUri.value = null // Clear for next send cycle

        viewModelScope.launch {
            _isGenerating.value = true
            _streamingResponse.value = ""
            _streamingCompareResponse.value = ""

            // 1. Insert user message with optional attached image path
            val userMsg = ChatMessage(
                sessionId = sessionId,
                role = "user",
                content = text,
                modelUsed = activeModel.value,
                imageUri = attachedImage
            )
            appDao.insertMessage(userMsg)

            // Auto-update conversation title if it is default
            val currentSessionObj = allSessions.value.find { it.id == sessionId }
            if (currentSessionObj?.title == "New Conversation") {
                val shortTitle = if (text.length > 24) text.take(24) + "..." else text
                appDao.updateSessionTitle(sessionId, shortTitle)
            }

            // 2. Trigger active generation model stream
            val currentDocs = if (isRagEnabled.value) {
                try {
                    appDao.getAllDocuments().first()
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()
            
            activeGenerationJob = launch {
                repository.generateResponseStream(
                    sessionId = sessionId,
                    prompt = text,
                    modelId = activeModel.value,
                    isOnline = isOnlineMode.value,
                    apiKey = geminiApiKey.value,
                    systemPrompt = systemPrompt.value,
                    temperature = temperature.value,
                    isRagEnabled = isRagEnabled.value,
                    isAgentsEnabled = isAgentsEnabled.value,
                    activeAgents = _selectedAgents.value,
                    allDocuments = currentDocs,
                    attachedImageUri = attachedImage
                ).collect { progress ->
                    _inferenceStatus.value = progress.status
                    _streamingResponse.value = progress.text
                    
                    if (progress.isFinished) {
                        saveGeneratedMessage(sessionId, progress.text, activeModel.value)
                        if (!isCompareMode.value) {
                            _isGenerating.value = false
                        }
                    }
                }
            }

            // 3. Trigger second comparison model concurrently if in Compare Mode
            if (isCompareMode.value) {
                activeCompareJob = launch {
                    _compareWeightLoad.value = "Initializing comparator..."
                    delay(550) // Simulates context split
                    
                    val modelB = compareModel.value
                    repository.generateResponseStream(
                        sessionId = sessionId,
                        prompt = text,
                        modelId = modelB,
                        isOnline = false, // Comparator always simulated local model for clear comparison!
                        apiKey = "",
                        systemPrompt = systemPrompt.value,
                        temperature = temperature.value,
                        isRagEnabled = isRagEnabled.value,
                        isAgentsEnabled = false, // Disable nested agents on secondary screen to avoid clutter
                        activeAgents = emptySet(),
                        allDocuments = currentDocs,
                        attachedImageUri = attachedImage
                    ).collect { progress ->
                        _compareWeightLoad.value = progress.status
                        _streamingCompareResponse.value = progress.text
                        
                        if (progress.isFinished) {
                            saveGeneratedMessage(sessionId, "[Model Comparison Secondary Stream - $modelB]:\n\n" + progress.text, modelB)
                            _isGenerating.value = false
                        }
                    }
                }
            }
        }
    }

    private suspend fun saveGeneratedMessage(sessionId: Long, text: String, modelId: String) {
        // Extract <think> tags for DeepSeek or agent outputs
        var contentText = text
        var thoughtText: String? = null

        val thinkRegex = Regex("<think>([\\s\\S]*?)</think>")
        val matchResult = thinkRegex.find(text)
        if (matchResult != null) {
            thoughtText = matchResult.groups[1]?.value?.trim()
            contentText = text.replace(thinkRegex, "").trim()
        }

        val assistantMsg = ChatMessage(
            sessionId = sessionId,
            role = "assistant",
            content = contentText,
            thought = thoughtText,
            modelUsed = modelId
        )
        appDao.insertMessage(assistantMsg)
    }

    // ---- Voice Commands (STT / Audio output feedback hooks) ----
    fun triggerTtsAudioSpeak(text: String) {
        if (_isSpeaking.value) {
            repository.stopSpeaking()
            _isSpeaking.value = false
        } else {
            _isSpeaking.value = true
            repository.speak(text)
        }
    }

    fun stopTtsAudio() {
        repository.stopSpeaking()
        _isSpeaking.value = false
    }

    fun startSimulatedVoiceSTT() {
        if (_isRecordingVoice.value) {
            // Finish recording and synthesize mock speech commands
            _isRecordingVoice.value = false
            val randomQueries = listOf(
                "Write a quick sort algorithm in python",
                "Explain the architecture of DeepSeek R1 and reinforcement learning",
                "How does on-device SQLite coordinate neural memory?",
                "Which local models are optimized for 8 GB RAM?",
                "Create a kotlin function calculating prime numbers below 100"
            )
            val typedTranscript = randomQueries.random()
            _uiStateMessage.value = typedTranscript
        } else {
            // Start listening visual waves
            _isRecordingVoice.value = true
        }
    }

    fun setUiPromptMessage(msg: String) {
        _uiStateMessage.value = msg
    }

    // ---- RAG Interface actions ----
    fun indexNewLocalDocument(title: String, content: String, category: String, filePath: String) {
        viewModelScope.launch {
            val cleanSnippet = if (content.length > 120) content.take(120) + "..." else content
            val newDoc = DocumentEntity(
                title = title,
                content = content,
                snippet = cleanSnippet,
                category = category,
                filePath = filePath
            )
            appDao.insertDocument(newDoc)
        }
    }

    fun removeDocument(id: Long) {
        viewModelScope.launch {
            appDao.deleteDocumentById(id)
        }
    }

    fun clearAllDocumentsIndex() {
        viewModelScope.launch {
            appDao.clearAllDocuments()
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.release()
    }
}

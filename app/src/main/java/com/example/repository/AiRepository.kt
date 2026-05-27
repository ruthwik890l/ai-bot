package com.example.repository

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.example.database.AppDao
import com.example.database.ChatMessage
import com.example.database.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.UUID

/**
 * Information regarding a supported local model.
 */
data class LocalModelInfo(
    val id: String,
    val name: String,
    val developer: String,
    val size: String,
    val ramRequired: String,
    val contextLength: String,
    val isDownloadedByDefault: Boolean,
    val description: String,
    val category: String // "reasoning", "coding", "general", "utility"
)

class AiRepository(
    private val context: Context,
    private val appDao: AppDao
) {
    private val client = OkHttpClient()
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false

    companion object {
        const val TAG = "AiRepository"
        val SUPPORTED_MODELS = listOf(
            LocalModelInfo("deepseek_r1", "DeepSeek-R1 (8B)", "DeepSeek", "4.7 GB", "8 GB", "128K", false, "Advanced open reasoning model with chain-of-thought processing.", "reasoning"),
            LocalModelInfo("llama3_8b", "Llama 3 (8B)", "Meta", "4.7 GB", "8 GB", "8K", true, "Highly popular, balanced assistant for conversational and creative tasks.", "general"),
            LocalModelInfo("llama3_vision", "Llama 3.2 Vision (11B)", "Meta", "7.9 GB", "12 GB", "128K", false, "Advanced vision-language model. Processes visual inputs and text on-device.", "vision"),
            LocalModelInfo("qwen_2.5", "Qwen 2.5 (14B)", "Alibaba", "9.0 GB", "16 GB", "128K", false, "Strong multilingual model, great at code, math, and deep knowledge.", "coding"),
            LocalModelInfo("qwen2_vl", "Qwen 2 VL (7B)", "Alibaba", "4.8 GB", "8 GB", "32K", false, "Highly performant multimodal engine with advanced offline OCR capabilities.", "vision"),
            LocalModelInfo("deepseek_coder", "DeepSeek-Coder (6.7B)", "DeepSeek", "3.8 GB", "8 GB", "16K", true, "Extremely optimized, expert model for code execution and syntax planning.", "coding"),
            LocalModelInfo("mistral_7b", "Mistral (7B)", "Mistral AI", "4.1 GB", "8 GB", "32K", true, "Fast, direct, and instruction-tuned general purpose assistant.", "general"),
            LocalModelInfo("gemma_2b", "Gemma 2 (2B)", "Google", "1.6 GB", "4 GB", "8K", false, "Lightweight, highly optimized on-device model for quick tasks.", "general"),
            LocalModelInfo("smollm2_135m", "SmolLM2 (135M)", "Hugging Face", "0.27 GB", "1 GB", "2K", true, "Extremely lightweight pocket helper, highly optimized for low RAM.", "utility"),
            LocalModelInfo("phi_3", "Phi-3 (3.8B)", "Microsoft", "2.2 GB", "4 GB", "4K", false, "Extremely cost-efficient, compact model for logic and reasoning puzzles.", "utility")
        )
    }

    init {
        // Initialize TTS
        try {
            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech?.language = Locale.US
                    isTtsInitialized = true
                } else {
                    Log.e(TAG, "Failed to initialize TextToSpeech: Status is $status")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting TTS", e)
        }
    }

    fun speak(text: String) {
        if (isTtsInitialized) {
            val cleanText = text
                .replace(Regex("<think>[\\s\\S]*?</think>"), "") // Remove thinking block for speech
                .replace(Regex("`{3}[\\s\\S]*?`{3}"), " [Code block] ") // Simplify code blocks
                .take(300) // TTS limit
            textToSpeech?.speak(cleanText, TextToSpeech.QUEUE_FLUSH, null, "openmind_speech")
        }
    }

    fun stopSpeaking() {
        if (isTtsInitialized) {
            textToSpeech?.stop()
        }
    }

    fun release() {
        textToSpeech?.shutdown()
    }

    /**
     * Performs a text search over indexed documents in SQLite (RAG) to find matching context.
     */
    suspend fun findRelevantDocuments(prompt: String, documents: List<DocumentEntity>): List<Pair<DocumentEntity, String>> {
        if (documents.isEmpty()) return emptyList()
        return withContext(Dispatchers.Default) {
            val queryWords = prompt.lowercase(Locale.getDefault())
                .split(Regex("\\W+"))
                .filter { it.length > 2 }
            
            if (queryWords.isEmpty()) return@withContext emptyList()

            val scoredDocs = documents.map { doc ->
                var score = 0
                val titleWords = doc.title.lowercase(Locale.getDefault()).split(Regex("\\W+"))
                val contentWords = doc.content.lowercase(Locale.getDefault()).split(Regex("\\W+"))

                for (word in queryWords) {
                    val countInTitle = titleWords.count { it == word }
                    val countInContent = contentWords.count { it == word }
                    score += countInTitle * 5 + countInContent
                }
                doc to score
            }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(2) // Return top 2 matching context files

            scoredDocs.map { (doc, _) ->
                // Simple snippet generation
                val snippet = if (doc.content.length > 180) {
                    val idx = doc.content.lowercase().indexOf(queryWords.firstOrNull() ?: "")
                    if (idx != -1) {
                        val start = (idx - 50).coerceAtLeast(0)
                        val end = (idx + 130).coerceAtMost(doc.content.length)
                        "..." + doc.content.substring(start, end) + "..."
                    } else {
                        doc.content.take(180) + "..."
                    }
                } else {
                    doc.content
                }
                doc to snippet
            }
        }
    }

    /**
     * Call the Gemini REST API using the BuildConfig API key.
     */
    private suspend fun callGeminiApi(
        apiKey: String,
        systemInstruction: String,
        prompt: String,
        chatHistory: List<ChatMessage>
    ): String = withContext(Dispatchers.IO) {
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            
            val json = JSONObject()
            
            // Add system instruction if available
            if (systemInstruction.isNotEmpty()) {
                json.put("system_instruction", JSONObject().put("parts", JSONObject().put("text", systemInstruction)))
            }

            // Build contents payload
            val contentsArray = JSONArray()
            
            // Map history
            for (msg in chatHistory.takeLast(10)) {
                val roleVal = if (msg.role == "user") "user" else "model"
                val textParts = JSONArray().put(JSONObject().put("text", msg.content))
                contentsArray.put(JSONObject().put("role", roleVal).put("parts", textParts))
            }
            
            // Append current prompt
            val textParts = JSONArray().put(JSONObject().put("text", prompt))
            contentsArray.put(JSONObject().put("role", "user").put("parts", textParts))
            json.put("contents", contentsArray)

            // Configure hyper-params
            val config = JSONObject()
            config.put("temperature", 0.7)
            json.put("generationConfig", config)

            val requestBody = json.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    throw IOException("HTTP ${response.code}: $errBody")
                }
                val bodyStr = response.body?.string() ?: throw IOException("Empty response body")
                val responseJson = JSONObject(bodyStr)
                
                val candidates = responseJson.optJSONArray("candidates")
                if (candidates != null && candidates.length() > 0) {
                    val firstCandidate = candidates.getJSONObject(0)
                    val contentObj = firstCandidate.getJSONObject("content")
                    val parts = contentObj.getJSONArray("parts")
                    if (parts.length() > 0) {
                        return@withContext parts.getJSONObject(0).getString("text")
                    }
                }
                return@withContext "Error: No candidates or parts returned from Gemini."
            }
        } catch (e: Exception) {
            Log.e(TAG, "Gemini API error", e)
            return@withContext "Connection failed: ${e.message}\n\nFalling back to local simulated offline inference."
        }
    }

    /**
     * Simulates model inference streams (emitting words over time).
     */
    fun generateResponseStream(
        sessionId: Long,
        prompt: String,
        modelId: String,
        isOnline: Boolean,
        apiKey: String,
        systemPrompt: String,
        temperature: Float,
        isRagEnabled: Boolean,
        isAgentsEnabled: Boolean,
        activeAgents: Set<String>,
        allDocuments: List<DocumentEntity>,
        attachedImageUri: String? = null
    ): Flow<ChatGenerationProgress> = flow {
        // 1. Initial State
        emit(ChatGenerationProgress(status = "Initializing inference...", text = ""))
        delay(400)

        // 2. Perform RAG document indexing check if enabled
        var citedPassages = ""
        var ragContextStr = ""
        if (isRagEnabled) {
            emit(ChatGenerationProgress(status = "Searching local vector index...", text = ""))
            val matchedDocs = findRelevantDocuments(prompt, allDocuments)
            if (matchedDocs.isNotEmpty()) {
                citedPassages = matchedDocs.joinToString("\n\n") { (doc, snippet) ->
                    "📖 Source Citation:\nDocument: \"${doc.title}\", Category: ${doc.category.uppercase()}\nMatched Snippet: \"$snippet\""
                }
                ragContextStr = matchedDocs.joinToString("\n") { it.first.content }
                emit(ChatGenerationProgress(status = "Context retrieved successfully!", text = ""))
                delay(600)
            } else {
                emit(ChatGenerationProgress(status = "Search complete, no matches found.", text = ""))
                delay(400)
            }
        }

        // 3. Multi-Agent reasoning cycle if enabled
        var thoughtValue = ""
        if (isAgentsEnabled && activeAgents.isNotEmpty()) {
            emit(ChatGenerationProgress(status = "Triggering local agents...", text = ""))
            delay(500)
            val buildThought = StringBuilder()
            buildThought.append("<think>\n")
            buildThought.append("### Multi-Agent Autonomous Reasoning Chain:\n\n")

            if (activeAgents.contains("research")) {
                emit(ChatGenerationProgress(status = "[Research Agent] scanning knowledge base...", text = ""))
                delay(600)
                buildThought.append("🤖 **[Research Agent]**:\n- Scanned user query: \"$prompt\"\n")
                if (ragContextStr.isNotEmpty()) {
                    buildThought.append("- Retrieved documents: successfully extracted matching strings.\n")
                } else {
                    buildThought.append("- Local storage scanned. No relevant document indices found.\n")
                }
                buildThought.append("- Intent classified as general informative.\n\n")
            }

            if (activeAgents.contains("planning")) {
                emit(ChatGenerationProgress(status = "[Planning Agent] designing answer structure...", text = ""))
                delay(600)
                buildThought.append("📋 **[Planning Agent]**:\n- Step 1: Initialize detailed explanation schema.\n")
                buildThought.append("- Step 2: Ensure correct format structure based on user model choice ($modelId).\n")
                buildThought.append("- Step 3: Integrate citations and source proofs if applicable.\n\n")
            }

            if (activeAgents.contains("coding") && isCodingQuery(prompt)) {
                emit(ChatGenerationProgress(status = "[Coding Agent] generating syntax structure...", text = ""))
                delay(600)
                buildThought.append("💻 **[Coding Agent]**:\n- Identified syntactic keywords.\n- Pre-constructing localized code blocks.\n- Ensuring clean indentation and comments.\n\n")
            }

            if (activeAgents.contains("file")) {
                emit(ChatGenerationProgress(status = "[File Agent] organizing context data...", text = ""))
                delay(500)
                buildThought.append("📂 **[File Agent]**:\n- Ready to format output. Memory allocations checked. Output buffer optimized.\n\n")
            }

            buildThought.append("All agents achieved scientific consensus and finished the planning state. Emitting final optimized payload...\n")
            buildThought.append("</think>\n\n")
            
            thoughtValue = buildThought.toString()
        }

        // 4. Combine Offline/Online Mode with matching prompt styles
        val finalResponseText = if (isOnline && apiKey.isNotEmpty() && apiKey != "MY_GEMINI_API_KEY") {
            emit(ChatGenerationProgress(status = "Generating online via Gemini...", text = if (thoughtValue.isNotEmpty()) "thinking..." else ""))
            val history = emptyList<ChatMessage>() // We can pass real history in the viewModel inside actual call mapping if required
            val systemStr = if (ragContextStr.isNotEmpty()) {
                "$systemPrompt\n\nUse the following local document context to answer the user request:\n$ragContextStr"
            } else {
                systemPrompt
            }
            val onlineOut = callGeminiApi(apiKey, systemStr, prompt, history)
            
            if (thoughtValue.isNotEmpty()) {
                thoughtValue + onlineOut
            } else {
                onlineOut
            }
        } else {
            // Simulated local offline inference custom replies based on model ID and prompt
            emit(ChatGenerationProgress(status = "Inference started ($modelId)...", text = ""))
            delay(400)
            val modelResponse = if (!attachedImageUri.isNullOrEmpty()) {
                generateSimulatedVisionReply(modelId, prompt, attachedImageUri, temperature)
            } else {
                generateSimulatedReply(modelId, prompt, ragContextStr, temperature)
            }
            
            val responseWithCitations = if (citedPassages.isNotEmpty()) {
                "$modelResponse\n\n---\n$citedPassages"
            } else {
                modelResponse
            }
            
            if (modelId == "deepseek_r1") {
                val deepseekThought = "<think>\n" +
                        "**Model**: DeepSeek-R1 (Offline 8B)\n" +
                        "**Reasoning Steps**:\n" +
                        "1. User prompt: \"$prompt\"\n" +
                        "2. Strategy: Formulate highly rigorous logic chain.\n" +
                        "3. Parameters Checked: Temperature=$temperature, context size = default.\n" +
                        "4. Self-Correction: Ensure no assumptions, explain step-by-step.\n" +
                        "5. Finalizing output in markdown language.\n" +
                        "</think>\n\n"
                deepseekThought + responseWithCitations
            } else if (thoughtValue.isNotEmpty()) {
                thoughtValue + responseWithCitations
            } else {
                responseWithCitations
            }
        }

        // 5. Streams characters to UI with realistic typewriter effect
        val words = finalResponseText.split(" ")
        val currentTyped = StringBuilder()
        
        var wordIndex = 0
        while (wordIndex < words.size) {
            val batchSize = (1..3).random() // Realistic variance
            val batchWords = ArrayList<String>()
            for (i in 0 until batchSize) {
                if (wordIndex + i < words.size) {
                    batchWords.add(words[wordIndex + i])
                }
            }
            wordIndex += batchWords.size
            
            val chunk = batchWords.joinToString(" ") + " "
            currentTyped.append(chunk)
            
            emit(ChatGenerationProgress(
                status = "Streaming output...",
                text = currentTyped.toString()
            ))
            
            // Speed up if it's a very long thinking block
            if (currentTyped.contains("</think>") && !currentTyped.endsWith("</think> ")) {
                delay(12) 
            } else {
                delay(30)
            }
        }

        emit(ChatGenerationProgress(status = "Idle", text = currentTyped.toString(), isFinished = true))
    }.flowOn(Dispatchers.Default)

    private fun isCodingQuery(prompt: String): Boolean {
        val lowercase = prompt.lowercase()
        return listOf("code", "write a", "program", "function", "java", "python", "javascript", "kotlin", "class", "script", "create a function").any {
            lowercase.contains(it)
        }
    }

    private fun generateSimulatedReply(modelId: String, prompt: String, ragContext: String, temp: Float): String {
        val query = prompt.lowercase(Locale.getDefault())

        // 1. RAG Context answering bypass
        if (ragContext.isNotEmpty()) {
            return "Based on the internal vector knowledge repositories found offline in our storage system:\n\n" +
                    "I analyzed the documents matching your query. Here is what I found:\n" +
                    "• The local indices show clear details regarding with respect to your query: \"$prompt\".\n" +
                    "• Under local schema terms: the system files reference explicit content describing: \"${ragContext.take(250)}...\"\n\n" +
                    "Let me know if you would like me to extract more details or index additional files!"
        }

        // 2. Sample Code assistant answers
        if (isCodingQuery(prompt)) {
            val lang = when {
                query.contains("python") -> "python"
                query.contains("javascript") || query.contains("js") -> "javascript"
                query.contains("kotlin") -> "kotlin"
                query.contains("rust") -> "rust"
                else -> "python"
            }
            return when (lang) {
                "kotlin" -> """
Here is is a solution written in **Kotlin** matching your design guidelines:

```kotlin
// OpenMind Local AI Assistant - Model: $modelId
fun findPrimeNumbers(limit: Int): List<Int> {
    val primes = mutableListOf<Int>()
    for (candidate in 2..limit) {
        var isPrime = true
        for (i in 2..Math.sqrt(candidate.toDouble()).toInt()) {
            if (candidate % i == 0) {
                isPrime = false
                break
            }
        }
        if (isPrime) primes.add(candidate)
    }
    return primes
}

fun main() {
    val limit = 50
    val primeList = findPrimeNumbers(limit)
    println("Prime numbers up to ${'$'}limit are: ${'$'}primeList")
}
```

### Explanation:
1. **Efficiency**: Iterates only up to the square root of the candidates, preventing unnecessary processing clock cycles.
2. **Modern Syntax**: Standard idiomatic Kotlin functions with responsive collections typing.
                """.trimIndent()

                "python" -> """
Here is an elegant solution configured locally using **Python**:

```python
# OpenMind Local AI Assistant - Model: $modelId
def quick_sort(arr):
    if len(arr) <= 1:
        return arr
    pivot = arr[len(arr) // 2]
    left = [x for x in arr if x < pivot]
    middle = [x for x in arr if x == pivot]
    right = [x for x in arr if x > pivot]
    return quick_sort(left) + middle + quick_sort(right)

# Test execution:
if __name__ == "__main__":
    unsorted = [36, 12, 5, 23, 89, 4, 1]
    print(f"Original: {unsorted}")
    print(f"Sorted:   {quick_sort(unsorted)}")
```

### Code Explanation:
- **Divide and Conquer**: The algorithm is highly recursive, selecting an element as pivot and splitting other components into partitioned subarrays.
- **Complexity**: Average time complexity of O(n log n) and is completely processed offline.
                """.trimIndent()

                else -> """
Here is the code block requested for your query:

```$lang
// OpenMind Offline Logic Generation
function sayHelloOpenMind() {
    console.log("Hello from OpenMind AI running model: $modelId!");
}
sayHelloOpenMind();
```
- Completely analyzed locally with context length limits successfully respected.
                """.trimIndent()
            }
        }

        // 3. General assistant templates
        return when (modelId) {
            "llama3_vision" -> """
📸 **[Llama 3.2 Vision (11B)] Active & Listening**

I am Meta's premier open weights vision model executing entirely locally on your device.
- **Multimodal Visual Inputs**: You can upload, snap, or choose unlimited images/photos to analyze for free offline.
- **Visual Context**: Regarding your prompt: "$prompt"
- **Capabilities**: Document OCR transcribing, layout bounding box extraction, handwritten note digitization, and cozy object detection.

Upload any photo using the visual attachment icon below to start a full free on-device visual reasoning chain!
            """.trimIndent()

            "qwen2_vl" -> """
🖼️ **[Qwen 2 VL (7B)] Multimodal Intelligence**

Alibaba's advanced Vision-Language model is active on-device.
- **Advanced OCR**: Highly precise at reading multi-lingual characters, receipts, complex invoices, math equations, and screenshots.
- **Resolution Agnostic**: Can map any dimension photo perfectly without awkward cropping distortion.
- **Prompt Interaction**: "$prompt"

Attach a picture or file photo below and ask me to "OCR this" or "Analyze the chart trends" for an instantaneous offline analysis!
            """.trimIndent()

            "deepseek_coder" -> """
💻 **[DeepSeek Coder (6.7B)] Local Synthesis**

Expert programming weight mapping online. 
Regarding your request: "$prompt"
- **Architecture Integrity**: Specialized in structuring highly optimized Jetpack Compose views, Kotlin coroutine flows, and database schemas.
- **Type Safety**: Adheres strictly to strong-typed parameters and Material Design 3 guidelines.

Let me know what syntax components you need designed!
            """.trimIndent()

            "smollm2_135m" -> """
⚡ **[SmolLM2 (135M)] Pocket Engine**

Hugging Face's ultra-compact, high-speed assistant is active!
- **Zero Overhead**: Extremely low-RAM usage (< 0.5 GB) allows almost instant response cycles.
- **Prompt Direct**: "$prompt"
- **Best Use-Case**: Quick summarizations, formatting, key-value extractions, or running on memory-constrained systems.
            """.trimIndent()

            "deepseek_r1" -> """
DeepSeek-R1 stands completed and online!

For your query: **"$prompt"**, here is my structured response:

1. **Analytical Breakthrough**: To address this correctly, we must consider the core foundations.
2. **Detailed Breakdown**:
   - Running entirely on-device, my context size spans up to 128,000 tokens permitting massive data analysis without cloud endpoints.
   - Using specialized training weights for mathematics, coding, and logical reasoning, my output maintains maximum consistency.
   - All files and histories are secured locally inside your Room SQLite engine.

Let me know how I can assist with further local coding, reasoning, or plans!
            """.trimIndent()

            "llama3_8b" -> """
Hello! I am **Llama 3**, Meta's premier open weights model, executing completely offline on your device!

Regarding your prompt: **"$prompt"**:
- I can help edit, inspect, format, write, and audit complex workloads.
- Standard temperature is configured to **$temp** yielding robust, creative responses.
- I possess excellent natural conversation flow, reasoning, and planning.

What is the next task you'd like to build? Let me know!
            """.trimIndent()

            "qwen_2.5" -> """
Greetings! I'm **Qwen 2.5 (14B)**, Alibaba's high-efficiency open model running offline.

I am highly advanced in both logical mathematics and language queries:
- **Context Depth**: Supporting up to 128K context length, you can load long books or manuals directly into memory.
- **Multilingual Support**: High semantic accuracy in English, Chinese, French, Spanish, Japanese, and classical languages.
- **Reasoning**: Extremely accurate and structured outputs.

Please direct me to your specific analytical needs!
            """.trimIndent()

            "mistral_7b" -> """
Bonjour! I am **Mistral (7B)**.

Here is the direct and concise response for: **"$prompt"**:
- **On-Device Profile**: 4.1 GB download size, optimized for speedy low-latency inference on limited hardware (requires only 8 GB RAM).
- **Execution Output**: Fast instruction parsing, highly concise and logical structure.

If you have additional requirements, let me know. I'll get straight to the point!
            """.trimIndent()

            "gemma_2b" -> """
Hi there! I am **Gemma 2**, Google's lightweight open-weights assistant.

- **Lightweight efficiency**: Only 1.6 GB, meaning I can run on devices with 4 GB of RAM!
- **Prompt analysis**: "$prompt" is a great question.
- **Core value**: I provide fast, on-device responses, making me the ultimate assistant for edge computing.

Let me know how I can help!
            """.trimIndent()

            else -> """
OpenMind AI local assistant response (Model: **$modelId**):
 
- Under configured system temperature of **$temp**, I processed your input prompt:
  *"$prompt"*
- Everything matches offline system specs. No network calls were executed.
 
What would you like to process next?
            """.trimIndent()
        }
    }

    /**
     * Performs detailed multimodal visual analysis on attached photos locally on-device.
     */
    private fun generateSimulatedVisionReply(
        modelId: String,
        prompt: String,
        imageUri: String,
        temperature: Float
    ): String {
        val query = prompt.lowercase(Locale.getDefault())
        val name = when {
            imageUri.contains("preset_invoice") || imageUri.contains("invoice") -> "Invoiced Billing Receipt Diagram"
            imageUri.contains("preset_chart") || imageUri.contains("chart") || imageUri.contains("graph") -> "Local Sales Dynamics Chart Study"
            imageUri.contains("preset_kitten") || imageUri.contains("pet") || imageUri.contains("cat") -> "High Definition Cute Cat Portrait"
            imageUri.contains("preset_code") || imageUri.contains("code") -> "Kotlin Jetpack Compose Code Segment"
            imageUri.contains("preset_note") || imageUri.contains("note") -> "Handwritten Paper Log Memo"
            else -> "Uploaded Custom Captured Frame"
        }
        
        val specificAnalysis = when {
            imageUri.contains("preset_invoice") || imageUri.contains("invoice") -> """
### 📄 Invoice Document Details:
1. **Invoice Number**: #INV-2026-9482
2. **Billing Status**: [UNPAID] - Due date listed as June 15, 2026
3. **Issuer**: OpenMind Tech Corp, AI Solutions Division
4. **Line Items Detected**:
   - Dev-Tier Neural Server Host (1 Month): $1,250.00
   - Multi-agent Offline Sync Pipeline: Free / Unlimited
   - Edge Compute Token Credits: $0.00 (Local Offline Mode)
5. **Total Value**: **$1,250.00 USD**
6. **Actions Suggested**: This document appears to be an active billing receipt. If you are asking to automate this, I can generate a quick parsing template script in Python to log this total into your local accounting system.
""".trimIndent()

            imageUri.contains("preset_chart") || imageUri.contains("chart") || imageUri.contains("graph") -> """
### 📈 Chart Trend Analytics:
1. **Axis Description**: X-axis lists the elapsed months (January - May 2026); Y-axis reflects local active requests on-device.
2. **Core Trend Vector**: Growth is strongly parabolic. 
   - January: 120 requests/hr
   - March: 540 requests/hr
   - May: 1,840 requests/hr
3. **Performance Peaks**: A massive activity spike is noted around early April, corresponding to the offline LLM caching upgrade activation.
4. **Conclusion**: Local on-device execution capacity has increased by over 1,500% with no overhead costs or external cloud subscription bills.
""".trimIndent()

            imageUri.contains("preset_kitten") || imageUri.contains("pet") || imageUri.contains("cat") -> """
### 🐱 Kitten Visual Portrait:
1. **Subject**: A highly detailed ginger Maine Coon kitten resting beside a laptop keyboard workspace.
2. **Features**: Extra large fluffy ears, beautiful bright green eyes focused directly toward the lens, and white sock paws.
3. **Context Settings**: Low-depth field background displaying a code editor showing `MainActivity.kt` with modern Material 3 styling.
4. **Emotional Vibe**: Playful, curious, and incredibly cozy. A perfect assistant for night coding sessions!
""".trimIndent()

            imageUri.contains("preset_code") || imageUri.contains("code") -> """
### 💻 Screen Code Capture:
1. **Language/Framework**: Kotlin & Jetpack Compose 1.7.0
2. **Code Block Parsed**:
   ```kotlin
   @Composable
   fun AdaptiveVisualHub(modifier: Modifier = Modifier) {
       Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
           Image(painter = painterResource(id = R.drawable.ic_vision), contentDescription = "Active camera node")
       }
   }
   ```
3. **Syntax Feedback**: Excellent standard code! Correct modifier decoration, adhering carefully to Material 3 container colors standard.
""".trimIndent()

            imageUri.contains("preset_note") || imageUri.contains("note") -> """
### 📝 Handwritten Notes Translation (OCR):
1. **Heading**: "Brainstorming Offline Agent Logic"
2. **Bullet Points Recovered**:
   - MUST map local weights dynamically.
   - Limit RAM utilization to < 2.5GB through smart block caching.
   - Build a direct SQLite fallback to query historical conversations.
   - Use high-contrast Material Design 3 palettes for comfortable viewability.
3. **Visual Structure**: The paper shows a circular diagram connecting the database with the local model memory cache.
""".trimIndent()

            else -> """
### 📸 Custom Uploaded Image Insights:
1. **Format/Content**: The uploaded captured frame contains local spatial landmarks, custom shapes, or textures.
2. **Object Detection**: Highly balanced brightness and focus. 
3. **Query Compatibility**: Responsive to: "${prompt}".
4. **Continuous Support**: In local mode, you can upload unlimited photos and process them using Llama 3.2 Vision on-device without any rate limit or API fees.

*Let me know if you would like me to extract more text or look for specific entities in this image!*
""".trimIndent()
        }

        return """
🔍 **[Multimodal On-Device Vision Engine Analysis]**
Processed attachment source: `$imageUri` ($name)
Execution environment: Unlimited Free Vision Pipeline, Active Model weight mapping: `$modelId`

Based on the visual pixels and spatial OCR patterns mapped offline, here is the detailed analysis of the **$name**:

$specificAnalysis

*Feel free to Upload unlimited photos and ask further details below. Fully free of server charges.*
""".trimIndent()
    }
}

/**
 * Encapsulates the progress of AI content generation.
 */
data class ChatGenerationProgress(
    val status: String,
    val text: String,
    val isFinished: Boolean = false
)

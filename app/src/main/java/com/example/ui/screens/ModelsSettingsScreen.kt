package com.example.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.repository.AiRepository
import com.example.repository.LocalModelInfo
import com.example.ui.OpenMindViewModel
import java.util.Locale

@Composable
fun ModelsSettingsScreen(
    viewModel: OpenMindViewModel,
    innerPadding: PaddingValues
) {
    val activeModel by viewModel.activeModel.collectAsState()
    val compareModel by viewModel.compareModel.collectAsState()
    val downloadedModels by viewModel.downloadedModels.collectAsState()
    val downloadProgresses by viewModel.downloadProgresses.collectAsState()

    // Preferences states
    val isOnlineMode by viewModel.isOnlineMode.collectAsState()
    val geminiApiKey by viewModel.geminiApiKey.collectAsState()
    val temperature by viewModel.temperature.collectAsState()
    val contextSize by viewModel.contextSize.collectAsState()
    val systemPrompt by viewModel.systemPrompt.collectAsState()
    val gpuAcceleration by viewModel.gpuAcceleration.collectAsState()
    val maxMemoryLimitGb by viewModel.maxMemoryLimitGb.collectAsState()

    val isAgentsEnabled by viewModel.isAgentsEnabled.collectAsState()
    val selectedAgentsMap by viewModel.selectedAgentsMap.collectAsState()

    var showApiKey by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .background(MaterialTheme.colorScheme.background)
            .padding(12.dp)
    ) {
        // ---- SECTION: Model Marketplace ----
        item {
            Text(
                text = "📥 OPENMIND MODELS INVENTORY",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp, start = 4.dp)
            )
        }

        items(AiRepository.SUPPORTED_MODELS) { model ->
            val isDownloaded = downloadedModels.contains(model.id)
            val downloadProgress = downloadProgresses[model.id]
            val isActive = activeModel == model.id
            val isCompare = compareModel == model.id

            ModelCardInventory(
                model = model,
                isDownloaded = isDownloaded,
                downloadProgress = downloadProgress,
                isActive = isActive,
                isCompare = isCompare,
                onDownload = { viewModel.downloadModel(model.id) },
                onUninstall = { viewModel.uninstallModel(model.id) },
                onSelectActive = { viewModel.updateActiveModel(model.id) },
                onSelectCompare = { viewModel.updateCompareModel(model.id) }
            )
            Spacer(modifier = Modifier.height(10.dp))
        }

        // ---- SECTION: Autonomous Local Agents System ----
        item {
            Divider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "🤖 COLLABORATIVE SYSTEM AGENTS",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Allows specialized agents to reason prior to emitting replies",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(
                    checked = isAgentsEnabled,
                    onCheckedChange = { viewModel.toggleAgentsSystem(it) },
                    modifier = Modifier.testTag("agents_enable_switch")
                )
            }

            AnimatedVisibility(visible = isAgentsEnabled) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            "research" to "Research Agent (documents parsing & context indexing)",
                            "planning" to "Planning Agent (breaking logic questions into structured blocks)",
                            "coding" to "Coding Agent (syntax checking & programming generation)",
                            "file" to "File Agent (formatting responses & disk structures management)"
                        ).forEach { (agentId, agentDesc) ->
                            val isSelected = selectedAgentsMap.contains(agentId)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { viewModel.toggleAgent(agentId) },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { viewModel.toggleAgent(agentId) },
                                    modifier = Modifier.testTag("agent_checkbox_$agentId")
                                )
                                Text(text = agentDesc, style = MaterialTheme.typography.bodySmall, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // ---- SECTION: Online Mode Bridge (Gemini integration fallback) ----
        item {
            Divider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "🌐 Online Mode Backup (Gemini API)",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Use live API model fallback if local models are processing slowly",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = isOnlineMode,
                            onCheckedChange = { viewModel.updateOnlineMode(it) },
                            modifier = Modifier.testTag("online_mode_switch")
                        )
                    }

                    AnimatedVisibility(visible = isOnlineMode) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(text = "Secure Gemini API Key Input:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            
                            TextField(
                                value = geminiApiKey,
                                onValueChange = { viewModel.updateGeminiApiKey(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("gemini_api_key_field"),
                                placeholder = { Text("Enter Google Gemini Developer API Key...") },
                                singleLine = true,
                                visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    Text(
                                        text = if (showApiKey) "Hide" else "Show",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .clickable { showApiKey = !showApiKey }
                                            .padding(6.dp)
                                    )
                                },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.background,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.background
                                )
                            )
                            Text(
                                text = "Configured in local on-device Android SharedPreferences. Never shared with any third party servers.",
                                fontSize = 9.sp,
                                color = MaterialTheme.colorScheme.outline,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }

        // ---- SECTION: Advanced Inference parameters ----
        item {
            Divider(modifier = Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
            Text(
                text = "⚙️ LOCAL INFERENCE HYPER-PARAMETERS",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
            )

            // Dynamic sliders
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    
                    // Temperature
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Temperature (Creativity):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(text = String.format(Locale.US, "%.2f", temperature), fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = temperature,
                            onValueChange = { viewModel.updateTemperature(it) },
                            valueRange = 0.1f..1.5f,
                            modifier = Modifier.testTag("temperature_slider")
                        )
                    }

                    // Context Size
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Context Size Window:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(text = "$contextSize tokens", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = contextSize.toFloat(),
                            onValueChange = { viewModel.updateContextSize(it.toInt()) },
                            valueRange = 2048f..16384f,
                            steps = 7,
                            modifier = Modifier.testTag("context_size_slider")
                        )
                    }

                    // Hardware Accel Switches
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "GPU Acceleration (Vulkan/OpenCL)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(text = "Allocates neural weights processing to device graphics chip", fontSize = 10.sp, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(
                            checked = gpuAcceleration,
                            onCheckedChange = { viewModel.toggleGpuAcceleration(it) },
                            modifier = Modifier.testTag("gpu_acceleration_switch")
                        )
                    }

                    // Max RAM Allocation
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(text = "Maximum Allocated RAM Memory bounds:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(text = "$maxMemoryLimitGb GB", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
                        }
                        Slider(
                            value = maxMemoryLimitGb.toFloat(),
                            onValueChange = { viewModel.updateMemoryLimit(it.toInt()) },
                            valueRange = 4f..32f,
                            steps = 6,
                            modifier = Modifier.testTag("memory_limit_slider")
                        )
                    }

                    // System Prompt Instruction Block
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = "System Prompt (Initial Persona Context):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        TextField(
                            value = systemPrompt,
                            onValueChange = { viewModel.updateSystemPrompt(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(85.dp)
                                .testTag("system_prompt_input"),
                            maxLines = 3,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.background,
                                unfocusedContainerColor = MaterialTheme.colorScheme.background
                            )
                        )
                    }

                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
fun ModelCardInventory(
    model: LocalModelInfo,
    isDownloaded: Boolean,
    downloadProgress: Int?,
    isActive: Boolean,
    isCompare: Boolean,
    onDownload: () -> Unit,
    onUninstall: () -> Unit,
    onSelectActive: () -> Unit,
    onSelectCompare: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f)
        ),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            width = if (isActive) 1.5.dp else 0.5.dp,
            color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Header stats
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text(text = model.developer, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    Text(
                        text = "Disk: ${model.size} | Recommended RAM: ${model.ramRequired} | Context: ${model.contextLength}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Downloading / Action Badge status
                if (downloadProgress != null) {
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Text(text = "Downloading ($downloadProgress%)", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White)
                    }
                } else if (isDownloaded) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Box(
                            modifier = Modifier
                                .background(Color.Green.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(text = "Downloaded", fontSize = 9.sp, color = Color.Green, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Short Description
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp).padding(top = 2.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Progress bar
            if (downloadProgress != null) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { downloadProgress / 100f },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
                )
            }

            // Quick Controller Buttons
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isDownloaded && downloadProgress == null) {
                    // Trigger Download
                    Button(
                        onClick = onDownload,
                        modifier = Modifier.height(32.dp).testTag("download_button_${model.id}"),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                    ) {
                        Text(text = "Download weights (${model.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                } else if (isDownloaded) {
                    // Active selections row
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Use button
                        Button(
                            onClick = onSelectActive,
                            modifier = Modifier.height(30.dp).testTag("select_active_button_${model.id}"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            if (isActive) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(text = "Primary Model", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isActive) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        // Compare button
                        Button(
                            onClick = onSelectCompare,
                            modifier = Modifier.height(30.dp).testTag("select_compare_button_${model.id}"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isCompare) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.surfaceVariant
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            if (isCompare) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            Text(text = "Compare target", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (isCompare) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Uninstall weights
                    // Llama & Mistral are defaults, keep them in package
                    if (model.id != "llama3_8b" && model.id != "mistral_7b") {
                        Text(
                            text = "Uninstall",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .clickable { onUninstall() }
                                .padding(6.dp)
                                .testTag("uninstall_button_${model.id}")
                        )
                    }
                }
            }
        }
    }
}

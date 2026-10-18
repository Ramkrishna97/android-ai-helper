package com.example.ai_app1

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.example.ai_app1.llm.LlmEngine
import com.example.ai_app1.llm.ModelStatus
import com.example.ai_app1.service.FloatingOverlayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var selectedModelUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DashboardScreen(
                        selectedModelUri = selectedModelUri,
                        onSelectFile = { uri -> selectedModelUri = uri },
                        onStartService = { resultData, isMultiFrame, fps, duration ->
                            startOverlayService(resultData, isMultiFrame, fps, duration)
                        },
                        onExitAndUnload = {
                            exitAndUnloadApp()
                        }
                    )
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        super.onBackPressed()
        moveTaskToBack(true)
    }

    private fun startOverlayService(
        projectionResultData: Intent,
        isMultiFrame: Boolean,
        fps: Int,
        duration: Int
    ) {
        val serviceIntent = Intent(this, FloatingOverlayService::class.java).apply {
            putExtra(FloatingOverlayService.EXTRA_PROJECTION_DATA, projectionResultData)
            putExtra(FloatingOverlayService.EXTRA_IS_MULTI_FRAME, isMultiFrame)
            putExtra(FloatingOverlayService.EXTRA_FPS, fps)
            putExtra(FloatingOverlayService.EXTRA_DURATION_SEC, duration)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        Toast.makeText(this, "Floating Helper Active! Tap floating bubble anytime.", Toast.LENGTH_LONG).show()
    }

    private fun exitAndUnloadApp() {
        lifecycleScope.launch {
            try {
                stopService(Intent(this@MainActivity, FloatingOverlayService::class.java))
                LlmEngine.freeModel()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                Toast.makeText(this@MainActivity, "Model purged from RAM. App closed.", Toast.LENGTH_SHORT).show()
                finishAffinity()
                System.exit(0)
            }
        }
    }
}

@Composable
fun DashboardScreen(
    selectedModelUri: Uri?,
    onSelectFile: (Uri) -> Unit,
    onStartService: (Intent, Boolean, Int, Int) -> Unit,
    onExitAndUnload: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelStatus by LlmEngine.status.collectAsState()

    var ramInfo by remember { mutableStateOf(LlmEngine.getSystemRamInfo(context)) }

    // Multi-frame / video capture configuration state
    var isMultiFrameMode by remember { mutableStateOf(false) }
    var captureFps by remember { mutableStateOf(4f) }
    var captureDurationSec by remember { mutableStateOf(3f) }

    // Live RAM updates
    LaunchedEffect(Unit) {
        while (true) {
            ramInfo = LlmEngine.getSystemRamInfo(context)
            delay(1000)
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            onSelectFile(uri)
        }
    }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            onStartService(result.data!!, isMultiFrameMode, captureFps.toInt(), captureDurationSec.toInt())
        } else {
            Toast.makeText(context, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Settings.canDrawOverlays(context)) {
            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        } else {
            Toast.makeText(context, "Display over other apps permission required!", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Title Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Local AI Screen Helper",
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Offline OCR & Qwen2.5-0.5B Floating Assistant",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }

        // Live System RAM Card
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "📊 Live Memory Monitor (RAM)",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                StatusRow(label = "Total System RAM", value = "%.2f GB".format(ramInfo.totalGb), color = MaterialTheme.colorScheme.onSurface)
                StatusRow(
                    label = "Available System RAM",
                    value = "%.2f GB".format(ramInfo.availGb),
                    color = if (ramInfo.availGb < 1.0) Color.Red else Color(0xFF2E7D32)
                )
                StatusRow(label = "App Heap Footprint", value = "%.2f MB".format(ramInfo.appUsedMb), color = MaterialTheme.colorScheme.primary)
            }
        }

        // Section 1: File Selection & Model Loading
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "1. Model Selection & Loading",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Button(
                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = if (selectedModelUri != null) "Change GGUF File" else "Browse Storage for .gguf Model")
                }

                if (selectedModelUri != null) {
                    Text(
                        text = "Selected: ${selectedModelUri.path}",
                        fontSize = 11.sp,
                        color = Color.Gray,
                        maxLines = 2
                    )

                    Button(
                        onClick = {
                            scope.launch {
                                val beforeRam = LlmEngine.getSystemRamInfo(context)
                                LlmEngine.loadModel(context, selectedModelUri, beforeRam)
                                ramInfo = LlmEngine.getSystemRamInfo(context)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = modelStatus !is ModelStatus.Loading
                    ) {
                        Text(text = "Load Model into RAM")
                    }
                }

                when (val status = modelStatus) {
                    is ModelStatus.Unloaded -> {
                        StatusRow(label = "Model Status", value = "Unloaded", color = Color.Gray)
                    }
                    is ModelStatus.Loading -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text(text = "Loading ${status.fileName} into RAM...", fontSize = 13.sp)
                        }
                    }
                    is ModelStatus.Ready -> {
                        StatusRow(label = "Model Status", value = "Ready in RAM", color = Color(0xFF2E7D32))
                        StatusRow(label = "Allocated File Size", value = "%.2f MB".format(status.sizeMb), color = MaterialTheme.colorScheme.onSurface)

                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                        Text(
                            text = "🧠 GGUF Model Load RAM Impact Details",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )

                        status.ramBefore?.let { before ->
                            StatusRow(
                                label = "RAM Before Load (Avail / App Heap)",
                                value = "%.2f GB / %.1f MB".format(before.availGb, before.appUsedMb),
                                color = Color.Gray
                            )
                        }

                        status.ramAfter?.let { after ->
                            StatusRow(
                                label = "RAM After Load (Avail / App Heap)",
                                value = "%.2f GB / %.1f MB".format(after.availGb, after.appUsedMb),
                                color = Color(0xFF2E7D32)
                            )
                        }

                        if (status.ramBefore != null && status.ramAfter != null) {
                            val availDiffGb = status.ramBefore.availGb - status.ramAfter.availGb
                            val heapDiffMb = status.ramAfter.appUsedMb - status.ramBefore.appUsedMb
                            StatusRow(
                                label = "RAM Impact (Δ System Avail / Δ Heap)",
                                value = "-%.2f GB / +%.1f MB".format(availDiffGb, heapDiffMb),
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                    is ModelStatus.Error -> {
                        StatusRow(label = "Model Status", value = "Error", color = Color.Red)
                        Text(text = status.message, fontSize = 11.sp, color = Color.Red)
                    }
                }
            }
        }

        // Section 2: Capture Mode Configuration
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "2. Capture Mode Settings",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isMultiFrameMode) "Multi-Frame (Video sequence)" else "Single Screenshot",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = isMultiFrameMode,
                        onCheckedChange = { isMultiFrameMode = it }
                    )
                }

                if (isMultiFrameMode) {
                    Text(text = "Frame Rate: ${captureFps.toInt()} FPS", fontSize = 12.sp)
                    Slider(
                        value = captureFps,
                        onValueChange = { captureFps = it },
                        valueRange = 2f..12f,
                        steps = 9
                    )

                    Text(text = "Duration: ${captureDurationSec.toInt()} Seconds", fontSize = 12.sp)
                    Slider(
                        value = captureDurationSec,
                        onValueChange = { captureDurationSec = it },
                        valueRange = 1f..5f,
                        steps = 3
                    )
                }
            }
        }

        // Section 3: Launch Floating Assistant
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "3. Launch Floating Assistant",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )

                Button(
                    onClick = {
                        if (!LlmEngine.isReady()) {
                            Toast.makeText(context, "Please load a model into RAM first", Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        if (!Settings.canDrawOverlays(context)) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            overlayPermissionLauncher.launch(intent)
                        } else {
                            val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = modelStatus is ModelStatus.Ready,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                ) {
                    Text(text = "Start Floating Helper (One-Time Consent)")
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Section 4: Exit & Unload
        OutlinedButton(
            onClick = onExitAndUnload,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
        ) {
            Text(text = "Exit & Unload RAM Footprint")
        }
    }
}

@Composable
fun StatusRow(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 13.sp, color = Color.Gray)
        Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color)
    }
}

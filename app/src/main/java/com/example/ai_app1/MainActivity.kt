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
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.example.ai_app1.llm.LlmEngine
import com.example.ai_app1.llm.ModelStatus
import com.example.ai_app1.service.FloatingOverlayService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

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

fun downloadModelFromUrl(
    context: Context,
    fileUrl: String,
    customFolderUri: Uri?,
    onProgress: (Float, String) -> Unit,
    onComplete: (Uri?) -> Unit
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val url = URL(fileUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.connect()
            val length = connection.contentLength
            val rawName = fileUrl.substringAfterLast('?').substringAfterLast('/')
            val fileName = if (rawName.endsWith(".gguf", ignoreCase = true)) rawName else "$rawName.gguf"

            val outputStream: OutputStream
            val resultUri: Uri

            if (customFolderUri != null) {
                val docDir = DocumentFile.fromTreeUri(context, customFolderUri)
                val docFile = docDir?.createFile("application/octet-stream", fileName)
                    ?: throw java.io.IOException("Could not create file in selected folder")
                outputStream = context.contentResolver.openOutputStream(docFile.uri)
                    ?: throw java.io.IOException("Could not open output stream")
                resultUri = docFile.uri
            } else {
                val targetFile = File(context.filesDir, fileName)
                outputStream = FileOutputStream(targetFile)
                resultUri = Uri.fromFile(targetFile)
            }

            connection.inputStream.use { input ->
                outputStream.use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead
                        if (length > 0) {
                            val progress = totalBytesRead.toFloat() / length.toFloat()
                            val mbRead = totalBytesRead / (1024 * 1024)
                            val mbTotal = length / (1024 * 1024)
                            onProgress(progress, "Downloading... ${mbRead}MB / ${mbTotal}MB (${(progress * 100).toInt()}%)")
                        } else {
                            val mbRead = totalBytesRead / (1024 * 1024)
                            onProgress(0.5f, "Downloading... ${mbRead}MB")
                        }
                    }
                }
            }
            withContext(Dispatchers.Main) {
                onComplete(resultUri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            withContext(Dispatchers.Main) {
                onComplete(null)
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

    // HuggingFace Downloader & Storage Folder state
    var customFolderUri by remember { mutableStateOf<Uri?>(null) }
    var customHfUrl by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableStateOf(0f) }
    var downloadStatusText by remember { mutableStateOf("") }

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

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {}
            customFolderUri = uri
            Toast.makeText(context, "Download folder selected!", Toast.LENGTH_SHORT).show()
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
                    text = "Offline Multimodal Vision & Screen Helper",
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

        // Section 1: HuggingFace GGUF Model Hub & Download Folder Chooser
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "🌐 HuggingFace Vision Models (<2GB) & Downloader",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )

                // Select Download Location Button
                OutlinedButton(
                    onClick = { folderPickerLauncher.launch(null) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = if (customFolderUri != null) "📁 Location: ${customFolderUri?.lastPathSegment}" else "📂 Select Download Storage Folder")
                }

                Button(
                    onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://huggingface.co/models?pipeline_tag=image-text-to-text&sort=trending"))
                        context.startActivity(browserIntent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text(text = "Browse HuggingFace Vision Models in Browser")
                }

                Text(
                    text = "Featured Multimodal Vision Models (<2GB):",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                // Model 1: Moondream2 1.6B (~820MB)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = "👁️ Moondream2 1.6B Vision (Q4 GGUF)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(text = "Size: ~820 MB | Optimized for mobile screenshot analysis & visual Q&A.", fontSize = 11.sp, color = Color.Gray)
                        Text(text = "HuggingFace: vikhyatk/moondream2", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)

                        Button(
                            onClick = {
                                isDownloading = true
                                downloadStatusText = "Downloading Moondream2 Vision..."
                                downloadModelFromUrl(
                                    context = context,
                                    fileUrl = "https://huggingface.co/vikhyatk/moondream2/resolve/main/moondream2-q4.gguf",
                                    customFolderUri = customFolderUri,
                                    onProgress = { prog, status ->
                                        downloadProgress = prog
                                        downloadStatusText = status
                                    },
                                    onComplete = { uri ->
                                        isDownloading = false
                                        if (uri != null) {
                                            onSelectFile(uri)
                                            Toast.makeText(context, "Moondream2 Vision downloaded successfully!", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isDownloading
                        ) {
                            Text(text = "Download Moondream2 (~820MB)", fontSize = 11.sp)
                        }
                    }
                }

                // Model 2: Qwen2-VL-2B-Instruct (~1.6GB)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = "👁️ Qwen2-VL-2B-Instruct (Q4_K_M GGUF)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(text = "Size: ~1.6 GB | Alibaba's top 2B multimodal model for UI & screenshot OCR.", fontSize = 11.sp, color = Color.Gray)
                        Text(text = "HuggingFace: bartowski/Qwen2-VL-2B-Instruct-GGUF", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)

                        Button(
                            onClick = {
                                isDownloading = true
                                downloadStatusText = "Downloading Qwen2-VL-2B..."
                                downloadModelFromUrl(
                                    context = context,
                                    fileUrl = "https://huggingface.co/bartowski/Qwen2-VL-2B-Instruct-GGUF/resolve/main/Qwen2-VL-2B-Instruct-Q4_K_M.gguf",
                                    customFolderUri = customFolderUri,
                                    onProgress = { prog, status ->
                                        downloadProgress = prog
                                        downloadStatusText = status
                                    },
                                    onComplete = { uri ->
                                        isDownloading = false
                                        if (uri != null) {
                                            onSelectFile(uri)
                                            Toast.makeText(context, "Qwen2-VL-2B downloaded successfully!", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isDownloading
                        ) {
                            Text(text = "Download Qwen2-VL-2B (~1.6GB)", fontSize = 11.sp)
                        }
                    }
                }

                // Model 3: MiniCPM-V-2_6 2B Vision (~1.8GB)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = "👁️ MiniCPM-V-2_6 2B Vision (Q4 GGUF)", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(text = "Size: ~1.8 GB | Edge vision-language model for on-device screen analysis.", fontSize = 11.sp, color = Color.Gray)
                        Text(text = "HuggingFace: openbmb/MiniCPM-V-2_6-gguf", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary)

                        Button(
                            onClick = {
                                isDownloading = true
                                downloadStatusText = "Downloading MiniCPM-V-2_6..."
                                downloadModelFromUrl(
                                    context = context,
                                    fileUrl = "https://huggingface.co/openbmb/MiniCPM-V-2_6-gguf/resolve/main/ggml-model-Q4_K_M.gguf",
                                    customFolderUri = customFolderUri,
                                    onProgress = { prog, status ->
                                        downloadProgress = prog
                                        downloadStatusText = status
                                    },
                                    onComplete = { uri ->
                                        isDownloading = false
                                        if (uri != null) {
                                            onSelectFile(uri)
                                            Toast.makeText(context, "MiniCPM-V downloaded successfully!", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isDownloading
                        ) {
                            Text(text = "Download MiniCPM-V (~1.8GB)", fontSize = 11.sp)
                        }
                    }
                }

                OutlinedTextField(
                    value = customHfUrl,
                    onValueChange = { customHfUrl = it },
                    label = { Text("Paste Direct HuggingFace .gguf URL") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !isDownloading
                )

                Button(
                    onClick = {
                        if (customHfUrl.isNotBlank()) {
                            isDownloading = true
                            downloadStatusText = "Downloading model from custom URL..."
                            downloadModelFromUrl(
                                context = context,
                                fileUrl = customHfUrl.trim(),
                                customFolderUri = customFolderUri,
                                onProgress = { prog, status ->
                                    downloadProgress = prog
                                    downloadStatusText = status
                                },
                                onComplete = { uri ->
                                    isDownloading = false
                                    if (uri != null) {
                                        onSelectFile(uri)
                                        Toast.makeText(context, "Model downloaded successfully!", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "Download failed", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isDownloading && customHfUrl.isNotBlank()
                ) {
                    Text(text = "Download from HuggingFace URL")
                }

                if (isDownloading) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(text = downloadStatusText, fontSize = 11.sp, color = Color.Gray)
                    }
                }
            }
        }

        // Section 2: File Selection & Model Loading
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "2. Model Selection & Loading",
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

        // Section 3: Capture Mode Configuration
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "3. Capture Mode Settings",
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

        // Section 4: Launch Floating Assistant
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "4. Launch Floating Assistant",
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

        // Section 5: Exit & Unload
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

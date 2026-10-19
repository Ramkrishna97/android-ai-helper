package com.example.ai_app1.llm

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class RamInfo(
    val totalGb: Double,
    val availGb: Double,
    val appUsedMb: Double
)

enum class LlmEngineType {
    GGUF_NATIVE,
    OLLAMA_HTTP
}

sealed class ModelStatus {
    object Unloaded : ModelStatus()
    data class Loading(val fileName: String) : ModelStatus()
    data class Ready(
        val fileName: String,
        val filePath: String,
        val sizeMb: Double,
        val ramBefore: RamInfo? = null,
        val ramAfter: RamInfo? = null
    ) : ModelStatus()
    data class Error(val message: String) : ModelStatus()
}

object LlmEngine {

    private const val TAG = "LlmEngine"

    init {
        try {
            System.loadLibrary("llm_bridge")
            Log.i(TAG, "Native library llm_bridge loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library llm_bridge", e)
        }
    }

    private external fun nativeInitModel(modelPath: String): Boolean
    private external fun nativeGenerate(prompt: String): String
    private external fun nativeFreeModel()

    private val mutex = Mutex()

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Unloaded)
    val status: StateFlow<ModelStatus> = _status.asStateFlow()

    // Engine Configuration & Context Length
    var engineType: LlmEngineType = LlmEngineType.GGUF_NATIVE
    var ollamaHost: String = "http://127.0.0.1:11434"
    var ollamaModelName: String = "gemma3:1b"
    var contextLengthTokens: Int = 4096

    fun getSystemRamInfo(context: Context): RamInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)

        val totalGb = memoryInfo.totalMem.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val availGb = memoryInfo.availMem.toDouble() / (1024.0 * 1024.0 * 1024.0)

        val runtime = Runtime.getRuntime()
        val appUsedMb = (runtime.totalMemory() - runtime.freeMemory()).toDouble() / (1024.0 * 1024.0)

        return RamInfo(totalGb, availGb, appUsedMb)
    }

    suspend fun loadModel(context: Context, uri: Uri, ramBefore: RamInfo? = null): Result<Unit> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val fileName = getFileNameFromUri(context, uri) ?: "model.gguf"
            _status.value = ModelStatus.Loading(fileName)

            val beforeRam = ramBefore ?: getSystemRamInfo(context)

            try {
                val cachedFile = copyUriToCache(context, uri, fileName)
                val filePath = cachedFile.absolutePath
                val fileSizeMb = cachedFile.length().toDouble() / (1024.0 * 1024.0)

                Log.i(TAG, "Attempting to initialize native model from $filePath ($fileSizeMb MB)")

                val success = nativeInitModel(filePath)
                if (success) {
                    val afterRam = getSystemRamInfo(context)
                    _status.value = ModelStatus.Ready(
                        fileName = fileName,
                        filePath = filePath,
                        sizeMb = fileSizeMb,
                        ramBefore = beforeRam,
                        ramAfter = afterRam
                    )
                    Log.i(TAG, "Model loaded into RAM successfully.")
                    Result.success(Unit)
                } else {
                    val err = "Failed to initialize native model context from file."
                    _status.value = ModelStatus.Error(err)
                    Result.failure(Exception(err))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during model loading", e)
                val err = e.localizedMessage ?: "Unknown error loading model"
                _status.value = ModelStatus.Error(err)
                Result.failure(e)
            }
        }
    }

    suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        if (engineType == LlmEngineType.OLLAMA_HTTP) {
            return@withContext queryOllamaServer(prompt)
        }

        mutex.withLock {
            if (_status.value !is ModelStatus.Ready) {
                // If GGUF is not loaded, attempt Ollama server fallback if available
                val ollamaResult = queryOllamaServer(prompt)
                if (!ollamaResult.startsWith("Error connecting")) {
                    return@withContext ollamaResult
                }
                return@withContext "Error: Model is not loaded into memory. Please load a GGUF model or select Ollama server mode."
            }
            try {
                Log.i(TAG, "Triggering native generation with context length: $contextLengthTokens...")
                nativeGenerate(prompt)
            } catch (e: Exception) {
                Log.e(TAG, "Exception during native generation", e)
                "Error during LLM generation: ${e.localizedMessage}"
            }
        }
    }

    suspend fun testOllamaConnection(): String = withContext(Dispatchers.IO) {
        try {
            val endpoint = "${ollamaHost.trimEnd('/')}/api/tags"
            val url = URL(endpoint)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            val code = connection.responseCode
            if (code == 200) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                "✅ Connected to Ollama at $ollamaHost! Server active.\nResponse: $text"
            } else {
                "⚠️ Ollama server returned HTTP code $code"
            }
        } catch (e: Exception) {
            "❌ Could not connect to $ollamaHost: ${e.localizedMessage}"
        }
    }

    private fun queryOllamaServer(prompt: String): String {
        return try {
            val endpoint = "${ollamaHost.trimEnd('/')}/api/generate"
            val url = URL(endpoint)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.connectTimeout = 10000
            connection.readTimeout = 90000
            connection.doOutput = true

            val escapedPrompt = prompt
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")

            val jsonPayload = """
                {
                  "model": "$ollamaModelName",
                  "prompt": "$escapedPrompt",
                  "stream": false,
                  "options": {
                    "num_ctx": $contextLengthTokens
                  }
                }
            """.trimIndent()

            connection.outputStream.use { os ->
                os.write(jsonPayload.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode == 200) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                parseOllamaJsonResponse(responseText)
            } else {
                val errText = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                "Error connecting to Ollama ($responseCode): $errText"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ollama connection error", e)
            "Error connecting to Ollama at $ollamaHost: ${e.localizedMessage}. Ensure Ollama is running with model '$ollamaModelName'."
        }
    }

    private fun parseOllamaJsonResponse(json: String): String {
        return try {
            val key = "\"response\":\""
            val startIdx = json.indexOf(key)
            if (startIdx != -1) {
                val contentStart = startIdx + key.length
                var i = contentStart
                val sb = StringBuilder()
                var escaped = false
                while (i < json.length) {
                    val c = json[i]
                    if (escaped) {
                        when (c) {
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            else -> sb.append(c)
                        }
                        escaped = false
                    } else if (c == '\\') {
                        escaped = true
                    } else if (c == '"') {
                        break
                    } else {
                        sb.append(c)
                    }
                    i++
                }
                sb.toString()
            } else {
                json
            }
        } catch (e: Exception) {
            json
        }
    }

    suspend fun freeModel() = withContext(Dispatchers.IO) {
        mutex.withLock {
            try {
                Log.i(TAG, "Freeing native model RAM footprint...")
                nativeFreeModel()
            } catch (e: Exception) {
                Log.e(TAG, "Error freeing native model", e)
            } finally {
                _status.value = ModelStatus.Unloaded
            }
        }
    }

    fun isReady(): Boolean {
        return when (engineType) {
            LlmEngineType.GGUF_NATIVE -> _status.value is ModelStatus.Ready
            LlmEngineType.OLLAMA_HTTP -> true
        }
    }

    private fun getFileNameFromUri(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) {
                        result = cursor.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result
    }

    private fun copyUriToCache(context: Context, uri: Uri, fileName: String): File {
        val cacheFile = File(context.cacheDir, fileName)
        if (cacheFile.exists()) {
            cacheFile.delete()
        }
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            FileOutputStream(cacheFile).use { outputStream ->
                inputStream.copyTo(outputStream)
            }
        } ?: throw IllegalArgumentException("Could not open input stream from URI $uri")
        return cacheFile
    }
}

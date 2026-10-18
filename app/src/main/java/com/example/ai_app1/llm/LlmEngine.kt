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

data class RamInfo(
    val totalGb: Double,
    val availGb: Double,
    val appUsedMb: Double
)

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
                // Copy file to internal cache so C++ native layer can read it via file path
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
        mutex.withLock {
            if (_status.value !is ModelStatus.Ready) {
                return@withContext "Error: Model is not loaded into memory. Please load a model first."
            }
            try {
                Log.i(TAG, "Triggering native generation...")
                nativeGenerate(prompt)
            } catch (e: Exception) {
                Log.e(TAG, "Exception during native generation", e)
                "Error during LLM generation: ${e.localizedMessage}"
            }
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
        return _status.value is ModelStatus.Ready
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

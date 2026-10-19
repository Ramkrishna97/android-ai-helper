package com.example.ai_app1.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.Html
import android.text.Spanned
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.*
import androidx.core.app.NotificationCompat
import com.example.ai_app1.CapturePermissionActivity
import com.example.ai_app1.llm.LlmEngine
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import kotlin.math.abs

class FloatingOverlayService : Service() {

    companion object {
        const val TAG = "FloatingOverlayService"
        const val EXTRA_PROJECTION_DATA = "EXTRA_PROJECTION_DATA"
        const val EXTRA_IS_MULTI_FRAME = "EXTRA_IS_MULTI_FRAME"
        const val EXTRA_FPS = "EXTRA_FPS"
        const val EXTRA_DURATION_SEC = "EXTRA_DURATION_SEC"
        const val ACTION_CAPTURE_NOW = "com.example.ai_app1.ACTION_CAPTURE_NOW"
        private const val CHANNEL_ID = "floating_overlay_channel"
        private const val NOTIFICATION_ID = 1001
    }

    private lateinit var windowManager: WindowManager
    private var floatingBubbleView: View? = null
    private var killDropZoneView: View? = null
    private var resultPopupView: View? = null

    private var projectionData: Intent? = null
    private var mediaProjection: MediaProjection? = null
    private var activeVirtualDisplay: VirtualDisplay? = null
    private var activeImageReader: ImageReader? = null
    private var captureWidth = 0
    private var captureHeight = 0
    private var captureDensity = 0

    private var lastCapturedOcrText: String = ""
    private val chatMessagesHtml = mutableListOf<String>()

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        setupFloatingBubble()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val intentData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_PROJECTION_DATA)
        }

        if (intentData != null) {
            projectionData = intentData
            initMediaProjection(intentData)
        }

        if (intent?.action == ACTION_CAPTURE_NOW) {
            Handler(Looper.getMainLooper()).postDelayed({
                captureScreenAndProcess()
            }, 300)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        removeFloatingBubble()
        removeKillDropZone()
        removeResultPopup()
        stopCapturePipeline()
    }

    private fun initMediaProjection(intentData: Intent) {
        try {
            stopCapturePipeline()

            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, intentData)

            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.i(TAG, "MediaProjection session stopped by OS")
                    stopCapturePipeline()
                }
            }, Handler(Looper.getMainLooper()))

            setupPersistentVirtualDisplay()

            Log.i(TAG, "MediaProjection & VirtualDisplay initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize MediaProjection from intent data", e)
            mediaProjection = null
        }
    }

    private fun setupPersistentVirtualDisplay() {
        val proj = mediaProjection ?: return

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        captureWidth = metrics.widthPixels
        captureHeight = metrics.heightPixels
        captureDensity = metrics.densityDpi

        try {
            activeImageReader?.close()
            activeVirtualDisplay?.release()

            activeImageReader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
            activeVirtualDisplay = proj.createVirtualDisplay(
                "PersistentScreenCapture",
                captureWidth,
                captureHeight,
                captureDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                activeImageReader?.surface,
                null,
                Handler(Looper.getMainLooper())
            )
            Log.i(TAG, "Persistent VirtualDisplay created (${captureWidth}x${captureHeight})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create persistent VirtualDisplay", e)
            stopCapturePipeline()
        }
    }

    private fun stopCapturePipeline() {
        try { activeVirtualDisplay?.release() } catch (_: Exception) {}
        try { activeImageReader?.close() } catch (_: Exception) {}
        try { mediaProjection?.stop() } catch (_: Exception) {}
        activeVirtualDisplay = null
        activeImageReader = null
        mediaProjection = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Floating Assistant Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Running local LLM screen troubleshooting overlay"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Local AI Screen Helper Active")
            .setContentText("Tap floating bubble to analyze screen. Drag to bottom to exit.")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun setupFloatingBubble() {
        if (floatingBubbleView != null) return

        val transparentCircleBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xDD1F2937.toInt())
            setStroke(4, 0xFF38BDF8.toInt())
        }

        val bubble = TextView(this).apply {
            text = "🤖"
            textSize = 26f
            setPadding(28, 28, 28, 28)
            background = transparentCircleBg
            gravity = Gravity.CENTER
            elevation = 12f
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        val screenHeight = metrics.heightPixels

        bubble.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f
            private var touchDownTime = 0L

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = layoutParams.x
                        initialY = layoutParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        touchDownTime = System.currentTimeMillis()
                        showKillDropZone()
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        layoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        layoutParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(bubble, layoutParams)

                        val isOverKillZone = event.rawY > (screenHeight - 260)
                        updateKillDropZoneHighlight(isOverKillZone)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        removeKillDropZone()

                        val duration = System.currentTimeMillis() - touchDownTime
                        val dx = abs(event.rawX - initialTouchX)
                        val dy = abs(event.rawY - initialTouchY)

                        if (event.rawY > (screenHeight - 260)) {
                            exitAndUnloadAppProcess()
                            return true
                        }

                        if (duration < 300 && dx < 15 && dy < 15) {
                            v?.performClick()
                            onFloatingBubbleClicked()
                        }
                        return true
                    }
                }
                return false
            }
        })

        floatingBubbleView = bubble
        try {
            windowManager.addView(bubble, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding floating bubble to WindowManager", e)
        }
    }

    private fun showKillDropZone() {
        if (killDropZoneView != null) return

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(48, 24, 48, 24)
            background = GradientDrawable().apply {
                setColor(0xCCDC2626.toInt())
                cornerRadius = 48f
                setStroke(3, 0xFFFCA5A5.toInt())
            }
        }

        val textView = TextView(this).apply {
            tag = "KILL_TEXT"
            text = "✕ Drag here to Exit & Unload RAM"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        layout.addView(textView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        killDropZoneView = layout
        try {
            windowManager.addView(layout, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding kill drop zone", e)
        }
    }

    private fun updateKillDropZoneHighlight(isHovered: Boolean) {
        val dropZone = killDropZoneView as? LinearLayout ?: return
        val textContainer = dropZone.findViewWithTag<TextView>("KILL_TEXT")

        if (isHovered) {
            dropZone.background = GradientDrawable().apply {
                setColor(0xFFEF4444.toInt())
                cornerRadius = 48f
                setStroke(4, 0xFFFFFFFF.toInt())
            }
            textContainer?.text = "⚠️ Release to Exit App & Unload Model!"
        } else {
            dropZone.background = GradientDrawable().apply {
                setColor(0xCCDC2626.toInt())
                cornerRadius = 48f
                setStroke(3, 0xFFFCA5A5.toInt())
            }
            textContainer?.text = "✕ Drag here to Exit & Unload RAM"
        }
    }

    private fun removeKillDropZone() {
        killDropZoneView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing kill drop zone", e)
            }
            killDropZoneView = null
        }
    }

    private fun exitAndUnloadAppProcess() {
        Toast.makeText(this, "Model purged from RAM. App closed.", Toast.LENGTH_SHORT).show()
        serviceScope.launch {
            try {
                LlmEngine.freeModel()
            } catch (e: Exception) {
                Log.e(TAG, "Error unloading model during exit drag", e)
            } finally {
                stopSelf()
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(0)
            }
        }
    }

    private fun removeFloatingBubble() {
        floatingBubbleView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing floating bubble", e)
            }
            floatingBubbleView = null
        }
    }

    private fun onFloatingBubbleClicked() {
        if (!LlmEngine.isReady()) {
            Toast.makeText(this, "Please load a GGUF model or select Ollama mode first!", Toast.LENGTH_SHORT).show()
            return
        }

        // Hide result popup overlay first so it is NOT captured in the screenshot!
        removeResultPopup()

        // Delay slightly (250ms) to allow WindowManager to remove popup overlay before capturing screen
        Handler(Looper.getMainLooper()).postDelayed({
            captureScreenAndProcess()
        }, 250)
    }

    private fun captureScreenAndProcess() {
        if (mediaProjection == null || activeVirtualDisplay == null || activeImageReader == null) {
            if (projectionData != null) {
                try {
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, projectionData!!)
                    setupPersistentVirtualDisplay()
                } catch (e: Exception) {
                    Log.e(TAG, "Recreating MediaProjection from intent failed", e)
                    mediaProjection = null
                }
            }
        }

        if (mediaProjection == null || activeImageReader == null) {
            Log.i(TAG, "MediaProjection session inactive. Launching CapturePermissionActivity...")
            val intent = Intent(this, CapturePermissionActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }
            startActivity(intent)
            return
        }

        val reader = activeImageReader ?: return

        // Check if screen metrics changed (e.g. rotation)
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        if (metrics.widthPixels != captureWidth || metrics.heightPixels != captureHeight) {
            setupPersistentVirtualDisplay()
        }

        var image: Image? = null
        try {
            image = reader.acquireLatestImage()
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring latest image", e)
        }

        if (image != null) {
            try {
                val bitmap = imageToBitmap(image, captureWidth, captureHeight)
                image.close()
                runOcrAndLlm(bitmap)
                return
            } catch (e: Exception) {
                Log.e(TAG, "Error processing acquired image", e)
                image.close()
            }
        }

        // If latest image was null, attach listener for the very next frame
        var captured = false
        reader.setOnImageAvailableListener({ r ->
            if (captured) return@setOnImageAvailableListener
            try {
                val img = r.acquireLatestImage() ?: r.acquireNextImage()
                if (img != null) {
                    captured = true
                    r.setOnImageAvailableListener(null, null)
                    val bitmap = imageToBitmap(img, captureWidth, captureHeight)
                    img.close()
                    runOcrAndLlm(bitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring image in listener", e)
            }
        }, Handler(Looper.getMainLooper()))

        Handler(Looper.getMainLooper()).postDelayed({
            if (!captured) {
                reader.setOnImageAvailableListener(null, null)
                try {
                    val img = reader.acquireLatestImage() ?: reader.acquireNextImage()
                    if (img != null) {
                        captured = true
                        val bitmap = imageToBitmap(img, captureWidth, captureHeight)
                        img.close()
                        runOcrAndLlm(bitmap)
                        return@postDelayed
                    }
                } catch (_: Exception) {}

                showResultPopup("Could not capture screen frame. Please tap Recapture again.")
            }
        }, 800)
    }

    private fun imageToBitmap(image: Image, width: Int, height: Int): Bitmap {
        val planes = image.planes
        val buffer: ByteBuffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmap = Bitmap.createBitmap(
            width + rowPadding / pixelStride,
            height,
            Bitmap.Config.ARGB_8888
        )
        bitmap.copyPixelsFromBuffer(buffer)
        return Bitmap.createBitmap(bitmap, 0, 0, width, height)
    }

    private fun runOcrAndLlm(bitmap: Bitmap) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val extractedText = visionText.text
                lastCapturedOcrText = extractedText
                Log.i(TAG, "OCR Extracted ${extractedText.length} characters:\n$extractedText")
                chatMessagesHtml.clear()

                if (extractedText.isBlank()) {
                    val msg = "<b>[Screen OCR]</b>: No text detected on screen."
                    lastCapturedOcrText = "General screen content (No text blocks found via OCR)."
                    chatMessagesHtml.add(msg)
                    showResultPopup(msg)
                } else {
                    val badge = "<b>[Screen OCR Active]</b>: Converted screen image to text (${extractedText.length} chars) for LLM."
                    chatMessagesHtml.add(badge)

                    val thinkingMsg = "<b><font color='#34D399'>AI Assistant:</font></b> <i>Analyzing screen context...</i>"
                    chatMessagesHtml.add(thinkingMsg)

                    showResultPopup(badge)

                    val prompt = """
                        Captured Screen Context:
                        $extractedText
                    """.trimIndent()

                    serviceScope.launch {
                        val response = LlmEngine.generate(prompt)
                        if (chatMessagesHtml.isNotEmpty() && chatMessagesHtml.last() == thinkingMsg) {
                            chatMessagesHtml.removeAt(chatMessagesHtml.size - 1)
                        }
                        val aiResponseHtml = "<b><font color='#34D399'>AI Assistant:</font></b><br/>" + response
                        chatMessagesHtml.add(aiResponseHtml)

                        if (resultPopupView != null) {
                            renderChatPopupContent()
                        } else {
                            showResultPopup(aiResponseHtml)
                        }
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "OCR recognition failed", e)
                val msg = "<b>[OCR Fallback]</b>: Text recognition failed: ${e.localizedMessage}."
                lastCapturedOcrText = "Screen capture acquired. OCR failure: ${e.localizedMessage}"
                chatMessagesHtml.clear()
                chatMessagesHtml.add(msg)
                showResultPopup(msg)
            }
    }

    private fun parseMarkdownToSpanned(markdown: String): Spanned {
        val html = markdown
            .replace("\n", "<br/>")
            .replace(Regex("###\\s*(.*?)(<br/>|$)")) { "<h3><b><font color='#38BDF8'>${it.groupValues[1]}</font></b></h3>" }
            .replace(Regex("\\*\\*(.*?)\\*\\*")) { "<b>${it.groupValues[1]}</b>" }
            .replace(Regex("\\*(.*?)\\*")) { "<i><font color='#9CA3AF'>${it.groupValues[1]}</font></i>" }
            .replace(Regex("•\\s*(.*?)(<br/>|$)")) { "&bull; ${it.groupValues[1]}<br/>" }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
        } else {
            @Suppress("DEPRECATION")
            Html.fromHtml(html)
        }
    }

    private fun handleUserFollowUpQuery(userQuestion: String) {
        val userMsgHtml = "<br/><hr/><br/><b><font color='#38BDF8'>You:</font></b> $userQuestion<br/>"
        chatMessagesHtml.add(userMsgHtml)

        val thinkingHtml = "<b><font color='#34D399'>AI Assistant:</font></b> <i>Thinking...</i>"
        chatMessagesHtml.add(thinkingHtml)

        renderChatPopupContent()

        val prompt = """
            Captured Screen Context:
            $lastCapturedOcrText

            User query: $userQuestion
        """.trimIndent()

        serviceScope.launch {
            val response = LlmEngine.generate(prompt)
            if (chatMessagesHtml.isNotEmpty() && chatMessagesHtml.last() == thinkingHtml) {
                chatMessagesHtml.removeAt(chatMessagesHtml.size - 1)
            }

            val aiResponseHtml = "<b><font color='#34D399'>AI Assistant:</font></b> $response<br/>"
            chatMessagesHtml.add(aiResponseHtml)

            renderChatPopupContent()
        }
    }

    private var popupScrollView: ScrollView? = null
    private var popupContentView: TextView? = null

    private fun renderChatPopupContent() {
        val fullContent = chatMessagesHtml.joinToString("\n\n")
        popupContentView?.text = parseMarkdownToSpanned(fullContent)

        popupScrollView?.post {
            popupScrollView?.fullScroll(View.FOCUS_DOWN)
        }
    }

    private var currentOpacityIndex = 1 // default ~60% (0x99)
    private val opacities = listOf(
        Pair(0x330F172A.toInt(), "20%"),
        Pair(0x660F172A.toInt(), "40%"),
        Pair(0x990F172A.toInt(), "60%"),
        Pair(0xCC0F172A.toInt(), "80%"),
        Pair(0xFF0F172A.toInt(), "100%")
    )

    private fun showResultPopup(content: String) {
        removeResultPopup()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            background = GradientDrawable().apply {
                setColor(opacities[currentOpacityIndex].first)
                cornerRadius = 32f
                setStroke(2, 0xAA38BDF8.toInt())
            }
        }

        // Header (Movable by dragging header)
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }

        val titleView = TextView(this).apply {
            text = "🤖 AI Screen Helper (Draggable)"
            textSize = 14f
            setTextColor(0xFF38BDF8.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val opacityButton = Button(this).apply {
            text = "Opacity: ${opacities[currentOpacityIndex].second}"
            textSize = 10f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(12, 4, 12, 4)
            background = GradientDrawable().apply {
                setColor(0x80374151.toInt())
                cornerRadius = 16f
            }
            setOnClickListener {
                currentOpacityIndex = (currentOpacityIndex + 1) % opacities.size
                val (colorBg, label) = opacities[currentOpacityIndex]
                text = "Opacity: $label"
                val bgDrawable = layout.background as? GradientDrawable
                bgDrawable?.setColor(colorBg)
            }
        }

        val recaptureButton = Button(this).apply {
            text = "Recapture"
            textSize = 11f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(14, 4, 14, 4)
            background = GradientDrawable().apply {
                setColor(0x80374151.toInt())
                cornerRadius = 16f
            }
            setOnClickListener {
                onFloatingBubbleClicked()
            }
        }

        val closeButton = Button(this).apply {
            text = "✕"
            textSize = 14f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(14, 4, 14, 4)
            background = GradientDrawable().apply {
                setColor(0x80374151.toInt())
                cornerRadius = 16f
            }
            setOnClickListener {
                removeResultPopup()
            }
        }

        headerLayout.addView(titleView)
        headerLayout.addView(opacityButton)
        headerLayout.addView(recaptureButton)
        headerLayout.addView(closeButton)

        // Scrollable Chat Body
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            ).apply {
                setMargins(0, 4, 0, 4)
            }
        }
        popupScrollView = scrollView

        if (chatMessagesHtml.isEmpty()) {
            chatMessagesHtml.add(content)
        }

        val contentView = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFFF3F4F6.toInt())
            setLineSpacing(5f, 1.1f)
        }
        popupContentView = contentView
        scrollView.addView(contentView)

        renderChatPopupContent()

        // Bottom Chat Input Row & Resize Handle
        val bottomRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 0)
        }

        val inputEditText = EditText(this).apply {
            hint = "Ask follow-up question..."
            setHintTextColor(0x999CA3AF.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 12.5f
            setPadding(20, 14, 20, 14)
            background = GradientDrawable().apply {
                setColor(0x801E293B.toInt())
                cornerRadius = 20f
                setStroke(2, 0x80475569.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            maxLines = 3
        }

        val sendButton = Button(this).apply {
            text = "Send"
            textSize = 12f
            setTextColor(0xFFFFFFFF.toInt())
            background = GradientDrawable().apply {
                setColor(0xFF0284C7.toInt())
                cornerRadius = 20f
            }
            setOnClickListener {
                val q = inputEditText.text.toString().trim()
                if (q.isNotEmpty()) {
                    inputEditText.setText("")
                    handleUserFollowUpQuery(q)
                }
            }
        }

        val resizeHandle = TextView(this).apply {
            text = " ◢"
            textSize = 18f
            setTextColor(0xFF38BDF8.toInt())
            setPadding(8, 0, 0, 0)
        }

        bottomRow.addView(inputEditText)
        bottomRow.addView(sendButton)
        bottomRow.addView(resizeHandle)

        layout.addView(headerLayout)
        layout.addView(scrollView)
        layout.addView(bottomRow)

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val params = WindowManager.LayoutParams(
            (metrics.widthPixels * 0.94).toInt(),
            (metrics.heightPixels * 0.62).toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            @Suppress("DEPRECATION")
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        // Enable moving popup by dragging header
        headerLayout.setOnTouchListener(object : View.OnTouchListener {
            private var startX = 0f
            private var startY = 0f
            private var initialX = params.x
            private var initialY = params.y

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = event.rawX
                        startY = event.rawY
                        initialX = params.x
                        initialY = params.y
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - startX).toInt()
                        params.y = initialY + (event.rawY - startY).toInt()
                        try {
                            windowManager.updateViewLayout(layout, params)
                        } catch (_: Exception) {}
                        return true
                    }
                }
                return false
            }
        })

        // Enable resizing popup by dragging resize handle
        resizeHandle.setOnTouchListener(object : View.OnTouchListener {
            private var downX = 0f
            private var downY = 0f
            private var startW = params.width
            private var startH = params.height

            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        startW = layout.width
                        startH = layout.height
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (event.rawX - downX).toInt()
                        val dy = (event.rawY - downY).toInt()
                        params.width = maxOf(450, startW + dx)
                        params.height = maxOf(350, startH + dy)
                        try {
                            windowManager.updateViewLayout(layout, params)
                        } catch (_: Exception) {}
                        return true
                    }
                }
                return false
            }
        })

        resultPopupView = layout
        try {
            windowManager.addView(layout, params)
        } catch (e: Exception) {
            Log.e(TAG, "Error displaying result popup overlay", e)
        }
    }

    private fun removeResultPopup() {
        resultPopupView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                Log.e(TAG, "Error removing result popup overlay", e)
            }
            resultPopupView = null
        }
        popupScrollView = null
        popupContentView = null
    }
}

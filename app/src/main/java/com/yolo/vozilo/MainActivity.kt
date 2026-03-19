package com.yolo.vozilo

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.PixelCopy
import android.view.View
import android.view.Window
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.SmartButton
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import kotlin.math.atan2
import kotlin.math.sqrt

private val ThemeBlue = Color(0xFF3498DB)
private val ThemeAlert = Color(0xFFE74C3C)
private val ThemeSuccess = Color(0xFF2ECC71)

@Composable
fun VoziloTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colorScheme = if (darkTheme) {
        darkColorScheme(primary = ThemeBlue, background = Color(0xFF121212), surface = Color(0xFF1E1E1E), onSurface = Color.White)
    } else {
        lightColorScheme(primary = ThemeBlue, background = Color(0xFFFDFDFD), surface = Color(0xFFF2F9FF), onSurface = Color.Black)
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

// Helper to get Activity Window for PixelCopy
private fun Context.findActivityWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findActivityWindow()
    else -> null
}

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val httpClient = OkHttpClient()

    // --- ML Kit ---
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val objectDetector = ObjectDetection.getClient(
        ObjectDetectorOptions.Builder().setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
            .enableClassification().enableMultipleObjects().build()
    )

    // --- State ---
    private var connected by mutableStateOf(false)
    private var isCamOn by mutableStateOf(false)
    private var useJoystick by mutableStateOf(false)
    private var ocrResultText by mutableStateOf("")
    private var isOcrRunning by mutableStateOf(false)
    private var isOcrAutoPilot by mutableStateOf(false)
    private var isYoloActive by mutableStateOf(false)
    private var isFollowActive by mutableStateOf(false)
    private var detectedObjects by mutableStateOf<List<DetectedObject>>(emptyList())

    // View References for hardware extraction
    private var webViewRef: WebView? = null

    private var isRecording by mutableStateOf(false)
    private val recordedFrames = mutableListOf<Bitmap>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            VoziloTheme {
                val context = LocalContext.current
                val window = context.findActivityWindow()
                val currentColorScheme = MaterialTheme.colorScheme

                // --- Hardware Accelerated Frame Grabber Loop ---
                LaunchedEffect(isCamOn, isYoloActive, isOcrRunning, isRecording) {
                    if (!isCamOn) return@LaunchedEffect
                    while (isActive) {
                        if (isYoloActive || isOcrRunning || isRecording) {
                            webViewRef?.let { view ->
                                window?.let { win ->
                                    grabHardwareFrame(win, view) { bitmap ->
                                        if (bitmap != null) processFrame(bitmap)
                                    }
                                }
                            }
                        }
                        // Dynamic throttling: Fast for video, slow for ML battery savings
                        delay(if (isRecording) 33 else 100)
                    }
                }

                LaunchedEffect(ocrResultText, isOcrAutoPilot) {
                    if (isOcrAutoPilot && ocrResultText.isNotBlank()) {
                        val text = ocrResultText.lowercase()
                        val cmd = when {
                            "rotate" in text -> "rot_desno"
                            "left" in text -> "levo"
                            "right" in text -> "desno"
                            "back" in text -> "nazad"
                            "forward" in text -> "napred"
                            else -> null
                        }

                        if (cmd != null) {
                            isOcrAutoPilot = false
                            sendCommand(cmd)
                            delay(1500)
                            sendCommand("stop")
                            delay(500)
                            ocrResultText = ""
                            isOcrAutoPilot = true
                        }
                    }
                }

                LaunchedEffect(isFollowActive, detectedObjects) {
                    if (isFollowActive && isYoloActive && detectedObjects.isNotEmpty()) {
                        val obj = detectedObjects.first()
                        val frameWidth = webViewRef?.width ?: 640
                        val centerX = obj.boundingBox.centerX()
                        val normalizedX = centerX.toFloat() / frameWidth

                        when {
                            normalizedX < 0.35f -> sendCommand("levo")
                            normalizedX > 0.65f -> sendCommand("desno")
                            else -> sendCommand("napred")
                        }
                    } else if (isFollowActive && (detectedObjects.isEmpty() || !isYoloActive)) {
                        sendCommand("stop")
                    }
                }

                LaunchedEffect(isCamOn) {
                    if (!isCamOn) {
                        connected = false
                        isRecording = false
                        isFollowActive = false
                        isOcrAutoPilot = false
                        detectedObjects = emptyList()
                        ocrResultText = ""
                    } else {
                        connected = true
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = currentColorScheme.background) {
                    Column(Modifier.padding(30.dp)) {
                        HeaderSection(connected, isCamOn, onToggleCam = { isCamOn = !isCamOn }, onCapture = {
                            window?.let { win ->
                                webViewRef?.let { view ->
                                    grabHardwareFrame(win, view) { bitmap ->
                                        bitmap?.let {
                                            saveToGallery(it, "PI_CAP_${System.currentTimeMillis()}.jpg")
                                            Toast.makeText(context, "Photo Saved!", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            }
                        })

                        Spacer(Modifier.height(16.dp))

                        VideoSectionLive(
                            isOn = isCamOn, ocrOverlay = ocrResultText, objects = detectedObjects,
                            isOcrRunning = isOcrRunning, isOcrAutoPilot = isOcrAutoPilot,
                            isFollowActive = isFollowActive,
                            onWebViewCreated = { view -> webViewRef = view }
                        )

                        AnimatedVisibility(visible = isCamOn, enter = fadeIn() + expandVertically()) {
                            Column {
                                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    FeaturePill("YOLO", Icons.Default.TrackChanges, Modifier.weight(1f), if (isYoloActive) ThemeSuccess else ThemeBlue) {
                                        isYoloActive = !isYoloActive
                                        if (!isYoloActive) {
                                            detectedObjects = emptyList()
                                            isFollowActive = false
                                        }
                                    }

                                    FeaturePill(
                                        label = if (isRecording) "STOP" else "RECORD",
                                        icon = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                                        modifier = Modifier.weight(1f),
                                        backgroundColor = if (isRecording) ThemeAlert else ThemeBlue
                                    ) {
                                        if (!isRecording) {
                                            synchronized(recordedFrames) { recordedFrames.clear() }
                                            isRecording = true
                                        } else {
                                            isRecording = false
                                            scope.launch(Dispatchers.IO) { createMp4Natively(context) }
                                        }
                                    }

                                    IconButton(
                                        onClick = {
                                            isOcrRunning = !isOcrRunning
                                            if (!isOcrRunning) {
                                                isOcrAutoPilot = false
                                                ocrResultText = ""
                                            }
                                        },
                                        modifier = Modifier
                                            .background(if (isOcrRunning) ThemeSuccess else currentColorScheme.surface, RoundedCornerShape(12.dp))
                                            .size(48.dp)
                                    ) {
                                        Icon(Icons.Default.TextFields, null, tint = if (isOcrRunning) Color.White else ThemeBlue)
                                    }
                                }

                                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (isYoloActive) {
                                        FeaturePill(
                                            label = if (isFollowActive) "FOLLOWING" else "FOLLOW",
                                            icon = Icons.AutoMirrored.Filled.DirectionsRun,
                                            modifier = Modifier.weight(1f),
                                            backgroundColor = if (isFollowActive) ThemeSuccess else Color.Gray
                                        ) {
                                            isFollowActive = !isFollowActive
                                            if (!isFollowActive) sendCommand("stop")
                                        }
                                    }

                                    if (isOcrRunning) {
                                        FeaturePill(
                                            label = if (isOcrAutoPilot) "AUTO ON" else "AUTO OFF",
                                            icon = Icons.Default.SmartButton,
                                            modifier = Modifier.weight(1f),
                                            backgroundColor = if (isOcrAutoPilot) ThemeSuccess else Color.Gray
                                        ) {
                                            isOcrAutoPilot = !isOcrAutoPilot
                                            if (!isOcrAutoPilot) sendCommand("stop")
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))

                        Row(Modifier.fillMaxWidth(), Arrangement.End, Alignment.CenterVertically) {
                            Text("JOYSTICK", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = ThemeBlue.copy(0.5f))
                            Spacer(Modifier.width(8.dp))
                            Switch(checked = useJoystick, onCheckedChange = { useJoystick = it })
                        }

                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (useJoystick) {
                                CircularJoystick { sendCommand(it) }
                            } else {
                                CompactDPad(onStart = { sendCommand(it) }, onStop = { sendCommand("stop") })
                            }
                        }
                    }
                }
            }
        }
    }

    private fun sendCommand(cmd: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val json = JSONObject().apply { put("cmd", cmd) }.toString()
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("http://pametno-vozilo.local:1607/control")
                    .post(body)
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("HTTP_CMD", "Unexpected code $response")
                    }
                }
            } catch (e: Exception) {
                Log.e("HTTP_CMD", "Send failed: ${e.message}")
            }
        }
    }

    private fun grabHardwareFrame(window: Window, view: View, onBitmapReady: (Bitmap?) -> Unit) {
        if (view.width <= 0 || view.height <= 0) {
            onBitmapReady(null)
            return
        }

        val bitmap = createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val rect = Rect(
            location[0],
            location[1],
            location[0] + view.width,
            location[1] + view.height
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PixelCopy.request(
                window,
                rect,
                bitmap,
                { copyResult ->
                    if (copyResult == PixelCopy.SUCCESS) {
                        onBitmapReady(bitmap)
                    } else {
                        onBitmapReady(null)
                    }
                },
                Handler(Looper.getMainLooper())
            )
        } else {
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            onBitmapReady(bitmap)
        }
    }

    private fun processFrame(bitmap: Bitmap) {
        if (isRecording) {
            synchronized(recordedFrames) { recordedFrames.add(bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)) }
        }

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        if (isOcrRunning) {
            recognizer.process(inputImage).addOnSuccessListener { visionText ->
                val detected = visionText.text.lines().firstOrNull { it.isNotBlank() } ?: ""
                if (detected != ocrResultText) ocrResultText = detected
            }
        }

        if (isYoloActive) {
            objectDetector.process(inputImage).addOnSuccessListener { objects -> detectedObjects = objects }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer.close()
        objectDetector.close()
        scope.cancel()
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    fun VideoSectionLive(
        isOn: Boolean,
        ocrOverlay: String,
        objects: List<DetectedObject>,
        isOcrRunning: Boolean,
        isOcrAutoPilot: Boolean,
        isFollowActive: Boolean,
        onWebViewCreated: (WebView) -> Unit
    ) {
        val textPaint = remember { Paint().apply { textSize = 38f; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(3f, 2f, 2f, android.graphics.Color.BLACK) } }

        Card(modifier = Modifier.fillMaxWidth().height(220.dp), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, ThemeBlue.copy(0.1f)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
                if (isOn) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                onWebViewCreated(this)
                                settings.apply {
                                    javaScriptEnabled = true
                                    loadWithOverviewMode = true
                                    useWideViewPort = true
                                    cacheMode = WebSettings.LOAD_NO_CACHE
                                    // FIX 1: Allow HTTP inside the WebView
                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                }
                                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                                setBackgroundColor(android.graphics.Color.BLACK)
                            }
                        },
                        update = { webView ->
                            if (webView.url == null || webView.url == "about:blank") {
                                val html = "<html><body style='margin:0;padding:0;background-color:black;'><img src='http://pametno-vozilo.local:1607/video_feed' width='100%' height='100%' style='object-fit:fill;' /></body></html>"
                                webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    Canvas(Modifier.fillMaxSize()) {
                        objects.forEach { obj ->
                            val box = obj.boundingBox
                            val rectColor = if(isFollowActive) ThemeSuccess else Color.Yellow
                            drawRect(color = rectColor, topLeft = Offset(box.left.toFloat(), box.top.toFloat()), size = Size(box.width().toFloat(), box.height().toFloat()), style = Stroke(width = 2.dp.toPx()))
                            obj.labels.firstOrNull { it.confidence > 0.4f }?.let { label ->
                                drawContext.canvas.nativeCanvas.drawText("${label.text.uppercase()} ${(label.confidence * 100).toInt()}%", box.left.toFloat(), box.top.toFloat() - 15f, textPaint.apply { color = rectColor.toArgb() })
                            }
                        }
                    }

                    if (isOcrRunning && ocrOverlay.isNotBlank()) {
                        Box(Modifier.align(Alignment.BottomStart).padding(10.dp).background(Color.Black.copy(0.6f), RoundedCornerShape(6.dp)).padding(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.TextFields, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(ocrOverlay, color = if(isOcrAutoPilot) ThemeSuccess else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    Text("STREAM STANDBY", color = ThemeBlue.copy(0.4f), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
        }
    }

    @Composable
    fun HeaderSection(isConnected: Boolean, isCamOn: Boolean, onToggleCam: () -> Unit, onCapture: () -> Unit) {
        val currentColorScheme = MaterialTheme.colorScheme
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text("YOLO VOZILO", fontSize = 24.sp, fontWeight = FontWeight.Black, color = ThemeBlue)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).background(if(isConnected) ThemeSuccess else ThemeAlert, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(text = if(isConnected) "ONLINE" else "OFFLINE", color = if(isConnected) ThemeSuccess else ThemeAlert, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCamOn) {
                    IconButton(onClick = onCapture, Modifier.padding(end = 8.dp).background(currentColorScheme.surface, CircleShape)) {
                        Icon(Icons.Default.CameraAlt, null, tint = ThemeBlue)
                    }
                }
                IconButton(onClick = onToggleCam, modifier = Modifier.background(if(isCamOn) ThemeBlue else currentColorScheme.surface, CircleShape)) {
                    Icon(if(isCamOn) Icons.Default.Videocam else Icons.Default.VideocamOff, null, tint = if(isCamOn) Color.White else ThemeBlue)
                }
            }
        }
    }

    @Composable
    fun CompactDPad(onStart: (String) -> Unit, onStop: () -> Unit) {
        Box(Modifier.size(240.dp)) {
            val btnSize = 65.dp
            DPadBtn("▲", Alignment.TopCenter, btnSize, "napred", onStart, onStop)
            DPadBtn("▼", Alignment.BottomCenter, btnSize, "nazad", onStart, onStop)
            DPadBtn("◀", Alignment.CenterStart, btnSize, "levo", onStart, onStop)
            DPadBtn("▶", Alignment.CenterEnd, btnSize, "desno", onStart, onStop)
            RotationBtn(Icons.AutoMirrored.Filled.RotateLeft, Alignment.BottomStart, "rot_levo", onStart, onStop)
            RotationBtn(Icons.AutoMirrored.Filled.RotateRight, Alignment.BottomEnd, "rot_desno", onStart, onStop)
        }
    }

    // FIX 2: Replaced detectTapGestures with awaitEachGesture for reliable continuous pressing
    @Composable
    fun BoxScope.DPadBtn(label: String, btnAlign: Alignment, size: androidx.compose.ui.unit.Dp, cmd: String, onStart: (String) -> Unit, onStop: () -> Unit) {
        var isPressed by remember { mutableStateOf(false) }
        val currentColorScheme = MaterialTheme.colorScheme

        LaunchedEffect(isPressed) {
            if (isPressed) {
                while (true) {
                    onStart(cmd)
                    delay(500)
                }
            }
        }

        Surface(
            modifier = Modifier
                .size(size)
                .align(btnAlign)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        isPressed = true
                        waitForUpOrCancellation()
                        isPressed = false
                        onStop()
                    }
                },
            shape = RoundedCornerShape(16.dp), color = if (isPressed) ThemeBlue else currentColorScheme.surface, border = BorderStroke(1.dp, ThemeBlue.copy(0.1f))
        ) { Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 24.sp, color = if (isPressed) Color.White else ThemeBlue) } }
    }

    // FIX 3: Same touch fix applied here
    @Composable
    fun BoxScope.RotationBtn(icon: ImageVector, btnAlign: Alignment, cmd: String, onStart: (String) -> Unit, onStop: () -> Unit) {
        var isPressed by remember { mutableStateOf(false) }
        val currentColorScheme = MaterialTheme.colorScheme

        LaunchedEffect(isPressed) {
            if (isPressed) {
                while (true) {
                    onStart(cmd)
                    delay(500)
                }
            }
        }

        Surface(
            modifier = Modifier
                .size(56.dp)
                .align(btnAlign)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown()
                        isPressed = true
                        waitForUpOrCancellation()
                        isPressed = false
                        onStop()
                    }
                },
            shape = CircleShape, color = if (isPressed) ThemeBlue else currentColorScheme.surface, border = BorderStroke(1.dp, ThemeBlue.copy(0.2f))
        ) { Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(28.dp), if (isPressed) Color.White else ThemeBlue) } }
    }

    @Composable
    fun CircularJoystick(onCmd: (String) -> Unit) {
        var offX by remember { mutableFloatStateOf(0f) }
        var offY by remember { mutableFloatStateOf(0f) }
        var activeCmd by remember { mutableStateOf<String?>(null) }
        val radius = 100f
        val currentColorScheme = MaterialTheme.colorScheme

        LaunchedEffect(activeCmd) {
            activeCmd?.let { cmd ->
                while (true) {
                    onCmd(cmd)
                    delay(500)
                }
            }
        }

        Box(
            Modifier.size(200.dp).background(currentColorScheme.surface, CircleShape).border(1.dp, ThemeBlue.copy(0.1f), CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = {
                        offX = 0f; offY = 0f
                        activeCmd = null
                        onCmd("stop")
                    }) { change, drag ->
                        change.consume()
                        val nX = offX + drag.x
                        val nY = offY + drag.y
                        val dist = sqrt(nX * nX + nY * nY)
                        val factor = if (dist > radius) radius / dist else 1f
                        offX = nX * factor
                        offY = nY * factor

                        if (dist > 40f) {
                            val angle = Math.toDegrees(atan2(offY.toDouble(), offX.toDouble()))
                            val cmd = when {
                                angle > -45 && angle <= 45 -> "desno"
                                angle > 45 && angle <= 135 -> "nazad"
                                angle > -135 && angle <= -45 -> "napred"
                                else -> "levo"
                            }
                            if (activeCmd != cmd) {
                                activeCmd = cmd
                            }
                        } else {
                            if (activeCmd != null) {
                                activeCmd = null
                                onCmd("stop")
                            }
                        }
                    }
                }, Alignment.Center
        ) {
            Box(Modifier.offset { IntOffset(offX.toInt(), offY.toInt()) }.size(60.dp).background(ThemeBlue, CircleShape).border(3.dp, Color.White, CircleShape))
        }
    }

    @Composable
    fun FeaturePill(label: String, icon: ImageVector, modifier: Modifier, backgroundColor: Color = ThemeBlue, onClick: () -> Unit) {
        Surface(onClick = onClick, modifier = modifier.height(48.dp), shape = RoundedCornerShape(12.dp), color = backgroundColor) {
            Row(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(18.dp), Color.White)
                Spacer(Modifier.width(8.dp))
                Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createMp4Natively(context: Context) {
        val frames = synchronized(recordedFrames) { recordedFrames.toList() }
        if (frames.isEmpty()) return
        val width = 640
        val height = 480
        val outputFile = File(context.cacheDir, "temp_video.mp4")
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 1500000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 20)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()
            val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()
            frames.forEachIndexed { i, bitmap ->
                val canvas = surface.lockCanvas(null)
                canvas.drawBitmap(bitmap.scale(width, height), 0f, 0f, null)
                surface.unlockCanvasAndPost(canvas)
                var dequeued = false
                while (!dequeued) {
                    val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 5000)
                    if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (outIdx >= 0) {
                        val data = encoder.getOutputBuffer(outIdx)
                        if (muxerStarted && data != null) {
                            bufferInfo.presentationTimeUs = (i * 1000000L / 20)
                            muxer.writeSampleData(trackIndex, data, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIdx, false)
                        dequeued = true
                    } else if (outIdx == MediaCodec.INFO_TRY_AGAIN_LATER) { dequeued = true }
                }
            }
            encoder.stop()
            encoder.release()
            if (muxerStarted) { muxer.stop(); muxer.release(); saveVideoToGallery(outputFile) }
            scope.launch(Dispatchers.Main) { Toast.makeText(context, "Video Saved to Gallery!", Toast.LENGTH_SHORT).show() }
        } catch (e: Exception) { Log.e("Media", "Encoding error: ${e.message}") }
    }

    private fun saveVideoToGallery(file: File) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "VOZILO_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/YoloVozilo")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let { dest -> contentResolver.openOutputStream(dest)?.use { out -> file.inputStream().use { input -> input.copyTo(out) } } }
    }

    private fun saveToGallery(bitmap: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/YoloVozilo")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let { dest -> contentResolver.openOutputStream(dest)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) } }
    }
}
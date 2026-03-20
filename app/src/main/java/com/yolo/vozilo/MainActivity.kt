package com.yolo.vozilo

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.*
import android.media.*
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.scale
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
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

class MainActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val httpClient = OkHttpClient()

    // --- HTTP Networking ---
    private var streamJob: Job? = null

    // --- Command State ---
    private var currentCommand by mutableStateOf("stop")

    // --- ML Kit OCR (Kept local for Android) ---
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // --- App State ---
    private var connected by mutableStateOf(false)
    private var isCamOn by mutableStateOf(false)
    private var useJoystick by mutableStateOf(false)
    private var ocrResultText by mutableStateOf("")
    private var isOcrRunning by mutableStateOf(false)
    private var isOcrAutoPilot by mutableStateOf(false)

    // --- New Remote AI State ---
    private var isRemoteDetectionOn by mutableStateOf(false)
    private var isRemoteFollowOn by mutableStateOf(false)

    private var currentFrame by mutableStateOf<Bitmap?>(null)
    private var isRecording by mutableStateOf(false)
    private val recordedFrames = mutableListOf<Bitmap>()
    private var lastFrameProcessTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Start the background network loop for manual commands
        startCommandLoop()

        setContent {
            VoziloTheme {
                val context = LocalContext.current
                val currentColorScheme = MaterialTheme.colorScheme

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
                            currentCommand = cmd
                            delay(1500)
                            currentCommand = "stop"
                            delay(500)
                            ocrResultText = ""
                            isOcrAutoPilot = true
                        }
                    }
                }

                LaunchedEffect(isCamOn) {
                    if (isCamOn) {
                        startHttpStream()
                    } else {
                        stopHttpStream()
                        currentFrame = null
                        isRecording = false
                        isRemoteDetectionOn = false
                        isRemoteFollowOn = false
                        isOcrAutoPilot = false
                        ocrResultText = ""
                        // Ensure AI shuts off if stream stops
                        togglePiAI("DETECTION", false)
                        togglePiAI("FOLLOW", false)
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = currentColorScheme.background) {
                    Column(Modifier.padding(30.dp)) {
                        HeaderSection(connected, isCamOn, onToggleCam = { isCamOn = !isCamOn }, onCapture = {
                            currentFrame?.let {
                                saveToGallery(it, "PI_CAP_${System.currentTimeMillis()}.jpg")
                                Toast.makeText(context, "Photo Saved!", Toast.LENGTH_SHORT).show()
                            }
                        })

                        Spacer(Modifier.height(16.dp))

                        // Video Section (No longer draws YOLO boxes, just shows stream)
                        VideoSectionLive(
                            isOn = isCamOn,
                            ocrOverlay = ocrResultText,
                            isOcrRunning = isOcrRunning,
                            isOcrAutoPilot = isOcrAutoPilot,
                            frame = currentFrame
                        )

                        AnimatedVisibility(visible = isCamOn, enter = fadeIn() + expandVertically()) {
                            Column {
                                // ROW 1: VISION & RECORD
                                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    FeaturePill(
                                        label = if (isRemoteDetectionOn) "VISION ON" else "VISION OFF",
                                        icon = Icons.Default.Visibility,
                                        modifier = Modifier.weight(1f),
                                        backgroundColor = if (isRemoteDetectionOn) ThemeSuccess else ThemeBlue
                                    ) {
                                        togglePiAI("DETECTION", !isRemoteDetectionOn)
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

                                // ROW 2: FOLLOW & OCR AUTO
                                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    if (isRemoteDetectionOn) {
                                        FeaturePill(
                                            label = if (isRemoteFollowOn) "FOLLOWING" else "FOLLOW",
                                            icon = Icons.AutoMirrored.Filled.DirectionsRun,
                                            modifier = Modifier.weight(1f),
                                            backgroundColor = if (isRemoteFollowOn) ThemeSuccess else Color.Gray
                                        ) {
                                            togglePiAI("FOLLOW", !isRemoteFollowOn)
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
                                            if (!isOcrAutoPilot) currentCommand = "stop"
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
                                CircularJoystick(currentCmd = currentCommand, onCmdChange = { currentCommand = it })
                            } else {
                                CompactDPad(currentCmd = currentCommand, onCmdChange = { currentCommand = it })
                            }
                        }
                    }
                }
            }
        }
    }

    // --- RPi AI Toggle Function ---
    private fun togglePiAI(type: String, enable: Boolean) {
        val endpoint = if (type == "DETECTION") "toggle_detection" else "toggle_follow"
        val json = JSONObject().apply { put("enable", enable) }.toString()
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("http://pametno-vozilo.local:1607/$endpoint")
            .post(body)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                scope.launch(Dispatchers.Main) {
                    Log.e("PI_AI", "Connection failed for $type")
                }
            }
            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    scope.launch(Dispatchers.Main) {
                        if (type == "DETECTION") {
                            isRemoteDetectionOn = enable
                            // If we turn vision off, force follow off for safety
                            if (!enable && isRemoteFollowOn) togglePiAI("FOLLOW", false)
                        } else {
                            isRemoteFollowOn = enable
                        }
                    }
                }
            }
        })
    }

    private fun startCommandLoop() {
        scope.launch(Dispatchers.IO) {
            var lastSentCommand = ""
            var lastSentTime = 0L

            while (isActive) {
                val commandToSend = currentCommand
                val now = System.currentTimeMillis()

                if (commandToSend != lastSentCommand || (commandToSend != "stop" && now - lastSentTime > 1500)) {
                    sendNetworkCommand(commandToSend)
                    lastSentCommand = commandToSend
                    lastSentTime = now
                }
                delay(50)
            }
        }
    }

    private fun sendNetworkCommand(cmd: String) {
        try {
            val json = JSONObject().apply { put("cmd", cmd) }.toString()
            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("http://pametno-vozilo.local:1607/control")
                .post(body)
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) Log.e("HTTP_CMD", "Unexpected code $response")
            }
        } catch (e: Exception) { Log.e("HTTP_CMD", "Send failed: ${e.message}") }
    }

    private fun startHttpStream() {
        streamJob?.cancel()
        streamJob = scope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder().url("http://pametno-vozilo.local:1607/video_feed").build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        connected = false; return@launch
                    }
                    connected = true
                    val inputStream = response.body.byteStream()
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    val streamBuffer = ByteArrayOutputStream()

                    while (isActive) {
                        bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) break

                        streamBuffer.write(buffer, 0, bytesRead)
                        val data = streamBuffer.toByteArray()
                        val startIndex = findJpegStart(data)
                        val endIndex = findJpegEnd(data, startIndex)

                        if (startIndex != -1 && endIndex != -1) {
                            val jpegData = data.copyOfRange(startIndex, endIndex + 2)
                            val bitmap = BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size)
                            if (bitmap != null) processFrame(bitmap)

                            val remainingData = data.copyOfRange(endIndex + 2, data.size)
                            streamBuffer.reset()
                            streamBuffer.write(remainingData)
                        } else if (streamBuffer.size() > 5 * 1024 * 1024) { streamBuffer.reset() }
                    }
                }
            } catch (e: Exception) {
                Log.e("HTTP_STREAM", "Stream error", e)
                connected = false
            }
        }
    }

    private fun findJpegStart(data: ByteArray): Int {
        for (i in 0 until data.size - 1) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte()) return i
        }
        return -1
    }

    private fun findJpegEnd(data: ByteArray, startIndex: Int): Int {
        if (startIndex == -1) return -1
        for (i in startIndex until data.size - 1) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD9.toByte()) return i
        }
        return -1
    }

    private fun stopHttpStream() {
        streamJob?.cancel()
        streamJob = null
        connected = false
    }

    // --- Simplified Process Frame (No Local YOLO) ---
    private fun processFrame(bitmap: Bitmap) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastFrameProcessTime < 100) return // Throttled for battery life
        lastFrameProcessTime = currentTime

        scope.launch(Dispatchers.Main) {
            currentFrame = bitmap
            if (isRecording) {
                synchronized(recordedFrames) { recordedFrames.add(bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)) }
            }

            if (isOcrRunning) {
                val inputImage = InputImage.fromBitmap(bitmap, 0)
                recognizer.process(inputImage).addOnSuccessListener { visionText ->
                    val detected = visionText.text.lines().firstOrNull { it.isNotBlank() } ?: ""
                    if (detected != ocrResultText) ocrResultText = detected
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHttpStream()
        recognizer.close()
        scope.cancel()
    }

    // --- Simplified Video Section (Stream handles drawing) ---
    @Composable
    fun VideoSectionLive(isOn: Boolean, ocrOverlay: String, isOcrRunning: Boolean, isOcrAutoPilot: Boolean, frame: Bitmap?) {
        Card(modifier = Modifier.fillMaxWidth().height(220.dp), shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, ThemeBlue.copy(0.1f)), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Box(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
                if (isOn && frame != null) {
                    // Just show the raw stream. If Remote Detection is on, the RPi will have drawn the boxes onto this frame already.
                    Image(bitmap = frame.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)

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
    fun CompactDPad(currentCmd: String, onCmdChange: (String) -> Unit) {
        Box(Modifier.size(240.dp)) {
            val btnSize = 65.dp
            DPadBtn("▲", Alignment.TopCenter, btnSize, "napred", currentCmd, onCmdChange)
            DPadBtn("▼", Alignment.BottomCenter, btnSize, "nazad", currentCmd, onCmdChange)
            DPadBtn("◀", Alignment.CenterStart, btnSize, "levo", currentCmd, onCmdChange)
            DPadBtn("▶", Alignment.CenterEnd, btnSize, "desno", currentCmd, onCmdChange)
            RotationBtn(Icons.AutoMirrored.Filled.RotateLeft, Alignment.BottomStart, "rot_levo", currentCmd, onCmdChange)
            RotationBtn(Icons.AutoMirrored.Filled.RotateRight, Alignment.BottomEnd, "rot_desno", currentCmd, onCmdChange)
        }
    }

    @Composable
    fun BoxScope.DPadBtn(label: String, btnAlign: Alignment, size: androidx.compose.ui.unit.Dp, targetCmd: String, currentCmd: String, onCmdChange: (String) -> Unit) {
        val currentColorScheme = MaterialTheme.colorScheme
        val isPressed = currentCmd == targetCmd

        Surface(
            modifier = Modifier
                .size(size)
                .align(btnAlign)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitFirstDown(requireUnconsumed = false)
                            onCmdChange(targetCmd)
                            do {
                                val event = awaitPointerEvent()
                            } while (event.changes.any { it.pressed })
                            onCmdChange("stop")
                        }
                    }
                },
            shape = RoundedCornerShape(16.dp),
            color = if (isPressed) ThemeBlue else currentColorScheme.surface,
            border = BorderStroke(1.dp, ThemeBlue.copy(0.1f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(label, fontSize = 24.sp, color = if (isPressed) Color.White else ThemeBlue)
            }
        }
    }

    @Composable
    fun BoxScope.RotationBtn(icon: ImageVector, btnAlign: Alignment, targetCmd: String, currentCmd: String, onCmdChange: (String) -> Unit) {
        val currentColorScheme = MaterialTheme.colorScheme
        val isPressed = currentCmd == targetCmd

        Surface(
            modifier = Modifier
                .size(56.dp)
                .align(btnAlign)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitFirstDown(requireUnconsumed = false)
                            onCmdChange(targetCmd)
                            do {
                                val event = awaitPointerEvent()
                            } while (event.changes.any { it.pressed })
                            onCmdChange("stop")
                        }
                    }
                },
            shape = CircleShape,
            color = if (isPressed) ThemeBlue else currentColorScheme.surface,
            border = BorderStroke(1.dp, ThemeBlue.copy(0.2f))
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(28.dp), if (isPressed) Color.White else ThemeBlue)
            }
        }
    }

    @Composable
    fun CircularJoystick(currentCmd: String, onCmdChange: (String) -> Unit) {
        var offX by remember { mutableFloatStateOf(0f) }
        var offY by remember { mutableFloatStateOf(0f) }
        val radius = 100f
        val currentColorScheme = MaterialTheme.colorScheme

        Box(
            Modifier.size(200.dp).background(currentColorScheme.surface, CircleShape).border(1.dp, ThemeBlue.copy(0.1f), CircleShape)
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = {
                        offX = 0f; offY = 0f
                        onCmdChange("stop")
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
                            if (currentCmd != cmd) {
                                onCmdChange(cmd)
                            }
                        } else {
                            if (currentCmd != "stop") {
                                onCmdChange("stop")
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
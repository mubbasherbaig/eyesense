package com.example.eyesense

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.sqrt
import android.os.VibrationEffect
import android.os.Vibrator
import java.util.concurrent.atomic.AtomicInteger

// ── Camera mode ───────────────────────────────────────────────────────────────
private enum class CameraMode { LOCAL, TCP }

class MainActivity : FlutterActivity() {

    private val METHOD_CHANNEL = "com.example.eyesense/tof"
    private val EVENT_CHANNEL  = "com.example.eyesense/depth_stream"
    private val CAMERA_PERM    = 201

    private var eventSink: EventChannel.EventSink? = null

    // ── Camera2 ───────────────────────────────────────────────────────────────
    private var cameraDevice:   CameraDevice?         = null
    private var captureSession: CameraCaptureSession?  = null
    private var imageReader:    ImageReader?            = null
    private var cameraThread:   HandlerThread?          = null
    private var cameraHandler:  Handler?                = null
    private val cameraRunning   = AtomicBoolean(false)
    private var sensorOrientation = 90

    // ── Mode ──────────────────────────────────────────────────────────────────
    @Volatile private var cameraMode  = CameraMode.LOCAL
    @Volatile private var activeView  = "mozart"   // "mozart" | "depth" | "confidence" | "gradient" | "n2asym"

    // ── TCP streaming ─────────────────────────────────────────────────────────
    private var laptopIp:   String = ""
    private var laptopPort: Int    = 9999
    @Volatile private var tcpSocket: Socket?       = null
    @Volatile private var tcpStream: OutputStream? = null
    private val streaming    = AtomicBoolean(false)
    private val frameCounter = AtomicLong(0)

    // ── Raw value logging ─────────────────────────────────────────────────────
    private val fileExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    @Volatile private var rawCsvWriter: FileWriter? = null
    private val csvTimeFmt  = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).also {
        it.timeZone = java.util.TimeZone.getDefault()
    }
    private val sessionTag  = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).also {
        it.timeZone = java.util.TimeZone.getDefault()
    }.format(Date())
    private val lastDumpMs  = AtomicLong(0L)

    // ── Burst capture ─────────────────────────────────────────────────────────
    private val burstCapturing   = AtomicBoolean(false)
    private val burstFrameCount  = AtomicInteger(0)
    private val BURST_TOTAL      = 30
    @Volatile private var currentSessionDir: File? = null
    private val captureExecutor  = Executors.newSingleThreadExecutor()
    @Volatile private var lastBurstCenterMm = 0

    // ── ROI snapshot (latest frame, for captureRoi) ───────────────────────────
    @Volatile private var roiDepthF: FloatArray? = null
    @Volatile private var roiConf3F: IntArray?   = null
    @Volatile private var roiW: Int = 0
    @Volatile private var roiH: Int = 0

    // ── Auto-capture ──────────────────────────────────────────────────────────
    private val autoCaptureEnabled   = AtomicBoolean(false)
    private val autoCaptureTriggered = AtomicBoolean(false)
    private val distanceHistory      = ArrayList<Int>()
    private val DIST_HISTORY_SIZE    = 10
    private val STABLE_MIN_MM        = 200
    private val STABLE_MAX_MM        = 300
    private val STABLE_VAR_MM        = 10
    private val STABLE_MS            = 1500L
    @Volatile private var stabilityStartMs = 0L

    // ── Engine setup ──────────────────────────────────────────────────────────

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, METHOD_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "requestPermission" -> {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED) {
                            result.success(true)
                        } else {
                            ActivityCompat.requestPermissions(
                                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERM)
                            result.success(false)
                        }
                    }
                    "startCamera" -> {
                        cameraMode = CameraMode.LOCAL
                        frameCounter.set(0)
                        if (!cameraRunning.get()) openDepthCamera()
                        result.success(null)
                    }
                    "stopCamera" -> {
                        cameraRunning.set(false)
                        closeCameraResources()
                        result.success(null)
                    }
                    "setView" -> {
                        activeView = call.arguments as? String ?: "mozart"
                        result.success(null)
                    }
                    "startStreaming" -> {
                        laptopIp   = call.argument<String>("ip")  ?: ""
                        laptopPort = call.argument<Int>("port")    ?: 9999
                        if (laptopIp.isBlank()) { result.error("BAD_IP", "Empty IP", null); return@setMethodCallHandler }
                        startTcpConnection()
                        result.success(null)
                    }
                    "stopStreaming" -> {
                        stopTcpConnection()
                        result.success(null)
                    }
                    "startBurstCapture" -> {
                        if (!burstCapturing.get()) startBurstSession()
                        result.success(null)
                    }
                    "toggleAutoCapture" -> {
                        val enabled = call.arguments as? Boolean ?: !autoCaptureEnabled.get()
                        autoCaptureEnabled.set(enabled)
                        autoCaptureTriggered.set(false)
                        stabilityStartMs = 0L
                        synchronized(distanceHistory) { distanceHistory.clear() }
                        result.success(null)
                    }
                    "resetAutoCapture" -> {
                        autoCaptureTriggered.set(false)
                        stabilityStartMs = 0L
                        result.success(null)
                    }
                    "captureRoi" -> {
                        val d  = roiDepthF?.copyOf()
                        val c  = roiConf3F?.copyOf()
                        val fw = roiW;  val fh = roiH
                        if (d == null || c == null || fw == 0 || fh == 0) {
                            result.error("NO_FRAME", "No depth frame available yet", null)
                        } else {
                            val sessDir = currentSessionDir ?: run {
                                val fmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                                val baseDir = getExternalFilesDir(null) ?: filesDir
                                val sd = File(baseDir, "EyeSense/session_${fmt.format(Date())}")
                                sd.mkdirs()
                                currentSessionDir = sd
                                sd
                            }
                            fileExecutor.execute {
                                try {
                                    saveRoiCsv(sessDir, d, c, fw, fh)
                                    runOnUiThread { result.success(sessDir.absolutePath) }
                                } catch (e: Exception) {
                                    runOnUiThread { result.error("SAVE_FAIL", e.message, null) }
                                }
                            }
                        }
                    }
                    else -> result.notImplemented()
                }
            }

        EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENT_CHANNEL)
            .setStreamHandler(object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, sink: EventChannel.EventSink) { eventSink = sink }
                override fun onCancel(arguments: Any?) { eventSink = null }
            })
    }

    // ── TCP connection ────────────────────────────────────────────────────────

    private fun startTcpConnection() {
        Thread {
            try {
                pushStatus("connecting", "Connecting to $laptopIp:$laptopPort…")
                val sock = Socket(laptopIp, laptopPort)
                sock.tcpNoDelay = true
                sock.setSendBufferSize(2 * 1024 * 1024)
                tcpSocket = sock
                tcpStream = sock.getOutputStream()
                streaming.set(true)
                frameCounter.set(0)
                cameraMode = CameraMode.TCP
                runOnUiThread { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                pushStatus("connected", "Connected — streaming to laptop…")
                // Ensure camera is running
                if (!cameraRunning.get()) openDepthCamera()
            } catch (e: Exception) {
                pushStatus("error", "TCP connect failed: ${e.message}")
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun stopTcpConnection() {
        streaming.set(false)
        cameraMode = CameraMode.LOCAL
        runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        try { tcpSocket?.close() } catch (_: Exception) {}
        tcpSocket = null; tcpStream = null
        pushStatus("idle", "Disconnected")
    }

    // ── Burst capture helpers ─────────────────────────────────────────────────

    private fun startBurstSession() {
        val sessionFmt = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val sessionName = "session_${sessionFmt.format(Date())}"
        val baseDir = getExternalFilesDir(null) ?: filesDir
        val sessDir = File(baseDir, "EyeSense/$sessionName")
        sessDir.mkdirs()
        currentSessionDir = sessDir
        burstFrameCount.set(0)
        lastBurstCenterMm = 0
        burstCapturing.set(true)
        pushCaptureProgress(0, BURST_TOTAL)
        android.util.Log.i("EyeSense", "Burst session: ${sessDir.absolutePath}")
    }

    private fun triggerAutoCapture() {
        val vibrator = getSystemService(VIBRATOR_SERVICE) as? Vibrator
        vibrator?.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        startBurstSession()
    }

    private fun renderClean(depthF: FloatArray, conf3F: IntArray, maxD: Float, w: Int, h: Int, view: String): Bitmap {
        val total = w * h
        val pixels = IntArray(total)
        when (view) {
            "mozart" -> {
                val mozVals = FloatArray(total)
                var mozMax = 0f
                for (i in 0 until total) {
                    val conf3      = conf3F[i]
                    val depthratio = if (conf3 == 0) 7 else (conf3 - 1)
                    val depthPct   = if (conf3 == 0) 1f else (conf3 - 1) * (1f / 7f)
                    val n2         = depthPct * (depthF[i] / maxD)
                    val mz         = sqrt(n2 * n2 * depthratio.toFloat())
                    mozVals[i] = mz
                    if (mz > mozMax) mozMax = mz
                }
                val norm = if (mozMax > 0f) mozMax else 1f
                for (i in 0 until total) {
                    val g = ((mozVals[i] / norm) * 255f).toInt().coerceIn(0, 255)
                    pixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                }
            }
            "depth" -> {
                for (i in 0 until total) pixels[i] = jetColormap(depthF[i] / maxD)
            }
            "confidence" -> {
                for (i in 0 until total) {
                    val c = conf3F[i]
                    val g = if (c == 0) 128 else ((c - 1) * 255 / 6)
                    pixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                }
            }
        }
        val raw = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val matrix = android.graphics.Matrix().apply { postRotate(sensorOrientation.toFloat()) }
        val bmp = Bitmap.createBitmap(raw, 0, 0, w, h, matrix, false)
        raw.recycle()
        return bmp
    }

    private fun saveBurstFrame(
        sessionDir: File, frameNum: Int,
        depthF: FloatArray, conf3F: IntArray,
        maxD: Float, w: Int, h: Int,
        rawBytes: ByteArray
    ) {
        try {
            val frameDir = File(sessionDir, "frame_%03d".format(frameNum))
            frameDir.mkdirs()
            // Mozart PNG (no overlay)
            val mozBmp = renderClean(depthF, conf3F, maxD, w, h, "mozart")
            File(frameDir, "mozart.png").outputStream().use { mozBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            mozBmp.recycle()
            // Depth PNG (no overlay)
            val depBmp = renderClean(depthF, conf3F, maxD, w, h, "depth")
            File(frameDir, "depth.png").outputStream().use { depBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            depBmp.recycle()
            // Confidence PNG (no overlay)
            val cfBmp = renderClean(depthF, conf3F, maxD, w, h, "confidence")
            File(frameDir, "confidence.png").outputStream().use { cfBmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            cfBmp.recycle()
            // Raw DEPTH16 bytes (packed, no stride padding)
            File(frameDir, "raw_depth16.bin").writeBytes(rawBytes)
            // depth_values.csv
            val sbD = StringBuilder()
            for (row in 0 until h) {
                for (col in 0 until w) { if (col > 0) sbD.append(','); sbD.append(depthF[row * w + col].toInt()) }
                sbD.append('\n')
            }
            File(frameDir, "depth_values.csv").writeText(sbD.toString())
            // confidence_values.csv
            val sbC = StringBuilder()
            for (row in 0 until h) {
                for (col in 0 until w) { if (col > 0) sbC.append(','); sbC.append(conf3F[row * w + col]) }
                sbC.append('\n')
            }
            File(frameDir, "confidence_values.csv").writeText(sbC.toString())
        } catch (e: Exception) {
            android.util.Log.e("EyeSense", "Burst frame $frameNum save failed: ${e.message}")
        }
    }

    private fun saveSessionMetadata(sessionDir: File) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date())
            val json = "{\n" +
                "  \"timestamp\": \"$ts\",\n" +
                "  \"frame_count\": $BURST_TOTAL,\n" +
                "  \"center_distance_mm\": $lastBurstCenterMm,\n" +
                "  \"sensor\": \"Samsung iToF DEPTH16\",\n" +
                "  \"depth_range_mm\": [0, 8191],\n" +
                "  \"confidence_range\": [0, 7],\n" +
                "  \"path\": \"${sessionDir.absolutePath}\"\n" +
                "}"
            File(sessionDir, "metadata.json").writeText(json)
        } catch (e: Exception) {
            android.util.Log.e("EyeSense", "Metadata save failed: ${e.message}")
        }
    }

    // Save pixels inside the right-eye oval to eye_roi.csv.
    // Oval is defined in raw sensor space (before 90° CW rotation):
    //   center  ≈ (row=0.65·h, col=0.35·w)  → upper-left in portrait display
    //   radii   ≈ (rowR=0.12·h, colR=0.08·w)
    // Adjust these fractions if the oval needs repositioning.
    private fun saveRoiCsv(sessionDir: File, depthF: FloatArray, conf3F: IntArray, w: Int, h: Int) {
        val rowC = (0.65 * h).toInt()
        val colC = (0.35 * w).toInt()
        val rowR = (0.06 * h).toInt().coerceAtLeast(1)
        val colR = (0.05 * w).toInt().coerceAtLeast(1)

        // CSV: pixels inside oval
        val sb = StringBuilder("x,y,depth_mm,confidence\n")
        for (row in 0 until h) {
            for (col in 0 until w) {
                val dr = (row - rowC).toDouble() / rowR
                val dc = (col - colC).toDouble() / colR
                if (dr * dr + dc * dc <= 1.0) {
                    val depth = depthF[row * w + col].toInt()
                    val conf  = conf3F[row * w + col]
                    sb.append("$col,$row,$depth,$conf\n")
                }
            }
        }
        File(sessionDir, "eye_roi.csv").writeText(sb.toString())

        // Image: render Mozart, rotate, crop oval bounding box
        try {
            val maxD = depthF.max().coerceAtLeast(1f)
            val bmp = renderClean(depthF, conf3F, maxD, w, h, "mozart")
            // bmp is now rotated: displayW = bmp.width, displayH = bmp.height
            val dw = bmp.width.toFloat();  val dh = bmp.height.toFloat()
            val cx = (0.35 * dw).toInt();  val cy = (0.35 * dh).toInt()
            val rx = (0.06 * dw).toInt().coerceAtLeast(1)
            val ry = (0.05 * dh).toInt().coerceAtLeast(1)
            val x1 = (cx - rx).coerceAtLeast(0)
            val y1 = (cy - ry).coerceAtLeast(0)
            val cropW = ((cx + rx).coerceAtMost(bmp.width)  - x1).coerceAtLeast(1)
            val cropH = ((cy + ry).coerceAtMost(bmp.height) - y1).coerceAtLeast(1)
            val crop = Bitmap.createBitmap(bmp, x1, y1, cropW, cropH)
            bmp.recycle()
            File(sessionDir, "eye_roi.png").outputStream().use {
                crop.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            crop.recycle()
        } catch (e: Exception) {
            android.util.Log.e("EyeSense", "ROI image save failed: ${e.message}")
        }
    }

    private fun pushCaptureProgress(current: Int, total: Int) {
        runOnUiThread {
            eventSink?.success(mapOf("type" to "capture_progress", "current" to current, "total" to total))
        }
    }

    private fun pushAutoStatus(mm: Int, status: String, stability: Double) {
        runOnUiThread {
            eventSink?.success(mapOf("type" to "auto_status", "mm" to mm, "status" to status, "stability" to stability))
        }
    }

    // ── Camera capability diagnostic ──────────────────────────────────────────

    private fun logCameraCapabilities(
        camId: String,
        chars: CameraCharacteristics,
        map: android.hardware.camera2.params.StreamConfigurationMap
    ) {
        val TAG = "EyeSense_CAM_CAPS"
        val sb = StringBuilder()
        sb.appendLine("=== Camera $camId capabilities ===")

        // All declared capabilities
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val capNames = mapOf(
            0 to "BACKWARD_COMPATIBLE", 1 to "MANUAL_SENSOR", 2 to "MANUAL_POST_PROCESSING",
            3 to "RAW", 4 to "PRIVATE_REPROCESSING", 5 to "READ_SENSOR_SETTINGS",
            6 to "BURST_CAPTURE", 7 to "YUV_REPROCESSING", 8 to "DEPTH_OUTPUT",
            9 to "CONSTRAINED_HIGH_SPEED_VIDEO", 10 to "MOTION_TRACKING",
            11 to "LOGICAL_MULTI_CAMERA", 12 to "MONOCHROME", 13 to "SECURE_IMAGE_DATA",
            14 to "SYSTEM_CAMERA", 18 to "ULTRA_HIGH_RESOLUTION_SENSOR",
            19 to "REMOSAIC_REPROCESSING", 21 to "DYNAMIC_RANGE_TEN_BIT", 29 to "STREAM_USE_CASE"
        )
        sb.appendLine("Capabilities: ${caps.joinToString { capNames[it] ?: "UNKNOWN($it)" }}")

        // All output formats and their sizes
        val formatsToCheck = listOf(
            ImageFormat.DEPTH16        to "DEPTH16",
            ImageFormat.DEPTH_JPEG     to "DEPTH_JPEG",
            ImageFormat.DEPTH_POINT_CLOUD to "DEPTH_POINT_CLOUD",
            ImageFormat.RAW_SENSOR     to "RAW_SENSOR",
            ImageFormat.RAW10          to "RAW10",
            ImageFormat.RAW12          to "RAW12",
            ImageFormat.YUV_420_888    to "YUV_420_888",
            ImageFormat.Y8             to "Y8",
            ImageFormat.JPEG           to "JPEG",
            ImageFormat.PRIVATE        to "PRIVATE",
            0x20203859                 to "Y8 (alt)",
            0x59380000                 to "DEPTH_AMPLITUDE (Samsung?)"
        )
        for ((fmt, name) in formatsToCheck) {
            val sizes = try { map.getOutputSizes(fmt) } catch (_: Exception) { null }
            if (!sizes.isNullOrEmpty()) {
                sb.appendLine("  FORMAT $name (0x${fmt.toString(16)}): ${sizes.joinToString { "${it.width}x${it.height}" }}")
            }
        }

        // Hardware level
        val hwLevel = when (chars.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY       -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED      -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL         -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3            -> "LEVEL_3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL     -> "EXTERNAL"
            else -> "UNKNOWN"
        }
        sb.appendLine("Hardware level: $hwLevel")

        // Depth-specific keys
        val depthExclusive = chars.get(CameraCharacteristics.DEPTH_DEPTH_IS_EXCLUSIVE)
        sb.appendLine("DEPTH_IS_EXCLUSIVE: $depthExclusive")

        // Log each line separately so logcat doesn't truncate
        sb.lines().forEach { android.util.Log.i(TAG, it) }

        // Also save to a file so you can read it without logcat
        fileExecutor.execute {
            try {
                val dir = getExternalFilesDir(null) ?: filesDir
                File(dir, "camera_caps_$camId.txt").writeText(sb.toString())
                android.util.Log.i(TAG, "Caps saved to camera_caps_$camId.txt")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Failed to save caps: ${e.message}")
            }
        }
    }

    // ── Camera2 depth camera ──────────────────────────────────────────────────

    private fun openDepthCamera() {
        cameraThread = HandlerThread("DepthCam").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)

        val mgr = getSystemService(CAMERA_SERVICE) as CameraManager
        val camId = findDepthCameraId(mgr)
        if (camId == null) {
            pushStatus("error", "No DEPTH16 camera found on this device"); return
        }

        val chars = mgr.getCameraCharacteristics(camId)
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        logCameraCapabilities(camId, chars, map)
        val sizes = map.getOutputSizes(ImageFormat.DEPTH16)
        if (sizes.isNullOrEmpty()) {
            pushStatus("error", "DEPTH16 has no output sizes"); return
        }

        val sz = sizes.maxByOrNull { it.width * it.height }!!
        val w  = sz.width
        val h  = sz.height

        imageReader = ImageReader.newInstance(w, h, ImageFormat.DEPTH16, 4)
        imageReader!!.setOnImageAvailableListener({ reader ->
            val img = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                processDepthFrame(img.planes[0], w, h)
            } finally {
                img.close()
            }
        }, cameraHandler)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            pushStatus("error", "Camera permission not granted"); return
        }

        mgr.openCamera(camId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                cameraDevice = camera
                createCaptureSession(camera)
            }
            override fun onDisconnected(camera: CameraDevice) {
                camera.close(); cameraDevice = null
            }
            override fun onError(camera: CameraDevice, error: Int) {
                camera.close(); cameraDevice = null
                pushStatus("error", "Depth camera error: $error")
            }
        }, cameraHandler)
    }

    private fun findDepthCameraId(mgr: CameraManager): String? {
        for (id in mgr.cameraIdList) {
            val caps = mgr.getCameraCharacteristics(id)
                .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
            if (CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT in caps) {
                val map = mgr.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                if (map?.getOutputSizes(ImageFormat.DEPTH16)?.isNotEmpty() == true) return id
            }
        }
        for (id in mgr.cameraIdList) {
            val map = mgr.getCameraCharacteristics(id)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            if (map.getOutputSizes(ImageFormat.DEPTH16)?.isNotEmpty() == true) return id
        }
        return null
    }

    private fun createCaptureSession(camera: CameraDevice) {
        camera.createCaptureSession(
            listOf(imageReader!!.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    cameraRunning.set(true)
                    val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(imageReader!!.surface)
                    }
                    session.setRepeatingRequest(req.build(), null, cameraHandler)
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    pushStatus("error", "Capture session config failed")
                }
            },
            cameraHandler
        )
    }

    // ── Frame routing ─────────────────────────────────────────────────────────

    private fun processDepthFrame(plane: android.media.Image.Plane, w: Int, h: Int) {
        when (cameraMode) {
            CameraMode.LOCAL -> computeLocalVisualization(plane, w, h)
            CameraMode.TCP   -> packAndSendTcp(plane, w, h)
        }
    }

    // ── Local visualization (Mozart / Depth / Confidence) ─────────────────────

    private fun computeLocalVisualization(plane: android.media.Image.Plane, w: Int, h: Int) {
        val buf       = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val rowStride = plane.rowStride
        val total     = w * h

        val depthF = FloatArray(total)
        val conf3F = IntArray(total)   // raw 3-bit confidence 0-7
        var maxDepth = 0

        for (row in 0 until h) {
            val rowBase = row * rowStride
            for (col in 0 until w) {
                val off   = rowBase + col * 2
                val lo    = buf.get(off).toInt()     and 0xFF
                val hi    = buf.get(off + 1).toInt() and 0xFF
                val px    = lo or (hi shl 8)
                val depth = px and 0x1FFF
                val conf3 = (px ushr 13) and 0x07
                val idx   = row * w + col
                depthF[idx]  = depth.toFloat()
                conf3F[idx]  = conf3
                if (depth > maxDepth) maxDepth = depth
            }
        }

        if (maxDepth == 0) return

        // Keep latest frame for ROI capture
        roiDepthF = depthF;  roiConf3F = conf3F;  roiW = w;  roiH = h

        // Log center pixel raw depth + confidence to CSV (background, no UI impact)
        val centerIdx   = h / 2 * w + w / 2
        val cDepthMm    = depthF[centerIdx].toInt()
        val cConf       = conf3F[centerIdx]
        val ts          = csvTimeFmt.format(Date())
        fileExecutor.execute {
            if (rawCsvWriter == null) {
                try {
                    val dir = getExternalFilesDir(null) ?: filesDir
                    dir.mkdirs()
                    val f = File(dir, "raw_depth_$sessionTag.csv")
                    rawCsvWriter = FileWriter(f, true).also {
                        it.write("timestamp,depth_mm,confidence_0to7\n")
                        it.flush()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("EyeSense", "CSV init failed: ${e.message}")
                }
            }
            try { rawCsvWriter?.apply { write("$ts,$cDepthMm,$cConf\n"); flush() } }
            catch (_: Exception) {}
        }

        // Dump full 2D confidence map when center depth is in eye sweet-spot range (60–80 mm)
        // Throttled to once per second to avoid flooding storage
        if (cDepthMm in 60..80) {
            val nowMs  = System.currentTimeMillis()
            val lastMs = lastDumpMs.get()
            if (nowMs - lastMs >= 1000L && lastDumpMs.compareAndSet(lastMs, nowMs)) {
                val confCopy = conf3F.copyOf()
                val frameTs  = ts
                val fw = w;  val fh = h
                fileExecutor.execute { saveConf2dFrame(confCopy, fw, fh, frameTs) }
            }
        }

        // ── Distance tracking + auto-capture ──────────────────────────────────
        val distStatus = when {
            cDepthMm in STABLE_MIN_MM..STABLE_MAX_MM -> "ok"
            cDepthMm in 1 until STABLE_MIN_MM        -> "too_close"
            cDepthMm > STABLE_MAX_MM                 -> "too_far"
            else                                     -> ""
        }
        synchronized(distanceHistory) {
            distanceHistory.add(cDepthMm)
            if (distanceHistory.size > DIST_HISTORY_SIZE) distanceHistory.removeAt(0)
        }
        var stabilityPct = 0.0
        if (autoCaptureEnabled.get() && !autoCaptureTriggered.get()) {
            val hist = synchronized(distanceHistory) { distanceHistory.toList() }
            if (hist.size >= DIST_HISTORY_SIZE && distStatus == "ok") {
                val range = (hist.maxOrNull() ?: 0) - (hist.minOrNull() ?: 0)
                if (range <= STABLE_VAR_MM) {
                    if (stabilityStartMs == 0L) stabilityStartMs = System.currentTimeMillis()
                    val elapsed = System.currentTimeMillis() - stabilityStartMs
                    stabilityPct = (elapsed.toDouble() / STABLE_MS).coerceIn(0.0, 1.0)
                    if (elapsed >= STABLE_MS && autoCaptureTriggered.compareAndSet(false, true)) {
                        triggerAutoCapture()
                    }
                } else {
                    stabilityStartMs = 0L
                }
            } else {
                stabilityStartMs = 0L
            }
        }
        if (frameCounter.get() % 3L == 0L) pushAutoStatus(cDepthMm, distStatus, stabilityPct)

        val maxD = maxDepth.toFloat()

        // ── Burst frame capture ───────────────────────────────────────────────
        if (burstCapturing.get()) {
            val burstIdx = burstFrameCount.getAndIncrement()
            if (burstIdx < BURST_TOTAL) {
                lastBurstCenterMm = cDepthMm
                val snapDepth = depthF.copyOf()
                val snapConf  = conf3F.copyOf()
                val rawBytes  = ByteArray(w * h * 2)
                for (i in 0 until w * h) {
                    val px = (snapDepth[i].toInt() and 0x1FFF) or ((snapConf[i] and 0x07) shl 13)
                    rawBytes[i * 2]     = (px and 0xFF).toByte()
                    rawBytes[i * 2 + 1] = ((px ushr 8) and 0xFF).toByte()
                }
                val captureDir = currentSessionDir
                val captureIdx = burstIdx
                captureExecutor.execute {
                    if (captureDir != null)
                        saveBurstFrame(captureDir, captureIdx + 1, snapDepth, snapConf, maxD, w, h, rawBytes)
                }
                val current = burstIdx + 1
                pushCaptureProgress(current, BURST_TOTAL)
                if (current >= BURST_TOTAL) {
                    burstCapturing.set(false)
                    val sessDir = captureDir
                    captureExecutor.execute { if (sessDir != null) saveSessionMetadata(sessDir) }
                    runOnUiThread {
                        eventSink?.success(mapOf(
                            "type"   to "capture_complete",
                            "path"   to (captureDir?.absolutePath ?: ""),
                            "frames" to BURST_TOTAL
                        ))
                    }
                }
            } else {
                burstCapturing.set(false)
            }
        }

        val pixels = IntArray(total)
        when (activeView) {
            "mozart" -> {
                // ── Official Mozart formula (MobiSys 2023) ────────────────────────
                // depthPercentage: conf==0 (unknown) → 1.0f, else (conf-1)/7
                // depthratio:      conf==0 (unknown) → 7,    else (conf-1)
                // cutDepth: depth / frameMax  (no distance clamping)
                // N2 = depthPercentage * cutDepth
                // Mozart = sqrt(N2² × depthratio) = N2 × sqrt(depthratio)
                val mozVals = FloatArray(total)
                var mozMax  = 0f
                for (i in 0 until total) {
                    val conf3       = conf3F[i]
                    val depthratio  = if (conf3 == 0) 7 else (conf3 - 1)
                    val depthPct    = if (conf3 == 0) 1f else (conf3 - 1) * (1f / 7f)
                    val cutDepth    = depthF[i] / maxD
                    val n2          = depthPct * cutDepth
                    val mz          = sqrt(n2 * n2 * depthratio.toFloat())
                    mozVals[i] = mz
                    if (mz > mozMax) mozMax = mz
                }
                val norm = if (mozMax > 0f) mozMax else 1f
                for (i in 0 until total) {
                    val g = ((mozVals[i] / norm) * 255f).toInt().coerceIn(0, 255)
                    pixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                }
            }
            "depth" -> {
                for (i in 0 until total) {
                    pixels[i] = jetColormap(depthF[i] / maxD)
                }
            }
            "confidence" -> {
                for (i in 0 until total) {
                    // conf 0 = unknown → show as mid-gray; 1-7 → scale 0-255
                    val conf3 = conf3F[i]
                    val g = if (conf3 == 0) 128 else ((conf3 - 1) * 255 / 6)
                    pixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
                }
            }
            /*
            "gradient" -> {
                // Sobel gradient magnitude on depth values
                val gradVals = FloatArray(total)
                var gradMax  = 0f
                for (row in 1 until h - 1) {
                    for (col in 1 until w - 1) {
                        val tl = depthF[(row-1)*w + (col-1)]; val tc = depthF[(row-1)*w + col]; val tr = depthF[(row-1)*w + (col+1)]
                        val ml = depthF[ row   *w + (col-1)];                                   val mr = depthF[ row   *w + (col+1)]
                        val bl = depthF[(row+1)*w + (col-1)]; val bc = depthF[(row+1)*w + col]; val br = depthF[(row+1)*w + (col+1)]
                        val dx = -tl + tr - 2f*ml + 2f*mr - bl + br
                        val dy = -tl - 2f*tc - tr + bl + 2f*bc + br
                        val mag = sqrt(dx*dx + dy*dy)
                        gradVals[row*w + col] = mag
                        if (mag > gradMax) gradMax = mag
                    }
                }
                val gradNorm = if (gradMax > 0f) gradMax else 1f
                for (i in 0 until total) {
                    pixels[i] = jetColormap(gradVals[i] / gradNorm)
                }
            }
            "n2asym" -> {
                // Compute N2 per pixel, then left-right mirror asymmetry
                val n2Vals = FloatArray(total)
                for (i in 0 until total) {
                    val c3       = conf3F[i]
                    val depthPct = if (c3 == 0) 1f else (c3 - 1) * (1f / 7f)
                    n2Vals[i]    = depthPct * (depthF[i] / maxD)
                }
                val asymVals = FloatArray(total)
                var asymMax  = 0f
                for (row in 0 until h) {
                    for (col in 0 until w) {
                        val mirror = n2Vals[row*w + (w - 1 - col)]
                        val asym   = n2Vals[row*w + col] - mirror
                        asymVals[row*w + col] = asym
                        val absAsym = abs(asym)
                        if (absAsym > asymMax) asymMax = absAsym
                    }
                }
                val asymNorm = if (asymMax > 0f) asymMax else 1f
                for (i in 0 until total) {
                    pixels[i] = divergingColormap(asymVals[i] / asymNorm)
                }
            }
            */
        }

        // Rotate bitmap to match portrait screen orientation
        val raw = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        val matrix = android.graphics.Matrix().apply { postRotate(sensorOrientation.toFloat()) }
        val bmp = Bitmap.createBitmap(raw, 0, 0, w, h, matrix, false).copy(Bitmap.Config.ARGB_8888, true)
        raw.recycle()

        // Draw center-pixel distance overlay
        val centerDepthMm = depthF[h / 2 * w + w / 2].toInt()
        val distText = if (centerDepthMm > 0) "${centerDepthMm} mm  (${centerDepthMm / 1000.0f} m)" else "-- mm"
        val canvas = android.graphics.Canvas(bmp)
        val paint  = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize    = bmp.width * 0.06f
            typeface    = android.graphics.Typeface.MONOSPACE
        }
        val tw = paint.measureText(distText)
        val tx = (bmp.width - tw) / 2f
        val ty = bmp.height * 0.08f
        // shadow
        paint.color = android.graphics.Color.BLACK
        canvas.drawText(distText, tx + 2f, ty + 2f, paint)
        // text
        paint.color = android.graphics.Color.WHITE
        canvas.drawText(distText, tx, ty, paint)
        // center crosshair
        val cx = bmp.width / 2f;  val cy = bmp.height / 2f;  val cs = bmp.width * 0.02f
        paint.strokeWidth = 2f;  paint.color = android.graphics.Color.WHITE
        canvas.drawLine(cx - cs, cy, cx + cs, cy, paint)
        canvas.drawLine(cx, cy - cs, cx, cy + cs, paint)

        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
        bmp.recycle()
        val jpegBytes = bos.toByteArray()

        val fn = frameCounter.incrementAndGet()
        runOnUiThread {
            eventSink?.success(mapOf(
                "type"   to "frame",
                "data"   to jpegBytes,
                "frames" to fn
            ))
        }
    }

    // ── Colormaps ─────────────────────────────────────────────────────────────

    // Hot: black → red → yellow → white
    private fun hotColormap(v: Float): Int {
        val r = (v * 3f).coerceIn(0f, 1f)
        val g = ((v - 1f / 3f) * 3f).coerceIn(0f, 1f)
        val b = ((v - 2f / 3f) * 3f).coerceIn(0f, 1f)
        return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
    }

    // Jet: blue → cyan → green → yellow → red
    private fun jetColormap(v: Float): Int {
        val r = (1.5f - abs(v * 4f - 3f)).coerceIn(0f, 1f)
        val g = (1.5f - abs(v * 4f - 2f)).coerceIn(0f, 1f)
        val b = (1.5f - abs(v * 4f - 1f)).coerceIn(0f, 1f)
        return (0xFF shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
    }

    // Diverging: blue (negative asymmetry) → black (symmetric) → red (positive asymmetry)
    /*
    private fun divergingColormap(v: Float): Int {
        val t = v.coerceIn(-1f, 1f)
        return if (t < 0) {
            val b = (-t * 255).toInt().coerceIn(0, 255)
            (0xFF shl 24) or b
        } else {
            val r = (t * 255).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16)
        }
    }
    */

    // ── TCP frame packing ─────────────────────────────────────────────────────
    // Wire protocol (big-endian):
    //   [4]  magic      0x4E314E32  "N1N2"
    //   [4]  frame_num  uint32
    //   [4]  width      uint32
    //   [4]  height     uint32
    //   [4]  max_depth  uint32  mm
    //   [W*H*2]  depth  uint16 BE mm
    //   [W*H*1]  conf   uint8  0-255

    private fun packAndSendTcp(plane: android.media.Image.Plane, w: Int, h: Int) {
        val out = tcpStream ?: return
        val buf       = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
        val rowStride = plane.rowStride
        val total     = w * h

        val depthBytes = ByteArray(total * 2)
        val confBytes  = ByteArray(total)
        var maxDepth   = 0
        var validCount = 0

        for (row in 0 until h) {
            val rowBase = row * rowStride
            for (col in 0 until w) {
                val off     = rowBase + col * 2
                val lo      = buf.get(off).toInt()     and 0xFF
                val hi      = buf.get(off + 1).toInt() and 0xFF
                val px      = lo or (hi shl 8)
                val depth   = px and 0x1FFF
                val conf3   = (px ushr 13) and 0x07
                val conf255 = conf3 * 255 / 7
                val idx     = row * w + col
                if (depth > 0 && conf3 > 0) validCount++
                if (depth > maxDepth) maxDepth = depth
                depthBytes[idx * 2]     = (depth ushr 8).toByte()
                depthBytes[idx * 2 + 1] = (depth and 0xFF).toByte()
                confBytes[idx]          = conf255.toByte()
            }
        }

        if (maxDepth == 0 || validCount < total / 10) return

        val fn     = frameCounter.incrementAndGet()
        val packet = ByteBuffer.allocate(20 + total * 3).order(ByteOrder.BIG_ENDIAN)
        packet.putInt(0x4E314E32.toInt())
        packet.putInt(fn.toInt())
        packet.putInt(w)
        packet.putInt(h)
        packet.putInt(maxDepth)
        packet.put(depthBytes)
        packet.put(confBytes)

        try {
            out.write(packet.array())
            out.flush()
        } catch (e: Exception) {
            streaming.set(false)
            cameraMode = CameraMode.LOCAL
            pushStatus("error", "Stream broken: ${e.message}")
            return
        }

        if (fn % 30L == 0L)
            pushStatus("streaming", "Streaming · $fn frames  (${w}×${h})")
    }

    // ── 2D confidence frame dump ──────────────────────────────────────────────
    // Binary format (little-endian):
    //   [4]  width  uint32
    //   [4]  height uint32
    //   [w*h] confidence uint8 (0-7 per pixel)
    // Load in Python:
    //   import numpy as np
    //   d = open('conf2d_xxx.bin','rb').read()
    //   w,h = np.frombuffer(d[:8], dtype='<u4')
    //   conf = np.frombuffer(d[8:], dtype=np.uint8).reshape(h, w)
    //   asymmetry = conf[:, :w//2].mean() - conf[:, w//2:].mean()

    private fun saveConf2dFrame(conf: IntArray, w: Int, h: Int, ts: String) {
        try {
            val dir    = getExternalFilesDir(null) ?: filesDir
            val subDir = File(dir, "conf2d_$sessionTag")
            subDir.mkdirs()
            val safeTs = ts.replace(":", "-").replace(" ", "_")
            val f      = File(subDir, "conf2d_$safeTs.bin")
            f.outputStream().use { out ->
                val hdr = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                hdr.putInt(w); hdr.putInt(h)
                out.write(hdr.array())
                out.write(ByteArray(conf.size) { conf[it].toByte() })
            }
        } catch (e: Exception) {
            android.util.Log.e("EyeSense", "conf2d save failed: ${e.message}")
        }
    }

    // ── Camera cleanup ────────────────────────────────────────────────────────

    private fun closeCameraResources() {
        try { captureSession?.stopRepeating() } catch (_: Exception) {}
        try { captureSession?.close()         } catch (_: Exception) {}
        try { cameraDevice?.close()           } catch (_: Exception) {}
        try { imageReader?.close()            } catch (_: Exception) {}
        cameraThread?.quitSafely()
        captureSession = null; cameraDevice = null
        imageReader    = null; cameraThread = null; cameraHandler = null
        fileExecutor.execute { try { rawCsvWriter?.close() } catch (_: Exception) {}; rawCsvWriter = null }
        burstCapturing.set(false)
    }

    // ── Event helpers ─────────────────────────────────────────────────────────

    private fun pushStatus(state: String, message: String) {
        runOnUiThread {
            eventSink?.success(mapOf(
                "type"    to "status",
                "state"   to state,
                "message" to message,
                "frames"  to frameCounter.get()
            ))
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onPause() {
        super.onPause()
        if (cameraRunning.get() && !streaming.get()) {
            try { captureSession?.stopRepeating() } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        if (cameraRunning.get()) {
            try {
                val req = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)?.apply {
                    imageReader?.surface?.let { addTarget(it) }
                }
                if (req != null) captureSession?.setRepeatingRequest(req.build(), null, cameraHandler)
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        stopTcpConnection()
        cameraRunning.set(false)
        closeCameraResources()
        super.onDestroy()
    }
}

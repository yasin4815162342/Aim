package com.yas.linedebugger

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

class CaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isCapturing = false

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // Capture died. Keep overlays alive. Only release projection resources.
            releaseCaptureResources()
            isCapturing = false
            // Do NOT stopSelf() — user wants tweaks permanent until explicit Stop.
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        AutoAimPrefs.init(applicationContext)
        AutoAimPrefs.loadIntoTunables()

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Screen capture", NotificationManager.IMPORTANCE_LOW)
        )

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }

        bgThread = HandlerThread("frame-processor").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)

        // Always attach overlays here. They stay until explicit stop.
        OverlayController.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_VISIBILITY -> {
                OverlayController.toggleAimVisible()
                refreshNotification()
                return START_STICKY
            }
            ACTION_TOGGLE_TWEAKS -> {
                OverlayController.toggleTweakPanelVisible()
                refreshNotification()
                return START_STICKY
            }
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)

        // RESULT_OK is -1 — treat it (and only it) as success.
        if (resultCode != Activity.RESULT_OK || data == null) {
            // Keep service + overlays alive so user can retry from MainActivity.
            return START_STICKY
        }

        // Already capturing? Ignore duplicate.
        if (isCapturing) return START_STICKY

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val projection = mgr.getMediaProjection(resultCode, data) ?: return START_STICKY

        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        // Use real metrics so VirtualDisplay origin matches the full-screen
        // overlay coordinate space (FLAG_LAYOUT_IN_SCREEN + cutout ALWAYS).
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val realMetrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(realMetrics)
        val width = realMetrics.widthPixels
        val height = realMetrics.heightPixels
        val density = realMetrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "line-debugger-capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, bgHandler
        )

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                // Controller mode: while Manual is active there's nothing
                // for the auto-detected guideline to drive, so skip the
                // (relatively expensive) crop + detect pass entirely.
                if (Tunables.manualModeEnabled) return@setOnImageAvailableListener
                val cx = OverlayController.circleCenterX
                val cy = OverlayController.circleCenterY
                val diam = Tunables.circleDiameter
                val pixels = extractCrop(image, cx, cy, diam)
                val result = LineDetector.detect(pixels, diam)
                mainHandler.post { OverlayController.updateResult(result) }
            } finally {
                image.close()
            }
        }, bgHandler)

        isCapturing = true
        return START_STICKY
    }

    private fun extractCrop(image: Image, cx: Int, cy: Int, diam: Int): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val imgW = image.width
        val imgH = image.height
        val half = diam / 2

        val maxStartX = (imgW - diam).coerceAtLeast(0)
        val maxStartY = (imgH - diam).coerceAtLeast(0)
        val startX = (cx - half).coerceIn(0, maxStartX)
        val startY = (cy - half).coerceIn(0, maxStartY)

        val out = IntArray(diam * diam)
        val rowBytes = ByteArray(rowStride)
        for (row in 0 until diam) {
            buffer.position((startY + row) * rowStride)
            buffer.get(rowBytes, 0, rowStride)
            for (col in 0 until diam) {
                val offset = (startX + col) * pixelStride
                val r = rowBytes[offset].toInt() and 0xFF
                val g = rowBytes[offset + 1].toInt() and 0xFF
                val b = rowBytes[offset + 2].toInt() and 0xFF
                out[row * diam + col] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        buffer.rewind()
        return out
    }

    private fun releaseCaptureResources() {
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null
    }

    private fun actionPendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, CaptureService::class.java).setAction(action)
        return PendingIntent.getService(
            this, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // Notification with the same Show/Hide + Stop pattern as the Manual
    // app, plus a Tweaks action to show/hide the floating tweak panel.
    private fun buildNotification(): Notification {
        val visibilityLabel = if (OverlayController.isAimVisible()) "Hide" else "Show"
        val tweaksLabel = if (OverlayController.isTweakPanelVisible()) "Tweaks: Hide" else "Tweaks: Show"

        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Stop", actionPendingIntent(ACTION_STOP, 1)
        ).build()

        val visibilityAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_view),
            visibilityLabel, actionPendingIntent(ACTION_TOGGLE_VISIBILITY, 2)
        ).build()

        val tweaksAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_edit),
            tweaksLabel, actionPendingIntent(ACTION_TOGGLE_TWEAKS, 3)
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setContentTitle("LineDebugger running")
            .setContentText("Capturing screen for guideline detection")
            .setOngoing(true)
            .addAction(stopAction)
            .addAction(visibilityAction)
            .addAction(tweaksAction)
            .build()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
    }

    override fun onDestroy() {
        releaseCaptureResources()
        OverlayController.detach()
        bgThread?.quitSafely()
        bgThread = null
        bgHandler = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val NOTIF_ID = 1
        private const val CHANNEL_ID = "line_debugger_capture"

        const val ACTION_STOP = "com.yas.linedebugger.STOP"
        const val ACTION_TOGGLE_VISIBILITY = "com.yas.linedebugger.TOGGLE_VISIBILITY"
        const val ACTION_TOGGLE_TWEAKS = "com.yas.linedebugger.TOGGLE_TWEAKS"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CaptureService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent) // triggers onStartCommand → stopSelf
            // fallback
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}

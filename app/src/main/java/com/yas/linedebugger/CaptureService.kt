
package com.yas.linedebugger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
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

class CaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var bgHandler: Handler? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "line_debugger_capture"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Screen capture", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("LineDebugger running")
            .setContentText("Capturing screen for guideline detection")
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notification)
        }

        bgThread = HandlerThread("frame-processor").also { it.start() }
        bgHandler = Handler(bgThread!!.looper)

        OverlayController.attach(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (resultCode == -1 || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val projection = mgr.getMediaProjection(resultCode, data) ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "line-debugger-capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, bgHandler
        )

        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage()
            if (image != null) {
                try {
                    val cx = OverlayController.circleCenterX
                    val cy = OverlayController.circleCenterY
                    val diam = Tunables.circleDiameter
                    val pixels = extractCrop(image, cx, cy, diam)
                    val result = LineDetector.detect(pixels, diam)
                    mainHandler.post { OverlayController.updateResult(result) }
                } finally {
                    image.close()
                }
            }
        }, bgHandler)

        return START_NOT_STICKY
    }

    /** Pulls just the diam×diam crop under the controller directly out of the frame's
     *  pixel buffer (accounting for row/pixel stride padding), instead of allocating
     *  a full-screen Bitmap every frame. */
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

    override fun onDestroy() {
        super.onDestroy()
        OverlayController.detach()
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        bgThread?.quitSafely()
    }

    companion object {
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptureService::class.java))
        }
    }
}

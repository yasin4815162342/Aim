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
    // Manual Only mode (feature request): the service is running purely to
    // host the overlay (manual CUE/TARGET/kiss controller + calibration),
    // with no MediaProjection/VirtualDisplay/ImageReader ever created. Which
    // mode we're in isn't known until the first onStartCommand (onCreate
    // has no intent extras yet), so attach() moved out of onCreate and into
    // onStartCommand, gated by `attached` so a second onStartCommand call
    // (re-delivery, notification actions, etc.) never double-attaches.
    private var isManualOnly = false
    private var attached = false
    // Cap detection to ~30 fps and skip frames while a detect is still running
    // so the bg thread never builds a multi-frame backlog (root of lag/jagged motion).
    private var lastProcessUptimeMs = 0L
    private var processing = false
    private val minFrameIntervalMs = 33L

    // Full-screen overlay coordinates → capture-buffer scale.
    // Capture runs at Tunables.captureScale of native resolution — user-
    // tunable trade-off between GPU/memory bandwidth and detection
    // precision (see Tunables.captureScale / AutoAimPrefs). Crop centers
    // from OverlayController are in full-screen pixels and are scaled down
    // when reading the buffer.
    private var captureScale = 1f
    private var captureWidth = 0
    private var captureHeight = 0
    private var screenWidth = 0
    private var screenHeight = 0

    // Reused across frames to avoid per-frame allocations in extractCrop.
    private var rowBytes: ByteArray = ByteArray(0)
    private var cropPixels: IntArray = IntArray(0)

    // The crop's ACTUAL top-left origin on the capture buffer, in capture-
    // space pixels, as extractCrop really computed it (rounded + clamped).
    // Set by extractCrop() each frame; read right after to build an
    // absolute full-screen anchor position — see the comment at the call
    // site below for why this replaces the old reconstruction.
    private var lastCropStartXCap = 0
    private var lastCropStartYCap = 0

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

        // Overlay attach happens in onStartCommand once the mode (capture
        // vs Manual Only) is known — see the `attached` field above.
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

        // Manual Only mode: attach the overlay (manual controller + kiss
        // shot + calibration, no Ray Circle/Ray Monitor) and stop right
        // there — never touches MediaProjectionManager at all, so there's
        // no capture permission prompt and no capture indicator.
        val manualOnly = intent?.getBooleanExtra(EXTRA_MANUAL_ONLY, false) ?: false
        if (manualOnly) {
            if (!attached) {
                attached = true
                isManualOnly = true
                refreshNotification()
                OverlayController.attach(this, captureless = true)
            }
            // Already running (either mode) — ignore; Stop first to switch.
            return START_STICKY
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
        // Already attached in Manual Only mode? Ignore — Stop first to
        // switch to Screen Capture mode instead of attaching a second time.
        if (attached && isManualOnly) return START_STICKY

        if (!attached) {
            attached = true
            isManualOnly = false
            refreshNotification()
            OverlayController.attach(this, captureless = false)
        }

        val mgr = getSystemService(MediaProjectionManager::class.java)
        val projection = mgr.getMediaProjection(resultCode, data) ?: return START_STICKY

        mediaProjection = projection
        projection.registerCallback(projectionCallback, mainHandler)

        // Use real metrics so VirtualDisplay origin matches the full-screen
        // overlay coordinate space (FLAG_LAYOUT_IN_SCREEN + cutout ALWAYS).
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val realMetrics = DisplayMetrics()
        wm.defaultDisplay.getRealMetrics(realMetrics)
        screenWidth = realMetrics.widthPixels
        screenHeight = realMetrics.heightPixels
        val density = realMetrics.densityDpi

        // Capture resolution vs accuracy — user-tunable now (see Tunables /
        // AutoAimPrefs). Lower = less GPU/bandwidth per mirrored frame but
        // coarser detection precision (each buffer pixel is 1/captureScale
        // screen pixels wide); 1.0 = native res = no resolution-driven
        // position error, at the highest render cost.
        captureScale = Tunables.captureScale.coerceIn(
            AutoAimPrefs.CAPTURE_SCALE_MIN, AutoAimPrefs.CAPTURE_SCALE_MAX
        )
        captureWidth = (screenWidth * captureScale).toInt().coerceAtLeast(160)
        captureHeight = (screenHeight * captureScale).toInt().coerceAtLeast(160)
        // Density for the virtual display: keep proportional so the surface
        // isn't forced through an extra resampler path on some OEMs.
        val captureDensity = (density * captureScale).toInt().coerceAtLeast(120)

        val reader = ImageReader.newInstance(captureWidth, captureHeight, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = projection.createVirtualDisplay(
            "line-debugger-capture",
            captureWidth, captureHeight, captureDensity,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, bgHandler
        )

        reader.setOnImageAvailableListener({ r ->
            // Always drain to latest frame so the queue never piles up.
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            val now = android.os.SystemClock.uptimeMillis()
            if (processing || now - lastProcessUptimeMs < minFrameIntervalMs) {
                image.close()
                return@setOnImageAvailableListener
            }
            processing = true
            lastProcessUptimeMs = now
            try {
                val cx = OverlayController.circleCenterX
                val cy = OverlayController.circleCenterY
                // Circle diameter is in full-screen pixels; crop size in the
                // downscaled buffer is diameter * captureScale.
                val diamScreen = Tunables.circleDiameter.coerceIn(1, minOf(screenWidth, screenHeight).coerceAtLeast(1))
                val diamCap = Math.round(diamScreen * captureScale).coerceIn(8, minOf(image.width, image.height).coerceAtLeast(8))
                val pixels = extractCrop(image, cx, cy, diamCap)
                if (pixels != null && pixels.size >= diamCap * diamCap) {
                    // Detect on the (smaller) capture-space crop. Angle is
                    // scale-invariant; offsets/width are scaled back to
                    // full-screen pixels for the overlay.
                    val result = LineDetector.detect(pixels, diamCap)
                    // result.offsetX/Y come back as a pixel-center position
                    // LOCAL to the crop (capture-space, 0..diamCap). The
                    // crop's origin on the capture buffer is
                    // lastCropStartXCap/Y — the exact rounded-and-clamped
                    // value extractCrop() actually used to slice the
                    // buffer a few lines up. Add them here and divide once
                    // by captureScale to get one, correct, ABSOLUTE
                    // full-screen anchor coordinate.
                    //
                    // This used to be done in two independent halves: this
                    // scaling here, plus OverlayController separately
                    // reconstructing the crop's screen-space origin as
                    // `circleCenter - circleDiameter/2f`. That's a
                    // DIFFERENT rounding path than the one above (round()
                    // then an integer halving of diamCap vs a plain float
                    // half of the on-screen diameter), so the two never
                    // quite agreed — by at most a capture pixel, but that's
                    // up to 1/captureScale *screen* pixels, and it drifts
                    // by a different amount depending on where the circle
                    // sits on screen. That's the "varies from spot to spot"
                    // 1-3px offset. Building the absolute position from the
                    // one true origin, in one place, removes the second
                    // reconstruction instead of trying to tune it to match.
                    val scaled = if (result.hasLine) {
                        val absXCap = lastCropStartXCap + result.offsetX
                        val absYCap = lastCropStartYCap + result.offsetY
                        result.copy(
                            widthPx = result.widthPx / captureScale,
                            offsetX = absXCap / captureScale,
                            offsetY = absYCap / captureScale
                        )
                    } else {
                        result
                    }
                    mainHandler.post { OverlayController.updateResult(scaled) }
                }
            } catch (t: Throwable) {
                // Never let a detection bug kill the whole process / service.
                android.util.Log.e("LineDebugger", "frame process failed", t)
            } finally {
                try { image.close() } catch (_: Throwable) {}
                processing = false
            }
        }, bgHandler)

        isCapturing = true
        return START_STICKY
    }

    /**
     * Extract a square crop centered on (cx,cy) in *full-screen* coordinates.
     * Buffer is at captureScale; diamCap is the side length in buffer pixels.
     * Returns null on failure; reuses [cropPixels] / [rowBytes] buffers.
     */
    private fun extractCrop(
        image: Image,
        cxScreen: Int,
        cyScreen: Int,
        diamCap: Int
    ): IntArray? {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride.coerceAtLeast(1)
        val imgW = image.width
        val imgH = image.height

        val halfCap = diamCap / 2
        // Map full-screen center into capture-buffer coordinates. Round to
        // nearest buffer pixel, not truncate — truncation silently drops up
        // to 1 buffer px (= 1/captureScale screen px) depending on where the
        // circle happens to sit on screen, which is why the offset used to
        // vary from spot to spot rather than being a fixed amount.
        val cxCap = Math.round(cxScreen * captureScale)
        val cyCap = Math.round(cyScreen * captureScale)

        val maxStartX = (imgW - diamCap).coerceAtLeast(0)
        val maxStartY = (imgH - diamCap).coerceAtLeast(0)
        val startX = (cxCap - halfCap).coerceIn(0, maxStartX)
        val startY = (cyCap - halfCap).coerceIn(0, maxStartY)
        lastCropStartXCap = startX
        lastCropStartYCap = startY

        val need = diamCap * diamCap
        if (cropPixels.size < need) cropPixels = IntArray(need)
        if (rowBytes.size < rowStride) rowBytes = ByteArray(rowStride)

        val out = cropPixels
        val row = rowBytes
        val bytesPerPixel = pixelStride
        val endColByte = (startX + diamCap) * bytesPerPixel

        for (r in 0 until diamCap) {
            val y = startY + r
            if (y >= imgH) break
            val pos = y * rowStride
            if (pos + rowStride > buffer.capacity()) break
            buffer.position(pos)
            // Only pull the bytes we need for this crop row when the crop
            // is much narrower than the full frame (typical).
            val readLen = if (endColByte in 1 until rowStride) endColByte else rowStride
            if (pos + readLen > buffer.capacity()) break
            buffer.get(row, 0, readLen)
            val outRow = r * diamCap
            for (c in 0 until diamCap) {
                val offset = (startX + c) * bytesPerPixel
                if (offset + 2 >= readLen) break
                val red = row[offset].toInt() and 0xFF
                val green = row[offset + 1].toInt() and 0xFF
                val blue = row[offset + 2].toInt() and 0xFF
                out[outRow + c] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
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
        val contentText = if (isManualOnly) {
            "Manual overlay running — no screen capture"
        } else {
            "Capturing screen for guideline detection"
        }

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
            .setContentText(contentText)
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
        private const val EXTRA_MANUAL_ONLY = "manual_only"
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

        // Manual Only mode: no screen-capture permission is requested at
        // all — the service just hosts the overlay (manual controller,
        // kiss shot, calibration) with no MediaProjection ever touched.
        fun startManualOnly(context: Context) {
            val intent = Intent(context, CaptureService::class.java)
                .putExtra(EXTRA_MANUAL_ONLY, true)
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

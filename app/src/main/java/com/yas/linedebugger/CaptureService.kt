private fun WindowManager.LayoutParams.applyFullScreenFlags() {
    flags = flags or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        layoutInDisplayCutoutMode =
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }
}

fun attach(service: Service) {
    val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    windowManager = wm

    // real metrics – same source the capture will use
    val display = wm.defaultDisplay
    val realMetrics = android.util.DisplayMetrics()
    display.getRealMetrics(realMetrics)
    circleCenterX = realMetrics.widthPixels / 2
    circleCenterY = realMetrics.heightPixels / 2

    val dView = DrawOverlayView(service)
    drawView = dView
    val drawParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        OVERLAY_TYPE,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply { applyFullScreenFlags() }
    wm.addView(dView, drawParams)

    val half = Tunables.circleDiameter / 2
    val hParams = WindowManager.LayoutParams(
        Tunables.circleDiameter,
        Tunables.circleDiameter,
        OVERLAY_TYPE,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = circleCenterX - half
        y = circleCenterY - half
        applyFullScreenFlags()
    }
    handleParams = hParams
    // ... rest of attach unchanged
}

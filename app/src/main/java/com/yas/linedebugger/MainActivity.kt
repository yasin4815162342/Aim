package com.yas.linedebugger

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {

    private lateinit var statusText: TextView

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                CaptureService.start(this, result.resultCode, result.data!!)
                statusText.text = "Running — switch to the pool app. Circle + tweak panel float on top."
            } else {
                statusText.text = "Capture permission denied. Tap Start to retry."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AutoAimPrefs.init(applicationContext)
        AutoAimPrefs.loadIntoTunables()

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }
        scroll.addView(root)

        statusText = TextView(this).apply {
            text = "1) Overlay permission  2) Start\nThen drag the circle onto a guideline in the pool app."
            textSize = 16f
        }
        root.addView(statusText)

        root.addView(Button(this).apply {
            text = "1. Grant overlay permission"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        })

        root.addView(Button(this).apply {
            text = "2. Start"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    statusText.text = "Overlay permission not granted yet."
                    return@setOnClickListener
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                val mgr = getSystemService(MediaProjectionManager::class.java)
                if (mgr == null) {
                    statusText.text = "MediaProjection unavailable on this device."
                    return@setOnClickListener
                }
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            }
        })

        root.addView(Button(this).apply {
            text = "Stop"
            setOnClickListener {
                CaptureService.stop(this@MainActivity)
                statusText.text = "Stopped."
            }
        })

        root.addView(TextView(this).apply {
            text = "\nTweaks below are shared with the floating tweak panel — change " +
                "them here or over there, either way. Show/Hide for the aim lines and " +
                "for the tweak panel itself live in the notification while running. " +
                "Table calibration is done from the floating panel (you need to see the " +
                "live overlay over the real table to align the corners)."
            textSize = 13f
            setPadding(0, 16, 0, 16)
        })

        // No calibrate button here on purpose — calibration only makes
        // sense while the live overlay is already running over the actual
        // table, so it's exposed from the floating panel instead (see
        // OverlayController). Everything else is fully shared.
        val settings = SettingsPanelBuilder.build(
            this,
            onChanged = { OverlayController.requestRedraw() },
            onCalibrate = null
        )
        root.addView(settings)

        root.addView(TextView(this).apply {
            text = "\nNew below: color-detection modes, the shared rail ghost ball " +
                "(bank-shot ball size), the Automatic/Manual controller switch, and " +
                "the ported manual CUE/TARGET controller's own settings. These are " +
                "in-app only — the floating panel was already full, so they don't " +
                "appear there."
            textSize = 13f
            setPadding(0, 16, 0, 16)
        })

        val extras = SettingsPanelBuilder.buildExtras(
            this,
            onChanged = { OverlayController.requestRedraw() }
        )
        root.addView(extras)

        setContentView(scroll)
    }
}

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
            text = "2. Start (Screen Capture + Auto Detection)"
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

        // Feature request: Manual Only mode. No screen-capture permission
        // is ever requested — the service just hosts the manual
        // CUE/TARGET/kiss controller, calibration, and tweak panel, with
        // no Ray Circle/Ray Monitor (nothing to detect against). Only one
        // mode can run at a time — Stop before switching.
        root.addView(Button(this).apply {
            text = "2b. Start Manual Only (no screen recording)"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    statusText.text = "Overlay permission not granted yet."
                    return@setOnClickListener
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                CaptureService.startManualOnly(this@MainActivity)
                statusText.text = "Manual Only running — no screen capture. Switch to the pool " +
                    "app; use the Manual Controller section below (enable it if it isn't " +
                    "already) for CUE/TARGET/kiss shots. Table calibration is available in " +
                    "the floating panel same as before."
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
                "live overlay over the real table to align the corners). The Manual " +
                "Controller section further down is only here — it doesn't appear in " +
                "the floating panel, which is already full."
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

        // Feature request #1: manual CUE/TARGET controller. Exclusively
        // here, never in the floating panel (already at capacity) — see
        // ManualControlPanelBuilder.
        val manualControls = ManualControlPanelBuilder.build(
            this,
            onChanged = { OverlayController.requestRedraw() }
        )
        root.addView(manualControls)

        setContentView(scroll)
    }
}

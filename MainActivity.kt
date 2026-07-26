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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

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

        setContentView(root)
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.wear

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.solstone.core.watch.courier.WatchCourierStrings
import app.solstone.core.watch.courier.WatchSessionFacts
import app.solstone.core.watch.courier.WatchSessionReducer
import app.solstone.core.watch.courier.WatchSessionState
import app.solstone.core.watch.courier.WindowPumpStopReason
import app.solstone.observer.wear.service.WearCaptureForegroundService

class WearMainActivity : Activity() {
    companion object {
        private const val REQUEST_RECORD_AUDIO = 101
    }

    private lateinit var statusText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            isFillViewport = true
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 24, 24, 24)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val titleView = TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        layout.addView(titleView)

        statusText = TextView(this).apply {
            text = "owner stop"
            textSize = 14f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        layout.addView(statusText)

        startButton = Button(this).apply {
            text = "start"
            setOnClickListener {
                checkPermissionAndStart()
            }
        }
        layout.addView(startButton)

        stopButton = Button(this).apply {
            text = "stop"
            setOnClickListener {
                stopCaptureService()
            }
        }
        layout.addView(stopButton)

        val noticeView = TextView(this).apply {
            text = WatchCourierStrings.RELAY_NOTICE_SHORT
            textSize = 11f
            setTextColor(Color.GRAY)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 20, 0, 24)
        }
        layout.addView(noticeView)

        scrollView.addView(layout)
        setContentView(scrollView)
    }

    override fun onResume() {
        super.onResume()
        updateUiState()
    }

    private fun checkPermissionAndStart() {
        val hasMic = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

        if (hasMic) {
            startCaptureService()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCaptureService()
            } else {
                updateUiState()
            }
        }
    }

    private fun startCaptureService() {
        val intent = Intent(this, WearCaptureForegroundService::class.java).apply {
            action = WearCaptureForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateUiState()
    }

    private fun stopCaptureService() {
        val intent = Intent(this, WearCaptureForegroundService::class.java).apply {
            action = WearCaptureForegroundService.ACTION_STOP
        }
        startService(intent)
        updateUiState()
    }

    private fun updateUiState() {
        val snapshot = WearCaptureForegroundService.recorderSnapshot
        val now = System.currentTimeMillis()
        val reduction = WatchSessionReducer.reduce(snapshot, now)

        val stateLabel = when (reduction.state) {
            WatchSessionState.STARTING -> "starting"
            WatchSessionState.ACTIVE -> "taking it in"
            WatchSessionState.INTERRUPTED -> "interrupted"
            WatchSessionState.ATTENTION -> "needs attention"
            WatchSessionState.ENDED -> {
                val reason = reduction.endReason ?: snapshot.terminalReason ?: WindowPumpStopReason.OWNER_STOP
                reason.name.lowercase().replace('_', ' ')
            }
        }

        statusText.text = stateLabel
        val isEngaged = reduction.state == WatchSessionState.ACTIVE ||
            reduction.state == WatchSessionState.STARTING ||
            reduction.state == WatchSessionState.INTERRUPTED ||
            reduction.state == WatchSessionState.ATTENTION
        startButton.isEnabled = !isEngaged
        stopButton.isEnabled = isEngaged
    }
}

package com.virtualgyro.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnResume: Button
    private lateinit var tvStatus: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            uiHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnPause = findViewById(R.id.btnPause)
        btnResume = findViewById(R.id.btnResume)
        tvStatus = findViewById(R.id.tvStatus)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1
            )
        }

        btnStart.setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, GyroUdpService::class.java))
            refreshUi()
        }

        btnPause.setOnClickListener {
            GyroUdpService.isPaused = true
            refreshUi()
        }

        btnResume.setOnClickListener {
            GyroUdpService.isPaused = false
            refreshUi()
        }

        // Tu khoi dong luon lan dau mo app, giong hanh vi cu
        if (!GyroUdpService.isServiceRunning) {
            ContextCompat.startForegroundService(this, Intent(this, GyroUdpService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(refreshRunnable)
    }

    private fun refreshUi() {
        val running = GyroUdpService.isServiceRunning
        val paused = GyroUdpService.isPaused

        tvStatus.text = when {
            !running -> "Chưa chạy"
            paused -> "Đã tạm dừng"
            else -> "Đang chạy"
        }

        btnStart.isEnabled = !running
        btnPause.isEnabled = running && !paused
        btnResume.isEnabled = running && paused
    }
}

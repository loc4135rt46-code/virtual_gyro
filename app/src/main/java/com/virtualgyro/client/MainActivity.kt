package com.virtualgyro.client

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var btnStart: Button
    private lateinit var btnPause: Button
    private lateinit var btnResume: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvPackets: TextView
    private lateinit var tvX: TextView
    private lateinit var tvY: TextView
    private lateinit var tvZ: TextView
    private lateinit var dotStatus: View

    private val uiHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi()
            uiHandler.postDelayed(this, UI_REFRESH_MS)
        }
    }

    companion object {
        private const val UI_REFRESH_MS = 100L // ~10 lan/giay du muot cho so X/Y/Z, nhe may
        private const val RAD_TO_DEG = 57.29578f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnStart = findViewById(R.id.btnStart)
        btnPause = findViewById(R.id.btnPause)
        btnResume = findViewById(R.id.btnResume)
        tvStatus = findViewById(R.id.tvStatus)
        tvPackets = findViewById(R.id.tvPackets)
        tvX = findViewById(R.id.tvX)
        tvY = findViewById(R.id.tvY)
        tvZ = findViewById(R.id.tvZ)
        dotStatus = findViewById(R.id.dotStatus)

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
        val connected = GyroUdpService.isConnected

        val (statusText, dotColorRes) = when {
            !running -> "Chưa chạy" to R.color.dot_off
            paused -> "Đã tạm dừng" to R.color.dot_waiting
            connected -> "Đang nhận dữ liệu" to R.color.dot_connected
            else -> "Đang chờ ESP8266..." to R.color.dot_waiting
        }
        tvStatus.text = statusText
        dotStatus.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, dotColorRes))

        tvPackets.text = "${GyroUdpService.packetCount} gói"
        tvX.text = formatDeg(GyroUdpService.lastX)
        tvY.text = formatDeg(GyroUdpService.lastY)
        tvZ.text = formatDeg(GyroUdpService.lastZ)

        btnStart.isEnabled = !running
        btnPause.isEnabled = running && !paused
        btnResume.isEnabled = running && paused
    }

    // Goi tu ESP la rad/s; hien thi do/giay cho de hinh dung. Locale.US de luon
    // dung dau cham thap phan (may dat tieng Viet se ra dau phay lam lech cot).
    private fun formatDeg(radPerSec: Float): String =
        String.format(Locale.US, "%+.1f", radPerSec * RAD_TO_DEG)
}

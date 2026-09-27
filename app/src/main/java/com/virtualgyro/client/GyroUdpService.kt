package com.virtualgyro.client

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

// Thay cho GyroBleClient.kt (BLE central) trên ESP32-C3.
// ESP8266 giờ gửi gyro qua UDP broadcast cổng 47819 (xem EspGyro8266.ino).
// Service này: nhận UDP -> forward nguyên 12 byte qua TCP loopback
// 127.0.0.1:8842 tới gyro_relay (không đổi gì ở phía relay/HAL).
class GyroUdpService : Service() {

    companion object {
        private const val UDP_PORT = 47819
        private const val RELAY_HOST = "127.0.0.1"
        private const val RELAY_PORT = 8842
        private const val CHANNEL_ID = "gyro_service"
        private const val NOTIF_ID = 1
    }

    @Volatile private var running = false
    private var udpSocket: DatagramSocket? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("Đang chờ dữ liệu gyro..."))
        running = true
        thread(start = true) { receiveLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        try { udpSocket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun receiveLoop() {
        var relaySocket: Socket? = null
        val buf = ByteArray(64)
        var packetCount = 0

        while (running) {
            try {
                if (udpSocket == null) {
                    udpSocket = DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(UDP_PORT))
                    }
                }
                if (relaySocket == null || relaySocket.isClosed) {
                    relaySocket = Socket(RELAY_HOST, RELAY_PORT)
                }

                val packet = DatagramPacket(buf, buf.size)
                udpSocket!!.receive(packet)

                if (packet.length == 12) {
                    relaySocket.getOutputStream().write(buf, 0, 12)
                    packetCount++
                    if (packetCount % 50 == 0) {
                        updateNotification("Đã nhận $packetCount gói từ ${packet.address.hostAddress}")
                    }
                }
            } catch (e: Exception) {
                // relay chưa chạy (chưa root/module chưa load) hoặc mất kết nối -> đóng, thử lại
                try { relaySocket?.close() } catch (_: Exception) {}
                relaySocket = null
                Thread.sleep(500)
            }
        }
        try { relaySocket?.close() } catch (_: Exception) {}
    }

    private fun buildNotification(text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Virtual Gyro", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Virtual Gyro")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }
}

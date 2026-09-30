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
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

// Nhan gyro qua UDP tu ESP8266 (cong 47819), forward nguyen 12 byte qua TCP
// loopback 127.0.0.1:8842 toi gyro_relay.
//
// Xu ly mat ket noi: soTimeout ngan (UDP_TIMEOUT_MS) tren socket UDP. Ngay
// khi 1 lan receive() timeout (khong doi/dem nguoc gi them), coi nhu ESP da
// mat nguon - forward thang goi 12 byte toan 0 xuong relay de gyro tro ve
// (0,0,0) ngay lap tuc, tranh nhan vat bi ket hanh dong vi giu gia tri cu.
// Chi lam viec nay 1 lan luc CHUYEN trang thai (dang connected -> mat), khong
// lap lai moi 300ms trong luc van dang mat, vi gia tri da la 0 roi thi khong
// can gui lai. Co goi that ve lai thi tro lai binh thuong ngay.
class GyroUdpService : Service() {

    companion object {
        private const val UDP_PORT = 47819
        private const val UDP_TIMEOUT_MS = 300 // 200-500ms deu duoc, chinh o day
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
        val zeroBuf = ByteArray(12) // toan 0 san (3x float32 0.0f) - dung khi mat ket noi
        var packetCount = 0
        var connected = false

        while (running) {
            try {
                if (udpSocket == null) {
                    udpSocket = DatagramSocket(null).apply {
                        reuseAddress = true
                        soTimeout = UDP_TIMEOUT_MS
                        bind(InetSocketAddress(UDP_PORT))
                    }
                }
                if (relaySocket == null || relaySocket!!.isClosed) {
                    relaySocket = Socket(RELAY_HOST, RELAY_PORT)
                }

                val packet = DatagramPacket(buf, buf.size)
                try {
                    udpSocket!!.receive(packet)
                } catch (e: SocketTimeoutException) {
                    // Khong nhan duoc goi nao trong UDP_TIMEOUT_MS -> mat ket noi.
                    // Zero NGAY, khong doi them lan timeout nao nua.
                    if (connected) {
                        connected = false
                        try {
                            relaySocket?.getOutputStream()?.write(zeroBuf)
                        } catch (_: Exception) {
                            try { relaySocket?.close() } catch (_: Exception) {}
                            relaySocket = null
                        }
                        updateNotification("Mất kết nối ESP8266 - gyro đã về 0")
                    }
                    continue
                }

                if (packet.length == 12) {
                    if (!connected) {
                        connected = true
                        updateNotification("Đã kết nối lại - nhận từ ${packet.address.hostAddress}")
                    }
                    relaySocket!!.getOutputStream().write(buf, 0, 12)
                    packetCount++
                    if (packetCount % 50 == 0) {
                        updateNotification("Đã nhận $packetCount gói từ ${packet.address.hostAddress}")
                    }
                }
            } catch (e: Exception) {
                // relay chưa chạy (chưa root/module chưa load) hoặc mất kết nối TCP -> đóng, thử lại
                try { relaySocket?.close() } catch (_: Exception) {}
                relaySocket = null
                connected = false
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

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
// loopback 127.0.0.1:8842 toi gyro_relay. Khong he scale/sua gia tri goi -
// do nhay hoan toan phu thuoc firmware ESP8266, khong doi gi o day.
//
// Xu ly mat ket noi: soTimeout CHI la chu ky poll ngan (de vong lap khong
// block vinh vien), KHONG phai nguong mat ket noi. Nguong that
// (DISCONNECT_THRESHOLD_MS) tinh theo thoi gian thuc te ke tu goi cuoi cung
// nhan duoc (lastPacketAtMs) - chi khi im lang LIEN TUC qua het nguong nay
// moi gui 1 goi 12 byte toan 0 xuong relay (gyro ve 0,0,0 ngay).
//
// isPaused: co de MainActivity dieu khien nut "Tam dung"/"Tiep tuc". Dang
// tam dung van tiep tuc nhan+bo qua goi UDP (tranh don backlog), nhung
// KHONG forward xuong relay - va zero 1 lan luc vua chuyen sang tam dung,
// y het co che xu ly mat ket noi.
class GyroUdpService : Service() {

    companion object {
        private const val UDP_PORT = 47819
        private const val POLL_TIMEOUT_MS = 100          // chu ky kiem tra, KHONG phai nguong mat ket noi
        private const val DISCONNECT_THRESHOLD_MS = 600  // im lang LIEN TUC qua moc nay moi coi la mat
        private const val RELAY_HOST = "127.0.0.1"
        private const val RELAY_PORT = 8842
        private const val CHANNEL_ID = "gyro_service"
        private const val NOTIF_ID = 1

        @Volatile var isServiceRunning = false
        @Volatile var isPaused = false
    }

    @Volatile private var running = false
    private var udpSocket: DatagramSocket? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification("Đang chờ dữ liệu gyro..."))
        running = true
        isServiceRunning = true
        isPaused = false
        thread(start = true) { receiveLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        isServiceRunning = false
        try { udpSocket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun receiveLoop() {
        var relaySocket: Socket? = null
        val buf = ByteArray(64)
        val zeroBuf = ByteArray(12) // toan 0 san (3x float32 0.0f) - dung khi mat ket noi/tam dung
        var packetCount = 0
        var connected = false
        var lastPacketAtMs = System.currentTimeMillis()

        while (running) {
            try {
                if (udpSocket == null) {
                    udpSocket = DatagramSocket(null).apply {
                        reuseAddress = true
                        soTimeout = POLL_TIMEOUT_MS
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
                    // Chi la 1 chu ky poll khong co goi - CHUA chac mat ket noi that.
                    val silentForMs = System.currentTimeMillis() - lastPacketAtMs
                    if (connected && (isPaused || silentForMs >= DISCONNECT_THRESHOLD_MS)) {
                        connected = false
                        try {
                            relaySocket?.getOutputStream()?.write(zeroBuf)
                        } catch (_: Exception) {
                            try { relaySocket?.close() } catch (_: Exception) {}
                            relaySocket = null
                        }
                        updateNotification(if (isPaused) "Đã tạm dừng - gyro về 0" else "Mất kết nối ESP8266 - gyro đã về 0")
                    }
                    continue
                }

                if (packet.length == 12) {
                    lastPacketAtMs = System.currentTimeMillis()

                    if (isPaused) {
                        // Van nhan de khong don backlog, nhung khong forward.
                        if (connected) {
                            connected = false
                            try {
                                relaySocket?.getOutputStream()?.write(zeroBuf)
                            } catch (_: Exception) {
                                try { relaySocket?.close() } catch (_: Exception) {}
                                relaySocket = null
                            }
                            updateNotification("Đã tạm dừng - gyro về 0")
                        }
                    } else {
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

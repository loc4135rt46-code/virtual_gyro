package com.example.virtualgyro

import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

// Phai khop UUID voi EspGyro.ino va port voi gyro_relay.c
private val SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
private val CHAR_GYRO_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
private const val RELAY_HOST = "127.0.0.1"
private const val RELAY_PORT = 8842
private const val DEVICE_NAME = "VirtualGyro"
private const val TAG = "GyroBleClient"

// Khung don gian: app chi lo BLE + forward qua TCP loopback (khong dung
// quyen root o day). gyro_relay (chay root tu boot) moi la thu ghi vao
// /dev/socket/virtgyro, de tranh app thuong bi SELinux chan.
//
// Can wrap trong 1 foreground Service de khong bi he thong kill khi app
// xuong nen, va xin quyen BLUETOOTH_SCAN + BLUETOOTH_CONNECT (Android 12+)
// hoac ACCESS_FINE_LOCATION (Android cu hon) truoc khi goi start().
class GyroBleClient(private val context: Context) {

    private var gatt: BluetoothGatt? = null
    private var relaySocket: Socket? = null
    private var relayOut: OutputStream? = null

    fun start() {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        adapter.bluetoothLeScanner.startScan(scanCallback)
    }

    fun stop() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        closeRelay()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.name != DEVICE_NAME) return
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter.bluetoothLeScanner.stopScan(this)
            gatt = result.device.connectGatt(context, false, gattCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectRelay()
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                closeRelay()
                // co the goi start() lai o day de tu dong scan/ket noi lai
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val char = g.getService(SERVICE_UUID)?.getCharacteristic(CHAR_GYRO_UUID) ?: return
            g.setCharacteristicNotification(char, true)
            val cccd = char.getDescriptor(CCCD_UUID)
            cccd?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            g.writeDescriptor(cccd)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value ?: return  // dung 12 byte: x,y,z float32 LE
            try {
                relayOut?.write(data)
            } catch (e: Exception) {
                Log.w(TAG, "Mat ket noi relay, thu ket noi lai", e)
                connectRelay()
            }
        }
    }

    private fun connectRelay() {
        try {
            relaySocket?.close()
            relaySocket = Socket()
            relaySocket!!.connect(InetSocketAddress(RELAY_HOST, RELAY_PORT), 2000)
            relayOut = relaySocket!!.outputStream
        } catch (e: Exception) {
            Log.e(TAG, "Khong ket noi duoc gyro_relay (dang chay chua?)", e)
        }
    }

    private fun closeRelay() {
        try { relayOut?.close() } catch (_: Exception) {}
        try { relaySocket?.close() } catch (_: Exception) {}
        relayOut = null
        relaySocket = null
    }
}

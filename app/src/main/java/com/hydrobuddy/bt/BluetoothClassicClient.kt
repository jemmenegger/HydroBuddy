// HC-06 Bluetooth Classic (SPP): connect, read SIP lines, send LINK + SET_HEALTH.

package com.hydrobuddy.bt

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import kotlin.concurrent.thread

class BluetoothClassicClient {
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var readerThread: Thread? = null

    @Volatile
    private var onSip: (() -> Unit)? = null

    /** Opens SPP socket to HC-06; background thread calls onSip when a line starts with SIP. */
    fun connect(device: BluetoothDevice, onSip: () -> Unit) {
        close()
        val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        val newSocket = device.createRfcommSocketToServiceRecord(sppUuid)
        newSocket.connect()
        socket = newSocket
        output = newSocket.outputStream
        this.onSip = onSip
        readerThread = thread(name = "BTReader", isDaemon = true) {
            val reader = BufferedReader(InputStreamReader(newSocket.inputStream))
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue
                    val head = trimmed.substringBefore(',').trim()
                    if (head.equals("SIP", ignoreCase = true)) {
                        this.onSip?.invoke()
                    }
                }
            } catch (_: IOException) {
                // Socket closed or link lost
            }
        }
    }

    fun isConnected(): Boolean = socket?.isConnected == true

    /** Sends one command line (caller adds payload, e.g. SET_HEALTH,42). */
    fun sendLine(command: String) {
        val stream = output ?: throw IOException("Not connected")
        stream.write((command.trim() + "\n").toByteArray())
        stream.flush()
    }

    fun close() {
        onSip = null
        try { output?.close() } catch (_: IOException) {}
        try { socket?.close() } catch (_: IOException) {}
        output = null
        socket = null
        readerThread = null
    }
}

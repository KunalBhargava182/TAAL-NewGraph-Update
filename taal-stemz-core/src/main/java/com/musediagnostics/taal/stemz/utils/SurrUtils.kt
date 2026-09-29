package com.musediagnostics.taal.stemz.utils

import android.content.Context
import android.hardware.usb.UsbManager
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SurrUtils {

    enum class ConnectionStatus {
        CONNECTED,
        NOT_CONNECTED,
        DEVICE_DOES_NOT_SUPPORT_OTG,
        INVALID_TAAL_CONNECTED
    }

    fun isTaalDeviceConnected(context: Context): ConnectionStatus {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return ConnectionStatus.DEVICE_DOES_NOT_SUPPORT_OTG

        val deviceList = usbManager.deviceList

        if (deviceList.isEmpty()) {
            return ConnectionStatus.NOT_CONNECTED
        }

        // Check for USB Audio Class device at both device and interface level.
        // Most USB audio devices report class 0 at device level and
        // USB_CLASS_AUDIO only on their interfaces.
        val hasAudioDevice = deviceList.values.any { device ->
            if (device.deviceClass == android.hardware.usb.UsbConstants.USB_CLASS_AUDIO) {
                return@any true
            }
            for (i in 0 until device.interfaceCount) {
                if (device.getInterface(i).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_AUDIO) {
                    return@any true
                }
            }
            false
        }

        return if (hasAudioDevice) {
            ConnectionStatus.CONNECTED
        } else {
            ConnectionStatus.INVALID_TAAL_CONNECTED
        }
    }

    fun readSampleRate(filePath: String): Int {
        FileInputStream(File(filePath)).use { fis ->
            val header = ByteArray(44)
            fis.read(header)

            // Sample rate is at bytes 24-27 in WAV header
            val buffer = ByteBuffer.wrap(header, 24, 4).order(ByteOrder.LITTLE_ENDIAN)
            return buffer.int
        }
    }

    fun getFloatBuffer(filePath: String): List<Float> {
        val file = File(filePath)
        val floats = mutableListOf<Float>()

        FileInputStream(file).use { fis ->
            // Skip WAV header
            fis.skip(44)

            val buffer = ByteArray(4096)

            while (true) {
                val bytesRead = fis.read(buffer)
                if (bytesRead <= 0) break

                for (i in 0 until bytesRead / 2) {
                    val sample = ((buffer[i * 2 + 1].toInt() shl 8) or
                                  (buffer[i * 2].toInt() and 0xff)).toShort()
                    floats.add(sample / 32768f)
                }
            }
        }

        return floats
    }
}

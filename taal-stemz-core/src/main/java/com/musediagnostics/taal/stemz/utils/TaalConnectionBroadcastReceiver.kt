package com.musediagnostics.taal.stemz.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager

class TaalConnectionBroadcastReceiver(
    private val listener: TaalConnectionListener
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                listener.onTaalConnect()
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                listener.onTaalDisconnect()
            }
        }
    }

    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(this, filter)
    }

    fun unregister(context: Context) {
        context.unregisterReceiver(this)
    }

    interface TaalConnectionListener {
        fun onTaalConnect()
        fun onTaalDisconnect()
    }
}

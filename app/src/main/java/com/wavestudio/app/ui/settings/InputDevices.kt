package com.wavestudio.app.ui.settings

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

data class InputDeviceItem(val id: Int, val label: String)

/** Microphones currently known to Android (built-in, wired, USB, Bluetooth). Ids are only stable while a device stays connected. */
fun listInputDevices(ctx: Context): List<InputDeviceItem> = try {
    val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.isSource && it.type != AudioDeviceInfo.TYPE_TELEPHONY }.map { d ->
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (phone-call quality)"
            AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Line in"
            else -> "Input"
        }
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() && !kind.startsWith(it) }
        InputDeviceItem(d.id, if (name != null) "$kind · $name" else kind)
    }
} catch (_: Exception) { emptyList() }

# MuseD App – `deviceIcon` Connection Flow

> **Purpose:** Complete reference for how the TAAL device icon works —
> how the app detects whether the TAAL stethoscope is plugged in,
> what triggers icon state changes, and what logic sits behind each check.
> Written to help the web team add equivalent connected/disconnected status.

---

## 1. What Is the `deviceIcon`?

It is an `ImageButton` in the top-right corner of the **Recording screen** (`fragment_recording.xml`). It displays a small SVG of the TAAL stethoscope and visually communicates device connection status purely through **color**.

```xml
<ImageButton
    android:id="@+id/deviceIcon"
    android:layout_width="40dp"
    android:layout_height="40dp"
    android:src="@drawable/icon_taal"
    app:tint="#333333" />   <!-- Default: gray = disconnected -->
```

---

## 2. The Icon SVG (`icon_taal.xml`)

The SVG is the TAAL stethoscope shape, 35×24dp viewport.

```
Native fill in SVG:  #008DB9  (teal — baked into the SVG paths)
Default XML tint:    #333333  (dark gray — set in layout XML)
```

The SVG's own fill color is **completely overridden** at runtime by `setColorFilter()` in Kotlin code. The XML `app:tint="#333333"` is only the starting state before any code runs.

---

## 3. Two Color States

| State | Color | Hex | When Applied |
|---|---|---|---|
| **Connected** | Teal/blue | `#128CB2` | USB audio device detected |
| **Disconnected** | Dark gray | `#333333` | No USB device / device removed |

Color is changed via:
```kotlin
binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2"))  // connected
binding.deviceIcon.setColorFilter(Color.parseColor("#333333"))  // disconnected
```

---

## 4. Two Detection Mechanisms

The app uses **two separate, complementary mechanisms** to keep the icon in sync.

```
┌─────────────────────────────────────────────────────────┐
│  MECHANISM 1: Snapshot check on every fragment resume   │
│  (handles: app launch, returning from Player screen)    │
├─────────────────────────────────────────────────────────┤
│  MECHANISM 2: Real-time BroadcastReceiver               │
│  (handles: cable plug/unplug while screen is open)      │
└─────────────────────────────────────────────────────────┘
```

---

## 5. Mechanism 1 — Snapshot Check on Resume

### Where it runs

`RecordingFragment.onResume()` → calls `checkDeviceConnectionStatus()`

### What it does

```kotlin
private fun checkDeviceConnectionStatus() {
    val usbManager =
        requireContext().getSystemService(Context.USB_SERVICE) as UsbManager

    if (usbManager.deviceList.isNotEmpty()) {
        binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2"))  // Teal
    } else {
        binding.deviceIcon.setColorFilter(Color.parseColor("#333333"))  // Gray
    }
}
```

### Logic

| Condition | Icon color |
|---|---|
| `usbManager.deviceList` is NOT empty (any USB device present) | Teal `#128CB2` |
| `usbManager.deviceList` is empty (nothing plugged in) | Gray `#333333` |

**Note:** This check does NOT verify USB audio class — it simply asks "is any USB device connected?". This is intentional for the icon: as long as something is plugged into the OTG port, the icon turns teal. The audio-class validation happens separately when recording actually starts (see Section 7).

### When it fires

| Event | onResume() called? |
|---|---|
| App first launch | Yes |
| Return from PlayerFragment (after discard/save) | Yes |
| Screen rotated | Yes (fragment recreated) |
| Cable plugged in while app is in background, then foregrounded | Yes |

---

## 6. Mechanism 2 — Real-Time BroadcastReceiver

### Source: `TaalConnectionBroadcastReceiver.kt` (taal-core module)

```kotlin
class TaalConnectionBroadcastReceiver(
    private val listener: TaalConnectionListener
) : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> listener.onTaalConnect()
            UsbManager.ACTION_USB_DEVICE_DETACHED -> listener.onTaalDisconnect()
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
```

### How RecordingFragment wires it up

```kotlin
// Called from onViewCreated()
private fun setupConnectionReceiver() {
    connectionReceiver = TaalConnectionBroadcastReceiver(object :
        TaalConnectionBroadcastReceiver.TaalConnectionListener {

        override fun onTaalConnect() {
            activity?.runOnUiThread {
                binding.deviceIcon.setColorFilter(Color.parseColor("#128CB2"))
            }
        }

        override fun onTaalDisconnect() {
            activity?.runOnUiThread {
                binding.deviceIcon.setColorFilter(Color.parseColor("#333333"))
            }
        }
    })
    connectionReceiver?.register(requireContext())
}
```

### System broadcasts being listened to

| Broadcast | Android constant | Fires when |
|---|---|---|
| `android.hardware.usb.action.USB_DEVICE_ATTACHED` | `UsbManager.ACTION_USB_DEVICE_ATTACHED` | A USB device is physically plugged in via OTG |
| `android.hardware.usb.action.USB_DEVICE_DETACHED` | `UsbManager.ACTION_USB_DEVICE_DETACHED` | A USB device is physically unplugged |

### Lifecycle — when the receiver is active

```
onViewCreated()  → connectionReceiver.register()   ← starts listening
      ↓
  [fragment visible — icon responds instantly to plug/unplug]
      ↓
onDestroyView()  → connectionReceiver.unregister() ← stops listening
```

The receiver is unregistered in `onDestroyView`, NOT `onPause`. This means it stays active even when the Recording screen loses focus (another dialog opens on top), but is cleaned up when the fragment's view is destroyed (navigating away or back stack popped).

---

## 7. Full Lifecycle Flow Diagram

```
App launches → RecordingFragment created
       │
       ▼
onViewCreated()
  ├─ setupWaveformChart()
  ├─ setupFilterButtons()
  ├─ setupPreAmpSlider()
  ├─ setupButtons()
  ├─ observeState()
  └─ setupConnectionReceiver()
       │
       └─→ BroadcastReceiver REGISTERED
             Listening for ATTACH / DETACH events
       │
       ▼
onResume()
  └─ checkDeviceConnectionStatus()
       │
       ├── UsbManager.deviceList.isNotEmpty()
       │         YES → setColorFilter(#128CB2)  ← TEAL
       │         NO  → setColorFilter(#333333)  ← GRAY
       │
       ▼
  [Fragment is visible to user]
       │
       ├── User PLUGS IN cable while screen is open
       │       → Android fires ACTION_USB_DEVICE_ATTACHED
       │       → BroadcastReceiver.onReceive()
       │       → onTaalConnect() → setColorFilter(#128CB2)  ← TEAL
       │
       ├── User UNPLUGS cable while screen is open
       │       → Android fires ACTION_USB_DEVICE_DETACHED
       │       → BroadcastReceiver.onReceive()
       │       → onTaalDisconnect() → setColorFilter(#333333)  ← GRAY
       │
       └── User navigates to PlayerFragment
               → onDestroyView() → connectionReceiver.unregister()
               → [BroadcastReceiver UNREGISTERED]
               → On returning: onResume() fires → snapshot check again
```

---

## 8. USB Audio Class Validation (Deep Check)

The icon check only asks "is something plugged in?". But before recording starts, the SDK does a **proper USB audio class validation** in two places.

### 8.1 `SurrUtils.isTaalDeviceConnected()` (taal-core)

```kotlin
fun isTaalDeviceConnected(context: Context): ConnectionStatus {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        ?: return ConnectionStatus.DEVICE_DOES_NOT_SUPPORT_OTG

    val deviceList = usbManager.deviceList

    if (deviceList.isEmpty()) return ConnectionStatus.NOT_CONNECTED

    val hasAudioDevice = deviceList.values.any { device ->
        // Check at device level (some devices report audio class here)
        if (device.deviceClass == USB_CLASS_AUDIO) return@any true

        // Check at interface level (most composite USB devices report it here)
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == USB_CLASS_AUDIO) return@any true
        }
        false
    }

    return if (hasAudioDevice) ConnectionStatus.CONNECTED
           else ConnectionStatus.INVALID_TAAL_CONNECTED
}
```

**Return values:**

| Status | Meaning |
|---|---|
| `CONNECTED` | A USB Audio Class device is present — this is a TAAL stethoscope |
| `NOT_CONNECTED` | No USB devices at all |
| `DEVICE_DOES_NOT_SUPPORT_OTG` | `UsbManager` not available on this phone |
| `INVALID_TAAL_CONNECTED` | A USB device is plugged in but it is NOT a USB audio device (e.g. a flash drive) |

### 8.2 `TaalAudioCapture.checkUsbConnection()` (called on startRecording)

```kotlin
fun checkUsbConnection(): Boolean {
    val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        ?: return false
    return deviceList.values.any { device ->
        if (device.deviceClass == USB_CLASS_AUDIO) return@any true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == USB_CLASS_AUDIO) return@any true
        }
        false
    }
}
```

Returns `Boolean` — same audio-class logic as `SurrUtils`, used internally when `AudioRecord` fails to initialize.

### Why two levels of validation?

| Check | Where | Purpose |
|---|---|---|
| `deviceList.isNotEmpty()` | `checkDeviceConnectionStatus()` | Fast icon update — good UX, doesn't need class validation |
| `USB_CLASS_AUDIO` interface scan | `TaalAudioCapture` + `SurrUtils` | Guard before actual recording — prevents crash if a non-audio USB device is plugged in |

---

## 9. What Happens When User Taps Record

If the user taps record with no TAAL device connected, `TaalRecorder.start()` eventually throws an exception. `RecordingFragment` catches it and shows an error dialog:

```kotlin
} catch (e: Exception) {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle("Recording Error")
        .setMessage(e.message)   // e.g. "check USB connection"
        .setPositiveButton("OKAY") { dialog, _ -> dialog.dismiss() }
        .show()
    viewModel.setUiState(RecordingUiState.IDLE)
}
```

**Error scenarios:**

| Condition | Exception thrown | Dialog message |
|---|---|---|
| No USB device at all | `IllegalStateException` | "AudioRecord initialization failed - check USB connection" |
| TAAL connected but claimed by another app | `TaalNotAvailableForUseException` | (from SDK) |
| TAAL connected, AudioRecord OK | No exception | Recording starts normally |

The icon color does NOT change when this error occurs — it stays teal (device is still physically connected). Only an actual DETACH event or app resume changes the icon.

---

## 10. Android Manifest Requirements

The following declarations are required in `AndroidManifest.xml` for USB detection to work:

```xml
<!-- Runtime permission to access USB devices -->
<uses-permission android:name="android.permission.USB_PERMISSION" />

<!-- Declares the app can use USB host mode.
     required=false means the app can still install on devices without OTG. -->
<uses-feature
    android:name="android.hardware.usb.host"
    android:required="false" />

<!-- Audio recording permission (separate from USB) -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
```

**No `intent-filter` for USB device attached on `MainActivity`** — the app does NOT auto-launch when the TAAL is plugged in. USB events are received at runtime via the dynamic BroadcastReceiver only.

---

## 11. Summary — What to Implement on Web

For the web SDK to replicate this behaviour, you need:

| Android mechanism | Web equivalent |
|---|---|
| `UsbManager.deviceList` | `navigator.usb.getDevices()` (WebUSB) |
| `ACTION_USB_DEVICE_ATTACHED` | `navigator.usb.addEventListener('connect', handler)` |
| `ACTION_USB_DEVICE_DETACHED` | `navigator.usb.addEventListener('disconnect', handler)` |
| Snapshot check on page load | Call `navigator.usb.getDevices()` on component mount |
| Icon teal `#128CB2` | Apply connected CSS class / color |
| Icon gray `#333333` | Apply disconnected CSS class / color |
| `USB_CLASS_AUDIO` interface scan | Filter `device.configurations[].interfaces[].alternates[].interfaceClass === 1` (USB Audio = class 1) |

### Web connection check pseudocode

```js
// On page load — snapshot check (equivalent to onResume)
async function checkTaalConnection() {
    const devices = await navigator.usb.getDevices();
    const taalConnected = devices.some(isUsbAudioDevice);
    setIconColor(taalConnected ? '#128CB2' : '#333333');
}

// Real-time events — equivalent to BroadcastReceiver
navigator.usb.addEventListener('connect', (event) => {
    if (isUsbAudioDevice(event.device)) {
        setIconColor('#128CB2');
    }
});

navigator.usb.addEventListener('disconnect', (event) => {
    setIconColor('#333333');
});

// USB Audio Class check — equivalent to SurrUtils.isTaalDeviceConnected()
function isUsbAudioDevice(device) {
    // USB Audio Class = 1
    return device.configurations.some(config =>
        config.interfaces.some(iface =>
            iface.alternates.some(alt => alt.interfaceClass === 1)
        )
    );
}
```

> **Important:** WebUSB requires the user to grant permission to access a specific USB device via `navigator.usb.requestDevice()`. `getDevices()` only returns previously-granted devices. This is different from Android where `UsbManager.deviceList` returns all connected devices (subject to manifest permissions).

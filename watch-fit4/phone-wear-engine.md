# Phone-side Wear Engine integration notes

The Android phone app should send `WatchStatusPayload.buildString(status, selectedMetricsText)` to the watch app through Huawei Wear Engine P2P messaging.

Huawei's Wear Engine sample uses these classes:

- `HiWear`
- `DeviceClient`
- `Device`
- `P2pClient`
- `Message`
- `SendCallback`

The minimum implementation shape is:

```kotlin
// Pseudocode. Add the Huawei Wear Engine SDK first.
val deviceClient = HiWear.getDeviceClient(context)
val p2pClient = HiWear.getP2pClient(context)
val payload = WatchStatusPayload.buildString(status, selectedMetricsText)
val message = Message.Builder()
    .setPayload(payload.toByteArray(Charsets.UTF_8))
    .build()

deviceClient.bondedDevices
    .addOnSuccessListener { devices ->
        val fit4 = devices.firstOrNull { it.name.contains("FIT", ignoreCase = true) }
            ?: devices.firstOrNull()
            ?: return@addOnSuccessListener
        p2pClient.send(fit4, message, sendCallback)
    }
```

Recommended sending policy:

- Send after every successful `/api/status` refresh when watch sync is enabled.
- Throttle to the app refresh interval. The default 2 seconds is acceptable for a connected watch, but 5 seconds is kinder to battery.
- Send immediately when status changes to `finished` or `error`.
- Keep notification sync as fallback when the watch app is not installed, Wear Engine permission is missing, or no bound FIT 4 is found.

Before enabling this in production:

- Add the Huawei Wear Engine SDK dependency from Huawei's official developer distribution.
- Request Wear Engine user authorization for device access.
- Choose the exact peer package name used by the FIT 4 watch app.
- Handle disconnected watch, missing Huawei Health, authorization denial, and send failure without blocking the main app.

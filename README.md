# Ollee Companion (Android)

I love the Ollee board drop-in replacement for classic casio watches. Unfortunately, 
the official Android app doesn't work without play services (ie LineageOS devices). 
Simple Android App to sync time and steps built with the help of Opus 4.8 - it's rough.

A native Android app (Kotlin + Jetpack Compose) for the Ollee watch, built on
the BLE protocol in [`../ollee-ble`](../ollee-ble). It speaks
the watch's custom framing over Nordic UART Service directly.

## Build & run

This project was scaffolded outside Android Studio, so build it there:

1. Open **Android Studio** (Hedgehog or newer) → *Open* this `ollee-android` folder.
2. Let it **Gradle sync** (downloads Gradle 8.9 + AGP 8.5 + SDK 34; Studio's
   bundled JDK 17 is used automatically). If the Gradle wrapper jar is missing,
   Studio regenerates it during sync.
3. Plug in an Android phone (USB debugging on) → **Run**.
4. Grant the Bluetooth + Location permissions when prompted, then **Connect
   watch** (the known address is pre-filled) or **Scan** and pick it.

> The protocol layer (`ble/OlleeProtocol.kt`) is a verified 1:1 port of the
> Python reference and is the part you can trust outright. The raw-GATT manager
> and UI were written without an on-device compile here, so expect to iron out
> the occasional minor issue on first build.

## Architecture

```
ble/
  OlleeProtocol.kt    frame build/parse, CRC-16/CCITT, set-time, reassembler
  OlleeGattManager.kt connect, notifications, serialized writes, request/response
  OlleeRepository.kt  typed reads + writes; stubs for capture-pending features
feature/
  SunCalculator.kt    on-phone sunrise/sunset (NOAA), fully working
  Models.kt           Alarm/Flashlight/Sun data models
MainViewModel.kt      scan, connect, auto-sync, UI state
MainActivity.kt       Compose UI
```

## Feature status

| Feature | Status | To finish |
|---|---|---|
| Automatic time sync | ✅ Working (`0x23`) | — |
| Step counter + daily goal | ✅ Read working | capture *goal-set* + *alert* |
| Sunrise/Sunset by location | ✅ Working (on-phone GPS) | capture to push to watch face |
| WorldTime / timezone | ✅ Set via `setTime(tz)` | capture multi-city list |
| Temperature + hourly log | ✅ Working (records `0x27`/`0x28`/`0x2d`), 30-day history | — |
| Heart-rate history (read-only) | ✅ Working (from records log), 30-day history | — |
| Alarm (time + repeat days) | ✅ Working (`0x25`) | chime/snooze bytes TBD |

### Health/activity records (`0x28`)

A full sync drains the watch log: `0x27` returns the record count, then each
`0x28` returns one 16-byte record `[type:4][tStart:4][tEnd:4][value:4]` (all
big-endian Unix-second timestamps), and `0x2d` acknowledges. Types: `0` steps
(value = steps in interval), `1` temperature (hourly window, value = °C × 100),
`2` heart rate (instantaneous, value = bpm).

Synced records are persisted on-device (`data/RecordStore.kt`, a deduplicated
JSON store) and kept for a rolling **30 days**, so history accumulates across
syncs even though the watch's own log is short-lived. The Temperature and Heart-
rate cards show per-day summaries (daily low/high temperature, daily
min/max/avg HR).

## Finishing a stubbed feature (the capture loop)

Each stub in `OlleeRepository.kt` names the exact action to record. For any one:

1. On the phone: enable *Developer options → Bluetooth HCI snoop log*, toggle
   Bluetooth off/on.
2. In the **official Ollee app**, perform just that one action (e.g. start a
   heart-rate measurement).
3. *Take bug report* → share the zip to a PC.
4. Decode it:
   ```
   ../ollee-ble/.venv/Scripts/python.exe ../ollee-ble/parse_capture.py bugreport.zip
   ```
5. The new `WriteReq` to handle `0x000e` is the command; the `Notify` frames are
   the replies. Its bytes follow the same `AA 55 [CRC] 02 CMD payload` framing —
   drop the new `CMD` + payload encoding into the matching repository method.

## License

[GNU General Public License v3.0](LICENSE).

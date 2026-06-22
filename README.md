# sys-mon-droid

A native **Android client** for [sys-mon](https://github.com/) — view and control
your PC from your phone instead of the browser. It does **not** monitor the phone;
it connects to the sys-mon FastAPI backend running on your PC and renders the same
data natively (Kotlin + Jetpack Compose).

The backend is unchanged: the app is a pure client of the existing REST +
WebSocket API (`docs/API.md` in the sys-mon repo).

## Features

Implemented in this build (core):

- **Connect & auth** — point at any `host:port`, log in (JWT), token persisted and
  verified on launch, log out / log out everywhere.
- **Live dashboard** — CPU (overall + per-core bars + freq/temp), memory (+ swap),
  GPU/iGPU (usage + VRAM + temp), disks (usage + free + I/O), streamed over
  `WS /ws/stream` (~400 ms). Pause/resume, one-shot refresh.
- **Charts** — rolling CPU/RAM/GPU/disk utilisation, hydrated from
  `/api/history/recent`, plus a host-side history backup action.
- **Processes** — top processes by CPU / RAM / Disk / GPU with the two-step
  prepare→confirm kill flow (and rate-limit handling).
- **Power & remote** — restart / shutdown (confirmed) and launch remote control.
- **File explorer** — browse drives & folders, open text files in an editor
  (read + save), image preview, rename / copy / move / delete, new folder,
  properties, favorites, recycle bin (restore / delete / empty), and search
  (with backend indicator).
- **Model Log** — the LLM audit trail, live over `WS /ws/models`, with COLD START
  badges, proxy status, row detail, and clear.

Staged for the next build (the heavy/native-hard ones — see
`docs/ROADMAP.md`): PTY terminal, screen share with input forwarding, WhatsApp,
Godot editor, full session management, PDF preview.

## Build

Requires JDK 17 and the Android SDK (platform 35). `local.properties` points the
build at the SDK; adjust `sdk.dir` if yours differs.

```bash
./gradlew :app:assembleDebug      # build a debug APK
./gradlew installDebug            # install on a connected device / emulator
```

The APK lands in `app/build/outputs/apk/debug/`.

## Connecting

`sys-mon` binds `0.0.0.0:11037`, so reach it as:

- **Emulator:** `10.0.2.2:11037` (the host loopback alias) — this is the default.
- **Physical device:** the PC's LAN IP, e.g. `192.168.1.20:11037`, on the same network.

sys-mon serves plain HTTP on the LAN, so the app ships a permissive
`network_security_config.xml` allowing cleartext. If you front sys-mon with TLS,
tighten that file to https-only.

## Tech

Kotlin · Jetpack Compose (Material 3) · Navigation-Compose · Retrofit + OkHttp ·
kotlinx.serialization · Coroutines/Flow · Coil · DataStore. Manual DI
(`AppContainer`) — no annotation processors. License: MIT.

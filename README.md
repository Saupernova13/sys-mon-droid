# sys-mon-droid

A native **Android client** for [sys-mon](https://github.com/) — view and control
your PC from your phone instead of the browser. It does **not** monitor the phone;
it connects to the sys-mon Rust backend (`sysmon-server`) running on your PC and
renders the same data natively (Kotlin + Jetpack Compose).

The backend is unchanged: the app is a pure client of the existing REST +
WebSocket API (`docs/API.md` in the sys-mon repo).

## Features

- **Connect & auth** — point at any `host:port`, log in (JWT), token persisted and
  verified on launch, log out / log out everywhere.
- **Live dashboard** — CPU (overall + per-core bars + freq/temp), memory (+ swap),
  GPU/iGPU (usage + VRAM + temp), disks (usage + free + I/O), streamed over
  `WS /ws/stream` (~400 ms). Pause/resume, one-shot refresh.
- **Charts** — rolling CPU/RAM/GPU/disk utilisation, hydrated from
  `/api/history/recent`, plus a host-side history backup action.
- **Processes** — top processes by CPU / RAM / Disk / GPU with the two-step
  prepare→confirm kill flow (and rate-limit handling).
- **Power & remote** — restart / shutdown (confirmed, with the server's delay
  echoed back) and launch remote control.
- **Power widget** — when the host has Tasmota smart plugs configured, a power
  card rides the live snapshot stream (watts, load gauge, today's cost) and opens
  a detail screen with a device list (live draw + relay switch per plug) and
  three tabs:
  - **Live** — usage chart (1h/24h/7d), cost breakdown and projections,
    electrical quality (voltage / power factor / reactive), and device info.
  - **Calendar** — a month grid of recorded use, each day showing its cost and a
    24-hour sparkline. Every cell shares one vertical scale so days are
    comparable, hours the plug was offline break the line rather than reading as
    zero, and tapping a day gives its exact kWh / cost / peak.
  - **Schedules** — per-plug on/off windows (label, on time, off time, weekdays,
    enabled), including windows that wrap past midnight. Read-only for viewers.
- **Home-screen widgets** — plug toggles that work with the app closed:
  - a **per-plug tile** (pick its plug when you drop it) showing the name, live
    draw and an ON/OFF pill;
  - an **all-plugs list**, resizable, with the aggregate draw in the header and a
    toggle on every row.

  Taps send the opposite of the state on screen rather than a blind flip, so a
  plug switched by hand can't be driven the wrong way. Android floors widget
  updates at 30 minutes, so the tiles also refresh on tap, after a toggle, on an
  FCM plug alert, and whenever the app polls power — and each one shows the time
  its reading was taken.
- **Plug alerts** (More → Plug alerts) — two layers. *Watched* is the server's
  per-plug flag, shared with the web dashboard and every signed-in device (admin
  only). *Mute on this phone* is local: the alert is still sent, this device just
  doesn't show it.
- **Roles & feature flags** — the client reads the session role from login
  (`admin` vs the read-only `viewer` account) and `GET /api/features`, so
  viewer sessions hide every mutating control (kill, power, file writes,
  terminal, screen share) and disabled server widgets (Model Log, WhatsApp)
  drop out of the navigation instead of erroring.
- **Server settings** — admins can flip the server's widget feature flags
  (WhatsApp / Model Log / ollama proxy / Godot / Power) from the phone, with
  availability hints and restart-required notices.
- **File explorer** — browse drives & folders, open text files in an editor
  (read + save), image preview, **PDF preview** (`PdfRenderer`), rename / copy /
  move / delete, new folder, properties, favorites, recycle bin
  (restore / delete / empty), and search (with backend indicator).
- **Model Log** — the LLM audit trail, live over `WS /ws/models`, with COLD START
  badges, proxy status, row detail, and clear.
- **Terminal** — a PTY shell over `WS /ws/terminal` with a built-in compact ANSI
  emulator (colours, scrollback), a command input + control-key bar (Esc/Tab/^C/
  arrows), auto-resize, and session resume across reconnects.
- **Screen share** — live JPEG frames over `WS /ws/screen` with absolute
  touch→cursor mapping (tap = click, hold = right-click, drag = move/drag), a
  scroll mode, and a keyboard bar that types to the PC.
- **WhatsApp** — chat list + conversation view, live messages over
  `WS /ws/whatsapp`, inline image/sticker media (authed Coil), send text, pin,
  load-older/backfill, contact search, and pairing-status guidance.

Found in `More`: Plug alerts, Terminal, Screen share, WhatsApp (when enabled),
Server settings, and the app + server version line. The Godot editor launcher was
intentionally left out of this client.

### Staying signed in

The server issues 72-hour JWTs. The app renews its token opportunistically —
on launch and on every widget refresh, whenever the token is inside its last
24 hours — via `POST /auth/refresh`. This is what lets the home-screen widgets
keep working without the app ever being opened. A phone left off for longer than
the token's full life still needs a fresh sign-in; the widgets say so and tap
through to the login screen.

Against a server too old to have `/auth/refresh`, renewal quietly no-ops and the
app behaves exactly as it did before.

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

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
- **Two themes** (More → Appearance, per phone) — *Classic* (the original slate +
  mint look, the default) and *Instrument*, which mirrors the sys-mon web panel:
  warm graphite ramp, one identity hue per subsystem, 2px corners, band headers,
  ticked meters and unsmoothed traces.
- **Live dashboard** — CPU (overall + per-core bars + freq/temp), memory (+ swap),
  GPU/iGPU (usage + VRAM + temp), disks (usage + I/O), streamed over
  `WS /ws/stream` (~400 ms). Pause/resume, one-shot refresh. Readings are graded
  with sys-mon's severity bands in both themes: load (CPU/GPU) tops out at
  caution, health (temperatures, capacity, swap) runs to warning. A reading the
  host could not take shows as a dash, not zero.
- **Disks & S.M.A.R.T.** — each volume shows its label, bus/media type and model.
  When the host's S.M.A.R.T. widget is on (CrystalDiskInfo on Windows, smartctl
  on Linux), it adds a temperature/health fact graded by the drive's verdict and an
  HDD- or SSD-specific temperature band. Tapping a volume opens a sheet covering
  every disk the host can see: verdict, identity, usage and sector counters, and
  the attribute table with failing ones flagged. Admins can rescan.
- **Jellyfin banner** — who is streaming on the host's Jellyfin, at the top of
  the dashboard while anyone is.
- **Charts** — rolling CPU/RAM/GPU/disk utilisation, hydrated from
  `/api/history/recent`, plus a host-side history backup action.
- **Processes** — top processes by CPU / RAM / Disk / GPU with the two-step
  prepare→confirm kill flow (and rate-limit handling).
- **Power & remote** — restart / shutdown, confirmed with wording for the host OS
  and its real delay. A confirmed action then takes the whole screen with a
  countdown, then "waiting for the machine to go down". Also launches remote control.
- **Power widget** — when the host has Tasmota smart plugs configured, a power
  card rides the live snapshot stream (watts, load gauge, today's cost) and opens
  a detail screen with a device list (live draw + relay switch per plug) and
  three tabs:
  - **Live** — usage chart (1h / 6h / 24h / 7d / 30d / 90d / 6m / 12m / All,
    long ranges served from the host's hourly rollup), cost breakdown and projections,
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
- **Jellyfin alerts** (More → Jellyfin) — a heads-up popup when someone starts
  streaming on the host's Jellyfin server: one dismissible card per stream (user,
  title, position, paused state), grouped when several are playing and cleared
  when playback ends. Rides the same push registration as plug alerts; a switch
  silences it on this phone. Shutting down or restarting the host while someone
  is streaming asks for a second confirmation first.
- **Roles & feature flags** — the client reads the session role from login
  (`admin` vs the read-only `viewer` account) and `GET /api/features`, so
  viewer sessions hide every mutating control (kill, power, file writes,
  terminal, screen share) and disabled server widgets (Model Log, WhatsApp)
  drop out of the navigation instead of erroring.
- **Server settings** — admins can flip the server's widget feature flags
  (WhatsApp / Model Log / ollama proxy / Godot / S.M.A.R.T. / Power) from the
  phone, with availability hints, restart-required notices, and a warning when the
  server's `settings.json` was unreadable. A Jellyfin section links the host's
  Jellyfin (URL, write-only API key, poll interval, what notifications include,
  the shutdown gate) and tests the connection.
- **File explorer** — a Places strip with every drive (named, capacity meter
  graded by severity, a hue per drive) and your favourites; icons coloured by
  file kind and real host thumbnails; open text files in an editor
  (read + save), image preview, **PDF preview** (`PdfRenderer`), rename, new
  folder, properties, recycle bin (restore / delete / empty, with a truthful
  partial result), and search (with backend indicator).
  - **Multi-select** — long-press to start; batch copy, move, delete, compress,
    and recycle restore / delete.
  - **Transfer jobs** — copy, move, delete, extract and compress run on the host
    as jobs with a progress dialog: a snake animation, percentage, ETA, cancel, and
    a details view (current file, items, data, speed, errors, files left behind).
    Closing it leaves the job running. Against a host without `/api/fs/jobs` the
    app falls back to the old per-item routes.
  - **Archives** (host needs 7-Zip) — browse inside zip / 7z / rar / tar / iso /
    … (folders included), preview images, PDFs and text, save anything else;
    extract here / to a stem folder / to a picked folder; compress to any format
    the host offers. Passwords are asked for, re-asked when wrong, and never stored.
- **Find Me** (More -> Find Me, admin, when the server's Find Me flag is on) — your paired
  Android devices on a map. Per device: fix age and accuracy, battery and network,
  Locate, Ring / Stop ring (with a "Locating..." / "Ring sent..." state read from the
  server's command list), rename, report interval and remove. The map is a built-in
  slippy map over the server's tile proxy (drag, pinch, +/-, Fit all), darkened to
  match the theme, with an accuracy circle per device and the selected device's 24 h /
  7 d / 30 d trail. Add device shows the pairing code, its expiry, the link and a QR,
  and closes when the phone pairs. Polls every 5 s while a command is pending or the
  pair dialog is open, otherwise every 30 s, only while the screen is visible.
- **This phone (built-in tracker)** — the Find Me tracker is part of this app, so there is
  nothing else to install. A "This phone" card at the top of the Find Me screen has a
  **Track this phone** switch (admin only). Turning it on mints a pairing code with the
  admin API, redeems it as this phone through the device API, starts tracking and walks
  the permission checklist (precise location, "Allow all the time", notifications, battery
  exemption, full-screen alerts), each with a fix button. Once paired the card shows
  tracking on/paused, last fix and accuracy, last upload result, queued fixes, report
  interval, push on/off, **Report now** and **Unpair**, and the phone's own row in the
  device list is marked "(this phone)". The tracker keeps its own device token and
  storage, so signing out of sys-mon does not unpair it. Locate and ring commands arrive
  instantly over FCM (`type=findme`) and otherwise with the next report.
  - Always visible: while sharing, a persistent "Sharing location with <server>"
    notification (Report now, Pause) stays up. Nothing is hidden.
  - Fixes are queued on the phone (file, capped at 1000) and uploaded in chunks of at
    most 200; a failed upload retries after 30 s, 1, 2, 5, then 10 minutes and fixes are
    never dropped. If the server drops the phone the app stops and asks to pair again.

  | Permission | Why |
  |---|---|
  | Fine and coarse location | the fixes sent to sys-mon |
  | Background location ("Allow all the time") | keep reporting and answer locate while the app is closed |
  | Foreground service (location, media playback) | the persistent tracking notification and the ring alarm |
  | Notifications | show that tracking is on |
  | Boot completed | resume tracking after a reboot |
  | Battery optimisation exemption | stop the system from pausing the tracker |
  | Full-screen intent | show the Stop screen over the lock screen when ringing |
  | Vibrate, wake lock | ring alert |
  | Internet, network state | talk to sys-mon, report wifi / cellular / none |

- **Model Log** — the LLM audit trail, live over `WS /ws/models`, with COLD START
  badges, proxy status, row detail, and clear.
- **Terminal** — a PTY shell over `WS /ws/terminal` with a built-in compact ANSI
  emulator (colours, scrollback), a command input + control-key bar (Esc/Tab/^C/
  arrows), auto-resize, and session resume across reconnects. Titled with the
  host's shell; UTF-8 split across frames decodes correctly.
- **Screen share** — live JPEG frames over `WS /ws/screen` with absolute
  touch→cursor mapping (tap = click, hold = right-click, drag = move/drag), a
  scroll mode, and a keyboard bar that types to the PC. The Windows/Super key is
  named for the host; Win+V and Win+D appear on Windows hosts only.
- **WhatsApp** — chat list + conversation view with profile pictures, live
  messages over `WS /ws/whatsapp`, inline image/sticker media (authed Coil) with a
  zoomable viewer and Save, send text and files, pin, load-older/backfill,
  contact search, and pairing-status guidance including why the sync daemon
  stopped. Reading a chat leaves it unread; *Mark as read* clears it on WhatsApp too.

Found in `More`: Plug alerts, Terminal, Screen share, WhatsApp (when enabled),
Server settings, Appearance, and the app + server version line. The Godot editor launcher was
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

## Logs

The app keeps an on-device log so you can send it when something breaks.

- **Where:** `Download/sys-mon/logs/` (Android 10+, visible in any file manager, no
  permission needed). On Android 8-9 it is the app's external files `logs` folder.
  One file per hour, `sysmon-YYYYMMDD-HH.log`, capped at about 2 MB each.
- **Retention:** files older than 24 hours are deleted on app start and whenever a
  new hourly file opens. Nothing is kept longer.
- **Logged:** app start (version, device model, Android version), screen route
  changes, every HTTP call as `METHOD /path?query status ms`, API errors, WebSocket
  connect/close/failure, tracker lifecycle (service, pair/unpair, command kind and
  id, upload counts), uncaught crashes with full stack traces, and the previous
  process exit reason (Android 11+).
- **Never logged:** headers, request/response bodies, tokens (`token`,
  `access_token`, `password`, `code` query values show as `***`), file contents,
  chat messages, WhatsApp text, or location coordinates.
- **Settings > Logs:** the **Write logs** switch (default on; when off nothing is
  written, crashes included, but old files are still pruned after 24 hours),
  **Share logs** (zips the current files and opens the share sheet) and
  **Clear logs**.

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

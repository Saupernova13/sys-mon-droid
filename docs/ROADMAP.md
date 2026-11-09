# Roadmap

The first build ported the high-value, REST/JSON-shaped surface of sys-mon. The
heavier, natively-hard features were staged afterwards and are now implemented.
The backend was never modified — this is purely a client.

## Done

### Stage 6 — Terminal ✅
- `WS /ws/terminal?session_id=<uuid>`; binary frames carry raw PTY keystrokes,
  text frames are JSON control messages (`{type:"resize",rows,cols}`).
- Self-contained compact ANSI/VT emulator (`core/term/TerminalEmulator.kt`):
  SGR colours (16 / 256 / truecolor), cursor + erase + scroll-region ops, OSC
  consume, scrollback. Renders to styled spans (no external terminal AAR).
- Command input + control-key bar (Esc/Tab/^C/^D/arrows). Grid auto-sizes to the
  view and sends a resize. Session id persisted in DataStore for resume.

### Stage 7 — Screen share ✅
- `WS /ws/screen`: server pushes binary JPEG frames (decoded with `BitmapFactory`);
  client sends `ScreenEvent` JSON with `x`/`y` normalised 0..1 over the letterboxed
  image.
- Absolute pointer mapping (tap=click, long-press=right-click, drag=move/drag),
  a scroll mode, and a keyboard bar (`typeString` + Enter/Tab/Esc/Backspace/Win).

### Stage 8 — WhatsApp ✅
- `/api/whatsapp/{status,chats,messages,contacts,media,send,pin,backfill}` plus
  `WS /ws/whatsapp` for live messages.
- wacli's loosely-shaped rows are parsed as raw JSON and normalised in Kotlin
  (mirrors the web frontend's `normChat`/`normMsg`). Chat list → conversation,
  inline image/sticker media via the authed Coil loader, send text, pin,
  load-older/backfill, contact search, pairing-status banner.
- Send media: the composer's attach button picks any file (system picker) and
  streams it to `/api/whatsapp/send-media` as a raw body (`?to=&filename=&caption=`,
  not multipart), 100 MiB cap checked client-side, with an optional caption.

### Stage 9 (partial) — PDF preview ✅
- PDF preview via Android's `PdfRenderer` for `/api/fs/file` PDFs: the file is
  fetched with the authed OkHttp client to a cache file and rendered page-by-page
  (capped to keep memory bounded). Opened by tapping a `.pdf` in the file explorer.

### Stage 10 — Rust backend parity (0.3.0) ✅
- Fixed the two decode breaks from the Python→Rust port: `/api/history/recent`
  and `/api/fs/search` now return `{items, ...}` wrappers; power/remote-control
  envelopes retyped to `{status, delay_seconds}` / `{status}`.
- Role awareness: `role` captured from login/verify and persisted; the read-only
  `viewer` account gets a fully read-only UI (no kill/power/writes/tools).
- Feature flags: `GET /api/features` gates the Model Log tab and the WhatsApp
  entry so disabled server routers (which 404 wholesale) never render.
- Power widget: snapshot-fed dashboard card + detail screen over
  `/api/power-usage` and `/api/power-usage/history`.
- Admin settings screen: `POST /api/settings` toggles with availability hints.
- `/api/version` shown next to the app version; DTOs pinned by unit tests
  decoding fixtures captured from a live server.

### Stage 11 — Power parity + home-screen widgets (0.7.0) ✅
- **Calendar tab** (`/api/power-usage/calendar`): month grid, per-day cost and a
  24-hour sparkline on one shared vertical scale, month paging capped at the
  current month, tap-a-day for exact figures (the web tooltip has no touch
  equivalent).
- **Schedules tab** (`GET`/`POST /api/power/schedules`): per-plug on/off windows
  with weekday selection and midnight wrapping, edited as a local draft because
  the endpoint replaces a plug's whole set in one call. Times go through the
  Material picker — the backend silently drops rows whose times it can't parse.
- **Plug alerts screen**: the server's per-plug `alert` flag (via
  `POST /api/power/devices`, shared with the dashboard) plus a per-phone mute
  list that filters what `PlugAlertNotifier` renders and re-sums the totals.
- **Jellyfin stream alerts**: `type=jellyfin_stream` FCM pushes carry the full
  set of live streams and are rendered by `JellyfinAlertNotifier` (own
  `jellyfin_streams` channel, per-session cards, only `start` pops). A
  `jellyfin_alerts` DataStore switch gates it per phone, and the power actions
  retry with `?force=true` after a 409 `jellyfin_streaming` confirm.
- **Home-screen widgets**: a configurable per-plug tile and an all-plugs list,
  both driven by `PlugWidgetRepository` — cached snapshot, relay toggles, and no
  dependency on the app having been opened.
- **Sliding token renewal** (`POST /auth/refresh`, added backend-side): the 72-hour
  JWT is renewed inside its last 24 hours on app launch and on every widget
  refresh, which is what makes the widgets viable at all.

### Stage 12 — sys-mon parity, Aug–Nov 2026 (0.8.0) ✅
Everything the web UI gained since the 0.7.0 power work that makes sense on a phone:
- **Instrument theme** (opt-in, More → Appearance) built from `DESIGN.md` and the
  `base.css` tokens, beside the unchanged Classic theme. `ui/theme/Palette.kt` is the
  only place colours live; components read `SysMon.palette`.
- **Severity** (`ui/common/Severity.kt`, a port of `severity.js`): one band table,
  load vs health, HDD/SSD temperature bands, CrystalDiskInfo/smartctl verdicts.
- **Absent is not zero**: drive I/O rates, CPU frequencies and GPU readings are
  nullable and render as a dash, matching the backend's `Option` fields.
- **Disks**: label / bus / media type / model per volume; S.M.A.R.T. fact and a
  sheet over `GET /api/smart` with admin `POST /api/smart/rescan`.
- **Files**: Places (drives with hue + graded capacity, favourites), kind colours,
  `/api/fs/thumbnail`, long-press multi-select, transfer jobs over `/api/fs/jobs`
  (progress, cancel, details, snake animation) with a fallback to the old routes,
  and archives over `/api/fs/archive/*` + extract/compress jobs with password retry.
- **Power**: 1h–12m/All history ranges from the hourly rollup; the going-down screen
  after a confirmed restart/shutdown; prompts worded from `host.power_delay_s`.
- **WhatsApp**: avatars (`/api/whatsapp/avatar` + batch refresh), explicit
  `mark-read` (reading no longer marks read server-side), image viewer with Save.
- **Jellyfin**: `/api/jellyfin/config` + `test` in Server settings; dashboard banner
  over `/api/jellyfin/streams`.
- **Host info** (`/api/features` `host`): screen-share key names and Windows-only
  combos, terminal title from the shell, explorer start at `fs_root`.
- **Compatibility**: recycle-bin clear reads `{ok, partial, deleted, errors}`;
  terminal output (now binary frames) decodes UTF-8 across frame boundaries.

Deliberately not ported: window minimise/maximise, the dock, sidebar and window
edge resizing (desktop window chrome), and `/auth/local` (loopback-only by design).

### Stage 13 — Find Me (0.9.0) ✅
- Admin Find Me screen over `/api/findme/*` (gated on `features.findme`): device list with
  Locate / Ring / Stop ring and pending-command state, rename / interval / remove, pairing
  dialog (code, countdown, link, QR via `coil-svg`), address line from `/api/findme/geocode`.
- Compose slippy map (`ui/findme/SlippyMap.kt`, pure `MapMath.kt`) over the authed tile
  proxy; no map SDK. Trail for 24 h / 7 d / 30 d from `/api/findme/devices/{id}/history`.

### Stage 14 — Built-in tracker (0.11.0) ✅
- The standalone find-me-droid tracker now lives in `core/tracker/*` (own DataStore,
  OkHttp/Retrofit client and device token; fix queue, backoff, command processor, ring
  and location services, boot receiver). Pairing needs no QR: the logged-in admin mints a
  code and redeems it as this phone. UI is the "This phone" card (`ui/findme/ThisPhoneCard.kt`).
- FCM `type=findme` commands route from `SysMonMessagingService` to the tracker when paired.

## Not included / future

- **Godot editor** — intentionally skipped in this client.
- **Full session management UI** — terminal sessions resume via a persisted id,
  but there is no screen to list/close arbitrary terminal/file-explorer sessions
  (`/api/sessions/*`).
- **History explorer** — `/api/history` pagination, `/api/history/processes`
  (what ran at a past timestamp), and CSV/JSON export.
- **Screen-share quality/scale controls** — server-side `config.SCREEN_*` only.
- **Widget updates faster than 30 minutes** — Android's `updatePeriodMillis`
  floor. Event-driven refreshes (tap, toggle, FCM alert, in-app poll) cover the
  cases that matter; a foreground service or a short-interval alarm would cost
  battery for little gain.
- **`RemoteCollectionItems` for the list widget** — the non-deprecated
  collection API is API 31+, and this module ships to minSdk 26. Revisit if the
  floor ever rises.

## Notes
- All streams authenticate with `?token=<jwt>` (see `ApiProvider.openWebSocket`).
- The Model Log decodes `cold_start`/`stream` defensively: the backend emits them
  as SQLite integers (`0`/`1`), so a flexible boolean serializer accepts int or
  bool (`core/net/Serializers.kt`).

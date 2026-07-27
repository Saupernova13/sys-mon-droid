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
- **Home-screen widgets**: a configurable per-plug tile and an all-plugs list,
  both driven by `PlugWidgetRepository` — cached snapshot, relay toggles, and no
  dependency on the app having been opened.
- **Sliding token renewal** (`POST /auth/refresh`, added backend-side): the 72-hour
  JWT is renewed inside its last 24 hours on app launch and on every widget
  refresh, which is what makes the widgets viable at all.

## Not included / future

- **Godot editor** — intentionally skipped in this client.
- **Send media** (`/api/whatsapp/send-media`) — outgoing text only for now;
  attaching/sending images from the phone is a future addition. (Raw-body POST
  with `?to=&filename=&caption=` query params, not multipart.)
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

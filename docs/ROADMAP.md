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

## Not included / future

- **Godot editor** — intentionally skipped in this client.
- **Send media** (`/api/whatsapp/send-media`) — outgoing text only for now;
  attaching/sending images from the phone is a future addition.
- **Full session management UI** — terminal sessions resume via a persisted id,
  but there is no screen to list/close arbitrary terminal/file-explorer sessions
  (`/api/sessions/*`).
- **Screen-share quality/scale controls** — server-side `config.SCREEN_*` only.

## Notes
- All streams authenticate with `?token=<jwt>` (see `ApiProvider.openWebSocket`).
- The Model Log decodes `cold_start`/`stream` defensively: the backend emits them
  as SQLite integers (`0`/`1`), so a flexible boolean serializer accepts int or
  bool (`core/net/Serializers.kt`).

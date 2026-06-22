# Roadmap

The first build ports the high-value, REST/JSON-shaped surface of sys-mon. The
remaining features are heavier to do natively and are staged next. The backend
already exposes all of them — only the Android UI is outstanding.

## Stage 6 — Terminal
- `WS /ws/terminal?session_id=<uuid>`; binary frames are raw PTY bytes, text frames
  are JSON control messages (`{type:"resize",rows,cols}`).
- Needs an ANSI terminal view. Options: the Termux `terminal-view` /
  `terminal-emulator` AARs, or a compact ANSI parser rendering to a monospaced grid.
- Wire a soft-keyboard + an extra keys row (Esc, Tab, Ctrl, arrows). Send resize on
  layout changes. Persist sessions via the Sessions API.

## Stage 7 — Screen share
- `WS /ws/screen`: server pushes binary JPEG frames; client sends `ScreenEvent` JSON
  (`mousemove/mousedown/mouseup/scroll/keydown/keyup`) with `x`/`y` normalised 0..1.
- Decode JPEG (`BitmapFactory`) into a Compose `Image`; map touch/drag/scroll and a
  soft keyboard to normalised events. Add a quality/scale control.

## Stage 8 — WhatsApp
- `/api/whatsapp/{status,chats,messages,contacts,media,send,send-media,pin,backfill}`
  plus `WS /ws/whatsapp` for live messages.
- Chat list → thread view, media via Coil (authed loader already exists), text +
  media send, pin, backfill.

## Stage 9 — Godot, sessions, PDF
- Godot: `POST /api/godot/start|stop`, `GET /api/godot/status`; open the editor URL
  in a Custom Tab / WebView once started.
- Sessions: surface and manage terminal + file-explorer sessions.
- PDF preview via Android's `PdfRenderer` for `/api/fs/file` PDFs.

## Notes
- All streams authenticate with `?token=<jwt>` (see `ApiProvider.openWebSocket`).
- The networking, auth, theming, charts, and Coil-with-auth plumbing are already in
  place, so these stages are mostly new screens + a couple of decoders.

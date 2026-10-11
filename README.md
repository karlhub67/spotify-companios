# Spotify Companion (Android) - Stage 3: receiver milestone

Receives the current song from your existing Windows tracker over your home Wi-Fi.
It does NOT edit Messenger Notes yet (that is a later stage).

## Testing status (honest)
Written and cross-checked against your `server.py` / `dashboard.html` by reading, but
**not compiled and not run** (no Android SDK or network in the build environment). The
first Gradle sync/build on your PC is the real test. If Android Studio shows a red error,
copy the exact message back to me.

## What the existing server does (what this app follows)
| Item | Value (from server.py) |
|---|---|
| Start page | `GET http://<pc-ip>:<port>/` (dashboard.html; used only by Test Connection) |
| WebSocket | `ws://<pc-ip>:<port>/ws` (same port, default 8765) |
| Pairing | within 10 s of opening, phone sends `{"type":"pair","code":"123456"}` (code is a JSON *string*) |
| Success | `{"type":"paired"}` then `{"type":"snapshot", ...}` |
| Updates | `{"type":"track_changed"/"playback_changed"/"no_source","title","artist","is_playing","timestamp"}` |
| Refusal | `{"type":"error","message":"...","fatal":true}` then server closes (wrong code, blocked, timeout) |
| Lockout | 5 wrong codes from one phone = blocked 5 minutes |
| Origin check | rejects browser pages from other sites; this app sends no Origin header, so it passes |
| Private-only | non-private client IPs get 403 |
| "New code" / server stop | server closes the socket; a restarted server has a NEW code |

The app sends nothing else (no `ping`/`get_state`), because the server answers unknown
messages with an error. Dead links are detected with WebSocket-level pings every 20 s.

## Project layout
```
SpotifyCompanion/
  settings.gradle.kts, build.gradle.kts, gradle.properties
  gradle/wrapper/gradle-wrapper.properties
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/res/{layout/activity_main.xml, values/*.xml, xml/network_security_config.xml}
  app/src/main/java/com/example/spotifycompanion/
    MainActivity.kt       screen, Test Connection, permission prompt
    ConnectionService.kt  foreground service: WebSocket, pairing, reconnect, dedupe
    AppState.kt / Models.kt   in-memory state + log shared by service and screen
    Net.kt                private-IP + input validation
    Prefs.kt              saves PC address and port only
    TrackEvents.kt        EXTENSION POINT for the future Messenger Notes module
```

## 1. Build the APK
1. Install **Android Studio** (current stable) from developer.android.com/studio.
2. Start it > **Open** > choose the `SpotifyCompanion` folder (the one containing `settings.gradle.kts`).
3. Let **Gradle Sync** finish (first time: several minutes, needs internet; Studio downloads Gradle 8.7,
   Android SDK 34 if missing - accept the license prompts). If Studio offers an "Android Gradle Plugin
   upgrade", you can decline.
4. Menu **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
5. When the "APK(s) generated" balloon appears click **locate**. File:
   `app/build/outputs/apk/debug/app-debug.apk`

## 2. Install on your phone
**Option A - USB (easiest to debug):**
1. Phone: Settings > About phone > tap **Build number** 7 times. Then Settings > System > Developer options > **USB debugging** ON.
2. Connect USB, accept the "Allow USB debugging" prompt on the phone.
3. In Studio pick your phone in the device dropdown and press the green **Run** button.

**Option B - copy the APK:**
1. Copy `app-debug.apk` to the phone (USB cable, Google Drive, etc.).
2. Open it from the Files app. Android asks to allow "install unknown apps" for that app - allow it, then Install.
(Debug APKs are fine for personal use; they are signed with a debug key.)

## 3. Connect to your PC server
1. PC: `python app.py` > **Start monitoring** > **Start phone server**. Note the address (e.g. `http://192.168.100.119:8765`) and the 6-digit code.
2. Phone on the SAME Wi-Fi (VPN off). Open Spotify Companion.
3. Enter IP `192.168.100.119` (no `http://`, no port) and port `8765`. Tap **Test Connection** - expect "TEST OK".
4. Type the 6-digit code, tap **Connect**. Allow the notification prompt (optional, recommended).
5. Status turns **CONNECTED** and the current song appears. Skip a song on the PC: it changes on the phone within ~2 s.

The IP and port are saved. The pairing code is NOT saved (the PC creates a new one on every server start),
so you type it each time you connect.

## Permissions and security
| Permission | Why |
|---|---|
| `INTERNET` | open the socket to your PC |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | run the connection as a foreground service |
| `POST_NOTIFICATIONS` (Android 13+, optional) | show the required status notification |

- **Cleartext:** your server uses `http`/`ws` (no encryption). Android blocks that unless allowed, so
  `res/xml/network_security_config.xml` enables cleartext. XML cannot limit it to private IPs, so the app
  itself refuses any address that is not a private IPv4 (10.x, 172.16-31.x, 192.168.x, 127.x, 169.254.x).
  It makes no other network requests. Only use this on your own home Wi-Fi.
- **Pairing code:** typed into a masked field, sent once per connection, kept only in memory, never logged
  or written to disk, wiped when you disconnect. `allowBackup` is off.
- No Spotify credentials, no cloud, no analytics, no ads.
- The server is not exposed to the internet by this app; keep it that way (no port forwarding).

## Background behavior (not guaranteed)
The connection runs in a **foreground service**, which shows a persistent notification ("Spotify Companion",
with a Disconnect button). That makes Android far less likely to stop it with the screen off, but it is **not a
guarantee**: battery savers, Doze, Wi-Fi power saving, and manufacturer task killers (Samsung, Xiaomi, Oppo,
Vivo, Huawei...) can still pause or kill it. Recommended: Settings > Apps > Spotify Companion > Battery >
**Unrestricted**. If the process is killed, the app does not restart itself (it no longer has the pairing code);
open it and Connect again. Swiping the app away from Recents normally leaves the service running; the
notification's **Disconnect** button or the app's Disconnect button stops it.
Reconnects after a lost connection use waits of 1, 2, 4, 8, then 15 seconds, forever, until you Disconnect.

## Reliability behavior
- Invalid / oversized / unknown messages: ignored and logged.
- Duplicate updates (same song/artist/state): ignored (also after a reconnect snapshot).
- Server stopped or Wi-Fi lost: status DISCONNECTED, auto-retry.
- Wrong code, locked out, or pairing timed out (`fatal`): the app stops and does NOT retry (retrying would
  burn your 5 attempts).
- If you restart the PC server or press "New code", the old code is invalid. The app's reconnect will be
  refused once ("Wrong pairing code") and stop - enter the new code and tap Connect. (Counts as 1 of 5 attempts.)
- Songs that change while the phone is disconnected are not replayed; you only get the latest song via the
  snapshot after reconnecting.

## Messenger Notes extension point (nothing fake in the UI)
`TrackEvents.kt` defines `TrackEvent`, `TrackEventListener` and `TrackEvents.register(...)`. The service
dispatches every real, de-duplicated change there. Snapshots are deliberately never dispatched (your Stage 2
README says a snapshot must not update a Note). The next stage adds a class implementing `TrackEventListener`
and registers it in `ConnectionService.registerFutureModules()`.

## Troubleshooting
| Symptom | Likely cause / fix |
|---|---|
| Test Connection: "Cannot reach ..." / timeout | Different Wi-Fi, phone server not started, wrong IP/port, firewall rule missing (see STAGE2_README step 4), AP/client isolation or Guest network, phone VPN on. Open `http://IP:8765` in the phone's Chrome - if that fails too, it's the network/PC, not the app. |
| "Enter your PC's private IPv4 address" | Use digits only like `192.168.100.119`; no `http://`, no port, no hostname. |
| Test: "refused this phone" (403) | PC sees the phone's address as non-private (VPN/mobile data). Turn VPN off, use Wi-Fi only. |
| "PC says: Wrong pairing code." | Use the code currently shown in the PC window. It changes on every server start / New code. 5 wrong tries = blocked 5 min ("Too many wrong codes"). |
| "Pairing timed out" | Rare; tap Connect again. |
| CONNECTED but no updates | PC "Start monitoring" is not running, or Spotify has no playing track. Check the PC activity log for "Sent to 1 phone(s)". |
| Disconnects with screen off | Battery optimization / vendor killer - set Battery to Unrestricted, keep the notification allowed. |
| No notification visible | Android 13+ notification permission denied; the service still runs (see it in the notification-shade "active apps" list). Enable under App info > Notifications. |
| Studio sync error about SDK/JDK | File > Settings > Build > Gradle > Gradle JDK = the bundled JDK 17 or newer; install "Android SDK Platform 34" in SDK Manager. |
| "Install blocked" on the phone | Allow "install unknown apps" for the app you opened the APK from. |

## Test checklist
- [ ] Project syncs and builds; app opens showing DISCONNECTED
- [ ] Test Connection with the right IP/port shows TEST OK; wrong IP shows TEST FAILED with a readable reason
- [ ] Wrong code: error "Wrong pairing code", app stops, no endless retry
- [ ] Right code: CONNECTED, current song + artist + Playing/Paused appear at once
- [ ] Skip 5 songs on the PC: all 5 appear on the phone, in order, each once (check the debug log)
- [ ] Pause -> Paused; resume -> Playing; title unchanged
- [ ] Close Spotify -> "Nothing playing"; reopen -> song returns
- [ ] Turn phone Wi-Fi off ~20 s, on again -> DISCONNECTED then CONNECTED automatically (same code still valid)
- [ ] Stop phone server on PC -> phone shows DISCONNECTED with retry countdown; start it again, new code, Connect works
- [ ] PC "New code" -> phone disconnects; old code rejected; new code works
- [ ] Lock the phone screen 5 minutes, skip songs on the PC, unlock -> latest song shown (note result for your phone model)
- [ ] Force-close and reopen the app: IP and port are pre-filled; code field empty
- [ ] Disconnect button and notification Disconnect button both stop the service and remove the notification

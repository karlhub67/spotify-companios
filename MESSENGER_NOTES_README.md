# Stage 3: Spotify -> Messenger Notes (diagnostic + manual test first)

## Read this first: what is and is NOT verified
| Item | Status |
|---|---|
| Messenger's real Notes screen (labels, buttons, limits, replace/delete behaviour) | **NOT inspected.** Messenger is not available in my build environment. Step 1 ("is the Notes UI reliably reachable?") can only be answered on YOUR phone with diagnostic mode below. **I do not know yet whether it can be automated.** |
| Label lists in the code (`Your note`, `Share`, ...) | **Guesses.** Editable in the app (Advanced: control labels). Nothing is hard-coded to coordinates. |
| Pure logic (Note text formatting, dedupe, coalescing, never-overlap, retries, screen classification, redaction) | **Tested**: 77 checks pass (`logic-tests/LogicTests.kt`, run with kotlinc, synthetic screens). One real bug was found and fixed by these tests (a title containing `{artist}` was expanded twice). |
| Android-facing Kotlin (service, controller, settings, state) | **Type-checked only** against hand-written stubs. Not compiled with the real Android SDK (blocked here), not run. |
| `MainActivity.kt`, layout, manifest edits | Not compiled. XML files parse; Kotlin was only read through. First Android Studio build is the real test. |
| Existing receiver / pairing / server / tracker | **Unchanged in behaviour.** `server.py`, `app.py`, `dashboard.html` untouched. In `ConnectionService.kt` only 3 lines were added (register the Notes module; forward the snapshot). |
| APK | **I could not build one** (no Android SDK / Gradle repositories reachable). Your uploaded `app-debug.apk` is the old build without these features. |

## Update 1 (after your screen recording, Messenger 582.0.0.22.92)
The recording showed the app reaching the Notes editor ("New note", disabled **Share**, "Share a thought…" field) but not recognising it, then pressing Back and giving up (`NOTES_ENTRY_NOT_FOUND`). Fixes:
- The editor is now recognised while **Share is disabled** (it is disabled while the field is empty); Share only has to be enabled at click time (waits up to 3.5 s after typing, else `SHARE_DISABLED`).
- Default labels added from what the recording showed: entry `Drop a thought`, `Post a note…`; editor markers `Share a thought…`, the "Friends and connections can see your note…" footer.
- If the screen after the Notes entry is not recognised, the job now **stops without pressing Back** (`COMPOSER_NOT_RECOGNISED`) and puts that screen's controls in the diagnostic box.
- Bug fixed: a failed test could still be "approved". Only a successful test can be approved now, and the old approval was cleared.
- Still unknown: whether Messenger exposes the "Share a thought…" field as an editable node. If not, the report will say "NO editable text field exposed" and automation is not possible with accessibility.

## What was added
New files (all in `app/src/main/java/com/example/spotifycompanion/` unless noted)
- `MessengerNotesService.kt` - AccessibilityService, Messenger only, plus the Note job (the verified step-by-step procedure)
- `NoteAutomation.kt` - connects `TrackEvents` to the service; timers, one job at a time, approval gate
- `NotePlanner.kt` - pure rules: new-track detection, delay, newest-only queue, retries, stop
- `NoteFormat.kt` - pure: templates, sanitising, length fitting (counts code points, emoji safe)
- `ScreenModel.kt` - pure: screen classifier ("is this the Notes editor?") and the privacy-filtered diagnostic report
- `NoteSettings.kt`, `NoteState.kt` - saved settings / screen state (separate from your `AppState`)
- `res/xml/messenger_accessibility_config.xml` - service configuration
- `MESSENGER_NOTES_README.md` (this file), `logic-tests/LogicTests.kt`

Modified: `AndroidManifest.xml` (service + `<queries>` for Messenger), `res/values/strings.xml`, `res/layout/activity_main.xml` (new section; old views untouched), `MainActivity.kt` (new `setupNotes()` + handlers appended; old code unchanged except a few hook lines), `TrackEvents.kt` (optional `onSnapshot` default method), `ConnectionService.kt` (3 lines).

## Safety design (what the code enforces)
- Service is limited to package `com.facebook.orca`; Android only delivers that app's events. It requests only `canRetrieveWindowContent`. No gestures, no coordinate taps, no key logging.
- You must switch it on yourself in Android Settings. It does nothing at all until Diagnostic mode is on, you press Test, or (after your approval) automatic mode is on.
- Before **every** capture and **every** click, the active window is re-checked to be Messenger; otherwise the job stops (`LEFT_MESSENGER`).
- Text is typed **only** when the screen is classified `NOTE_COMPOSER`: an editable field + an enabled Share control + a Notes marker label, and **no chat controls** (Send, Message box, Like, call buttons...). If anything chat-like is visible it stops with `CHAT_SCREEN_DETECTED`. The code never clicks "Send".
- After typing, the field is read back and must equal the intended text. Immediately before Share it is checked again. After Share it waits for the editor to close and looks for the new text on screen: `VERIFIED`, or `UNVERIFIED` (reported as uncertain, never as success), or `SHARE_NOT_CONFIRMED`.
- Diagnostic reports print label text **only** if it is known UI vocabulary (Share, Your note, 12/60 ...). Chat names, message previews, typed text and passwords appear as `<redacted N chars>`. Nothing is written to disk; "Copy" puts it on the clipboard only when you press it.
- Automatic mode needs: accessibility service running + you approved a manual test **for the installed Messenger version**. A Messenger update resets the approval. Automation is never "on" after an app restart.
- No passwords, cookies, private APIs or authentication tricks anywhere.

## Limitations you should expect (not hidden)
- Phone must be **unlocked** (`PHONE_LOCKED` otherwise). Accessibility cannot operate a locked screen.
- Messenger has to be opened. Whether Android lets the app launch Messenger from the background on your phone is **untested**; if blocked you get `MESSENGER_NOT_FOREGROUND` / `MESSENGER_LAUNCH_BLOCKED`, and nothing is pretended. It will take over the screen while it works.
- If Messenger is inside a chat, the job may press Back (max 3, setting can be turned off) to reach the Notes screen.
- Note limit: default **60 characters is an assumption**. If Messenger's field reports its own max length, the smaller value is used and logged. Check the character counter in the diagnostic report.
- Whether an existing Note must be deleted before a new one can be made is unknown. Default: the editor's text is replaced. "Experimental: delete first" exists but is untested.
- After a Wi-Fi drop, a song that changed meanwhile is picked up from the reconnect snapshot **only if** this app already published a Note in this run (the first snapshot after pairing never updates the Note, as your server README requires).

## 1. Build the APK
1. Replace your project folder contents with this `SpotifyCompanion` folder (or copy the changed files listed above over yours).
2. Android Studio > Open the folder > let Gradle sync (no new dependencies were added).
3. If a red error appears, copy the exact message to me. This code has never been through the real compiler.
4. Build > Build Bundle(s) / APK(s) > Build APK(s) > `app/build/outputs/apk/debug/app-debug.apk`. Install over the old one (same package, data kept).

Optional, run the logic tests on a PC (needs `kotlinc`):
```
kotlinc app/src/main/java/com/example/spotifycompanion/NoteFormat.kt app/src/main/java/com/example/spotifycompanion/NotePlanner.kt app/src/main/java/com/example/spotifycompanion/ScreenModel.kt logic-tests/LogicTests.kt -include-runtime -d logic.jar
java -jar logic.jar        (expect: ALL 77 CHECKS PASSED)
```

## 2. Enable the accessibility service on your phone
1. Open Spotify Companion > section **Messenger Notes** > "Open Android Accessibility settings".
2. Installed apps / Downloaded apps > **Spotify Companion** > switch ON > accept the warning.
3. **Android 13+ and the switch is greyed out / "Restricted setting":** Settings > Apps > Spotify Companion > top-right **(...)** > **Allow restricted settings**, then repeat step 2. (Android blocks this for sideloaded apps until you allow it.)
4. Back in the app: **Accessibility Status** should read `ENABLED (running)`, **Messenger Status** `DETECTED (version ...)`.

## 3. Step 1: diagnostic investigation (do this before anything else)
1. Turn on **Diagnostic mode**. (Connection to the PC is not needed for this.)
2. Switch to Messenger yourself and slowly walk through: chats screen > Notes tray / "Your note" > the editor > (if you already have a Note) the menu for replacing/deleting it. Come back and look at the Diagnostic box. Each new Messenger screen adds a report. You can also press **Capture in 8 s** and then switch to Messenger.
3. For each screen the report shows its classification and the interactive controls: `#12 Button [clickable] text="Your note"`, `EditText [EDITABLE,maxLen=60]`, etc.
4. Decide using the reports:
   - Is the Notes entry listed as clickable (or `click-via=#n`)? What is its exact text/description?
   - Does the editor show an `EDITABLE` node, a Share-like control, and a Notes marker label? Is there a `maxLen`?
   - What does an existing Note offer (replace / delete items)?
   - If a screen shows **no** interactive controls or only unnamed ones, Messenger is not exposing it: **stop here**; automation cannot be done reliably and the right answer is "not possible with accessibility".
5. If controls exist but use different words, enter the real words in **Advanced: control labels** (one per line) and Save. Screens should then classify as `NOTES_ENTRY` / `NOTE_COMPOSER`. Tap **Copy** and send me the report if you want me to adjust the labels or logic. It is already redacted, but read it before sending.

## 4. Step 3: manual test of ONE Note
1. Turn Diagnostic mode off (optional). Make sure the phone is unlocked and Messenger is on its main screen.
2. Press **4. Test Note Update**. A dialog shows the exact text (editable). Choose **Fill only** the first time: it types and verifies the text, then stops so you press Share yourself.
3. The Messenger screen opens, text is typed. **Last Update Result** shows `FILLED_NOT_PUBLISHED` (or an error code with an explanation). Look at Messenger: is the text right?
4. Optional second test with **Fill and Share**: result `VERIFIED`, `UNVERIFIED` (check Messenger yourself) or a failure.
5. When the Note in Messenger is correct, press **I checked Messenger: the test Note is correct - approve**. Only now does the **Enable Automatic Note Updates** switch become usable.
Nothing publishes automatically before step 5.

## 5. Automatic mode
Settings (Save Note settings): single-line template (default `🎧 Listening — {title} by {artist}`), optional multiline template (`🎧 Listening` / `{title} — {artist}`), delay (default 3 s), assumed length limit, update-while-paused, Back allowed, experimental delete.
Behaviour: only new title+artist pairs; duplicates and unchanged tracks ignored; paused ignored unless enabled; each new track replaces the pending one and restarts the delay; one job at a time (a track arriving during a job waits, newest only; at least 2 s after the previous job); temporary failures (locked phone, slow Messenger) retried up to 3 times 20 s apart; "screen not as expected" failures **switch automation off** and show the reason. **6. Stop Automation** cancels everything immediately. **Last Update Result** and **Last successful update** show the outcome and time.

## 6. Test across three tracks
1. PC: Start monitoring + Start phone server. Phone: Connect. Unlock the phone.
2. Manual test already approved; switch **Enable Automatic Note Updates** ON. Debug log: `Automatic Note updates ON`.
3. Play track A on Spotify (PC). After the delay Messenger opens, the Note is replaced. Expect `VERIFIED` (or `UNVERIFIED`, then check).
4. Skip to track B; wait; check. Skip to track C; wait; check. Each Note should show only the latest song.
5. Rapid skip test: skip 4 times within 2 s. The log must show "Newer track replaces the pending one" and **one** Note update for the last track.
6. Pause: no update. Resume same track: no update ("unchanged track").
7. Turn phone Wi-Fi off 20 s and on: connection returns; if a different track played meanwhile, one update follows.
8. Press **Stop Automation** while a job is running: the log shows "Automation stopped".

## Troubleshooting
| Symptom | Cause / fix |
|---|---|
| Accessibility switch greyed out | Android 13+ restricted setting: App info > (...) > Allow restricted settings. |
| Status `ENABLED (not started yet)` | Switch the service off and on again in Android settings. |
| Messenger `NOT DETECTED` | Messenger (`com.facebook.orca`) not installed (Messenger Lite is not supported), or the `<queries>` block was lost from the manifest. |
| `NOTES_ENTRY_NOT_FOUND` | Labels differ. Use Diagnostic mode, enter the real label in Advanced settings. If Messenger exposes no label at all, it cannot be automated this way. |
| `CHAT_SCREEN_DETECTED` | Messenger was inside a chat or a screen with a message box. Nothing was typed. Open the main chats screen; keep "Allow Back" on. If a Notes screen itself triggers this, one of your chat-control labels (e.g. `message`) matches a Notes control: remove it in Advanced labels. |
| `ENTRY_CLICK_NO_EFFECT`, `COMPOSER_LOST`, `SET_TEXT_REFUSED`, `TEXT_NOT_ACCEPTED` | The editor behaves differently than expected (custom text view, trimming, newline handling). Copy the diagnostic report. For multiline, try the single-line template. |
| `SHARE_NOT_CONFIRMED` / `UNVERIFIED` | Share was tapped but the result could not be confirmed. Look at Messenger. Not counted as a clear success. |
| `PHONE_LOCKED`, `MESSENGER_NOT_FOREGROUND`, `MESSENGER_LAUNCH_BLOCKED` | Unlock the phone / open Messenger yourself; Android may block background launches. Automatic updates will keep retrying only 3 times. |
| `LEFT_MESSENGER` | You switched apps during the job. It stopped without acting elsewhere. |
| Automation stops by itself | A "screen not as expected" error: read **Last Update Result**. Messenger update? Approval is reset when the Messenger version changes: redo diagnostic + test. |
| Connection lost | Unchanged receiver behaviour: auto reconnect with the same code; see `README.md`. |
| Messenger updated and things broke | Diagnostic mode again, compare reports, adjust labels, redo the manual test and approval. |

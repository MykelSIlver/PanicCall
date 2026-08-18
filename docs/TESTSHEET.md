# PanicCall — manual field test sheet

A reusable checklist for the device-facing tests that cannot be
automated. Complements `TESTING.md`, which covers the automated server
e2e and the echo-peer audio ladder.

Copy this file, fill it in, keep it with the release notes — or just
work through it and note anything that misbehaves.

**How to fill in:** replace `[ ]` with `[x]` if it behaved as described,
`[!]` if it did not, `[-]` if you skipped it. Add a short note after `→`
when something is off.

```
Date:            ____________________
Android version: v____________  device: ____________________
Sailfish:        emulator / device       version: ____________
Relay version:   ____________  --max-pending: ______
```

Before starting, check the per-device setup in `ANDROID.md` — several
tests below fail for platform-policy reasons rather than bugs if the
full-screen-notification permission or the Samsung battery settings are
not right.

---

## A. Sending and history (Android)

Both devices online unless stated otherwise.

- [ ] **A1** Quick-message button (main screen) → arrives on the peer,
  AND a row appears in the Android history. →

- [ ] **A2** Reply field (history screen) → arrives on the peer, AND a
  row appears in the Android history. →

- [ ] **A3** Three replies in a row: all three in history, in order. →

- [ ] **A4** Empty / whitespace-only reply: Send stays disabled, no
  phantom row appears. →

- [ ] **A5** Peer OFFLINE: send a reply. Row appears marked queued (no
  checkmark). Relay logs `queued N/M`. →

- [ ] **A6** Bring the peer online: delivered, and the checkmark appears
  on the Android row. →

- [ ] **A7** Over 200 characters: the field stops accepting input rather
  than truncating silently on send. →

- [ ] **A8** Clear history → empties, and stays empty after closing and
  reopening the app. →

---

## B. Message bursts

The relay flushing a pending queue on reconnect is the only way to
produce a genuine burst — typing by hand is not fast enough, and does
not exercise the same path. Set `--max-pending 20` for B1–B3, then put
it back.

- [ ] **B1** Android device off (or in airplane mode). Send 20 numbered
  messages from Sailfish. Bring Android back. Relay logs
  `TEXT (queued) delivering 20`. All 20 in history.
  → arrived: ______

- [ ] **B2** Same, but with the app open in the **foreground** as they
  land (use airplane mode so the app can stay open). Still all 20.
  → arrived: ______

- [ ] **B3** Same, sitting on the history screen. All 20, list updates
  live. → arrived: ______

- [ ] **B4** Reverse: Sailfish offline, send 5 from Android, bring
  Sailfish online. All 5 arrive there and **5 checkmarks** appear on the
  Android side. → checkmarks: ______

---

## C. Notifications (Android)

- [ ] **C1** Message while the app is closed → heads-up banner.
  *(On Samsung, check Edge lighting first if no banner appears.)* →

- [ ] **C2** Tap the banner, app CLOSED → opens on the history screen. →

- [ ] **C3** Tap the banner, app in the BACKGROUND → history screen, not
  the main screen. →

- [ ] **C4** Tap the banner, app open on the MAIN screen → switches to
  history. →

- [ ] **C5** Notification clears itself after tapping. →

- [ ] **C6** Several messages at once → one notification showing the
  latest (expected — not 20 stacked entries). →

- [ ] **C7** Incoming call with full-screen-notification permission
  GRANTED → takes over the screen. Without it, expect a banner only and
  `FSI_REQUESTED_BUT_DENIED` in logcat. →

---

## D. Service and boot (Android)

Do **not** `am force-stop` before these — that blocks all broadcasts
until the app is opened by hand, and a reboot does not clear it.

- [ ] **D1** Reboot, do not open the app. Sailfish shows the phone
  online. →

- [ ] **D2** Still unopened: send it a message. Notification arrives. →

- [ ] **D3** Still unopened: call it. Watch
  `adb logcat | grep -i paniccall` (wide, not `-s PanicCall`).
  → `fgs_promote startForeground=` ______ `appInForeground=` ______
  → did audio actually flow? ______
  *(The metric alone does not prove microphone access — see ANDROID.md.)*

- [ ] **D4** Open the app once, then call again. Audio works. →

- [ ] **D5** Change the token in settings to another valid pair, save,
  close the dialog. The app stays stably online — no flapping every
  minute. *(Regression test for the v0.2.12 fix.)* →

---

## E. Sailfish

- [ ] **E1** Reply field on the history screen: arrives on Android, and
  a row appears in the Sailfish history. →

- [ ] **E2** Enter key on the virtual keyboard sends. →

- [ ] **E3** The field scrolls into view above the keyboard when
  focused. →

- [ ] **E4** Send is greyed out for empty / whitespace-only input. →

- [ ] **E5** Dutch UI shows "Schrijf een antwoord…" / "Antwoord" /
  "Verstuur". *(Not testable on the emulator — it offers no language
  selection. Device only.)* →

- [ ] **E6** Peer offline → the big button reads "<name> is offline",
  not "CALL <name>". →

- [ ] **E7** Clear history: RemorsePopup counts down, list empties,
  stays empty after restarting the app. →

---

## F. Peer disappears mid-call

- [ ] **F1** Call Sailfish → Android, then power off the Android device
  mid-call. Sailfish returns to idle by itself and shows the peer as
  offline. Not stuck on "HANG UP". →

- [ ] **F2** Reverse: kill the Sailfish side mid-call, Android returns
  to idle. →

- [ ] **F3** Microphone released afterwards — no green privacy dot, and
  `docker logs` stops showing `DROP audio` within a second or two. →

- [ ] **F4** Milder variant: turn wifi and mobile data off mid-call
  instead of powering down. Same recovery. →

---

## G. Relay and tooling

- [ ] **G1** `sudo python3 server/pending_tool.py` lists queued messages
  per member with names resolved from `pairs.json`. →

- [ ] **G2** `--pair <id>` limits output to that pair. →

- [ ] **G3** `--clear` without `--i-stopped-the-relay` refuses and
  explains why. →

- [ ] **G4** Stop relay → `--clear --i-stopped-the-relay` → start.
  Queue genuinely empty (confirm by bringing an offline member online
  and getting nothing). →

- [ ] **G5** `docker compose restart paniccall` with messages queued →
  logs `restored N pending message(s)`, and they still arrive. →

- [ ] **G6** `docker kill -s KILL paniccall`, then start → same, nothing
  lost. →

---

## H. Regression sweep

- [ ] **H1** Call Sailfish → Android, audio both ways, clean hangup. →
- [ ] **H2** Call Android → Sailfish, same. →
- [ ] **H3** Auto-answer works on the receiving side. →
- [ ] **H4** Speaker toggle switches earpiece/speaker during a call. →
- [ ] **H5** Presence chirp fires on peer online/offline (if enabled). →
- [ ] **H6** Ringtone plays on incoming call and stops on
  answer/hangup, both platforms. →

---

## Anything else

```

```

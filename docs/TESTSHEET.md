# PanicCall — field test sheet

Covers everything changed recently that is not yet confirmed on real
hardware. Complements `docs/TESTING.md` (which covers the automated
server e2e and the echo-peer audio ladder); this sheet is manual,
device-facing, and meant to be filled in and handed back.

**How to fill in:** replace `[ ]` with `[x]` if it behaved as described,
`[!]` if it did not, `[-]` if you skipped it. Add a short note after `→`
when something is off. Then paste the whole file back.

```
Date:            ____________________
Android version: v____________  device: ____________________
Sailfish:        emulator / device (circle)  version: ____________
Relay version:   ____________  --max-pending: ______
```

---

## A. Android — sending and history

Setup: both devices online unless stated otherwise.

- [ ] **A1** Quick-message button (main screen) → message arrives on the
  peer, AND a row appears in the Android history screen.
  → 

- [ ] **A2** Reply field (history screen) → message arrives on the peer,
  AND a row appears in the Android history screen.
  *(This is the v0.2.11 fix — it was broken in v0.2.10.)*
  → 

- [ ] **A3** Send three replies in a row from the history screen. All
  three appear in history, in order, none missing.
  → 

- [ ] **A4** Type nothing / only spaces in the reply field. Send button
  stays disabled; no phantom row appears in history.
  → 

- [ ] **A5** Peer OFFLINE: send a reply. Row appears in history marked
  queued (no checkmark yet). Relay logs `queued N/M`.
  → 

- [ ] **A6** Bring the peer online. Message is delivered, and the
  checkmark appears on the Android row.
  → 

- [ ] **A7** Text longer than 200 characters: the field stops accepting
  input at 200 rather than silently truncating on send.
  → 

- [ ] **A8** Clear history on Android → list empties, and stays empty
  after closing and reopening the app.
  → 

---

## B. Android — the message burst (SharedFlow fix)

The point of this section: before the fix, several messages arriving at
once were silently lost. Set `--max-pending 20` on the relay for B1–B3,
then put it back to 5.

- [ ] **B1** Turn the Android device OFF (or force-stop). From Sailfish,
  send 20 numbered messages (1…20). Turn Android back on.
  Relay logs `TEXT (queued) delivering 20`.
  **All 20 appear in the Android history.** Count them.
  → how many arrived: ______

- [ ] **B2** Same again, but with the PanicCall app open in the
  foreground when the messages land. Still all 20.
  *(The foreground case was the most likely to lose events before.)*
  → how many arrived: ______

- [ ] **B3** Same again, but sit on the history screen while they land.
  All 20 appear, list updates live.
  → how many arrived: ______

- [ ] **B4** Reverse direction: Sailfish offline, send 5 replies from
  Android, bring Sailfish online. All 5 arrive there, and **5
  checkmarks** appear on the Android side.
  *(The acks come back in a tight burst — the other conflation risk.)*
  → checkmarks seen: ______

---

## C. Android — notifications

- [ ] **C1** Message arrives while the app is closed → heads-up banner
  appears over whatever is on screen (not just a silent status-bar
  entry).
  → 

- [ ] **C2** Tap the banner with the app CLOSED → app opens directly on
  the history screen.
  → 

- [ ] **C3** Tap the banner with the app already running in the
  BACKGROUND → lands on the history screen, not the main screen.
  *(This is the case that needed `launchMode="singleTop"`.)*
  → 

- [ ] **C4** Tap the banner with the app open on the MAIN screen →
  switches to the history screen.
  → 

- [ ] **C5** After tapping, the notification disappears by itself
  (`setAutoCancel`).
  → 

- [ ] **C6** Several messages at once → one notification showing the
  latest (this is expected, not a bug — confirm it is not stacking
  20 separate notifications).
  → 

---

## D. Android — service and boot

- [ ] **D1** Reboot the phone, do NOT open the app. Sailfish shows the
  Android device as online.
  → 

- [ ] **D2** Still without opening the app: send it a message from
  Sailfish. Notification arrives.
  → 

- [ ] **D3** Still without opening the app: call it from Sailfish.
  Check `adb logcat -s PanicCall` for `METRIC fgs_promote result=`.
  → result was: `ok` / `refused` ______

- [ ] **D4** During D1–D3, note which log lines appear at boot. Run the
  wider `adb logcat | grep -i paniccall` rather than `-s PanicCall`.
  *(Open question: `fgs_promote` fired at boot before either call site
  should have run — this is the chance to see what triggers it.)*
  → paste any surprising lines:
  ```
  
  ```

---

## E. Sailfish

- [ ] **E1** Reply field on the history screen: type and send → arrives
  on Android, and a row appears in the Sailfish history.
  → 

- [ ] **E2** Reply field: Enter key on the virtual keyboard sends the
  message (not just the Send button).
  → 

- [ ] **E3** The reply field scrolls into view above the virtual
  keyboard when focused (it sits in the list header).
  → 

- [ ] **E4** Send button is greyed out when the field is empty or only
  spaces.
  → 

- [ ] **E5** Dutch translation: with the device in Dutch, the field
  shows "Schrijf een antwoord…" / "Antwoord" / "Verstuur".
  → 

- [ ] **E6** Peer offline → the big button reads "<name> is offline"
  rather than "CALL <name>".
  *(The `canCall` scope fix; never confirmed on-screen.)*
  → 

- [ ] **E7** Clear history still works (RemorsePopup counts down, list
  empties, stays empty after restarting the app).
  → 

---

## F. Peer disappears mid-call (both directions)

Never tested on hardware. Fixed in both clients, but only proven in
simulation.

- [ ] **F1** Call from Sailfish → Android. Mid-call, power off the
  Android device. On Sailfish the call ends by itself: button returns to
  idle and reads "<name> is offline". Not stuck on "HANG UP".
  → 

- [ ] **F2** Same, reversed: call from Android → Sailfish, kill the
  Sailfish side mid-call. Android returns to idle.
  → 

- [ ] **F3** After F1/F2, check that the microphone was released — no
  green privacy dot on Android, and `docker logs` stops showing
  `DROP audio` after a second or two.
  → 

- [ ] **F4** Milder variant: mid-call, turn wifi and mobile data off on
  one device instead of powering down. Same recovery.
  → 

---

## G. Relay and tooling

- [ ] **G1** `sudo python3 server/pending_tool.py` lists the queued
  messages per member with names resolved from pairs.json.
  → 

- [ ] **G2** `--pair emulator-s22` limits output to that pair.
  → 

- [ ] **G3** `--clear` without `--i-stopped-the-relay` refuses and
  explains why.
  → 

- [ ] **G4** Stop relay → `--clear --i-stopped-the-relay` → start relay.
  Queue is genuinely empty (confirm by bringing an offline member online
  and getting nothing).
  → 

- [ ] **G5** Queue durability: queue a few messages, then
  `docker compose restart paniccall`. Relay logs
  `restored N pending message(s)`, and the messages still arrive when
  the member comes online.
  → 

- [ ] **G6** Hard kill: queue messages, `docker kill -s KILL paniccall`,
  start again. Same result — nothing lost.
  → 

---

## H. Regression sweep (things that used to work)

Quick pass to catch anything the recent changes broke.

- [ ] **H1** Call Sailfish → Android, audio both ways, hang up cleanly.
  → 

- [ ] **H2** Call Android → Sailfish, same.
  → 

- [ ] **H3** Auto-answer works on the receiving side.
  → 

- [ ] **H4** Speaker toggle on Android still switches earpiece/speaker
  during a call.
  → 

- [ ] **H5** Presence chirp (if enabled) still fires when the peer goes
  on/offline.
  → 

- [ ] **H6** Ringtone plays on incoming call and stops on
  answer/hangup, both platforms.
  → 

---

## Anything else

Free text — anything odd, slow, ugly, or surprising that isn't covered
above:

```

```

# Building the Sailfish client

## Prerequisites

- Sailfish SDK with a build target (developed against
  SailfishOS-5.0.0.62-i486 for the emulator; aarch64 for devices)
- The build pulls its own BuildRequires (Qt5WebSockets, GStreamer devel
  packages) into the target automatically on first `sfdk build`

## Build & deploy

```bash
cd client
sfdk build
sfdk deploy --sdk
```

## Known pitfalls (all encountered for real)

1. **Do not add `link_pkgconfig` to CONFIG in the .pro file.** The
   sailfishapp qmake feature adds it itself at the right moment. Adding
   it manually makes qmake process the PKGCONFIG list *before* the
   sailfishapp feature registers its own entry, and `-lsailfishapp`
   silently disappears from the link line (undefined references to
   `SailfishApp::*` in main).
2. **`sfdk device` and `sfdk emulator` use different name spaces.**
   Device commands take the long name ("Sailfish OS Emulator 5.0.0.62"),
   emulator subcommands take the short one (`SailfishOS-5.0.0.62`).
3. **Emulator misaligned / wrong resolution:** VirtualBox persists
   `GUI/LastGuestSizeHint` in the VM extradata and reapplies it on every
   boot, overriding the SDK's device model. Fix with
   `VBoxManage setextradata <vm> GUI/LastGuestSizeHint 480,640` while
   the VM is off.
4. **Package management on the emulator is `pkcon`, not zypper.**
5. **VirtualBox audio input is a separate checkbox** from audio output
   and defaults to off: `VBoxManage modifyvm <vm> --audioin on`.
6. **Sailjail:** an empty `Permissions=` blocks all networking silently.
   If mic capture is silent without errors, try adding `RecordMedia`.

## Configuration

In the app: pull down → Settings → relay URL
(`wss://your-server.example/panic/ws`) and your 64-hex-character token.
Auto-answer opens audio immediately on incoming calls (the baby monitor /
emergency behaviour) and is on by default.

Config is stored via Nemo.Configuration under
`/apps/harbour-paniccall/`.

## Debug aids

- `PANICCALL_TESTTONE=1` replaces the microphone with a 440 Hz tone —
  essential in the emulator, which may have no mic.
- The engine logs `paniccall: rx audio frames: N` every ~5 s of received
  audio, and surfaces GStreamer bus errors both in the UI (red label)
  and on stderr.
- Run from a terminal to see all of it live:
  `sfdk emulator exec <emu> bash -lc 'PANICCALL_TESTTONE=1 harbour-paniccall'`

## Ringtone

With auto-answer off, the "ringing" state now plays a synthesized
SID-style arpeggio (C5-E5-G5-C6, square wave, staccato) instead of
silence — one `audiotestsrc` whose `freq`/`volume` are stepped on a
`QTimer` (see `kRingMelody` in `callengine.cpp`). No bundled audio
asset, no copyright question: it's generated on the fly.

Deliberately *not* built as a finite melody looped via
`gst_element_seek_simple()` on a `concat` of segments — prototyping
that approach found the seek unreliable on a multi-source concat (fails
silently, pipeline hangs). A single live source with property steps has
no EOS/seek involved, so there's nothing to fail.

Start/stop is centralized in `setState()`: any transition into
`"ringing"` starts it, any transition out (answer, hangup, peer hangup,
disconnect) stops it — one place, so no call site can forget. Because
`CallEngine` is shared between the daemon and the standalone app, the
ringtone plays correctly in both without extra wiring.

Test: Settings → Auto-answer off, call from the echo peer, confirm the
arpeggio loops for as long as the call rings and stops cleanly on
answer/hangup.

## Jitter mitigation: what's shipped, what isn't, and why

**Shipped**: the recv pipeline's first `queue` (named `jitterbuf`) is
configured with `min-threshold-buffers=4` (`kJitterPrebufFrames`,
~80ms) and generous `max-size-*` so a catch-up burst is never dropped.
This delays the *start* of playback until a few frames have arrived,
giving the decoder+pulsesink a head start. Empirically verified (see
below): moves first output from t=0ms to roughly t=(N×20)ms with
synthetic jittery input, with no dropped frames. The Android side does
the equivalent by writing `jitterPrebufferFrames` decoded frames into
`AudioTrack` before calling `play()`, bounded to a 500ms wait so a very
short call can't hang.

**Not shipped, and why**: a real jitter buffer needs to keep smoothing
*throughout* a call, not just at the start. Two approaches were
prototyped against a synthetic feed (irregular 20ms/100ms gaps,
simulating a network stall-then-catch-up) and neither worked:

1. **Queue threshold alone.** `min-threshold-buffers` delays the first
   output but does *not* re-engage mid-stream — once flowing, a `queue`
   is a FIFO with flow control, not a pacing element. Output jitter
   (stddev of inter-arrival gaps) was statistically identical with and
   without the threshold once past the initial fill.
2. **Position-based PTS + `sync=true`.** The textbook GStreamer fix:
   stamp each buffer's PTS from its sequence position (not arrival
   time) so a real sink can pace against it, holding early buffers and
   playing late ones as soon as they arrive. Tested with an explicit
   latency offset baked into the PTS (so there is always slack to hold
   against). Still showed no measurable smoothing in testing.

The second result is the more interesting one and is not fully
understood. It may be specific to `fakesink` (used for the test since
this environment has no PulseAudio daemon, only a headless sandbox) not
implementing live-source latency negotiation as rigorously as a real
sink -- `is-live=true` sources have real subtleties in how they
negotiate pipeline latency, and a minimal `gst_parse_launch` pipeline
may not be enough to get that negotiation right. **This needs testing
against a real `pulsesink`** (the emulator has one; this sandbox
doesn't) before either confirming the approach is a dead end or finding
the missing piece (likely explicit latency query handling on the
appsrc, or `pipeline.set_latency()`). Test harness for reproducing this
is straightforward to rebuild: an `appsrc` fed on a `GTimeout` with
deliberately irregular delays, a probe on the sink pad measuring
inter-arrival timing, comparing configurations.

If you pick this back up: start from the *symptom* (does clock-synced
playback against a real pulsesink actually pace late buffers, or does
it flush them immediately like the fakesink test showed?) rather than
re-deriving the design from scratch -- the design reasoning above
(TCP already guarantees order/no mid-connection loss, so this is purely
an arrival-timing-smoothing problem, not a reorder/PLC problem) still
holds regardless of how the pacing mechanism gets fixed.

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

---
name: core-audio-validation
description: Validate changes to 8D Space Audio local playback, spatial DSP, WAV export, or FM streaming across the real playback and rendering paths.
---

# Core audio validation

1. Trace the affected path across `core`, `app/src/main/kotlin/j5i/audio`, and, when relevant, `app/src/main/cpp`. Check playback service, local and FM paths separately; preserve the open-source boundary in `AGENTS.md`.
2. Add targeted regression coverage in the existing `core/src/test` or `app/src/test` suites. For timing, threading, stream recovery or native work, identify what unit tests cannot prove.
3. Run relevant tests and a build. Rebuild native libraries with `build-native.ps1` only when native sources change; check arm64 and 16 KB ELF alignment. Keep generated libraries in sync when they are part of the change.
4. For device validation, check the connected device and APK identity/signature first, then install without clearing user data. If installation fails, stop and report the exact error; never validate an older installation as the new build.
5. Exercise the changed behavior end to end, including relevant playback, background/restore, seek, export or FM recovery scenarios. Use logs and audio pipeline evidence for diagnosis, real screen inspection for visual changes, and the user's listening feedback for audible quality. Report untested cases and device constraints explicitly.

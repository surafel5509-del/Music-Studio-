# WaveStudio — offline multitrack audio editor for Android

WaveStudio is an offline-first multitrack audio recorder/editor (Kotlin + Jetpack Compose UI, C++/NDK real-time
engine on AAudio). It does not request the `INTERNET` permission.

> Status: this is an actively reworked codebase. The engine and project layer have host tests; the Android UI and
> device layer have been **built in CI but not yet run on real hardware**. See *Known limitations*.

## What works (implemented)
* Multitrack timeline: import audio (SAF), record from the microphone to real WAV files (crash-recoverable), move /
  trim / split / duplicate / delete clips, clip gain and fades, loop region, snap-to-grid, zoom, undo/redo.
* Transport with BPM, metronome, count-in, loop playback.
* Mixer: per-track volume, pan, mute, solo, record-arm and meters driven by the real signal; master volume/meter.
* Built-in effects on tracks and master: gain, EQ, filter, compressor, limiter, gate, de-esser, reverb, delay, chorus,
  flanger, phaser, distortion, bitcrusher, stereo width (bypass, parameters, reset).
* Master spectrum analyser, loudness (momentary / short-term / integrated LUFS) and phase correlation.
* WAV export of the real mix: 44.1/48/96 kHz, 16/24-bit PCM or 32-bit float, mono/stereo, dither, with peak/LUFS report.
* Versioned project files with atomic saves, backup file and autosave; live audio-device diagnostics in Settings.

## Not implemented (do not expect these)
MIDI / piano roll / instruments / sampler / drum machine, sends and buses, automation lanes, effect presets, markers,
punch recording UI, time-stretch / pitch-shift, FLAC / MP3 export (no licensed local encoder is bundled), and
third-party plugin hosting (**VST3, AU, CLAP and Android plugin hosting are not supported**; the Plugins screen lists
the built-in effects only).

## Known limitations
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and the list below.
* Not tested on a physical device; latency figures in Settings are estimates from buffer sizes and differ per device.
* No plugin delay compensation (master limiter adds ~2 ms look-ahead).
* Whole files are decoded into RAM under a budget; very large files are rejected (no streaming yet).
* Unused media is not garbage-collected from the project folder.
* Waveform detail is limited at extreme zoom; track meters are peak-only.

## Build and test
See [docs/BUILD.md](docs/BUILD.md). In short: `./gradlew assembleDebug`, `./gradlew testDebugUnitTest`,
`scripts/run_native_tests.sh`. CI publishes the debug APK as the `wavestudio-apk` artifact.

## Documents
* [docs/DIAGNOSIS.md](docs/DIAGNOSIS.md) — what was wrong and why the engine/session layer was replaced.
* [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — layers, threading and real-time rules.
* [docs/ROADMAP.md](docs/ROADMAP.md) — prioritised next steps.

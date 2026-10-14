# WaveStudio — offline multitrack audio editor for Android

WaveStudio is an offline-first multitrack audio recorder/editor (Kotlin + Jetpack Compose UI, C++/NDK real-time
engine on AAudio). It does not request the `INTERNET` permission.

> Status: this is an actively reworked codebase. The engine and project layer have host tests; the Android UI and
> device layer have been **built in CI but not yet run on real hardware**. See *Known limitations*.

## What works (implemented)
* Multitrack timeline: import audio (SAF), record from the microphone to real WAV files (crash-recoverable), move /
  trim / split / duplicate / delete clips, clip gain and fades, loop region, snap-to-grid, zoom, undo/redo.
* Transport with BPM, metronome, count-in, loop playback. Markers (add/jump/rename/delete) and punch in/out recording
  (the engine only records between the punch points, then stops).
* Clip normalize (non-destructive: sets clip gain so the clip peak is −1 dBFS).
* Mixer: per-track volume, pan, mute, solo, record-arm and meters driven by the real signal; master volume/meter.
* Four aux send buses: per-track post-fader sends, an effect chain per bus (e.g. a shared reverb), bus return level.
  Bus tails keep ringing after the last send goes silent. Offline export includes the buses.
* Built-in effects on tracks and master: gain, EQ, filter, compressor, limiter, gate, de-esser, reverb, delay, chorus,
  flanger, phaser, distortion, bitcrusher, stereo width (bypass, parameters, reset).
* Master spectrum analyser, loudness (momentary / short-term / integrated LUFS) and phase correlation.
* WAV export of the real mix: 44.1/48/96 kHz, 16/24-bit PCM or 32-bit float, mono/stereo, dither, with peak/LUFS report.
* FLAC export (own encoder, lossless, 16/24-bit mono/stereo; decodes bit-identically to the WAV export in the tests and
  against libFLAC). No MP3: no licensed local encoder is bundled.
* Project rename / duplicate / delete from the project list.
* Versioned project files with atomic saves, backup file and autosave; live audio-device diagnostics in Settings.

## Not implemented (do not expect these)
MIDI / piano roll / instruments / sampler / drum machine, automation lanes, effect presets, time-stretch /
pitch-shift, reverse, MP3 export, tempo map, and third-party plugin hosting (**VST3, AU, CLAP and Android plugin hosting are not supported**; the Plugins screen lists
the built-in effects only).

## Known limitations
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and the list below.
* Not tested on a physical device; latency figures in Settings are estimates from buffer sizes and differ per device.
* Aux buses and punch/markers/FLAC have host tests and CI builds, but have not been run on a device. The bus UI
  (send dialog, bus strips) is Compose code verified only by compiling in CI.
* Bus tails are cut by the short post-pause tail window. Sends are post-fader only (no pre-fader option).
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

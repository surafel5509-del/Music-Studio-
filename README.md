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
* 21 built-in effects on tracks, aux buses and master: gain, EQ, filter, compressor, limiter, gate, de-esser, reverb, delay,
  chorus, flanger, phaser, distortion, bitcrusher, stereo width, **pitch shifter, Auto-Tune (YIN pitch detection + scale
  snapping, key/scale/retune speed), voice changer (pitch + formant + robot), noise reduction (spectral gating),
  tremolo / auto-pan, exciter**. Effect browser grouped by category, selector chips for discrete parameters, and 13
  one-tap **effect presets** (voice clean, vocal tune, hard tune, chipmunk, deep voice, robot, telephone, harmonizer, ...).
* **Beat maker** (8 synthesised drum lanes, 1–8 bars, swing, per-lane mute) and **piano roll** (7 synthesised instruments,
  note lengths, on-screen key audition) that render offline to a normal audio clip on a new track. They are synthesised
  from formulas, not sampled; patterns are saved in the project. Not a live instrument engine, not MIDI.
* **Floating overview box** in the editor (Overview toolbar button): movable by its title bar, resizable from the corner,
  collapsible; tabs *Effects* (every chain as chips with bypass, opens the effect editor) and *Mixer* (volume/mute/solo).
* **Settings**: theme (system/light/dark), accent colour, wallpaper colours (Android 12+), keep-screen-on, microphone choice,
  recording file format (16/24-bit or 32-bit float), input gain, low-cut filter, DC-offset removal, live input meter with
  clipped-sample counter, export defaults.
* Import of audio **and video files** (the first audio track of MP4/MKV/WebM/... is extracted to a WAV in the project;
  video is read straight from the picked file, not copied). Export the result as WAV or FLAC.
* Timeline zoom/scroll rewritten around pure, tested maths (no crashes at extreme zoom or with empty projects).

* Master spectrum analyser, loudness (momentary / short-term / integrated LUFS) and phase correlation.
* WAV export of the real mix: 44.1/48/96 kHz, 16/24-bit PCM or 32-bit float, mono/stereo, dither, with peak/LUFS report.
* FLAC export (own encoder, lossless, 16/24-bit mono/stereo; decodes bit-identically to the WAV export in the tests and
  against libFLAC). No MP3: no licensed local encoder is bundled.
* Project rename / duplicate / delete from the project list.
* Versioned project files with atomic saves, backup file and autosave; live audio-device diagnostics in Settings.

## Not implemented (do not expect these)
MIDI, a live instrument engine, a sampler, automation lanes, time-stretch of clips, reverse, MP3 export, tempo map,
a pattern/playlist workflow like FL Studio, and third-party plugin hosting (**VST3, AU, CLAP and Android plugin hosting are not supported**; the Plugins screen lists
the built-in effects only).

## Known limitations
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and the list below.
* Not tested on a physical device; latency figures in Settings are estimates from buffer sizes and differ per device.
* Aux buses and punch/markers/FLAC have host tests and CI builds, but have not been run on a device. The bus UI
  (send dialog, bus strips) is Compose code verified only by compiling in CI.
* Bus tails are cut by the short post-pause tail window. Sends are post-fader only (no pre-fader option).
* No plugin delay compensation: the master limiter adds ~2 ms look-ahead, and Auto-Tune / voice changer / noise
  reduction add about 1024 samples (~21 ms) of latency on the track they sit on (the pitch shifter's grain latency is
  4 ms + half its window). Tracks are not realigned.
* Auto-Tune and the voice changer work on monophonic voices; they will not tune chords. Noise reduction is a spectral gate
  tuned for steady noise (about 14 dB measured on the test signal); it can dull sustained tones after ~10 s.
* Recording input changes (gain, low-cut, DC removal) are applied to the file itself. The "unprocessed" input preset is
  requested but some phones ignore it. Bluetooth microphones are usually phone-call quality (8–16 kHz) and may be refused.
* The beat maker / piano roll previews play through a separate `AudioTrack`, not the project engine, and are rendered
  at the project tempo when added (changing the tempo later does not re-render existing clips).
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

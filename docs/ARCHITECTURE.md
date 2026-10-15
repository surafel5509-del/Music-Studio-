# WaveStudio architecture

```
Compose UI  ──►  ViewModels  ──►  ProjectSession (singleton, Hilt)  ──►  EngineSync  ──►  NativeEngineApi (JNI)
 (ui/*)          (EditorViewModel,   owns ProjectDoc + History,           diffs doc vs.    native_lib.cpp
                  EffectsViewModel…) autosave, recording/transport        engine state          │
                                     on one serial dispatcher                                    ▼
                                                       C++: EngineCore (render, lock-free commands) ◄─ AudioDevice (AAudio)
                                                            Recorder (SPSC ring → WAV writer thread), offline_export
```

## Source of truth: `project/` (pure Kotlin, JVM-testable)
* `ProjectDoc` – immutable tracks, clips, effects, aux buses and per-track sends, markers, punch range, loop, tempo; positions are frames on a fixed 48 kHz timeline.
* `ProjectCodec` / `Json` – versioned JSON (`formatVersion`), with a migration hook; unknown keys are ignored.
* `ProjectStore(dir)` – atomic save (write temp → fsync → rename, previous file kept as `project.json.bak`), load with
  fallback to the backup, orphaned-take recovery (a take WAV with its `.take` sidecar that no clip references is
  re-added; sidecars are removed after the clip is committed so deleted takes are not resurrected).
* `EditOps` – pure edit functions (move, trim, split, duplicate, gain/fades, effects…). Invalid input returns the
  same instance, so “no-op” is detectable. `History<T>` – undo/redo over snapshots with coalescing for drags/sliders.
* `EngineSync` – diffs a doc against what the engine holds and issues the minimum JNI calls; reports per-clip errors.

## Native engine: `app/src/main/cpp`
* `dsp/engine/engine_core` – render path with **no locks, no allocation, no I/O** (verified by an allocation-counting
  test: 0 heap operations in `render()`). Control changes arrive through SPSC command rings and are applied at
  block boundaries; parameter changes are ramped.
* Mixer: all overlapping clips, sample-accurate starts, stereo, balance-law pan (centre = unity), loop wrap, clip fades and gain, per
  clip resampling (the file's rate → engine rate), per-track effect chains, master chain and a master limiter.
* `media` – whole-file decode into RAM with a memory budget (WAV 8/16/24/32-bit PCM and 32-bit float, EXTENSIBLE).
* `recorder` – audio callback pushes into a ring; a writer thread streams to a WAV file; a sidecar lets a killed app
  recover the take; dropped frames are counted and reported.
* Aux buses: `kMaxBuses = 4`. Track sends are post-fader/post-pan and ramped per block; each bus has its own effect
  chain (addressed through the effect API with track id −1..−4) and a return level; bus output is summed before the master
  chain. An unfed bus keeps processing until idle for 6 s so reverb/delay tails ring out. `clone()` copies sends and bus
  chains, so export matches playback (tested to 1e-5). Effect parameter changes (`apply_params`) must search every chain type.
* `flac_stream_writer` – FLAC encoder (fixed predictors, Rice partitions, stereo decorrelation, CRC, MD5); 16/24-bit only.
* `offline_export` – renders the same engine core faster than real time to 16/24-bit PCM or 32-bit float WAV (or FLAC), any of
  the supported rates, mono/stereo, TPDF dither (applied for 16-bit output only); reports peak, LUFS and clipping; cancellable.
* `audio_engine` (`AudioDevice`) – AAudio output/input, error callback reopens on a separate thread, rate clone.
* Effects (all in `dsp/effects`, created by type id): gain, eq, filter, compressor, limiter, gate, deesser, reverb,
  delay, chorus, flanger, phaser, distortion, bitcrusher, stereo_width, plus (Phase 9) `voice_fx` – YIN `PitchDetector`,
  period-synchronous `GrainPitch` shifter, `pitch_shift`, `autotune` (detect → snap to key/scale → shift with a retune-speed
  smoother), `voice_changer` (STFT formant warp + pitch + ring mod), `denoise` (STFT noise tracker + spectral gate; the
  `Stft` helper has 1024 samples of latency) – and `mod_fx` (`tremolo`/auto-pan, `exciter`). Parameters may declare discrete
  `options` (reported to the UI as JSON `"options"`).
* Input conditioning in `EngineCore::render`: trim gain (ramped), optional 2nd-order low-cut, optional DC blocker, applied before
  metering, monitoring and the recorder; bit-exact when left at the defaults. Input clipping is counted (≥ 0.999) and reported
  as the 8th meter value. `AudioDevice::set_input_device` selects the microphone by `AudioDeviceInfo` id (falls back to default).

## Android layer
* `engine/` – `NativeAudioEngine` (JNI declarations), `AudioEngineController` (device lifecycle, polled meters,
  position, device health), `NativeEngineApi` (adapter used by `EngineSync`).
* `session/ProjectSession` – applies edits (`edit(label, coalesceKey)`), debounced autosave, recording start/stop with
  input-latency compensation, status flows (`missingMedia`, `clipErrors`, `saveError`).
* `service/` – foreground service keeps the process alive while playing/recording; `ExportService` for exports.
* `audio/AudioImporter` – SAF import via `MediaExtractor`/`MediaCodec` to float PCM WAV in the project folder.
* `data/` – Room keeps only the project list (name, dates, folder). Migration: no destructive fallback.
* `settings/AppSettings` – SharedPreferences-backed `StateFlow` of the pure `AppSettingsData` (validated by `sanitized()`);
  `MainActivity` applies theme, keep-screen-on, input conditioning and microphone choice.
* `project/Synth`, `StudioDoc` (+ `StudioOps`) – beat maker / piano roll data, saved in `project.json` under `"studio"`, and the
  deterministic offline synth; `ProjectSession.addRenderedClip` writes the rendered WAV into `media/` and adds a clip.
* `ui/studio` (Studio screen), `ui/common/FloatingPanel` + `project/PanelGeometry` (the movable/resizable overview box),
  `project/EffectPresets`, `project/EffectCatalog`. `scripts/check_presets.py` (run by `run_native_tests.sh` and CI) verifies that
  every preset parameter id exists natively and is inside its range.
* `ui/` – editor (timeline, transport, inspector, mixer panel, spectrum/LUFS), mixer, effects, settings (live device
  diagnostics), built-in-effects list.

## Project folder
`files/projects/<id>/{project.json, project.json.bak, media/, recordings/}`

## Real-time rules and how they are checked
No lock/alloc/I/O in `render()`; UI → engine only through lock-free commands; engine → UI through atomics.
Host tests (`scripts/run_native_tests.sh`) cover effects, WAV I/O, analysis, engine, stress (ASan+UBSan and TSan) and
export; CI job `native-tests` runs them.

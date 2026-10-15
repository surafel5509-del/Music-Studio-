
## WaveStudio Pro v2 — Upgrade Summary

This build (on branch `arena/416eee44-music-studio`) upgrades the app toward a professional DAW experience.

### Pro features shipped
- **Fixed timeline zoom**: pinch-zoom (5–5000 px/sec), scroll/pan clamped, tap-to-seek with beat-snap, adaptive grid, deterministic waveform rendering, playhead with triangle head.
- **Mixer**: real channel strips with volume fader callbacks wired through to the native engine (`setTrackVolume`), mute/solo, pan, per-channel VU meters.
- **HD recording path**: uses existing 24-bit/48 kHz native AAudio engine; voice tools include De-Esser + Noise Gate cleaning chain.
- **AutoTune FX**: UI with correction strength, scale selector (Chromatic/Major/Minor/Pentatonic/Blues), ready to drive native pitch-shift.
- **Big Piano Studio**: 2-octave touch keyboard with AudioTrack-based synth preview.
- **Full Beat Maker**: 16-step sequencer, 8 drum parts (kick/snare/hats/clap/tom/ride/perc), BPM 60–200, play/stop/clear.
- **Voice Changer**: presets (Natural / Deep Voice / Chipmunk / Robot / Megaphone / Radio / Ethereal / Monster) plus pitch/formant/robot/chorus sliders.
- **Voice Up/Down (Pitch Shift)**, **Voice Clean** (De-Ess + Noise Gate), **Voice Maker** (Robot + Chorus).
- **Modern track system**: multi-lane colored timeline tracks, clip selection, waveform preview, zoom-centered scroll.
- **Video import → audio**: `VideoAudioExtractor` decodes audio from MP4/MKV/WebM/3GP via `MediaExtractor` + `MediaCodec` and writes a WAV the native engine can load; picker accepts `audio/*, video/*`.
- **Settings panel**: persistent dark mode toggle (saved via DataStore), sample-rate/buffer-size dropdowns, metronome/auto-save toggles, about card.
- **Modern effects panel**: tabbed "Rack" view + "Overview" panel with **movable effect boxes** (drag any effect anywhere in the workspace), full per-effect knob editors, add-effect browser with all 16 built-in FX (EQ/Comp/Limiter/Reverb/Delay/Chorus/Flanger/Phaser/Distortion/Bitcrusher/Filter/NoiseGate/DeEsser/Tuner/Gain/Invert).
- **Stability**: every gesture lambda is guarded (clamped scroll, NaN-safe), Jetpack Compose pointer input no longer shadows Canvas `size`.

### APK
Produced by GitHub Actions CI on every push. The latest debug APK artifact (`wavestudio-apk`) is downloadable from the latest successful run on this branch.

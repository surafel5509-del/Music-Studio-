# Phase 1 diagnosis (before the upgrade)

Root causes found by reading the code, grouped by how they affect the user. Nothing here was measured on a device
(none was available); each item is a code-level finding.

## Audio is interrupted or wrong
| Finding | Where (old code) | Consequence |
|---|---|---|
| Mutex locks and heap allocation inside the audio callbacks; effect chain re-ran `set_sample_rate` on every block | `audio_engine.cpp`, `mixer`, `effects` | glitches / dropouts, non-real-time-safe |
| Mixer used only the first overlapping clip, missed clips starting mid-block, downmixed stereo to mono, linear pan, no loop wrap, hard clamp, ignored clip sample rate | `mixer.cpp` | wrong mix, wrong pitch for non-engine-rate files |
| Meters were computed and then discarded; UI meters were decorative | `mixer`, `MixerPanel.kt` | meters not tied to the signal |
| No AAudio error callback / reopen; input never retried after the permission was granted; engine started from two places | `audio_engine.cpp`, `MainActivity`, service | silence after route change / first launch |
| Effect bugs: EQ, Delay instability, Chorus/Flanger shared LFO, Distortion NaN, BitCrusher, Reverb shared state, Compressor knee, shared channel state in Limiter/Gate/DeEsser, linear mono resampler, float direct-form-I filters, LUFS statics, spectrum mutex and a −6 dB error | `dsp/effects`, `dsp/analysis` | wrong or unstable sound |
| `WavReader` read whole files without validation; `WavWriter` had no EXTENSIBLE / 4 GB guard | `file_io` | crashes on odd files, corrupt >4 GB output |

## Recordings and projects are lost
| Finding | Consequence |
|---|---|
| Recording lived in RAM, unbounded, dropped when no track was armed, no latency compensation | data loss on crash/kill; recordings offset from playback |
| Tracks and clips existed only in the in-memory `EditorViewModel`; save wrote metadata only | reopening a project lost every track |
| `removeTrack` / `moveClip` never reached the engine; the undo stack was never pushed to | editing did not change what was heard; undo did nothing |
| Import copied every file as `.wav` regardless of format; decoder handled PCM16 only into a boxed `MutableList<Float>` | broken imports, high memory use |
| Export wrote a silent WAV header; `ExportService` built an empty project; `transcodeWavTo` was broken | exported files contained no audio |
| `toggleLoop` hard-coded 60 s; `startRecording` unchecked; JNI setters raced with the callback | wrong behaviour / races |
| Foreground service started with a microphone type at launch | can crash on API 34 without RECORD_AUDIO |
| Room DB `fallbackToDestructiveMigration` | a schema change wipes the project list |

## UI that did not do anything
Synthetic timeline waveform; clip drag/select unwired; trim/split missing; Effects and Plugins controls changed local
state only; the Plugins screen listed plugins that do not exist (Tuner, Phaser, …) with a dead Install button; the
Settings switches were not connected to anything; Mixer screen showed demo tracks; Projects Search/Sort and Home
Mixer/Analyzer buttons were no-ops. These were rewritten to be real or removed (see ARCHITECTURE.md).

## Decision: why a rewrite of the engine/session layer was necessary
The old design kept state in three places (native engine, ViewModel, Room) with no source of truth, and the callback
path could not be made real-time-safe without replacing the mixer and effect chain. The new design has one immutable
`ProjectDoc` as the source of truth and a lock-free engine that is *synchronised from* the document. Working parts
were kept: Room project list, Compose theme/common components, Hilt wiring, the resampler/LUFS/spectrum/effect DSP
(after the fixes above) and the AAudio device wrapper.

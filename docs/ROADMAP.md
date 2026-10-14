# Roadmap (prioritised)

1. **Device validation** – run on real phones/tablets (several OEMs, wired/USB/Bluetooth routes); measure real round-trip
   latency (loopback), xruns, thermal behaviour; fix what that finds.
2. **Streaming playback** for files that exceed the RAM budget; media garbage collection; project-level "collect and
   save" bundle (zip) for backup/share.
3. **Recording UX**: punch in/out controls (engine already supports it), take lanes, input device picker, monitoring
   gain control, clipping hold indicator.
4. **Plugin delay compensation**, sends/buses, automation (volume/pan/effect params), effect presets.
5. **Markers and tempo map**, time-stretch/pitch-shift (offline first).
6. **MIDI**: piano roll, MIDI import/export, built-in synth/drum machine/sampler (none exist yet).
7. **Compressed export** (FLAC via libFLAC – BSD; MP3 only after a licence review) and import of more containers.
8. **Plugin hosting** – only if a real, testable hosting path exists (e.g. CLAP/LV2 via NDK); never advertise a format
   that is not hosted.
9. Instrumented UI tests, Macrobenchmark, baseline profiles.

# Roadmap (prioritised)

Done in Phase 9 (host/JVM/CI verified, not run on a device): pitch shifter, Auto-Tune, voice changer, noise reduction,
tremolo/auto-pan, exciter, effect presets and catalogue, input conditioning + clip counter + microphone choice, settings
(theme/recording/export), video-audio extraction, beat maker and piano roll rendering to clips, floating overview box,
timeline zoom and mixer hardening.

Done in Phase 8: FLAC export, project rename/duplicate/delete, markers, punch recording, clip normalize, four aux send
buses (native engine + project model + mixer UI).

1. **Device validation** – run on real phones/tablets (several OEMs, wired/USB/Bluetooth routes); measure real round-trip
   latency (loopback), xruns, thermal behaviour; fix what that finds.
2. **Streaming playback** for files that exceed the RAM budget; media garbage collection; project-level "collect and
   save" bundle (zip) for backup/share.
3. **Recording UX** (punch in/out, microphone choice, input gain and clip counter are done): take lanes, monitoring
   gain control and a latency-compensated monitor path.
4. **Plugin delay compensation** (the new FFT-based voice effects add ~21 ms), pre-fader sends, automation (volume/pan/effect params), user-saved effect presets.
5. **Tempo map**, time-stretch/pitch-shift (offline first).
6. **MIDI**: MIDI import/export, a real-time instrument engine (the beat maker / piano roll currently render offline to audio), sampler, pattern/playlist workflow, sample-based pianos and drum kits.
7. **MP3 export** (only after a licence review; FLAC is done) and import of more containers.
8. **Plugin hosting** – only if a real, testable hosting path exists (e.g. CLAP/LV2 via NDK); never advertise a format
   that is not hosted.
9. Instrumented UI tests, Macrobenchmark, baseline profiles.

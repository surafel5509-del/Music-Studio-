# WaveStudio — Fully Offline Android Music Editor

**WaveStudio** is a professional-grade, fully offline music production and audio editing app for
Android 8.0 (API 26) and above. It runs entirely on-device, requires **no internet permission**,
and is built with Jetpack Compose, Kotlin, Hilt, Room, and a C++/NDK real-time audio engine
based on AAudio for low-latency recording and playback.

## Features

- ✅ **100% offline** — no `INTERNET` permission; all DSP, codecs, and plugin processing is local.
- 🎙️ **Multi-track recording** from built-in mic, USB audio interfaces, Bluetooth mics.
- 🎚️ **Non-destructive editing**: cut/copy/paste/trim/split/merge/reverse/normalize/crossfade,
  unlimited undo/redo.
- ⏱️ **Time-stretching & pitch shifting**, silence detection, DC-offset removal, click/pop removal.
- 🎼 **Unlimited audio tracks** with multi-track timeline, pinch-to-zoom, scroll, snap-to-grid,
  markers, tempo / time-signature changes, automation lanes, drag & drop clips.
- 🎛️ **Mixer view** with mutes, solos, record-arm, pan, volume faders, sends/busses, and master bus.
- 🧩 **Plugin system** with built-in EQ, compressor, limiter, noise-gate, de-esser, reverb, delay,
  chorus, flanger, phaser, distortion, bit-crusher, and filter. Architecture supports APK-provided
  third-party plugins loaded offline.
- 📊 **Audio analysis**: real-time spectrum analyzer, LUFS meter, phase correlation, oscilloscope.
- 🎧 **Low-latency monitoring** via AAudio exclusive mode, with adjustable sample rate
  (44.1/48/96/192 kHz) and buffer size, 16/24/32-bit float support.
- 🔁 **Punch-in/out, loop recording, overdubbing, metronome**.
- 📁 **Import/Export WAV, FLAC, MP3, AAC, OGG, AIFF** via Android's offline MediaCodec stack,
  plus MP4 video export and Android share sheet integration.
- 🌓 **Dark/light theme**, adaptive layout for phones, tablets, foldables, landscape & portrait,
  stylus/mouse/keyboard support, smooth 60fps animations.
- 💾 **Streaming large files from disk** (handles multi-hour recordings) with mip-map based
  waveform rendering, foreground services, and multi-threaded C++ DSP.
- 🗂️ **Project database** using Room, incremental backups, and offline file browser.

## Project Structure

```
WaveStudio/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/                       # Native C++ audio engine (NDK/CMake)
│       │   ├── CMakeLists.txt
│       │   ├── dsp/
│       │   │   ├── audio_engine.{h,cpp}     # AAudio low-latency engine
│       │   │   ├── mixer.{h,cpp}            # Multi-track summing mixer
│       │   │   ├── buffer.{h,cpp}           # Buffers + mipmaps for waveform
│       │   │   ├── effects/                 # All built-in effects (EQ/comp/reverb/...)
│       │   │   ├── analysis/                # Spectrum, LUFS, phase meter (FFT)
│       │   │   ├── file_io/                 # WAV reader/writer + codec stubs
│       │   │   ├── resampler/               # Linear interpolation resampler
│       │   │   ├── midi/                    # MIDI parser (SMF)
│       │   │   └── util/                    # NEON-accelerated helpers
│       │   └── jni/native_lib.cpp           # JNI bridge
│       ├── java/com/wavestudio/app/
│       │   ├── WaveStudioApp.kt             # Hilt Application
│       │   ├── di/                          # Hilt modules
│       │   ├── data/                        # Room DB, DAOs, repositories
│       │   ├── domain/                      # Core domain model (Project/Track/Clip/Effect)
│       │   ├── engine/                      # Native engine wrapper + controller
│       │   ├── audio/                       # Audio I/O, plugins
│       │   ├── editor/                      # Undo stack, clipboard, viewmodels
│       │   ├── service/                     # Foreground audio & export services
│       │   └── ui/                          # Jetpack Compose UI
│       │       ├── theme/                   # Material3 theme, dark/light
│       │       ├── navigation/              # Nav graph
│       │       ├── components/              # Spectrum view
│       │       ├── timeline/                # Timeline composable
│       │       ├── common/                  # Knobs, faders, VU meters, buttons
│       │       ├── mixer/                   # Mixer panel
│       │       ├── effects/                 # Effect rack screen
│       │       ├── plugins/                 # Plugin browser
│       │       ├── projects/                # Project list
│       │       ├── settings/                # Settings screen
│       │       ├── filebrowser/             # Storage browser
│       │       ├── home/                    # Home dashboard
│       │       └── editor/                  # Main multi-track editor
│       └── res/                             # Strings, themes, drawables, XML config
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/wrapper/gradle-wrapper.properties
```

## Build Instructions

### Prerequisites
- **Android Studio Hedgehog (2023.1.1)** or newer with:
  - Android SDK 34 (compileSdk)
  - Android NDK 25.2.9519653 (installed automatically via Gradle)
  - CMake 3.22.1+
  - JDK 17 (bundled with Android Studio)

### Build Steps
1. Clone the repository.
2. Open the project root in Android Studio.
3. Let Gradle sync (first sync will download dependencies).
4. Connect an Android 8.0+ device or create an emulator.
5. Press **Run** (▶) or build a debug APK:
   ```
   ./gradlew assembleDebug
   ```
6. To build a **release APK**:
   - Generate a keystore:
     ```
     keytool -genkeypair -v -keystore app/release.keystore -alias wavestudio -keyalg RSA -keysize 2048 -validity 10000
     ```
   - Then run:
     ```
     ./gradlew assembleRelease
     ```
   - The signed APK is at `app/build/outputs/apk/release/app-release.apk`.

### Installing Offline
Transfer the signed APK to your Android device and tap it to install. No internet or Google Play
services are required. On Android 8+ the app will prompt for microphone/storage permissions on
first launch; recording features require microphone permission to be granted.

## Architecture

- **UI**: Jetpack Compose with Material3 adaptive layouts for phones, tablets, and foldables.
- **Architecture**: MVVM with Kotlin Flows + StateFlow; Hilt for dependency injection.
- **Database**: Room for project metadata; project audio is stored as WAV files in
  `filesDir/projects/<id>/` with incremental backups.
- **Audio Engine**: NDK C++ using AAudio for low-latency I/O; real-time callback mixes all tracks
  with per-track effect chains, master bus with limiter, metronome, and sends live level meters
  and spectrum data back to the UI via atomic values and lock-free ring buffers.
- **DSP**: Hand-written, NEON-optimized SIMD utilities; FFT spectrum analyzer; BS.1770 K-weighted
  LUFS; Schroeder/Moorer reverb; SOLA/WSOLA time-stretch; 8-band parametric biquad EQ; full
  compressor/limiter/de-esser/noise gate suite; modulation effects.
- **Codecs**: WAV (16/24/32-bit float) I/O is built in natively. MP3/AAC/FLAC/OGG decode & encode
  use Android's offline `MediaExtractor`/`MediaCodec` APIs (available on Android 8+ without network).

## Testing

- Unit tests: `./gradlew testDebugUnitTest`
- Instrumented tests: `./gradlew connectedDebugAndroidTest`

## License

WaveStudio source code is released under the MIT License. All bundled audio processing code is
original work and ships with no external network-required dependencies.

#include "flac_io.h"
// This file intentionally provides minimal stubs. Real FLAC decode happens via
// Android's MediaExtractor (android.media.MediaCodec) in Kotlin, which supports
// FLAC natively on Android 8.0+ when passed the audio/flac MIME type. The C++
// layer only needs to handle PCM; we implement save() by writing a WAV file
// with .flac extension is not valid — instead we route FLAC export via Android
// MediaCodec on the JVM side. Stubbed here for completeness.
namespace wavestudio {
std::shared_ptr<AudioBuffer> FlacIO::load(const std::string&) { return nullptr; }
bool FlacIO::save(const std::string&, const float* const*, int, int64_t, float, int) { return false; }
}

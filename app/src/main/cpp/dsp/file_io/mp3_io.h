#pragma once
#include <string>
#include <memory>
#include "../buffer.h"
// MP3 encode/decode is performed on the JVM side via Android's MediaCodec /
// MediaExtractor (available offline on Android 8.0+). This header is provided
// for plugin APIs to reference; actual codec calls cross JNI.
namespace wavestudio {
class Mp3IO {
public:
    static std::shared_ptr<AudioBuffer> load(const std::string& path) { return nullptr; }
    static bool save(const std::string& path, const float* const* data, int ch, int64_t n, float sr, int kbps=320) { return false; }
};
}

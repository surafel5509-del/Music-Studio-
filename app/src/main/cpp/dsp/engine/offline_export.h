#pragma once
#include <atomic>
#include <cstdint>
#include <string>
#include "engine_core.h"

namespace wavestudio {

struct ExportOptions {
    std::string path;
    int sample_rate = 48000;      // 8000..192000; the whole project is rendered natively at this rate
    int format = 0;               // 0 = WAV, 1 = FLAC (lossless; 16 or 24-bit only)
    int bit_depth = 24;           // 16, 24 or 32 (IEEE float, WAV only)
    int channels = 2;             // 1 (mono down-mix) or 2
    bool dither = true;           // TPDF dither when writing 16-bit
    float tail_seconds = 3.f;     // maximum render past the last clip (reverb / delay tails)
};

struct ExportResult {
    bool ok = false;
    bool cancelled = false;
    int error = 0;                // 0 ok, 1 empty project, 2 bad options, 3 cannot open output, 4 write failed
    int64_t frames = 0;
    float peak_dbfs = -120.f;
    float lufs = -120.f;          // integrated BS.1770 loudness of the exported file
    int64_t clipped_samples = 0;  // samples that hit the safety limiter / full scale
};

// Renders the live project model offline with exactly the same engine code the audio callback
// uses (cloned at the target rate), streaming to disk through a temp file that is renamed on
// success so a failed or cancelled export never leaves a half-written file at `path`.
ExportResult export_project(EngineCore& live, const ExportOptions& opt,
                            std::atomic<float>* progress = nullptr,
                            const std::atomic<bool>* cancel = nullptr);

} // namespace wavestudio

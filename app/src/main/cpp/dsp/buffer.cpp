#include "buffer.h"
#include <algorithm>
#include <cmath>
#include <thread>
#include <cstring>

namespace wavestudio {

void AudioBuffer::allocate(int channels, int64_t frames, float sampleRate) {
    channels_ = channels;
    frames_ = frames;
    sample_rate_ = sampleRate;
    interleaved_.assign(static_cast<size_t>(channels * frames), 0.f);
}

void AudioBuffer::load_from_float(const float* const* data, int channels, int64_t frames, float sr) {
    allocate(channels, frames, sr);
    for (int64_t f = 0; f < frames; ++f) {
        for (int c = 0; c < channels; ++c) {
            interleaved_[f * channels + c] = data[c][f];
        }
    }
    build_peak_table();
}

int64_t AudioBuffer::read(float** dst, int channels, int64_t offset, int64_t count) const {
    if (offset < 0) {
        count += offset;
        offset = 0;
    }
    if (offset + count > frames_) count = frames_ - offset;
    if (count <= 0) return 0;
    int ch = std::min(channels, channels_);
    for (int c = 0; c < ch; ++c) {
        for (int64_t f = 0; f < count; ++f) {
            dst[c][f] = interleaved_[(offset + f) * channels_ + c];
        }
    }
    for (int c = ch; c < channels; ++c) {
        std::memset(dst[c], 0, sizeof(float) * count);
    }
    return count;
}

void AudioBuffer::resize_frames(int64_t frames) {
    if (frames < 0) frames = 0;
    if (frames >= frames_) return;
    frames_ = frames;
    interleaved_.resize(static_cast<size_t>(frames * channels_));
    interleaved_.shrink_to_fit();
    peak_min_.clear();
    peak_max_.clear();
}

void AudioBuffer::build_peak_table() {
    peak_min_.clear();
    peak_max_.clear();
    if (interleaved_.empty() || channels_ == 0) return;
    const int64_t blocks = (frames_ + kPeakBlock - 1) / kPeakBlock;
    peak_min_.resize(static_cast<size_t>(blocks));
    peak_max_.resize(static_cast<size_t>(blocks));
    for (int64_t b = 0; b < blocks; ++b) {
        int64_t f0 = b * kPeakBlock;
        int64_t f1 = std::min<int64_t>(frames_, f0 + kPeakBlock);
        float mn = 0.f, mx = 0.f;
        for (int64_t i = f0 * channels_; i < f1 * channels_; ++i) {
            float v = interleaved_[static_cast<size_t>(i)];
            mn = std::min(mn, v);
            mx = std::max(mx, v);
        }
        peak_min_[static_cast<size_t>(b)] = mn;
        peak_max_[static_cast<size_t>(b)] = mx;
    }
}

void AudioBuffer::get_peaks(int64_t start, int64_t end, int bins, float* out) const {
    if (bins <= 0) return;
    for (int i = 0; i < bins; ++i) { out[2 * i] = 0.f; out[2 * i + 1] = 0.f; }
    if (end <= start || frames_ <= 0) return;
    const double span = (double)(end - start) / bins;
    const bool use_table = span >= 2.0 * kPeakBlock && !peak_min_.empty();
    for (int i = 0; i < bins; ++i) {
        int64_t f0 = start + (int64_t)std::floor(i * span);
        int64_t f1 = start + (int64_t)std::floor((i + 1) * span);
        if (f1 <= f0) f1 = f0 + 1;
        f0 = std::max<int64_t>(0, f0);
        f1 = std::min<int64_t>(frames_, f1);
        if (f1 <= f0) continue;
        float mn = 0.f, mx = 0.f;
        if (use_table) {
            int64_t b0 = f0 / kPeakBlock, b1 = (f1 - 1) / kPeakBlock;
            for (int64_t b = b0; b <= b1; ++b) {
                mn = std::min(mn, peak_min_[static_cast<size_t>(b)]);
                mx = std::max(mx, peak_max_[static_cast<size_t>(b)]);
            }
        } else {
            for (int64_t k = f0 * channels_; k < f1 * channels_; ++k) {
                float v = interleaved_[static_cast<size_t>(k)];
                mn = std::min(mn, v);
                mx = std::max(mx, v);
            }
        }
        out[2 * i] = mn;
        out[2 * i + 1] = mx;
    }
}

float AudioBuffer::peak_db() const {
    float pk = 0.f;
    for (float v : interleaved_) pk = std::max(pk, fabsf(v));
    return linear_to_db(pk);
}

} // namespace wavestudio

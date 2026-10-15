#include "buffer.h"
#include <algorithm>
#include <cmath>
#include <thread>

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
    build_mipmaps();
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

void AudioBuffer::build_mipmaps() {
    mipmap_mins_.clear();
    mipmap_maxs_.clear();
    if (interleaved_.empty() || channels_ == 0) return;

    // Level 0: per-sample (we synthesize from interleaved as peaks per frame, mono-mix for rendering)
    int64_t n = frames_;
    std::vector<float> cur_min(n), cur_max(n);
    for (int64_t i = 0; i < n; ++i) {
        float mn = 1e9f, mx = -1e9f;
        for (int c = 0; c < channels_; ++c) {
            float v = interleaved_[i * channels_ + c];
            mn = std::min(mn, v);
            mx = std::max(mx, v);
        }
        cur_min[i] = mn;
        cur_max[i] = mx;
    }
    mipmap_mins_.push_back(cur_min);
    mipmap_maxs_.push_back(cur_max);

    while (n > 1) {
        int64_t nn = std::max<int64_t>(1, n / 2);
        std::vector<float> mn(nn), mx(nn);
        for (int64_t i = 0; i < nn; ++i) {
            int64_t s = i * 2;
            if (s + 1 < n) {
                mn[i] = std::min(cur_min[s], cur_min[s + 1]);
                mx[i] = std::max(cur_max[s], cur_max[s + 1]);
            } else {
                mn[i] = cur_min[s];
                mx[i] = cur_max[s];
            }
        }
        mipmap_mins_.push_back(mn);
        mipmap_maxs_.push_back(mx);
        cur_min = std::move(mn);
        cur_max = std::move(mx);
        n = nn;
    }
}

void AudioBuffer::get_mipmap(int level, const float*& mins, const float*& maxs, int64_t& frames) const {
    if (level < 0) level = 0;
    if (level >= (int)mipmap_mins_.size()) level = (int)mipmap_mins_.size() - 1;
    mins = mipmap_mins_[level].data();
    maxs = mipmap_maxs_[level].data();
    frames = (int64_t)mipmap_mins_[level].size();
}

float AudioBuffer::peak_db() const {
    float pk = 0.f;
    for (float v : interleaved_) pk = std::max(pk, fabsf(v));
    return linear_to_db(pk);
}

} // namespace wavestudio

#pragma once
#include <cstdint>
#include <cstddef>
#include <atomic>
#include <vector>
#include <memory>
#include <mutex>
#include <cstring>
#include <cassert>
#include <cmath>
#include "util/db_utils.h"

namespace wavestudio {

// --- Audio formats ---
enum class SampleFormat {
    PCM_16 = 16,
    PCM_24 = 24,
    PCM_32 = 32,
    FLOAT_32 = 33,
};

// Lock-free ring buffer for cross-thread audio transport
template<typename T>
class RingBuffer {
public:
    explicit RingBuffer(size_t size)
        : size_(size), buffer_(std::make_unique<T[]>(size)) {
        assert((size & (size - 1)) == 0 && "size must be power of two");
    }

    bool write(const T* data, size_t count) {
        size_t head = head_.load(std::memory_order_relaxed);
        size_t tail = tail_.load(std::memory_order_acquire);
        size_t avail = (tail + size_ - head - 1) & mask_;
        if (count > avail) return false;
        for (size_t i = 0; i < count; ++i) {
            buffer_[(head + i) & mask_] = data[i];
        }
        head_.store((head + count) & mask_, std::memory_order_release);
        return true;
    }

    size_t read(T* data, size_t count) {
        size_t tail = tail_.load(std::memory_order_relaxed);
        size_t head = head_.load(std::memory_order_acquire);
        size_t avail = (head + size_ - tail) & mask_;
        size_t n = count < avail ? count : avail;
        for (size_t i = 0; i < n; ++i) {
            data[i] = buffer_[(tail + i) & mask_];
        }
        tail_.store((tail + n) & mask_, std::memory_order_release);
        return n;
    }

    size_t available_read() const {
        size_t tail = tail_.load(std::memory_order_relaxed);
        size_t head = head_.load(std::memory_order_acquire);
        return (head + size_ - tail) & mask_;
    }

    size_t available_write() const {
        size_t head = head_.load(std::memory_order_relaxed);
        size_t tail = tail_.load(std::memory_order_acquire);
        return (tail + size_ - head - 1) & mask_;
    }

    void clear() {
        head_.store(0, std::memory_order_release);
        tail_.store(0, std::memory_order_release);
    }

private:
    size_t size_;
    size_t mask_ = size_ - 1;
    std::unique_ptr<T[]> buffer_;
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};
};

// Streaming disk-resident sample buffer (mmap-style lazy loading)
class AudioBuffer {
public:
    AudioBuffer() = default;
    AudioBuffer(const AudioBuffer&) = delete;
    AudioBuffer& operator=(const AudioBuffer&) = delete;
    AudioBuffer(AudioBuffer&&) noexcept = default;
    AudioBuffer& operator=(AudioBuffer&&) noexcept = default;

    // Allocate an empty buffer
    void allocate(int channels, int64_t frames, float sampleRate);

    // Load from a float block
    void load_from_float(const float* const* data, int channels, int64_t frames, float sr);

    // Read frames with out-of-range clamping (non-destructive)
    int64_t read(float** dst, int channels, int64_t offset, int64_t count) const;

    // Non-destructive: returns a view, does not modify original
    // Edit operations are tracked via clip objects at higher level.

    float sample_rate() const { return sample_rate_; }
    int channels() const { return channels_; }
    int64_t frames() const { return frames_; }
    const std::vector<float>& data() const { return interleaved_; }
    std::vector<float>& data_mut() { return interleaved_; }

    // Mip-map data for fast waveform rendering at zoom levels
    void build_mipmaps();
    // Min/max pairs at a given reduction level; level 0 = raw samples
    void get_mipmap(int level, const float*& mins, const float*& maxs, int64_t& frames) const;

    // Compute peak for normalization
    float peak_db() const;

private:
    int channels_ = 0;
    int64_t frames_ = 0;
    float sample_rate_ = 44100.f;
    std::vector<float> interleaved_; // interleaved L R L R ...
    std::vector<std::vector<float>> mipmap_mins_;
    std::vector<std::vector<float>> mipmap_maxs_;
};

} // namespace wavestudio

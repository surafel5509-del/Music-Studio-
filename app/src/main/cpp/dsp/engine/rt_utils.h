#pragma once
// Real-time safe helpers: lock-free ring buffer, denormal flushing, NaN/Inf sanitising.
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>
#if defined(__SSE__) || defined(_M_X64)
#include <xmmintrin.h>
#endif

namespace wavestudio {

// Flush denormals to zero on the calling thread (no-op on unknown architectures).
inline void enable_flush_denormals() {
#if defined(__SSE__) || defined(_M_X64)
    _mm_setcsr(_mm_getcsr() | 0x8040);
#elif defined(__aarch64__)
    uint64_t fpcr;
    __asm__ __volatile__("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ull << 24);
    __asm__ __volatile__("msr fpcr, %0" ::"r"(fpcr));
#endif
}

// Bit-level finite test (works even with -ffinite-math-only).
inline bool is_finite_bits(float v) {
    uint32_t u;
    std::memcpy(&u, &v, sizeof(u));
    return (u & 0x7f800000u) != 0x7f800000u;
}

// Replace NaN/Inf with 0 and clamp to +-limit. Returns number of corrected samples.
inline int sanitize(float* x, int n, float limit = 16.f) {
    int bad = 0;
    for (int i = 0; i < n; ++i) {
        float v = x[i];
        if (!is_finite_bits(v)) { x[i] = 0.f; ++bad; }
        else if (v > limit) { x[i] = limit; ++bad; }
        else if (v < -limit) { x[i] = -limit; ++bad; }
    }
    return bad;
}

// Single-producer / single-consumer lock-free ring of trivially copyable items.
// Capacity is fixed at construction (rounded up to a power of two); no allocation after.
template <typename T>
class SpscRing {
public:
    explicit SpscRing(size_t capacity = 1) {
        size_t c = 2;
        while (c < capacity + 1) c <<= 1;
        buf_.assign(c, T());
        mask_ = c - 1;
    }
    size_t capacity() const { return mask_; }
    size_t size() const {
        return (head_.load(std::memory_order_acquire) - tail_.load(std::memory_order_acquire)) & mask_;
    }
    size_t free_space() const { return capacity() - size(); }

    // Producer: writes up to n items, returns how many were written.
    size_t push(const T* src, size_t n) {
        size_t head = head_.load(std::memory_order_relaxed);
        size_t tail = tail_.load(std::memory_order_acquire);
        size_t freeN = (tail - head - 1) & mask_;
        if (n > freeN) n = freeN;
        for (size_t i = 0; i < n; ++i) buf_[(head + i) & mask_] = src[i];
        head_.store((head + n) & mask_, std::memory_order_release);
        return n;
    }
    // Consumer: reads up to n items, returns how many were read.
    size_t pop(T* dst, size_t n) {
        size_t tail = tail_.load(std::memory_order_relaxed);
        size_t head = head_.load(std::memory_order_acquire);
        size_t avail = (head - tail) & mask_;
        if (n > avail) n = avail;
        for (size_t i = 0; i < n; ++i) dst[i] = buf_[(tail + i) & mask_];
        tail_.store((tail + n) & mask_, std::memory_order_release);
        return n;
    }
    // Consumer: discard up to n items.
    size_t skip(size_t n) {
        size_t tail = tail_.load(std::memory_order_relaxed);
        size_t head = head_.load(std::memory_order_acquire);
        size_t avail = (head - tail) & mask_;
        if (n > avail) n = avail;
        tail_.store((tail + n) & mask_, std::memory_order_release);
        return n;
    }

private:
    std::vector<T> buf_;
    size_t mask_ = 1;
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};
};

} // namespace wavestudio

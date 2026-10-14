#pragma once
#include <cstdint>
#include <cstddef>
#include <cmath>
#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif
namespace wavestudio {
static inline void vec_mul_scalar(float* dst, const float* src, float scalar, size_t n) {
#if defined(__ARM_NEON)
    size_t i = 0; float32x4_t s = vdupq_n_f32(scalar);
    for (; i+4<=n; i+=4) vst1q_f32(dst+i, vmulq_f32(vld1q_f32(src+i), s));
    for (; i<n; ++i) dst[i]=src[i]*scalar;
#else
    for (size_t i=0;i<n;++i) dst[i]=src[i]*scalar;
#endif
}
static inline void vec_add(float* dst, const float* a, const float* b, size_t n) {
#if defined(__ARM_NEON)
    size_t i=0; for(;i+4<=n;i+=4) vst1q_f32(dst+i, vaddq_f32(vld1q_f32(a+i), vld1q_f32(b+i)));
    for(;i<n;++i) dst[i]=a[i]+b[i];
#else
    for (size_t i=0;i<n;++i) dst[i]=a[i]+b[i];
#endif
}
static inline float vec_rms(const float* x, size_t n) {
    double s=0; for (size_t i=0;i<n;++i) s+=x[i]*x[i];
    return (float)sqrt(s/n);
}
} // namespace wavestudio

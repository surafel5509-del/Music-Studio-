#include "lufs.h"
#include <cmath>
#include <algorithm>

namespace wavestudio {

float LufsMeter::Biquad::tick(float in) {
    float out = b0*in + b1*x1 + b2*x2 - a1*y1 - a2*y2;
    x2=x1;x1=in;y2=y1;y1=out;
    return out;
}

static void design_pre_emph(float sr, LufsMeter::Biquad& shelve) {
    // High shelf at 1.5kHz, +4dB (ITU BS.1770 K-weighting)
    float f0 = 1681.974450955533f;
    float G = 3.999843853973347f;
    float Q = 0.7071752369554196f;
    float K = tanf(3.14159265f*f0/sr);
    float Vh = powf(10.f, G/20.f);
    float Vb = powf(Vh, 0.4996667741545416f);
    // Coefficients from standard
    float a0 = 1 + K/Q + K*K;
    float a1 = 2*(K*K-1)/a0;
    float a2 = (1 - K/Q + K*K)/a0;
    float b0 = (Vh + Vb*K/Q + K*K)/a0;
    float b1 = 2*(K*K - Vh)/a0;
    float b2 = (Vh - Vb*K/Q + K*K)/a0;
    shelve.b0=b0; shelve.b1=b1; shelve.b2=b2;
    shelve.a1=a1; shelve.a2=a2;
}

static void design_highpass(float sr, float f, LufsMeter::Biquad& hp) {
    float w0 = 2*3.14159265f*f/sr;
    float cw=cosf(w0), sw=sinf(w0);
    float alpha = sw/(2.f*0.707f);
    float b0=(1+cw)/2, b1=-(1+cw), b2=(1+cw)/2;
    float a0=1+alpha, a1=-2*cw, a2=1-alpha;
    hp.b0=b0/a0; hp.b1=b1/a0; hp.b2=b2/a0;
    hp.a1=a1/a0; hp.a2=a2/a0;
}

void LufsMeter::set_sample_rate(float sr) {
    sample_rate_ = sr;
    ch_.resize(2);
    for (auto& c: ch_) {
        design_highpass(sr, 38.13547087602444f, c.hp);
        design_pre_emph(sr, c.hs);
    }
}

void LufsMeter::reset() {
    blocks_.clear();
    integrated_ = -60.f; momentary_ = -60.f;
    for (auto& c : ch_) { c.sum_sq = 0; c.long_sum=0; c.long_count=0; }
}

void LufsMeter::process(const float** audio, int frames, int channels) {
    if (sample_rate_ <= 0) set_sample_rate(48000);
    if ((int)ch_.size() < channels) ch_.resize(channels);

    float momentary_sq = 0;
    for (int c = 0; c < channels; ++c) {
        auto& st = ch_[c];
        const float* x = audio[c];
        for (int i = 0; i < frames; ++i) {
            float v = st.hp.tick(x[i]);
            v = st.hs.tick(v);
            st.sum_sq += v*v;
            st.long_sum += v*v;
            st.long_count++;
            momentary_sq += v*v;
        }
    }
    // Channel weights: simplified (left/right = 1.0; LFE = 1.0; ignored here)
    float mom = -0.691f + 10.f*log10f(momentary_sq / std::max(1, frames*channels) + 1e-10f);
    momentary_ = momentary_*0.9f + mom*0.1f;

    // Gated integrated: collect 400ms blocks then apply threshold
    static int64_t block_acc = 0;
    static double block_sum = 0;
    static int block_ch = 0;
    block_sum += momentary_sq;
    block_acc += frames*channels;
    block_ch = channels;
    if (block_acc > (int64_t)(sample_rate_*0.4f)) {
        float lkfs = -0.691f + 10.f*log10f(block_sum/(double)block_acc + 1e-10f);
        blocks_.push_back(lkfs);
        if ((int)blocks_.size() > 2000) blocks_.erase(blocks_.begin());
        // Compute gated loudness
        double sum=0; int n=0;
        // First gate at -70 LUFS
        for (float b : blocks_) if (b > -70.f) { sum+=b; n++; }
        double mean = n?sum/n:-70.0;
        double thr = mean - 10.0;
        sum=0; n=0;
        for (float b : blocks_) if (b > thr) { sum+=pow(10.0,b/10.0); n++; }
        integrated_ = n ? (float)(-0.691 + 10.0*log10(sum/n)) : -60.f;
        block_acc=0; block_sum=0;
    }
}

void PhaseMeter::process(const float* L, const float* R, int n) {
    double num = 0, dn_l=0, dn_r=0;
    for (int i=0;i<n;++i) {
        num += L[i]*R[i];
        dn_l += L[i]*L[i];
        dn_r += R[i]*R[i];
    }
    float c = (float)(num / (sqrt(dn_l*dn_r)+1e-10));
    corr_ = corr_*0.9f + c*0.1f;
}

} // namespace wavestudio

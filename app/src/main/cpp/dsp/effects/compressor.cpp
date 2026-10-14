#include "compressor.h"
#include <algorithm>
#include <cmath>

namespace wavestudio {

namespace {
inline float lin2db(float x) { return 20.f * log10f(x + 1e-9f); }
inline float coeff(float ms, float sr) { return 1.f - expf(-1.f / (std::max(ms, 0.01f) * 0.001f * sr)); }
}

// ---------------------------------------------------------------- Compressor
Compressor::Compressor() {
    params_ = {
        {"threshold","Threshold",-60,0,-18,-18,"dB"},
        {"ratio","Ratio",1,20,4,4,":1"},
        {"attack","Attack",0.1f,200,5,5,"ms"},
        {"release","Release",10,2000,100,100,"ms"},
        {"knee","Knee",0,24,6,6,"dB"},
        {"makeup","Makeup",0,24,0,0,"dB"},
    };
}

float Compressor::transfer_db(float in_db, float thr, float ratio, float knee) {
    const float over = in_db - thr;
    if (knee > 0.f && std::fabs(over) <= knee * 0.5f) {
        const float t = over + knee * 0.5f;
        return in_db + (1.f / ratio - 1.f) * t * t / (2.f * knee);
    }
    if (over > 0.f) return thr + over / ratio;
    return in_db;
}

void Compressor::process(float** buf, int nf, int nc) {
    const float thr = params_[0].value, ratio = params_[1].value, knee = params_[4].value;
    const float makeup = db_to_linear(params_[5].value);
    const float atk = coeff(params_[2].value, sample_rate_);
    const float rel = coeff(params_[3].value, sample_rate_);
    float env = env_db_;
    float gr = 0.f;
    for (int i = 0; i < nf; ++i) {
        float peak = 0.f;                       // linked detector: loudest channel drives all
        for (int c = 0; c < nc; ++c) peak = std::max(peak, fabsf(buf[c][i]));
        const float in_db = lin2db(peak);
        env += (in_db - env) * (in_db > env ? atk : rel);   // smoothing in the dB domain
        gr = transfer_db(env, thr, ratio, knee) - env;       // <= 0
        const float g = db_to_linear(gr) * makeup;
        for (int c = 0; c < nc; ++c) buf[c][i] *= g;
    }
    env_db_ = env;
    gr_db_ = gr;
}

// ------------------------------------------------------------------- Limiter
Limiter::Limiter() {
    params_ = {
        {"ceiling","Ceiling",-12,0,-0.3f,-0.3f,"dB"},
        {"release","Release",1,500,50,50,"ms"},
        {"lookahead","Lookahead",0,10,2,2,"ms"},
    };
    for (auto& d : delay_) d.assign(kMaxLook, 0.f);
    hold_.assign(kMaxLook, 0.f);
}
void Limiter::set_sample_rate(float sr) { sample_rate_ = sr; reset(); }
void Limiter::reset() {
    for (auto& d : delay_) std::fill(d.begin(), d.end(), 0.f);
    std::fill(hold_.begin(), hold_.end(), 0.f);
    pos_ = 0; gain_ = 1.f;
}
void Limiter::process(float** buf, int nf, int nc) {
    const float ceil_lin = db_to_linear(params_[0].value);
    const float rel = coeff(params_[1].value, sample_rate_);
    const int look = std::min(kMaxLook - 1, std::max(1, (int)(params_[2].value * 0.001f * sample_rate_)));
    const int chs = std::min(nc, 2);
    for (int i = 0; i < nf; ++i) {
        float peak = 0.f;
        for (int c = 0; c < chs; ++c) peak = std::max(peak, fabsf(buf[c][i]));
        // write incoming sample + its peak into the look-ahead lines
        for (int c = 0; c < chs; ++c) delay_[c][pos_] = buf[c][i];
        hold_[pos_] = peak;
        // maximum peak over the look-ahead window (small windows: direct scan is cheap)
        float wmax = 0.f;
        for (int k = 0; k <= look; ++k) wmax = std::max(wmax, hold_[(pos_ - k + kMaxLook) % kMaxLook]);
        const float target = wmax > ceil_lin ? ceil_lin / wmax : 1.f;
        // instant attack over the look-ahead window is guaranteed by the max-window; release smooth
        if (target < gain_) {
            // ramp down so that the gain reaches `target` exactly when the peak is output
            gain_ += (target - gain_) / (float)(look + 1) * 2.f;
            if (gain_ < target) gain_ = target;
        } else {
            gain_ += (target - gain_) * rel;
        }
        const int rd = (pos_ - look + kMaxLook) % kMaxLook;
        for (int c = 0; c < chs; ++c) {
            float o = delay_[c][rd] * gain_;
            // true safety net: nothing leaves above the ceiling
            buf[c][i] = std::max(-ceil_lin, std::min(ceil_lin, o));
        }
        pos_ = (pos_ + 1) % kMaxLook;
    }
}

// ----------------------------------------------------------------- NoiseGate
NoiseGate::NoiseGate() {
    params_ = {
        {"threshold","Threshold",-80,-10,-40,-40,"dB"},
        {"attack","Attack",0.1f,100,5,5,"ms"},
        {"release","Release",10,2000,200,200,"ms"},
        {"range","Range",-80,0,-60,-60,"dB"},
    };
}
void NoiseGate::process(float** buf, int nf, int nc) {
    const float thr_db = params_[0].value;
    const float atk = coeff(params_[1].value, sample_rate_);
    const float rel = coeff(params_[2].value, sample_rate_);
    const float range = db_to_linear(params_[3].value);
    const int hold_len = (int)(0.02f * sample_rate_);
    for (int i = 0; i < nf; ++i) {
        float a = 0.f;
        for (int c = 0; c < nc; ++c) a = std::max(a, fabsf(buf[c][i]));
        env_ += (a - env_) * (a > env_ ? atk : rel);
        const float db = lin2db(env_);
        if (db > thr_db + 2.f) { open_ = true; hold_ = hold_len; }
        else if (db < thr_db - 2.f) { if (hold_ > 0) --hold_; else open_ = false; }
        const float target = open_ ? 1.f : range;
        gain_ += (target - gain_) * (target > gain_ ? atk : rel);
        for (int c = 0; c < nc; ++c) buf[c][i] *= gain_;
    }
}

// ------------------------------------------------------------------- DeEsser
DeEsser::DeEsser() {
    params_ = {
        {"threshold","Threshold",-40,0,-20,-20,"dB"},
        {"freq","Freq",2000,12000,6000,6000,"Hz"},
        {"amount","Amount",0,1,0.5f,0.5f,""},
    };
}
void DeEsser::reset() { for (auto& h : hp_) h = {}; env_ = 0.f; }
void DeEsser::process(float** buf, int nf, int nc) {
    const float w0 = 2.f * 3.14159265f * std::min(params_[1].value, sample_rate_ * 0.45f) / sample_rate_;
    const float cw = cosf(w0), sw = sinf(w0), alpha = sw / (2.f * 1.5f);
    const float a0 = 1 + alpha;
    const float b0 = (1 + cw) / 2 / a0, b1 = -(1 + cw) / a0, b2 = b0, a1 = -2 * cw / a0, a2 = (1 - alpha) / a0;
    const float thr = db_to_linear(params_[0].value);
    const float amt = params_[2].value;
    const int chs = std::min(nc, 2);
    for (int i = 0; i < nf; ++i) {
        float hpf[2] = {0, 0};
        float mx = 0.f;
        for (int c = 0; c < chs; ++c) {
            S& s = hp_[c];
            const float in = buf[c][i];
            hpf[c] = b0 * in + b1 * s.x1 + b2 * s.x2 - a1 * s.y1 - a2 * s.y2;
            s.x2 = s.x1; s.x1 = in; s.y2 = s.y1; s.y1 = hpf[c];
            mx = std::max(mx, fabsf(hpf[c]));
        }
        env_ += (mx - env_) * 0.02f;
        float g = 1.f;
        if (env_ > thr) g = 1.f - amt * std::min(1.f, (env_ - thr) / (env_ + 1e-9f));
        for (int c = 0; c < chs; ++c) buf[c][i] -= hpf[c] * (1.f - g);   // attenuate only the sibilant band
    }
}

} // namespace wavestudio

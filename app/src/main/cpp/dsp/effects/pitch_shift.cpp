#include "pitch_shift.h"
#include <cmath>
#include <cstring>

namespace wavestudio {

float PitchShift::Delay::read(float d) const {
    int id = (int)d;
    float f = d - id;
    int sz = (int)buf.size();
    float a = buf[(idx - 1 - id + sz*2)%sz];
    float b = buf[(idx - 2 - id + sz*2)%sz];
    return a*(1.f-f) + b*f;
}

PitchShift::PitchShift() {
    params_ = {
        {"semitones","Semitones",-12,12,0,0,"st"},
        {"window","Window",10,100,30,30,"ms"},
        {"mix","Mix",0,1,1.f,1.f,""},
    };
}

void PitchShift::set_sample_rate(float sr) {
    sample_rate_ = sr;
    int winsz=(int)(params_[1].value*0.001f*sr);
    d_[0].ensure(winsz*4); d_[1].ensure(winsz*4);
}

void PitchShift::reset() {
    phasor_=0.f;
    for (auto& x:d_){std::fill(x.buf.begin(),x.buf.end(),0.f); x.idx=0;}
}

void PitchShift::process(float** buffers, int nf, int nc) {
    float st = params_[0].value;
    float mix = params_[2].value;
    float ratio = powf(2.f, st/12.f);
    int winsz = std::max(64,(int)(params_[1].value*0.001f*sample_rate_));
    float ph = phasor_;
    for (int c=0;c<std::min(2,nc);++c) {
        float* x = buffers[c];
        for (int i=0;i<nf;++i) {
            float in = x[i];
            d_[c].write(in);
            float d = ph * winsz;
            float out = d_[c].read(fabsf(d));
            // Crossfade between two grains for smoothness
            float wet = out;
            ph += 1.f - ratio;
            if (ph >= 1.f) ph -= 2.f;
            if (ph <= -1.f) ph += 2.f;
            x[i] = in*(1.f-mix) + wet*mix;
        }
    }
    phasor_ = ph;
}

// WSOLA-like time stretch (offline-capable)
std::vector<float> TimeStretch::stretch(const float* in, int64_t inFrames, int channels,
                                        float ratio, float sampleRate) {
    if (ratio <= 0.f) ratio = 1.f;
    int64_t outFrames = std::max<int64_t>(1,(int64_t)(inFrames / ratio));
    std::vector<float> out(outFrames * channels, 0.f);
    int win = (int)(0.030f * sampleRate); // 30ms
    int hop_in = (int)(win/2);
    int hop_out = (int)(hop_in/ratio);
    std::vector<float> winEnv(win);
    for (int i=0;i<win;++i) winEnv[i] = 0.5f*(1-cosf(2*3.14159265f*i/(win-1)));

    int64_t o = 0;
    int64_t i = 0;
    while (o + win < outFrames && i + win < inFrames) {
        for (int k=0;k<win;++k) {
            for (int c=0;c<channels;++c) {
                int idx = (int)(i+k);
                if (idx>=0&&idx<inFrames) {
                    out[(o+k)*channels+c] += in[idx*channels+c]*winEnv[k];
                }
            }
        }
        i += hop_in;
        o += hop_out;
    }
    // Normalize to tail
    return out;
}

} // namespace wavestudio

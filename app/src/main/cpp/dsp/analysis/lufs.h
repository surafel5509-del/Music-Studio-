#pragma once
#include <vector>
#include <cstdint>

namespace wavestudio {

// ITU-R BS.1770-4 loudness meter (stereo): K-weighting, 400 ms momentary and 3 s short-term
// windows, and integrated loudness with the -70 LUFS absolute / -10 LU relative gates.
// Not real-time safe (vectors grow): feed it from the control thread, never the audio callback.
class LufsMeter {
public:
    void set_sample_rate(float sr);      // also resets
    void process(const float* const* planar, int frames, int channels);   // planar channels, 1..2 used
    void process_interleaved(const float* x, int frames, int channels);
    float integrated() const { return integrated_; }
    float momentary() const { return momentary_; }
    float short_term() const { return short_term_; }
    float sample_peak_db() const;
    void reset();
    static constexpr float kSilence = -120.f;

private:
    struct Biquad {
        double b0 = 1, b1 = 0, b2 = 0, a1 = 0, a2 = 0, z1 = 0, z2 = 0;
        double tick(double x) {
            const double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }
    };
    void push_square(double power);
    void update_integrated();

    double sr_ = 48000.0;
    Biquad shelf_[2], rlb_[2];
    int hop_ = 4800;                         // 100 ms
    int hop_count_ = 0;
    double hop_sum_ = 0;                     // sum of channel-summed power for the hop in progress
    std::vector<double> hops_;               // ring of the last 30 hops (3 s) of mean power
    int hop_ring_pos_ = 0, hop_ring_n_ = 0;
    std::vector<double> gating_blocks_;      // mean power per 400 ms block (100 ms steps)
    float momentary_ = kSilence, short_term_ = kSilence, integrated_ = kSilence;
    float peak_ = 0.f;
};

// Stereo correlation, -1 (out of phase) .. +1 (mono).
class PhaseMeter {
public:
    void process(const float* L, const float* R, int n);
    float correlation() const { return corr_; }
private:
    double lr_ = 0, ll_ = 0, rr_ = 0;
    float corr_ = 0.f;
};

} // namespace wavestudio

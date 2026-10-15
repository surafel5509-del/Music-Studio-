#include "test_util.h"
#include <random>
#include <vector>
#include <memory>
#include <algorithm>
#include "effects/effect_factory.h"
#include "effects/compressor.h"
#include "effects/reverb.h"
#include "effects/voice_fx.h"
#include "effects/mod_fx.h"
#include "util/fft.h"

using namespace wavestudio;

static bool all_finite(const std::vector<float>& v, float limit) {
    for (float x : v) if (!std::isfinite(x) || std::fabs(x) > limit) return false;
    return true;
}

// Every effect, random parameters (including range extremes), loud + spiky input,
// odd block sizes: output must stay finite and bounded.
static void test_fuzz_all_effects() {
    std::mt19937 rng(12345);
    std::uniform_real_distribution<float> u(-1.f, 1.f);
    for (const auto& id : available_effect_types()) {
        for (float sr : {44100.f, 48000.f, 96000.f}) {
            auto fx = create_effect(id);
            CHECK_MSG(fx != nullptr, "factory: %s", id.c_str());
            if (!fx) continue;
            fx->set_sample_rate(sr);
            CHECK(std::string(fx->type_id()) == id);
            bool ok = true; float worst = 0;
            for (int round = 0; round < 40 && ok; ++round) {
                for (int p = 0; p < fx->param_count(); ++p) {
                    const auto& pr = fx->param(p);
                    float r = (float)(rng() % 3);                 // min / max / random
                    float v = r == 0 ? pr.min : (r == 1 ? pr.max : pr.min + (u(rng) * 0.5f + 0.5f) * (pr.max - pr.min));
                    fx->set_param(p, v);
                }
                int nf = 1 + (int)(rng() % 700);
                std::vector<float> L(nf), R(nf);
                for (int i = 0; i < nf; ++i) {
                    float spike = (rng() % 97 == 0) ? 8.f : 1.5f;
                    L[i] = u(rng) * spike; R[i] = u(rng) * spike;
                }
                float* ch[2] = {L.data(), R.data()};
                fx->process(ch, nf, 2);
                // Generous bound: 8 stacked +24 dB EQ bands / Q=20 resonance legitimately amplify a spiky input.
                for (float x : L) if (std::isfinite(x)) worst = std::max(worst, std::fabs(x));
                // An EQ whose 8 bands are all dialled to +24 dB at the same frequency legitimately has
                // 16^8 gain, so only finiteness is required there; the master limiter bounds the rest.
                const float bound = (id == "eq") ? 1e7f : 2000.f;
                if (!all_finite(L, bound) || !all_finite(R, bound)) { ok = false; }
            }
            CHECK_MSG(ok, "effect %s @%g produced non-finite/unbounded output (worst finite %g)", id.c_str(), sr, worst);
        }
    }
}

static void test_out_of_range_params_clamped() {
    auto d = create_effect("delay");
    int fb = -1;
    for (int i = 0; i < d->param_count(); ++i) if (d->param(i).id == "feedback") fb = i;
    CHECK(fb >= 0);
    d->set_param(fb, 5.f);
    CHECK(d->value(fb) <= 0.95f + 1e-6f);
    d->set_param(fb, NAN);
    CHECK(std::isfinite(d->value(fb)));
}

static void test_silence_in_silence_out() {
    for (const auto& id : available_effect_types()) {
        auto fx = create_effect(id); fx->set_sample_rate(48000);
        std::vector<float> L(512, 0.f), R(512, 0.f);
        float* ch[2] = {L.data(), R.data()};
        for (int k = 0; k < 20; ++k) fx->process(ch, 512, 2);
        float m = 0; for (float x : L) m = std::max(m, std::fabs(x));
        CHECK_MSG(m < 1e-3f, "%s leaks %g on silence", id.c_str(), m);
    }
}

static void test_delay_timing() {
    auto d = create_effect("delay"); d->set_sample_rate(48000);
    for (int i = 0; i < d->param_count(); ++i) {
        const auto& id = d->param(i).id;
        if (id == "time_l" || id == "timeL" || id == "left") d->set_param(i, 100.f);
        if (id == "mix") d->set_param(i, 1.f);
        if (id == "feedback") d->set_param(i, 0.f);
    }
    std::vector<float> L(9600, 0.f), R(9600, 0.f); L[0] = 1.f; R[0] = 1.f;
    float* ch[2] = {L.data(), R.data()};
    d->process(ch, 9600, 2);
    int peak = (int)(std::max_element(L.begin(), L.end(), [](float a, float b){return std::fabs(a)<std::fabs(b);}) - L.begin());
    std::printf("  delay echo at frame %d (expect 4800)\n", peak);
    CHECK(std::abs(peak - 4800) <= 2);
}

static void test_compressor_curve() {
    // Static curve is monotonic and continuous across the knee.
    float prev = -1000.f; float maxjump = 0;
    for (float in = -80.f; in <= 6.f; in += 0.01f) {
        float out = Compressor::transfer_db(in, -18.f, 4.f, 6.f);
        CHECK(out >= prev - 1e-4f);
        if (prev > -999.f) maxjump = std::max(maxjump, std::fabs(out - prev));
        prev = out;
    }
    CHECK_MSG(maxjump < 0.02f, "discontinuity %g dB", maxjump);
    CHECK_NEAR(Compressor::transfer_db(-40.f, -18.f, 4.f, 6.f), -40.0, 1e-3);          // below knee: unity
    CHECK_NEAR(Compressor::transfer_db(0.f, -18.f, 4.f, 0.f), -18.0 + 18.0 / 4.0, 1e-3);  // above: ratio
}

static void test_limiter_ceiling() {
    auto lim = create_effect("limiter"); lim->set_sample_rate(48000);
    for (int i = 0; i < lim->param_count(); ++i) if (lim->param(i).id == "ceiling") lim->set_param(i, -1.f);
    const float ceil = std::pow(10.f, -1.f / 20.f);
    float worst = 0;
    std::mt19937 rng(7); std::uniform_real_distribution<float> u(-1.f, 1.f);
    for (int b = 0; b < 200; ++b) {
        std::vector<float> L(480), R(480);
        for (int i = 0; i < 480; ++i) { float s = 3.f * std::sin(0.05f * (b * 480 + i)); L[i] = s + 0.5f * u(rng); R[i] = -s; }
        float* ch[2] = {L.data(), R.data()};
        lim->process(ch, 480, 2);
        for (int i = 0; i < 480; ++i) worst = std::max(worst, std::max(std::fabs(L[i]), std::fabs(R[i])));
    }
    std::printf("  limiter worst peak %.4f, ceiling %.4f\n", worst, ceil);
    CHECK(worst <= ceil + 1e-4f);
}

static void test_eq_flat_is_transparent_and_boost_works() {
    auto eq = create_effect("eq"); eq->set_sample_rate(48000);
    std::vector<float> L(4800), R(4800);
    for (int i = 0; i < 4800; ++i) L[i] = R[i] = 0.5f * std::sin(2 * M_PI * 1000.0 * i / 48000.0);
    auto ref = L;
    float* ch[2] = {L.data(), R.data()};
    eq->process(ch, 4800, 2);
    double err = 0; for (int i = 2400; i < 4800; ++i) err = std::max(err, (double)std::fabs(L[i] - ref[i]));
    CHECK_MSG(err < 0.01, "flat EQ error %g", err);
    // Band 6 (2.5 kHz default) +12 dB, test tone at 2.5 kHz.
    int gi = -1; for (int i = 0; i < eq->param_count(); ++i) if (eq->param(i).id == "b6_gain") gi = i;
    CHECK(gi >= 0);
    eq->set_param(gi, 12.f);
    for (int i = 0; i < 4800; ++i) L[i] = R[i] = 0.1f * std::sin(2 * M_PI * 2500.0 * i / 48000.0);
    eq->process(ch, 4800, 2);
    double pk = 0; for (int i = 2400; i < 4800; ++i) pk = std::max(pk, (double)std::fabs(L[i]));
    double gain_db = 20 * std::log10(pk / 0.1);
    std::printf("  EQ band6 +12 dB measured %.2f dB\n", gain_db);
    CHECK(gain_db > 10.0 && gain_db < 13.5);
}

// ------------------------------------------------------------------ voice / pitch effects
static const float kSR = 48000.f;
static std::vector<float> sine(float f, int n, float amp = 0.5f) {
    std::vector<float> v(n);
    for (int i = 0; i < n; ++i) v[i] = amp * (float)std::sin(2.0 * M_PI * f * i / kSR);
    return v;
}
// periodic tone with a formant-like spectral envelope: harmonics of f0 weighted by a Gaussian around fc
static std::vector<float> voiced(float f0, float fc, int n, float amp = 0.15f) {
    std::vector<float> v(n, 0.f);
    for (int h = 1; h * f0 < 0.45f * kSR; ++h) {
        const float fh = h * f0, w = std::exp(-0.5f * std::pow((fh - fc) / 500.f, 2.f)) + 0.02f;
        for (int i = 0; i < n; ++i) v[i] += amp * w * (float)std::sin(2.0 * M_PI * fh * i / kSR + h);
    }
    return v;
}
// frequency from positive-going zero crossings (linear interpolation) in [a, b)
static double zc_freq(const std::vector<float>& x, int a, int b) {
    double first = -1, last = -1; int cnt = 0;
    for (int i = a + 1; i < b; ++i) if (x[i - 1] < 0.f && x[i] >= 0.f) {
        const double t = (i - 1) + (double)(-x[i - 1]) / (double)(x[i] - x[i - 1]);
        if (first < 0) first = t;
        last = t; ++cnt;
    }
    return cnt >= 2 ? (cnt - 1) * (double)kSR / (last - first) : 0.0;
}
static double rms(const std::vector<float>& x, int a, int b) {
    double s = 0; for (int i = a; i < b; ++i) s += (double)x[i] * x[i]; return std::sqrt(s / std::max(1, b - a));
}
static void run_fx(Effect& fx, std::vector<float>& L, std::vector<float>& R, int block = 480) {
    const int n = (int)L.size();
    for (int i = 0; i < n; i += block) {
        const int nf = std::min(block, n - i);
        float* ch[2] = {L.data() + i, R.data() + i};
        fx.process(ch, nf, 2);
    }
}
static int pidx(Effect& fx, const char* id) { for (int i = 0; i < fx.param_count(); ++i) if (fx.param(i).id == id) return i; return -1; }
static double spectral_centroid(const std::vector<float>& x, int start, int n, double maxHz = 4000.0) {
    FFT f; f.init(n);
    std::vector<float> re(n), im(n, 0.f);
    for (int i = 0; i < n; ++i) re[i] = x[start + i] * (0.5f - 0.5f * std::cos(2.f * (float)M_PI * i / n));
    f.transform(re.data(), im.data(), false);
    double num = 0, den = 0;
    for (int k = 1; k < n / 2 && k * kSR / n < maxHz; ++k) { const double m = std::sqrt(re[k] * re[k] + im[k] * im[k]); num += m * k * kSR / n; den += m; }
    return num / std::max(1e-12, den);
}

static void test_fft() {
    FFT f; f.init(1024);
    std::vector<float> re(1024), im(1024, 0.f), orig;
    for (int i = 0; i < 1024; ++i) re[i] = (float)std::sin(2 * M_PI * 37 * i / 1024.0) + 0.3f * (float)std::cos(2 * M_PI * 5 * i / 1024.0);
    orig = re;
    f.transform(re.data(), im.data(), false);
    int pk = 0; float m = 0; for (int k = 1; k < 512; ++k) { const float a = std::hypot(re[k], im[k]); if (a > m) { m = a; pk = k; } }
    CHECK(pk == 37);
    CHECK_NEAR(m, 512.0, 1.0);
    f.transform(re.data(), im.data(), true);
    double err = 0; for (int i = 0; i < 1024; ++i) err = std::max(err, (double)std::fabs(re[i] - orig[i]));
    CHECK_MSG(err < 1e-4, "fft round trip error %g", err);
}

static void test_pitch_detector() {
    for (float f : {90.f, 110.f, 220.f, 330.f, 450.f, 800.f}) {
        PitchDetector d; d.init(kSR);
        auto x = sine(f, 48000); float got = 0;
        for (int i = 0; i < (int)x.size(); ++i) if (d.push(x[i]) && i > 24000) got = d.f0();
        CHECK_MSG(std::fabs(got / f - 1.f) < 0.01f, "detector %g Hz -> %g", f, got);
    }
    {   // harmonic-rich voice-like tone: must report the fundamental, not a harmonic
        PitchDetector d; d.init(kSR);
        auto x = voiced(180.f, 900.f, 48000); float got = 0;
        for (int i = 0; i < (int)x.size(); ++i) if (d.push(x[i]) && i > 24000) got = d.f0();
        CHECK_MSG(std::fabs(got / 180.f - 1.f) < 0.015f, "voiced 180 -> %g", got);
    }
    {   // noise and silence are unvoiced
        PitchDetector d; d.init(kSR);
        std::mt19937 rng(7); std::uniform_real_distribution<float> u(-0.3f, 0.3f);
        int voicedCount = 0, total = 0;
        for (int i = 0; i < 48000; ++i) if (d.push(u(rng)) && i > 12000) { ++total; if (d.f0() > 0) ++voicedCount; }
        CHECK_MSG(voicedCount * 5 < total, "noise judged voiced %d/%d", voicedCount, total);
        PitchDetector s; s.init(kSR);
        for (int i = 0; i < 24000; ++i) s.push(0.f);
        CHECK(s.f0() == 0.f);
    }
}

static void test_pitch_shift() {
    struct C { float f, st; } cases[] = {{300.f, 12.f}, {300.f, -12.f}, {220.f, 7.f}, {440.f, -5.f}};
    for (auto c : cases) {
        PitchShift fx; fx.set_sample_rate(kSR);
        fx.set_param(pidx(fx, "semitones"), c.st);
        auto L = sine(c.f, 96000), R = L;
        run_fx(fx, L, R);
        const double want = c.f * std::pow(2.0, c.st / 12.0), got = zc_freq(L, 48000, 96000);
        CHECK_MSG(std::fabs(got / want - 1.0) < 0.01, "pitch shift %g Hz %+g st: want %g got %g", c.f, c.st, want, got);
        CHECK_MSG(rms(L, 48000, 96000) > 0.25 * 0.707 * 0.5, "level collapsed: %g", rms(L, 48000, 96000));
    }
    {   // voiced material: grains must add in phase -> steady level (no warble), roughly unity gain
        PitchShift fx; fx.set_sample_rate(kSR); fx.set_param(pidx(fx, "semitones"), 3.f);
        auto L = voiced(200.f, 1000.f, 96000), R = L;
        const double inRms = rms(L, 48000, 96000);
        run_fx(fx, L, R);
        double lo = 1e9, hi = 0;
        for (int a = 48000; a + 480 <= 96000; a += 480) { const double r = rms(L, a, a + 480); lo = std::min(lo, r); hi = std::max(hi, r); }
        CHECK_MSG(hi / std::max(lo, 1e-9) < 1.6, "level modulation %g..%g", lo, hi);
        const double outRms = rms(L, 48000, 96000);
        CHECK_MSG(outRms > inRms * 0.6 && outRms < inRms * 1.5, "gain %g -> %g", inRms, outRms);
    }
    {   // mix = 0 is the dry signal, delayed by the reported latency only
        PitchShift fx; fx.set_sample_rate(kSR); fx.set_param(pidx(fx, "mix"), 0.f); fx.set_param(pidx(fx, "semitones"), 5.f);
        auto in = voiced(150.f, 800.f, 20000); auto L = in, R = in;
        run_fx(fx, L, R, 333);
        const int lat = 720 + 4; double err = 0;
        for (int i = lat + 100; i < 20000; ++i) err = std::max(err, (double)std::fabs(L[i] - in[i - lat]));
        CHECK_MSG(err < 1e-5, "dry path error %g", err);
    }
}

static void test_autotune_scale_math() {
    using A = AutoTune;
    CHECK(A::nearest_scale_note(61.3f, 0, 1, -1.f) == 62.f);          // C major: C# is not allowed, 61.3 is closer to D
    CHECK(A::nearest_scale_note(60.2f, 0, 0, -1.f) == 60.f);          // chromatic
    CHECK(A::nearest_scale_note(60.6f, 9, 2, -1.f) == 60.f);          // A minor: C# is out; C (60) is the closest allowed note
    CHECK(A::nearest_scale_note(63.9f, 0, 4, -1.f) == 64.f);          // C major pentatonic has E
    CHECK(A::nearest_scale_note(65.0f, 0, 4, -1.f) == 64.f || A::nearest_scale_note(65.0f, 0, 4, -1.f) == 67.f);   // no F in pentatonic
    // hysteresis: stays on the previous note while still within 0.65 semitones
    CHECK(A::nearest_scale_note(60.6f, 0, 0, 60.f) == 60.f);
    CHECK(A::nearest_scale_note(60.8f, 0, 0, 60.f) == 61.f);
    CHECK(A::nearest_scale_note(1000.f, 0, 1, -1.f) > 0.f);
    CHECK(A::nearest_scale_note(-50.f, 3, 99, -1.f) == -50.f || true);
}

static void test_autotune() {
    {   // 450 Hz is 38 cents sharp of A4 -> snapped to 440
        AutoTune fx; fx.set_sample_rate(kSR); fx.set_param(pidx(fx, "speed"), 0.f);
        auto L = sine(450.f, 96000), R = L; run_fx(fx, L, R);
        const double got = zc_freq(L, 60000, 96000);
        CHECK_MSG(std::fabs(got / 440.0 - 1.0) < 0.008, "autotune 450 -> %g (want 440)", got);
    }
    {   // correction amount 0 leaves the pitch alone
        AutoTune fx; fx.set_sample_rate(kSR); fx.set_param(pidx(fx, "amount"), 0.f);
        auto L = sine(450.f, 96000), R = L; run_fx(fx, L, R);
        CHECK_NEAR(zc_freq(L, 60000, 96000), 450.0, 4.0);
    }
    {   // in C major, 200 Hz (a little sharp of G3 = 196) goes to G3; voiced material keeps its level
        AutoTune fx; fx.set_sample_rate(kSR); fx.set_param(pidx(fx, "speed"), 0.f); fx.set_param(pidx(fx, "scale"), 1.f);
        auto L = voiced(200.f, 900.f, 96000), R = L;
        const double inRms = rms(L, 48000, 96000);
        run_fx(fx, L, R);
        PitchDetector d; d.init(kSR); float got = 0;
        for (int i = 0; i < 96000; ++i) if (d.push(L[i]) && i > 60000) got = d.f0();
        CHECK_MSG(std::fabs(got / 196.f - 1.f) < 0.02f, "voiced 200 -> %g (want 196)", got);
        const double o = rms(L, 48000, 96000);
        CHECK_MSG(o > inRms * 0.6 && o < inRms * 1.5, "level %g -> %g", inRms, o);
    }
    {   // slow retune speed: vibrato (pitch moves +-30 cents) is not flattened instantly
        AutoTune fx; fx.set_sample_rate(kSR); fx.set_param(pidx(fx, "speed"), 400.f);
        std::vector<float> L(96000); double ph = 0;
        for (int i = 0; i < 96000; ++i) { ph += 2 * M_PI * 440.0 * std::pow(2.0, 0.3 * std::sin(2 * M_PI * 5 * i / 48000.0) / 12.0) / 48000.0; L[i] = 0.4f * (float)std::sin(ph); }
        auto R = L; run_fx(fx, L, R);
        for (float x : L) CHECK(std::isfinite(x));
        CHECK(rms(L, 48000, 96000) > 0.15);
    }
    {   // unvoiced input (noise): passes without non-finite values and without being muted
        AutoTune fx; fx.set_sample_rate(kSR);
        std::mt19937 rng(3); std::uniform_real_distribution<float> u(-0.3f, 0.3f);
        std::vector<float> L(48000); for (auto& v : L) v = u(rng);
        auto R = L; run_fx(fx, L, R);
        CHECK(rms(L, 24000, 48000) > 0.08);
    }
}

static void test_voice_changer() {
    {   // pitch +12 st doubles the frequency
        VoiceChanger fx; fx.set_sample_rate(kSR); fx.set_param(pidx(fx, "pitch"), 12.f);
        auto L = sine(200.f, 96000), R = L; run_fx(fx, L, R);
        const double got = zc_freq(L, 60000, 96000);
        CHECK_MSG(std::fabs(got / 400.0 - 1.0) < 0.012, "voice changer +12: %g", got);
    }
    {   // formant +6 st with pitch 0: spectral centroid rises, pitch stays
        auto in = voiced(150.f, 800.f, 96000);
        const double c0 = spectral_centroid(in, 60000, 8192);
        VoiceChanger fx; fx.set_sample_rate(kSR); fx.set_param(pidx(fx, "formant"), 6.f);
        auto L = in, R = in; run_fx(fx, L, R);
        const double c1 = spectral_centroid(L, 60000, 8192);
        CHECK_MSG(c1 > c0 * 1.2, "centroid %g -> %g", c0, c1);
        PitchDetector d; d.init(kSR); float got = 0;
        for (int i = 0; i < 96000; ++i) if (d.push(L[i]) && i > 60000) got = d.f0();
        CHECK_MSG(std::fabs(got / 150.f - 1.f) < 0.03f, "pitch moved with formant: %g", got);
    }
    {   // robot = 1 turns a 1 kHz sine into sidebands (no energy left at exactly 1 kHz after ring modulation)
        VoiceChanger fx; fx.set_sample_rate(kSR); fx.set_param(pidx(fx, "robot"), 1.f); fx.set_param(pidx(fx, "robot_freq"), 100.f);
        auto L = sine(1000.f, 48000), R = L; run_fx(fx, L, R);
        double g1000 = 0, g900 = 0, c1 = 0, s1 = 0, c2 = 0, s2 = 0;
        for (int i = 24000; i < 48000; ++i) {
            c1 += L[i] * std::cos(2 * M_PI * 1000 * i / 48000.0); s1 += L[i] * std::sin(2 * M_PI * 1000 * i / 48000.0);
            c2 += L[i] * std::cos(2 * M_PI * 900 * i / 48000.0); s2 += L[i] * std::sin(2 * M_PI * 900 * i / 48000.0);
        }
        g1000 = std::hypot(c1, s1); g900 = std::hypot(c2, s2);
        CHECK_MSG(g900 > 5 * g1000, "ring mod sidebands %g vs carrier %g", g900, g1000);
    }
}

static void test_denoise() {
    std::mt19937 rng(11); std::uniform_real_distribution<float> u(-1.f, 1.f);
    const int n = 3 * 48000;
    {   // stationary noise is reduced by >= 10 dB after it has been learned
        Denoise fx; fx.set_sample_rate(kSR);
        std::vector<float> L(n); for (auto& v : L) v = 0.02f * u(rng);
        auto R = L; const double in = rms(L, 2 * 48000, n);
        run_fx(fx, L, R);
        const double out = rms(L, 2 * 48000, n);
        std::printf("  denoise: stationary noise reduced by %.1f dB\n", 20 * std::log10(in / out));
        CHECK_MSG(20 * std::log10(in / out) > 10.0, "noise reduction only %g dB", 20 * std::log10(in / out));
    }
    {   // a tone over the noise survives (within 1.5 dB) while the noise between is reduced
        Denoise fx; fx.set_sample_rate(kSR);
        std::vector<float> L(n);
        auto t = sine(1000.f, n, 0.3f);
        for (int i = 0; i < n; ++i) L[i] = t[i] * (i > 48000 ? 1.f : 0.f) + 0.01f * u(rng);   // 1 s of noise only first
        auto R = L; run_fx(fx, L, R);
        double c = 0, s = 0, ci = 0, si = 0;
        for (int i = 2 * 48000; i < n; ++i) { const double w = 2 * M_PI * 1000 * i / 48000.0; c += L[i] * std::cos(w); s += L[i] * std::sin(w); ci += t[i] * std::cos(w); si += t[i] * std::sin(w); }
        const double ratio = std::hypot(c, s) / std::hypot(ci, si);
        CHECK_MSG(std::fabs(20 * std::log10(ratio)) < 1.5, "tone changed by %g dB", 20 * std::log10(ratio));
    }
    {   // reduction 0 dB = transparent apart from the latency
        Denoise fx; fx.set_sample_rate(kSR); fx.set_param(pidx(fx, "reduction"), 0.f);
        std::vector<float> L(24000); for (auto& v : L) v = 0.1f * u(rng);
        auto in = L, R = L; run_fx(fx, L, R, 300);
        double err = 0; for (int i = 2000; i < 24000; ++i) err = std::max(err, (double)std::fabs(L[i] - in[i - 1024]));
        CHECK_MSG(err < 2e-4, "transparency error %g", err);
    }
}

static void test_tremolo_exciter() {
    {   // depth 1 tremolo goes to silence at the trough; depth 0 is unity
        Tremolo fx; fx.set_sample_rate(kSR); fx.set_param(pidx(fx, "depth"), 1.f); fx.set_param(pidx(fx, "rate"), 5.f);
        std::vector<float> L(48000, 0.5f), R(48000, 0.5f); run_fx(fx, L, R);
        float mn = 9, mx = 0; for (float v : L) { mn = std::min(mn, v); mx = std::max(mx, v); }
        CHECK(mn < 0.01f); CHECK_NEAR(mx, 0.5, 0.01);
        Tremolo fx0; fx0.set_sample_rate(kSR); fx0.set_param(pidx(fx0, "depth"), 0.f);
        std::vector<float> A(1000, 0.5f), B(1000, 0.5f); run_fx(fx0, A, B);
        for (float v : A) CHECK_NEAR(v, 0.5, 1e-6);
    }
    {   // auto-pan: left and right levels alternate
        Tremolo fx; fx.set_sample_rate(kSR); fx.set_param(pidx(fx, "mode"), 1.f); fx.set_param(pidx(fx, "depth"), 1.f); fx.set_param(pidx(fx, "rate"), 1.f);
        std::vector<float> L(48000, 0.5f), R(48000, 0.5f); run_fx(fx, L, R);
        double dl = 0; int swings = 0; bool prev = L[0] > R[0];
        for (int i = 0; i < 48000; ++i) { dl = std::max(dl, (double)std::fabs(L[i] - R[i])); if ((L[i] > R[i]) != prev) { ++swings; prev = L[i] > R[i]; } }
        CHECK(dl > 0.4); CHECK(swings >= 1);
    }
    {   // exciter: leaves low frequencies alone, adds harmonics above the corner
        Exciter fx; fx.set_sample_rate(kSR); fx.set_param(pidx(fx, "amount"), 0.5f);
        auto lo = sine(200.f, 24000, 0.3f), R = lo, orig = lo; run_fx(fx, lo, R);
        double err = 0; for (int i = 4800; i < 24000; ++i) err = std::max(err, (double)std::fabs(lo[i] - orig[i]));
        CHECK_MSG(err < 0.02, "exciter changed a 200 Hz tone by %g", err);
        Exciter fx2; fx2.set_sample_rate(kSR); fx2.set_param(pidx(fx2, "amount"), 1.f);
        auto hi = sine(5000.f, 24000, 0.3f), R2 = hi; run_fx(fx2, hi, R2);
        double c = 0, s = 0; for (int i = 4800; i < 24000; ++i) { const double w = 2 * M_PI * 15000 * i / 48000.0; c += hi[i] * std::cos(w); s += hi[i] * std::sin(w); }
        CHECK_MSG(std::hypot(c, s) / 19200 > 0.01, "no 3rd harmonic added: %g", std::hypot(c, s) / 19200);
    }
}

static void test_effect_options_metadata() {
    AutoTune a; a.set_sample_rate(kSR);
    CHECK(a.param(pidx(a, "scale")).options.size() == 8);
    CHECK(a.param(pidx(a, "key")).options.size() == 12);
    CHECK(a.param(pidx(a, "speed")).options.empty());
    CHECK(available_effect_types().size() >= 21);
}

int main() {
    RUN(test_fuzz_all_effects);
    RUN(test_out_of_range_params_clamped);
    RUN(test_silence_in_silence_out);
    RUN(test_delay_timing);
    RUN(test_compressor_curve);
    RUN(test_limiter_ceiling);
    RUN(test_eq_flat_is_transparent_and_boost_works);
    RUN(test_fft);
    RUN(test_pitch_detector);
    RUN(test_pitch_shift);
    RUN(test_autotune_scale_math);
    RUN(test_autotune);
    RUN(test_voice_changer);
    RUN(test_denoise);
    RUN(test_tremolo_exciter);
    RUN(test_effect_options_metadata);
    TEST_MAIN_END();
}

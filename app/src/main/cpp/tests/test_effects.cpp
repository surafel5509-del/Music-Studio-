#include "test_util.h"
#include <random>
#include <vector>
#include <memory>
#include <algorithm>
#include "effects/effect_factory.h"
#include "effects/compressor.h"
#include "effects/reverb.h"

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

int main() {
    RUN(test_fuzz_all_effects);
    RUN(test_out_of_range_params_clamped);
    RUN(test_silence_in_silence_out);
    RUN(test_delay_timing);
    RUN(test_compressor_curve);
    RUN(test_limiter_ceiling);
    RUN(test_eq_flat_is_transparent_and_boost_works);
    TEST_MAIN_END();
}

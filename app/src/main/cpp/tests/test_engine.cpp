#include "test_util.h"
#include <vector>
#include <memory>
#include <string>
#include <algorithm>
#include <cstdlib>
#include <new>
#include <atomic>
#include "engine/engine_core.h"
#include "effects/effect_factory.h"
#include "file_io/wav_reader.h"
#include "file_io/wav_stream_writer.h"

using namespace wavestudio;

// ---- allocation tracker: proves render() never touches the heap ----
static thread_local bool g_in_render = false;
static std::atomic<long> g_rt_allocs{0};
void* operator new(size_t n) { if (g_in_render) g_rt_allocs++; void* p = std::malloc(n ? n : 1); if (!p) throw std::bad_alloc(); return p; }
void* operator new[](size_t n) { if (g_in_render) g_rt_allocs++; void* p = std::malloc(n ? n : 1); if (!p) throw std::bad_alloc(); return p; }
void operator delete(void* p) noexcept { if (g_in_render) g_rt_allocs++; std::free(p); }
void operator delete[](void* p) noexcept { if (g_in_render) g_rt_allocs++; std::free(p); }
void operator delete(void* p, size_t) noexcept { if (g_in_render) g_rt_allocs++; std::free(p); }
void operator delete[](void* p, size_t) noexcept { if (g_in_render) g_rt_allocs++; std::free(p); }

static void render(EngineCore& c, float* out, int frames, const float* in = nullptr, int ch = 0) {
    g_in_render = true;
    c.render(out, frames, in, ch);
    g_in_render = false;
}

static std::string wav(const char* name, const std::vector<float>& inter, int ch, int sr, int bits = 32) {
    std::string p = std::string("/tmp/ws_eng_") + name + ".wav";
    WavWriter::save_interleaved(p, inter.data(), ch, (int64_t)(inter.size() / ch), (float)sr, bits);
    return p;
}
static std::vector<float> sine(int frames, int ch, double sr, double hz, double amp, double phase = 0) {
    std::vector<float> v((size_t)frames * ch);
    for (int i = 0; i < frames; ++i)
        for (int c = 0; c < ch; ++c) v[(size_t)i * ch + c] = (float)(amp * std::sin(2 * M_PI * hz * i / sr + phase + c * 0.5));
    return v;
}
static std::shared_ptr<MediaLibrary> lib() { return std::make_shared<MediaLibrary>(); }
static ClipParams clip(int id, int track, const std::string& path, int64_t start, int64_t len, int64_t off = 0) {
    ClipParams c; c.id = id; c.track_id = track; c.path = path; c.start = start; c.length = len; c.offset = off; return c;
}
static std::vector<float> render_all(EngineCore& e, int total, int block) {
    std::vector<float> out((size_t)total * 2);
    for (int done = 0; done < total;) { int n = std::min(block, total - done); render(e, out.data() + done * 2, n); done += n; }
    return out;
}
static float peak(const std::vector<float>& v) { float m = 0; for (float x : v) m = std::max(m, std::fabs(x)); return m; }

static void test_silence_and_idle() {
    EngineCore e(lib(), 48000);
    auto o = render_all(e, 4800, 480);
    CHECK(peak(o) == 0.f);
    e.add_track(1);
    e.play();
    o = render_all(e, 4800, 480);
    CHECK(peak(o) == 0.f);
    CHECK(e.position() == 4800);
}

static void test_clip_exact_playback_stereo_and_mono() {
    auto st = sine(9600, 2, 48000, 440, 0.4);
    auto mo = sine(9600, 1, 48000, 330, 0.3);
    std::string ps = wav("st", st, 2, 48000), pm = wav("mo", mo, 1, 48000);
    EngineCore e(lib(), 48000);
    e.add_track(1); e.add_track(2);
    CHECK(e.set_clip(clip(1, 1, ps, 0, 9600)) == 0);
    e.play();
    auto o = render_all(e, 9600, 333);
    double err = 0; for (int i = 0; i < 9600 * 2; ++i) err = std::max(err, (double)std::fabs(o[i] - st[i]));
    CHECK_MSG(err < 1e-6, "stereo clip error %g", err);
    // L and R differ in the source and must differ in the output (no mono downmix)
    CHECK(std::fabs(o[2 * 100] - o[2 * 100 + 1]) > 0.01f);

    EngineCore m(lib(), 48000);
    m.add_track(1);
    CHECK(m.set_clip(clip(1, 1, pm, 0, 9600)) == 0);
    m.play();
    o = render_all(m, 9600, 480);
    err = 0; for (int i = 0; i < 9600; ++i) err = std::max({err, (double)std::fabs(o[2 * i] - mo[i]), (double)std::fabs(o[2 * i + 1] - mo[i])});
    CHECK_MSG(err < 1e-6, "mono clip error %g", err);
}

static void test_clip_starting_mid_block_and_multi_clip() {
    auto a = sine(2000, 1, 48000, 500, 0.2), b = sine(2000, 1, 48000, 700, 0.2);
    std::string pa = wav("ca", a, 1, 48000), pb = wav("cb", b, 1, 48000);
    EngineCore e(lib(), 48000);
    e.add_track(1);
    e.set_clip(clip(1, 1, pa, 100, 2000));        // starts inside first 480-frame block
    e.set_clip(clip(2, 1, pb, 1000, 2000));       // overlaps first clip, and starts mid-block
    e.play();
    auto o = render_all(e, 4000, 480);
    double err = 0;
    for (int i = 0; i < 4000; ++i) {
        float ex = 0;
        if (i >= 100 && i < 2100) ex += a[i - 100];
        if (i >= 1000 && i < 3000) ex += b[i - 1000];
        err = std::max(err, (double)std::fabs(o[2 * i] - ex));
    }
    CHECK_MSG(err < 1e-6, "multi-clip error %g", err);
    CHECK(std::fabs(o[2 * 99]) == 0.f && std::fabs(o[2 * 3000 + 2 * 5]) == 0.f);
}

static void test_trim_offset() {
    auto a = sine(6000, 1, 48000, 440, 0.3);
    std::string pa = wav("trim", a, 1, 48000);
    EngineCore e(lib(), 48000);
    e.add_track(1);
    e.set_clip(clip(1, 1, pa, 500, 1000, 2000));   // plays source frames 2000..2999 at timeline 500
    e.play();
    auto o = render_all(e, 2000, 480);
    double err = 0;
    for (int i = 0; i < 2000; ++i) { float ex = (i >= 500 && i < 1500) ? a[2000 + i - 500] : 0.f; err = std::max(err, (double)std::fabs(o[2 * i] - ex)); }
    CHECK_MSG(err < 1e-6, "trim error %g", err);
}

static void test_block_size_independence() {
    auto a = sine(30000, 2, 48000, 440, 0.3), b = sine(30000, 1, 48000, 1234, 0.25);
    std::string pa = wav("bi_a", a, 2, 48000), pb = wav("bi_b", b, 1, 48000);
    auto build = [&](EngineCore& e) {
        e.add_track(1); e.add_track(2);
        e.set_clip(clip(1, 1, pa, 1000, 25000)); e.set_clip(clip(2, 2, pb, 7777, 20000, 3000));
        e.set_track_pan(2, -0.4f); e.set_track_volume(1, 0.8f);
        e.add_effect(1, "eq"); e.add_effect(2, "reverb"); e.add_effect(0, "compressor");
        e.set_loop(5000, 20001, true);
        e.set_metronome(true, 133.f, 4);
        e.play();
    };
    EngineCore ref(lib(), 48000);
    build(ref);
    auto o1 = render_all(ref, 60000, 480);
    for (int block : {1, 7, 64, 511, 512, 513, 1000, 4096}) {
        EngineCore e(lib(), 48000);
        build(e);
        auto o = render_all(e, 60000, block);
        double err = 0; for (size_t i = 0; i < o.size(); ++i) err = std::max(err, (double)std::fabs(o[i] - o1[i]));
        CHECK_MSG(err < 2e-5, "block %d differs from 480-block render by %g", block, err);
    }
}

static void test_pan_mute_solo() {
    auto a = sine(4800, 1, 48000, 440, 0.4);
    std::string pa = wav("pan", a, 1, 48000);
    EngineCore e(lib(), 48000);
    e.add_track(1); e.add_track(2);
    e.set_clip(clip(1, 1, pa, 0, 4800)); e.set_clip(clip(2, 2, pa, 0, 4800));
    e.set_track_mute(2, true);
    e.set_track_pan(1, -1.f);
    e.play();
    auto o = render_all(e, 4800, 480);
    float pl = 0, pr = 0; for (int i = 0; i < 4800; ++i) { pl = std::max(pl, std::fabs(o[2 * i])); pr = std::max(pr, std::fabs(o[2 * i + 1])); }
    CHECK(pr == 0.f);
    CHECK_NEAR(pl, 0.4, 1e-3);                       // muted track 2 contributes nothing
    // Solo track 2 only (centre) -> track 1 silenced
    e.set_track_mute(2, false); e.set_track_solo(2, true); e.seek(0);
    o = render_all(e, 4800, 480);
    pl = pr = 0; for (int i = 960; i < 4800; ++i) { pl = std::max(pl, std::fabs(o[2 * i])); pr = std::max(pr, std::fabs(o[2 * i + 1])); }
    CHECK_NEAR(pl, 0.4, 1e-3); CHECK_NEAR(pr, 0.4, 1e-3);
    // Unmute path: mute again then unmute must restore audio (regression for the 'cannot unmute' bug class)
    e.set_track_solo(2, false); e.set_track_mute(1, true); e.set_track_mute(2, true); e.seek(0);
    o = render_all(e, 2400, 480);
    o.erase(o.begin(), o.begin() + 960);                 // first 480 frames: click-free fade-out ramp
    CHECK(peak(o) < 1e-3f);
    e.set_track_mute(2, false); e.seek(0);
    o = render_all(e, 4800, 480);
    CHECK(peak(o) > 0.3f);
}

static void test_loop_wrap() {
    std::vector<float> ramp(10000); for (int i = 0; i < 10000; ++i) ramp[i] = 0.0001f * i;   // value encodes source frame
    std::string p = wav("ramp", ramp, 1, 48000);
    EngineCore e(lib(), 48000);
    e.add_track(1); e.set_clip(clip(1, 1, p, 0, 10000));
    e.set_loop(1000, 3000, true);
    e.seek(900); e.play();
    auto o = render_all(e, 5000, 480);
    int bad = 0;
    for (int i = 0; i < 5000; ++i) {
        int src = 900 + i; if (src >= 3000) src = 1000 + (src - 1000) % 2000;
        if (std::fabs(o[2 * i] - ramp[src]) > 1e-5) ++bad;
    }
    CHECK_MSG(bad == 0, "%d samples wrong around loop wrap", bad);
    CHECK(e.position() >= 1000 && e.position() < 3000);
}

static void test_sample_rate_conversion_pitch() {
    auto a = sine(44100, 1, 44100, 1000, 0.4);
    std::string p = wav("sr441", a, 1, 44100);
    EngineCore e(lib(), 48000);
    e.add_track(1); e.set_clip(clip(1, 1, p, 0, 48000));     // timeline frames @48k = 1 s
    e.play();
    auto o = render_all(e, 48000, 480);
    int first = -1, last = -1, cnt = 0;
    for (int i = 12000; i < 36000; ++i) if (o[2 * (i - 1)] < 0 && o[2 * i] >= 0) { if (first < 0) first = i; last = i; ++cnt; }
    double f = (cnt - 1) * 48000.0 / (last - first);
    std::printf("  44.1k file on 48k engine: tone %.2f Hz\n", f);
    CHECK_NEAR(f, 1000.0, 1.0);
    // And a 48k engine opened at 44.1k (timeline stays 48k): clip start/length scale in time.
    EngineCore e2(lib(), 44100);
    e2.add_track(1); e2.set_clip(clip(1, 1, p, 48000, 48000));   // starts at t = 1.0 s
    e2.play();
    auto o2 = render_all(e2, 44100 * 2, 441);
    int firstNonZero = -1; for (int i = 0; i < 44100 * 2; ++i) if (std::fabs(o2[2 * i]) > 1e-4f) { firstNonZero = i; break; }
    std::printf("  44.1k engine: clip begins at frame %d (expect ~44100)\n", firstNonZero);
    CHECK(std::abs(firstNonZero - 44100) < 50);
}

static void test_clipping_protection() {
    auto a = sine(4800, 1, 48000, 440, 0.95);
    std::string p = wav("loud", a, 1, 48000);
    EngineCore e(lib(), 48000);
    e.add_track(1); e.add_track(2); e.add_track(3);
    for (int t = 1; t <= 3; ++t) e.set_clip(clip(t, t, p, 0, 4800));
    e.play();
    auto o = render_all(e, 4800, 480);
    CHECK(peak(o) <= 1.0f);
    for (float x : o) CHECK(std::isfinite(x));
    CHECK(e.master_meters().clip_count > 0);
    CHECK(e.master_meters().peak_l <= 1.0f);
}

static void test_effects_in_engine_bypass_param() {
    auto a = sine(9600, 1, 48000, 440, 0.2);
    std::string p = wav("fx", a, 1, 48000);
    EngineCore e(lib(), 48000);
    e.add_track(1); e.set_clip(clip(1, 1, p, 0, 9600));
    int fx = e.add_effect(1, "gain");
    CHECK(fx > 0);
    EffectInfo info; CHECK(e.get_effect_info(fx, info));
    CHECK(info.type == "gain" && info.params.size() >= 1);
    CHECK(e.set_effect_param(fx, 0, 6.f));
    e.play();
    auto o = render_all(e, 2400, 480);
    CHECK_NEAR(peak(o), 0.4, 0.01);                    // +6 dB
    e.set_effect_bypass(fx, true);
    o = render_all(e, 2400, 480);
    CHECK_NEAR(peak(o), 0.2, 0.01);                    // bypassed
    e.set_effect_bypass(fx, false);
    e.set_effect_param(fx, 0, -6.f);
    o = render_all(e, 2400, 480);
    o.erase(o.begin(), o.begin() + 960);                 // skip the smoothing ramp block
    CHECK_NEAR(peak(o), 0.1, 0.01);
    CHECK(!e.set_effect_param(fx, 99, 1.f));
    CHECK(e.set_effect_param(fx, 0, 1000.f));           // clamped to the declared range
    e.get_effect_info(fx, info); CHECK(info.params[0].value <= 24.f);
    CHECK(e.remove_effect(fx));
    CHECK(e.list_effects(1).empty());
    CHECK(e.add_effect(1, "does_not_exist") < 0);
}

static void test_count_in_and_metronome() {
    auto a = sine(48000 * 4, 1, 48000, 440, 0.3);
    std::string p = wav("cnt", a, 1, 48000);
    EngineCore e(lib(), 48000);
    e.add_track(1); e.set_clip(clip(1, 1, p, 0, 48000 * 4));
    e.set_metronome(false, 120.f, 4);
    e.set_count_in_bars(1);                              // 4 beats @120 = 2 s = 96000 frames
    e.play();
    auto o = render_all(e, 96000 + 4800, 480);
    // During the count-in only clicks: peak limited and sparse; the clip begins at 96000.
    float cpeak = 0; for (int i = 0; i < 96000; ++i) cpeak = std::max(cpeak, std::fabs(o[2 * i]));
    CHECK(cpeak > 0.1f);                                 // clicks audible
    int clicks = 0; int last_hi = -100000;
    for (int i = 0; i < 96000; ++i) { if (std::fabs(o[2 * i]) > 0.05f) { if (i - last_hi > 4800) ++clicks; last_hi = i; } }
    std::printf("  count-in clicks detected: %d (expect 4)\n", clicks);
    CHECK(clicks == 4);
    CHECK(e.position() >= 4700 && e.position() <= 4900);
    // the clip started exactly where the transport started: first sample after count-in
    CHECK_NEAR(o[2 * 96000 + 2 * 10], a[10], 1e-3 + 0.0);
}

static void test_recording_alignment_and_content() {
    EngineCore e(lib(), 48000);
    e.add_track(1);
    std::string path = "/tmp/ws_eng_rec.wav";
    CHECK(e.start_recording(path, 1, 24));
    const int total = 48000, block = 480;
    auto in = sine(total, 1, 48000, 523.25, 0.5);
    e.seek(2000);
    e.play();
    std::vector<float> out(block * 2);
    for (int done = 0; done < total; done += block) render(e, out.data(), block, in.data() + done, 1);
    int64_t tl = -1;
    Recorder::Result r = e.stop_recording(&tl);
    CHECK(r.ok && r.dropped_frames == 0);
    CHECK(r.frames == total);
    CHECK(tl == 2000);                                  // timeline start = position when audio began
    WavError we; auto buf = WavReader::load(path, &we);
    CHECK(buf && buf->frames() == total && buf->channels() == 1);
    if (buf) { double err = 0; for (int i = 0; i < total; ++i) err = std::max(err, (double)std::fabs(buf->data()[i] - in[i])); CHECK_MSG(err < 2e-6, "rec err %g", err); }

    // latency compensation shifts the clip earlier
    e.set_input_latency_frames(480);
    CHECK(e.start_recording(path, 1, 24));
    e.seek(10000); e.play();
    for (int done = 0; done < 4800; done += block) render(e, out.data(), block, in.data() + done, 1);
    r = e.stop_recording(&tl);
    CHECK(tl == 10000 - 480);
    CHECK(r.frames == 4800);

    // transport stopped: nothing is captured, and no fake audio is produced
    e.pause(); render(e, out.data(), block, in.data(), 1);
    CHECK(e.start_recording(path, 1, 24));
    for (int k = 0; k < 10; ++k) render(e, out.data(), block, in.data(), 1);
    r = e.stop_recording(&tl);
    CHECK(r.frames == 0);

    // Punch in/out: only the punch range is written
    e.set_punch(true, 20000, 24800);
    CHECK(e.start_recording(path, 1, 24));
    e.seek(19000); e.play();
    for (int done = 0; done < 9600; done += block) render(e, out.data(), block, in.data() + done, 1);
    r = e.stop_recording(&tl);
    std::printf("  punch recorded %lld frames (expect 4800), start %lld\n", (long long)r.frames, (long long)tl);
    CHECK(r.frames == 4800);
    CHECK(tl == 20000 - 480);
}

static void test_input_monitor_and_input_meter() {
    EngineCore e(lib(), 48000);
    auto in = sine(480, 1, 48000, 440, 0.5);
    std::vector<float> out(960);
    e.set_monitor(true, 1.f);
    e.play();
    render(e, out.data(), 480, in.data(), 1);
    CHECK_NEAR(out[2 * 100], in[100], 1e-6);
    CHECK_NEAR(e.input_peak(), 0.5, 0.01);
    CHECK(e.input_peak() == 0.f);                        // read-and-reset
    e.set_monitor(false, 1.f);
    render(e, out.data(), 480, in.data(), 1);
    CHECK(peak(out) == 0.f);
}

static void test_input_conditioning() {
    auto sine_in = [](double f, double amp, int n) { std::vector<float> v(n); for (int i = 0; i < n; ++i) v[i] = (float)(amp * std::sin(2 * M_PI * f * i / 48000.0)); return v; };
    auto rms_of = [](const std::vector<float>& o, int from) { double s = 0; int c = 0; for (size_t i = from; i < o.size() / 2; ++i) { s += (double)o[2 * i] * o[2 * i]; ++c; } return std::sqrt(s / c); };
    auto run = [&](const std::vector<float>& in, float gdb, float hz, bool dc, bool* clipped = nullptr) {
        EngineCore e(lib(), 48000);
        e.set_monitor(true, 1.f); e.set_input_conditioning(gdb, hz, dc); e.play();
        std::vector<float> out(in.size() * 2), blk(960);
        for (size_t i = 0; i + 480 <= in.size(); i += 480) { render(e, blk.data(), 480, in.data() + i, 1); std::copy(blk.begin(), blk.end(), out.begin() + 2 * i); }
        if (clipped) *clipped = e.input_clip_count() > 0;
        return out;
    };
    const int n = 48000;
    auto tone = sine_in(1000, 0.2, n);
    double base = rms_of(run(tone, 0.f, 0.f, false), 4800);
    double g6 = rms_of(run(tone, 6.f, 0.f, false), 4800);
    CHECK_NEAR(g6 / base, 1.9953, 0.01);                                  // +6 dB
    double gm = rms_of(run(tone, -12.f, 0.f, false), 4800);
    CHECK_NEAR(gm / base, 0.2512, 0.01);
    // low-cut at 200 Hz: 50 Hz is attenuated by >= 20 dB, 2 kHz untouched
    auto lo = sine_in(50, 0.3, n), hi = sine_in(2000, 0.3, n);
    double lo0 = rms_of(run(lo, 0.f, 0.f, false), 9600), lo1 = rms_of(run(lo, 0.f, 200.f, false), 9600);
    double hi0 = rms_of(run(hi, 0.f, 0.f, false), 9600), hi1 = rms_of(run(hi, 0.f, 200.f, false), 9600);
    CHECK_MSG(20 * std::log10(lo0 / lo1) > 20.0, "50 Hz only %g dB down", 20 * std::log10(lo0 / lo1));
    CHECK_NEAR(hi1 / hi0, 1.0, 0.02);
    // DC offset: a constant 0.25 input decays to ~0 with DC removal; without it it stays
    std::vector<float> dcv(n, 0.25f);
    auto d0 = run(dcv, 0.f, 0.f, false), d1 = run(dcv, 0.f, 0.f, true);
    CHECK_NEAR(d0[2 * (n - 10)], 0.25, 1e-6);
    CHECK_MSG(std::fabs(d1[2 * (n - 10)]) < 0.005, "DC left %g", d1[2 * (n - 10)]);
    // clip counter: full-scale samples are counted, a clean signal is not; reset works
    bool clipped = false;
    run(sine_in(1000, 0.5, 4800), 0.f, 0.f, false, &clipped); CHECK(!clipped);
    run(sine_in(1000, 0.9, 4800), 6.f, 0.f, false, &clipped); CHECK(clipped);
    EngineCore e(lib(), 48000); std::vector<float> hot(480, 1.0f), out(960);
    e.set_monitor(true, 1.f); e.play(); render(e, out.data(), 480, hot.data(), 1);
    CHECK(e.input_clip_count() == 480); e.reset_input_clips(); CHECK(e.input_clip_count() == 0);
    // NaN/inf conditioning arguments are ignored safely
    e.set_input_conditioning(NAN, INFINITY, true); render(e, out.data(), 480, hot.data(), 1);
    for (float v : out) CHECK(std::isfinite(v));
}

static void test_offline_matches_live() {
    auto a = sine(40000, 2, 48000, 440, 0.3);
    std::string p = wav("off", a, 2, 48000);
    EngineCore live(lib(), 48000);
    live.add_track(1); live.set_clip(clip(1, 1, p, 500, 38000));
    live.add_effect(1, "delay"); live.add_effect(0, "limiter");
    live.set_track_pan(1, 0.3f);
    live.play();
    auto lo = render_all(live, 50000, 480);
    auto cl = live.clone(48000);
    std::vector<float> off(50000 * 2);
    CHECK(cl->render_offline(0, 50000, off.data(), 4096));
    double err = 0; for (size_t i = 0; i < off.size(); ++i) err = std::max(err, (double)std::fabs(off[i] - lo[i]));
    CHECK_MSG(err < 1e-5, "offline vs live differ by %g", err);
    // Export at a different engine rate keeps timing (positions are in timeline time).
    auto c2 = live.clone(44100);
    std::vector<float> o44(44100 * 2);
    c2->render_offline(0, 44100, o44.data());
    int firstNZ = -1; for (int i = 0; i < 44100; ++i) if (std::fabs(o44[2 * i]) > 1e-4f) { firstNZ = i; break; }
    // 459 frames of silence + the 2 ms (88 frame) look-ahead latency of the master limiter (no PDC yet).
    std::printf("  clone@44.1k: audio starts at frame %d (expect ~459 + 88 limiter latency)\n", firstNZ);
    CHECK(std::abs(firstNZ - (459 + 88)) < 12);
}

static void test_loudness_via_engine() {
    auto a = sine(48000 * 6, 2, 48000, 997, 0.1, 0);
    // identical channels so the stereo sum is +3 dB over a single channel => -20 LUFS needs 0.1 on both
    for (size_t i = 0; i < a.size(); i += 2) a[i + 1] = a[i];
    std::string p = wav("lufs", a, 2, 48000);
    EngineCore e(lib(), 48000);
    e.add_track(1); e.set_clip(clip(1, 1, p, 0, 48000 * 6));
    e.play();
    render_all(e, 48000 * 6, 480);
    float I = e.lufs_integrated();
    std::printf("  engine loudness: %.2f LUFS (expect -20)\n", I);
    CHECK_NEAR(I, -20.0, 0.3);
    CHECK_NEAR(e.correlation(), 1.0, 0.01);
}

static void test_no_allocation_in_render() {
    auto a = sine(48000, 2, 48000, 440, 0.3);
    std::string p = wav("alloc", a, 2, 48000);
    EngineCore e(lib(), 48000);
    e.add_track(1); e.add_track(2);
    e.set_clip(clip(1, 1, p, 0, 48000)); e.set_clip(clip(2, 2, p, 100, 48000));
    for (const auto& t : available_effect_types()) { e.add_effect(1, t); e.add_effect(0, t); }
    e.set_loop(0, 20000, true); e.set_metronome(true, 120, 4);
    e.start_recording("/tmp/ws_eng_alloc_rec.wav", 2, 24);
    e.play();
    std::vector<float> out(1920), in(1920, 0.1f);
    render(e, out.data(), 960, in.data(), 2);          // warm-up (consumes the pending snapshot)
    g_rt_allocs = 0;
    for (int i = 0; i < 400; ++i) {
        if (i % 50 == 0) { e.set_track_volume(1, 0.5f + 0.001f * i); }
        render(e, out.data(), 960, in.data(), 2);
    }
    long n = g_rt_allocs.load();
    std::printf("  heap operations inside render(): %ld\n", n);
    CHECK(n == 0);
    e.stop_recording();
}

static void test_missing_and_bad_media() {
    EngineCore e(lib(), 48000);
    e.add_track(1);
    CHECK(e.set_clip(clip(1, 1, "/does/not/exist.wav", 0, 1000)) == kMediaOpenFailed);
    FILE* f = fopen("/tmp/ws_eng_bad.wav", "wb"); fputs("RIFFxxxxWAVEjunkjunkjunk", f); fclose(f);
    int r = e.set_clip(clip(2, 1, "/tmp/ws_eng_bad.wav", 0, 1000));
    CHECK(r != 0 && r != kMediaTruncated);
    CHECK(e.set_clip(clip(3, 99, "/tmp/ws_eng_bad.wav", 0, 1000)) == -1);
    e.play();
    auto o = render_all(e, 960, 480);
    CHECK(peak(o) == 0.f);
}

// ---- aux send buses ----
static float rms_range(const std::vector<float>& v, int from, int to) {
    double a = 0; for (int i = from; i < to; ++i) a += (double)v[i * 2] * v[i * 2];
    return (float)std::sqrt(a / std::max(1, to - from));
}
static void test_aux_buses() {
    auto src = sine(48000, 2, 48000, 440, 0.2);
    std::string p = wav("bus", src, 2, 48000);
    auto setup = [&](EngineCore& e) { e.add_track(1); e.set_clip(clip(1, 1, p, 0, 48000)); e.play(); };
    // reference: no sends
    EngineCore ref(lib(), 48000); setup(ref);
    auto base = render_all(ref, 24000, 480);
    const float base_rms = rms_range(base, 4800, 24000);
    CHECK(base_rms > 0.1f);

    // send 0.5 into an empty bus chain: acts as a parallel dry path -> 1.5x
    EngineCore a(lib(), 48000); setup(a); a.set_track_send(1, 0, 0.5f);
    auto oa = render_all(a, 24000, 480);
    CHECK_NEAR(rms_range(oa, 4800, 24000) / base_rms, 1.5, 0.02);

    // a +6 dB gain effect on the bus, return level 0.5: 1 + 0.5 * 2 * 0.5 = 1.5
    EngineCore b(lib(), 48000); setup(b); b.set_track_send(1, 1, 0.5f);
    int g = b.add_effect(-2, "gain"); CHECK(g > 0);
    b.set_effect_param(g, 0, 6.0206f); b.set_bus_level(1, 0.5f);
    auto ob = render_all(b, 24000, 480);
    CHECK_NEAR(rms_range(ob, 8000, 24000) / base_rms, 1.5, 0.03);
    EffectInfo info; CHECK(b.get_effect_info(g, info) && info.track_id == -2);
    CHECK(b.list_effects(-2).size() == 1 && b.list_effects(-1).empty() && b.list_effects(-9).empty());

    // send 0 or muted track: bus is silent
    EngineCore c(lib(), 48000); setup(c); c.set_track_send(1, 0, 0.8f); c.set_track_send(1, 0, 0.f);
    auto oc = render_all(c, 24000, 480);
    CHECK_NEAR(rms_range(oc, 4800, 24000) / base_rms, 1.0, 0.005);
    EngineCore d(lib(), 48000); setup(d); d.set_track_send(1, 0, 1.f); d.set_track_mute(1, true);
    auto od = render_all(d, 24000, 480);
    CHECK(rms_range(od, 4800, 24000) < 1e-4f);

    // out-of-range buses are ignored, bad bus chain id is rejected
    a.set_track_send(1, 7, 1.f); a.set_track_send(1, -1, 1.f); a.set_bus_level(9, 1.f);
    CHECK(a.add_effect(-5, "gain") < 0);

    // reverb on a bus keeps ringing after the clip has ended (the dry track is silent by then)
    EngineCore r(lib(), 48000); setup(r); r.set_track_send(1, 0, 1.f);
    int rv = r.add_effect(-1, "reverb"); CHECK(rv > 0);
    r.set_effect_param(rv, 2, 1.f); r.set_effect_param(rv, 3, 0.f);                     // wet only
    auto orv = render_all(r, 48000 + 48000, 480);                                          // 1 s clip + 1 s after
    CHECK(rms_range(orv, 48000 + 2400, 48000 + 24000) > 0.005f);
    EngineCore nr(lib(), 48000); setup(nr);
    auto onr = render_all(nr, 96000, 480);
    CHECK(rms_range(onr, 48000 + 2400, 96000) < 1e-5f);

    // offline clone keeps sends, bus effects and levels: same signal as the live engine would produce
    EngineCore live(lib(), 48000); live.add_track(1); live.set_clip(clip(1, 1, p, 0, 48000));
    live.set_track_send(1, 2, 0.7f); live.set_bus_level(2, 0.6f);
    int lg = live.add_effect(-3, "gain"); live.set_effect_param(lg, 0, -3.f);
    auto cl = live.clone(48000.f); cl->prepare_offline();
    std::vector<float> off(48000 * 2), lv(48000 * 2);
    CHECK(cl->render_offline(0, 48000, off.data()));
    live.prepare_offline(); CHECK(live.render_offline(0, 48000, lv.data()));
    double diff = 0; for (size_t i = 0; i < off.size(); ++i) diff = std::max(diff, (double)std::fabs(off[i] - lv[i]));
    CHECK_MSG(diff < 1e-5, "clone differs by %g", diff);
    EngineCore plain(lib(), 48000); setup(plain);
    auto op = render_all(plain, 48000, 480);
    CHECK(rms_range(off, 4800, 40000) > rms_range(op, 4800, 40000) * 1.25f);         // expected ~1.30x: the send (0.7 * 0.6 * -3 dB) is audible in the clone
}

int main() {
    RUN(test_silence_and_idle);
    RUN(test_clip_exact_playback_stereo_and_mono);
    RUN(test_clip_starting_mid_block_and_multi_clip);
    RUN(test_trim_offset);
    RUN(test_block_size_independence);
    RUN(test_pan_mute_solo);
    RUN(test_loop_wrap);
    RUN(test_sample_rate_conversion_pitch);
    RUN(test_clipping_protection);
    RUN(test_effects_in_engine_bypass_param);
    RUN(test_count_in_and_metronome);
    RUN(test_recording_alignment_and_content);
    RUN(test_input_monitor_and_input_meter);
    RUN(test_input_conditioning);
    RUN(test_offline_matches_live);
    RUN(test_loudness_via_engine);
    RUN(test_missing_and_bad_media);
    RUN(test_aux_buses);
    RUN(test_no_allocation_in_render);
    TEST_MAIN_END();
}

#include "test_util.h"
#include <vector>
#include <atomic>
#include <thread>
#include "engine/engine_core.h"
#include "engine/offline_export.h"
#include "file_io/wav_reader.h"

using namespace wavestudio;

static std::vector<float> sine(int frames, int ch, double sr, double hz, double amp) {
    std::vector<float> v((size_t)frames * ch);
    for (int i = 0; i < frames; ++i) for (int c = 0; c < ch; ++c) v[(size_t)i * ch + c] = (float)(amp * std::sin(2 * M_PI * hz * i / sr));
    return v;
}
static std::string mk(const char* n, const std::vector<float>& d, int ch, int sr) {
    std::string p = std::string("/tmp/ws_exp_") + n + ".wav";
    WavWriter::save_interleaved(p, d.data(), ch, (int64_t)d.size() / ch, (float)sr, 32); return p;
}

static void test_export_formats() {
    auto a = sine(48000 * 2, 2, 48000, 997, 0.1);
    std::string p = mk("src", a, 2, 48000);
    EngineCore core(std::make_shared<MediaLibrary>(), 48000);
    core.add_track(1);
    ClipParams c; c.id = 1; c.track_id = 1; c.path = p; c.start = 24000; c.length = 96000;
    CHECK(core.set_clip(c) == 0);
    for (int sr : {44100, 48000, 96000}) for (int bits : {16, 24, 32}) for (int ch : {1, 2}) {
        ExportOptions o; o.path = "/tmp/ws_exp_out.wav"; o.sample_rate = sr; o.bit_depth = bits; o.channels = ch; o.tail_seconds = 1.f;
        ExportResult r = export_project(core, o);
        CHECK_MSG(r.ok, "sr %d bits %d ch %d err %d", sr, bits, ch, r.error);
        if (!r.ok) continue;
        WavInfo info; CHECK(WavReader::read_info(o.path, info) == WavError::Ok);
        CHECK(info.channels == ch && (int)info.sample_rate == sr && info.bit_depth == bits);
        // 0.5 s lead-in + 2 s of audio (+ <=0.5 s trailing silence kept) 
        double secs = (double)info.frames / sr;
        CHECK_MSG(secs >= 2.5 && secs <= 3.6, "length %.3f s", secs);
        auto buf = WavReader::load(o.path);
        CHECK(buf != nullptr);
        if (buf) {
            // tone must be at 997 Hz in the file, at the right amplitude and start time
            const float* d = buf->data().data(); int bc = buf->channels();
            int start = -1; for (int64_t i = 0; i < buf->frames(); ++i) if (std::fabs(d[i * bc]) > 0.01f) { start = (int)i; break; }
            double expect_start = 0.5 * sr;
            CHECK_MSG(std::fabs(start - expect_start) < 0.002 * sr, "start %d vs %.0f", start, expect_start);
            float pk = 0; for (int64_t i = 0; i < buf->frames(); ++i) pk = std::max(pk, std::fabs(d[i * bc]));
            double want = ch == 1 ? 0.1 : 0.1;               // mono down-mix of identical channels keeps level
            CHECK_NEAR(pk, want, 0.01);
        }
        CHECK(std::fopen("/tmp/ws_exp_out.wav.part", "rb") == nullptr);   // temp file renamed away
        if (sr == 48000 && bits == 24 && ch == 2) {
            std::printf("  export 48k/24/stereo: %lld frames, peak %.2f dBFS, %.2f LUFS\n", (long long)r.frames, r.peak_dbfs, r.lufs);
            CHECK_NEAR(r.lufs, -23.0 + 0.0, 4.0);     // -20 dBFS sine for 2 of 2.5 s: gating keeps the loud part => ~-20
            CHECK_NEAR(r.peak_dbfs, -20.0, 0.5);
        }
    }
}

static void test_export_errors_and_cancel() {
    EngineCore core(std::make_shared<MediaLibrary>(), 48000);
    ExportOptions o; o.path = "/tmp/ws_exp_empty.wav";
    CHECK(export_project(core, o).error == 1);       // empty project
    core.add_track(1);
    auto a = sine(48000 * 30, 1, 48000, 440, 0.2);
    std::string p = mk("long", a, 1, 48000);
    ClipParams c; c.id = 1; c.track_id = 1; c.path = p; c.length = 48000 * 30;
    core.set_clip(c);
    o.sample_rate = 12345678; CHECK(export_project(core, o).error == 2);
    o.sample_rate = 48000; o.bit_depth = 12; CHECK(export_project(core, o).error == 2);
    o.bit_depth = 24; o.path = "/nonexistent_dir/x.wav"; CHECK(export_project(core, o).error == 3);
    // cancel mid-way
    o.path = "/tmp/ws_exp_cancel.wav"; std::remove(o.path.c_str());
    std::atomic<bool> cancel{false}; std::atomic<float> prog{0};
    std::thread t([&] { while (prog.load() < 0.1f) std::this_thread::yield(); cancel = true; });
    ExportResult r = export_project(core, o, &prog, &cancel);
    t.join();
    CHECK(r.cancelled && !r.ok);
    CHECK(std::fopen("/tmp/ws_exp_cancel.wav", "rb") == nullptr);
    CHECK(std::fopen("/tmp/ws_exp_cancel.wav.part", "rb") == nullptr);
}

int main() {
    RUN(test_export_formats);
    RUN(test_export_errors_and_cancel);
    TEST_MAIN_END();
}

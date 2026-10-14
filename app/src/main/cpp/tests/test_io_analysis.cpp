#include "test_util.h"
#include <vector>
#include <cstdio>
#include <string>
#include <algorithm>
#include "buffer.h"
#include "file_io/wav_reader.h"
#include "file_io/wav_stream_writer.h"
#include "resampler/resampler.h"
#include "analysis/lufs.h"
#include "analysis/spectrum.h"

using namespace wavestudio;

static std::string tmp(const char* n) { return std::string("/tmp/ws_test_") + n; }

static std::vector<float> sine(int frames, int ch, double sr, double hz, double amp) {
    std::vector<float> v((size_t)frames * ch);
    for (int i = 0; i < frames; ++i)
        for (int c = 0; c < ch; ++c) v[(size_t)i * ch + c] = (float)(amp * std::sin(2 * M_PI * hz * i / sr));
    return v;
}

static void test_wav_roundtrip() {
    for (int bits : {16, 24, 32}) {
        auto src = sine(10000, 2, 44100, 440, 0.8);
        std::string p = tmp("rt.wav");
        CHECK(WavWriter::save_interleaved(p, src.data(), 2, 10000, 44100.f, bits));
        WavError e = WavError::Ok;
        auto buf = WavReader::load(p, &e);
        CHECK(buf != nullptr && e == WavError::Ok);
        if (!buf) continue;
        CHECK(buf->channels() == 2 && buf->frames() == 10000);
        CHECK_NEAR(buf->sample_rate(), 44100.0, 0.5);
        double tol = bits == 16 ? 1.0 / 16000 : (bits == 24 ? 1e-6 : 1e-7);
        double worst = 0;
        for (size_t i = 0; i < src.size(); ++i) worst = std::max(worst, (double)std::fabs(buf->data()[i] - src[i]));
        CHECK_MSG(worst <= tol, "bits=%d worst err %g", bits, worst);
    }
}

static void test_wav_truncated_and_garbage() {
    auto src = sine(48000, 2, 48000, 220, 0.5);
    std::string p = tmp("trunc.wav");
    WavWriter::save_interleaved(p, src.data(), 2, 48000, 48000.f, 16);
    // Chop the file in half: header now over-states the data size (what an interrupted recording looks like).
    FILE* f = fopen(p.c_str(), "rb"); std::vector<char> bytes(44 + 48000 * 4); size_t got = fread(bytes.data(), 1, bytes.size(), f); fclose(f);
    CHECK(got == bytes.size());
    f = fopen(p.c_str(), "wb"); fwrite(bytes.data(), 1, 44 + 24000 * 4, f); fclose(f);
    WavError e = WavError::Ok;
    auto buf = WavReader::load(p, &e);
    CHECK(e == WavError::Truncated);
    CHECK(buf != nullptr);
    if (buf) CHECK(buf->frames() == 24000);
    // Garbage
    f = fopen(p.c_str(), "wb"); const char junk[] = "this is not a wav file at all, just text........."; fwrite(junk, 1, sizeof junk, f); fclose(f);
    auto b2 = WavReader::load(p, &e);
    CHECK(b2 == nullptr && e == WavError::NotRiffWave);
    b2 = WavReader::load("/nonexistent/dir/x.wav", &e);
    CHECK(b2 == nullptr && e == WavError::OpenFailed);
    // Memory budget
    WavWriter::save_interleaved(p, src.data(), 2, 48000, 48000.f, 16);
    b2 = WavReader::load(p, &e, 1000);
    CHECK(b2 == nullptr && e == WavError::TooLarge);
}

static void test_stream_writer_checkpoint_recovery() {
    // Simulate a crash: write, checkpoint, write more, then read the file WITHOUT close().
    std::string p = tmp("crash.wav");
    WavStreamWriter w;
    CHECK(w.open(p, 1, 48000, 16, false));
    auto a = sine(48000, 1, 48000, 300, 0.5);
    CHECK(w.write(a.data(), 48000));
    CHECK(w.checkpoint());
    CHECK(w.write(a.data(), 12000));        // not yet checkpointed
    fflush(nullptr);
    WavError e;
    auto buf = WavReader::load(p, &e);      // file is still open in the writer
    CHECK(buf != nullptr);
    if (buf) {
        std::printf("  recovered %lld frames after 'crash' (>= 48000 checkpointed)\n", (long long)buf->frames());
        CHECK(buf->frames() >= 48000);
    }
    w.close();
}

static double estimate_freq(const float* x, int n, double sr) {   // positive-going zero crossings
    int first = -1, last = -1, cnt = 0;
    for (int i = 1; i < n; ++i) if (x[i - 1] < 0 && x[i] >= 0) { if (first < 0) first = i; last = i; ++cnt; }
    if (cnt < 2) return 0;
    return (cnt - 1) * sr / (last - first);
}

static void test_resampler() {
    for (auto pr : {std::pair<float, float>{44100, 48000}, {48000, 44100}, {96000, 48000}, {22050, 48000}}) {
        const double hz = 1000.0;
        auto src = std::make_shared<AudioBuffer>();
        int n = (int)pr.first;   // 1 s
        src->allocate(1, n, pr.first);
        auto s = sine(n, 1, pr.first, hz, 0.5);
        std::copy(s.begin(), s.end(), src->data_mut().begin());
        auto out = resample_buffer(src, pr.second);
        CHECK(out != nullptr);
        if (!out) continue;
        CHECK_NEAR(out->sample_rate(), pr.second, 0.5);
        CHECK(std::llabs(out->frames() - (int64_t)pr.second) <= 2);
        const float* d = out->data().data();
        int mid = (int)(out->frames() / 4), len = (int)(out->frames() / 2);
        double f = estimate_freq(d + mid, len, pr.second);
        float pk = 0; for (int i = mid; i < mid + len; ++i) pk = std::max(pk, std::fabs(d[i]));
        std::printf("  %g -> %g: tone %.2f Hz, peak %.4f\n", pr.first, pr.second, f, pk);
        CHECK_NEAR(f, hz, 1.0);
        CHECK_NEAR(pk, 0.5, 0.01);
    }
    // Aliasing: 20 kHz tone downsampled 96k -> 32k must be removed (above new Nyquist 16k).
    auto src = std::make_shared<AudioBuffer>(); src->allocate(1, 96000, 96000.f);
    auto s = sine(96000, 1, 96000, 20000, 0.5); std::copy(s.begin(), s.end(), src->data_mut().begin());
    auto out = resample_buffer(src, 32000.f);
    float pk = 0; for (int64_t i = 8000; i < out->frames() - 8000; ++i) pk = std::max(pk, std::fabs(out->data()[i]));
    std::printf("  anti-alias residual peak %.5f\n", pk);
    CHECK(pk < 0.01f);
}

static void test_lufs() {
    // BS.1770: stereo 997 Hz sine at -20 dBFS (both channels) reads -20.0 LUFS; one channel only reads -23.0.
    for (float sr : {44100.f, 48000.f}) {
        LufsMeter m; m.set_sample_rate(sr);
        auto st = sine((int)(sr * 8), 2, sr, 997, 0.1);       // 0.1 = -20 dBFS
        m.process_interleaved(st.data(), (int)(sr * 8), 2);
        std::printf("  stereo -20 dBFS sine @%g: I=%.2f M=%.2f S=%.2f LUFS\n", sr, m.integrated(), m.momentary(), m.short_term());
        CHECK_NEAR(m.integrated(), -20.0, 0.15);
        CHECK_NEAR(m.momentary(), -20.0, 0.15);
        LufsMeter m2; m2.set_sample_rate(sr);
        for (size_t i = 1; i < st.size(); i += 2) st[i] = 0;
        m2.process_interleaved(st.data(), (int)(sr * 8), 2);
        CHECK_NEAR(m2.integrated(), -23.0, 0.15);
    }
    // Gating: 4 s of -20 dB sine then 10 s of silence -> integrated ignores the silence.
    LufsMeter g; g.set_sample_rate(48000);
    auto a = sine(48000 * 4, 2, 48000, 997, 0.1); g.process_interleaved(a.data(), 48000 * 4, 2);
    std::vector<float> z(48000 * 10 * 2, 0.f); g.process_interleaved(z.data(), 48000 * 10, 2);
    CHECK_NEAR(g.integrated(), -20.0, 0.2);
    CHECK(g.momentary() < -100.f);
}

static void test_spectrum_bin() {
    SpectrumAnalyzer sp; sp.init(2048, 48000.f);
    auto s = sine(48000, 1, 48000, 1000, 0.5);
    sp.push(s.data(), 48000, 1);
    const int bins = 256;
    std::vector<float> out(bins);
    sp.get_magnitudes(out.data(), bins);
    int best = (int)(std::max_element(out.begin(), out.end()) - out.begin());
    // log mapping 20 Hz .. 24 kHz
    double f = 20.0 * std::pow(24000.0 / 20.0, (double)best / (bins - 1));
    std::printf("  spectrum peak at %.0f Hz, %.1f dB\n", f, out[best]);
    CHECK(f > 850 && f < 1180);
    CHECK_NEAR(out[best], -6.0, 2.0);    // 0.5 amplitude = -6 dBFS
}

int main() {
    RUN(test_wav_roundtrip);
    RUN(test_wav_truncated_and_garbage);
    RUN(test_stream_writer_checkpoint_recovery);
    RUN(test_resampler);
    RUN(test_lufs);
    RUN(test_spectrum_bin);
    TEST_MAIN_END();
}

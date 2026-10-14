#include "offline_export.h"
#include "../file_io/wav_stream_writer.h"
#include "../file_io/flac_stream_writer.h"
#include "../analysis/lufs.h"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

namespace wavestudio {

ExportResult export_project(EngineCore& live, const ExportOptions& opt,
                            std::atomic<float>* progress, const std::atomic<bool>* cancel) {
    ExportResult res;
    if (opt.sample_rate < 8000 || opt.sample_rate > 192000 || (opt.bit_depth != 16 && opt.bit_depth != 24 && opt.bit_depth != 32) ||
        (opt.channels != 1 && opt.channels != 2) || opt.path.empty() ||
        (opt.format != 0 && opt.format != 1) || (opt.format == 1 && opt.bit_depth == 32)) {
        res.error = 2; return res;
    }
    const int64_t end_tl = live.project_end();
    if (end_tl <= 0) { res.error = 1; return res; }

    auto core = live.clone((float)opt.sample_rate);
    core->prepare_offline();
    const double ratio = (double)opt.sample_rate / (double)core->timeline_rate();
    const int64_t body = (int64_t)std::llround((double)end_tl * ratio);
    const int64_t max_total = body + (int64_t)(std::max(0.f, opt.tail_seconds) * opt.sample_rate);

    const std::string tmp = opt.path + ".part";
    // Both writers share the same small interface; only one is opened.
    WavStreamWriter wav; FlacStreamWriter flac;
    const bool use_flac = opt.format == 1;
    const bool dither16 = opt.dither && opt.bit_depth == 16;
    if (use_flac ? !flac.open(tmp, opt.channels, opt.sample_rate, opt.bit_depth, dither16)
                 : !wav.open(tmp, opt.channels, opt.sample_rate, opt.bit_depth, dither16)) { res.error = 3; return res; }
    auto w_write = [&](const float* d, int64_t n) { return use_flac ? flac.write(d, n) : wav.write(d, n); };

    LufsMeter lufs; lufs.set_sample_rate((float)opt.sample_rate);
    core->seek(0);
    core->play();
    const int kChunk = 4096;
    std::vector<float> st(kChunk * 2), mono(kChunk);
    int64_t done = 0;
    int64_t quiet = 0;                        // consecutive near-silent frames after the body
    const int64_t quiet_needed = opt.sample_rate / 2;
    bool failed = false;
    while (done < max_total) {
        if (cancel && cancel->load()) { res.cancelled = true; break; }
        const int n = (int)std::min<int64_t>(kChunk, max_total - done);
        core->render(st.data(), n, nullptr, 0);
        float pk = 0.f;
        for (int i = 0; i < n * 2; ++i) pk = std::max(pk, std::fabs(st[i]));
        int used = n;
        // After the project body, stop as soon as the tail has decayed to silence.
        if (done >= body) {
            if (pk < 3e-5f) { quiet += n; if (quiet >= quiet_needed) { used = std::max(0, n - (int)(quiet - quiet_needed)); } }
            else quiet = 0;
        }
        lufs.process_interleaved(st.data(), used, 2);
        for (int i = 0; i < used * 2; ++i) {
            const float a = std::fabs(st[i]);
            if (a >= 0.9999f) ++res.clipped_samples;
            res.peak_dbfs = std::max(res.peak_dbfs, a > 1e-6f ? 20.f * std::log10(a) : -120.f);
        }
        const float* data = st.data();
        if (opt.channels == 1) {
            for (int i = 0; i < used; ++i) mono[i] = 0.5f * (st[2 * i] + st[2 * i + 1]);
            data = mono.data();
        }
        if (used > 0 && !w_write(data, used)) { failed = true; break; }
        done += used;
        if (progress) progress->store(std::min(1.f, (float)((double)done / (double)std::max<int64_t>(1, body))));
        if (used < n) break;
    }
    const bool closed = use_flac ? flac.close() : wav.close();
    const bool had_err = use_flac ? flac.had_error() : wav.had_error();
    if (res.cancelled) { std::remove(tmp.c_str()); return res; }
    if (failed || !closed || had_err) { std::remove(tmp.c_str()); res.error = 4; return res; }
    if (std::rename(tmp.c_str(), opt.path.c_str()) != 0) { std::remove(tmp.c_str()); res.error = 4; return res; }
    res.frames = done;
    res.lufs = lufs.integrated();
    res.ok = true;
    if (progress) progress->store(1.f);
    return res;
}

} // namespace wavestudio

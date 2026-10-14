// Concurrency stress: one "audio" thread renders flat out while a "UI" thread edits the model
// randomly. Run under ThreadSanitizer and AddressSanitizer: any data race, use-after-free or
// non-finite/out-of-range sample fails the test.
#include "test_util.h"
#include <atomic>
#include <thread>
#include <random>
#include <vector>
#include "engine/engine_core.h"
#include "effects/effect_factory.h"
#include "file_io/wav_reader.h"

using namespace wavestudio;

int main() {
    setvbuf(stdout, nullptr, _IONBF, 0);
    std::vector<float> a(48000 * 3 * 2);
    for (size_t i = 0; i < a.size() / 2; ++i) { float s = 0.4f * std::sin(0.06f * i); a[2 * i] = s; a[2 * i + 1] = -s; }
    const char* paths[2] = {"/tmp/ws_stress_a.wav", "/tmp/ws_stress_b.wav"};
    WavWriter::save_interleaved(paths[0], a.data(), 2, 48000 * 3, 48000.f, 16);
    WavWriter::save_interleaved(paths[1], a.data(), 2, 48000 * 3, 44100.f, 24);   // needs resampling

    EngineCore core(std::make_shared<MediaLibrary>(), 48000);
    std::atomic<bool> stop{false};
    std::atomic<long> bad{0}, blocks{0};

    std::thread audio([&] {
        std::vector<float> out(480 * 2), in(480 * 2);
        std::mt19937 r(1);
        while (!stop.load()) {
            for (auto& x : in) x = ((int)(r() % 2000) - 1000) / 2000.f;
            core.render(out.data(), 480, in.data(), 2);
            for (float x : out) if (!std::isfinite(x) || std::fabs(x) > 1.0f) bad++;
            blocks++;
        }
    });

    std::mt19937 rng(42);
    auto types = available_effect_types();
    std::vector<int> fx_ids;
    int next_clip = 1;
    const int iterations = getenv("ITER") ? atoi(getenv("ITER")) : 4000;
    for (int it = 0; it < iterations; ++it) {
        int op = rng() % 24;
        int track = 1 + rng() % 6;
        if (getenv("VERBOSE2")) std::printf("op %d track %d\n", op, track);
        switch (op) {
            case 0: core.add_track(track); break;
            case 1: if (rng() % 4 == 0) core.remove_track(track); break;
            case 2: case 3: {
                ClipParams c; c.id = next_clip++ % 40 + 1; c.track_id = track; c.path = paths[rng() % 2];
                c.start = rng() % 100000; c.length = 1000 + rng() % 90000; c.offset = rng() % 20000;
                c.fade_in = rng() % 500; c.fade_out = rng() % 500; c.gain_db = ((int)(rng() % 24)) - 12;
                core.set_clip(c);
                break; }
            case 4: core.remove_clip(1 + rng() % 40); break;
            case 5: { int where = rng() % 3 == 0 ? 0 : (rng() % 4 == 0 ? -(int)(1 + rng() % 4) : track); int id = core.add_effect(where, types[rng() % types.size()]); if (id > 0) fx_ids.push_back(id); break; }
            case 6: if (!fx_ids.empty()) { size_t k = rng() % fx_ids.size(); core.remove_effect(fx_ids[k]); fx_ids.erase(fx_ids.begin() + k); } break;
            case 7: case 8: case 9: case 10: if (!fx_ids.empty()) {
                int id = fx_ids[rng() % fx_ids.size()]; EffectInfo inf;
                if (core.get_effect_info(id, inf) && !inf.params.empty()) {
                    int pi = rng() % inf.params.size();
                    const auto& p = inf.params[pi];
                    core.set_effect_param(id, pi, p.min + (rng() % 1001) / 1000.f * (p.max - p.min));
                } } break;
            case 11: if (!fx_ids.empty()) core.set_effect_bypass(fx_ids[rng() % fx_ids.size()], rng() & 1); break;
            case 12: if (!fx_ids.empty()) core.move_effect(fx_ids[rng() % fx_ids.size()], rng() % 4); break;
            case 13: core.set_track_volume(track, (rng() % 300) / 100.f); break;
            case 14: core.set_track_pan(track, ((int)(rng() % 201) - 100) / 100.f); break;
            case 15: core.set_track_mute(track, rng() & 1); core.set_track_solo(track, rng() % 5 == 0); break;
            case 16: core.seek(rng() % 150000); break;
            case 17: if (rng() & 1) core.play(); else core.pause(); break;
            case 18: core.set_loop(rng() % 20000, 20000 + rng() % 60000, rng() & 1); break;
            case 19: core.set_metronome(rng() & 1, 60 + rng() % 120, 3 + rng() % 3); core.set_count_in_bars(rng() % 2); break;
            case 20: {
                if (core.is_recording()) core.stop_recording(); else core.start_recording("/tmp/ws_stress_rec.wav", 1 + rng() % 2, 24);
                break; }
            case 22: core.set_track_send(track, rng() % 4, (rng() % 150) / 100.f); break;
            case 23: core.set_bus_level(rng() % 4, (rng() % 200) / 100.f); break;
            case 21: core.master_meters(); core.lufs_integrated(); { float b[64]; core.get_spectrum(b, 64); } float l, r; core.track_meters(track, &l, &r); break;
        }
        if (it % 200 == 0) { std::this_thread::yield(); if (getenv("VERBOSE")) std::printf("it %d blocks %ld\n", it, blocks.load()); }
    }
    if (core.is_recording()) core.stop_recording();
    stop = true;
    audio.join();
    std::printf("  %ld blocks rendered during %d random edits, %ld bad samples\n", blocks.load(), iterations, bad.load());
    CHECK(bad == 0);
    CHECK(blocks > 100);
    TEST_MAIN_END();
}

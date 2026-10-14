#include "file_io/flac_stream_writer.h"
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <vector>
using namespace wavestudio;
// Test tool: writes a synthetic FLAC file (see scripts/verify_flac.py).
// usage: flac_tool out.flac channels bits frames mode(0 sine,1 noise,2 silence,3 square)
int main(int, char** argv) {
    int ch = atoi(argv[2]), bits = atoi(argv[3]); long frames = atol(argv[4]); int mode = atoi(argv[5]);
    FlacStreamWriter w; if (!w.open(argv[1], ch, 44100, bits, false)) return 1;
    std::vector<float> buf(4000 * ch); long done = 0; uint32_t r = 1;
    while (done < frames) {
        int n = (int)std::min<long>(4000, frames - done);
        for (int i = 0; i < n; ++i) for (int c = 0; c < ch; ++c) {
            double t = (done + i) / 44100.0; float v;
            r = r * 1664525u + 1013904223u;
            if (mode == 0) v = 0.5f * sinf(6.2831853f * 440.f * t + c);
            else if (mode == 1) v = ((r >> 8) / 8388608.f - 1.f);           // full-scale noise
            else if (mode == 2) v = 0.f;                                       // silence
            else v = (i % 700 < 350) ? 1.f : -1.f;                             // square, clips at extremes
            buf[i * ch + c] = v;
        }
        w.write(buf.data(), n); done += n;
    }
    return w.close() ? 0 : 2;
}

#include "flac_stream_writer.h"
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>

namespace wavestudio {
namespace {

// ---- CRCs
uint8_t crc8(const uint8_t* p, size_t n) {
    uint8_t c = 0;
    for (size_t i = 0; i < n; ++i) { c ^= p[i]; for (int k = 0; k < 8; ++k) c = (c & 0x80) ? (uint8_t)((c << 1) ^ 0x07) : (uint8_t)(c << 1); }
    return c;
}
uint16_t crc16(const uint8_t* p, size_t n) {
    static uint16_t table[256]; static bool init = false;
    if (!init) {
        for (int i = 0; i < 256; ++i) { uint16_t c = (uint16_t)(i << 8); for (int k = 0; k < 8; ++k) c = (c & 0x8000) ? (uint16_t)((c << 1) ^ 0x8005) : (uint16_t)(c << 1); table[i] = c; }
        init = true;
    }
    uint16_t c = 0;
    for (size_t i = 0; i < n; ++i) c = (uint16_t)((c << 8) ^ table[(c >> 8) ^ p[i]]);
    return c;
}

// ---- bit writer (MSB first)
struct Bits {
    std::vector<uint8_t> v; uint64_t acc = 0; int n = 0;
    void put(uint64_t value, int bits) {          // bits <= 32
        if (bits == 0) return;
        acc = (acc << bits) | (value & ((bits == 64) ? ~0ull : ((1ull << bits) - 1)));
        n += bits;
        while (n >= 8) { v.push_back((uint8_t)(acc >> (n - 8))); n -= 8; }
        acc &= (n ? ((1ull << n) - 1) : 0);
    }
    void put_signed(int64_t value, int bits) { put((uint64_t)value & ((1ull << bits) - 1), bits); }
    void unary_zero_run(uint64_t q) {             // q zeros then a one
        while (q >= 32) { put(0, 32); q -= 32; }
        put(1, (int)q + 1);
    }
    void align() { if (n) put(0, 8 - n); }
    size_t bit_count() const { return v.size() * 8 + n; }
};

inline uint64_t zigzag(int64_t r) { return ((uint64_t)r << 1) ^ (uint64_t)(r >> 63); }

// Cost in bits of Rice-coding u[0..n) with parameter k.
uint64_t rice_cost(const uint64_t* u, int n, int k) {
    uint64_t c = (uint64_t)n * (uint64_t)(k + 1);
    for (int i = 0; i < n; ++i) c += (u[i] >> k);
    return c;
}

struct Residual {
    std::vector<int64_t> r;       // residual samples (after warm-up)
    int order = 0;
};

// Encoded subframe candidates are compared by size; encode returns the bit buffer.
struct Sub { Bits bits; };

void write_residual(Bits& b, const std::vector<int64_t>& res, int block_n, int order) {
    // Choose partition order minimising size; coding method 1 (5-bit parameters).
    const int count = (int)res.size();
    std::vector<uint64_t> u(count);
    for (int i = 0; i < count; ++i) u[i] = zigzag(res[i]);
    int best_po = 0; uint64_t best_cost = ~0ull;
    std::vector<int> best_k;
    for (int po = 0; po <= 8; ++po) {
        const int parts = 1 << po;
        if (block_n % parts != 0) break;
        const int psize = block_n / parts;
        if (psize <= order) break;
        std::vector<int> ks(parts);
        uint64_t total = 0;
        int pos = 0;
        for (int p = 0; p < parts; ++p) {
            const int len = psize - (p == 0 ? order : 0);
            uint64_t sum = 0;
            for (int i = 0; i < len; ++i) sum += u[pos + i];
            int k0 = 0;
            if (len > 0 && sum > 0) { const uint64_t mean = sum / (uint64_t)len; while (k0 < 30 && (1ull << (k0 + 1)) <= mean + 1) ++k0; }
            uint64_t bc = ~0ull; int bk = 0;
            for (int k = std::max(0, k0 - 1); k <= std::min(30, k0 + 1); ++k) {
                const uint64_t c = rice_cost(u.data() + pos, len, k);
                if (c < bc) { bc = c; bk = k; }
            }
            ks[p] = bk; total += bc + 5; pos += len;
        }
        if (total < best_cost) { best_cost = total; best_po = po; best_k = ks; }
    }
    b.put(1, 2);                      // coding method: 5-bit Rice parameter
    b.put((uint64_t)best_po, 4);
    const int parts = 1 << best_po;
    const int psize = block_n / parts;
    int pos = 0;
    for (int p = 0; p < parts; ++p) {
        const int len = psize - (p == 0 ? order : 0);
        const int k = best_k[p];
        b.put((uint64_t)k, 5);
        for (int i = 0; i < len; ++i) {
            const uint64_t v = u[pos + i];
            b.unary_zero_run(v >> k);
            if (k) b.put(v & ((1ull << k) - 1), k);
        }
        pos += len;
    }
}

// Smallest encoding of one channel (samples x[0..n), sample width `bps` bits).
Bits encode_subframe(const int64_t* x, int n, int bps) {
    // constant?
    bool constant = true;
    for (int i = 1; i < n; ++i) if (x[i] != x[0]) { constant = false; break; }
    if (constant) { Bits b; b.put(0, 8); b.put_signed(x[0], bps); return b; }

    Bits best;
    uint64_t best_bits = (uint64_t)(8 + (uint64_t)n * bps);   // verbatim size
    bool have = false;
    std::vector<int64_t> res;
    for (int order = 0; order <= 4 && order < n; ++order) {
        res.assign(n - order, 0);
        for (int i = order; i < n; ++i) {
            int64_t p = 0;
            switch (order) {
                case 0: p = 0; break;
                case 1: p = x[i - 1]; break;
                case 2: p = 2 * x[i - 1] - x[i - 2]; break;
                case 3: p = 3 * x[i - 1] - 3 * x[i - 2] + x[i - 3]; break;
                default: p = 4 * x[i - 1] - 6 * x[i - 2] + 4 * x[i - 3] - x[i - 4]; break;
            }
            res[i - order] = x[i] - p;
        }
        Bits b;
        b.put(0, 1); b.put((uint64_t)(8 + order), 6); b.put(0, 1);   // SUBFRAME_FIXED, no wasted bits
        for (int i = 0; i < order; ++i) b.put_signed(x[i], bps);
        write_residual(b, res, n, order);
        const uint64_t bits = b.bit_count();
        if (bits < best_bits) { best_bits = bits; best = std::move(b); have = true; }
    }
    if (!have) {
        Bits b; b.put(0, 1); b.put(1, 6); b.put(0, 1);               // SUBFRAME_VERBATIM
        for (int i = 0; i < n; ++i) b.put_signed(x[i], bps);
        return b;
    }
    return best;
}

void append_bits(Bits& dst, const Bits& src) {
    for (uint8_t byte : src.v) dst.put(byte, 8);
    if (src.n) dst.put(src.acc, src.n);
}

void utf8_number(Bits& b, uint32_t v) {
    if (v < 0x80) { b.put(v, 8); return; }
    int extra = v < 0x800 ? 1 : v < 0x10000 ? 2 : v < 0x200000 ? 3 : v < 0x4000000 ? 4 : 5;
    const uint8_t lead = (uint8_t)(0xFF << (7 - extra)) & 0xFF;
    b.put((uint8_t)(lead | (v >> (6 * extra))), 8);
    for (int i = extra - 1; i >= 0; --i) b.put(0x80 | ((v >> (6 * i)) & 0x3F), 8);
}

} // namespace

bool FlacStreamWriter::put(const std::vector<uint8_t>& v) {
    if (!f_) return false;
    if (std::fwrite(v.data(), 1, v.size(), f_) != v.size()) { error_ = true; return false; }
    return true;
}

bool FlacStreamWriter::open(const std::string& path, int channels, int sample_rate, int bit_depth, bool dither) {
    close();
    if ((channels != 1 && channels != 2) || (bit_depth != 16 && bit_depth != 24) || sample_rate < 1 || sample_rate > 655350) return false;
    f_ = std::fopen(path.c_str(), "wb");
    if (!f_) return false;
    ch_ = channels; sr_ = sample_rate; bits_ = bit_depth; dither_ = dither && bit_depth == 16;
    for (auto& v : pcm_) v.assign(kBlock, 0);
    pending_ = 0; total_ = 0; frame_no_ = 0; error_ = false;
    min_frame_ = 0xffffffffu; max_frame_ = 0; min_block_ = 0x7fffffff; max_block_ = 0;
    md5_ = Md5State();
    // "fLaC" + STREAMINFO placeholder (rewritten on close).
    std::vector<uint8_t> h = {'f', 'L', 'a', 'C', 0x80, 0, 0, 34};
    h.resize(8 + 34, 0);
    if (!put(h)) { std::fclose(f_); f_ = nullptr; return false; }
    return true;
}

bool FlacStreamWriter::write(const float* in, int64_t frames) {
    if (!f_ || error_) return false;
    const float scale = bits_ == 16 ? 32767.f : 8388607.f;
    for (int64_t i = 0; i < frames; ++i) {
        for (int c = 0; c < ch_; ++c) {
            float v = in[i * ch_ + c];
            if (!std::isfinite(v)) v = 0.f;
            v = std::max(-1.f, std::min(1.f, v));
            if (dither_) {
                rng_ = rng_ * 1664525u + 1013904223u; const float a = (rng_ >> 8) * (1.f / 16777216.f);
                rng_ = rng_ * 1664525u + 1013904223u; const float b = (rng_ >> 8) * (1.f / 16777216.f);
                v = std::max(-1.f, std::min(1.f, v + (a - b) / 32768.f));
            }
            pcm_[c][pending_] = (int32_t)std::lrintf(v * scale);
        }
        if (++pending_ == kBlock) { encode_block(kBlock); pending_ = 0; if (error_) return false; }
    }
    return !error_;
}

void FlacStreamWriter::encode_block(int n) {
    // MD5 over the little-endian interleaved PCM, as required by STREAMINFO.
    {
        const int bps = bits_ / 8;
        std::vector<uint8_t> raw((size_t)n * ch_ * bps);
        size_t o = 0;
        for (int i = 0; i < n; ++i) for (int c = 0; c < ch_; ++c) {
            const int32_t s = pcm_[c][i];
            for (int k = 0; k < bps; ++k) raw[o++] = (uint8_t)((s >> (8 * k)) & 0xff);
        }
        md5_update(raw.data(), raw.size());
    }
    std::vector<int64_t> l(n), r(n), m(n), s(n);
    for (int i = 0; i < n; ++i) {
        l[i] = pcm_[0][i];
        if (ch_ == 2) { r[i] = pcm_[1][i]; s[i] = l[i] - r[i]; m[i] = (l[i] + r[i]) >> 1; }
    }
    Bits body;
    int assignment = ch_ - 1;     // independent channels
    if (ch_ == 1) {
        append_bits(body, encode_subframe(l.data(), n, bits_));
    } else {
        Bits bl = encode_subframe(l.data(), n, bits_), br = encode_subframe(r.data(), n, bits_);
        Bits bm = encode_subframe(m.data(), n, bits_), bs = encode_subframe(s.data(), n, bits_ + 1);
        const size_t cl = bl.bit_count(), cr = br.bit_count(), cm = bm.bit_count(), cs = bs.bit_count();
        const size_t indep = cl + cr, ls = cl + cs, rs = cr + cs, ms = cm + cs;
        const size_t best = std::min(std::min(indep, ls), std::min(rs, ms));
        if (best == indep) { assignment = 1; append_bits(body, bl); append_bits(body, br); }
        else if (best == ls) { assignment = 8; append_bits(body, bl); append_bits(body, bs); }
        else if (best == rs) { assignment = 9; append_bits(body, bs); append_bits(body, br); }
        else { assignment = 10; append_bits(body, bm); append_bits(body, bs); }
    }
    body.align();

    Bits fr;
    fr.put(0xFFF8, 16);                                   // sync + fixed blocksize strategy
    const bool std_block = n == kBlock;
    fr.put(std_block ? 12 : 7, 4);                        // 4096 or "16-bit value follows"
    fr.put(0, 4);                                         // sample rate: from STREAMINFO
    fr.put((uint64_t)assignment, 4);
    fr.put(bits_ == 16 ? 4 : 6, 3);
    fr.put(0, 1);
    utf8_number(fr, frame_no_);
    if (!std_block) fr.put((uint64_t)(n - 1), 16);
    fr.align();
    fr.put(crc8(fr.v.data(), fr.v.size()), 8);
    for (uint8_t byte : body.v) fr.put(byte, 8);
    fr.put(crc16(fr.v.data(), fr.v.size()), 16);
    if (!put(fr.v)) return;
    const uint32_t sz = (uint32_t)fr.v.size();
    min_frame_ = std::min(min_frame_, sz); max_frame_ = std::max(max_frame_, sz);
    min_block_ = std::min(min_block_, n); max_block_ = std::max(max_block_, n);
    ++frame_no_; total_ += n;
}

bool FlacStreamWriter::close() {
    if (!f_) return true;
    if (pending_ > 0 && !error_) { encode_block(pending_); pending_ = 0; }
    uint8_t md5[16]; md5_final(md5);
    std::vector<uint8_t> si(34, 0);
    const int minb = total_ ? min_block_ : kBlock, maxb = total_ ? max_block_ : kBlock;
    si[0] = (uint8_t)(minb >> 8); si[1] = (uint8_t)minb; si[2] = (uint8_t)(maxb >> 8); si[3] = (uint8_t)maxb;
    const uint32_t minf = total_ ? min_frame_ : 0, maxf = total_ ? max_frame_ : 0;
    si[4] = (uint8_t)(minf >> 16); si[5] = (uint8_t)(minf >> 8); si[6] = (uint8_t)minf;
    si[7] = (uint8_t)(maxf >> 16); si[8] = (uint8_t)(maxf >> 8); si[9] = (uint8_t)maxf;
    const uint64_t packed = ((uint64_t)sr_ << 44) | ((uint64_t)(ch_ - 1) << 41) | ((uint64_t)(bits_ - 1) << 36) | ((uint64_t)total_ & 0xFFFFFFFFFull);
    for (int i = 0; i < 8; ++i) si[10 + i] = (uint8_t)(packed >> (56 - 8 * i));
    std::memcpy(&si[18], md5, 16);
    bool ok = !error_;
    if (std::fseek(f_, 8, SEEK_SET) != 0 || std::fwrite(si.data(), 1, si.size(), f_) != si.size()) ok = false;
    if (std::fflush(f_) != 0) ok = false;
    if (std::fclose(f_) != 0) ok = false;
    f_ = nullptr;
    if (!ok) error_ = true;
    return ok;
}

// ---- MD5 (RFC 1321)
void FlacStreamWriter::md5_block(const uint8_t* p) {
    static const uint32_t K[64] = {
        0xd76aa478,0xe8c7b756,0x242070db,0xc1bdceee,0xf57c0faf,0x4787c62a,0xa8304613,0xfd469501,0x698098d8,0x8b44f7af,0xffff5bb1,0x895cd7be,0x6b901122,0xfd987193,0xa679438e,0x49b40821,
        0xf61e2562,0xc040b340,0x265e5a51,0xe9b6c7aa,0xd62f105d,0x02441453,0xd8a1e681,0xe7d3fbc8,0x21e1cde6,0xc33707d6,0xf4d50d87,0x455a14ed,0xa9e3e905,0xfcefa3f8,0x676f02d9,0x8d2a4c8a,
        0xfffa3942,0x8771f681,0x6d9d6122,0xfde5380c,0xa4beea44,0x4bdecfa9,0xf6bb4b60,0xbebfbc70,0x289b7ec6,0xeaa127fa,0xd4ef3085,0x04881d05,0xd9d4d039,0xe6db99e5,0x1fa27cf8,0xc4ac5665,
        0xf4292244,0x432aff97,0xab9423a7,0xfc93a039,0x655b59c3,0x8f0ccc92,0xffeff47d,0x85845dd1,0x6fa87e4f,0xfe2ce6e0,0xa3014314,0x4e0811a1,0xf7537e82,0xbd3af235,0x2ad7d2bb,0xeb86d391};
    static const int S[64] = {7,12,17,22,7,12,17,22,7,12,17,22,7,12,17,22,5,9,14,20,5,9,14,20,5,9,14,20,5,9,14,20,
                              4,11,16,23,4,11,16,23,4,11,16,23,4,11,16,23,6,10,15,21,6,10,15,21,6,10,15,21,6,10,15,21};
    uint32_t M[16];
    for (int i = 0; i < 16; ++i) M[i] = (uint32_t)p[i * 4] | ((uint32_t)p[i * 4 + 1] << 8) | ((uint32_t)p[i * 4 + 2] << 16) | ((uint32_t)p[i * 4 + 3] << 24);
    uint32_t A = md5_.a, B = md5_.b, C = md5_.c, D = md5_.d;
    for (int i = 0; i < 64; ++i) {
        uint32_t F; int g;
        if (i < 16) { F = (B & C) | (~B & D); g = i; }
        else if (i < 32) { F = (D & B) | (~D & C); g = (5 * i + 1) & 15; }
        else if (i < 48) { F = B ^ C ^ D; g = (3 * i + 5) & 15; }
        else { F = C ^ (B | ~D); g = (7 * i) & 15; }
        F = F + A + K[i] + M[g];
        A = D; D = C; C = B;
        B = B + ((F << S[i]) | (F >> (32 - S[i])));
    }
    md5_.a += A; md5_.b += B; md5_.c += C; md5_.d += D;
}
void FlacStreamWriter::md5_update(const uint8_t* p, size_t n) {
    md5_.len += n;
    while (n) {
        const size_t take = std::min(n, 64 - md5_.fill);
        std::memcpy(md5_.buf + md5_.fill, p, take);
        md5_.fill += take; p += take; n -= take;
        if (md5_.fill == 64) { md5_block(md5_.buf); md5_.fill = 0; }
    }
}
void FlacStreamWriter::md5_final(uint8_t out[16]) {
    const uint64_t bitlen = md5_.len * 8;
    const uint8_t one = 0x80; md5_update(&one, 1);
    const uint8_t zero = 0; while (md5_.fill != 56) md5_update(&zero, 1);
    uint8_t lenb[8]; for (int i = 0; i < 8; ++i) lenb[i] = (uint8_t)(bitlen >> (8 * i));
    md5_update(lenb, 8);
    const uint32_t v[4] = {md5_.a, md5_.b, md5_.c, md5_.d};
    for (int i = 0; i < 4; ++i) for (int k = 0; k < 4; ++k) out[i * 4 + k] = (uint8_t)(v[i] >> (8 * k));
}

} // namespace wavestudio

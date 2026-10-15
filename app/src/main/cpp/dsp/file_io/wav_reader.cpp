#include "wav_reader.h"
#include <fstream>
#include <cstring>
#include <algorithm>

namespace wavestudio {

static inline int16_t read_i16(std::ifstream& f) { int16_t v; f.read((char*)&v,2); return v; }
static inline int32_t read_i32(std::ifstream& f) { int32_t v; f.read((char*)&v,4); return v; }
static inline uint16_t read_u16(std::ifstream& f) { uint16_t v; f.read((char*)&v,2); return v; }
static inline uint32_t read_u32(std::ifstream& f) { uint32_t v; f.read((char*)&v,4); return v; }

std::shared_ptr<AudioBuffer> WavReader::load(const std::string& path) {
    std::ifstream f(path, std::ios::binary);
    if (!f.is_open()) return nullptr;
    char riff[4], wave[4];
    f.read(riff,4);
    if (std::strncmp(riff,"RIFF",4)!=0) return nullptr;
    /*uint32_t sz = */read_u32(f);
    f.read(wave,4);
    if (std::strncmp(wave,"WAVE",4)!=0) return nullptr;

    int channels=0; int sampleRate=0; int bitDepth=16;
    int audioFormat=1;
    std::vector<char> data;

    while (f.good() && !f.eof()) {
        char cid[4]; uint32_t csz=0;
        f.read(cid,4);
        if (!f.good()) break;
        csz = read_u32(f);
        if (std::strncmp(cid,"fmt ",3)==0) {
            audioFormat = read_u16(f);
            channels = read_u16(f);
            sampleRate = (int)read_u32(f);
            /*byteRate*/ read_u32(f);
            /*blockAlign*/ read_u16(f);
            bitDepth = read_u16(f);
            if (csz > 16) f.seekg(csz-16, std::ios::cur);
            else f.seekg(csz - 16 + (csz%2), std::ios::cur);
        } else if (std::strncmp(cid,"data",4)==0) {
            data.resize(csz);
            f.read(data.data(), csz);
            if (csz % 2) f.seekg(1, std::ios::cur);
        } else {
            f.seekg(csz + (csz%2), std::ios::cur);
        }
    }
    if (data.empty() || channels <= 0 || sampleRate <= 0) return nullptr;
    int bytesPerSample = bitDepth/8;
    int64_t frames = (int64_t)data.size() / (bytesPerSample*channels);
    auto buf = std::make_shared<AudioBuffer>();
    buf->allocate(channels, frames, (float)sampleRate);
    float* out = buf->data_mut().data();
    for (int64_t i = 0; i < frames; ++i) {
        for (int c=0;c<channels;++c) {
            size_t off = i*channels*bytesPerSample + c*bytesPerSample;
            float v = 0.f;
            switch(bitDepth){
                case 16: {
                    int16_t s; std::memcpy(&s, data.data()+off,2); v = (float)s / 32768.f; break;
                }
                case 24: {
                    int32_t s = ((uint8_t)data[off+2] << 24) | ((uint8_t)data[off+1]<<16) | ((uint8_t)data[off]<<8);
                    v = (float)(s>>8) / 8388608.f;
                    break;
                }
                case 32: {
                    if (audioFormat == 3) { // float
                        std::memcpy(&v, data.data()+off, 4);
                    } else {
                        int32_t s; std::memcpy(&s, data.data()+off,4); v = (float)s / 2147483648.f;
                    }
                    break;
                }
            }
            out[i*channels + c] = std::max(-1.f, std::min(1.f, v));
        }
    }
    buf->build_mipmaps();
    return buf;
}

bool WavReader::read_info(const std::string& path, int& channels, int64_t& frames, float& sampleRate, int& bitDepth) {
    auto b = load(path);
    if (!b) return false;
    channels = b->channels(); frames = b->frames(); sampleRate = b->sample_rate(); bitDepth = 24;
    return true;
}

bool WavWriter::save_interleaved(const std::string& path, const float* data, int channels, int64_t frames, float sampleRate, int bitDepth) {
    std::vector<const float*> ptrs(channels);
    std::vector<std::vector<float>> planar(channels, std::vector<float>(frames));
    for (int c=0;c<channels;++c) {
        for (int64_t i=0;i<frames;++i) planar[c][i] = data[i*channels+c];
        ptrs[c] = planar[c].data();
    }
    return save(path, ptrs.data(), channels, frames, sampleRate, bitDepth);
}

bool WavWriter::save(const std::string& path, const float* const* data, int channels, int64_t frames, float sampleRate, int bitDepth) {
    std::ofstream f(path, std::ios::binary);
    if (!f.is_open()) return false;
    int bytesPS = bitDepth/8;
    int audioFormat = (bitDepth==32) ? 3 : 1;
    uint32_t dataSize = (uint32_t)(frames * channels * bytesPS);
    // RIFF
    f.write("RIFF",4);
    uint32_t riffSize = 36 + dataSize;
    f.write((char*)&riffSize,4);
    f.write("WAVE",4);
    // fmt
    f.write("fmt ",4);
    uint32_t fmtSize = (bitDepth==32)?18:16;
    f.write((char*)&fmtSize,4);
    uint16_t af = (uint16_t)audioFormat;
    uint16_t ch = (uint16_t)channels;
    uint32_t sr = (uint32_t)sampleRate;
    uint16_t bd = (uint16_t)bitDepth;
    uint16_t ba = (uint16_t)(channels*bytesPS);
    uint32_t br = sr*ba;
    f.write((char*)&af,2);
    f.write((char*)&ch,2);
    f.write((char*)&sr,4);
    f.write((char*)&br,4);
    f.write((char*)&ba,2);
    f.write((char*)&bd,2);
    if (bitDepth==32) {
        uint16_t cbSize=0; f.write((char*)&cbSize,2);
    }
    f.write("data",4);
    f.write((char*)&dataSize,4);
    for (int64_t i = 0; i < frames; ++i) {
        for (int c=0;c<channels;++c) {
            float v = std::max(-1.f,std::min(1.f,data[c][i]));
            switch(bitDepth){
                case 16: {
                    int16_t s = (int16_t)(v*32767.f); f.write((char*)&s,2); break;
                }
                case 24: {
                    int32_t s = (int32_t)(v*8388607.f);
                    uint8_t b[3] = { (uint8_t)(s&0xff), (uint8_t)((s>>8)&0xff), (uint8_t)((s>>16)&0xff) };
                    f.write((char*)b,3); break;
                }
                case 32: {
                    f.write((char*)&v,4); break;
                }
            }
        }
    }
    return true;
}

} // namespace wavestudio

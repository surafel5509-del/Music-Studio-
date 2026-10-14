#include "midi_processor.h"
#include <cstring>

namespace wavestudio {

void MidiProcessor::load(const std::vector<uint8_t>& data) {
    events_.clear();
    if (data.size() < 14) return;
    if (std::memcmp(data.data(),"MThd",4)!=0) return;
    uint32_t hdr_len = (data[4]<<24)|(data[5]<<16)|(data[6]<<8)|data[7];
    (void)hdr_len;
    uint16_t fmt = (data[8]<<8)|data[9];
    uint16_t ntrk = (data[10]<<8)|data[11];
    ppq_ = (data[12]<<8)|data[13];
    size_t p = 14;
    (void)fmt;(void)ntrk;
    int64_t absTick = 0;
    while (p + 8 < data.size()) {
        if (std::memcmp(data.data()+p,"MTrk",4)!=0) break;
        uint32_t len = (data[p+4]<<24)|(data[p+5]<<16)|(data[p+6]<<8)|data[p+7];
        p += 8;
        size_t end = p + len;
        uint8_t running = 0;
        int64_t tick = 0;
        while (p < end) {
            uint32_t delta=0;
            while (p < end) { uint8_t b=data[p++]; delta = (delta<<7)|(b&0x7f); if(!(b&0x80))break; }
            tick += delta;
            if (p >= end) break;
            uint8_t b = data[p];
            if (b & 0x80) { running = b; p++; }
            uint8_t ev = running;
            uint8_t d1=0,d2=0;
            if ((ev & 0xF0) != 0xF0 && ev != 0xFF) {
                if (p < end) d1 = data[p++];
                if ((ev & 0xF0) != 0xC0 && (ev & 0xF0) != 0xD0) {
                    if (p < end) d2 = data[p++];
                }
            } else {
                // meta / sysex
                if (ev == 0xFF) {
                    uint8_t type = data[p++];
                    uint32_t mlen=0;
                    while (p<end) { uint8_t x=data[p++]; mlen=(mlen<<7)|(x&0x7f); if(!(x&0x80))break; }
                    if (type == 0x51 && mlen == 3 && p+2<end) {
                        tempo_us_ = (data[p]<<16)|(data[p+1]<<8)|data[p+2];
                    }
                    p += mlen;
                } else {
                    uint32_t slen=0;
                    while (p<end) { uint8_t x=data[p++]; slen=(slen<<7)|(x&0x7f); if(!(x&0x80))break; }
                    p += slen;
                }
                continue;
            }
            MidiEvent e;
            e.frame = tick; // caller converts tick->frame
            e.status = ev; e.data1 = d1; e.data2 = d2;
            events_.push_back(e);
        }
        p = end;
        absTick = tick;
    }
}

void MidiProcessor::dispatch(int64_t startFrame, int64_t endFrame, std::function<void(const MidiEvent&)> cb) {
    // Converts tick-based events to frames using ppq and tempo.
    double usPerTick = (double)tempo_us_ / ppq_;
    double secPerTick = usPerTick * 1e-6;
    // We assume events are sorted by tick (produced by load() in file order)
    while (cursor_ < events_.size()) {
        const auto& e = events_[cursor_];
        double sec = e.frame * secPerTick;
        // We'd need sample rate for actual frames; simplified: caller uses tempo map.
        (void)sec;
        int64_t f = e.frame; // placeholder
        if (f >= endFrame) break;
        if (f >= startFrame) cb(e);
        cursor_++;
    }
}

} // namespace wavestudio

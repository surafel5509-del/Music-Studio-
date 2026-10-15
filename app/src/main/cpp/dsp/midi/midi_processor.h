#pragma once
#include <cstdint>
#include <vector>
#include <functional>

namespace wavestudio {

struct MidiEvent {
    int64_t frame;
    uint8_t status, data1, data2;
};

class MidiProcessor {
public:
    void load(const std::vector<uint8_t>& data);
    const std::vector<MidiEvent>& events() const { return events_; }
    void dispatch(int64_t startFrame, int64_t endFrame, std::function<void(const MidiEvent&)> cb);
private:
    std::vector<MidiEvent> events_;
    size_t cursor_ = 0;
    int64_t ppq_ = 480;
    int64_t tempo_us_ = 500000; // microseconds per quarter
};

} // namespace wavestudio

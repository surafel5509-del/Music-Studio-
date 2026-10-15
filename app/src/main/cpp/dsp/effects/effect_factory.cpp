#include "effect_factory.h"
#include "compressor.h"
#include "distortion.h"
#include "eq.h"
#include "reverb.h"
#include "utility.h"
#include "voice_fx.h"
#include "mod_fx.h"

namespace wavestudio {

std::unique_ptr<Effect> create_effect(const std::string& id) {
    if (id == "gain") return std::make_unique<Gain>();
    if (id == "eq") return std::make_unique<ParametricEQ>();
    if (id == "filter") return std::make_unique<Filter>();
    if (id == "compressor") return std::make_unique<Compressor>();
    if (id == "limiter") return std::make_unique<Limiter>();
    if (id == "gate") return std::make_unique<NoiseGate>();
    if (id == "deesser") return std::make_unique<DeEsser>();
    if (id == "reverb") return std::make_unique<Reverb>();
    if (id == "delay") return std::make_unique<Delay>();
    if (id == "chorus") return std::make_unique<ChorusFlangerPhaser>(ChorusFlangerPhaser::CHORUS);
    if (id == "flanger") return std::make_unique<ChorusFlangerPhaser>(ChorusFlangerPhaser::FLANGER);
    if (id == "phaser") return std::make_unique<ChorusFlangerPhaser>(ChorusFlangerPhaser::PHASER);
    if (id == "distortion") return std::make_unique<Distortion>();
    if (id == "bitcrusher") return std::make_unique<BitCrusher>();
    if (id == "stereo_width") return std::make_unique<StereoWidth>();
    if (id == "pitch_shift") return std::make_unique<PitchShift>();
    if (id == "autotune") return std::make_unique<AutoTune>();
    if (id == "voice_changer") return std::make_unique<VoiceChanger>();
    if (id == "denoise") return std::make_unique<Denoise>();
    if (id == "tremolo") return std::make_unique<Tremolo>();
    if (id == "exciter") return std::make_unique<Exciter>();
    return nullptr;
}

const std::vector<std::string>& available_effect_types() {
    static const std::vector<std::string> v = {
        "gain", "eq", "filter", "compressor", "limiter", "gate", "deesser", "reverb", "delay",
        "chorus", "flanger", "phaser", "distortion", "bitcrusher", "stereo_width",
        "pitch_shift", "autotune", "voice_changer", "denoise", "tremolo", "exciter"};
    return v;
}

} // namespace wavestudio

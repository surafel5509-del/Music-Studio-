#pragma once
#include <cmath>

namespace wavestudio {

// dB <-> linear gain helpers (single definition shared across the engine)
inline float db_to_linear(float db) { return std::pow(10.f, db / 20.f); }
inline float linear_to_db(float lin) { return 20.f * std::log10(std::fmax(lin, 1e-10f)); }

} // namespace wavestudio

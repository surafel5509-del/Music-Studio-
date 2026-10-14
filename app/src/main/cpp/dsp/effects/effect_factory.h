#pragma once
#include <memory>
#include <string>
#include <vector>
#include "effect.h"

namespace wavestudio {

// Creates built-in effects by stable type id (the id stored in project files).
// Returns nullptr for unknown ids. Constructed effects are fully allocated for
// 48 kHz; call set_sample_rate() on the control thread before inserting them.
std::unique_ptr<Effect> create_effect(const std::string& type_id);

// Ids of all effects that really process audio in this build.
const std::vector<std::string>& available_effect_types();

} // namespace wavestudio

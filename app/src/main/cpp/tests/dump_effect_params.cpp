// Prints "type: id[min..max] ..." for every built-in effect. Used by scripts/check_presets.py so the
// Kotlin effect presets can never reference a parameter that does not exist or is out of range.
#include "effects/effect_factory.h"
#include <cstdio>
using namespace wavestudio;
int main() {
    for (auto& t : available_effect_types()) {
        auto e = create_effect(t);
        printf("%s:", t.c_str());
        for (int i = 0; i < e->param_count(); ++i) printf(" %s[%g..%g]", e->param(i).id.c_str(), e->param(i).min, e->param(i).max);
        printf("\n");
    }
}

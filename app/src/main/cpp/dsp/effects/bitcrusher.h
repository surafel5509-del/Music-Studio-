#pragma once
#include "effect.h"
namespace wavestudio {
class BitCrusher : public Effect {
public:
    BitCrusher(); const char* name() const override { return "Bit Crusher"; }
    void process(float**,int,int) override;
};
}

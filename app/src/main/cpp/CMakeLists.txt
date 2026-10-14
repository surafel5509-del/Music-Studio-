cmake_minimum_required(VERSION 3.22.1)
project("wavestudio")

# Oboe-like low-latency audio is implemented directly using AAudio/OpenSL ES
# to keep this project self-contained and fully offline (no git submodules needed).

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Sources
set(WS_ENGINE_SOURCES
        jni/native_lib.cpp
        dsp/audio_engine.cpp
        dsp/mixer.cpp
        dsp/buffer.cpp
        dsp/effects/effect_chain.cpp
        dsp/effects/eq.cpp
        dsp/effects/compressor.cpp
        dsp/effects/reverb.cpp
        dsp/effects/delay.cpp
        dsp/effects/distortion.cpp
        dsp/effects/filter.cpp
        dsp/effects/limiter.cpp
        dsp/effects/noise_gate.cpp
        dsp/effects/chorus_flanger_phaser.cpp
        dsp/effects/bitcrusher.cpp
        dsp/effects/deesser.cpp
        dsp/effects/pitch_shift.cpp
        dsp/effects/time_stretch.cpp
        dsp/analysis/spectrum.cpp
        dsp/analysis/lufs.cpp
        dsp/analysis/phase_meter.cpp
        dsp/file_io/wav_reader.cpp
        dsp/file_io/wav_writer.cpp
        dsp/file_io/flac_io.cpp
        dsp/file_io/mp3_io.cpp
        dsp/resampler/resampler.cpp
        dsp/midi/midi_processor.cpp
        dsp/util/simd_utils.cpp
)

add_library(wavestudio SHARED ${WS_ENGINE_SOURCES})

# Compiler flags per build type
target_compile_options(wavestudio PRIVATE
        -Wall -Wextra -Wno-unused-parameter
        -fvisibility=hidden -fvisibility-inlines-hidden
        -ffast-math -fno-math-errno
)

target_compile_options(wavestudio PRIVATE
        "$<$<CONFIG:Release>:-O3>"
        "$<$<CONFIG:Release>:-flto>"
        "$<$<CONFIG:Debug>:-O0>"
        "$<$<CONFIG:Debug>:-g>"
)

# Link libraries
target_link_libraries(wavestudio
        PRIVATE
        aaudio
        OpenSLES
        log
        android
        m
)

# Include paths
target_include_directories(wavestudio
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/dsp
        ${CMAKE_CURRENT_SOURCE_DIR}/jni
)

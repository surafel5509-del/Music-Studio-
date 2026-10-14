cmake_minimum_required(VERSION 3.22.1)
project("wavestudio")

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(WS_ENGINE_SOURCES
        jni/native_lib.cpp
        dsp/audio_engine.cpp
        dsp/buffer.cpp
        dsp/engine/engine_core.cpp
        dsp/engine/media.cpp
        dsp/engine/recorder.cpp
        dsp/engine/offline_export.cpp
        dsp/effects/effect_factory.cpp
        dsp/effects/eq.cpp
        dsp/effects/compressor.cpp
        dsp/effects/reverb.cpp
        dsp/effects/distortion.cpp
        dsp/effects/utility.cpp
        dsp/analysis/spectrum.cpp
        dsp/analysis/lufs.cpp
        dsp/file_io/wav_reader.cpp
        dsp/file_io/wav_stream_writer.cpp
        dsp/file_io/flac_stream_writer.cpp
        dsp/resampler/resampler.cpp
)

# Not built into the app (stubs / not yet integrated): dsp/file_io/mp3_io.cpp,
# dsp/midi/midi_processor.cpp, dsp/effects/pitch_shift.cpp, dsp/util/simd_utils.cpp.
# See docs/ARCHITECTURE.md ("What is and is not implemented").

add_library(wavestudio SHARED ${WS_ENGINE_SOURCES})

target_compile_options(wavestudio PRIVATE
        -Wall -Wextra -Wno-unused-parameter -Wno-unused-function -Wno-unused-variable
        -fvisibility=hidden -fvisibility-inlines-hidden
        -fno-math-errno
)

target_compile_options(wavestudio PRIVATE
        "$<$<CONFIG:Release>:-O3>"
        "$<$<CONFIG:Release>:-flto>"
        "$<$<CONFIG:Debug>:-O0>"
        "$<$<CONFIG:Debug>:-g>"
)

target_link_libraries(wavestudio
        PRIVATE
        aaudio
        OpenSLES
        log
        android
        m
)

target_include_directories(wavestudio
        PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/dsp
        ${CMAKE_CURRENT_SOURCE_DIR}/jni
)

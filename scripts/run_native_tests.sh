#!/usr/bin/env bash
# Host-side tests for the native audio engine (no Android SDK / NDK / device needed).
# Requires only g++ (>= 9) with C++17, and libasan/libubsan/libtsan for the sanitizer builds.
#
#   scripts/run_native_tests.sh            # full run: ASan+UBSan unit/engine tests, TSan stress test
#   FAST=1 scripts/run_native_tests.sh     # skip the sanitizer builds (plain -O2), shorter stress test
#
# These tests exercise the real engine code (EngineCore, effects, WAV I/O, resampler, recorder,
# offline export) with a fake "device" that calls render() directly. They do NOT test AAudio,
# JNI, the UI or any real audio hardware.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CPP="$ROOT/app/src/main/cpp"
OUT="${TMPDIR:-/tmp}/wavestudio_native_tests"
mkdir -p "$OUT"
cd "$CPP"

COMMON_SRC="dsp/buffer.cpp dsp/file_io/wav_reader.cpp dsp/file_io/wav_stream_writer.cpp dsp/file_io/flac_stream_writer.cpp dsp/resampler/resampler.cpp \
dsp/analysis/lufs.cpp dsp/analysis/spectrum.cpp dsp/effects/effect_factory.cpp dsp/effects/eq.cpp dsp/effects/compressor.cpp \
dsp/effects/reverb.cpp dsp/effects/distortion.cpp dsp/effects/utility.cpp dsp/effects/voice_fx.cpp dsp/effects/mod_fx.cpp \
dsp/engine/engine_core.cpp dsp/engine/media.cpp dsp/engine/recorder.cpp dsp/engine/offline_export.cpp"

if [[ "${FAST:-0}" == "1" ]]; then SAN=""; TSAN=""; ITER=300; else SAN="-fsanitize=address,undefined -fno-sanitize-recover=undefined"; TSAN="-fsanitize=thread"; ITER=1000; fi
CXX="${CXX:-g++}"
FLAGS="-std=c++17 -O1 -g -Wall -Wextra -Wno-missing-field-initializers -Wno-unused-parameter -Idsp -Itests -pthread"

fail=0
build_and_run() {  # name sanitizer-flags [env...]
  local name="$1" san="$2"; shift 2
  echo "=== $name ${san:+($san)}"
  $CXX $FLAGS $san "tests/$name.cpp" $COMMON_SRC -o "$OUT/$name"
  if ! env "$@" "$OUT/$name"; then fail=1; echo "!!! $name FAILED"; fi
}
build_and_run test_effects "$SAN"
build_and_run test_io_analysis "$SAN"
build_and_run test_engine "$SAN"
build_and_run test_export "$SAN"
build_and_run test_engine_stress "$SAN" ITER=$ITER
if [[ -n "$TSAN" ]]; then build_and_run test_engine_stress "$TSAN" ITER=$((ITER/2)); fi

# FLAC: decode our output with libFLAC (through python soundfile) and compare with the WAV export.
echo "=== flac verification"
if python3 -c "import soundfile, numpy" 2>/dev/null; then
  $CXX $FLAGS $SAN tests/flac_tool.cpp dsp/file_io/flac_stream_writer.cpp -o "$OUT/flac_tool"
  mkdir -p "$OUT/flac"
  if ! python3 "$ROOT/scripts/verify_flac.py" "$OUT/flac_tool" "$OUT/flac" /tmp; then fail=1; echo "!!! FLAC verification FAILED"; fi
else
  echo "(skipped: pip install soundfile numpy to enable the libFLAC decode check)"
fi

if [[ $fail -ne 0 ]]; then echo "NATIVE TESTS FAILED"; exit 1; fi
echo "ALL NATIVE TESTS PASSED"

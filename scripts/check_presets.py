#!/usr/bin/env python3
"""Checks every PresetFx in EffectPresets.kt against the parameter list printed by dump_effect_params.
usage: check_presets.py <EffectPresets.kt> <dump output file>"""
import re, sys

src = open(sys.argv[1]).read()
native = {}
for line in open(sys.argv[2]):
    line = line.strip()
    if not line:
        continue
    t, rest = line.split(":", 1)
    native[t] = {m.group(1): (float(m.group(2)), float(m.group(3))) for m in re.finditer(r"(\w+)\[([-\d.e+]+)\.\.([-\d.e+]+)\]", rest)}

bad = 0
n = 0
for m in re.finditer(r'PresetFx\("(\w+)"(?:,\s*mapOf\(([^)]*)\))?\)', src):
    t, body = m.group(1), m.group(2) or ""
    n += 1
    if t not in native:
        print(f"UNKNOWN EFFECT TYPE {t}"); bad += 1; continue
    for k, v in re.findall(r'"(\w+)"\s+to\s+(-?[\d.]+)f', body):
        if k not in native[t]:
            print(f"{t}: unknown parameter '{k}' (have: {', '.join(native[t])})"); bad += 1
        else:
            lo, hi = native[t][k]
            if not (lo - 1e-6 <= float(v) <= hi + 1e-6):
                print(f"{t}.{k}={v} outside [{lo}, {hi}]"); bad += 1
print(f"{n} preset effects checked, {bad} problem(s)")
sys.exit(1 if bad else 0)

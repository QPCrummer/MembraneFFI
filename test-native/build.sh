#!/bin/sh
# Native fixtures for CorrectnessBattery / InlineFixturesBattery / MemBench.
# Point -Dmembrane.testlibs at this directory after building.
set -e
cd "$(dirname "$0")"
gcc -O2 -shared -fPIC -fno-stack-protector -o libnalim_bench.so nalim_bench.c
gcc -O2 -shared -fPIC -fno-stack-protector -o libnalim_narrow_returns.so nalim_narrow_returns.c
gcc -O2 -shared -fPIC -fno-stack-protector -o libstack_args.so stack_args.c
gcc -O2 -shared -fPIC -fno-stack-protector -DFXSUF=_base -o libnalim_fx_base.so nalim_inline_fixtures.c
gcc -O2 -march=x86-64-v3 -ffp-contract=off -shared -fPIC -fno-stack-protector -DFXSUF=_v3 -o libnalim_fx_v3.so nalim_inline_fixtures.c
gcc -shared -o libnalim_fx_asm.so nalim_fx_asm.S
gcc -O2 -shared -fPIC -fno-stack-protector -o liboxidizium.so oxidizium_workalike.c oxidizium_workalike_bool.S -lm
echo "fixtures built: $(ls *.so | wc -l) libraries"

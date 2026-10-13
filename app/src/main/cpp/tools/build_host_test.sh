#!/bin/sh
# Compila la prueba de escritorio contra una build de llama.cpp (mismo commit que el submódulo) con libs compartidas.
# Uso: build_host_test.sh <llama.cpp con build/bin> <salida>
set -e
LL=${1:-/tmp/claude-0/bench/llama.cpp}
OUT=${2:-/tmp/llm_host_test}
HERE=$(cd "$(dirname "$0")/.." && pwd)
g++ -O2 -std=c++17 -o "$OUT" "$HERE/tools/llm_host_test.cpp" "$HERE/llm_session.cpp" \
  -I"$HERE" -I"$LL/include" -I"$LL/common" -I"$LL/tools/mtmd" -I"$LL/ggml/include" -I"$LL/vendor" \
  -L"$LL/build/bin" -Wl,-rpath,"$LL/build/bin" -lmtmd -lllama-common -lllama -lggml -lggml-base -lpthread
echo "OK: $OUT"

#!/usr/bin/env bash
# Compila libpdfsqueeze.so (puente JNI native/pdfsqueeze-ffi + núcleo del submódulo native/pdfsqueeze)
# para arm64-v8a, armeabi-v7a y x86_64 (API 24) y la deja en app/src/main/jniLibs/<abi>/.
# Las .so NO se versionan (.gitignore): este script las genera. Gradle lo llama desde preBuild si faltan.
#
# Requisitos: rustup + cargo, targets Android (rustup target add aarch64-linux-android
# armv7-linux-androideabi x86_64-linux-android), cargo-ndk (cargo install cargo-ndk) y el NDK
# (ANDROID_NDK_HOME, o ndk/<versión> dentro de ANDROID_HOME/ANDROID_SDK_ROOT/sdk.dir de local.properties).
#
# Variables opcionales: PDFSQUEEZE_ABIS="arm64-v8a x86_64" (subconjunto), CARGO_TARGET_DIR.
# Código de salida 0 = generado; 2 = falta el submódulo o la cadena de herramientas (Gradle continúa sin
# motor nativo y la app usa el compresor de respaldo); otro = error de compilación.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
FFI="$HERE/pdfsqueeze-ffi"
OUT="$ROOT/app/src/main/jniLibs"
API=24
ABIS="${PDFSQUEEZE_ABIS:-arm64-v8a armeabi-v7a x86_64}"

skip() { echo "pdfsqueeze: OMITIDO — $*" >&2; echo "pdfsqueeze: la app se compilará con el compresor de respaldo." >&2; exit 2; }

[ -f "$HERE/pdfsqueeze/crates/core/Cargo.toml" ] || skip "falta el submódulo native/pdfsqueeze (git submodule update --init native/pdfsqueeze)"

export PATH="$HOME/.cargo/bin:/root/.cargo/bin:$PATH"
command -v cargo >/dev/null 2>&1 || skip "no se encontró cargo (instala Rust con rustup)"
cargo ndk --version >/dev/null 2>&1 || skip "no se encontró cargo-ndk (cargo install cargo-ndk)"

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$SDK" ] && [ -f "$ROOT/local.properties" ]; then
    SDK="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | tail -1)"
  fi
  if [ -n "$SDK" ] && [ -d "$SDK/ndk" ]; then
    NDK="$(ls -1d "$SDK"/ndk/*/ 2>/dev/null | sort -V | tail -1)"
    [ -n "$NDK" ] && export ANDROID_NDK_HOME="${NDK%/}"
  fi
fi
[ -n "${ANDROID_NDK_HOME:-}" ] && [ -d "$ANDROID_NDK_HOME" ] || skip "no se encontró el NDK de Android (ANDROID_NDK_HOME)"

if command -v rustup >/dev/null 2>&1; then
  installed="$(rustup target list --installed)"
  for abi in $ABIS; do
    case "$abi" in
      arm64-v8a) t=aarch64-linux-android ;;
      armeabi-v7a) t=armv7-linux-androideabi ;;
      x86_64) t=x86_64-linux-android ;;
      *) echo "ABI desconocida: $abi" >&2; exit 1 ;;
    esac
    grep -qx "$t" <<<"$installed" || skip "falta el target de Rust $t (rustup target add $t)"
  done
fi

targs=()
for abi in $ABIS; do targs+=(-t "$abi"); done

echo "pdfsqueeze: compilando ($ABIS, API $API, NDK $(basename "$ANDROID_NDK_HOME"))…"
cd "$FFI"
cargo ndk --platform "$API" "${targs[@]}" -o "$OUT" build --release --locked

for abi in $ABIS; do
  so="$OUT/$abi/libpdfsqueeze.so"
  [ -f "$so" ] || { echo "pdfsqueeze: no se generó $so" >&2; exit 1; }
  echo "pdfsqueeze: $abi → $(du -k "$so" | cut -f1) KB"
done

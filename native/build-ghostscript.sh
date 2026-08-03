#!/bin/bash
set -euo pipefail

# --- config ---------------------------------------------------------------
GS_VERSION=10.07.1
GS_TAG=gs10071
# Pin this after the first successful download: `shasum -a 256 downloads/ghostscript-$GS_VERSION.tar.gz`
# then paste the value below so reruns/other machines verify against a known-good hash.
GS_SHA256="${GS_SHA256:-2fc74362f9be6fae1b0a65d38fdcfd4f0b518cc3b07c5581fb661eb4d2e15251}"
API=26
NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/26.1.10909125}"
HOST_TAG=darwin-x86_64        # NDK toolchain dir name; same on Apple Silicon
ROOT="$(cd "$(dirname "$0")" && pwd)"
DL="$ROOT/downloads"
# ---------------------------------------------------------------------------

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
export CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
# Host compiler for genarch/genconf build tools. -UTARGET_OS_MAC works around a
# real bug in Ghostscript's bundled zlib: modern Apple clang always predefines
# TARGET_OS_MAC=1, which wrongly triggers zlib's classic-MacOS-9 branch
# (#if defined(MACOS) || defined(TARGET_OS_MAC)) that #defines fdopen to NULL —
# that macro then mangles the real fdopen() prototype in the system <stdio.h>,
# producing a syntax error. Passed as a `make` variable (not just exported) because
# `./configure` bakes CCAUX into the generated Makefile; a plain env var is ignored
# on later `make` invocations.
CCAUX="cc -UTARGET_OS_MAC"

mkdir -p "$DL"
cd "$DL"
if [ ! -d "ghostscript-$GS_VERSION" ]; then
  curl -LO "https://github.com/ArtifexSoftware/ghostpdl-downloads/releases/download/$GS_TAG/ghostscript-$GS_VERSION.tar.gz"
  if [ -n "$GS_SHA256" ]; then
    echo "$GS_SHA256  ghostscript-$GS_VERSION.tar.gz" | shasum -a 256 -c -
  else
    echo "WARNING: GS_SHA256 not pinned yet. Computed hash of this download:"
    shasum -a 256 "ghostscript-$GS_VERSION.tar.gz"
    echo "Paste it into GS_SHA256 in this script before trusting future runs."
  fi
  tar xzf "ghostscript-$GS_VERSION.tar.gz"
fi
cd "ghostscript-$GS_VERSION"

# Ghostscript 10.07.1 collides with Android Bionic's __printflike definition
# when a device header has already redefined printf. Keep this source fix
# checked in and idempotent so clean and resumed builds use the same input.
if ! grep -q "Android Bionic defines __printflike" base/gserrors.h; then
  patch -p0 < "$ROOT/patches/ghostscript-$GS_VERSION/0001-bionic-printflike-macro.patch"
fi

./configure \
  --host=aarch64-linux-android \
  --disable-cups --disable-dbus --disable-fontconfig --disable-gtk \
  --without-x --without-tesseract --without-libpaper \
  --with-drivers=FILES

# LDFLAGS: 16KB-page-size devices (Android 15+) require ELF LOAD segments
# aligned to 16KB; the NDK's default linker output is 4KB-aligned.
make -j"$(sysctl -n hw.ncpu)" CCAUX="$CCAUX" LDFLAGS="-Wl,-z,max-page-size=16384" so

OUT="$ROOT/../app/src/main/jniLibs/arm64-v8a"
INC="$ROOT/../app/src/main/cpp/include"
mkdir -p "$OUT" "$INC"
cp sobin/libgs.so "$OUT/libgs.so"
# gserrors.h (base/) is pulled in by ierrors.h but lives outside psi/ — without
# it, gsjni.c fails to compile with "use of undeclared identifier gs_error_Quit".
cp psi/iapi.h psi/ierrors.h base/gserrors.h "$INC/"
echo "OK: $OUT/libgs.so"

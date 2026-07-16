#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
DL="$ROOT/downloads"
CPPDIR="$ROOT/../app/src/main/cpp"

# Pin these after the first successful download (see Task 2's Ghostscript
# script for the same pattern): `shasum -a 256 downloads/<file>`.
HPLIP_SHA256="${HPLIP_SHA256:-5d7643831893a5e2addf9d42d581a5dbfe5aaf023626886b8762c5645da0f1fb}"
CUPS_SHA256="${CUPS_SHA256:-d75757c2bc0f7a28b02ee4d52ca9e4b1aa1ba2affe16b985854f5336940e5ad7}"

verify() {
  local file="$1" expected="$2"
  if [ -n "$expected" ]; then
    echo "$expected  $file" | shasum -a 256 -c -
  else
    echo "WARNING: no pinned checksum for $file. Computed hash:"
    shasum -a 256 "$file"
    echo "Pin it into this script before trusting future runs."
  fi
}

mkdir -p "$DL"
cd "$DL"

if [ ! -d hplip-3.24.4 ]; then
  curl -LO "https://downloads.sourceforge.net/project/hplip/hplip/3.24.4/hplip-3.24.4.tar.gz"
  verify hplip-3.24.4.tar.gz "$HPLIP_SHA256"
  tar xzf hplip-3.24.4.tar.gz
fi
if [ ! -d cups-2.4.10 ]; then
  curl -LO "https://github.com/OpenPrinting/cups/releases/download/v2.4.10/cups-2.4.10-source.tar.gz"
  verify cups-2.4.10-source.tar.gz "$CUPS_SHA256"
  tar xzf cups-2.4.10-source.tar.gz
fi

# hpcups filter sources (the whole prnt/hpcups directory)
mkdir -p "$CPPDIR/hpcups" "$CPPDIR/cupsraster/cups"
cp -R hplip-3.24.4/prnt/hpcups/* "$CPPDIR/hpcups/"
cp -R hplip-3.24.4/common "$CPPDIR/hpcups-common"

# hpcups/Utils.h and hpcups-common/utils.h are DIFFERENT files that collide on
# a case-insensitive filesystem (macOS): a quoted #include "utils.h" from a
# .cpp in hpcups/ resolves to the same-directory Utils.h instead of the
# common one, silently dropping MAX_FILE_PATH_LEN/CUPS_TMP_DIR etc. Rename
# the hpcups-local one (fewer references to fix) to break the collision.
mv "$CPPDIR/hpcups/Utils.h" "$CPPDIR/hpcups/HpUtils.h"
sed -i '' 's/#include "Utils.h"/#include "HpUtils.h"/' \
  "$CPPDIR/hpcups/LJZxStream.cpp" "$CPPDIR/hpcups/LJZjStream.cpp" \
  "$CPPDIR/hpcups/ModeJpeg.cpp" "$CPPDIR/hpcups/ModeJbig.cpp" "$CPPDIR/hpcups/Hbpl1.h"

# Dead code excluded from the build glob (CMakeLists.txt globs hpcups/*.cpp
# and hpcups/*.c, so anything left in place compiles unconditionally even if
# no runtime path ever selects it). All of the below need things this Android
# build doesn't have (jpeglib.h, hpmud.h/DBUS session bus, HP's proprietary
# plugin .so) and none of it is reachable for this printer family — the PPD
# is pcl3gui, and EncapsulatorFactory's dispatch table only ever looks up
# "pcl3gui" for it (patch 0004 strips the hbpl1/quickconnect/ljjetready
# branches that would otherwise dead-end at these excluded files).
mkdir -p "$CPPDIR/hpcups/excluded"
mv "$CPPDIR/hpcups/dbuscomm.cpp" "$CPPDIR/hpcups/dbuscomm.h" \
   "$CPPDIR/hpcups/ModeJpeg.cpp" "$CPPDIR/hpcups/ModeJpeg.h" \
   "$CPPDIR/hpcups/LJJetReady.cpp" "$CPPDIR/hpcups/LJJetReady.h" \
   "$CPPDIR/hpcups/QuickConnect.cpp" "$CPPDIR/hpcups/QuickConnect.h" \
   "$CPPDIR/hpcups/Hbpl1.cpp" "$CPPDIR/hpcups/Hbpl1.h" \
   "$CPPDIR/hpcups/Hbpl1_Wrapper.cpp" "$CPPDIR/hpcups/Hbpl1_Wrapper.h" \
   "$CPPDIR/hpcups/PCLmGenerator.h" "$CPPDIR/hpcups/genPCLm.cpp" \
   "$CPPDIR/hpcups/genJPEGStrips.cpp" \
   "$CPPDIR/hpcups/jccolor.c" "$CPPDIR/hpcups/jdatadbf.c" \
   "$CPPDIR/hpcups/excluded/"
# dbuscomm.cpp/dbuscomm.h themselves stay (DBusCommunicator is a real member
# of HPCupsFilter, just gated behind HAVE_DBUS which we don't define) — only
# their now-dead peers above are excluded. Patch 0003 drops dbuscomm.h's
# vestigial (unused) #include "hpmud.h".
mv "$CPPDIR/hpcups/excluded/dbuscomm.cpp" "$CPPDIR/hpcups/excluded/dbuscomm.h" "$CPPDIR/hpcups/"

# CUPS raster I/O + PPD parsing: sources AND headers together in
# cupsraster/cups/, mirroring upstream's layout (everything sibling in
# cups/) — raster-private.h and friends use quoted same-directory includes,
# so splitting .c from .h across directories breaks those. Full header set
# copied because the raster/string/language private headers pull each other
# in extensively; a minimal subset became whack-a-mole.
cp cups-2.4.10/cups/*.h "$CPPDIR/cupsraster/cups/" 2>/dev/null || true

# hpcups is a real CUPS filter that normally links the full libcups.so; PPD
# parsing (ppdOpenFile etc) and raster I/O both pull in a meaningful chunk of
# CUPS's own core (COREOBJS + DRIVEROBJS in cups/Makefile) — bundling that
# scope directly rather than chasing individual undefined-symbol errors.
# Excluded on purpose: tls.c (no TLS needed for local PPD/raster parsing),
# and the backend/network-transport pieces (backend.c, backchannel.c,
# getdevices.c, getifaddrs.c, sidechannel.c, adminutil.c, snmp*) — hpcups
# talks to the printer over the fds we hand it, never over CUPS's own
# backend transport.
cp cups-2.4.10/cups/array.c cups-2.4.10/cups/auth.c cups-2.4.10/cups/debug.c \
   cups-2.4.10/cups/dest.c cups-2.4.10/cups/dest-job.c \
   cups-2.4.10/cups/dest-localization.c cups-2.4.10/cups/dest-options.c \
   cups-2.4.10/cups/dir.c cups-2.4.10/cups/encode.c cups-2.4.10/cups/file.c \
   cups-2.4.10/cups/getputfile.c cups-2.4.10/cups/globals.c \
   cups-2.4.10/cups/hash.c cups-2.4.10/cups/http.c cups-2.4.10/cups/http-addr.c \
   cups-2.4.10/cups/http-addrlist.c cups-2.4.10/cups/http-support.c \
   cups-2.4.10/cups/ipp.c cups-2.4.10/cups/ipp-file.c cups-2.4.10/cups/ipp-vars.c \
   cups-2.4.10/cups/ipp-support.c cups-2.4.10/cups/langprintf.c \
   cups-2.4.10/cups/language.c cups-2.4.10/cups/md5.c cups-2.4.10/cups/md5passwd.c \
   cups-2.4.10/cups/notify.c cups-2.4.10/cups/options.c cups-2.4.10/cups/pwg-media.c \
   cups-2.4.10/cups/raster-error.c cups-2.4.10/cups/raster-stream.c \
   cups-2.4.10/cups/raster-stubs.c cups-2.4.10/cups/request.c \
   cups-2.4.10/cups/snprintf.c cups-2.4.10/cups/string.c cups-2.4.10/cups/tempfile.c \
   cups-2.4.10/cups/thread.c cups-2.4.10/cups/transcode.c cups-2.4.10/cups/usersys.c \
   cups-2.4.10/cups/util.c \
   cups-2.4.10/cups/ppd.c cups-2.4.10/cups/ppd-attr.c cups-2.4.10/cups/ppd-cache.c \
   cups-2.4.10/cups/ppd-conflicts.c cups-2.4.10/cups/ppd-custom.c \
   cups-2.4.10/cups/ppd-emit.c cups-2.4.10/cups/ppd-localize.c \
   cups-2.4.10/cups/ppd-mark.c cups-2.4.10/cups/ppd-page.c cups-2.4.10/cups/ppd-util.c \
   cups-2.4.10/cups/raster-interpret.c \
   "$CPPDIR/cupsraster/cups/"

# config.h: CUPS's own ./configure output, generated for our Android target
# (not the host mac) so its HAVE_*/platform macros match what we're compiling
# for. Needed by string-private.h and others.
(
  cd cups-2.4.10
  NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/26.1.10909125}"
  TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64"
  export CC="$TOOLCHAIN/bin/aarch64-linux-android26-clang"
  export CXX="$TOOLCHAIN/bin/aarch64-linux-android26-clang++"
  ./configure --host=aarch64-linux-android --disable-shared --with-tls=no
)
cp cups-2.4.10/config.h "$CPPDIR/cupsraster/cups/"

# Apply the checked-in fd-routing / dead-code / Bionic-compat patches so
# reruns never drift.
for p in "$ROOT/patches/hplip-3.24.4/"*.patch; do
  patch -p0 -d "$CPPDIR" < "$p"
done
for p in "$ROOT/patches/cups-2.4.10/"*.patch; do
  patch -p0 -d "$CPPDIR" < "$p"
done
echo "Sources staged and patched. If a patch fails to apply, the upstream layout"
echo "shifted from the pinned version — regenerate the patch against the new layout."

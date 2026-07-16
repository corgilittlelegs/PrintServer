# Tier-2 Legacy Driverless Printing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the HP DeskJet 2130/2300 family (host-based, no IPP-USB) fully driverless on the network: the app runs its own IPP printer, renders PDF → PCL3-GUI on-device via cross-compiled Ghostscript + hpcups, and writes to USB.

**Architecture:** `LocalIppServer` (synthetic IPP printer on :8631) → `JobQueue` (single worker) → `RenderingPipeline` (Ghostscript JNI renders PDF→PPM; hpcups JNI encodes RGB→PCL3-GUI via bundled PPD) → existing legacy `UsbTransport`. Spec: `docs/superpowers/specs/2026-07-16-tier2-legacy-driverless-printing-design.md`.

**Tech Stack:** Everything from the Tier-1 plan, plus: Android NDK (r26+), CMake, Ghostscript 10.03.1 (AGPL), HPLIP 3.24.4 source (`hpcups`, GPL), CUPS 2.4.10 source (`libcupsimage` raster I/O, Apache-2.0).

**Prerequisites:** Tier-1 plan Tasks 1–12 complete (this plan reuses `HttpHead`, `BodyCopier`, `UsbTransport`, `FakePrinterTransport`, `UsbPrinterManager`, `TxtRecords`, `DiscoveryAdvertiser`, `ServerService`). NDK installed via SDK manager. A working PPD for the printer copied from the user's Ubuntu machine (Task 5).

**Licensing note:** Ghostscript is AGPL, hpcups is GPL. Fine for personal sideload; before any Play Store release the whole app must ship under a compatible license with source availability. Flag this again at release time.

**Task types:** Tasks 6–11 are TDD (JVM tests, no device). Tasks 1–4 are native toolchain tasks — verified by build artifacts and an early native fixture proof in Task 5.5, then the full device smoke test in Task 12. Task 14 is a licensing/documentation task, not code.

**Plan-review addendum (2026-07-16):** An independent review of this plan (`docs/superpowers/plans/tier2-legacy-driverless-plan-review.md`, filed externally) confirmed two real bugs — a dangling `HpcupsEncoder.kt` file reference in Task 4's file list, and `printJob()` hardcoding `processing` instead of the job's actual queue state — plus several hardening gaps (native build reproducibility, streaming output, resource limits, IPP interop depth, failure-artifact capture, licensing). All are folded into the tasks below; see each task's changes.

**Git note:** Same as Tier-1 plan — skip Commit steps if the repo still isn't initialized.

Path shorthands: `SRC = app/src/main/java/dev/jaspreet/printserver`, `TST = app/src/test/java/dev/jaspreet/printserver`, `CPP = app/src/main/cpp`, `NATIVE = native/` (build scripts + downloaded sources, not shipped in APK).

---

### Task 1: NDK/CMake scaffolding

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `CPP/CMakeLists.txt`
- Create: `CPP/placeholder.c`
- Modify: `.gitignore`

- [ ] **Step 1: Add NDK config to the app module**

In `app/build.gradle.kts`, inside `android { defaultConfig { ... } }` add:
```kotlin
        ndk { abiFilters += "arm64-v8a" }   // phones only; emulator (x86_64) unsupported for native path
```
Inside `android { ... }` (sibling of `defaultConfig`) add:
```kotlin
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
```

- [ ] **Step 2: Create a compiling-but-empty CMake project**

`CPP/CMakeLists.txt`:
```cmake
cmake_minimum_required(VERSION 3.22.1)
project(printserver_native C CXX)

# Real targets (gsjni, hpcupsjni) are added in Tasks 3 and 4.
add_library(placeholder SHARED placeholder.c)
```

`CPP/placeholder.c`:
```c
/* Keeps the CMake build green until real native targets land (Tasks 3-4). */
int printserver_native_placeholder(void) { return 0; }
```

Append to `.gitignore`:
```
native/downloads/
native/build/
app/.cxx/
```

- [ ] **Step 3: Verify build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL (NDK + CMake toolchain resolves; APK contains `lib/arm64-v8a/libplaceholder.so`).

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "chore: NDK/CMake scaffolding for native rendering pipeline"
```

---

### Task 2: Cross-compile Ghostscript to libgs.so

Artifact task: produces `app/src/main/jniLibs/arm64-v8a/libgs.so` + API headers. Run on the Mac (host genarch output is correct here: macOS arm64/x86_64 and Android arm64 are all LP64 little-endian, so the generated arch header matches the target).

**Files:**
- Create: `native/build-ghostscript.sh`
- Create (artifact): `app/src/main/jniLibs/arm64-v8a/libgs.so`
- Create (artifact): `CPP/include/iapi.h`, `CPP/include/ierrors.h`

- [ ] **Step 1: Write the build script**

`native/build-ghostscript.sh`:
```bash
#!/bin/bash
set -euo pipefail

# --- config ---------------------------------------------------------------
GS_VERSION=10.03.1
GS_TAG=gs10031
# Pin this after the first successful download: `shasum -a 256 downloads/ghostscript-$GS_VERSION.tar.gz`
# then paste the value below so reruns/other machines verify against a known-good hash.
GS_SHA256="${GS_SHA256:-}"
API=26
NDK="${ANDROID_NDK_HOME:-$HOME/Library/Android/sdk/ndk/26.3.11579264}"
HOST_TAG=darwin-x86_64        # NDK toolchain dir name; same on Apple Silicon
ROOT="$(cd "$(dirname "$0")" && pwd)"
DL="$ROOT/downloads"
# ---------------------------------------------------------------------------

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG"
export CC="$TOOLCHAIN/bin/aarch64-linux-android${API}-clang"
export CCAUX=cc               # host compiler for genarch/genconf build tools

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

./configure \
  --host=aarch64-linux-android \
  --disable-cups --disable-dbus --disable-fontconfig --disable-gtk \
  --without-x --without-tesseract --without-libpaper \
  --with-drivers=FILES

make -j"$(sysctl -n hw.ncpu)" so

OUT="$ROOT/../app/src/main/jniLibs/arm64-v8a"
INC="$ROOT/../app/src/main/cpp/include"
mkdir -p "$OUT" "$INC"
cp sobin/libgs.so "$OUT/libgs.so"
cp psi/iapi.h psi/ierrors.h "$INC/"
echo "OK: $OUT/libgs.so"
```

- [ ] **Step 2: Run it**

Run:
```bash
chmod +x native/build-ghostscript.sh && ./native/build-ghostscript.sh
```
Expected: ends with `OK: .../libgs.so`.
Known failure modes: (a) `sobin/libgs.so` path differs by version — check `ls sobin* obj/ 2>/dev/null` and adjust the `cp`; (b) configure picks host compiler — confirm `CC` path exists first; (c) genarch tools fail — rerun `make so` after `make clean`, ensuring `CCAUX=cc`.

- [ ] **Step 3: Verify the artifact is really Android arm64**

Run: `file app/src/main/jniLibs/arm64-v8a/libgs.so`
Expected: `ELF 64-bit LSB shared object, ARM aarch64`.

- [ ] **Step 4: Confirm the `ppmraw` output device actually got compiled in**

`--with-drivers=FILES` is a device-category shorthand, not a guarantee any specific device is present. The whole rendering pipeline depends on `ppmraw` existing, so check for it now instead of discovering its absence deep in Task 7 or 12:

Run: `strings app/src/main/jniLibs/arm64-v8a/libgs.so | grep -c ppmraw`
Expected: a nonzero count. If zero, the device list needs an explicit `--with-drivers=FILES,ppmraw` (or check `native/downloads/ghostscript-$GS_VERSION/devs.mak` for the exact device group name) and Step 2 must be rerun.

- [ ] **Step 5: Commit** (script only — the .so is a build artifact but SHOULD be committed too for this personal project so builds are reproducible without redoing this task)

```bash
git add native/build-ghostscript.sh app/src/main/jniLibs app/src/main/cpp/include .gitignore
git commit -m "build: cross-compiled Ghostscript 10.03.1 for arm64 Android"
```

---

### Task 3: Ghostscript JNI bridge (PDF → PPM)

**Files:**
- Create: `CPP/gsjni.c`
- Modify: `CPP/CMakeLists.txt`
- Create: `SRC/render/GhostscriptNative.kt`
- Create: `SRC/render/GhostscriptRenderer.kt`

- [ ] **Step 1: Write the JNI wrapper**

`CPP/gsjni.c`:
```c
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include "include/iapi.h"

/*
 * Runs one Ghostscript invocation with the given argv.
 * Returns the gsapi error code (0 or gs_error_Quit on success).
 * Ghostscript is not reentrant: callers must serialize (JobQueue does).
 */
JNIEXPORT jint JNICALL
Java_dev_jaspreet_printserver_render_GhostscriptNative_run(JNIEnv *env, jobject thiz, jobjectArray jargs) {
    int argc = (*env)->GetArrayLength(env, jargs);
    char **argv = calloc(argc, sizeof(char *));
    if (!argv) return -100;
    for (int i = 0; i < argc; i++) {
        jstring js = (jstring)(*env)->GetObjectArrayElement(env, jargs, i);
        const char *c = (*env)->GetStringUTFChars(env, js, NULL);
        argv[i] = strdup(c);
        (*env)->ReleaseStringUTFChars(env, js, c);
        (*env)->DeleteLocalRef(env, js);
    }

    void *instance = NULL;
    int code = gsapi_new_instance(&instance, NULL);
    if (code == 0) {
        code = gsapi_init_with_args(instance, argc, argv);
        gsapi_exit(instance);
        gsapi_delete_instance(instance);
    }

    for (int i = 0; i < argc; i++) free(argv[i]);
    free(argv);
    if (code == gs_error_Quit) code = 0;
    return code;
}
```

In `CPP/CMakeLists.txt` replace the placeholder target with:
```cmake
add_library(gs SHARED IMPORTED)
set_target_properties(gs PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libgs.so)

add_library(gsjni SHARED gsjni.c)
target_include_directories(gsjni PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(gsjni gs log)
```
Delete `CPP/placeholder.c` and its `add_library(placeholder ...)` line.

- [ ] **Step 2: Write the Kotlin bindings**

`SRC/render/GhostscriptNative.kt`:
```kotlin
package dev.jaspreet.printserver.render

object GhostscriptNative {
    init {
        System.loadLibrary("gs")
        System.loadLibrary("gsjni")
    }
    /** Returns 0 on success; negative gsapi error code on failure. */
    external fun run(args: Array<String>): Int
}
```

`SRC/render/GhostscriptRenderer.kt`:
```kotlin
package dev.jaspreet.printserver.render

import java.io.File
import java.io.IOException

/** Renders a PDF to a raw PPM (P6) file at fixed resolution via Ghostscript. */
class GhostscriptRenderer(private val dpi: Int = 300) {

    fun renderToPpm(pdf: File, outPpm: File) {
        val code = GhostscriptNative.run(
            arrayOf(
                "gs", "-dSAFER", "-dBATCH", "-dNOPAUSE", "-dQUIET",
                "-sDEVICE=ppmraw", "-r$dpi",
                "-o", outPpm.absolutePath,
                pdf.absolutePath,
            )
        )
        if (code != 0) throw IOException("Ghostscript failed with code $code")
        if (!outPpm.exists() || outPpm.length() == 0L) {
            throw IOException("Ghostscript produced no output")
        }
    }
}
```

- [ ] **Step 3: Verify build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep '\.so'` lists `libgs.so` and `libgsjni.so` under `lib/arm64-v8a/`. Runtime verification happens in Task 12's device smoke test.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: Ghostscript JNI bridge rendering PDF to PPM"
```

---

### Task 4: hpcups + CUPS raster JNI bridge (RGB rows → PCL3-GUI)

Heaviest native task. Compiles hpcups (from HPLIP source) and libcupsimage's raster writer directly into one `libhpcupsjni.so`. hpcups normally runs as a CUPS filter process reading raster on fd 0 and writing PCL to fd 1; two small source patches route those through globals instead.

**Files:**
- Create: `native/fetch-hpcups-sources.sh`
- Create: `native/patches/hplip-3.24.4/0001-expose-hpcups-main.patch`
- Create: `native/patches/hplip-3.24.4/0002-route-filter-fds.patch`
- Create: `CPP/hpcups_glue.h`
- Create: `CPP/hpcupsjni.cpp`
- Modify: `CPP/CMakeLists.txt`
- Create: `SRC/render/HpcupsNative.kt`

(No `HpcupsEncoder.kt` — the encoding entry point is the single `HpcupsNative.encode` JNI call below; `NativeRenderingPipeline`, built in Task 7, is what composes it with Ghostscript.)

- [ ] **Step 1: Write the source-fetch script with checksum verification**

`native/fetch-hpcups-sources.sh`:
```bash
#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
DL="$ROOT/downloads"
CPPDIR="$ROOT/../app/src/main/cpp"

# Pin these after the first successful download (see Task 2's Ghostscript
# script for the same pattern): `shasum -a 256 downloads/<file>`.
HPLIP_SHA256="${HPLIP_SHA256:-}"
CUPS_SHA256="${CUPS_SHA256:-}"

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

# CUPS raster I/O sources + public headers (only what raster read/write needs)
cp cups-2.4.10/cups/raster.h cups-2.4.10/cups/versioning.h cups-2.4.10/cups/cups.h \
   cups-2.4.10/cups/ppd.h cups-2.4.10/cups/array.h cups-2.4.10/cups/file.h \
   cups-2.4.10/cups/language.h cups-2.4.10/cups/string-private.h \
   "$CPPDIR/cupsraster/cups/" 2>/dev/null || true
cp cups-2.4.10/cups/raster-stream.c cups-2.4.10/cups/raster-interpret.c \
   cups-2.4.10/cups/raster-stubs.c "$CPPDIR/cupsraster/"

# Apply the checked-in fd-routing patches (Step 3) so reruns never drift.
for p in "$ROOT/patches/hplip-3.24.4/"*.patch; do
  patch -p0 -d "$CPPDIR" < "$p"
done
echo "Sources staged and patched. If a patch fails to apply, HPLIP's layout"
echo "shifted from 3.24.4 — regenerate the patch (see Step 3) against the new layout."
```

- [ ] **Step 2: Run it and stage sources — expect the patch step to fail on first run**

Run: `chmod +x native/fetch-hpcups-sources.sh && ./native/fetch-hpcups-sources.sh`
Expected on this first run: sources stage successfully (`app/src/main/cpp/hpcups/` contains `HPCupsFilter.cpp` among ~40 source files; `cupsraster/` contains raster sources), then the patch loop fails because the patch files don't exist yet (Step 3 creates them from this exact staged source). Continue to Step 3.

- [ ] **Step 3: Create the fd-routing patches as real, checked-in files**

Create `CPP/hpcups_glue.h`:
```c
#ifndef HPCUPS_GLUE_H
#define HPCUPS_GLUE_H
/* Set by hpcupsjni.cpp before invoking hpcups_main. Defaults preserve
 * original CUPS-filter behavior (stdin/stdout). */
extern int g_hpcups_input_fd;
extern int g_hpcups_output_fd;
#endif
```

hpcups normally runs as a CUPS filter reading raster on fd 0 and writing PCL to fd 1. Two edits route those through globals instead. Locate the exact lines first (HPLIP 3.24.4):

```bash
grep -n "int main" app/src/main/cpp/hpcups/HPCupsFilter.cpp
grep -n "cupsRasterOpen" app/src/main/cpp/hpcups/HPCupsFilter.cpp
grep -rn "STDOUT_FILENO\|write(1," app/src/main/cpp/hpcups/
```

Make the edits directly in the staged tree (rename `main` to `hpcups_main` + add the glue include; replace the fd `0` in `cupsRasterOpen` with `g_hpcups_input_fd`; replace the output fd with `g_hpcups_output_fd`), then capture them as patches so future fetches are reproducible instead of manual:

```bash
cd app/src/main/cpp
cp hpcups/HPCupsFilter.cpp /tmp/HPCupsFilter.cpp.orig   # before editing, for the diff below
# ... make the edits described above in hpcups/HPCupsFilter.cpp ...
diff -u /tmp/HPCupsFilter.cpp.orig hpcups/HPCupsFilter.cpp \
  > ../../../../../native/patches/hplip-3.24.4/0001-expose-hpcups-main.patch
```
Split the `main`-rename + fd-routing edits into two patches (`0001-expose-hpcups-main.patch` for the rename/include, `0002-route-filter-fds.patch` for the two fd substitutions) if they land in different files, so each patch has one clear purpose; combine into one file if HPLIP 3.24.4 keeps all three edits in `HPCupsFilter.cpp` (likely, since the patch loop in Step 1 applies every file under `native/patches/hplip-3.24.4/` regardless of count).

Re-run `./native/fetch-hpcups-sources.sh` once the patches exist — a fresh checkout must now stage and patch cleanly with no manual steps. If a grep in this step returns nothing, the version layout shifted: search the same symbols across `CPP/hpcups/*.cpp`, patch where actually found, and note the deviation in the patch file's header comment. The invariant that matters: after patching, hpcups reads raster from `g_hpcups_input_fd` and writes PCL bytes to `g_hpcups_output_fd`, with no other fd assumptions.

- [ ] **Step 4: Write the JNI encoder**

`CPP/hpcupsjni.cpp`:
```cpp
#include <jni.h>
#include <pthread.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "cupsraster/cups/raster.h"
#include "hpcups_glue.h"

int g_hpcups_input_fd = 0;
int g_hpcups_output_fd = 1;

extern int hpcups_main(int argc, char *argv[]);

#define LOG_TAG "hpcupsjni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

struct RasterFeed {
    int fd;                 // write end of the pipe
    const unsigned char *rgb;
    unsigned width, height, dpi;
};

/* Writer thread: emits one CUPS-Raster v2 page (sRGB, 8bpc chunky) into the pipe. */
static void *feed_raster(void *arg) {
    RasterFeed *f = (RasterFeed *)arg;
    cups_raster_t *ras = cupsRasterOpen(f->fd, CUPS_RASTER_WRITE);
    if (ras) {
        cups_page_header2_t h;
        memset(&h, 0, sizeof(h));
        strcpy(h.MediaClass, "");
        h.HWResolution[0] = f->dpi;
        h.HWResolution[1] = f->dpi;
        h.cupsWidth = f->width;
        h.cupsHeight = f->height;
        h.cupsBitsPerColor = 8;
        h.cupsBitsPerPixel = 24;
        h.cupsBytesPerLine = f->width * 3;
        h.cupsColorOrder = CUPS_ORDER_CHUNKED;
        h.cupsColorSpace = CUPS_CSPACE_SRGB;
        h.cupsNumColors = 3;
        h.PageSize[0] = (unsigned)(f->width * 72 / f->dpi);
        h.PageSize[1] = (unsigned)(f->height * 72 / f->dpi);
        h.NumCopies = 1;
        if (cupsRasterWriteHeader2(ras, &h)) {
            for (unsigned y = 0; y < f->height; y++) {
                if (cupsRasterWritePixels(ras,
                        (unsigned char *)f->rgb + (size_t)y * h.cupsBytesPerLine,
                        h.cupsBytesPerLine) == 0) {
                    LOGE("raster write failed at row %u", y);
                    break;
                }
            }
        }
        cupsRasterClose(ras);
    }
    close(f->fd);
    return NULL;
}

/*
 * Encodes one RGB page to PCL3-GUI via hpcups.
 * Returns 0 on success, nonzero hpcups exit code / -1 on setup failure.
 * NOT thread-safe (globals + hpcups statics): callers must serialize.
 */
extern "C" JNIEXPORT jint JNICALL
Java_dev_jaspreet_printserver_render_HpcupsNative_encode(
    JNIEnv *env, jobject thiz,
    jbyteArray jrgb, jint width, jint height, jint dpi,
    jstring jppdPath, jstring joutPath) {

    const char *ppd = env->GetStringUTFChars(jppdPath, NULL);
    const char *outPath = env->GetStringUTFChars(joutPath, NULL);
    jbyte *rgb = env->GetByteArrayElements(jrgb, NULL);
    int result = -1;

    int pipefd[2];
    int outFd = open(outPath, O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (outFd >= 0 && pipe(pipefd) == 0) {
        setenv("PPD", ppd, 1);
        g_hpcups_input_fd = pipefd[0];
        g_hpcups_output_fd = outFd;

        RasterFeed feed = { pipefd[1], (const unsigned char *)rgb,
                            (unsigned)width, (unsigned)height, (unsigned)dpi };
        pthread_t writer;
        pthread_create(&writer, NULL, feed_raster, &feed);

        char *argv[] = { (char *)"hpcups", (char *)"1", (char *)"android",
                         (char *)"printserver", (char *)"1", (char *)"", NULL };
        result = hpcups_main(6, argv);

        pthread_join(writer, NULL);
        close(pipefd[0]);
    }
    if (outFd >= 0) close(outFd);

    env->ReleaseByteArrayElements(jrgb, rgb, JNI_ABORT);
    env->ReleaseStringUTFChars(jppdPath, ppd);
    env->ReleaseStringUTFChars(joutPath, outPath);
    return result;
}
```

- [ ] **Step 5: Add the CMake target**

Append to `CPP/CMakeLists.txt`:
```cmake
file(GLOB HPCUPS_SOURCES hpcups/*.cpp hpcups/*.c)
file(GLOB CUPSRASTER_SOURCES cupsraster/*.c)

add_library(hpcupsjni SHARED
    hpcupsjni.cpp
    ${HPCUPS_SOURCES}
    ${CUPSRASTER_SOURCES})
target_include_directories(hpcupsjni PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/hpcups
    ${CMAKE_SOURCE_DIR}/hpcups-common
    ${CMAKE_SOURCE_DIR}/cupsraster)
target_compile_definitions(hpcupsjni PRIVATE -DUNIX -DAPDK_LINUX)
target_link_libraries(hpcupsjni log z)
```

- [ ] **Step 6: Write the Kotlin JNI binding**

`SRC/render/HpcupsNative.kt`:
```kotlin
package dev.jaspreet.printserver.render

object HpcupsNative {
    init { System.loadLibrary("hpcupsjni") }
    /** Returns 0 on success. Not thread-safe — serialize calls (JobQueue does). */
    external fun encode(
        rgb: ByteArray, width: Int, height: Int, dpi: Int,
        ppdPath: String, outPath: String,
    ): Int
}
```

- [ ] **Step 7: Iterate the build until green**

Run: `./gradlew :app:assembleDebug` repeatedly. Expected failure loop: missing private CUPS/HPLIP headers or unused-source compile errors. Resolution rules:
- Missing cups header → copy it from `native/downloads/cups-2.4.10/cups/` into `CPP/cupsraster/cups/`.
- hpcups source that drags in DBUS/SANE/network code irrelevant to encoding → exclude it from the glob by moving it to `CPP/hpcups/excluded/` (create the dir; glob doesn't recurse).
- Symbol needed only for the filter's CUPS-process housekeeping (signal handlers, syslog) → stub it in `hpcupsjni.cpp` if trivial.
Done when the APK contains `libhpcupsjni.so`.

- [ ] **Step 8: Commit**

```bash
git add -A && git commit -m "feat: hpcups + CUPS raster compiled into JNI encoder for PCL3-GUI"
```

---

### Task 5: Bundle the PPD

**Files:**
- Create: `app/src/main/assets/ppd/hp_deskjet_2300_series.ppd`
- Create: `SRC/render/PpdAsset.kt`

- [ ] **Step 1: Obtain the PPD from the working Ubuntu machine**

On the Ubuntu box that already prints to this printer:
```bash
ls /etc/cups/ppd/            # find the DeskJet 2300 entry, e.g. HP_DeskJet_2300.ppd
scp /etc/cups/ppd/<name>.ppd <mac>:PrintServer/app/src/main/assets/ppd/hp_deskjet_2300_series.ppd
```
Verify the copy: file starts with `*PPD-Adobe:` and `grep '\*hpPrinterLanguage' <file>` shows `pcl3gui`.

- [ ] **Step 2: Write the asset extractor** (hpcups needs a real filesystem path via the `PPD` env var)

`SRC/render/PpdAsset.kt`:
```kotlin
package dev.jaspreet.printserver.render

import android.content.Context
import java.io.File

object PpdAsset {
    private const val ASSET = "ppd/hp_deskjet_2300_series.ppd"

    /** Extracts the bundled PPD to filesDir (idempotent) and returns its path. */
    fun extract(context: Context): File {
        val target = File(context.filesDir, "hp_deskjet_2300_series.ppd")
        if (!target.exists() || target.length() == 0L) {
            context.assets.open(ASSET).use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }
        return target
    }
}
```

- [ ] **Step 3: Verify build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep ppd` shows the asset.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: bundle DeskJet 2300 series PPD asset"
```

---

### Task 5.5: Native pipeline fixture smoke test (early feasibility proof)

Everything needed for one real end-to-end proof of the riskiest part of this plan now exists: `GhostscriptNative`/`GhostscriptRenderer` (Task 3), `HpcupsNative` (Task 4), and the PPD (Task 5). Prove the chain works *before* building the job queue and IPP server around it — a toolchain problem found here costs one test run to diagnose; found only at Task 12 it costs debugging through two more layers first.

**Files:**
- Modify: `app/build.gradle.kts` (androidTest deps + instrumentation runner — Task 12 reuses this, does not repeat it)
- Create: `app/src/androidTest/assets/smoke.pdf` (any small one-page PDF, e.g. print-to-PDF a page of text on the Mac)
- Create: `app/src/androidTest/java/dev/jaspreet/printserver/NativePipelineFixtureTest.kt`

- [ ] **Step 1: Add androidTest scaffolding**

In `app/build.gradle.kts` `dependencies` add:
```kotlin
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
```
And inside `defaultConfig`: `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`.

- [ ] **Step 2: Write the fixture test, preserving artifacts on failure**

`app/src/androidTest/java/dev/jaspreet/printserver/NativePipelineFixtureTest.kt`:
```kotlin
package dev.jaspreet.printserver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.jaspreet.printserver.render.GhostscriptRenderer
import dev.jaspreet.printserver.render.HpcupsNative
import dev.jaspreet.printserver.render.PpdAsset
import dev.jaspreet.printserver.render.PpmImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Device-only: proves gs -> ppm -> hpcups produces PCL bytes, with none of
 * JobQueue/LocalIppServer/USB in the way. On failure, copies every
 * intermediate artifact to cacheDir/fixture-failure so a real device debug
 * session doesn't start from nothing.
 */
@RunWith(AndroidJUnit4::class)
class NativePipelineFixtureTest {

    @Test
    fun rendersOnePagePdfToPcl() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val pdf = File(ctx.cacheDir, "fixture.pdf")
        testCtx.assets.open("smoke.pdf").use { input -> pdf.outputStream().use { input.copyTo(it) } }

        val ppm = File(ctx.cacheDir, "fixture.ppm")
        val pcl = File(ctx.cacheDir, "fixture.pcl")
        val failureDir = File(ctx.cacheDir, "fixture-failure")

        try {
            GhostscriptRenderer(dpi = 300).renderToPpm(pdf, ppm)
            val img = ppm.inputStream().buffered().use { PpmImage.parse(it) }
            val code = HpcupsNative.encode(
                img.rgb, img.width, img.height, 300, PpdAsset.extract(ctx).absolutePath, pcl.absolutePath,
            )
            assertEquals("hpcups should return 0", 0, code)
            assertTrue("PCL output should be non-trivial", pcl.length() > 1024)
            assertEquals("PCL output should start with ESC", 0x1B, pcl.inputStream().use { it.read() })
        } catch (e: Throwable) {
            failureDir.mkdirs()
            pdf.copyTo(File(failureDir, "fixture.pdf"), overwrite = true)
            if (ppm.exists()) ppm.copyTo(File(failureDir, "fixture.ppm"), overwrite = true)
            if (pcl.exists()) pcl.copyTo(File(failureDir, "fixture.pcl"), overwrite = true)
            throw AssertionError(
                "Native pipeline fixture failed; artifacts saved to ${failureDir.absolutePath} " +
                    "(pull with `adb pull`), inspect with `adb logcat -s hpcupsjni gsjni`", e,
            )
        }
    }
}
```

- [ ] **Step 3: Run it on a real arm64 phone**

Run: `./gradlew :app:connectedDebugAndroidTest --tests "dev.jaspreet.printserver.NativePipelineFixtureTest"`
Expected: PASSES. This is the point where hpcups patch/build problems (Task 4) or PPD/colorspace mismatches (Task 5) actually surface — debug here, not three tasks later. On failure, `adb pull /data/data/dev.jaspreet.printserver/cache/fixture-failure ./fixture-failure` retrieves the intermediate PDF/PPM/PCL for inspection.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "test: early native pipeline fixture proof (gs -> ppm -> hpcups)"
```

---

### Task 6: PPM parser (pure Kotlin, TDD resumes)

**Files:**
- Create: `SRC/render/PpmImage.kt`
- Test: `TST/render/PpmImageTest.kt`

- [ ] **Step 1: Write the failing tests**

`TST/render/PpmImageTest.kt`:
```kotlin
package dev.jaspreet.printserver.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class PpmImageTest {

    private fun ppm(header: String, pixels: ByteArray) =
        ByteArrayInputStream(header.toByteArray(Charsets.US_ASCII) + pixels)

    @Test
    fun `parses P6 header and pixel data`() {
        val pixels = byteArrayOf(255.toByte(), 0, 0, 0, 255.toByte(), 0) // 2x1: red, green
        val img = PpmImage.parse(ppm("P6\n2 1\n255\n", pixels))
        assertEquals(2, img.width)
        assertEquals(1, img.height)
        assertArrayEquals(pixels, img.rgb)
    }

    @Test
    fun `skips comment lines in header`() {
        val pixels = byteArrayOf(1, 2, 3)
        val img = PpmImage.parse(ppm("P6\n# ghostscript output\n1 1\n255\n", pixels))
        assertEquals(1, img.width)
        assertArrayEquals(pixels, img.rgb)
    }

    @Test(expected = IOException::class)
    fun `rejects non-P6 magic`() {
        PpmImage.parse(ppm("P3\n1 1\n255\n", byteArrayOf(1, 2, 3)))
    }

    @Test(expected = IOException::class)
    fun `rejects truncated pixel data`() {
        PpmImage.parse(ppm("P6\n2 2\n255\n", byteArrayOf(1, 2, 3))) // needs 12 bytes
    }

    @Test(expected = IOException::class)
    fun `rejects dimensions that would overflow pixel buffer size`() {
        // width * height * 3 as Int would overflow; must be rejected before allocating.
        PpmImage.parse(ppm("P6\n50000 50000\n255\n", ByteArray(0)))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.render.PpmImageTest"`
Expected: compilation FAILURE — `PpmImage` not defined.

- [ ] **Step 3: Write the implementation**

`SRC/render/PpmImage.kt`:
```kotlin
package dev.jaspreet.printserver.render

import java.io.IOException
import java.io.InputStream

/** Minimal binary PPM (P6, maxval 255) reader — the format ppmraw emits. */
class PpmImage(val width: Int, val height: Int, val rgb: ByteArray) {

    companion object {
        fun parse(input: InputStream): PpmImage {
            if (nextToken(input) != "P6") throw IOException("Not a P6 PPM")
            val width = nextToken(input).toIntOrNull() ?: throw IOException("Bad width")
            val height = nextToken(input).toIntOrNull() ?: throw IOException("Bad height")
            val maxval = nextToken(input).toIntOrNull() ?: throw IOException("Bad maxval")
            if (maxval != 255) throw IOException("Unsupported maxval $maxval")
            // Compute in Long first: width * height * 3 as Int can silently overflow
            // and wrap negative, which would otherwise pass to ByteArray(negative).
            val expectedLong = width.toLong() * height.toLong() * 3L
            if (width <= 0 || height <= 0 || expectedLong > Int.MAX_VALUE) {
                throw IOException("Invalid or oversized PPM dimensions: ${width}x$height")
            }
            val expected = expectedLong.toInt()
            val rgb = ByteArray(expected)
            var read = 0
            while (read < expected) {
                val n = input.read(rgb, read, expected - read)
                if (n < 0) throw IOException("Truncated PPM: got $read of $expected bytes")
                read += n
            }
            return PpmImage(width, height, rgb)
        }

        /** Whitespace-delimited token reader that skips '#' comment lines. */
        private fun nextToken(input: InputStream): String {
            val sb = StringBuilder()
            var c = input.read()
            while (c != -1) {
                when {
                    c == '#'.code -> while (c != -1 && c != '\n'.code) c = input.read()
                    Character.isWhitespace(c) -> if (sb.isNotEmpty()) return sb.toString()
                    else -> sb.append(c.toChar())
                }
                c = input.read()
            }
            if (sb.isEmpty()) throw IOException("EOF in PPM header")
            return sb.toString()
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.render.PpmImageTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: PPM (P6) parser for Ghostscript output"
```

---

### Task 7: RenderingPipeline interface + native implementation

**Files:**
- Create: `SRC/render/RenderingPipeline.kt`
- Create: `SRC/render/NativeRenderingPipeline.kt` (composes `GhostscriptRenderer` from Task 3 and `HpcupsNative` from Task 4 — both already exist)
- Create: `TST/render/FakeRenderingPipeline.kt`

The interface + fake are what all later JVM tests use; `NativeRenderingPipeline` itself is device-only (already proven end-to-end by Task 5.5's fixture test; re-verified in context in Task 12).

- [ ] **Step 1: Write interface and fake**

`SRC/render/RenderingPipeline.kt`:
```kotlin
package dev.jaspreet.printserver.render

import java.io.File

/** Converts one spooled PDF into printer-ready bytes (PCL3-GUI for hpcups models). */
interface RenderingPipeline {
    /** Renders [pdf] and writes printer bytes to [output]. Throws IOException on failure. */
    fun render(pdf: File, output: File)
}
```

`TST/render/FakeRenderingPipeline.kt`:
```kotlin
package dev.jaspreet.printserver.render

import java.io.File
import java.io.IOException

class FakeRenderingPipeline(
    private val result: ByteArray = "FAKE-PCL".toByteArray(),
    private val failWith: IOException? = null,
) : RenderingPipeline {
    val rendered = mutableListOf<File>()

    override fun render(pdf: File, output: File) {
        rendered += pdf
        failWith?.let { throw it }
        output.writeBytes(result)
    }
}
```

- [ ] **Step 2: Write the pipeline that composes the already-built native pieces**

`SRC/render/NativeRenderingPipeline.kt`:
```kotlin
package dev.jaspreet.printserver.render

import java.io.File
import java.io.IOException

/**
 * Ghostscript (PDF -> PPM pages) then hpcups (RGB -> PCL3-GUI).
 * Multi-page PDFs: ppmraw with %d in the output name emits one file per page;
 * pages are encoded in order and concatenated into [output].
 */
class NativeRenderingPipeline(
    private val workDir: File,
    private val ppdPath: String,
    private val dpi: Int = 300,
) : RenderingPipeline {

    private val ghostscript = GhostscriptRenderer(dpi)

    override fun render(pdf: File, output: File) {
        val pageDir = File(workDir, "pages-${System.nanoTime()}").apply { mkdirs() }
        try {
            val pattern = File(pageDir, "page-%03d.ppm")
            ghostscript.renderToPpm(pdf, pattern)
            val pages = pageDir.listFiles { f -> f.name.endsWith(".ppm") }?.sortedBy { it.name }
                ?: emptyList()
            if (pages.isEmpty()) throw IOException("Ghostscript produced no pages")

            output.outputStream().use { out ->
                for (page in pages) {
                    val img = page.inputStream().buffered().use { PpmImage.parse(it) }
                    val pageOut = File(pageDir, "${page.name}.pcl")
                    val code = HpcupsNative.encode(
                        img.rgb, img.width, img.height, dpi, ppdPath, pageOut.absolutePath,
                    )
                    if (code != 0) throw IOException("hpcups failed with code $code on ${page.name}")
                    pageOut.inputStream().use { it.copyTo(out) }
                }
            }
        } finally {
            pageDir.deleteRecursively()
        }
    }
}
```

Note: `GhostscriptRenderer.renderToPpm` receives the `%03d` pattern file — gs expands `-o page-%03d.ppm` itself, and its output-exists check must be adjusted: change that check in `GhostscriptRenderer` to verify the *directory* contains at least one `.ppm` when the name contains `%`, keeping the original single-file check otherwise:

```kotlin
        val producedSomething = if (outPpm.name.contains('%')) {
            outPpm.parentFile?.listFiles { f -> f.name.endsWith(".ppm") }?.isNotEmpty() == true
        } else {
            outPpm.exists() && outPpm.length() > 0L
        }
        if (!producedSomething) throw IOException("Ghostscript produced no output")
```

- [ ] **Step 3: Verify build + existing tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "feat: rendering pipeline interface with native gs+hpcups implementation"
```

---

### Task 8: Job model + queue

**Files:**
- Create: `SRC/jobs/PrintJob.kt`
- Create: `SRC/jobs/JobQueue.kt`
- Test: `TST/jobs/JobQueueTest.kt`

- [ ] **Step 1: Write the failing tests**

`TST/jobs/JobQueueTest.kt`:
```kotlin
package dev.jaspreet.printserver.jobs

import dev.jaspreet.printserver.render.FakeRenderingPipeline
import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JobQueueTest {

    private var queue: JobQueue? = null
    private val tempFiles = mutableListOf<File>()

    private fun pdf(): File = File.createTempFile("job", ".pdf").also {
        it.writeText("%PDF-fake")
        tempFiles += it
    }

    @After
    fun tearDown() {
        queue?.shutdown()
        tempFiles.forEach { it.delete() }
    }

    @Test
    fun `job runs through pipeline and lands on the printer`() {
        val printer = FakePrinterTransport { ByteArray(0) }
        val done = CountDownLatch(1)
        val q = JobQueue(FakeRenderingPipeline("PCL!".toByteArray()), { printer }) { done.countDown() }
        queue = q
        val id = q.submit(pdf(), "test-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(JobState.COMPLETED, q.get(id)!!.state)
        assertEquals("PCL!", String(printer.lastRequest()))
    }

    @Test
    fun `render failure aborts the job and deletes the spool file`() {
        val spool = pdf()
        val done = CountDownLatch(1)
        val q = JobQueue(
            FakeRenderingPipeline(failWith = IOException("bad pdf")),
            { FakePrinterTransport { ByteArray(0) } },
        ) { done.countDown() }
        queue = q
        val id = q.submit(spool, "broken-doc")
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(JobState.ABORTED, q.get(id)!!.state)
        assertFalse(spool.exists())
    }

    @Test
    fun `cancel while pending prevents processing`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingPipeline = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(pdf: File, output: File) {
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
                output.writeBytes("X".toByteArray())
            }
        }
        val q = JobQueue(blockingPipeline, { FakePrinterTransport { ByteArray(0) } }) {}
        queue = q
        q.submit(pdf(), "job-a")                      // occupies the worker
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val second = q.submit(pdf(), "job-b")          // sits pending
        assertTrue(q.cancel(second))
        release.countDown()
        assertEquals(JobState.CANCELED, q.get(second)!!.state)
    }

    @Test
    fun `unknown job id returns null`() {
        val q = JobQueue(FakeRenderingPipeline(), { FakePrinterTransport { ByteArray(0) } }) {}
        queue = q
        assertNull(q.get(999))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.jobs.JobQueueTest"`
Expected: compilation FAILURE — `JobQueue`, `JobState` not defined.

- [ ] **Step 3: Write the implementation**

`SRC/jobs/PrintJob.kt`:
```kotlin
package dev.jaspreet.printserver.jobs

import java.io.File

enum class JobState { PENDING, PROCESSING, COMPLETED, ABORTED, CANCELED }

class PrintJob(
    val id: Int,
    val name: String,
    val spoolFile: File,
) {
    @Volatile var state: JobState = JobState.PENDING
    @Volatile var stateReason: String = "none"
}
```

`SRC/jobs/JobQueue.kt`:
```kotlin
package dev.jaspreet.printserver.jobs

import dev.jaspreet.printserver.render.RenderingPipeline
import dev.jaspreet.printserver.usb.UsbTransport
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Single-worker FIFO print queue: one physical printer, one USB channel,
 * deliberately no concurrency. Ghostscript/hpcups are not reentrant, so the
 * single worker is also what makes native rendering safe.
 */
class JobQueue(
    private val pipeline: RenderingPipeline,
    private val transportProvider: () -> UsbTransport,
    private val onJobFinished: (PrintJob) -> Unit = {},
) {
    private val nextId = AtomicInteger(1)
    private val jobs = ConcurrentHashMap<Int, PrintJob>()
    private val pending = LinkedBlockingQueue<PrintJob>()
    @Volatile private var running = true

    private val worker = thread(name = "print-worker") {
        while (running) {
            val job = try { pending.take() } catch (_: InterruptedException) { break }
            if (job.state == JobState.CANCELED) continue
            process(job)
        }
    }

    fun submit(spoolFile: File, name: String): Int {
        val job = PrintJob(nextId.getAndIncrement(), name, spoolFile)
        jobs[job.id] = job
        pending.put(job)
        return job.id
    }

    fun get(id: Int): PrintJob? = jobs[id]

    /** True if the job was still pending and is now canceled. */
    fun cancel(id: Int): Boolean {
        val job = jobs[id] ?: return false
        synchronized(job) {
            if (job.state != JobState.PENDING) return false
            job.state = JobState.CANCELED
            job.spoolFile.delete()
            return true
        }
    }

    private fun process(job: PrintJob) {
        synchronized(job) {
            if (job.state == JobState.CANCELED) return
            job.state = JobState.PROCESSING
        }
        val rendered = File(job.spoolFile.parentFile, "${job.spoolFile.name}.out")
        try {
            checkFreeSpace(job.spoolFile.parentFile)
            pipeline.render(job.spoolFile, rendered)
            writeToUsb(rendered)
            job.state = JobState.COMPLETED
        } catch (e: Exception) {
            job.state = JobState.ABORTED
            job.stateReason = "document-format-error"
        } finally {
            job.spoolFile.delete()
            rendered.delete()
            onJobFinished(job)
        }
    }

    /**
     * Streams the rendered file to the printer in fixed chunks instead of
     * loading it whole — multi-page color output at 300dpi can be tens of MB.
     */
    private fun writeToUsb(rendered: File) {
        val transport = transportProvider()
        val buf = ByteArray(65536)
        rendered.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                transport.write(buf, 0, n)
            }
        }
    }

    private fun checkFreeSpace(dir: File) {
        if (dir.usableSpace < MIN_FREE_SPACE_BYTES) {
            throw java.io.IOException("Insufficient cache space to render job (need ${MIN_FREE_SPACE_BYTES / 1_000_000}MB free)")
        }
    }

    fun shutdown() {
        running = false
        worker.interrupt()
    }

    companion object {
        // A single 300dpi color A4 page is tens of MB uncompressed; leave headroom for multi-page jobs.
        private const val MIN_FREE_SPACE_BYTES = 200L * 1_000_000L
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.jobs.JobQueueTest"`
Expected: 4 tests PASS.

- [ ] **Step 5: Add a stale-spool cleanup helper**

A prior run killed mid-job (Doze, force-stop, crash) can leave `.pdf`/`.out` files behind in the spool directory; job ids restart from 1 on every launch anyway, so any leftover file is orphaned and safe to delete. Add this to the `JobQueue` companion object (same file, alongside `MIN_FREE_SPACE_BYTES`):
```kotlin
        /** Deletes leftover spool/render files from a run that never finished cleanly. Call before construction. */
        fun cleanStaleSpool(dir: File) {
            dir.listFiles()?.forEach { it.delete() }
        }
```
`ServerService.startLegacyPipeline` (edited in Task 12) calls this before constructing `JobQueue` — cleanup belongs at the call site since only the service owns the spool directory's lifetime.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: single-worker print job queue with cancel semantics"
```

---

### Task 9: Printer capabilities (synthetic attributes)

**Files:**
- Create: `SRC/ipp/PrinterCapabilities.kt`
- Test: `TST/ipp/PrinterCapabilitiesTest.kt`

- [ ] **Step 1: Write the failing tests**

`TST/ipp/PrinterCapabilitiesTest.kt`:
```kotlin
package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Types
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class PrinterCapabilitiesTest {

    private val caps = PrinterCapabilities.deskJet2300(URI.create("ipp://192.168.1.5:8631/ipp/print"))

    @Test
    fun `advertises pdf as the only document format`() {
        val group = caps.asPrinterAttributes()
        assertEquals(listOf("application/pdf"), group.getValues(Types.documentFormatSupported))
        assertEquals("application/pdf", group.getValue(Types.documentFormatDefault))
    }

    @Test
    fun `reports required identity and state attributes`() {
        val group = caps.asPrinterAttributes()
        assertTrue(group.getValue(Types.printerMakeAndModel)!!.value.contains("DeskJet 2300"))
        assertEquals(true, group.getValue(Types.colorSupported))
        assertTrue(group.getValues(Types.ippVersionsSupported).contains("2.0"))
        assertTrue(group.getValues(Types.operationsSupported).isNotEmpty())
    }

    @Test
    fun `printer info feeds txt records`() {
        val info = caps.toPrinterInfo()
        assertEquals(listOf("application/pdf"), info.formats)
        assertTrue(info.color)
        val txt = TxtRecords.forIpp(info)
        assertEquals("application/pdf", txt["pdl"])
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.ipp.PrinterCapabilitiesTest"`
Expected: compilation FAILURE — `PrinterCapabilities` not defined.

- [ ] **Step 3: Write the implementation**

`SRC/ipp/PrinterCapabilities.kt`:
```kotlin
package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.encoding.AttributeGroup.Companion.groupOf
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Operation
import com.hp.jipp.model.Types
import java.net.URI
import java.util.UUID

/**
 * Hardcoded capabilities for a Tier-2 (host-based) printer — there is no
 * printer-side IPP to query, so the app is the source of truth.
 */
class PrinterCapabilities(
    val makeAndModel: String,
    val formats: List<String>,
    val color: Boolean,
    val printerUri: URI,
    val uuid: UUID,
) {
    fun asPrinterAttributes(): AttributeGroup = groupOf(
        Tag.printerAttributes,
        Types.printerUriSupported.of(printerUri),
        Types.printerName.of(makeAndModel),
        Types.printerMakeAndModel.of(makeAndModel),
        Types.printerState.of(com.hp.jipp.model.PrinterState.idle),
        Types.printerStateReasons.of("none"),
        Types.printerIsAcceptingJobs.of(true),
        Types.printerUuid.of(URI.create("urn:uuid:$uuid")),
        Types.ippVersionsSupported.of("1.1", "2.0"),
        Types.operationsSupported.of(
            Operation.printJob.code, Operation.validateJob.code,
            Operation.getPrinterAttributes.code, Operation.getJobAttributes.code,
            Operation.cancelJob.code,
        ),
        Types.charsetConfigured.of("utf-8"),
        Types.charsetSupported.of("utf-8"),
        Types.naturalLanguageConfigured.of("en"),
        Types.generatedNaturalLanguageSupported.of("en"),
        Types.documentFormatDefault.of(formats.first()),
        Types.documentFormatSupported.of(formats),
        Types.colorSupported.of(color),
        Types.compressionSupported.of("none"),
        Types.mediaDefault.of("iso_a4_210x297mm"),
        Types.mediaSupported.of("iso_a4_210x297mm", "na_letter_8.5x11in"),
        Types.pdlOverrideSupported.of("attempted"),
    )

    fun toPrinterInfo(): PrinterInfo =
        PrinterInfo(makeAndModel, formats, color, uuid.toString(), urf = emptyList())

    companion object {
        fun deskJet2300(printerUri: URI, uuid: UUID = STABLE_UUID): PrinterCapabilities =
            PrinterCapabilities(
                makeAndModel = "HP DeskJet 2300 series",
                formats = listOf("application/pdf"),
                color = true,
                printerUri = printerUri,
                uuid = uuid,
            )

        // Fixed so clients don't see a "new printer" after every app restart.
        private val STABLE_UUID: UUID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7")
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.ipp.PrinterCapabilitiesTest"`
Expected: 3 tests PASS.
(Same JIPP-version caveat as the Tier-1 plan: if accessor/type names differ on the installed JIPP, adjust the implementation, never the test intent.)

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: hardcoded printer capabilities for DeskJet 2300 family"
```

---

### Task 10: Chunked-body decoder

Tier 1 only *copies* chunked bodies verbatim; the local IPP server must *consume* them (CUPS clients send Print-Job chunked).

**Files:**
- Create: `SRC/http/BodyReader.kt`
- Test: `TST/http/BodyReaderTest.kt`

- [ ] **Step 1: Write the failing tests**

`TST/http/BodyReaderTest.kt`:
```kotlin
package dev.jaspreet.printserver.http

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class BodyReaderTest {

    private fun head(vararg headers: Pair<String, String>) =
        HttpHead("POST / HTTP/1.1", headers.toList())

    @Test
    fun `reads content-length body`() {
        val input = ByteArrayInputStream("helloEXTRA".toByteArray())
        val body = BodyReader.readAll(head("Content-Length" to "5"), input)
        assertEquals("hello", String(body))
    }

    @Test
    fun `decodes chunked body`() {
        val chunked = "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n"
        val input = ByteArrayInputStream(chunked.toByteArray(Charsets.ISO_8859_1))
        val body = BodyReader.readAll(head("Transfer-Encoding" to "chunked"), input)
        assertEquals("Wikipedia", String(body))
    }

    @Test
    fun `no framing means empty body`() {
        val body = BodyReader.readAll(head(), ByteArrayInputStream("x".toByteArray()))
        assertEquals(0, body.size)
    }

    @Test(expected = BodyTooLargeException::class)
    fun `rejects content-length body over the configured limit`() {
        BodyReader.readAll(
            head("Content-Length" to "999999999999"),
            ByteArrayInputStream(ByteArray(0)),
            maxBytes = 1024,
        )
    }

    @Test(expected = BodyTooLargeException::class)
    fun `rejects chunked body whose cumulative size exceeds the limit`() {
        // Ten 200-byte chunks = 2000 bytes, over a 1024 limit.
        val chunk = "C8\r\n" + "x".repeat(200) + "\r\n"
        val chunked = chunk.repeat(10) + "0\r\n\r\n"
        BodyReader.readAll(
            head("Transfer-Encoding" to "chunked"),
            ByteArrayInputStream(chunked.toByteArray(Charsets.ISO_8859_1)),
            maxBytes = 1024,
        )
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.http.BodyReaderTest"`
Expected: compilation FAILURE — `BodyReader` not defined.

- [ ] **Step 3: Write the implementation**

`SRC/http/BodyReader.kt`:
```kotlin
package dev.jaspreet.printserver.http

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Body exceeded the caller's [BodyReader.readAll] maxBytes — a print client sent (or claimed) too much data. */
class BodyTooLargeException(message: String) : IOException(message)

/** Consumes one HTTP body into memory, DECODING chunked framing (contrast BodyCopier, which copies it verbatim). */
object BodyReader {

    /** Default cap for an incoming print document: generous for a multi-page PDF, small enough to bound memory/spool use. */
    const val DEFAULT_MAX_BYTES = 200L * 1_000_000L

    fun readAll(head: HttpHead, from: InputStream, maxBytes: Long = DEFAULT_MAX_BYTES): ByteArray {
        val out = ByteArrayOutputStream()
        val te = head.get("Transfer-Encoding")
        if (te != null && te.contains("chunked", ignoreCase = true)) {
            readChunked(from, out, maxBytes)
        } else {
            val length = head.get("Content-Length")?.trim()?.toLongOrNull() ?: 0L
            if (length > maxBytes) {
                throw BodyTooLargeException("Content-Length $length exceeds limit $maxBytes")
            }
            copyExact(from, out, length)
        }
        return out.toByteArray()
    }

    private fun copyExact(from: InputStream, to: ByteArrayOutputStream, count: Long) {
        val buf = ByteArray(65536)
        var left = count
        while (left > 0) {
            val n = from.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (n < 0) throw IOException("EOF mid-body, expected $left more bytes")
            to.write(buf, 0, n)
            left -= n
        }
    }

    private fun readChunked(from: InputStream, to: ByteArrayOutputStream, maxBytes: Long) {
        var total = 0L
        while (true) {
            val sizeLine = HttpHead.readLine(from) ?: throw IOException("EOF at chunk size")
            val size = sizeLine.substringBefore(';').trim().toLongOrNull(16)
                ?: throw IOException("Bad chunk size: $sizeLine")
            if (size == 0L) {
                while (true) {
                    val line = HttpHead.readLine(from) ?: throw IOException("EOF in trailers")
                    if (line.isEmpty()) return
                }
            }
            total += size
            // A chunked body has no advance total, so the limit is checked cumulatively
            // per chunk instead of up front the way Content-Length allows.
            if (total > maxBytes) {
                throw BodyTooLargeException("Chunked body exceeded limit $maxBytes at $total bytes")
            }
            copyExact(from, to, size)
            val cr = from.read(); val lf = from.read()
            if (cr != '\r'.code || lf != '\n'.code) throw IOException("Missing CRLF after chunk")
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.http.BodyReaderTest"`
Expected: 5 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: chunk-decoding HTTP body reader for local IPP server"
```

---

### Task 11: LocalIppServer

**Files:**
- Create: `SRC/ipp/LocalIppServer.kt`
- Test: `TST/ipp/LocalIppServerTest.kt`

- [ ] **Step 1: Write the failing tests**

`TST/ipp/LocalIppServerTest.kt`:
```kotlin
package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.AttributeGroup.Companion.groupOf
import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.Operation
import com.hp.jipp.model.Status
import com.hp.jipp.model.Types
import dev.jaspreet.printserver.jobs.JobQueue
import dev.jaspreet.printserver.render.FakeRenderingPipeline
import dev.jaspreet.printserver.usb.FakePrinterTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

class LocalIppServerTest {

    private var server: LocalIppServer? = null
    private var queue: JobQueue? = null

    @After
    fun tearDown() {
        server?.stop()
        queue?.shutdown()
    }

    private fun start(pipeline: dev.jaspreet.printserver.render.RenderingPipeline = FakeRenderingPipeline()): Int {
        val q = JobQueue(pipeline, { FakePrinterTransport { ByteArray(0) } })
        queue = q
        val caps = PrinterCapabilities.deskJet2300(URI.create("ipp://127.0.0.1:0/ipp/print"))
        val s = LocalIppServer(port = 0, capabilities = caps, jobQueue = q, spoolDir = createTempDir())
        s.start(bindAddress = null)
        server = s
        return s.actualPort
    }

    private fun ipp(port: Int, packet: IppPacket, document: ByteArray = ByteArray(0)): IppPacket {
        val body = ByteArrayOutputStream()
        IppOutputStream(body).write(packet)
        body.write(document)
        val conn = URL("http://127.0.0.1:$port/ipp/print").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/ipp")
        conn.outputStream.use { it.write(body.toByteArray()) }
        assertEquals(200, conn.responseCode)
        return conn.inputStream.use { IppInputStream(it).readPacket() }
    }

    private fun operationGroup() = groupOf(
        Tag.operationAttributes,
        Types.attributesCharset.of("utf-8"),
        Types.attributesNaturalLanguage.of("en"),
        Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
    )

    @Test
    fun `answers get-printer-attributes with pdf support`() {
        val port = start()
        val resp = ipp(port, IppPacket(Operation.getPrinterAttributes, 1, operationGroup()))
        assertEquals(Status.successfulOk, resp.status)
        val formats = resp[Tag.printerAttributes]!!.getValues(Types.documentFormatSupported)
        assertEquals(listOf("application/pdf"), formats)
    }

    @Test
    fun `print-job spools document and returns job id`() {
        val port = start()
        val resp = ipp(
            port,
            IppPacket(Operation.printJob, 2, operationGroup()),
            "%PDF-1.4 fake".toByteArray(),
        )
        assertEquals(Status.successfulOk, resp.status)
        val jobId = resp[Tag.jobAttributes]!!.getValue(Types.jobId)
        assertNotNull(jobId)
        assertNotNull(queue!!.get(jobId!!))
    }

    @Test
    fun `get-job-attributes reports job state`() {
        val port = start()
        val submit = ipp(port, IppPacket(Operation.printJob, 3, operationGroup()), "%PDF".toByteArray())
        val jobId = submit[Tag.jobAttributes]!!.getValue(Types.jobId)!!
        val query = IppPacket(
            Operation.getJobAttributes, 4,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
                Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
                Types.jobId.of(jobId),
            ),
        )
        val resp = ipp(port, query)
        assertEquals(Status.successfulOk, resp.status)
        assertNotNull(resp[Tag.jobAttributes]!!.getValue(Types.jobState))
    }

    @Test
    fun `unsupported operation returns error status`() {
        val port = start()
        val resp = ipp(port, IppPacket(Operation.pausePrinter, 5, operationGroup()))
        assertEquals(Status.serverErrorOperationNotSupported, resp.status)
    }

    @Test
    fun `print-job reports the job's real queue state, not a hardcoded value`() {
        // A pipeline that blocks forever: the worker thread picks the job up
        // and parks in render(), so it never reaches COMPLETED during this test.
        // If printJob() still hardcodes "processing" this test can't tell the
        // difference — the point is it must come from jobQueue.get(id) instead.
        val block = java.util.concurrent.CountDownLatch(1)
        val neverCompletes = object : dev.jaspreet.printserver.render.RenderingPipeline {
            override fun render(pdf: File, output: File) { block.await() }
        }
        val port = start(neverCompletes)
        val resp = ipp(port, IppPacket(Operation.printJob, 6, operationGroup()), "%PDF".toByteArray())
        val jobId = resp[Tag.jobAttributes]!!.getValue(Types.jobId)!!
        val actualState = queue!!.get(jobId)!!.state
        assertTrue(
            "job must not already be COMPLETED (pipeline is still blocked)",
            actualState != dev.jaspreet.printserver.jobs.JobState.COMPLETED,
        )
        val reportedState = resp[Tag.jobAttributes]!!.getValue(Types.jobState)
        assertEquals(
            "reported jobState must match the real queue state, not a hardcoded one",
            if (actualState == dev.jaspreet.printserver.jobs.JobState.PENDING)
                com.hp.jipp.model.JobState.pending
            else
                com.hp.jipp.model.JobState.processing,
            reportedState,
        )
        block.countDown()
    }

    @Test
    fun `get-printer-attributes honors requested-attributes and omits the rest`() {
        val port = start()
        val request = IppPacket(
            Operation.getPrinterAttributes, 7,
            groupOf(
                Tag.operationAttributes,
                Types.attributesCharset.of("utf-8"),
                Types.attributesNaturalLanguage.of("en"),
                Types.printerUri.of(URI.create("ipp://127.0.0.1/ipp/print")),
                Types.requestedAttributes.of("printer-name"),
            ),
        )
        val resp = ipp(port, request)
        assertEquals(Status.successfulOk, resp.status)
        val group = resp[Tag.printerAttributes]!!
        assertNotNull(group.getValue(Types.printerName))
        assertEquals(
            "only the requested attribute should be present",
            null,
            group.getValue(Types.documentFormatSupported),
        )
    }

    @Test
    fun `oversized document is rejected with an ipp error, not a dropped connection`() {
        val port = start()
        // BodyReader's limit is enforced inside LocalIppServer; a body of a
        // few KB is nowhere near a real 200MB cap, so the server is
        // constructed with a tiny limit for this test via the same field
        // LocalIppServer exposes (see Step 3's maxDocumentBytes constructor param).
        val q = JobQueue(FakeRenderingPipeline(), { FakePrinterTransport { ByteArray(0) } })
        queue = q
        val caps = PrinterCapabilities.deskJet2300(URI.create("ipp://127.0.0.1:0/ipp/print"))
        val tinyLimitServer = LocalIppServer(
            port = 0, capabilities = caps, jobQueue = q, spoolDir = createTempDir(), maxDocumentBytes = 16,
        )
        tinyLimitServer.start(bindAddress = null)
        server = tinyLimitServer
        val resp = ipp(
            tinyLimitServer.actualPort,
            IppPacket(Operation.printJob, 8, operationGroup()),
            "this document is definitely over sixteen bytes".toByteArray(),
        )
        assertEquals(Status.clientErrorRequestEntityTooLarge, resp.status)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.ipp.LocalIppServerTest"`
Expected: compilation FAILURE — `LocalIppServer` not defined.

- [ ] **Step 3: Write the implementation**

`SRC/ipp/LocalIppServer.kt`:
```kotlin
package dev.jaspreet.printserver.ipp

import com.hp.jipp.encoding.AttributeGroup
import com.hp.jipp.encoding.AttributeGroup.Companion.groupOf
import com.hp.jipp.encoding.IppInputStream
import com.hp.jipp.encoding.IppOutputStream
import com.hp.jipp.encoding.IppPacket
import com.hp.jipp.encoding.Tag
import com.hp.jipp.model.JobState as IppJobState
import com.hp.jipp.model.Operation
import com.hp.jipp.model.Status
import com.hp.jipp.model.Types
import dev.jaspreet.printserver.http.BodyReader
import dev.jaspreet.printserver.http.BodyTooLargeException
import dev.jaspreet.printserver.http.HttpHead
import dev.jaspreet.printserver.jobs.JobQueue
import dev.jaspreet.printserver.jobs.JobState
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors

/**
 * A synthetic IPP printer: the app IS the printer as far as clients can tell.
 * Tier-2 counterpart of IppRelayServer (there is no printer-side IPP to relay).
 */
class LocalIppServer(
    private val port: Int,
    private val capabilities: PrinterCapabilities,
    private val jobQueue: JobQueue,
    private val spoolDir: File,
    private val maxDocumentBytes: Long = BodyReader.DEFAULT_MAX_BYTES,
) {
    @Volatile private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()

    val actualPort: Int get() = serverSocket?.localPort ?: port

    fun start(bindAddress: InetAddress?) {
        val ss = ServerSocket(port, 50, bindAddress)
        serverSocket = ss
        executor.execute {
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: IOException) { break }
                executor.execute { handleClient(client) }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = 30_000
        client.use {
            val cin = BufferedInputStream(client.getInputStream())
            val cout = BufferedOutputStream(client.getOutputStream())
            while (true) {
                val head = try { HttpHead.parse(cin) ?: break } catch (_: IOException) { break }
                val response = try {
                    val body = BodyReader.readAll(head, cin, maxDocumentBytes)
                    handleIpp(body)
                } catch (e: BodyTooLargeException) {
                    // The client already sent bytes we're discarding; the connection
                    // can't be reused, so this is the last response on it (Step below).
                    errorResponse(0, Status.clientErrorRequestEntityTooLarge)
                } catch (e: IOException) {
                    break
                } catch (e: Exception) {
                    errorResponse(0, Status.serverErrorInternalError)
                }
                val respBytes = ByteArrayOutputStream().also { IppOutputStream(it).write(response) }.toByteArray()
                cout.write(
                    ("HTTP/1.1 200 OK\r\nContent-Type: application/ipp\r\n" +
                        "Content-Length: ${respBytes.size}\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                )
                cout.write(respBytes)
                cout.flush()
                if (response.status == Status.clientErrorRequestEntityTooLarge) break
                if (head.get("Connection")?.equals("close", ignoreCase = true) == true) break
            }
        }
    }

    private fun handleIpp(body: ByteArray): IppPacket {
        val input = IppInputStream(ByteArrayInputStream(body))
        val request = input.readPacket()
        // Any bytes after the IPP packet are the document payload (Print-Job).
        val document = input.readBytes()

        return when (request.code) {
            Operation.getPrinterAttributes.code -> getPrinterAttributes(request)
            Operation.validateJob.code -> IppPacket(
                Status.successfulOk, request.requestId, operationGroup(),
            )
            Operation.printJob.code -> printJob(request, document)
            Operation.getJobAttributes.code -> jobAttributes(request)
            Operation.cancelJob.code -> cancelJob(request)
            else -> errorResponse(request.requestId, Status.serverErrorOperationNotSupported)
        }
    }

    private fun getPrinterAttributes(request: IppPacket): IppPacket {
        val full = capabilities.asPrinterAttributes()
        val requested = request[Tag.operationAttributes]?.getValues(Types.requestedAttributes)
        val filtered = if (requested.isNullOrEmpty() || requested.contains("all")) {
            full
        } else {
            AttributeGroup.groupOf(Tag.printerAttributes, full.filter { it.name in requested })
        }
        return IppPacket(Status.successfulOk, request.requestId, operationGroup(), filtered)
    }

    private fun printJob(request: IppPacket, document: ByteArray): IppPacket {
        if (document.isEmpty()) {
            return errorResponse(request.requestId, Status.clientErrorBadRequest)
        }
        val requestedUri = request[Tag.operationAttributes]?.getValue(Types.printerUri)
        if (requestedUri == null) {
            return errorResponse(request.requestId, Status.clientErrorBadRequest)
        }
        spoolDir.mkdirs()
        val spool = File.createTempFile("job", ".pdf", spoolDir)
        spool.writeBytes(document)
        val name = request[Tag.operationAttributes]?.getValue(Types.jobName) ?: "untitled"
        val jobId = jobQueue.submit(spool, name)
        // Report the queue's real state — submit() only enqueues, it does not
        // guarantee the worker has started (previously this hardcoded "processing").
        val actualState = jobQueue.get(jobId)?.state ?: JobState.PENDING
        return IppPacket(
            Status.successfulOk, request.requestId,
            operationGroup(),
            groupOf(
                Tag.jobAttributes,
                Types.jobId.of(jobId),
                Types.jobUri.of(capabilities.printerUri.resolve("job/$jobId")),
                Types.jobState.of(ippState(actualState)),
                Types.jobStateReasons.of("none"),
            ),
        )
    }

    private fun jobAttributes(request: IppPacket): IppPacket {
        val jobId = request[Tag.operationAttributes]?.getValue(Types.jobId)
            ?: return errorResponse(request.requestId, Status.clientErrorBadRequest)
        val job = jobQueue.get(jobId)
            ?: return errorResponse(request.requestId, Status.clientErrorNotFound)
        return IppPacket(
            Status.successfulOk, request.requestId,
            operationGroup(),
            groupOf(
                Tag.jobAttributes,
                Types.jobId.of(job.id),
                Types.jobUri.of(capabilities.printerUri.resolve("job/${job.id}")),
                Types.jobState.of(ippState(job.state)),
                Types.jobStateReasons.of(job.stateReason),
            ),
        )
    }

    private fun cancelJob(request: IppPacket): IppPacket {
        val jobId = request[Tag.operationAttributes]?.getValue(Types.jobId)
            ?: return errorResponse(request.requestId, Status.clientErrorBadRequest)
        val job = jobQueue.get(jobId)
        return if (jobQueue.cancel(jobId)) {
            IppPacket(Status.successfulOk, request.requestId, operationGroup())
        } else {
            // Surface *why* it couldn't be canceled (already processing/completed/etc)
            // via status-message, the standard IPP attribute for human-readable error text.
            val reason = job?.state?.name?.lowercase() ?: "unknown"
            errorResponse(
                request.requestId, Status.clientErrorNotPossible,
                groupOf(Tag.operationAttributes, Types.statusMessage.of("job already $reason")),
            )
        }
    }

    private fun ippState(state: JobState): IppJobState = when (state) {
        JobState.PENDING -> IppJobState.pending
        JobState.PROCESSING -> IppJobState.processing
        JobState.COMPLETED -> IppJobState.completed
        JobState.ABORTED -> IppJobState.aborted
        JobState.CANCELED -> IppJobState.canceled
    }

    private fun operationGroup() = groupOf(
        Tag.operationAttributes,
        Types.attributesCharset.of("utf-8"),
        Types.attributesNaturalLanguage.of("en"),
    )

    private fun errorResponse(requestId: Int, status: Status, extra: AttributeGroup? = null): IppPacket =
        if (extra != null) IppPacket(status, requestId, operationGroup(), extra)
        else IppPacket(status, requestId, operationGroup())

    fun stop() {
        try { serverSocket?.close() } catch (_: IOException) {}
        executor.shutdownNow()
    }
}
```

Notes on the JIPP calls above (same version caveat as elsewhere in both plans): `AttributeGroup.filter`/iteration and `Types.statusMessage` accessor names should be checked against the installed JIPP version; if they differ, adjust the implementation to match, keeping the *behavior* (real job state, requested-attributes filtering, a human-readable cancel-failure reason) — never remove the test that pins that behavior.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.jaspreet.printserver.ipp.LocalIppServerTest"`
Expected: 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: synthetic local IPP server for host-based printers"
```

---

### Task 12: Service wiring + device smoke test

**Files:**
- Modify: `SRC/service/ServerService.kt` (replace `startLegacyPipeline`)
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/androidTest/java/dev/jaspreet/printserver/LegacyPipelineWiringSmokeTest.kt`
- Create: `app/src/androidTest/assets/smoke.pdf` (if not already added by Task 5.5)

(No `app/build.gradle.kts` androidTest changes here — Task 5.5 already added the scaffolding this task reuses.)

- [ ] **Step 1: Rewire the legacy pipeline in ServerService**

Replace the existing `startLegacyPipeline` in `SRC/service/ServerService.kt` with:
```kotlin
    private fun startLegacyPipeline(
        name: String,
        usb: UsbPrinterManager,
        device: android.hardware.usb.UsbDevice,
        bindAddr: java.net.Inet4Address,
    ) {
        val transport = usb.openLegacyTransport(device)
            ?: return fail("Printer has no usable USB interface")

        // Tier-2: the app itself is the IPP printer; rendering happens on-device.
        val ppd = PpdAsset.extract(this)
        val pipeline = NativeRenderingPipeline(cacheDir, ppd.absolutePath)
        val spoolDir = File(cacheDir, "spool")
        JobQueue.cleanStaleSpool(spoolDir.apply { mkdirs() }) // drop leftovers from a run killed mid-job
        val queue = JobQueue(pipeline, { transport }).also { jobQueue = it }
        val caps = PrinterCapabilities.deskJet2300(
            java.net.URI.create("ipp://${bindAddr.hostAddress}:$IPP_PORT/ipp/print")
        )
        val ipp = LocalIppServer(IPP_PORT, caps, queue, spoolDir)
            .also { localIppServer = it }
        ipp.start(bindAddr)

        // Raw 9100 stays available for PC-driver clients.
        val relay = Raw9100Relay(RAW_PORT) { transport }.also { rawRelay = it }
        relay.start(bindAddr)

        advertiser = NsdAdvertiser(this).also {
            it.advertiseIpp(caps.makeAndModel, IPP_PORT, TxtRecords.forIpp(caps.toPrinterInfo()))
            it.advertiseRaw(name, RAW_PORT)
        }
        update {
            it.copy(running = true, printerName = caps.makeAndModel, ippSupported = true,
                ip = bindAddr.hostAddress, port = IPP_PORT,
                message = "Serving ${caps.makeAndModel} (on-device rendering)")
        }
        notify("Serving ${caps.makeAndModel} at ${bindAddr.hostAddress}:$IPP_PORT")
    }
```
Add fields `private var jobQueue: JobQueue? = null` and `private var localIppServer: LocalIppServer? = null` beside the existing ones; in `stopPipeline()` add `localIppServer?.stop(); localIppServer = null; jobQueue?.shutdown(); jobQueue = null`. Add the imports (`JobQueue`, `LocalIppServer`, `PrinterCapabilities`, `NativeRenderingPipeline`, `PpdAsset`, `java.io.File`). Remove the now-obsolete legacy banner trigger: this printer family is no longer "unsupported" (`ippSupported = true` above); the `legacy_banner` string stays for genuinely unknown legacy models — change the trigger condition later when a second model family is added.

- [ ] **Step 2: Add a wiring-level smoke test through the full pipeline**

androidTest scaffolding (`androidx.test` deps, instrumentation runner) already exists from Task 5.5 — no gradle changes needed here. Task 5.5 already proved the raw gs→ppm→hpcups chain works; this test instead exercises `NativeRenderingPipeline` exactly the way `ServerService` wires it (same `cacheDir` as workDir, same `PpdAsset.extract`), catching any regression the Task 12 Step 1 wiring change might introduce, with the same failure-artifact capture pattern as Task 5.5.

`app/src/androidTest/java/dev/jaspreet/printserver/LegacyPipelineWiringSmokeTest.kt`:
```kotlin
package dev.jaspreet.printserver

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.jaspreet.printserver.render.NativeRenderingPipeline
import dev.jaspreet.printserver.render.PpdAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device-only: re-proves the native chain through the exact construction ServerService uses. */
@RunWith(AndroidJUnit4::class)
class LegacyPipelineWiringSmokeTest {

    @Test
    fun rendersOnePagePdfToPcl() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val testCtx = InstrumentationRegistry.getInstrumentation().context
        val pdf = File(ctx.cacheDir, "smoke.pdf")
        val out = File(ctx.cacheDir, "smoke.pcl")
        val failureDir = File(ctx.cacheDir, "wiring-smoke-failure")
        testCtx.assets.open("smoke.pdf").use { input -> pdf.outputStream().use { input.copyTo(it) } }

        try {
            NativeRenderingPipeline(ctx.cacheDir, PpdAsset.extract(ctx).absolutePath).render(pdf, out)
            assertTrue("PCL output should be non-trivial", out.length() > 1024)
            assertEquals("PCL output should start with ESC", 0x1B, out.inputStream().use { it.read() })
        } catch (e: Throwable) {
            failureDir.mkdirs()
            pdf.copyTo(File(failureDir, "smoke.pdf"), overwrite = true)
            if (out.exists()) out.copyTo(File(failureDir, "smoke.pcl"), overwrite = true)
            throw AssertionError(
                "Wiring smoke test failed; artifacts saved to ${failureDir.absolutePath}, " +
                    "inspect with `adb logcat -s hpcupsjni gsjni`", e,
            )
        }
    }
}
```
Create `app/src/androidTest/assets/smoke.pdf`: any small single-page PDF (e.g. print-to-PDF a page of text on the Mac and copy it in) — reuse the same file already added for Task 5.5 if the assets directory is shared, otherwise copy it.

- [ ] **Step 3: Verify JVM build + tests still green**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests PASS.

- [ ] **Step 4: Run the smoke test on a real phone** (arm64 device attached via adb)

Run: `./gradlew :app:connectedDebugAndroidTest --tests "dev.jaspreet.printserver.LegacyPipelineWiringSmokeTest"`
Expected: PASSES — Task 5.5 already retired most native-toolchain risk, so this should be uneventful; if it fails, something in the Step 1 wiring change (paths, PPD extraction timing) regressed rather than the native chain itself. Debug via `adb logcat -s hpcupsjni gsjni`, or pull `wiring-smoke-failure/` via `adb pull`.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: wire Tier-2 rendering pipeline into service; native smoke test"
```

---

### Task 13: Hardware smoke checklist addition

**Files:**
- Modify: `docs/superpowers/testing/hardware-smoke-checklist.md`

- [ ] **Step 1: Append the Tier-2 section**

Append to `docs/superpowers/testing/hardware-smoke-checklist.md`:
```markdown

## Tier-2: host-based printer, on-device rendering (HP DeskJet 2338)

Prereq: 2338 connected via OTG, server running, NO legacy banner shown
(this family is fully supported now).

Run the `ipptool`/`lp` probes first — they catch missing/wrong IPP attributes
in minutes, before burning paper on a print that was never going to render
correctly:

- [ ] `ipptool -tv ipp://<phone-ip>:8631/ipp/print get-printer-attributes.test` passes
      with no missing/unexpected-attribute warnings (ipptool ships with CUPS).
- [ ] `ipptool -tv ipp://<phone-ip>:8631/ipp/print print-job.test` (or a
      hand-rolled `.test` file posting a real PDF) reports job-id and
      job-state as expected.
- [ ] `lp -h <phone-ip>:8631 -d ipp/print page.pdf` from a Linux/macOS shell
      completes without CUPS falling back to a generic/raw queue.

Then the physical print checks:

- [ ] macOS discovers the printer via Bonjour and prints one text PDF page —
      output physically correct (no garbage, no offset, right colors).
- [ ] Windows 11 adds it driverlessly and prints a page.
- [ ] iPhone AirPrint prints a photo (color fidelity check).
- [ ] Multi-page PDF (3+ pages) prints all pages in order.
- [ ] Submit two jobs back-to-back from different machines: both print, in order.
- [ ] Corrupt PDF (truncate a real one) → job aborts, printer does not hang,
      NEXT job still prints fine.
- [ ] Cancel a queued (not yet printing) job from the client — it never prints;
      canceling an already-processing job gets a clear rejection, not a hang.
- [ ] Raw 9100 path still works from a PC with the HP driver installed.
```

- [ ] **Step 2: Run the checklist with the real 2338**

Work through every box with physical paper output. The color/alignment check on the first box is the moment of truth for the whole rendering pipeline — if output is garbled, debug order: (1) PPM looks right? (pull from device, view), (2) hpcups exit code 0?, (3) PPD ColorModel/resolution match the raster header (300dpi sRGB chunky)?

- [ ] **Step 3: Commit**

```bash
git add -A && git commit -m "docs: Tier-2 hardware smoke checklist"
```

---

### Task 14: Licensing compliance

Ghostscript (AGPL-3.0) and hpcups/CUPS-raster (GPL-2.0/Apache-2.0) are now bundled and compiled into the APK. Fine for personal sideload as-is, but "revisit before Play Store" is easy to forget once the app works — turn it into an artifact now instead of a future TODO.

**Files:**
- Create: `app/src/main/assets/licenses/GHOSTSCRIPT-AGPL-3.0.txt`
- Create: `app/src/main/assets/licenses/HPLIP-GPL-2.0.txt`
- Create: `app/src/main/assets/licenses/CUPS-APACHE-2.0.txt`
- Create: `app/src/main/assets/licenses/NOTICE.md`
- Modify: `app/src/main/res/values/strings.xml`
- Modify: `SRC/MainActivity.kt`

- [ ] **Step 1: Bundle the actual license texts**

```bash
mkdir -p app/src/main/assets/licenses
curl -o app/src/main/assets/licenses/GHOSTSCRIPT-AGPL-3.0.txt https://www.gnu.org/licenses/agpl-3.0.txt
curl -o app/src/main/assets/licenses/HPLIP-GPL-2.0.txt https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt
curl -o app/src/main/assets/licenses/CUPS-APACHE-2.0.txt https://www.apache.org/licenses/LICENSE-2.0.txt
```

- [ ] **Step 2: Write the attribution/source-availability notice**

`app/src/main/assets/licenses/NOTICE.md`:
```markdown
# Third-party components

This app bundles native code compiled from the following third-party
projects. Their full license texts are alongside this file.

- **Ghostscript 10.03.1** — AGPL-3.0. Source: https://www.ghostscript.com/
  (upstream archives: https://github.com/ArtifexSoftware/ghostpdl-downloads)
- **HPLIP 3.24.4 (hpcups filter)** — GPL-2.0. Source: https://developers.hp.com/hp-linux-imaging-and-printing
- **CUPS 2.4.10 (raster I/O)** — Apache-2.0. Source: https://github.com/OpenPrinting/cups

Build scripts and any source patches applied to the above are in this
repository's `native/` directory (`native/build-ghostscript.sh`,
`native/fetch-hpcups-sources.sh`, `native/patches/`), which together with
the pinned upstream version numbers satisfy the AGPL/GPL requirement that
corresponding source be available to anyone who receives the binary.

**Distribution note:** this build is currently for personal/sideload use
only. Before any wider distribution (Play Store or otherwise), AGPL
specifically requires that users interacting with the app over a network
be able to obtain the exact corresponding source — confirm the above
satisfies that for whatever distribution channel is used, and update the
"Licenses" screen's source link if the repository moves.
```

- [ ] **Step 3: Expose a Licenses screen entry point**

Add to `app/src/main/res/values/strings.xml`:
```xml
    <string name="licenses_button">Third-party licenses</string>
```
In `SRC/MainActivity.kt`, add a button (reusing the pattern of `batteryButton` in `activity_main.xml`/`onCreate`) that opens the bundled `NOTICE.md` and license texts — simplest correct implementation is a plain-text viewer `Activity` is overkill for three files; instead add a click listener that reads `licenses/NOTICE.md` from assets and shows it in an `AlertDialog`:
```kotlin
        findViewById<Button>(R.id.licensesButton).setOnClickListener {
            val notice = assets.open("licenses/NOTICE.md").bufferedReader().readText()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.licenses_button)
                .setMessage(notice)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
```
Add the corresponding `<Button android:id="@+id/licensesButton" ... android:text="@string/licenses_button" />` to `activity_main.xml`, below `batteryButton`.

- [ ] **Step 4: Verify build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL; `unzip -l app/build/outputs/apk/debug/app-debug.apk | grep licenses/` shows all four files.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "docs: bundle third-party license texts and NOTICE"
```

---

## Self-review notes

- **Spec coverage:** LocalIppServer 5 operations incl. requested-attributes filtering and printer-uri validation (Task 11), hardcoded capabilities (9), single-worker JobQueue with states + cancel race + streaming USB write + free-space guard + stale-spool cleanup (8), RenderingPipeline interface + fake (7), Ghostscript JNI + `ppmraw` device verification (2–3), hpcups JNI + raster feed + reproducible checked-in patches (4), PPD bundling (5), early native fixture proof (5.5), spool-file cleanup (8/12), service wiring (12), testing strategy incl. device smoke (5.5, 12), hardware checklist with ipptool/lp interop probes (13), licensing artifact (14). Chunked-body consumption and its size cap, which the spec implies but Tier 1 lacked, added as Task 10.
- **Placeholder scan:** Native tasks 2/4 contain iterate-until-green loops by necessity (cross-compiling third-party C against a pinned version); each gives concrete resolution rules rather than "handle errors". Checksum pinning (Tasks 2/4) is intentionally left as a warn-and-print-the-hash flow on first run rather than a fabricated hash value — accepted as the honest shape of a first-time pin.
- **Type consistency:** `RenderingPipeline.render(pdf, output)` defined Task 7, used Tasks 8/12; `JobQueue.submit/get/cancel/shutdown/cleanStaleSpool` defined Task 8, used Tasks 11/12; `PrinterCapabilities.deskJet2300/asPrinterAttributes/toPrinterInfo/printerUri` defined Task 9, used Tasks 11/12; `HpcupsNative.encode` defined Task 4 (moved from a duplicate/dangling listing in the original Task 4/7 split), signature matches `hpcupsjni.cpp` JNI name and arity, used by Task 7's `NativeRenderingPipeline`; `BodyReader.readAll`'s new `maxBytes` param and `BodyTooLargeException` defined Task 10, used Task 11; `PrinterInfo`/`TxtRecords` reused from Tier-1 Task 8 unchanged.
- **Known risks (deliberate, documented):** JIPP accessor naming, including the newly-added `Types.statusMessage` and `AttributeGroup` filtering calls in Task 11 (same caveat as Tier-1 plan); hpcups patch points may shift if HPLIP version differs from 3.24.4 (pinned; now captured as checked-in patch files rather than ad hoc grep-edits); raster header colorspace must match what the PPD tells hpcups to expect — Task 5.5 validates this early instead of only at Task 12/13.
- **Plan-review addendum applied:** this revision folds in every item from the external review filed at `docs/superpowers/plans/tier2-legacy-driverless-plan-review.md` (2026-07-16) — the two confirmed bugs (dangling `HpcupsEncoder.kt`, hardcoded `processing` job state) and all twelve hardening suggestions. See the addendum note near the top of this document.

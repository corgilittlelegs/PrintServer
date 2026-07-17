cmake_minimum_required(VERSION 3.22.1)
project(printserver_native C CXX)

# 16KB-page-size devices (Android 15+) require ELF LOAD segments aligned to
# 16KB; the NDK's default linker output is 4KB-aligned. Applies to gsjni and
# hpcupsjni (built here); libgs.so is prebuilt separately by
# native/build-ghostscript.sh, which sets the matching LDFLAGS itself.
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

add_library(gs SHARED IMPORTED)
set_target_properties(gs PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libgs.so)

add_library(gsjni SHARED gsjni.c)
target_include_directories(gsjni PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(gsjni gs log)

file(GLOB HPCUPS_SOURCES hpcups/*.cpp hpcups/*.c)
file(GLOB HPCUPS_COMMON_SOURCES hpcups-common/*.c)
file(GLOB CUPSRASTER_SOURCES cupsraster/cups/*.c)

add_library(hpcupsjni SHARED
    hpcupsjni.cpp
    ${HPCUPS_SOURCES}
    ${HPCUPS_COMMON_SOURCES}
    ${CUPSRASTER_SOURCES})
# HPLIP's hpcups sources predate C++17 (which removed the `register` keyword
# entirely); build them as C++14, where it's merely deprecated.
set_target_properties(hpcupsjni PROPERTIES CXX_STANDARD 14 CXX_STANDARD_REQUIRED ON)
target_include_directories(hpcupsjni PRIVATE
    ${CMAKE_SOURCE_DIR}
    ${CMAKE_SOURCE_DIR}/hpcups
    ${CMAKE_SOURCE_DIR}/hpcups-common
    ${CMAKE_SOURCE_DIR}/cupsraster)
# APDK_LITTLE_ENDIAN: gates byte-order-dependent pixel packing/unpacking in
# ErnieFilter.h and CommonDefinitions.h (GetRed/GetGreen/GetBlue, get4Pixel/
# put4Pixel). Undefined means those macros take the big-endian bit-shift
# path, which corrupts every pixel read/write on arm64 (always little-endian)
# — HPLIP's original build system defined this for x86 Linux but our CMake
# port never did, so ErnieFilter's block-merge logic compared garbage color
# values and smeared black across whole blocks near any non-white content.
target_compile_definitions(hpcupsjni PRIVATE UNIX APDK_LINUX DISABLE_IMAGEPROCESSOR APDK_LITTLE_ENDIAN)
# CONFDIR: HPLIP's system config dir (normally /etc/hp), read by get_conf()
# for hplip.conf. No such install exists on Android — this just means
# get_conf() harmlessly reports "not found" at runtime, matching reality.
set_source_files_properties(hpcups-common/utils.c PROPERTIES
    COMPILE_DEFINITIONS "CONFDIR=\"/data/local/tmp/nonexistent-hplip-conf\"")
target_link_libraries(hpcupsjni log z)

cmake_minimum_required(VERSION 3.22.1)
project(printserver_native C CXX)

add_library(gs SHARED IMPORTED)
set_target_properties(gs PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/arm64-v8a/libgs.so)

add_library(gsjni SHARED gsjni.c)
target_include_directories(gsjni PRIVATE ${CMAKE_SOURCE_DIR})
target_link_libraries(gsjni gs log)

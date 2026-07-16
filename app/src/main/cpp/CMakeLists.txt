cmake_minimum_required(VERSION 3.22.1)
project(printserver_native C CXX)

# Real targets (gsjni, hpcupsjni) are added in Tasks 3 and 4.
add_library(placeholder SHARED placeholder.c)

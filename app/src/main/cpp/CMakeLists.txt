# Native guardian bridge for Mink.
#
# This builds libmink_llm.so, a thin JNI wrapper over llama.cpp that runs
# MiniCPM5-1B fully on device. The app's build.gradle only invokes this file
# when BOTH this CMakeLists.txt and a vendored ./llama directory exist, so the
# Kotlin app always builds on its own and degrades to the rules engine when the
# native sources are absent. See README.md in this directory for how to vendor
# llama.cpp.

cmake_minimum_required(VERSION 3.22.1)
project(mink_llm CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/llama)

if(NOT EXISTS ${LLAMA_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "llama.cpp sources not found at ${LLAMA_DIR}. "
        "Vendor them (see src/main/cpp/README.md) or remove the native build.")
endif()

# Keep the vendored build lean: no tools, tests, examples, or server.
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} ${CMAKE_BINARY_DIR}/llama EXCLUDE_FROM_ALL)

add_library(mink_llm SHARED mink_llm.cpp)

target_include_directories(mink_llm PRIVATE
    ${LLAMA_DIR}/include
    ${LLAMA_DIR}/ggml/include)

find_library(log-lib log)

target_link_libraries(mink_llm
    llama
    ${log-lib})

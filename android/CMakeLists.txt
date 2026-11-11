cmake_minimum_required(VERSION 3.22.1)
project(whisperstt)

set(CMAKE_CXX_STANDARD 17)

# whisper.cpp (MIT, https://github.com/ggml-org/whisper.cpp), git submodule pinned to v1.9.5 in ../cpp
set(WHISPER_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../cpp/whisper.cpp)
if (NOT EXISTS ${WHISPER_CPP_DIR}/CMakeLists.txt)
    message(FATAL_ERROR "whisper.cpp not found at ${WHISPER_CPP_DIR}. Run: git submodule update --init")
endif ()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    # dotprod + fp16 are available on every Cortex-A55/A75 and newer core
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
endif ()
add_subdirectory(${WHISPER_CPP_DIR} whisper.cpp)

add_library(whisperstt SHARED src/main/cpp/whispercppJNI.cpp)
target_link_libraries(whisperstt android log whisper)

# Debug builds default to -O0, which makes whisper.cpp ~10x slower
target_compile_options(whisperstt PRIVATE -O3)
foreach (t whisper ggml ggml-base ggml-cpu)
    if (TARGET ${t})
        target_compile_options(${t} PRIVATE -O3)
    endif ()
endforeach ()

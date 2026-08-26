cmake_minimum_required(VERSION 3.22)
project(summarizer C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Shared libraries are required by ggml's runtime CPU dispatch below.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)

# Inference is unusably slow without optimizations, so every variant must get
# optimized native code no matter which CMAKE_BUILD_TYPE ends up selected.
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG" CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG" CACHE STRING "" FORCE)

# ggml is built once (from llama.cpp) and shared with whisper.cpp, which skips
# its own copy when the target already exists.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# Build one CPU backend per ARM feature level (baseline armv8.0 through
# armv8.6 with dotprod/fp16/i8mm) and let ggml dlopen the best one the device
# actually supports. A single hard-coded -march would either give up the fast
# kernels or SIGILL on older arm64 hardware.
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)

set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)
add_subdirectory(whisper.cpp)

add_library(summarizer SHARED
    jni_utils.cpp
    backend_loader.cpp
    whisper_bridge.cpp
    llama_bridge.cpp
)

target_link_libraries(summarizer PRIVATE llama whisper)

if (ANDROID)
    target_link_libraries(summarizer PRIVATE android log)
else()
    # Host (Linux) compile check: take JNI headers from the JDK.
    if (DEFINED ENV{JAVA_HOME})
        target_include_directories(summarizer PRIVATE
            $ENV{JAVA_HOME}/include
            $ENV{JAVA_HOME}/include/linux
        )
    endif()
endif()

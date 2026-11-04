cmake_minimum_required(VERSION 3.22.1)
project(mscope_llama CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 只编推理库，关掉示例/工具/测试，缩短编译时间并减小体积
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)
# llamafile 的 sgemm 在 32 位 ARM 上会用到 vld1q_f16，NDK clang 无法编译；
# 该实现主要面向 x86，关闭后 ARM 走通用/NEON 路径，功能与精度不受影响。
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)

# ---- arm64-v8a：多档指令集变体（多档指令集 = 多份 CPU 后端，运行时按芯片选最优）----
#
# 以前是单一构建，固定 -march=armv8.2-a+dotprod+fp16：所有机型都只能跑这一档，
# 而且缺 dotprod 的老 arm64 会被直接拒绝。改成 GGML_CPU_ALL_VARIANTS 后，会为
# Android 各编一份后端（armv8.0 基线 / 8.2 dotprod+fp16 / 8.6 i8mm / 9.0 SVE2 /
# 9.2 SVE+SME），每份导出 ggml_backend_score() 在运行时按 HWCAP 打分，ggml 挑
# 分数最高的那份加载。于是：新芯片（i8mm/SVE）走更快的量化矩阵乘，老芯片退回
# armv8-a 基线，不会 SIGILL。
#
# 该模式要求后端以动态库产出：GGML_BACKEND_DL 依赖 BUILD_SHARED_LIBS。
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(BUILD_SHARED_LIBS     ON CACHE BOOL "" FORCE)
    set(GGML_BACKEND_DL       ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    # Android 上版本化 SONAME（libllama.so.0）在 APK 里并不存在，会导致 dlopen 失败
    set(CMAKE_PLATFORM_NO_VERSIONED_SONAME TRUE)
else()
    # armeabi-v7a：变体列表只覆盖 arm64，保持单档静态构建
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
endif()

# 本地已有源码时直接用（加快沙箱构建）；否则按固定 tag 拉取，保证可复现
if(LLAMA_CPP_DIR AND EXISTS "${LLAMA_CPP_DIR}/CMakeLists.txt")
    message(STATUS "使用本地 llama.cpp 源码: ${LLAMA_CPP_DIR}")
    add_subdirectory("${LLAMA_CPP_DIR}" "${CMAKE_BINARY_DIR}/llama_cpp" EXCLUDE_FROM_ALL)
else()
    message(STATUS "通过 FetchContent 拉取 llama.cpp b11205")
    include(FetchContent)
    FetchContent_Declare(llama_cpp
        GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
        GIT_TAG        b11205
        GIT_SHALLOW    TRUE)
    FetchContent_MakeAvailable(llama_cpp)
endif()

add_library(mscope_llama SHARED llama_bridge.cpp)
target_link_libraries(mscope_llama PRIVATE llama log)

# 桥接层只调用 API、不含 SIMD 内核，因此刻意不加 -march：
# 保持基线 armv8-a，任何 arm64 机型都能加载（具体算力交给上面的变体后端）。
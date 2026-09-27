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
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)

# ---- arm64-v8a：打开 ARMv8.2 的 dotprod + fp16 ----
# 交叉编译时 GGML_NATIVE 自动关闭，若不显式指定 -march，则整个构建退化为
# 基线 armv8-a：而 ggml 的量化矩阵乘 / repack 内核（arch/arm/repack.cpp、
# quants.c、vec.h 等）全部以 __ARM_FEATURE_DOTPROD 为条件编译 —— 不指定时
# 这些内核会被整段编译掉，只能走通用路径，这正是此前推理偏慢的主因。
# dotprod 需要 2018 年后的 arm64 处理器，运行时会在 JNI 层用 HWCAP 校验，
# 不支持的机型给出明确提示而非直接崩溃。
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    message(STATUS "arm64-v8a: 启用 GGML_CPU_ARM_ARCH=${GGML_CPU_ARM_ARCH}")
else()
    message(STATUS "${ANDROID_ABI}: 使用 NDK 默认指令集")
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

# 桥接层也用同一套指令集，保证 __ARM_FEATURE_* 宏与实际编译产物一致
# （ggml 的 ARCH_FLAGS 只作用于 ggml-cpu 目标，不会自动传导到这里）
if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(mscope_llama PRIVATE -march=armv8.2-a+dotprod+fp16)
endif()
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
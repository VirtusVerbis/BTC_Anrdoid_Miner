cmake_minimum_required(VERSION 3.13.1)
project("miner")

# Compile compute shaders to SPIR-V and embed as C arrays (for Vulkan compute path)
set(SHADER_SCALAR_SRC "${CMAKE_CURRENT_SOURCE_DIR}/miner.comp")
set(SHADER_SCALAR_SPV "${CMAKE_CURRENT_BINARY_DIR}/miner.spv")
set(SHADER_SCALAR_H   "${CMAKE_CURRENT_BINARY_DIR}/miner_spv.h")
set(SHADER_UVEC2_SRC "${CMAKE_CURRENT_SOURCE_DIR}/miner_uvec2.comp")
set(SHADER_UVEC2_SPV "${CMAKE_CURRENT_BINARY_DIR}/miner_uvec2.spv")
set(SHADER_UVEC2_H   "${CMAKE_CURRENT_BINARY_DIR}/miner_uvec2_spv.h")
set(SHADER_UVEC4_SRC "${CMAKE_CURRENT_SOURCE_DIR}/miner_uvec4.comp")
set(SHADER_UVEC4_SPV "${CMAKE_CURRENT_BINARY_DIR}/miner_uvec4.spv")
set(SHADER_UVEC4_H   "${CMAKE_CURRENT_BINARY_DIR}/miner_uvec4_spv.h")

set(SHADER_UVEC2_LOOP_SPV "${CMAKE_CURRENT_BINARY_DIR}/miner_uvec2_loop.spv")
set(SHADER_UVEC2_LOOP_H   "${CMAKE_CURRENT_BINARY_DIR}/miner_uvec2_loop_spv.h")
set(SHADER_UVEC4_LOOP_SPV "${CMAKE_CURRENT_BINARY_DIR}/miner_uvec4_loop.spv")
set(SHADER_UVEC4_LOOP_H   "${CMAKE_CURRENT_BINARY_DIR}/miner_uvec4_loop_spv.h")
set(SHADER_SCALAR_SPV_OPT "${CMAKE_CURRENT_BINARY_DIR}/miner_opt.spv")
set(SHADER_UVEC2_SPV_OPT "${CMAKE_CURRENT_BINARY_DIR}/miner_uvec2_opt.spv")
set(SHADER_UVEC4_SPV_OPT "${CMAKE_CURRENT_BINARY_DIR}/miner_uvec4_opt.spv")
set(SHADER_UVEC2_LOOP_SPV_OPT "${CMAKE_CURRENT_BINARY_DIR}/miner_uvec2_loop_opt.spv")
set(SHADER_UVEC4_LOOP_SPV_OPT "${CMAKE_CURRENT_BINARY_DIR}/miner_uvec4_loop_opt.spv")

# Try glslangValidator first (Vulkan SDK), then glslc (shaderc)
find_program(GLSLANG_VALIDATOR NAMES glslangValidator glslc)
find_program(SPIRV_OPT NAMES spirv-opt)
if(NOT GLSLANG_VALIDATOR)
    # Fallback: try common Vulkan SDK install paths (adjust version if needed)
    set(VULKAN_SDK_PATHS
        "D:/VulkanSDK/1.4.341.1/Bin/glslangValidator.exe"
        "C:/VulkanSDK/1.4.341.1/Bin/glslangValidator.exe"
        "C:/VulkanSDK/1.4.0/Bin/glslangValidator.exe"
        "C:/VulkanSDK/1.3.0/Bin/glslangValidator.exe"
        "$ENV{VULKAN_SDK}/Bin/glslangValidator.exe"
    )
    foreach(PATH ${VULKAN_SDK_PATHS})
        if(EXISTS "${PATH}")
            set(GLSLANG_VALIDATOR "${PATH}")
            message(STATUS "Using explicit glslangValidator path: ${GLSLANG_VALIDATOR}")
            break()
        endif()
    endforeach()
endif()
if(NOT SPIRV_OPT)
    set(SPIRV_OPT_PATHS
        "D:/VulkanSDK/1.4.341.1/Bin/spirv-opt.exe"
        "C:/VulkanSDK/1.4.341.1/Bin/spirv-opt.exe"
        "C:/VulkanSDK/1.4.0/Bin/spirv-opt.exe"
        "C:/VulkanSDK/1.3.0/Bin/spirv-opt.exe"
        "$ENV{VULKAN_SDK}/Bin/spirv-opt.exe"
    )
    foreach(PATH ${SPIRV_OPT_PATHS})
        if(EXISTS "${PATH}")
            set(SPIRV_OPT "${PATH}")
            message(STATUS "Using explicit spirv-opt path: ${SPIRV_OPT}")
            break()
        endif()
    endforeach()
endif()
if(GLSLANG_VALIDATOR)
    # -V = compile to SPIR-V; SDK 1.4.x glslangValidator does not use --target-env
    set(GLSLANG_FLAGS "-V")
    set(SHADER_SCALAR_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_scalar.inc")
    set(SHADER_SECOND_SCALAR_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_second_scalar.inc")
    set(SHADER_SECOND_CPU_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_second_cpu.inc")
    set(SHADER_SECOND_UVEC2_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_second_uvec2.inc")
    set(SHADER_SECOND_UVEC2_LOOP_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_second_uvec2_loop.inc")
    set(SHADER_SECOND_UVEC4_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_second_uvec4.inc")
    set(SHADER_SECOND_UVEC4_LOOP_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_second_uvec4_loop.inc")
    set(SHADER_SECOND_GEN "${CMAKE_CURRENT_SOURCE_DIR}/tools/gen_sha256_second_compress.py")
    set(SHADER_FIRST_MID_SCALAR_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_first_mid_scalar.inc")
    set(SHADER_FIRST_MID_CPU_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_first_mid_cpu.inc")
    set(SHADER_FIRST_MID_UVEC2_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_first_mid_uvec2.inc")
    set(SHADER_FIRST_MID_UVEC2_LOOP_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_first_mid_uvec2_loop.inc")
    set(SHADER_FIRST_MID_UVEC4_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_first_mid_uvec4.inc")
    set(SHADER_FIRST_MID_UVEC4_LOOP_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_first_mid_uvec4_loop.inc")
    set(SHADER_FIRST_MID_GEN "${CMAKE_CURRENT_SOURCE_DIR}/tools/gen_sha256_first_mid_compress.py")
    set(SHADER_CODEGEN "${CMAKE_CURRENT_SOURCE_DIR}/tools/sha256_compress_codegen.py")
    find_package(Python3 COMPONENTS Interpreter)
    if(Python3_Interpreter_FOUND)
        add_custom_command(
            OUTPUT "${SHADER_SECOND_SCALAR_INC}" "${SHADER_SECOND_CPU_INC}" "${SHADER_SECOND_UVEC2_INC}" "${SHADER_SECOND_UVEC4_INC}"
                "${SHADER_SECOND_UVEC2_LOOP_INC}" "${SHADER_SECOND_UVEC4_LOOP_INC}"
            COMMAND ${Python3_EXECUTABLE} "${SHADER_SECOND_GEN}"
            DEPENDS "${SHADER_SECOND_GEN}" "${SHADER_CODEGEN}"
            COMMENT "Generating sha256_compress_second_*.inc"
            WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
        )
        add_custom_command(
            OUTPUT "${SHADER_FIRST_MID_SCALAR_INC}" "${SHADER_FIRST_MID_CPU_INC}" "${SHADER_FIRST_MID_UVEC2_INC}" "${SHADER_FIRST_MID_UVEC4_INC}"
                "${SHADER_FIRST_MID_UVEC2_LOOP_INC}" "${SHADER_FIRST_MID_UVEC4_LOOP_INC}"
            COMMAND ${Python3_EXECUTABLE} "${SHADER_FIRST_MID_GEN}"
            DEPENDS "${SHADER_FIRST_MID_GEN}" "${SHADER_CODEGEN}"
            COMMENT "Generating sha256_compress_first_mid_*.inc"
            WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
        )
    else()
        message(STATUS "Python3 not found; using committed sha256_compress_second_*.inc")
        message(STATUS "Python3 not found; using committed sha256_compress_first_mid_*.inc")
    endif()
    if(SPIRV_OPT)
        message(STATUS "spirv-opt found: ${SPIRV_OPT}")
    else()
        message(STATUS "spirv-opt not found; embedding raw SPIR-V without optimization")
    endif()

    # miner.comp (scalar, full unroll only)
    add_custom_command(
        OUTPUT "${SHADER_SCALAR_SPV}"
        COMMAND ${GLSLANG_VALIDATOR} ${GLSLANG_FLAGS} -o "${SHADER_SCALAR_SPV}" "${SHADER_SCALAR_SRC}"
        DEPENDS "${SHADER_SCALAR_SRC}" "${SHADER_SCALAR_INC}" "${SHADER_SECOND_SCALAR_INC}" "${SHADER_FIRST_MID_SCALAR_INC}" "${SHADER_SECOND_CPU_INC}" "${SHADER_FIRST_MID_CPU_INC}"
        COMMENT "Compiling miner.comp to SPIR-V"
    )
    if(SPIRV_OPT)
        add_custom_command(
            OUTPUT "${SHADER_SCALAR_SPV_OPT}"
            COMMAND ${SPIRV_OPT} -O "${SHADER_SCALAR_SPV}" -o "${SHADER_SCALAR_SPV_OPT}"
            DEPENDS "${SHADER_SCALAR_SPV}"
            COMMENT "Optimizing miner.comp SPIR-V"
        )
        set(_SHADER_SCALAR_EMBED_SPV "${SHADER_SCALAR_SPV_OPT}")
        set(_SHADER_SCALAR_EMBED_DEP "${SHADER_SCALAR_SPV_OPT}")
    else()
        set(_SHADER_SCALAR_EMBED_SPV "${SHADER_SCALAR_SPV}")
        set(_SHADER_SCALAR_EMBED_DEP "${SHADER_SCALAR_SPV}")
    endif()
    add_custom_command(
        OUTPUT "${SHADER_SCALAR_H}"
        COMMAND ${CMAKE_COMMAND} -DSPV_FILE="${_SHADER_SCALAR_EMBED_SPV}" -DOUT_FILE="${SHADER_SCALAR_H}"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.cmake"
        DEPENDS "${_SHADER_SCALAR_EMBED_DEP}"
        COMMENT "Embedding SPIR-V into miner_spv.h"
    )
    add_custom_target(miner_shader ALL DEPENDS "${SHADER_SCALAR_H}")

    set(SHADER_UVEC4_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_uvec4.inc")
    # miner_uvec4.comp full unroll (GPU_COMPRESS_STYLE=0)
    add_custom_command(
        OUTPUT "${SHADER_UVEC4_SPV}"
        COMMAND ${GLSLANG_VALIDATOR} ${GLSLANG_FLAGS} -DGPU_COMPRESS_STYLE=0 -o "${SHADER_UVEC4_SPV}" "${SHADER_UVEC4_SRC}"
        DEPENDS "${SHADER_UVEC4_SRC}" "${SHADER_UVEC4_INC}"
            "${SHADER_SECOND_UVEC4_INC}" "${SHADER_FIRST_MID_UVEC4_INC}"
            "${SHADER_SECOND_GEN}" "${SHADER_FIRST_MID_GEN}" "${SHADER_CODEGEN}"
        COMMENT "Compiling miner_uvec4.comp (full unroll) to SPIR-V"
    )
    if(SPIRV_OPT)
        add_custom_command(
            OUTPUT "${SHADER_UVEC4_SPV_OPT}"
            COMMAND ${SPIRV_OPT} -O "${SHADER_UVEC4_SPV}" -o "${SHADER_UVEC4_SPV_OPT}"
            DEPENDS "${SHADER_UVEC4_SPV}"
            COMMENT "Optimizing miner_uvec4 SPIR-V (full unroll)"
        )
        set(_SHADER_UVEC4_EMBED_SPV "${SHADER_UVEC4_SPV_OPT}")
        set(_SHADER_UVEC4_EMBED_DEP "${SHADER_UVEC4_SPV_OPT}")
    else()
        set(_SHADER_UVEC4_EMBED_SPV "${SHADER_UVEC4_SPV}")
        set(_SHADER_UVEC4_EMBED_DEP "${SHADER_UVEC4_SPV}")
    endif()
    add_custom_command(
        OUTPUT "${SHADER_UVEC4_H}"
        COMMAND ${CMAKE_COMMAND} -DSPV_FILE="${_SHADER_UVEC4_EMBED_SPV}" -DOUT_FILE="${SHADER_UVEC4_H}"
            -DARRAY_NAME=g_miner_uvec4_spv -DHEADER_GUARD=MINER_UVEC4_SPV_H
            -P "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.cmake"
        DEPENDS "${_SHADER_UVEC4_EMBED_DEP}"
        COMMENT "Embedding SPIR-V into miner_uvec4_spv.h"
    )
    # miner_uvec4.comp compact loop (GPU_COMPRESS_STYLE=1)
    add_custom_command(
        OUTPUT "${SHADER_UVEC4_LOOP_SPV}"
        COMMAND ${GLSLANG_VALIDATOR} ${GLSLANG_FLAGS} -DGPU_COMPRESS_STYLE=1 -o "${SHADER_UVEC4_LOOP_SPV}" "${SHADER_UVEC4_SRC}"
        DEPENDS "${SHADER_UVEC4_SRC}" "${SHADER_UVEC4_INC}"
            "${SHADER_SECOND_UVEC4_LOOP_INC}" "${SHADER_FIRST_MID_UVEC4_LOOP_INC}"
            "${SHADER_SECOND_GEN}" "${SHADER_FIRST_MID_GEN}" "${SHADER_CODEGEN}"
        COMMENT "Compiling miner_uvec4.comp (compact loop) to SPIR-V"
    )
    if(SPIRV_OPT)
        add_custom_command(
            OUTPUT "${SHADER_UVEC4_LOOP_SPV_OPT}"
            COMMAND ${SPIRV_OPT} -O "${SHADER_UVEC4_LOOP_SPV}" -o "${SHADER_UVEC4_LOOP_SPV_OPT}"
            DEPENDS "${SHADER_UVEC4_LOOP_SPV}"
            COMMENT "Optimizing miner_uvec4 SPIR-V (compact loop)"
        )
        set(_SHADER_UVEC4_LOOP_EMBED_SPV "${SHADER_UVEC4_LOOP_SPV_OPT}")
        set(_SHADER_UVEC4_LOOP_EMBED_DEP "${SHADER_UVEC4_LOOP_SPV_OPT}")
    else()
        set(_SHADER_UVEC4_LOOP_EMBED_SPV "${SHADER_UVEC4_LOOP_SPV}")
        set(_SHADER_UVEC4_LOOP_EMBED_DEP "${SHADER_UVEC4_LOOP_SPV}")
    endif()
    add_custom_command(
        OUTPUT "${SHADER_UVEC4_LOOP_H}"
        COMMAND ${CMAKE_COMMAND} -DSPV_FILE="${_SHADER_UVEC4_LOOP_EMBED_SPV}" -DOUT_FILE="${SHADER_UVEC4_LOOP_H}"
            -DARRAY_NAME=g_miner_uvec4_loop_spv -DHEADER_GUARD=MINER_UVEC4_LOOP_SPV_H
            -P "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.cmake"
        DEPENDS "${_SHADER_UVEC4_LOOP_EMBED_DEP}"
        COMMENT "Embedding SPIR-V into miner_uvec4_loop_spv.h"
    )
    add_custom_target(miner_uvec4_shader ALL DEPENDS "${SHADER_UVEC4_H}" "${SHADER_UVEC4_LOOP_H}")

    set(SHADER_UVEC2_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_uvec2.inc")
    # miner_uvec2.comp full unroll
    add_custom_command(
        OUTPUT "${SHADER_UVEC2_SPV}"
        COMMAND ${GLSLANG_VALIDATOR} ${GLSLANG_FLAGS} -DGPU_COMPRESS_STYLE=0 -o "${SHADER_UVEC2_SPV}" "${SHADER_UVEC2_SRC}"
        DEPENDS "${SHADER_UVEC2_SRC}" "${SHADER_UVEC2_INC}"
            "${SHADER_SECOND_UVEC2_INC}" "${SHADER_FIRST_MID_UVEC2_INC}"
            "${SHADER_SECOND_GEN}" "${SHADER_FIRST_MID_GEN}" "${SHADER_CODEGEN}"
        COMMENT "Compiling miner_uvec2.comp (full unroll) to SPIR-V"
    )
    if(SPIRV_OPT)
        add_custom_command(
            OUTPUT "${SHADER_UVEC2_SPV_OPT}"
            COMMAND ${SPIRV_OPT} -O "${SHADER_UVEC2_SPV}" -o "${SHADER_UVEC2_SPV_OPT}"
            DEPENDS "${SHADER_UVEC2_SPV}"
            COMMENT "Optimizing miner_uvec2 SPIR-V (full unroll)"
        )
        set(_SHADER_UVEC2_EMBED_SPV "${SHADER_UVEC2_SPV_OPT}")
        set(_SHADER_UVEC2_EMBED_DEP "${SHADER_UVEC2_SPV_OPT}")
    else()
        set(_SHADER_UVEC2_EMBED_SPV "${SHADER_UVEC2_SPV}")
        set(_SHADER_UVEC2_EMBED_DEP "${SHADER_UVEC2_SPV}")
    endif()
    add_custom_command(
        OUTPUT "${SHADER_UVEC2_H}"
        COMMAND ${CMAKE_COMMAND} -DSPV_FILE="${_SHADER_UVEC2_EMBED_SPV}" -DOUT_FILE="${SHADER_UVEC2_H}"
            -DARRAY_NAME=g_miner_uvec2_spv -DHEADER_GUARD=MINER_UVEC2_SPV_H
            -P "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.cmake"
        DEPENDS "${_SHADER_UVEC2_EMBED_DEP}"
        COMMENT "Embedding SPIR-V into miner_uvec2_spv.h"
    )
    # miner_uvec2.comp compact loop
    add_custom_command(
        OUTPUT "${SHADER_UVEC2_LOOP_SPV}"
        COMMAND ${GLSLANG_VALIDATOR} ${GLSLANG_FLAGS} -DGPU_COMPRESS_STYLE=1 -o "${SHADER_UVEC2_LOOP_SPV}" "${SHADER_UVEC2_SRC}"
        DEPENDS "${SHADER_UVEC2_SRC}" "${SHADER_UVEC2_INC}"
            "${SHADER_SECOND_UVEC2_LOOP_INC}" "${SHADER_FIRST_MID_UVEC2_LOOP_INC}"
            "${SHADER_SECOND_GEN}" "${SHADER_FIRST_MID_GEN}" "${SHADER_CODEGEN}"
        COMMENT "Compiling miner_uvec2.comp (compact loop) to SPIR-V"
    )
    if(SPIRV_OPT)
        add_custom_command(
            OUTPUT "${SHADER_UVEC2_LOOP_SPV_OPT}"
            COMMAND ${SPIRV_OPT} -O "${SHADER_UVEC2_LOOP_SPV}" -o "${SHADER_UVEC2_LOOP_SPV_OPT}"
            DEPENDS "${SHADER_UVEC2_LOOP_SPV}"
            COMMENT "Optimizing miner_uvec2 SPIR-V (compact loop)"
        )
        set(_SHADER_UVEC2_LOOP_EMBED_SPV "${SHADER_UVEC2_LOOP_SPV_OPT}")
        set(_SHADER_UVEC2_LOOP_EMBED_DEP "${SHADER_UVEC2_LOOP_SPV_OPT}")
    else()
        set(_SHADER_UVEC2_LOOP_EMBED_SPV "${SHADER_UVEC2_LOOP_SPV}")
        set(_SHADER_UVEC2_LOOP_EMBED_DEP "${SHADER_UVEC2_LOOP_SPV}")
    endif()
    add_custom_command(
        OUTPUT "${SHADER_UVEC2_LOOP_H}"
        COMMAND ${CMAKE_COMMAND} -DSPV_FILE="${_SHADER_UVEC2_LOOP_EMBED_SPV}" -DOUT_FILE="${SHADER_UVEC2_LOOP_H}"
            -DARRAY_NAME=g_miner_uvec2_loop_spv -DHEADER_GUARD=MINER_UVEC2_LOOP_SPV_H
            -P "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.cmake"
        DEPENDS "${_SHADER_UVEC2_LOOP_EMBED_DEP}"
        COMMENT "Embedding SPIR-V into miner_uvec2_loop_spv.h"
    )
    add_custom_target(miner_uvec2_shader ALL DEPENDS "${SHADER_UVEC2_H}" "${SHADER_UVEC2_LOOP_H}")
else()
    message(FATAL_ERROR "glslangValidator or glslc required for GPU path. Install Vulkan SDK or shaderc and add to PATH.")
endif()

set(MINER_SRCS miner.c sha256.c sha256_btc_fast.c sha256_scan.c btc_header_sha256.c vulkan_miner.c)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    list(APPEND MINER_SRCS sha256_arm_sha2.c sha256_neon_4way.c)
endif()

add_library(miner SHARED ${MINER_SRCS})
target_include_directories(miner PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
add_dependencies(miner miner_shader miner_uvec2_shader miner_uvec4_shader)
target_compile_features(miner PRIVATE c_std_11)

# Optional native self-test logs: pass -DSHA256_SELFTEST_DIAG=1 in Gradle cmake arguments, e.g.
# android { defaultConfig { externalNativeBuild { cmake { arguments += "-DSHA256_SELFTEST_DIAG=1" } } } }
if(SHA256_SELFTEST_DIAG)
    target_compile_definitions(miner PRIVATE SHA256_SELFTEST_DIAG=1)
endif()

if(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_options(miner PRIVATE -march=armv8-a+crypto)
endif()

find_library(LOG_LIB log)
target_link_libraries(miner ${LOG_LIB})

if(ANDROID)
    target_link_libraries(miner vulkan)
endif()

# 16 KB page alignment for Android 15+ and 16 KB devices (Play requirement from Nov 2025)
target_link_options(miner PRIVATE "LINKER:-z,max-page-size=16384")

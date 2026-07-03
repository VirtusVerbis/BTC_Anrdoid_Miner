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

# Try glslangValidator first (Vulkan SDK), then glslc (shaderc)
find_program(GLSLANG_VALIDATOR NAMES glslangValidator glslc)
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
if(GLSLANG_VALIDATOR)
    # -V = compile to SPIR-V; SDK 1.4.x glslangValidator does not use --target-env
    set(GLSLANG_FLAGS "-V" "-o")
    set(SHADER_SCALAR_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_scalar.inc")
set(SHADER_MINING_NONCE_INC "${CMAKE_CURRENT_SOURCE_DIR}/mining_nonce.inc")
    set(SHADER_SECOND_SCALAR_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_second_scalar.inc")
    set(SHADER_SECOND_CPU_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_second_cpu.inc")
    set(SHADER_SECOND_UVEC2_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_second_uvec2.inc")
    set(SHADER_SECOND_UVEC4_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_second_uvec4.inc")
    set(SHADER_SECOND_GEN "${CMAKE_CURRENT_SOURCE_DIR}/tools/gen_sha256_second_compress.py")
    set(SHADER_FIRST_MID_SCALAR_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_first_mid_scalar.inc")
    set(SHADER_FIRST_MID_CPU_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_first_mid_cpu.inc")
    set(SHADER_FIRST_MID_UVEC2_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_first_mid_uvec2.inc")
    set(SHADER_FIRST_MID_UVEC4_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_first_mid_uvec4.inc")
    set(SHADER_FIRST_MID_GEN "${CMAKE_CURRENT_SOURCE_DIR}/tools/gen_sha256_first_mid_compress.py")
    set(SHADER_CODEGEN "${CMAKE_CURRENT_SOURCE_DIR}/tools/sha256_compress_codegen.py")
    find_package(Python3 COMPONENTS Interpreter)
    if(Python3_Interpreter_FOUND)
        add_custom_command(
            OUTPUT "${SHADER_SECOND_SCALAR_INC}" "${SHADER_SECOND_CPU_INC}" "${SHADER_SECOND_UVEC2_INC}" "${SHADER_SECOND_UVEC4_INC}"
            COMMAND ${Python3_EXECUTABLE} "${SHADER_SECOND_GEN}"
            DEPENDS "${SHADER_SECOND_GEN}" "${SHADER_CODEGEN}"
            COMMENT "Generating sha256_compress_second_*.inc"
            WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
        )
        add_custom_command(
            OUTPUT "${SHADER_FIRST_MID_SCALAR_INC}" "${SHADER_FIRST_MID_CPU_INC}" "${SHADER_FIRST_MID_UVEC2_INC}" "${SHADER_FIRST_MID_UVEC4_INC}"
            COMMAND ${Python3_EXECUTABLE} "${SHADER_FIRST_MID_GEN}"
            DEPENDS "${SHADER_FIRST_MID_GEN}" "${SHADER_CODEGEN}"
            COMMENT "Generating sha256_compress_first_mid_*.inc"
            WORKING_DIRECTORY "${CMAKE_CURRENT_SOURCE_DIR}"
        )
    else()
        message(STATUS "Python3 not found; using committed sha256_compress_second_*.inc")
        message(STATUS "Python3 not found; using committed sha256_compress_first_mid_*.inc")
    endif()
    add_custom_command(
        OUTPUT "${SHADER_SCALAR_SPV}"
        COMMAND ${GLSLANG_VALIDATOR} ${GLSLANG_FLAGS} "${SHADER_SCALAR_SPV}" "${SHADER_SCALAR_SRC}"
        DEPENDS "${SHADER_SCALAR_SRC}" "${SHADER_SCALAR_INC}" "${SHADER_MINING_NONCE_INC}" "${SHADER_SECOND_SCALAR_INC}" "${SHADER_FIRST_MID_SCALAR_INC}" "${SHADER_SECOND_CPU_INC}" "${SHADER_FIRST_MID_CPU_INC}"
        COMMENT "Compiling miner.comp to SPIR-V"
    )
    add_custom_command(
        OUTPUT "${SHADER_SCALAR_H}"
        COMMAND ${CMAKE_COMMAND} -DSPV_FILE="${SHADER_SCALAR_SPV}" -DOUT_FILE="${SHADER_SCALAR_H}"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.cmake"
        DEPENDS "${SHADER_SCALAR_SPV}"
        COMMENT "Embedding SPIR-V into miner_spv.h"
    )
    add_custom_target(miner_shader ALL DEPENDS "${SHADER_SCALAR_H}")

    set(SHADER_UVEC4_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_uvec4.inc")
    add_custom_command(
        OUTPUT "${SHADER_UVEC4_SPV}"
        COMMAND ${GLSLANG_VALIDATOR} ${GLSLANG_FLAGS} "${SHADER_UVEC4_SPV}" "${SHADER_UVEC4_SRC}"
        DEPENDS "${SHADER_UVEC4_SRC}" "${SHADER_UVEC4_INC}" "${SHADER_MINING_NONCE_INC}"
            "${SHADER_SECOND_UVEC4_INC}" "${SHADER_FIRST_MID_UVEC4_INC}"
            "${SHADER_SECOND_GEN}" "${SHADER_FIRST_MID_GEN}" "${SHADER_CODEGEN}"
        COMMENT "Compiling miner_uvec4.comp to SPIR-V"
    )
    add_custom_command(
        OUTPUT "${SHADER_UVEC4_H}"
        COMMAND ${CMAKE_COMMAND} -DSPV_FILE="${SHADER_UVEC4_SPV}" -DOUT_FILE="${SHADER_UVEC4_H}"
            -DARRAY_NAME=g_miner_uvec4_spv -DHEADER_GUARD=MINER_UVEC4_SPV_H
            -P "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.cmake"
        DEPENDS "${SHADER_UVEC4_SPV}"
        COMMENT "Embedding SPIR-V into miner_uvec4_spv.h"
    )
    add_custom_target(miner_uvec4_shader ALL DEPENDS "${SHADER_UVEC4_H}")

    set(SHADER_UVEC2_INC "${CMAKE_CURRENT_SOURCE_DIR}/sha256_compress_uvec2.inc")
    add_custom_command(
        OUTPUT "${SHADER_UVEC2_SPV}"
        COMMAND ${GLSLANG_VALIDATOR} ${GLSLANG_FLAGS} "${SHADER_UVEC2_SPV}" "${SHADER_UVEC2_SRC}"
        DEPENDS "${SHADER_UVEC2_SRC}" "${SHADER_UVEC2_INC}" "${SHADER_MINING_NONCE_INC}"
            "${SHADER_SECOND_UVEC2_INC}" "${SHADER_FIRST_MID_UVEC2_INC}"
            "${SHADER_SECOND_GEN}" "${SHADER_FIRST_MID_GEN}" "${SHADER_CODEGEN}"
        COMMENT "Compiling miner_uvec2.comp to SPIR-V"
    )
    add_custom_command(
        OUTPUT "${SHADER_UVEC2_H}"
        COMMAND ${CMAKE_COMMAND} -DSPV_FILE="${SHADER_UVEC2_SPV}" -DOUT_FILE="${SHADER_UVEC2_H}"
            -DARRAY_NAME=g_miner_uvec2_spv -DHEADER_GUARD=MINER_UVEC2_SPV_H
            -P "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.cmake"
        DEPENDS "${SHADER_UVEC2_SPV}"
        COMMENT "Embedding SPIR-V into miner_uvec2_spv.h"
    )
    add_custom_target(miner_uvec2_shader ALL DEPENDS "${SHADER_UVEC2_H}")
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

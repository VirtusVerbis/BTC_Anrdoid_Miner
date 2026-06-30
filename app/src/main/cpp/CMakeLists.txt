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
    add_custom_command(
        OUTPUT "${SHADER_SCALAR_SPV}"
        COMMAND ${GLSLANG_VALIDATOR} ${GLSLANG_FLAGS} "${SHADER_SCALAR_SPV}" "${SHADER_SCALAR_SRC}"
        DEPENDS "${SHADER_SCALAR_SRC}"
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

    add_custom_command(
        OUTPUT "${SHADER_UVEC4_SPV}"
        COMMAND ${GLSLANG_VALIDATOR} ${GLSLANG_FLAGS} "${SHADER_UVEC4_SPV}" "${SHADER_UVEC4_SRC}"
        DEPENDS "${SHADER_UVEC4_SRC}"
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

    add_custom_command(
        OUTPUT "${SHADER_UVEC2_SPV}"
        COMMAND ${GLSLANG_VALIDATOR} ${GLSLANG_FLAGS} "${SHADER_UVEC2_SPV}" "${SHADER_UVEC2_SRC}"
        DEPENDS "${SHADER_UVEC2_SRC}"
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

set(MINER_SRCS miner.c sha256.c sha256_scan.c btc_header_sha256.c vulkan_miner.c)
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

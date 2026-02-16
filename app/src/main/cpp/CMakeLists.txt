cmake_minimum_required(VERSION 3.13.1)
project("miner")

# Compile compute shader to SPIR-V and embed as C array (for Vulkan compute path)
set(SHADER_SRC "${CMAKE_CURRENT_SOURCE_DIR}/miner.comp")
set(SHADER_SPV "${CMAKE_CURRENT_BINARY_DIR}/miner.spv")
set(SHADER_H   "${CMAKE_CURRENT_BINARY_DIR}/miner_spv.h")

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
    set(GLSLANG_FLAGS "-V" "-o" "${SHADER_SPV}")
    add_custom_command(
        OUTPUT "${SHADER_SPV}"
        COMMAND ${GLSLANG_VALIDATOR} ${GLSLANG_FLAGS} "${SHADER_SRC}"
        DEPENDS "${SHADER_SRC}"
        COMMENT "Compiling miner.comp to SPIR-V"
    )
    add_custom_command(
        OUTPUT "${SHADER_H}"
        COMMAND ${CMAKE_COMMAND} -DSPV_FILE="${SHADER_SPV}" -DOUT_FILE="${SHADER_H}"
            -P "${CMAKE_CURRENT_SOURCE_DIR}/embed_spv.cmake"
        DEPENDS "${SHADER_SPV}"
        COMMENT "Embedding SPIR-V into miner_spv.h"
    )
    add_custom_target(miner_shader ALL DEPENDS "${SHADER_H}")
else()
    message(WARNING "glslangValidator/glslc not found; Vulkan compute shader will not be built. Install Vulkan SDK or shaderc and add to PATH. GPU path will use CPU fallback.")
    # Create empty stub so build can proceed (vulkan_miner.c will fall back to CPU)
    file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}")
    file(WRITE "${SHADER_H}" "/* SPIR-V not built (no glslangValidator); GPU path uses CPU fallback */\n#ifndef MINER_SPV_H\n#define MINER_SPV_H\nstatic const unsigned char g_miner_spv[] = { 0 };\nstatic const unsigned int g_miner_spv_len = 0;\n#endif\n")
    add_custom_target(miner_shader ALL)
endif()

add_library(miner SHARED miner.c sha256.c vulkan_miner.c)
target_include_directories(miner PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
add_dependencies(miner miner_shader)

find_library(LOG_LIB log)
target_link_libraries(miner ${LOG_LIB})

if(ANDROID)
    target_link_libraries(miner vulkan)
endif()

# 16 KB page alignment for Android 15+ and 16 KB devices (Play requirement from Nov 2025)
target_link_options(miner PRIVATE "LINKER:-z,max-page-size=16384")

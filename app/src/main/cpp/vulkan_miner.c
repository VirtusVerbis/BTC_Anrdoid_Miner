/*
 * Vulkan GPU miner JNI.
 * gpuIsAvailable(): initializes Vulkan (instance, device, compute queue). Returns true if Vulkan is present.
 * gpuScanNoncesInto(): scans nonce range via compute shader; writes status + nonce into jlong[2] (GPU JNI codes only).
 */
#include "sha256.h"
#include "btc_header_sha256.h"
#include <jni.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <android/log.h>

#ifdef __ANDROID__
#include <sys/system_properties.h>
#include <vulkan/vulkan.h>
#include "miner_spv.h"
#include "miner_uvec2_spv.h"
#include "miner_uvec4_spv.h"
#endif

/* Match com.btcminer.android.config.GpuSha256Mode ordinals. */
#define GPU_MODE_FULL 0
#define GPU_MODE_MIDSTATE 1
#define GPU_MODE_UVEC4_MIDSTATE 2
#define GPU_MODE_UVEC2_MIDSTATE 3

#define HEADER_PREFIX_SIZE 76
#define BLOCK_HEADER_SIZE 80
#define HASH_SIZE 32
#define UBO_SIZE 256
#define UBO_HEADER_WORDS (HEADER_PREFIX_SIZE / 4)
#define UBO_OFFSET_MIDSTATE (HEADER_PREFIX_SIZE)
#define UBO_OFFSET_NONCE_START (HEADER_PREFIX_SIZE + 32u)
#define UBO_OFFSET_NONCE_END (UBO_OFFSET_NONCE_START + 4u)
#define UBO_OFFSET_TARGET (UBO_OFFSET_NONCE_END + 4u)
#define UBO_OFFSET_GPU_USE_MIDSTATE (UBO_OFFSET_TARGET + (uint32_t)HASH_SIZE)
#define UBO_OFFSET_GPU_SELFTEST (UBO_OFFSET_GPU_USE_MIDSTATE + 4u)
#define UBO_HOST_PAYLOAD_BYTES (UBO_OFFSET_GPU_SELFTEST + 4u)
_Static_assert(UBO_HOST_PAYLOAD_BYTES <= UBO_SIZE, "UBO host layout exceeds UBO_SIZE; update vulkan_miner.c and miner.comp");
_Static_assert(HEADER_PREFIX_SIZE % 4 == 0, "UBO header must be a multiple of 4 bytes");
#define RESULT_BUFFER_SIZE 128
#define GPU_SELFTEST_TAG "GPU_SHA_SelfTest"
#define LOG_TAG "VulkanMiner"
#define MAX_GPU_WORKGROUP_STEPS 64
#define GPU_HASH_PER_THREAD_VARIANTS 4
#define GPU_PIPELINE_SLOT_COUNT (MAX_GPU_WORKGROUP_STEPS * GPU_HASH_PER_THREAD_VARIANTS)
/** Max sub-ranges batched into one vkQueueSubmit; beyond this, fall back to per-pass submits. */
#define GPU_MAX_MULTIPASS_PASSES 64
/** Double-buffer pipelined submits when session active; 0 = synchronous gpuScanNoncesInto only. */
#define GPU_PIPE_ENABLED 1
#define GPU_PIPE_SLOT_COUNT 2
/* Returned to Java when GPU path is unavailable (no SPIR-V or Vulkan failure). */
#define GPU_UNAVAILABLE (-2)
/* JNI jlong[0] status values for GPU path only (not shared with miner.c). */
#define GPU_JNI_STATUS_MISS 0
#define GPU_JNI_STATUS_HIT 1
#define GPU_JNI_STATUS_UNAVAILABLE (-2)
/* SSBO layout words 0..1 = resultFound, winningNonce; words 2..9 first_hash; 10..17 final_hash (miner.comp). */
#define RES_WORD_FOUND 0u
#define RES_WORD_NONCE 1u
#define RES_WORD_FIRST_HASH 2u
#define RES_SELFTEST_FOUND_MAGIC 2u

/* Same ordering as sha256_scan.c / bitcoinjs checkProofOfWork (reversed digest vs target). */
static int hash_meets_target(const uint8_t *hash, const uint8_t *target) {
    uint8_t rev[HASH_SIZE];
    for (int i = 0; i < HASH_SIZE; i++)
        rev[i] = hash[HASH_SIZE - 1 - i];
    return memcmp(rev, target, HASH_SIZE) <= 0;
}

/** Mining dispatch: SSBO word0=0 (no hit yet), word1=0xFFFFFFFF for atomicMin baseline; rest cleared. */
static void mining_result_buffer_reset(void *ptr) {
    uint32_t head[2] = {0u, 0xFFFFFFFFu};
    memcpy(ptr, head, sizeof(head));
    memset((uint8_t *)ptr + sizeof(head), 0, RESULT_BUFFER_SIZE - sizeof(head));
}

#ifdef __ANDROID__
static VkInstance g_instance = VK_NULL_HANDLE;
static VkDevice g_device = VK_NULL_HANDLE;
static VkPhysicalDevice g_physicalDevice = VK_NULL_HANDLE;
static VkQueue g_queue = VK_NULL_HANDLE;
static uint32_t g_computeQueueFamily = 0;
static uint32_t g_maxWorkGroupSize = 256;
static uint32_t g_maxWorkGroupInvocations = 256;
static uint32_t g_effectiveMaxLocalSizeX = 256;
static uint32_t g_maxWorkGroupCount = 65535;
static int g_vulkan_available = -1;
static int g_limits_logged = 0;
static int g_env_logged = 0;
static char g_deviceName[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE];
static char g_driverName[256];

typedef enum {
    VULKAN_ENV_UNKNOWN = -1,
    VULKAN_ENV_REAL_DEVICE = 0,
    VULKAN_ENV_EMULATOR = 1,
} VulkanRuntimeEnv;

static VulkanRuntimeEnv g_vulkan_env = VULKAN_ENV_UNKNOWN;

static VkDescriptorSetLayout g_descriptorSetLayout = VK_NULL_HANDLE;
static VkPipelineLayout g_pipelineLayout = VK_NULL_HANDLE;
static VkPipeline g_pipelines[GPU_PIPELINE_SLOT_COUNT];
static VkPipeline g_pipelines_uvec2[GPU_PIPELINE_SLOT_COUNT];
static VkPipeline g_pipelines_uvec4[GPU_PIPELINE_SLOT_COUNT];
static VkPipeline g_pipeline_selftest = VK_NULL_HANDLE;
static VkPipeline g_pipeline_selftest_uvec2 = VK_NULL_HANDLE;
static VkPipeline g_pipeline_selftest_uvec4 = VK_NULL_HANDLE;
typedef struct {
    VkBuffer uboBuffer;
    VkDeviceMemory uboMemory;
    void *uboMapped;
    VkBuffer resultBuffer;
    VkDeviceMemory resultMemory;
    void *resultMapped;
    VkCommandBuffer commandBuffer;
    VkFence fence;
    VkDescriptorSet descriptorSet;
    uint32_t nonceStart;
    uint32_t nonceEnd;
    int inFlight;
} GpuPipeSlot;

typedef struct {
    int active;
    int staticUboReady;
    int writeIndex;
    int inFlightIndex;
    int syncPending;
    int syncPendingHit;
    uint32_t syncPendingNonce;
    uint8_t header76[HEADER_PREFIX_SIZE];
    uint8_t target[HASH_SIZE];
    uint32_t mid[8];
    int useMidstate;
    int gpuSha256Mode;
    VkPipeline miningPipe;
    uint32_t localSize;
    uint32_t hpt;
    uint32_t noncesPerThread;
} GpuPipeSession;

static GpuPipeSlot g_pipeSlots[GPU_PIPE_SLOT_COUNT];
static GpuPipeSession g_pipeSession;
static int s_gpu_pipe_logged;

static VkDescriptorPool g_descriptorPool = VK_NULL_HANDLE;
/** Legacy aliases for slot 0 (self-test + synchronous scan). */
static VkDescriptorSet g_descriptorSet = VK_NULL_HANDLE;
static VkBuffer g_uboBuffer = VK_NULL_HANDLE;
static VkDeviceMemory g_uboMemory = VK_NULL_HANDLE;
static VkBuffer g_resultBuffer = VK_NULL_HANDLE;
static VkDeviceMemory g_resultMemory = VK_NULL_HANDLE;
static void *g_uboMapped = NULL;
static void *g_resultMapped = NULL;
static VkCommandPool g_commandPool = VK_NULL_HANDLE;
static VkCommandBuffer g_commandBuffer = VK_NULL_HANDLE;
static VkFence g_fence = VK_NULL_HANDLE;

static int g_resources_logged = 0;
static int g_pipeline_created_logged = 0;
static int g_first_dispatch_state = 0;
static int g_workgroup_size_logged = 0;
static atomic_int g_interrupt_requested = 0;
/** Set in ensure_compute_resources: false if we fell back to host-visible without HOST_COHERENT. */
static int g_host_mem_coherent = 1;

static const char* vk_result_str(VkResult r) {
    switch ((int)r) {
        case 0: return "VK_SUCCESS";
        case -1: return "VK_ERROR_OUT_OF_MEMORY";
        case -2: return "VK_ERROR_INITIALIZATION_FAILED";
        case -4: return "VK_ERROR_DEVICE_LOST";
        case -7: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        case -12: return "VK_TIMEOUT";
        default: return "VK_OTHER";
    }
}

static void host_flush_before_gpu_read(VkDeviceMemory mem) {
    if (g_host_mem_coherent || mem == VK_NULL_HANDLE)
        return;
    VkMappedMemoryRange range = {
        .sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
        .memory = mem,
        .offset = 0,
        .size = VK_WHOLE_SIZE,
    };
    vkFlushMappedMemoryRanges(g_device, 1, &range);
}

static void host_flush_range(VkDeviceMemory mem, VkDeviceSize offset, VkDeviceSize size) {
    if (g_host_mem_coherent || mem == VK_NULL_HANDLE)
        return;
    VkMappedMemoryRange range = {
        .sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
        .memory = mem,
        .offset = offset,
        .size = size,
    };
    vkFlushMappedMemoryRanges(g_device, 1, &range);
}

/** Call only while [mem] is host-mapped for this device (see Vulkan spec). */
static void host_invalidate_after_gpu_write_while_mapped(VkDeviceMemory mem) {
    if (g_host_mem_coherent || mem == VK_NULL_HANDLE)
        return;
    VkMappedMemoryRange range = {
        .sType = VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE,
        .memory = mem,
        .offset = 0,
        .size = VK_WHOLE_SIZE,
    };
    vkInvalidateMappedMemoryRanges(g_device, 1, &range);
}

static void cleanup_vulkan(void);
static void gpu_pipeline_session_end_inner(void);
static int run_gpu_scan(const uint8_t *header76, uint32_t nonceStart, uint32_t nonceEnd, const uint8_t *target,
                        int localSizeX, int hashesPerThread, int gpuSha256Mode, int *hit_out, uint32_t *nonce_out);

static void sync_legacy_slot0_aliases(void) {
    g_descriptorSet = g_pipeSlots[0].descriptorSet;
    g_uboBuffer = g_pipeSlots[0].uboBuffer;
    g_uboMemory = g_pipeSlots[0].uboMemory;
    g_uboMapped = g_pipeSlots[0].uboMapped;
    g_resultBuffer = g_pipeSlots[0].resultBuffer;
    g_resultMemory = g_pipeSlots[0].resultMemory;
    g_resultMapped = g_pipeSlots[0].resultMapped;
    g_commandBuffer = g_pipeSlots[0].commandBuffer;
    g_fence = g_pipeSlots[0].fence;
}

static void unmap_pipe_slot(GpuPipeSlot *slot) {
    if (slot->uboMapped != NULL) {
        vkUnmapMemory(g_device, slot->uboMemory);
        slot->uboMapped = NULL;
    }
    if (slot->resultMapped != NULL) {
        vkUnmapMemory(g_device, slot->resultMemory);
        slot->resultMapped = NULL;
    }
}

static void unmap_host_buffers(void) {
    for (uint32_t i = 0; i < GPU_PIPE_SLOT_COUNT; i++)
        unmap_pipe_slot(&g_pipeSlots[i]);
    g_uboMapped = NULL;
    g_resultMapped = NULL;
}

static int map_pipe_slot(GpuPipeSlot *slot) {
    if (slot->uboMapped != NULL && slot->resultMapped != NULL)
        return 1;
    if (slot->uboMemory == VK_NULL_HANDLE || slot->resultMemory == VK_NULL_HANDLE)
        return 0;
    VkResult mapRes = vkMapMemory(g_device, slot->uboMemory, 0, UBO_SIZE, 0, &slot->uboMapped);
    if (mapRes != VK_SUCCESS) {
        slot->uboMapped = NULL;
        if (mapRes == VK_ERROR_DEVICE_LOST)
            cleanup_vulkan();
        return 0;
    }
    mapRes = vkMapMemory(g_device, slot->resultMemory, 0, RESULT_BUFFER_SIZE, 0, &slot->resultMapped);
    if (mapRes != VK_SUCCESS) {
        vkUnmapMemory(g_device, slot->uboMemory);
        slot->uboMapped = NULL;
        slot->resultMapped = NULL;
        if (mapRes == VK_ERROR_DEVICE_LOST)
            cleanup_vulkan();
        return 0;
    }
    return 1;
}

/** Map UBO + result SSBO for all pipe slots. Idempotent. */
static int map_host_buffers(void) {
    for (uint32_t i = 0; i < GPU_PIPE_SLOT_COUNT; i++) {
        if (!map_pipe_slot(&g_pipeSlots[i]))
            return 0;
    }
    sync_legacy_slot0_aliases();
    return 1;
}

static int gpu_mode_uses_midstate(int gpuSha256Mode) {
    return gpuSha256Mode != GPU_MODE_FULL;
}

typedef enum {
    GPU_SHADER_SCALAR = 0,
    GPU_SHADER_UVEC2 = 1,
    GPU_SHADER_UVEC4 = 2,
} GpuShaderVariant;

static GpuShaderVariant gpu_mode_shader_variant(int gpuSha256Mode) {
    switch (gpuSha256Mode) {
        case GPU_MODE_UVEC4_MIDSTATE:
            return GPU_SHADER_UVEC4;
        case GPU_MODE_UVEC2_MIDSTATE:
            return GPU_SHADER_UVEC2;
        default:
            return GPU_SHADER_SCALAR;
    }
}

static uint32_t gpu_vector_width(int gpuSha256Mode) {
    switch (gpu_mode_shader_variant(gpuSha256Mode)) {
        case GPU_SHADER_UVEC4:
            return 4u;
        case GPU_SHADER_UVEC2:
            return 2u;
        default:
            return 1u;
    }
}

static int create_miner_shader_module(GpuShaderVariant variant, VkShaderModule *outModule) {
    size_t codeSize;
    const uint32_t *pCode;
    const char *label;
    switch (variant) {
        case GPU_SHADER_UVEC4:
            codeSize = (size_t)g_miner_uvec4_spv_len;
            pCode = (const uint32_t *)g_miner_uvec4_spv;
            label = "uvec4";
            break;
        case GPU_SHADER_UVEC2:
            codeSize = (size_t)g_miner_uvec2_spv_len;
            pCode = (const uint32_t *)g_miner_uvec2_spv;
            label = "uvec2";
            break;
        default:
            codeSize = (size_t)g_miner_spv_len;
            pCode = (const uint32_t *)g_miner_spv;
            label = "scalar";
            break;
    }
    VkShaderModuleCreateInfo modInfo = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = codeSize,
        .pCode = pCode,
    };
    if (codeSize == 0 || (codeSize % 4) != 0) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "No SPIR-V (%s); using CPU fallback", label);
        return 0;
    }
    if (vkCreateShaderModule(g_device, &modInfo, NULL, outModule) != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "vkCreateShaderModule failed");
        return 0;
    }
    return 1;
}

static uint32_t effective_max_local_size_x(void) {
    uint32_t maxLs = g_effectiveMaxLocalSizeX;
    if (maxLs < 32u)
        maxLs = 32u;
    return (maxLs / 32u) * 32u;
}

static uint32_t normalize_local_size_x(int localSizeX) {
    uint32_t maxLs = effective_max_local_size_x();
    if (localSizeX < 32)
        localSizeX = 32;
    uint32_t ls = (uint32_t)localSizeX;
    if (ls > maxLs)
        ls = maxLs;
    ls = (ls / 32u) * 32u;
    if (ls < 32u)
        ls = 32u;
    return ls;
}

static uint32_t normalize_hashes_per_thread(int hashesPerThread) {
    switch (hashesPerThread) {
        case 2: return 2u;
        case 4: return 4u;
        case 8: return 8u;
        default: return 1u;
    }
}

static uint32_t hashes_per_thread_variant_index(uint32_t hashesPerThread) {
    switch (hashesPerThread) {
        case 2u: return 1u;
        case 4u: return 2u;
        case 8u: return 3u;
        default: return 0u;
    }
}

static uint32_t pipeline_slot(uint32_t localSize, uint32_t hashesPerThread) {
    uint32_t lsSlot = localSize / 32u;
    if (lsSlot < 1u || lsSlot > MAX_GPU_WORKGROUP_STEPS)
        return UINT32_MAX;
    return (lsSlot - 1u) * GPU_HASH_PER_THREAD_VARIANTS + hashes_per_thread_variant_index(hashesPerThread);
}

typedef void (VKAPI_PTR *PFN_vkGetPhysicalDeviceProperties2)(
    VkPhysicalDevice device, VkPhysicalDeviceProperties2 *pProperties);

static int str_contains_ci(const char *haystack, const char *needle) {
    if (!haystack || !needle || !needle[0])
        return 0;
    return strcasestr(haystack, needle) != NULL;
}

static int android_prop_equals(const char *key, const char *value) {
    char buf[PROP_VALUE_MAX] = {0};
    if (__system_property_get(key, buf) <= 0)
        return 0;
    return strcmp(buf, value) == 0;
}

static int android_prop_contains_ci(const char *key, const char *needle) {
    char buf[PROP_VALUE_MAX] = {0};
    if (__system_property_get(key, buf) <= 0)
        return 0;
    return str_contains_ci(buf, needle);
}

static VulkanRuntimeEnv detect_vulkan_runtime_env(void) {
    if (g_vulkan_env != VULKAN_ENV_UNKNOWN)
        return g_vulkan_env;

    int emulator = 0;
    if (android_prop_equals("ro.kernel.qemu", "1"))
        emulator = 1;
    else if (android_prop_contains_ci("ro.hardware", "goldfish") ||
             android_prop_contains_ci("ro.hardware", "ranchu") ||
             android_prop_contains_ci("ro.hardware", "vexpress"))
        emulator = 1;
    else if (android_prop_contains_ci("ro.product.model", "sdk_gphone") ||
             android_prop_contains_ci("ro.product.model", "Android SDK built for x86"))
        emulator = 1;
    else if (android_prop_contains_ci("ro.build.fingerprint", "generic") ||
             android_prop_contains_ci("ro.build.fingerprint", "emulator") ||
             android_prop_contains_ci("ro.build.fingerprint", "sdk_gphone"))
        emulator = 1;

    g_vulkan_env = emulator ? VULKAN_ENV_EMULATOR : VULKAN_ENV_REAL_DEVICE;
    if (!g_env_logged) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "runtime env=%s",
            g_vulkan_env == VULKAN_ENV_EMULATOR ? "emulator" : "real_device");
        g_env_logged = 1;
    }
    return g_vulkan_env;
}

static int device_supports_driver_properties(uint32_t apiVersion) {
    if (apiVersion >= VK_API_VERSION_1_2)
        return 1;
    uint32_t extCount = 0;
    if (vkEnumerateDeviceExtensionProperties(g_physicalDevice, NULL, &extCount, NULL) != VK_SUCCESS ||
        extCount == 0)
        return 0;
    VkExtensionProperties *exts = (VkExtensionProperties *)malloc(extCount * sizeof(VkExtensionProperties));
    if (!exts)
        return 0;
    if (vkEnumerateDeviceExtensionProperties(g_physicalDevice, NULL, &extCount, exts) != VK_SUCCESS) {
        free(exts);
        return 0;
    }
    int found = 0;
    for (uint32_t i = 0; i < extCount; i++) {
        if (strcmp(exts[i].extensionName, "VK_KHR_driver_properties") == 0) {
            found = 1;
            break;
        }
    }
    free(exts);
    return found;
}

static void try_fill_driver_name(const VkPhysicalDeviceProperties *props) {
    g_driverName[0] = '\0';
    if (!props || g_instance == VK_NULL_HANDLE || g_physicalDevice == VK_NULL_HANDLE)
        return;
    if (!device_supports_driver_properties(props->apiVersion))
        return;
    PFN_vkGetPhysicalDeviceProperties2 pfn = (PFN_vkGetPhysicalDeviceProperties2)
        vkGetInstanceProcAddr(g_instance, "vkGetPhysicalDeviceProperties2");
    if (!pfn)
        return;
    VkPhysicalDeviceDriverProperties driverProps = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES,
    };
    VkPhysicalDeviceProperties2 props2 = {
        .sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2,
        .pNext = &driverProps,
    };
    pfn(g_physicalDevice, &props2);
    strncpy(g_driverName, driverProps.driverName, sizeof(g_driverName) - 1);
    g_driverName[sizeof(g_driverName) - 1] = '\0';
}

static void update_and_log_vulkan_limits(void) {
    if (g_physicalDevice == VK_NULL_HANDLE)
        return;
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(g_physicalDevice, &props);
    g_maxWorkGroupSize = props.limits.maxComputeWorkGroupSize[0];
    g_maxWorkGroupInvocations = props.limits.maxComputeWorkGroupInvocations;
    g_maxWorkGroupCount = props.limits.maxComputeWorkGroupCount[0];
    g_effectiveMaxLocalSizeX = g_maxWorkGroupSize;
    if (g_effectiveMaxLocalSizeX > g_maxWorkGroupInvocations)
        g_effectiveMaxLocalSizeX = g_maxWorkGroupInvocations;

    strncpy(g_deviceName, props.deviceName, sizeof(g_deviceName) - 1);
    g_deviceName[sizeof(g_deviceName) - 1] = '\0';

    if (detect_vulkan_runtime_env() == VULKAN_ENV_REAL_DEVICE)
        try_fill_driver_name(&props);
    else
        g_driverName[0] = '\0';

    if (!g_limits_logged) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG,
            "Vulkan limits: maxLocalSizeX=%u maxComputeWorkGroupCount[0]=%u device=%s driver=%s",
            (unsigned)effective_max_local_size_x(), (unsigned)g_maxWorkGroupCount, g_deviceName, g_driverName);
        g_limits_logged = 1;
    }
}

static int create_pipeline_with_spec(uint32_t localSize, uint32_t hashesPerThread, GpuShaderVariant variant,
                                     VkPipeline *outPipeline) {
    VkShaderModule shaderModule;
    if (!create_miner_shader_module(variant, &shaderModule))
        return 0;
    uint32_t specData[2] = { localSize, hashesPerThread };
    VkSpecializationMapEntry specMap[2] = {
        { .constantID = 0, .offset = 0, .size = sizeof(uint32_t) },
        { .constantID = 1, .offset = sizeof(uint32_t), .size = sizeof(uint32_t) },
    };
    VkSpecializationInfo specInfo = {
        .mapEntryCount = 2,
        .pMapEntries = specMap,
        .dataSize = sizeof(specData),
        .pData = specData,
    };
    VkPipelineShaderStageCreateInfo stageInfo = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
        .stage = VK_SHADER_STAGE_COMPUTE_BIT,
        .module = shaderModule,
        .pName = "main",
        .pSpecializationInfo = &specInfo,
    };
    VkComputePipelineCreateInfo pipeInfo = {
        .sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO,
        .stage = stageInfo,
        .layout = g_pipelineLayout,
    };
    VkResult res = vkCreateComputePipelines(g_device, VK_NULL_HANDLE, 1, &pipeInfo, NULL, outPipeline);
    vkDestroyShaderModule(g_device, shaderModule, NULL);
    if (res != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "vkCreateComputePipelines failed");
        return 0;
    }
    if (!g_pipeline_created_logged) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Compute shader loaded and pipeline created for GPU");
        g_pipeline_created_logged = 1;
    }
    return 1;
}

static VkPipeline *mining_pipeline_slot(GpuShaderVariant variant, uint32_t slot) {
    switch (variant) {
        case GPU_SHADER_UVEC4:
            return &g_pipelines_uvec4[slot];
        case GPU_SHADER_UVEC2:
            return &g_pipelines_uvec2[slot];
        default:
            return &g_pipelines[slot];
    }
}

static VkPipeline *selftest_pipeline_slot(GpuShaderVariant variant) {
    switch (variant) {
        case GPU_SHADER_UVEC4:
            return &g_pipeline_selftest_uvec4;
        case GPU_SHADER_UVEC2:
            return &g_pipeline_selftest_uvec2;
        default:
            return &g_pipeline_selftest;
    }
}

static int ensure_mining_pipeline(uint32_t localSizeX, uint32_t hashesPerThread, GpuShaderVariant variant) {
    localSizeX = normalize_local_size_x((int)localSizeX);
    hashesPerThread = normalize_hashes_per_thread((int)hashesPerThread);
    uint32_t slot = pipeline_slot(localSizeX, hashesPerThread);
    if (slot >= GPU_PIPELINE_SLOT_COUNT)
        return 0;
    VkPipeline *pipeSlot = mining_pipeline_slot(variant, slot);
    if (*pipeSlot != VK_NULL_HANDLE)
        return 1;
    return create_pipeline_with_spec(localSizeX, hashesPerThread, variant, pipeSlot);
}

static int ensure_selftest_pipeline(GpuShaderVariant variant) {
    VkPipeline *pipeSlot = selftest_pipeline_slot(variant);
    if (*pipeSlot != VK_NULL_HANDLE)
        return 1;
    return create_pipeline_with_spec(1u, 1u, variant, pipeSlot);
}

static int ensure_compute_resources(void) {
    if (g_descriptorSetLayout != VK_NULL_HANDLE) {
        if (!map_host_buffers())
            return 0;
        if (!g_resources_logged) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan compute resources ready (buffers, command buffer, fence)");
            g_resources_logged = 1;
        }
        return 1;
    }
    if (g_miner_spv_len == 0) {
        if (!g_resources_logged) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan compute resources failed (buffers/setup)");
            g_resources_logged = 1;
        }
        return 0;
    }
    VkDescriptorSetLayoutBinding bindings[2] = {
        { .binding = 0, .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT },
        { .binding = 1, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .descriptorCount = 1, .stageFlags = VK_SHADER_STAGE_COMPUTE_BIT },
    };
    VkDescriptorSetLayoutCreateInfo layoutInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO,
        .bindingCount = 2,
        .pBindings = bindings,
    };
    if (vkCreateDescriptorSetLayout(g_device, &layoutInfo, NULL, &g_descriptorSetLayout) != VK_SUCCESS) {
        if (!g_resources_logged) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan compute resources failed (buffers/setup)");
            g_resources_logged = 1;
        }
        return 0;
    }
    VkPipelineLayoutCreateInfo pipeLayoutInfo = {
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .setLayoutCount = 1,
        .pSetLayouts = &g_descriptorSetLayout,
    };
    if (vkCreatePipelineLayout(g_device, &pipeLayoutInfo, NULL, &g_pipelineLayout) != VK_SUCCESS) {
        vkDestroyDescriptorSetLayout(g_device, g_descriptorSetLayout, NULL);
        g_descriptorSetLayout = VK_NULL_HANDLE;
        if (!g_resources_logged) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan compute resources failed (buffers/setup)");
            g_resources_logged = 1;
        }
        return 0;
    }
    VkDescriptorPoolSize poolSizes[2] = {
        { VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, GPU_PIPE_SLOT_COUNT },
        { VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, GPU_PIPE_SLOT_COUNT },
    };
    VkDescriptorPoolCreateInfo poolInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .maxSets = GPU_PIPE_SLOT_COUNT,
        .poolSizeCount = 2,
        .pPoolSizes = poolSizes,
    };
    if (vkCreateDescriptorPool(g_device, &poolInfo, NULL, &g_descriptorPool) != VK_SUCCESS) {
        vkDestroyPipelineLayout(g_device, g_pipelineLayout, NULL);
        vkDestroyDescriptorSetLayout(g_device, g_descriptorSetLayout, NULL);
        g_pipelineLayout = VK_NULL_HANDLE;
        g_descriptorSetLayout = VK_NULL_HANDLE;
        if (!g_resources_logged) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan compute resources failed (buffers/setup)");
            g_resources_logged = 1;
        }
        return 0;
    }
    VkDescriptorSetLayout setLayouts[GPU_PIPE_SLOT_COUNT] = {
        g_descriptorSetLayout, g_descriptorSetLayout,
    };
    VkDescriptorSet sets[GPU_PIPE_SLOT_COUNT];
    VkDescriptorSetAllocateInfo allocInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = g_descriptorPool,
        .descriptorSetCount = GPU_PIPE_SLOT_COUNT,
        .pSetLayouts = setLayouts,
    };
    if (vkAllocateDescriptorSets(g_device, &allocInfo, sets) != VK_SUCCESS) {
        vkDestroyDescriptorPool(g_device, g_descriptorPool, NULL);
        vkDestroyPipelineLayout(g_device, g_pipelineLayout, NULL);
        vkDestroyDescriptorSetLayout(g_device, g_descriptorSetLayout, NULL);
        g_descriptorPool = VK_NULL_HANDLE;
        g_pipelineLayout = VK_NULL_HANDLE;
        g_descriptorSetLayout = VK_NULL_HANDLE;
        if (!g_resources_logged) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan compute resources failed (buffers/setup)");
            g_resources_logged = 1;
        }
        return 0;
    }
    VkMemoryRequirements memReq;
    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(g_physicalDevice, &memProps);
    uint32_t memTypeIndex = (uint32_t)-1;
    VkBufferCreateInfo bufInfo = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = UBO_SIZE,
        .usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
    };
    VkMemoryRequirements uboMemReq;
    if (vkCreateBuffer(g_device, &bufInfo, NULL, &g_pipeSlots[0].uboBuffer) != VK_SUCCESS)
        goto fail_buffers;
    vkGetBufferMemoryRequirements(g_device, g_pipeSlots[0].uboBuffer, &uboMemReq);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((uboMemReq.memoryTypeBits & (1u << i)) &&
            (memProps.memoryTypes[i].propertyFlags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
                (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
            memTypeIndex = i;
            break;
        }
    }
    if (memTypeIndex == (uint32_t)-1) {
        for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
            if ((uboMemReq.memoryTypeBits & (1u << i)) && (memProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
                memTypeIndex = i;
                break;
            }
        }
    }
    if (memTypeIndex == (uint32_t)-1)
        goto fail_buffers;
    g_host_mem_coherent =
        (memProps.memoryTypes[memTypeIndex].propertyFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
    VkMemoryAllocateInfo allocMem = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = uboMemReq.size,
        .memoryTypeIndex = memTypeIndex,
    };
    for (uint32_t si = 0; si < GPU_PIPE_SLOT_COUNT; si++) {
        GpuPipeSlot *ps = &g_pipeSlots[si];
        ps->descriptorSet = sets[si];
        ps->inFlight = 0;
        ps->nonceStart = 0u;
        ps->nonceEnd = 0u;
        if (si > 0) {
            bufInfo.size = UBO_SIZE;
            bufInfo.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
            if (vkCreateBuffer(g_device, &bufInfo, NULL, &ps->uboBuffer) != VK_SUCCESS)
                goto fail_slot_partial;
            vkGetBufferMemoryRequirements(g_device, ps->uboBuffer, &memReq);
            allocMem.allocationSize = memReq.size;
            if (vkAllocateMemory(g_device, &allocMem, NULL, &ps->uboMemory) != VK_SUCCESS)
                goto fail_slot_partial;
            vkBindBufferMemory(g_device, ps->uboBuffer, ps->uboMemory, 0);
        } else {
            ps->uboBuffer = g_pipeSlots[0].uboBuffer;
            if (vkAllocateMemory(g_device, &allocMem, NULL, &ps->uboMemory) != VK_SUCCESS)
                goto fail_buffers;
            vkBindBufferMemory(g_device, ps->uboBuffer, ps->uboMemory, 0);
        }
        bufInfo.size = RESULT_BUFFER_SIZE;
        bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        if (vkCreateBuffer(g_device, &bufInfo, NULL, &ps->resultBuffer) != VK_SUCCESS)
            goto fail_slot_partial;
        vkGetBufferMemoryRequirements(g_device, ps->resultBuffer, &memReq);
        allocMem.allocationSize = memReq.size;
        if (vkAllocateMemory(g_device, &allocMem, NULL, &ps->resultMemory) != VK_SUCCESS)
            goto fail_slot_partial;
        vkBindBufferMemory(g_device, ps->resultBuffer, ps->resultMemory, 0);
        VkDescriptorBufferInfo uboInfo = { ps->uboBuffer, 0, UBO_SIZE };
        VkDescriptorBufferInfo resultInfo = { ps->resultBuffer, 0, RESULT_BUFFER_SIZE };
        VkWriteDescriptorSet writes[2] = {
            { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = ps->descriptorSet, .dstBinding = 0, .descriptorCount = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, .pBufferInfo = &uboInfo },
            { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = ps->descriptorSet, .dstBinding = 1, .descriptorCount = 1,
                .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .pBufferInfo = &resultInfo },
        };
        vkUpdateDescriptorSets(g_device, 2, writes, 0, NULL);
        VkFenceCreateInfo fenceInfo = { .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
        if (vkCreateFence(g_device, &fenceInfo, NULL, &ps->fence) != VK_SUCCESS)
            goto fail_slot_partial;
    }
    bufInfo.size = UBO_SIZE;
    bufInfo.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    VkCommandPoolCreateInfo cmdPoolInfo = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
        .queueFamilyIndex = g_computeQueueFamily,
    };
    if (vkCreateCommandPool(g_device, &cmdPoolInfo, NULL, &g_commandPool) != VK_SUCCESS)
        goto fail_slot_partial;
    VkCommandBuffer cmdBufs[GPU_PIPE_SLOT_COUNT];
    VkCommandBufferAllocateInfo cmdAlloc = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = g_commandPool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = GPU_PIPE_SLOT_COUNT,
    };
    if (vkAllocateCommandBuffers(g_device, &cmdAlloc, cmdBufs) != VK_SUCCESS) {
        vkDestroyCommandPool(g_device, g_commandPool, NULL);
        g_commandPool = VK_NULL_HANDLE;
        goto fail_slot_partial;
    }
    for (uint32_t si = 0; si < GPU_PIPE_SLOT_COUNT; si++)
        g_pipeSlots[si].commandBuffer = cmdBufs[si];
    if (!map_host_buffers())
        goto fail_result;
    if (!s_gpu_pipe_logged && GPU_PIPE_ENABLED) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "GPU double-buffer pipe enabled (%u slots)", (unsigned)GPU_PIPE_SLOT_COUNT);
        s_gpu_pipe_logged = 1;
    }
    if (!g_resources_logged) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan compute resources ready (buffers, command buffer, fence)");
        g_resources_logged = 1;
    }
    return 1;
fail_result:
    unmap_host_buffers();
fail_slot_partial:
    for (uint32_t si = 0; si < GPU_PIPE_SLOT_COUNT; si++) {
        GpuPipeSlot *ps = &g_pipeSlots[si];
        if (ps->fence != VK_NULL_HANDLE) {
            vkDestroyFence(g_device, ps->fence, NULL);
            ps->fence = VK_NULL_HANDLE;
        }
        if (ps->resultMemory != VK_NULL_HANDLE) {
            vkFreeMemory(g_device, ps->resultMemory, NULL);
            ps->resultMemory = VK_NULL_HANDLE;
        }
        if (ps->resultBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(g_device, ps->resultBuffer, NULL);
            ps->resultBuffer = VK_NULL_HANDLE;
        }
        if (si > 0 && ps->uboMemory != VK_NULL_HANDLE) {
            vkFreeMemory(g_device, ps->uboMemory, NULL);
            ps->uboMemory = VK_NULL_HANDLE;
        }
        if (si > 0 && ps->uboBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(g_device, ps->uboBuffer, NULL);
            ps->uboBuffer = VK_NULL_HANDLE;
        }
        ps->descriptorSet = VK_NULL_HANDLE;
        ps->commandBuffer = VK_NULL_HANDLE;
    }
    if (g_commandPool != VK_NULL_HANDLE) {
        vkDestroyCommandPool(g_device, g_commandPool, NULL);
        g_commandPool = VK_NULL_HANDLE;
    }
fail_ubo:
    if (g_pipeSlots[0].uboMemory != VK_NULL_HANDLE) {
        vkFreeMemory(g_device, g_pipeSlots[0].uboMemory, NULL);
        g_pipeSlots[0].uboMemory = VK_NULL_HANDLE;
    }
    if (g_pipeSlots[0].uboBuffer != VK_NULL_HANDLE) {
        vkDestroyBuffer(g_device, g_pipeSlots[0].uboBuffer, NULL);
        g_pipeSlots[0].uboBuffer = VK_NULL_HANDLE;
    }
fail_buffers:
    vkFreeDescriptorSets(g_device, g_descriptorPool, 1, &g_descriptorSet);
    vkDestroyDescriptorPool(g_device, g_descriptorPool, NULL);
    vkDestroyPipelineLayout(g_device, g_pipelineLayout, NULL);
    vkDestroyDescriptorSetLayout(g_device, g_descriptorSetLayout, NULL);
    g_descriptorSet = VK_NULL_HANDLE;
    g_descriptorPool = VK_NULL_HANDLE;
    g_pipelineLayout = VK_NULL_HANDLE;
    g_descriptorSetLayout = VK_NULL_HANDLE;
    if (!g_resources_logged) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan compute resources failed (buffers/setup)");
        g_resources_logged = 1;
    }
    return 0;
}

static void destroy_compute_resources(void) {
    gpu_pipeline_session_end_inner();
    unmap_host_buffers();
    for (uint32_t si = 0; si < GPU_PIPE_SLOT_COUNT; si++) {
        GpuPipeSlot *ps = &g_pipeSlots[si];
        if (ps->fence != VK_NULL_HANDLE) {
            vkDestroyFence(g_device, ps->fence, NULL);
            ps->fence = VK_NULL_HANDLE;
        }
    }
    if (g_commandPool != VK_NULL_HANDLE) {
        vkDestroyCommandPool(g_device, g_commandPool, NULL);
        g_commandPool = VK_NULL_HANDLE;
    }
    for (uint32_t si = 0; si < GPU_PIPE_SLOT_COUNT; si++) {
        GpuPipeSlot *ps = &g_pipeSlots[si];
        ps->commandBuffer = VK_NULL_HANDLE;
        if (ps->resultBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(g_device, ps->resultBuffer, NULL);
            ps->resultBuffer = VK_NULL_HANDLE;
        }
        if (ps->resultMemory != VK_NULL_HANDLE) {
            vkFreeMemory(g_device, ps->resultMemory, NULL);
            ps->resultMemory = VK_NULL_HANDLE;
        }
        if (ps->uboBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(g_device, ps->uboBuffer, NULL);
            ps->uboBuffer = VK_NULL_HANDLE;
        }
        if (ps->uboMemory != VK_NULL_HANDLE) {
            vkFreeMemory(g_device, ps->uboMemory, NULL);
            ps->uboMemory = VK_NULL_HANDLE;
        }
        ps->descriptorSet = VK_NULL_HANDLE;
        ps->inFlight = 0;
    }
    g_commandBuffer = VK_NULL_HANDLE;
    g_fence = VK_NULL_HANDLE;
    g_uboBuffer = VK_NULL_HANDLE;
    g_uboMemory = VK_NULL_HANDLE;
    g_resultBuffer = VK_NULL_HANDLE;
    g_resultMemory = VK_NULL_HANDLE;
    g_descriptorSet = VK_NULL_HANDLE;
    for (uint32_t i = 0; i < GPU_PIPELINE_SLOT_COUNT; i++) {
        if (g_pipelines[i] != VK_NULL_HANDLE) {
            vkDestroyPipeline(g_device, g_pipelines[i], NULL);
            g_pipelines[i] = VK_NULL_HANDLE;
        }
        if (g_pipelines_uvec4[i] != VK_NULL_HANDLE) {
            vkDestroyPipeline(g_device, g_pipelines_uvec4[i], NULL);
            g_pipelines_uvec4[i] = VK_NULL_HANDLE;
        }
        if (g_pipelines_uvec2[i] != VK_NULL_HANDLE) {
            vkDestroyPipeline(g_device, g_pipelines_uvec2[i], NULL);
            g_pipelines_uvec2[i] = VK_NULL_HANDLE;
        }
    }
    if (g_pipeline_selftest != VK_NULL_HANDLE) {
        vkDestroyPipeline(g_device, g_pipeline_selftest, NULL);
        g_pipeline_selftest = VK_NULL_HANDLE;
    }
    if (g_pipeline_selftest_uvec2 != VK_NULL_HANDLE) {
        vkDestroyPipeline(g_device, g_pipeline_selftest_uvec2, NULL);
        g_pipeline_selftest_uvec2 = VK_NULL_HANDLE;
    }
    if (g_pipeline_selftest_uvec4 != VK_NULL_HANDLE) {
        vkDestroyPipeline(g_device, g_pipeline_selftest_uvec4, NULL);
        g_pipeline_selftest_uvec4 = VK_NULL_HANDLE;
    }
    if (g_descriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(g_device, g_descriptorPool, NULL);
        g_descriptorPool = VK_NULL_HANDLE;
    }
    g_descriptorSet = VK_NULL_HANDLE;
    if (g_pipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(g_device, g_pipelineLayout, NULL);
        g_pipelineLayout = VK_NULL_HANDLE;
    }
    if (g_descriptorSetLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(g_device, g_descriptorSetLayout, NULL);
        g_descriptorSetLayout = VK_NULL_HANDLE;
    }
}

static int try_init_vulkan(void) {
    if (g_vulkan_available >= 0)
        return g_vulkan_available;

    detect_vulkan_runtime_env();
    g_vulkan_available = 0;

    VkApplicationInfo appInfo = {
        .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO,
        .pApplicationName = "BTC Miner",
        .applicationVersion = 1,
        .apiVersion = VK_API_VERSION_1_0,
    };

    VkInstanceCreateInfo instInfo = {
        .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO,
        .pApplicationInfo = &appInfo,
    };

    if (vkCreateInstance(&instInfo, NULL, &g_instance) != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "vkCreateInstance failed");
        return 0;
    }

    uint32_t devCount = 0;
    if (vkEnumeratePhysicalDevices(g_instance, &devCount, NULL) != VK_SUCCESS || devCount == 0) {
        vkDestroyInstance(g_instance, NULL);
        g_instance = VK_NULL_HANDLE;
        return 0;
    }

    VkPhysicalDevice *devices = (VkPhysicalDevice *)malloc(devCount * sizeof(VkPhysicalDevice));
    if (!devices) {
        vkDestroyInstance(g_instance, NULL);
        g_instance = VK_NULL_HANDLE;
        return 0;
    }
    if (vkEnumeratePhysicalDevices(g_instance, &devCount, devices) != VK_SUCCESS) {
        free(devices);
        vkDestroyInstance(g_instance, NULL);
        g_instance = VK_NULL_HANDLE;
        return 0;
    }

    g_physicalDevice = devices[0];
    free(devices);

    update_and_log_vulkan_limits();

    uint32_t queueCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(g_physicalDevice, &queueCount, NULL);
    if (queueCount == 0) {
        vkDestroyInstance(g_instance, NULL);
        g_instance = VK_NULL_HANDLE;
        g_physicalDevice = VK_NULL_HANDLE;
        return 0;
    }

    VkQueueFamilyProperties *qprops = (VkQueueFamilyProperties *)malloc(queueCount * sizeof(VkQueueFamilyProperties));
    if (!qprops) {
        vkDestroyInstance(g_instance, NULL);
        g_instance = VK_NULL_HANDLE;
        g_physicalDevice = VK_NULL_HANDLE;
        return 0;
    }
    vkGetPhysicalDeviceQueueFamilyProperties(g_physicalDevice, &queueCount, qprops);

    g_computeQueueFamily = UINT32_MAX;
    for (uint32_t i = 0; i < queueCount; i++) {
        if (qprops[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            g_computeQueueFamily = i;
            break;
        }
    }
    free(qprops);

    if (g_computeQueueFamily == UINT32_MAX) {
        vkDestroyInstance(g_instance, NULL);
        g_instance = VK_NULL_HANDLE;
        g_physicalDevice = VK_NULL_HANDLE;
        return 0;
    }

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = g_computeQueueFamily,
        .queueCount = 1,
        .pQueuePriorities = &priority,
    };

    VkDeviceCreateInfo devInfo = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO,
        .queueCreateInfoCount = 1,
        .pQueueCreateInfos = &queueInfo,
    };

    if (vkCreateDevice(g_physicalDevice, &devInfo, NULL, &g_device) != VK_SUCCESS) {
        vkDestroyInstance(g_instance, NULL);
        g_instance = VK_NULL_HANDLE;
        g_physicalDevice = VK_NULL_HANDLE;
        return 0;
    }

    vkGetDeviceQueue(g_device, g_computeQueueFamily, 0, &g_queue);
    g_vulkan_available = 1;
    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan init OK");
    return 1;
}

static void cleanup_vulkan(void) {
    if (g_device != VK_NULL_HANDLE) {
        VkResult r = vkDeviceWaitIdle(g_device);
        if (r != VK_SUCCESS && r != VK_ERROR_DEVICE_LOST) {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkDeviceWaitIdle returned %d", (int)r);
        }
        destroy_compute_resources();
        vkDestroyDevice(g_device, NULL);
        g_device = VK_NULL_HANDLE;
        g_queue = VK_NULL_HANDLE;
    }
    if (g_instance != VK_NULL_HANDLE) {
        vkDestroyInstance(g_instance, NULL);
        g_instance = VK_NULL_HANDLE;
        g_physicalDevice = VK_NULL_HANDLE;
    }
    g_vulkan_available = -1;
}

/* Write 4 bytes to dst as little-endian so GPU (LE) reads the same uint value as C (BE word) */
static void write_le32(uint8_t *dst, uint32_t val) {
    dst[0] = (uint8_t)(val);
    dst[1] = (uint8_t)(val >> 8);
    dst[2] = (uint8_t)(val >> 16);
    dst[3] = (uint8_t)(val >> 24);
}

static void fill_ubo_static(uint8_t *ubo, const uint8_t *header76, const uint8_t *target, int useMidstate,
                            int selftestWriteDigest, const uint32_t mid[8]) {
    memset(ubo, 0, UBO_SIZE);
    for (int i = 0; i < UBO_HEADER_WORDS; i++) {
        uint32_t w = (uint32_t)header76[i * 4] << 24 | (uint32_t)header76[i * 4 + 1] << 16 |
                     (uint32_t)header76[i * 4 + 2] << 8 | (uint32_t)header76[i * 4 + 3];
        write_le32(ubo + i * 4, w);
    }
    if (useMidstate) {
        for (int i = 0; i < 8; i++)
            write_le32(ubo + UBO_OFFSET_MIDSTATE + i * 4, mid[i]);
    }
    memcpy(ubo + UBO_OFFSET_TARGET, target, HASH_SIZE);
    write_le32(ubo + UBO_OFFSET_GPU_USE_MIDSTATE, useMidstate ? 1u : 0u);
    write_le32(ubo + UBO_OFFSET_GPU_SELFTEST, selftestWriteDigest ? 1u : 0u);
}

static void patch_ubo_nonce_range(uint8_t *ubo, uint32_t nonceStart, uint32_t nonceEnd) {
    write_le32(ubo + UBO_OFFSET_NONCE_START, nonceStart);
    write_le32(ubo + UBO_OFFSET_NONCE_END, nonceEnd);
}

static void fill_ubo_mining(uint8_t *ubo, const uint8_t *header76, uint32_t nonceStart, uint32_t nonceEnd,
                            const uint8_t *target, int useMidstate, int selftestWriteDigest, const uint32_t mid[8]) {
    fill_ubo_static(ubo, header76, target, useMidstate, selftestWriteDigest, mid);
    patch_ubo_nonce_range(ubo, nonceStart, nonceEnd);
}

static int pipe_init_static_ubo_slots(const uint8_t *header76, const uint8_t *target, const uint32_t mid[8],
                                      int useMidstate) {
    if (!map_host_buffers())
        return 0;
    uint8_t ubo[UBO_SIZE];
    fill_ubo_static(ubo, header76, target, useMidstate, 0, mid);
    for (uint32_t i = 0; i < GPU_PIPE_SLOT_COUNT; i++) {
        GpuPipeSlot *slot = &g_pipeSlots[i];
        if (slot->uboMapped == NULL)
            return 0;
        memcpy(slot->uboMapped, ubo, UBO_SIZE);
        host_flush_before_gpu_read(slot->uboMemory);
    }
    return 1;
}

static void sha256_words_to_digest_be(const uint32_t w[8], uint8_t out[32]) {
    for (int i = 0; i < 8; i++) {
        out[i * 4 + 0] = (uint8_t)(w[i] >> 24);
        out[i * 4 + 1] = (uint8_t)(w[i] >> 16);
        out[i * 4 + 2] = (uint8_t)(w[i] >> 8);
        out[i * 4 + 3] = (uint8_t)w[i];
    }
}

static void bytes32_to_hex(const uint8_t b[32], char out[65]) {
    static const char *const hex = "0123456789abcdef";
    for (int i = 0; i < 32; i++) {
        out[i * 2] = hex[b[i] >> 4];
        out[i * 2 + 1] = hex[b[i] & 0x0f];
    }
    out[64] = '\0';
}

typedef struct {
    uint32_t nonceStart;
    uint32_t nonceEnd;
    uint32_t groupCountX;
} GpuScanPass;

static void slot_prepare_chunk_nonces(GpuPipeSlot *slot, uint32_t nonceStart, uint32_t nonceEnd) {
    if (slot->uboMapped == NULL)
        return;
    patch_ubo_nonce_range((uint8_t *)slot->uboMapped, nonceStart, nonceEnd);
    host_flush_range(slot->uboMemory, UBO_OFFSET_NONCE_START, 8u);
    mining_result_buffer_reset(slot->resultMapped);
    host_flush_before_gpu_read(slot->resultMemory);
    slot->nonceStart = nonceStart;
    slot->nonceEnd = nonceEnd;
}

static int slot_read_result(GpuPipeSlot *slot, int *hit_out, uint32_t *nonce_out) {
    host_invalidate_after_gpu_write_while_mapped(slot->resultMemory);
    uint32_t *words = (uint32_t *)slot->resultMapped;
    uint32_t found = words[RES_WORD_FOUND];
    uint32_t win = words[RES_WORD_NONCE];
    if (found == 1u) {
        *hit_out = 1;
        *nonce_out = win;
    } else {
        *hit_out = 0;
        *nonce_out = 0u;
    }
    return 1;
}

static int slot_wait(GpuPipeSlot *slot) {
    if (slot->fence == VK_NULL_HANDLE)
        return 0;
    for (;;) {
        VkResult res = vkWaitForFences(g_device, 1, &slot->fence, VK_TRUE, 1000000000ull);
        if (res == VK_SUCCESS) {
            slot->inFlight = 0;
            return 1;
        }
        if (res == VK_TIMEOUT) {
            if (atomic_exchange_explicit(&g_interrupt_requested, 0, memory_order_acq_rel)) {
                __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "GPU scan interrupted by watchdog");
                return 0;
            }
            continue;
        }
        if (res == VK_ERROR_DEVICE_LOST) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkWaitForFences");
            cleanup_vulkan();
        } else {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkWaitForFences failed: %s (%d)", vk_result_str(res), (int)res);
        }
        return 0;
    }
}

static int slot_record_multipass(GpuPipeSlot *slot, VkPipeline pipeline, const GpuScanPass *passes, uint32_t passCount) {
    if (passCount == 0 || passes == NULL || slot == NULL)
        return 0;
    VkResult res = vkResetFences(g_device, 1, &slot->fence);
    if (res != VK_SUCCESS) {
        if (res == VK_ERROR_DEVICE_LOST) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkResetFences");
            cleanup_vulkan();
        } else {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkResetFences failed: %s (%d)", vk_result_str(res), (int)res);
        }
        return 0;
    }
    res = vkResetCommandBuffer(slot->commandBuffer, 0);
    if (res != VK_SUCCESS) {
        if (res == VK_ERROR_DEVICE_LOST) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkResetCommandBuffer");
            cleanup_vulkan();
        } else {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkResetCommandBuffer failed: %s (%d)", vk_result_str(res), (int)res);
        }
        return 0;
    }
    VkCommandBufferBeginInfo beginInfo = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    res = vkBeginCommandBuffer(slot->commandBuffer, &beginInfo);
    if (res != VK_SUCCESS) {
        if (res == VK_ERROR_DEVICE_LOST) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkBeginCommandBuffer");
            cleanup_vulkan();
        } else {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "vkBeginCommandBuffer failed: %s (%d)", vk_result_str(res), (int)res);
        }
        return 0;
    }
    vkCmdBindPipeline(slot->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    vkCmdBindDescriptorSets(slot->commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g_pipelineLayout, 0, 1,
        &slot->descriptorSet, 0, NULL);
    VkMemoryBarrier hostToCompute = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_HOST_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_UNIFORM_READ_BIT,
    };
    vkCmdPipelineBarrier(slot->commandBuffer, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1,
        &hostToCompute, 0, NULL, 0, NULL);
    for (uint32_t i = 0; i < passCount; i++) {
        if (i > 0) {
            uint8_t nonceData[8];
            write_le32(nonceData, passes[i].nonceStart);
            write_le32(nonceData + 4, passes[i].nonceEnd);
            vkCmdUpdateBuffer(slot->commandBuffer, slot->uboBuffer, UBO_OFFSET_NONCE_START, 8, nonceData);
            VkMemoryBarrier transferToCompute = {
                .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
                .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_UNIFORM_READ_BIT,
            };
            vkCmdPipelineBarrier(slot->commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, 1, &transferToCompute, 0, NULL, 0, NULL);
        }
        vkCmdDispatch(slot->commandBuffer, passes[i].groupCountX, 1u, 1u);
        if (i + 1u < passCount) {
            VkMemoryBarrier betweenPasses = {
                .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
                .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT,
            };
            vkCmdPipelineBarrier(slot->commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &betweenPasses, 0, NULL, 0, NULL);
        }
    }
    VkMemoryBarrier computeToHost = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
    };
    vkCmdPipelineBarrier(slot->commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1,
        &computeToHost, 0, NULL, 0, NULL);
    res = vkEndCommandBuffer(slot->commandBuffer);
    if (res != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkEndCommandBuffer failed: %s (%d)", vk_result_str(res), (int)res);
        return 0;
    }
    return 1;
}

static int slot_submit(GpuPipeSlot *slot) {
    VkSubmitInfo submitInfo = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount = 1,
        .pCommandBuffers = &slot->commandBuffer,
    };
    if (g_first_dispatch_state == 0) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "First GPU dispatch submitted");
        g_first_dispatch_state = 1;
    }
    VkResult res = vkQueueSubmit(g_queue, 1, &submitInfo, slot->fence);
    if (res != VK_SUCCESS) {
        if (g_first_dispatch_state < 2) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "First GPU dispatch failed (queue submit or wait)");
            g_first_dispatch_state = 2;
        }
        if (res == VK_ERROR_DEVICE_LOST) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkQueueSubmit");
            cleanup_vulkan();
        } else {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkQueueSubmit failed: %s (%d)", vk_result_str(res), (int)res);
        }
        return 0;
    }
    slot->inFlight = 1;
    return 1;
}

static int build_chunk_passes(uint32_t nonceStart, uint32_t nonceEnd, uint32_t localSize, uint32_t noncesPerThread,
                              GpuScanPass *passes, uint32_t passCap, uint32_t *passCountOut, int *legacyOut) {
    uint64_t chunkInv = (uint64_t)nonceEnd - (uint64_t)nonceStart + 1ULL;
    uint64_t maxInvPerPass = (uint64_t)g_maxWorkGroupCount * (uint64_t)localSize * (uint64_t)noncesPerThread;
    if (maxInvPerPass == 0)
        return GPU_UNAVAILABLE;
    uint32_t passCount = 0;
    int useLegacyMultipass = 0;
    for (uint32_t cursor = nonceStart; cursor <= nonceEnd;) {
        uint64_t remain = (uint64_t)nonceEnd - (uint64_t)cursor + 1ULL;
        uint32_t thisInv = remain > maxInvPerPass ? (uint32_t)maxInvPerPass : (uint32_t)remain;
        uint32_t subEnd = (uint32_t)((uint64_t)cursor + (uint64_t)thisInv - 1ULL);
        uint64_t invocations = (thisInv + noncesPerThread - 1u) / noncesPerThread;
        uint32_t groupCountX = (uint32_t)((invocations + localSize - 1u) / localSize);
        if (groupCountX > g_maxWorkGroupCount)
            groupCountX = g_maxWorkGroupCount;
        if (groupCountX == 0)
            return GPU_UNAVAILABLE;
        if (passCount >= passCap) {
            useLegacyMultipass = 1;
            break;
        }
        passes[passCount].nonceStart = cursor;
        passes[passCount].nonceEnd = subEnd;
        passes[passCount].groupCountX = groupCountX;
        passCount++;
        if (subEnd >= nonceEnd)
            break;
        cursor = subEnd + 1u;
    }
    if (legacyOut)
        *legacyOut = useLegacyMultipass;
    if (passCountOut)
        *passCountOut = passCount;
    (void)chunkInv;
    return 0;
}

static void pipe_write_jni_out(int hit, uint32_t nonce, int statusMissOrHit, jlong *out) {
    if (hit) {
        out[0] = (jlong)GPU_JNI_STATUS_HIT;
        out[1] = (jlong)(uint32_t)nonce;
    } else {
        out[0] = (jlong)statusMissOrHit;
        out[1] = 0;
    }
}

static int pipe_drain_inflight(int *hit_out, uint32_t *nonce_out) {
    if (g_pipeSession.inFlightIndex < 0)
        return 0;
    GpuPipeSlot *slot = &g_pipeSlots[g_pipeSession.inFlightIndex];
    if (!slot_wait(slot))
        return GPU_UNAVAILABLE;
    slot_read_result(slot, hit_out, nonce_out);
    g_pipeSession.inFlightIndex = -1;
    return 1;
}

static void gpu_pipeline_session_end_inner(void) {
    if (!g_pipeSession.active)
        return;
    int hit = 0;
    uint32_t nonce = 0u;
    while (g_pipeSession.inFlightIndex >= 0) {
        if (pipe_drain_inflight(&hit, &nonce) == GPU_UNAVAILABLE)
            break;
    }
    memset(&g_pipeSession, 0, sizeof(g_pipeSession));
    g_pipeSession.inFlightIndex = -1;
}

static int gpu_pipeline_session_begin_inner(const uint8_t *header76, const uint8_t *target, int localSizeX,
                                            int hashesPerThread, int gpuSha256Mode) {
    if (!GPU_PIPE_ENABLED)
        return 0;
    gpu_pipeline_session_end_inner();
    int useMidstate = gpu_mode_uses_midstate(gpuSha256Mode);
    GpuShaderVariant variant = gpu_mode_shader_variant(gpuSha256Mode);
    uint32_t vectorWidth = gpu_vector_width(gpuSha256Mode);
    uint32_t localSize = normalize_local_size_x(localSizeX);
    uint32_t hpt = normalize_hashes_per_thread(hashesPerThread);
    if (g_device == VK_NULL_HANDLE || g_queue == VK_NULL_HANDLE)
        return GPU_UNAVAILABLE;
    if (!ensure_compute_resources())
        return GPU_UNAVAILABLE;
    if (!ensure_mining_pipeline(localSize, hpt, variant))
        return GPU_UNAVAILABLE;
    uint32_t pipeSlot = pipeline_slot(localSize, hpt);
    VkPipeline miningPipe = *mining_pipeline_slot(variant, pipeSlot);
    if (pipeSlot >= GPU_PIPELINE_SLOT_COUNT || miningPipe == VK_NULL_HANDLE)
        return GPU_UNAVAILABLE;
    memset(&g_pipeSession, 0, sizeof(g_pipeSession));
    memcpy(g_pipeSession.header76, header76, HEADER_PREFIX_SIZE);
    memcpy(g_pipeSession.target, target, HASH_SIZE);
    if (useMidstate)
        btc_midstate_header76(header76, g_pipeSession.mid);
    g_pipeSession.useMidstate = useMidstate;
    g_pipeSession.gpuSha256Mode = gpuSha256Mode;
    g_pipeSession.miningPipe = miningPipe;
    g_pipeSession.localSize = localSize;
    g_pipeSession.hpt = hpt;
    g_pipeSession.noncesPerThread = vectorWidth * hpt;
    g_pipeSession.writeIndex = 0;
    g_pipeSession.inFlightIndex = -1;
    g_pipeSession.staticUboReady = 0;
    g_pipeSession.active = 1;
    if (!pipe_init_static_ubo_slots(header76, target, g_pipeSession.mid, useMidstate))
        return GPU_UNAVAILABLE;
    g_pipeSession.staticUboReady = 1;
    atomic_store_explicit(&g_interrupt_requested, 0, memory_order_relaxed);
    if (!g_workgroup_size_logged) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "GPU local_size_x=%u hashesPerThread=%u vectorWidth=%u",
            (unsigned)localSize, (unsigned)hpt, (unsigned)vectorWidth);
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan maxComputeWorkGroupCount[0]=%u", (unsigned)g_maxWorkGroupCount);
        g_workgroup_size_logged = 1;
    }
    return 0;
}

static int gpu_pipeline_flush_inner(jlong *out, int maxPending) {
    if (!g_pipeSession.active || maxPending <= 0)
        return 0;
    int hit = 0;
    uint32_t nonce = 0u;
    int drained = 0;
    if (g_pipeSession.syncPending && drained < maxPending) {
        hit = g_pipeSession.syncPendingHit;
        nonce = g_pipeSession.syncPendingNonce;
        g_pipeSession.syncPending = 0;
        if (out)
            pipe_write_jni_out(hit, nonce, GPU_JNI_STATUS_MISS, out);
        return 1;
    }
    if (g_pipeSession.inFlightIndex >= 0 && drained < maxPending) {
        int dr = pipe_drain_inflight(&hit, &nonce);
        if (dr == GPU_UNAVAILABLE) {
            if (out) {
                out[0] = (jlong)GPU_JNI_STATUS_UNAVAILABLE;
                out[1] = 0;
            }
            return -1;
        }
        if (dr > 0) {
            if (out)
                pipe_write_jni_out(hit, nonce, GPU_JNI_STATUS_MISS, out);
            drained = 1;
        }
    }
    return drained;
}

static int run_gpu_scan_pipelined(uint32_t nonceStart, uint32_t nonceEnd, int *hit_out, uint32_t *nonce_out) {
    GpuPipeSession *sess = &g_pipeSession;
    int completedHit = 0;
    uint32_t completedNonce = 0u;
    int haveCompleted = 0;

    if (sess->syncPending) {
        completedHit = sess->syncPendingHit;
        completedNonce = sess->syncPendingNonce;
        sess->syncPending = 0;
        haveCompleted = 1;
    } else if (sess->inFlightIndex >= 0) {
        int dr = pipe_drain_inflight(&completedHit, &completedNonce);
        if (dr == GPU_UNAVAILABLE)
            return GPU_UNAVAILABLE;
        if (dr > 0)
            haveCompleted = 1;
    }

    if (haveCompleted) {
        *hit_out = completedHit;
        *nonce_out = completedNonce;
    } else {
        *hit_out = 0;
        *nonce_out = 0u;
    }

    GpuScanPass passes[GPU_MAX_MULTIPASS_PASSES];
    uint32_t passCount = 0;
    int legacy = 0;
    int br = build_chunk_passes(nonceStart, nonceEnd, sess->localSize, sess->noncesPerThread, passes,
        GPU_MAX_MULTIPASS_PASSES, &passCount, &legacy);
    if (br == GPU_UNAVAILABLE)
        return GPU_UNAVAILABLE;

    if (legacy || passCount == 0) {
        int syncHit = 0;
        uint32_t syncNonce = 0u;
        int rr = run_gpu_scan(sess->header76, nonceStart, nonceEnd, sess->target, (int)sess->localSize, (int)sess->hpt,
            sess->gpuSha256Mode, &syncHit, &syncNonce);
        if (rr == GPU_UNAVAILABLE)
            return GPU_UNAVAILABLE;
        sess->syncPending = 1;
        sess->syncPendingHit = syncHit;
        sess->syncPendingNonce = syncNonce;
        sess->inFlightIndex = -1;
        return 0;
    }

    static int s_gpu_multipass_notice;
    uint64_t chunkInv = (uint64_t)nonceEnd - (uint64_t)nonceStart + 1ULL;
    uint64_t maxInvPerPass =
        (uint64_t)g_maxWorkGroupCount * (uint64_t)sess->localSize * (uint64_t)sess->noncesPerThread;
    if (passCount > 1 && chunkInv > maxInvPerPass && !s_gpu_multipass_notice) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
            "GPU scan: %u dispatches in one submit (chunkNonces=%llu maxNoncesPerPass=%llu maxGroups=%u localSize=%u noncesPerThread=%u)",
            (unsigned)passCount, (unsigned long long)chunkInv, (unsigned long long)maxInvPerPass,
            (unsigned)g_maxWorkGroupCount, (unsigned)sess->localSize, (unsigned)sess->noncesPerThread);
        s_gpu_multipass_notice = 1;
    }

    if (!sess->staticUboReady)
        return GPU_UNAVAILABLE;
    GpuPipeSlot *slot = &g_pipeSlots[sess->writeIndex];
    slot_prepare_chunk_nonces(slot, nonceStart, nonceEnd);
    if (!slot_record_multipass(slot, sess->miningPipe, passes, passCount))
        return GPU_UNAVAILABLE;
    if (!slot_submit(slot))
        return GPU_UNAVAILABLE;
    sess->inFlightIndex = sess->writeIndex;
    sess->writeIndex ^= 1;
    return 0;
}

static int submit_once_and_wait(VkPipeline pipeline, uint32_t groupX, uint32_t groupY, uint32_t groupZ) {
    VkResult res = vkResetFences(g_device, 1, &g_fence);
    if (res != VK_SUCCESS) {
        if (res == VK_ERROR_DEVICE_LOST) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkResetFences");
            cleanup_vulkan();
        } else {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkResetFences failed: %s (%d)", vk_result_str(res), (int)res);
        }
        return 0;
    }
    res = vkResetCommandPool(g_device, g_commandPool, 0);
    if (res != VK_SUCCESS) {
        if (res == VK_ERROR_DEVICE_LOST) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkResetCommandPool");
            cleanup_vulkan();
        } else {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkResetCommandPool failed: %s (%d)", vk_result_str(res), (int)res);
        }
        return 0;
    }
    VkCommandBufferBeginInfo beginInfo = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    res = vkBeginCommandBuffer(g_commandBuffer, &beginInfo);
    if (res != VK_SUCCESS) {
        if (res == VK_ERROR_DEVICE_LOST) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkBeginCommandBuffer");
            cleanup_vulkan();
        } else {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "vkBeginCommandBuffer failed: %s (%d)", vk_result_str(res), (int)res);
        }
        return 0;
    }
    vkCmdBindPipeline(g_commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    vkCmdBindDescriptorSets(g_commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g_pipelineLayout, 0, 1, &g_descriptorSet, 0, NULL);
    vkCmdDispatch(g_commandBuffer, groupX, groupY, groupZ);
    VkMemoryBarrier barrier = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
    };
    vkCmdPipelineBarrier(g_commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &barrier,
        0, NULL, 0, NULL);
    res = vkEndCommandBuffer(g_commandBuffer);
    if (res != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkEndCommandBuffer failed: %s (%d)", vk_result_str(res), (int)res);
        return 0;
    }
    VkSubmitInfo submitInfo = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount = 1,
        .pCommandBuffers = &g_commandBuffer,
    };
    if (g_first_dispatch_state == 0) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "First GPU dispatch submitted");
        g_first_dispatch_state = 1;
    }
    res = vkQueueSubmit(g_queue, 1, &submitInfo, g_fence);
    if (res != VK_SUCCESS) {
        if (g_first_dispatch_state < 2) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "First GPU dispatch failed (queue submit or wait)");
            g_first_dispatch_state = 2;
        }
        if (res == VK_ERROR_DEVICE_LOST) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkQueueSubmit");
            cleanup_vulkan();
        } else {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkQueueSubmit failed: %s (%d)", vk_result_str(res), (int)res);
        }
        return 0;
    }
    for (;;) {
        res = vkWaitForFences(g_device, 1, &g_fence, VK_TRUE, 1000000000ull);
        if (res == VK_SUCCESS)
            break;
        if (res == VK_TIMEOUT) {
            if (atomic_exchange_explicit(&g_interrupt_requested, 0, memory_order_acq_rel)) {
                __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "GPU scan interrupted by watchdog");
                return 0;
            }
            continue;
        }
        if (g_first_dispatch_state < 2) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "First GPU dispatch failed (queue submit or wait)");
            g_first_dispatch_state = 2;
        }
        if (res == VK_ERROR_DEVICE_LOST) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkWaitForFences");
            cleanup_vulkan();
        } else {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkWaitForFences failed: %s (%d)", vk_result_str(res), (int)res);
        }
        return 0;
    }
    if (g_first_dispatch_state == 1) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "First GPU dispatch completed");
        g_first_dispatch_state = 2;
    }
    return 1;
}

/** Record passCount dispatches in one command buffer; one vkQueueSubmit + fence wait. Pass 0 UBO must be host-written before call. */
static int submit_multipass_and_wait(VkPipeline pipeline, const GpuScanPass *passes, uint32_t passCount) {
    if (passCount == 0 || passes == NULL)
        return 0;
    VkResult res = vkResetFences(g_device, 1, &g_fence);
    if (res != VK_SUCCESS) {
        if (res == VK_ERROR_DEVICE_LOST) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkResetFences");
            cleanup_vulkan();
        } else {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkResetFences failed: %s (%d)", vk_result_str(res), (int)res);
        }
        return 0;
    }
    res = vkResetCommandPool(g_device, g_commandPool, 0);
    if (res != VK_SUCCESS) {
        if (res == VK_ERROR_DEVICE_LOST) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkResetCommandPool");
            cleanup_vulkan();
        } else {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkResetCommandPool failed: %s (%d)", vk_result_str(res), (int)res);
        }
        return 0;
    }
    VkCommandBufferBeginInfo beginInfo = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
    };
    res = vkBeginCommandBuffer(g_commandBuffer, &beginInfo);
    if (res != VK_SUCCESS) {
        if (res == VK_ERROR_DEVICE_LOST) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkBeginCommandBuffer");
            cleanup_vulkan();
        } else {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "vkBeginCommandBuffer failed: %s (%d)", vk_result_str(res), (int)res);
        }
        return 0;
    }
    vkCmdBindPipeline(g_commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    vkCmdBindDescriptorSets(g_commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g_pipelineLayout, 0, 1, &g_descriptorSet, 0, NULL);

    VkMemoryBarrier hostToCompute = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_HOST_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_UNIFORM_READ_BIT,
    };
    vkCmdPipelineBarrier(g_commandBuffer, VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &hostToCompute,
        0, NULL, 0, NULL);

    for (uint32_t i = 0; i < passCount; i++) {
        if (i > 0) {
            uint8_t nonceData[8];
            write_le32(nonceData, passes[i].nonceStart);
            write_le32(nonceData + 4, passes[i].nonceEnd);
            vkCmdUpdateBuffer(g_commandBuffer, g_uboBuffer, UBO_OFFSET_NONCE_START, 8, nonceData);
            VkMemoryBarrier transferToCompute = {
                .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
                .srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_UNIFORM_READ_BIT,
            };
            vkCmdPipelineBarrier(g_commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                1, &transferToCompute, 0, NULL, 0, NULL);
        }
        vkCmdDispatch(g_commandBuffer, passes[i].groupCountX, 1u, 1u);
        if (i + 1u < passCount) {
            VkMemoryBarrier betweenPasses = {
                .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
                .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
                .dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_SHADER_WRITE_BIT,
            };
            vkCmdPipelineBarrier(g_commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &betweenPasses, 0, NULL, 0, NULL);
        }
    }

    VkMemoryBarrier computeToHost = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER,
        .srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT,
        .dstAccessMask = VK_ACCESS_HOST_READ_BIT,
    };
    vkCmdPipelineBarrier(g_commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &computeToHost,
        0, NULL, 0, NULL);
    res = vkEndCommandBuffer(g_commandBuffer);
    if (res != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkEndCommandBuffer failed: %s (%d)", vk_result_str(res), (int)res);
        return 0;
    }
    VkSubmitInfo submitInfo = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount = 1,
        .pCommandBuffers = &g_commandBuffer,
    };
    if (g_first_dispatch_state == 0) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "First GPU dispatch submitted");
        g_first_dispatch_state = 1;
    }
    res = vkQueueSubmit(g_queue, 1, &submitInfo, g_fence);
    if (res != VK_SUCCESS) {
        if (g_first_dispatch_state < 2) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "First GPU dispatch failed (queue submit or wait)");
            g_first_dispatch_state = 2;
        }
        if (res == VK_ERROR_DEVICE_LOST) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkQueueSubmit");
            cleanup_vulkan();
        } else {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkQueueSubmit failed: %s (%d)", vk_result_str(res), (int)res);
        }
        return 0;
    }
    for (;;) {
        res = vkWaitForFences(g_device, 1, &g_fence, VK_TRUE, 1000000000ull);
        if (res == VK_SUCCESS)
            break;
        if (res == VK_TIMEOUT) {
            if (atomic_exchange_explicit(&g_interrupt_requested, 0, memory_order_acq_rel)) {
                __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "GPU scan interrupted by watchdog");
                return 0;
            }
            continue;
        }
        if (g_first_dispatch_state < 2) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "First GPU dispatch failed (queue submit or wait)");
            g_first_dispatch_state = 2;
        }
        if (res == VK_ERROR_DEVICE_LOST) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkWaitForFences");
            cleanup_vulkan();
        } else {
            __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkWaitForFences failed: %s (%d)", vk_result_str(res), (int)res);
        }
        return 0;
    }
    if (g_first_dispatch_state == 1) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "First GPU dispatch completed");
        g_first_dispatch_state = 2;
    }
    return 1;
}

/** Vulkan SSBO readback self-test: nonce=1, digest write path; logs GPU_SELFTEST_TAG. Returns 1 if ok. */
static int gpu_sha_vulkan_selftest_inner(int gpuSha256Mode) {
    int useMidstate = gpu_mode_uses_midstate(gpuSha256Mode);
    GpuShaderVariant variant = gpu_mode_shader_variant(gpuSha256Mode);
    if (g_device == VK_NULL_HANDLE || g_queue == VK_NULL_HANDLE)
        return 0;
    if (!ensure_compute_resources()) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "vulkan_selftest: ensure_compute_resources failed");
        return 0;
    }
    if (!ensure_selftest_pipeline(variant)) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "vulkan_selftest: self-test pipeline failed");
        return 0;
    }
    if (g_uboMapped == NULL || g_resultMapped == NULL)
        return 0;
    const uint8_t *h76 = btc_gpu_selftest_header76();
    uint32_t mid[8] = {0};
    if (useMidstate)
        btc_midstate_header76(h76, mid);
    uint8_t target[HASH_SIZE];
    memset(target, 0, sizeof(target));
    uint8_t ubo[UBO_SIZE];
    fill_ubo_mining(ubo, h76, 1u, 1u, target, useMidstate, 1, mid);

    memcpy(g_uboMapped, ubo, UBO_SIZE);
    host_flush_before_gpu_read(g_uboMemory);

    memset(g_resultMapped, 0, RESULT_BUFFER_SIZE);
    host_flush_before_gpu_read(g_resultMemory);

    if (!submit_once_and_wait(*selftest_pipeline_slot(variant), 1u, 1u, 1u))
        return 0;

    host_invalidate_after_gpu_write_while_mapped(g_resultMemory);
    uint32_t *words = (uint32_t *)g_resultMapped;
    uint32_t found = words[RES_WORD_FOUND];
    uint32_t sent_nonce = words[RES_WORD_NONCE];
    uint32_t gw_first[8], gw_final[8];
    memcpy(gw_first, words + RES_WORD_FIRST_HASH, sizeof(gw_first));
    memcpy(gw_final, words + RES_WORD_FIRST_HASH + 8u, sizeof(gw_final));

    uint8_t ref_first[32], ref_final[32];
    if (useMidstate) {
        btc_first_sha_from_mid(h76, mid, 1u, ref_first);
        btc_double_sha_from_mid(h76, mid, 1u, ref_final);
    } else {
        btc_first_sha_full(h76, 1u, ref_first);
        btc_double_sha_full(h76, 1u, ref_final);
    }
    uint8_t g_first[32], g_final[32];
    sha256_words_to_digest_be(gw_first, g_first);
    sha256_words_to_digest_be(gw_final, g_final);
    int same_first = (memcmp(ref_first, g_first, 32) == 0);
    int same_final = (memcmp(ref_final, g_final, 32) == 0);

    char ref_f_hex[65], ref_l_hex[65], g_f_hex[65], g_l_hex[65];
    bytes32_to_hex(ref_first, ref_f_hex);
    bytes32_to_hex(ref_final, ref_l_hex);
    bytes32_to_hex(g_first, g_f_hex);
    bytes32_to_hex(g_final, g_l_hex);

    char mid_line[128];
    if (useMidstate) {
        snprintf(mid_line, sizeof(mid_line), "%08x:%08x:%08x:%08x:%08x:%08x:%08x:%08x", mid[0], mid[1], mid[2], mid[3],
            mid[4], mid[5], mid[6], mid[7]);
    } else {
        snprintf(mid_line, sizeof(mid_line), "n/a");
    }

    const char *modeLabel;
    switch (gpuSha256Mode) {
        case GPU_MODE_UVEC4_MIDSTATE:
            modeLabel = "GPU_Uvec4_Midstate";
            break;
        case GPU_MODE_UVEC2_MIDSTATE:
            modeLabel = "GPU_Uvec2_Midstate";
            break;
        case GPU_MODE_MIDSTATE:
            modeLabel = "GPU_Midstate";
            break;
        default:
            modeLabel = "GPU_Full";
            break;
    }
    __android_log_print(ANDROID_LOG_INFO, GPU_SELFTEST_TAG,
        "vulkan_readback mode=%s midstate=%s cpu_first=%s cpu_final=%s gpu_first=%s gpu_final=%s same_first=%d same_final=%d resultFound=%08x winningNonce=%08x",
        modeLabel, mid_line, ref_f_hex, ref_l_hex, g_f_hex, g_l_hex, same_first, same_final,
        (unsigned)found, (unsigned)sent_nonce);

    if (useMidstate && same_first) {
        uint8_t ref_mid_first[32];
        btc_first_sha_from_mid(h76, mid, 1u, ref_mid_first);
        int midpath = (memcmp(ref_mid_first, g_first, 32) == 0);
        __android_log_print(ANDROID_LOG_INFO, GPU_SELFTEST_TAG, "vulkan_readback cpu_midstate_first_matches_gpu_first=%d", midpath);
    }

    return (same_first && same_final && found == RES_SELFTEST_FOUND_MAGIC) ? 1 : 0;
}

/* Returns GPU_UNAVAILABLE on failure; else 0. Sets *hit_out 0/1; if 1, *nonce_out is the winning nonce (may be 0xFFFFFFFFu). */
static int run_gpu_scan(const uint8_t *header76, uint32_t nonceStart, uint32_t nonceEnd,
                        const uint8_t *target, int localSizeX, int hashesPerThread, int gpuSha256Mode,
                        int *hit_out, uint32_t *nonce_out) {
    int useMidstate = gpu_mode_uses_midstate(gpuSha256Mode);
    GpuShaderVariant variant = gpu_mode_shader_variant(gpuSha256Mode);
    uint32_t vectorWidth = gpu_vector_width(gpuSha256Mode);
    uint32_t localSize = normalize_local_size_x(localSizeX);
    uint32_t hpt = normalize_hashes_per_thread(hashesPerThread);
    uint32_t noncesPerThread = vectorWidth * hpt;
    uint32_t slot = pipeline_slot(localSize, hpt);
    /* Defensive: ensure core Vulkan handles are valid before proceeding. */
    if (g_device == VK_NULL_HANDLE || g_queue == VK_NULL_HANDLE) {
        return GPU_UNAVAILABLE;
    }
    if (!ensure_compute_resources()) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "run_gpu_scan: ensure_compute_resources failed");
        return GPU_UNAVAILABLE;
    }
    if (g_commandBuffer == VK_NULL_HANDLE || g_fence == VK_NULL_HANDLE) {
        return GPU_UNAVAILABLE;
    }
    if (!ensure_mining_pipeline(localSize, hpt, variant))
        return GPU_UNAVAILABLE;
    VkPipeline miningPipe = *mining_pipeline_slot(variant, slot);
    if (slot >= GPU_PIPELINE_SLOT_COUNT || miningPipe == VK_NULL_HANDLE) {
        return GPU_UNAVAILABLE;
    }
    if (g_uboMemory == VK_NULL_HANDLE || g_resultMemory == VK_NULL_HANDLE ||
        g_descriptorSet == VK_NULL_HANDLE || g_pipelineLayout == VK_NULL_HANDLE) {
        return GPU_UNAVAILABLE;
    }
    if (g_uboMapped == NULL || g_resultMapped == NULL)
        return GPU_UNAVAILABLE;
    *hit_out = 0;
    *nonce_out = 0u;
    atomic_store_explicit(&g_interrupt_requested, 0, memory_order_relaxed);

    if (!g_workgroup_size_logged) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "GPU local_size_x=%u hashesPerThread=%u vectorWidth=%u",
            (unsigned)localSize, (unsigned)hpt, (unsigned)vectorWidth);
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan maxComputeWorkGroupCount[0]=%u", (unsigned)g_maxWorkGroupCount);
        g_workgroup_size_logged = 1;
    }

    uint32_t mid[8] = {0};
    if (useMidstate)
        btc_midstate_header76(header76, mid);
    uint8_t ubo[UBO_SIZE];

    /* One vkCmdDispatch is limited to maxComputeWorkGroupCount[0] groups. Without looping, part of a
     * large Java "chunk" would never be scanned while Kotlin still credits the full chunk — misses
     * shares and skews hashrate. Cover [nonceStart, nonceEnd] in one or more sub-ranges. */
    static int s_gpu_multipass_notice;
    uint64_t chunkInv = (uint64_t)nonceEnd - (uint64_t)nonceStart + 1ULL;
    uint64_t maxInvPerPass = (uint64_t)g_maxWorkGroupCount * (uint64_t)localSize * (uint64_t)noncesPerThread;
    if (maxInvPerPass == 0)
        return GPU_UNAVAILABLE;

    GpuScanPass passes[GPU_MAX_MULTIPASS_PASSES];
    uint32_t passCount = 0;
    int useLegacyMultipass = 0;
    for (uint32_t cursor = nonceStart; cursor <= nonceEnd;) {
        uint64_t remain = (uint64_t)nonceEnd - (uint64_t)cursor + 1ULL;
        uint32_t thisInv = remain > maxInvPerPass ? (uint32_t)maxInvPerPass : (uint32_t)remain;
        uint32_t subEnd = (uint32_t)((uint64_t)cursor + (uint64_t)thisInv - 1ULL);
        uint64_t invocations = (thisInv + noncesPerThread - 1u) / noncesPerThread;
        uint32_t groupCountX = (uint32_t)((invocations + localSize - 1u) / localSize);
        if (groupCountX > g_maxWorkGroupCount)
            groupCountX = g_maxWorkGroupCount;
        if (groupCountX == 0)
            return GPU_UNAVAILABLE;
        if (passCount >= GPU_MAX_MULTIPASS_PASSES) {
            useLegacyMultipass = 1;
            break;
        }
        passes[passCount].nonceStart = cursor;
        passes[passCount].nonceEnd = subEnd;
        passes[passCount].groupCountX = groupCountX;
        passCount++;
        if (subEnd >= nonceEnd)
            break;
        cursor = subEnd + 1u;
    }

    if (useLegacyMultipass) {
        for (uint32_t cursor = nonceStart; cursor <= nonceEnd;) {
            uint64_t remain = (uint64_t)nonceEnd - (uint64_t)cursor + 1ULL;
            uint32_t thisInv = remain > maxInvPerPass ? (uint32_t)maxInvPerPass : (uint32_t)remain;
            uint32_t subEnd = (uint32_t)((uint64_t)cursor + (uint64_t)thisInv - 1ULL);
            uint64_t invocations = (thisInv + noncesPerThread - 1u) / noncesPerThread;
            uint32_t groupCountX = (uint32_t)((invocations + localSize - 1u) / localSize);
            if (groupCountX > g_maxWorkGroupCount)
                groupCountX = g_maxWorkGroupCount;
            if (groupCountX == 0)
                return GPU_UNAVAILABLE;

            fill_ubo_mining(ubo, header76, cursor, subEnd, target, useMidstate, 0, mid);
            memcpy(g_uboMapped, ubo, UBO_SIZE);
            host_flush_before_gpu_read(g_uboMemory);
            mining_result_buffer_reset(g_resultMapped);
            host_flush_before_gpu_read(g_resultMemory);

            if (!submit_once_and_wait(miningPipe, groupCountX, 1u, 1u))
                return GPU_UNAVAILABLE;

            host_invalidate_after_gpu_write_while_mapped(g_resultMemory);
            {
                uint32_t *words = (uint32_t *)g_resultMapped;
                uint32_t found = words[RES_WORD_FOUND];
                uint32_t win = words[RES_WORD_NONCE];
                if (found == 1u) {
                    *hit_out = 1;
                    *nonce_out = win;
                    return 0;
                }
            }
            if (subEnd >= nonceEnd)
                break;
            cursor = subEnd + 1u;
        }
        return 0;
    }

    if (passCount > 1 && chunkInv > maxInvPerPass && !s_gpu_multipass_notice) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
            "GPU scan: %u dispatches in one submit (chunkNonces=%llu maxNoncesPerPass=%llu maxGroups=%u localSize=%u noncesPerThread=%u vectorWidth=%u)",
            (unsigned)passCount, (unsigned long long)chunkInv, (unsigned long long)maxInvPerPass, (unsigned)g_maxWorkGroupCount,
            (unsigned)localSize, (unsigned)noncesPerThread, (unsigned)vectorWidth);
        s_gpu_multipass_notice = 1;
    }

    fill_ubo_mining(ubo, header76, passes[0].nonceStart, passes[0].nonceEnd, target, useMidstate, 0, mid);
    memcpy(g_uboMapped, ubo, UBO_SIZE);
    host_flush_before_gpu_read(g_uboMemory);
    mining_result_buffer_reset(g_resultMapped);
    host_flush_before_gpu_read(g_resultMemory);

    if (passCount == 1) {
        if (!submit_once_and_wait(miningPipe, passes[0].groupCountX, 1u, 1u))
            return GPU_UNAVAILABLE;
    } else {
        if (!submit_multipass_and_wait(miningPipe, passes, passCount))
            return GPU_UNAVAILABLE;
    }

    host_invalidate_after_gpu_write_while_mapped(g_resultMemory);
    {
        uint32_t *words = (uint32_t *)g_resultMapped;
        uint32_t found = words[RES_WORD_FOUND];
        uint32_t win = words[RES_WORD_NONCE];
        if (found == 1u) {
            *hit_out = 1;
            *nonce_out = win;
            return 0;
        }
    }
    return 0; /* Chunk scanned, no solution */
}
#endif

JNIEXPORT void JNICALL
Java_com_btcminer_android_mining_NativeMiner_gpuRequestInterrupt(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
#ifdef __ANDROID__
    atomic_store_explicit(&g_interrupt_requested, 1, memory_order_release);
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_btcminer_android_mining_NativeMiner_gpuIsAvailable(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
#ifdef __ANDROID__
    return try_init_vulkan() ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jint JNICALL
Java_com_btcminer_android_mining_NativeMiner_getVulkanRuntimeEnv(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
#ifdef __ANDROID__
    return (jint)detect_vulkan_runtime_env();
#else
    return -1;
#endif
}

JNIEXPORT jint JNICALL
Java_com_btcminer_android_mining_NativeMiner_getMaxComputeWorkGroupSize(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
#ifdef __ANDROID__
    if (!try_init_vulkan())
        return 0;
    return (jint)g_maxWorkGroupSize;
#else
    return 0;
#endif
}

JNIEXPORT jint JNICALL
Java_com_btcminer_android_mining_NativeMiner_getMaxGpuLocalSizeX(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
#ifdef __ANDROID__
    if (!try_init_vulkan())
        return 0;
    return (jint)effective_max_local_size_x();
#else
    return 0;
#endif
}

JNIEXPORT jint JNICALL
Java_com_btcminer_android_mining_NativeMiner_getMaxComputeWorkGroupCount(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
#ifdef __ANDROID__
    if (!try_init_vulkan())
        return 0;
    return (jint)g_maxWorkGroupCount;
#else
    return 0;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_btcminer_android_mining_NativeMiner_getVulkanGpuInfo(JNIEnv *env, jclass clazz) {
    (void)clazz;
#ifdef __ANDROID__
    if (!try_init_vulkan())
        return (*env)->NewStringUTF(env, "|");
    char buf[512];
    snprintf(buf, sizeof(buf), "%s|%s", g_deviceName, g_driverName);
    return (*env)->NewStringUTF(env, buf);
#else
    (void)env;
    return NULL;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_btcminer_android_mining_NativeMiner_gpuPipelineReady(JNIEnv *env, jclass clazz, jint localSizeX,
                                                              jint hashesPerThread, jint gpuSha256Mode) {
    (void)env;
    (void)clazz;
#ifdef __ANDROID__
    if (!try_init_vulkan())
        return JNI_FALSE;
    if (normalize_local_size_x((int)localSizeX) < 32u)
        return JNI_FALSE;
    if (!ensure_compute_resources())
        return JNI_FALSE;
    if (!ensure_mining_pipeline((uint32_t)localSizeX, normalize_hashes_per_thread((int)hashesPerThread),
            gpu_mode_shader_variant((int)gpuSha256Mode)))
        return JNI_FALSE;
    return JNI_TRUE;
#else
    (void)localSizeX;
    (void)hashesPerThread;
    (void)gpuSha256Mode;
    return JNI_FALSE;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_btcminer_android_mining_NativeMiner_gpuShaVulkanSelftest(JNIEnv *env, jclass clazz, jint gpuSha256Mode) {
    (void)env;
    (void)clazz;
#ifdef __ANDROID__
    if (!try_init_vulkan())
        return JNI_FALSE;
    if (!ensure_compute_resources())
        return JNI_FALSE;
    return gpu_sha_vulkan_selftest_inner((int)gpuSha256Mode) ? JNI_TRUE : JNI_FALSE;
#else
    (void)gpuSha256Mode;
    return JNI_FALSE;
#endif
}

/* Parameter order must match Kotlin [NativeMiner.gpuScanNoncesInto] (out is last). */
JNIEXPORT void JNICALL
Java_com_btcminer_android_mining_NativeMiner_gpuPipelineSessionBegin(JNIEnv *env, jclass clazz, jbyteArray header76Java,
                                                                     jbyteArray targetJava, jint localSizeX,
                                                                     jint hashesPerThread, jint gpuSha256Mode) {
    (void)clazz;
#ifdef __ANDROID__
    if (!header76Java || !targetJava ||
        (*env)->GetArrayLength(env, header76Java) != HEADER_PREFIX_SIZE ||
        (*env)->GetArrayLength(env, targetJava) != HASH_SIZE) {
        return;
    }
    if (!try_init_vulkan())
        return;
    uint8_t header76[HEADER_PREFIX_SIZE];
    uint8_t target[HASH_SIZE];
    (*env)->GetByteArrayRegion(env, header76Java, 0, HEADER_PREFIX_SIZE, (jbyte *)header76);
    (*env)->GetByteArrayRegion(env, targetJava, 0, HASH_SIZE, (jbyte *)target);
    gpu_pipeline_session_begin_inner(header76, target, (int)localSizeX, (int)hashesPerThread, (int)gpuSha256Mode);
#else
    (void)env;
    (void)localSizeX;
    (void)hashesPerThread;
    (void)gpuSha256Mode;
#endif
}

JNIEXPORT jint JNICALL
Java_com_btcminer_android_mining_NativeMiner_gpuPipelineFlush(JNIEnv *env, jclass clazz, jlongArray outJava,
                                                              jint maxPending) {
    (void)clazz;
#ifdef __ANDROID__
    if (!outJava || (*env)->GetArrayLength(env, outJava) < 2)
        return 0;
    jlong *out = (*env)->GetLongArrayElements(env, outJava, NULL);
    if (!out)
        return 0;
    int drained = gpu_pipeline_flush_inner(out, (int)maxPending);
    if (drained < 0) {
        (*env)->ReleaseLongArrayElements(env, outJava, out, 0);
        return 0;
    }
    (*env)->ReleaseLongArrayElements(env, outJava, out, 0);
    return (jint)drained;
#else
    (void)env;
    (void)outJava;
    (void)maxPending;
    return 0;
#endif
}

JNIEXPORT void JNICALL
Java_com_btcminer_android_mining_NativeMiner_gpuPipelineSessionEnd(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
#ifdef __ANDROID__
    gpu_pipeline_session_end_inner();
#endif
}

JNIEXPORT void JNICALL
Java_com_btcminer_android_mining_NativeMiner_gpuScanNoncesInto(JNIEnv *env, jclass clazz, jbyteArray header76Java,
                                                               jint nonceStart, jint nonceEnd, jbyteArray targetJava,
                                                               jint localSizeX, jint hashesPerThread, jint gpuSha256Mode,
                                                               jlongArray outJava) {
    (void)clazz;
    if (!outJava || (*env)->GetArrayLength(env, outJava) < 2) {
        return;
    }
    jlong *out = (*env)->GetLongArrayElements(env, outJava, NULL);
    if (!out)
        return;
#ifdef __ANDROID__
    const int sessionActive = (g_pipeSession.active && GPU_PIPE_ENABLED);
    if (!sessionActive) {
        if (!header76Java || !targetJava ||
            (*env)->GetArrayLength(env, header76Java) != HEADER_PREFIX_SIZE ||
            (*env)->GetArrayLength(env, targetJava) != HASH_SIZE) {
            out[0] = (jlong)GPU_JNI_STATUS_UNAVAILABLE;
            out[1] = 0;
            (*env)->ReleaseLongArrayElements(env, outJava, out, 0);
            return;
        }
    } else {
        if (header76Java && (*env)->GetArrayLength(env, header76Java) != HEADER_PREFIX_SIZE) {
            out[0] = (jlong)GPU_JNI_STATUS_UNAVAILABLE;
            out[1] = 0;
            (*env)->ReleaseLongArrayElements(env, outJava, out, 0);
            return;
        }
        if (targetJava && (*env)->GetArrayLength(env, targetJava) != HASH_SIZE) {
            out[0] = (jlong)GPU_JNI_STATUS_UNAVAILABLE;
            out[1] = 0;
            (*env)->ReleaseLongArrayElements(env, outJava, out, 0);
            return;
        }
    }

    uint8_t header76[HEADER_PREFIX_SIZE];
    uint8_t target[HASH_SIZE];

    if (!try_init_vulkan()) {
        out[0] = (jlong)GPU_JNI_STATUS_UNAVAILABLE;
        out[1] = 0;
        (*env)->ReleaseLongArrayElements(env, outJava, out, 0);
        return;
    }
    if (!sessionActive) {
        (*env)->GetByteArrayRegion(env, header76Java, 0, HEADER_PREFIX_SIZE, (jbyte *)header76);
        (*env)->GetByteArrayRegion(env, targetJava, 0, HASH_SIZE, (jbyte *)target);
    }
    int hit = 0;
    uint32_t winNonce = 0u;
    int rr;
    if (sessionActive) {
        rr = run_gpu_scan_pipelined((uint32_t)nonceStart, (uint32_t)nonceEnd, &hit, &winNonce);
    } else {
        rr = run_gpu_scan(header76, (uint32_t)nonceStart, (uint32_t)nonceEnd, target, (int)localSizeX,
            (int)hashesPerThread, (int)gpuSha256Mode, &hit, &winNonce);
    }
    if (rr == GPU_UNAVAILABLE) {
        out[0] = (jlong)GPU_JNI_STATUS_UNAVAILABLE;
        out[1] = 0;
    } else if (hit) {
        out[0] = (jlong)GPU_JNI_STATUS_HIT;
        out[1] = (jlong)(uint32_t)winNonce;
    } else {
        out[0] = (jlong)GPU_JNI_STATUS_MISS;
        out[1] = 0;
    }
#else
    (void)nonceStart;
    (void)nonceEnd;
    (void)localSizeX;
    (void)hashesPerThread;
    (void)gpuSha256Mode;
    (void)header76Java;
    (void)targetJava;
    out[0] = (jlong)GPU_JNI_STATUS_UNAVAILABLE;
    out[1] = 0;
#endif
    (*env)->ReleaseLongArrayElements(env, outJava, out, 0);
}

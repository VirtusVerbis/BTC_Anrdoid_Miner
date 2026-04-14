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
#include <android/log.h>

#ifdef __ANDROID__
#include <vulkan/vulkan.h>
#include "miner_spv.h"
#endif

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
static uint32_t g_maxWorkGroupCount = 65535;
static int g_vulkan_available = -1;

static VkDescriptorSetLayout g_descriptorSetLayout = VK_NULL_HANDLE;
static VkPipelineLayout g_pipelineLayout = VK_NULL_HANDLE;
static VkPipeline g_pipelines[MAX_GPU_WORKGROUP_STEPS + 1];
static VkPipeline g_pipeline_selftest = VK_NULL_HANDLE;
static VkDescriptorPool g_descriptorPool = VK_NULL_HANDLE;
static VkDescriptorSet g_descriptorSet = VK_NULL_HANDLE;
static VkBuffer g_uboBuffer = VK_NULL_HANDLE;
static VkDeviceMemory g_uboMemory = VK_NULL_HANDLE;
static VkBuffer g_resultBuffer = VK_NULL_HANDLE;
static VkDeviceMemory g_resultMemory = VK_NULL_HANDLE;
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

static int create_miner_shader_module(VkShaderModule *outModule) {
    VkShaderModuleCreateInfo modInfo = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = g_miner_spv_len,
        .pCode = (const uint32_t *)g_miner_spv,
    };
    if (g_miner_spv_len == 0 || (g_miner_spv_len % 4) != 0) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "No SPIR-V; using CPU fallback");
        return 0;
    }
    if (vkCreateShaderModule(g_device, &modInfo, NULL, outModule) != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "vkCreateShaderModule failed");
        return 0;
    }
    return 1;
}

static int create_pipeline_with_spec(uint32_t localSize, VkPipeline *outPipeline) {
    VkShaderModule shaderModule;
    if (!create_miner_shader_module(&shaderModule))
        return 0;
    uint32_t specData[1] = { localSize };
    VkSpecializationMapEntry specMap[1] = {
        { .constantID = 0, .offset = 0, .size = sizeof(uint32_t) },
    };
    VkSpecializationInfo specInfo = {
        .mapEntryCount = 1,
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

static int ensure_mining_pipeline(uint32_t gpuCores) {
    uint32_t maxSteps = g_maxWorkGroupSize / 32;
    if (maxSteps > MAX_GPU_WORKGROUP_STEPS)
        maxSteps = MAX_GPU_WORKGROUP_STEPS;
    if (gpuCores < 1 || gpuCores > maxSteps || (unsigned)gpuCores > MAX_GPU_WORKGROUP_STEPS)
        return 0;
    VkPipeline *slot = &g_pipelines[gpuCores];
    if (*slot != VK_NULL_HANDLE)
        return 1;
    uint32_t localSize = 32u * gpuCores;
    if (localSize > g_maxWorkGroupSize)
        localSize = g_maxWorkGroupSize;
    if (localSize < 1u)
        localSize = 1u;
    return create_pipeline_with_spec(localSize, slot);
}

static int ensure_selftest_pipeline(void) {
    if (g_pipeline_selftest != VK_NULL_HANDLE)
        return 1;
    return create_pipeline_with_spec(1u, &g_pipeline_selftest);
}

static int ensure_compute_resources(void) {
    if (g_descriptorSetLayout != VK_NULL_HANDLE) {
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
        { VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1 },
        { VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1 },
    };
    VkDescriptorPoolCreateInfo poolInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO,
        .maxSets = 1,
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
    VkDescriptorSetAllocateInfo allocInfo = {
        .sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO,
        .descriptorPool = g_descriptorPool,
        .descriptorSetCount = 1,
        .pSetLayouts = &g_descriptorSetLayout,
    };
    if (vkAllocateDescriptorSets(g_device, &allocInfo, &g_descriptorSet) != VK_SUCCESS) {
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
    VkBufferCreateInfo bufInfo = {
        .sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO,
        .size = UBO_SIZE,
        .usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
    };
    if (vkCreateBuffer(g_device, &bufInfo, NULL, &g_uboBuffer) != VK_SUCCESS)
        goto fail_buffers;
    vkGetBufferMemoryRequirements(g_device, g_uboBuffer, &memReq);
    VkPhysicalDeviceMemoryProperties memProps;
    vkGetPhysicalDeviceMemoryProperties(g_physicalDevice, &memProps);
    uint32_t memTypeIndex = (uint32_t)-1;
    for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
        if ((memReq.memoryTypeBits & (1u << i)) &&
            (memProps.memoryTypes[i].propertyFlags & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) == (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
            memTypeIndex = i;
            break;
        }
    }
    if (memTypeIndex == (uint32_t)-1) {
        for (uint32_t i = 0; i < memProps.memoryTypeCount; i++) {
            if ((memReq.memoryTypeBits & (1u << i)) && (memProps.memoryTypes[i].propertyFlags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
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
        .allocationSize = memReq.size,
        .memoryTypeIndex = memTypeIndex,
    };
    if (vkAllocateMemory(g_device, &allocMem, NULL, &g_uboMemory) != VK_SUCCESS)
        goto fail_buffers;
    vkBindBufferMemory(g_device, g_uboBuffer, g_uboMemory, 0);

    bufInfo.size = RESULT_BUFFER_SIZE;
    bufInfo.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
    if (vkCreateBuffer(g_device, &bufInfo, NULL, &g_resultBuffer) != VK_SUCCESS)
        goto fail_ubo;
    vkGetBufferMemoryRequirements(g_device, g_resultBuffer, &memReq);
    allocMem.allocationSize = memReq.size;
    allocMem.memoryTypeIndex = memTypeIndex;
    if (vkAllocateMemory(g_device, &allocMem, NULL, &g_resultMemory) != VK_SUCCESS) {
        vkDestroyBuffer(g_device, g_resultBuffer, NULL);
        g_resultBuffer = VK_NULL_HANDLE;
        goto fail_ubo;
    }
    vkBindBufferMemory(g_device, g_resultBuffer, g_resultMemory, 0);

    VkDescriptorBufferInfo uboInfo = { g_uboBuffer, 0, UBO_SIZE };
    VkDescriptorBufferInfo resultInfo = { g_resultBuffer, 0, RESULT_BUFFER_SIZE };
    VkWriteDescriptorSet writes[2] = {
        { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = g_descriptorSet, .dstBinding = 0, .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, .pBufferInfo = &uboInfo },
        { .sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET, .dstSet = g_descriptorSet, .dstBinding = 1, .descriptorCount = 1, .descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, .pBufferInfo = &resultInfo },
    };
    vkUpdateDescriptorSets(g_device, 2, writes, 0, NULL);

    VkCommandPoolCreateInfo cmdPoolInfo = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .queueFamilyIndex = g_computeQueueFamily,
    };
    if (vkCreateCommandPool(g_device, &cmdPoolInfo, NULL, &g_commandPool) != VK_SUCCESS)
        goto fail_result;
    VkCommandBufferAllocateInfo cmdAlloc = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .commandPool = g_commandPool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    if (vkAllocateCommandBuffers(g_device, &cmdAlloc, &g_commandBuffer) != VK_SUCCESS) {
        vkDestroyCommandPool(g_device, g_commandPool, NULL);
        g_commandPool = VK_NULL_HANDLE;
        goto fail_result;
    }
    VkFenceCreateInfo fenceInfo = { .sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
    if (vkCreateFence(g_device, &fenceInfo, NULL, &g_fence) != VK_SUCCESS) {
        vkFreeCommandBuffers(g_device, g_commandPool, 1, &g_commandBuffer);
        vkDestroyCommandPool(g_device, g_commandPool, NULL);
        g_commandBuffer = VK_NULL_HANDLE;
        g_commandPool = VK_NULL_HANDLE;
        goto fail_result;
    }
    if (!g_resources_logged) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan compute resources ready (buffers, command buffer, fence)");
        g_resources_logged = 1;
    }
    return 1;
fail_result:
    vkFreeMemory(g_device, g_resultMemory, NULL);
    vkDestroyBuffer(g_device, g_resultBuffer, NULL);
    g_resultMemory = VK_NULL_HANDLE;
    g_resultBuffer = VK_NULL_HANDLE;
fail_ubo:
    vkFreeMemory(g_device, g_uboMemory, NULL);
    vkDestroyBuffer(g_device, g_uboBuffer, NULL);
    g_uboMemory = VK_NULL_HANDLE;
    g_uboBuffer = VK_NULL_HANDLE;
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
    if (g_fence != VK_NULL_HANDLE) {
        vkDestroyFence(g_device, g_fence, NULL);
        g_fence = VK_NULL_HANDLE;
    }
    if (g_commandPool != VK_NULL_HANDLE) {
        if (g_commandBuffer != VK_NULL_HANDLE)
            vkFreeCommandBuffers(g_device, g_commandPool, 1, &g_commandBuffer);
        g_commandBuffer = VK_NULL_HANDLE;
        vkDestroyCommandPool(g_device, g_commandPool, NULL);
        g_commandPool = VK_NULL_HANDLE;
    }
    if (g_resultBuffer != VK_NULL_HANDLE) {
        vkDestroyBuffer(g_device, g_resultBuffer, NULL);
        g_resultBuffer = VK_NULL_HANDLE;
    }
    if (g_resultMemory != VK_NULL_HANDLE) {
        vkFreeMemory(g_device, g_resultMemory, NULL);
        g_resultMemory = VK_NULL_HANDLE;
    }
    if (g_uboBuffer != VK_NULL_HANDLE) {
        vkDestroyBuffer(g_device, g_uboBuffer, NULL);
        g_uboBuffer = VK_NULL_HANDLE;
    }
    if (g_uboMemory != VK_NULL_HANDLE) {
        vkFreeMemory(g_device, g_uboMemory, NULL);
        g_uboMemory = VK_NULL_HANDLE;
    }
    for (int i = 1; i <= MAX_GPU_WORKGROUP_STEPS; i++) {
        if (g_pipelines[i] != VK_NULL_HANDLE) {
            vkDestroyPipeline(g_device, g_pipelines[i], NULL);
            g_pipelines[i] = VK_NULL_HANDLE;
        }
    }
    if (g_pipeline_selftest != VK_NULL_HANDLE) {
        vkDestroyPipeline(g_device, g_pipeline_selftest, NULL);
        g_pipeline_selftest = VK_NULL_HANDLE;
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

    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(g_physicalDevice, &props);
    g_maxWorkGroupSize = props.limits.maxComputeWorkGroupSize[0];
    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan maxComputeWorkGroupSize[0]=%u", (unsigned)g_maxWorkGroupSize);
    g_maxWorkGroupCount = props.limits.maxComputeWorkGroupCount[0];

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

static void fill_ubo_mining(uint8_t *ubo, const uint8_t *header76, uint32_t nonceStart, uint32_t nonceEnd,
                            const uint8_t *target, int useMidstate, int selftestWriteDigest, const uint32_t mid[8]) {
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
    write_le32(ubo + UBO_OFFSET_NONCE_START, nonceStart);
    write_le32(ubo + UBO_OFFSET_NONCE_END, nonceEnd);
    memcpy(ubo + UBO_OFFSET_TARGET, target, HASH_SIZE);
    write_le32(ubo + UBO_OFFSET_GPU_USE_MIDSTATE, useMidstate ? 1u : 0u);
    write_le32(ubo + UBO_OFFSET_GPU_SELFTEST, selftestWriteDigest ? 1u : 0u);
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
    VkCommandBufferBeginInfo beginInfo = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    res = vkBeginCommandBuffer(g_commandBuffer, &beginInfo);
    if (res != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkBeginCommandBuffer failed: %s (%d)", vk_result_str(res), (int)res);
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

/** Vulkan SSBO readback self-test: nonce=1, digest write path; logs GPU_SELFTEST_TAG. Returns 1 if ok. */
static int gpu_sha_vulkan_selftest_inner(int useMidstate) {
    if (g_device == VK_NULL_HANDLE || g_queue == VK_NULL_HANDLE)
        return 0;
    if (!ensure_compute_resources()) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "vulkan_selftest: ensure_compute_resources failed");
        return 0;
    }
    if (!ensure_selftest_pipeline()) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "vulkan_selftest: self-test pipeline failed");
        return 0;
    }
    const uint8_t *h76 = btc_gpu_selftest_header76();
    uint32_t mid[8] = {0};
    if (useMidstate)
        btc_midstate_header76(h76, mid);
    uint8_t target[HASH_SIZE];
    memset(target, 0, sizeof(target));
    uint8_t ubo[UBO_SIZE];
    fill_ubo_mining(ubo, h76, 1u, 1u, target, useMidstate, 1, mid);

    void *ptr;
    VkResult mapRes = vkMapMemory(g_device, g_uboMemory, 0, UBO_SIZE, 0, &ptr);
    if (mapRes != VK_SUCCESS) {
        if (mapRes == VK_ERROR_DEVICE_LOST)
            cleanup_vulkan();
        return 0;
    }
    memcpy(ptr, ubo, UBO_SIZE);
    host_flush_before_gpu_read(g_uboMemory);
    vkUnmapMemory(g_device, g_uboMemory);

    mapRes = vkMapMemory(g_device, g_resultMemory, 0, RESULT_BUFFER_SIZE, 0, &ptr);
    if (mapRes != VK_SUCCESS) {
        if (mapRes == VK_ERROR_DEVICE_LOST)
            cleanup_vulkan();
        return 0;
    }
    memset(ptr, 0, RESULT_BUFFER_SIZE);
    host_flush_before_gpu_read(g_resultMemory);
    vkUnmapMemory(g_device, g_resultMemory);

    if (!submit_once_and_wait(g_pipeline_selftest, 1u, 1u, 1u))
        return 0;

    mapRes = vkMapMemory(g_device, g_resultMemory, 0, RESULT_BUFFER_SIZE, 0, &ptr);
    if (mapRes != VK_SUCCESS) {
        if (mapRes == VK_ERROR_DEVICE_LOST)
            cleanup_vulkan();
        return 0;
    }
    host_invalidate_after_gpu_write_while_mapped(g_resultMemory);
    uint32_t *words = (uint32_t *)ptr;
    uint32_t found = words[RES_WORD_FOUND];
    uint32_t sent_nonce = words[RES_WORD_NONCE];
    uint32_t gw_first[8], gw_final[8];
    memcpy(gw_first, words + RES_WORD_FIRST_HASH, sizeof(gw_first));
    memcpy(gw_final, words + RES_WORD_FIRST_HASH + 8u, sizeof(gw_final));
    vkUnmapMemory(g_device, g_resultMemory);

    uint8_t ref_first[32], ref_final[32];
    btc_first_sha_full(h76, 1u, ref_first);
    btc_double_sha_full(h76, 1u, ref_final);
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

    __android_log_print(ANDROID_LOG_INFO, GPU_SELFTEST_TAG,
        "vulkan_readback mode=%s midstate=%s cpu_first=%s cpu_final=%s gpu_first=%s gpu_final=%s same_first=%d same_final=%d resultFound=%08x winningNonce=%08x",
        useMidstate ? "GPU_Midstate" : "GPU_Full", mid_line, ref_f_hex, ref_l_hex, g_f_hex, g_l_hex, same_first, same_final,
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
                        const uint8_t *target, int gpuCores, int useMidstate, int *hit_out, uint32_t *nonce_out) {
    if (gpuCores < 1) gpuCores = 1;
    uint32_t maxSteps = g_maxWorkGroupSize / 32;
    if (maxSteps > MAX_GPU_WORKGROUP_STEPS)
        maxSteps = MAX_GPU_WORKGROUP_STEPS;
    if ((unsigned)gpuCores > maxSteps)
        gpuCores = (int)maxSteps;
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
    if (!ensure_mining_pipeline((uint32_t)gpuCores))
        return GPU_UNAVAILABLE;
    VkPipeline miningPipe = g_pipelines[gpuCores];
    if (gpuCores < 1 || gpuCores > (int)MAX_GPU_WORKGROUP_STEPS || miningPipe == VK_NULL_HANDLE) {
        return GPU_UNAVAILABLE;
    }
    if (g_uboMemory == VK_NULL_HANDLE || g_resultMemory == VK_NULL_HANDLE ||
        g_descriptorSet == VK_NULL_HANDLE || g_pipelineLayout == VK_NULL_HANDLE) {
        return GPU_UNAVAILABLE;
    }
    *hit_out = 0;
    *nonce_out = 0u;
    atomic_store_explicit(&g_interrupt_requested, 0, memory_order_relaxed);

    uint32_t localSize = 32 * (uint32_t)gpuCores;
    if (localSize > g_maxWorkGroupSize)
        localSize = g_maxWorkGroupSize;
    if (localSize < 1)
        localSize = 1;

    if (!g_workgroup_size_logged) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "GPU workgroup size in use: %u", (unsigned)localSize);
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
    uint64_t maxInvPerPass = (uint64_t)g_maxWorkGroupCount * (uint64_t)localSize;
    if (maxInvPerPass == 0)
        return GPU_UNAVAILABLE;
    if (chunkInv > maxInvPerPass && !s_gpu_multipass_notice) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG,
            "GPU scan uses multiple dispatches per chunk (chunkNonces=%llu maxNoncesPerPass=%llu maxGroups=%u localSize=%u)",
            (unsigned long long)chunkInv, (unsigned long long)maxInvPerPass, (unsigned)g_maxWorkGroupCount, (unsigned)localSize);
        s_gpu_multipass_notice = 1;
    }

    for (uint32_t cursor = nonceStart; cursor <= nonceEnd;) {
        uint64_t remain = (uint64_t)nonceEnd - (uint64_t)cursor + 1ULL;
        uint32_t thisInv = remain > maxInvPerPass ? (uint32_t)maxInvPerPass : (uint32_t)remain;
        uint32_t subEnd = (uint32_t)((uint64_t)cursor + (uint64_t)thisInv - 1ULL);
        uint32_t groupCountX = (thisInv + localSize - 1) / localSize;
        if (groupCountX > g_maxWorkGroupCount)
            groupCountX = g_maxWorkGroupCount;
        if (groupCountX == 0)
            return GPU_UNAVAILABLE;

        fill_ubo_mining(ubo, header76, cursor, subEnd, target, useMidstate, 0, mid);

        void *ptr;
        VkResult mapRes = vkMapMemory(g_device, g_uboMemory, 0, UBO_SIZE, 0, &ptr);
        if (mapRes != VK_SUCCESS) {
            if (mapRes == VK_ERROR_DEVICE_LOST) {
                __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkMapMemory (ubo)");
                cleanup_vulkan();
            } else {
                __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkMapMemory(ubo) failed: %s (%d)", vk_result_str(mapRes), (int)mapRes);
            }
            return GPU_UNAVAILABLE;
        }
        memcpy(ptr, ubo, UBO_SIZE);
        host_flush_before_gpu_read(g_uboMemory);
        vkUnmapMemory(g_device, g_uboMemory);

        mapRes = vkMapMemory(g_device, g_resultMemory, 0, RESULT_BUFFER_SIZE, 0, &ptr);
        if (mapRes != VK_SUCCESS) {
            if (mapRes == VK_ERROR_DEVICE_LOST) {
                __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkMapMemory (result)");
                cleanup_vulkan();
            } else {
                __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkMapMemory(result) failed: %s (%d)", vk_result_str(mapRes), (int)mapRes);
            }
            return GPU_UNAVAILABLE;
        }
        mining_result_buffer_reset(ptr);
        host_flush_before_gpu_read(g_resultMemory);
        vkUnmapMemory(g_device, g_resultMemory);

        if (!submit_once_and_wait(miningPipe, groupCountX, 1u, 1u))
            return GPU_UNAVAILABLE;

        mapRes = vkMapMemory(g_device, g_resultMemory, 0, 8u, 0, &ptr);
        if (mapRes != VK_SUCCESS) {
            if (mapRes == VK_ERROR_DEVICE_LOST) {
                __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan device lost on vkMapMemory (result read)");
                cleanup_vulkan();
            } else {
                __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "vkMapMemory(result read) failed: %s (%d)", vk_result_str(mapRes), (int)mapRes);
            }
            return GPU_UNAVAILABLE;
        }
        host_invalidate_after_gpu_write_while_mapped(g_resultMemory);
        {
            uint32_t *words = (uint32_t *)ptr;
            uint32_t found = words[RES_WORD_FOUND];
            uint32_t win = words[RES_WORD_NONCE];
            vkUnmapMemory(g_device, g_resultMemory);
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

JNIEXPORT jboolean JNICALL
Java_com_btcminer_android_mining_NativeMiner_gpuPipelineReady(JNIEnv *env, jclass clazz, jint gpuCores,
                                                              jint gpuSha256Mode) {
    (void)env;
    (void)clazz;
#ifdef __ANDROID__
    if (!try_init_vulkan())
        return JNI_FALSE;
    if (gpuCores < 1) gpuCores = 1;
    uint32_t maxSteps = g_maxWorkGroupSize / 32;
    if (maxSteps > MAX_GPU_WORKGROUP_STEPS)
        maxSteps = MAX_GPU_WORKGROUP_STEPS;
    if ((unsigned)gpuCores > maxSteps)
        gpuCores = (int)maxSteps;
    if (!ensure_compute_resources())
        return JNI_FALSE;
    (void)gpuSha256Mode;
    if (!ensure_mining_pipeline((uint32_t)gpuCores))
        return JNI_FALSE;
    return JNI_TRUE;
#else
    (void)gpuCores;
    (void)gpuSha256Mode;
    return JNI_FALSE;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_btcminer_android_mining_NativeMiner_gpuShaVulkanSelftest(JNIEnv *env, jclass clazz, jint useMidstate) {
    (void)env;
    (void)clazz;
#ifdef __ANDROID__
    if (!try_init_vulkan())
        return JNI_FALSE;
    if (!ensure_compute_resources())
        return JNI_FALSE;
    return gpu_sha_vulkan_selftest_inner(useMidstate != 0) ? JNI_TRUE : JNI_FALSE;
#else
    (void)useMidstate;
    return JNI_FALSE;
#endif
}

/* Parameter order must match Kotlin [NativeMiner.gpuScanNoncesInto] (out is last). */
JNIEXPORT void JNICALL
Java_com_btcminer_android_mining_NativeMiner_gpuScanNoncesInto(JNIEnv *env, jclass clazz, jbyteArray header76Java,
                                                               jint nonceStart, jint nonceEnd, jbyteArray targetJava,
                                                               jint gpuCores, jint gpuSha256Mode, jlongArray outJava) {
    (void)clazz;
    if (!outJava || (*env)->GetArrayLength(env, outJava) < 2) {
        return;
    }
    jlong *out = (*env)->GetLongArrayElements(env, outJava, NULL);
    if (!out)
        return;
    if (!header76Java || !targetJava ||
        (*env)->GetArrayLength(env, header76Java) != HEADER_PREFIX_SIZE ||
        (*env)->GetArrayLength(env, targetJava) != HASH_SIZE) {
        out[0] = (jlong)GPU_JNI_STATUS_UNAVAILABLE;
        out[1] = 0;
        (*env)->ReleaseLongArrayElements(env, outJava, out, 0);
        return;
    }

    uint8_t header76[HEADER_PREFIX_SIZE];
    uint8_t target[HASH_SIZE];

#ifdef __ANDROID__
    if (!try_init_vulkan()) {
        out[0] = (jlong)GPU_JNI_STATUS_UNAVAILABLE;
        out[1] = 0;
        (*env)->ReleaseLongArrayElements(env, outJava, out, 0);
        return;
    }
    (*env)->GetByteArrayRegion(env, header76Java, 0, HEADER_PREFIX_SIZE, (jbyte *)header76);
    (*env)->GetByteArrayRegion(env, targetJava, 0, HASH_SIZE, (jbyte *)target);
    int useMid = (gpuSha256Mode != 0);
    int hit = 0;
    uint32_t winNonce = 0u;
    int rr = run_gpu_scan(header76, (uint32_t)nonceStart, (uint32_t)nonceEnd, target, (int)gpuCores, useMid, &hit,
        &winNonce);
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
    (void)gpuCores;
    (void)gpuSha256Mode;
    out[0] = (jlong)GPU_JNI_STATUS_UNAVAILABLE;
    out[1] = 0;
#endif
    (*env)->ReleaseLongArrayElements(env, outJava, out, 0);
}

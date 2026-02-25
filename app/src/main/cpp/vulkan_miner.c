/*
 * Vulkan GPU miner JNI.
 * gpuIsAvailable(): initializes Vulkan (instance, device, compute queue). Returns true if Vulkan is present.
 * gpuScanNonces(): scans nonce range via compute shader. Returns -2 if GPU path unavailable (no CPU fallback).
 */
#include "sha256.h"
#include <jni.h>
#include <stdint.h>
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
#define UBO_SIZE 128
#define LOG_TAG "VulkanMiner"
#define MAX_GPU_WORKGROUP_STEPS 64
/* Returned to Java when GPU path is unavailable (no SPIR-V or Vulkan failure). */
#define GPU_UNAVAILABLE (-2)

static int hash_meets_target(const uint8_t *hash, const uint8_t *target) {
    return memcmp(hash, target, HASH_SIZE) <= 0;
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
static VkPipeline g_pipelines[MAX_GPU_WORKGROUP_STEPS + 1] = { VK_NULL_HANDLE }; /* index 0 unused, 1..maxSteps = gpuCores */
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

static int create_compute_pipeline(uint32_t gpuCores) {
    uint32_t maxSteps = g_maxWorkGroupSize / 32;
    if (maxSteps > MAX_GPU_WORKGROUP_STEPS)
        maxSteps = MAX_GPU_WORKGROUP_STEPS;
    if (gpuCores < 1 || gpuCores > maxSteps || (unsigned)gpuCores > MAX_GPU_WORKGROUP_STEPS || g_pipelines[gpuCores] != VK_NULL_HANDLE)
        return (g_pipelines[gpuCores] != VK_NULL_HANDLE);
    uint32_t localSize = 32 * gpuCores;
    if (localSize > g_maxWorkGroupSize)
        localSize = g_maxWorkGroupSize;
    if (localSize < 1)
        localSize = 1;

    VkShaderModuleCreateInfo modInfo = {
        .sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO,
        .codeSize = g_miner_spv_len,
        .pCode = (const uint32_t *)g_miner_spv,
    };
    if (g_miner_spv_len == 0 || (g_miner_spv_len % 4) != 0) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "No SPIR-V; using CPU fallback");
        return 0;
    }
    VkShaderModule shaderModule;
    if (vkCreateShaderModule(g_device, &modInfo, NULL, &shaderModule) != VK_SUCCESS) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "vkCreateShaderModule failed");
        return 0;
    }
    VkSpecializationMapEntry specEntry = { .constantID = 0, .offset = 0, .size = sizeof(uint32_t) };
    VkSpecializationInfo specInfo = {
        .mapEntryCount = 1,
        .pMapEntries = &specEntry,
        .dataSize = sizeof(uint32_t),
        .pData = &localSize,
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
    VkResult res = vkCreateComputePipelines(g_device, VK_NULL_HANDLE, 1, &pipeInfo, NULL, &g_pipelines[gpuCores]);
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
    VkMemoryAllocateInfo allocMem = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .allocationSize = memReq.size,
        .memoryTypeIndex = memTypeIndex,
    };
    if (vkAllocateMemory(g_device, &allocMem, NULL, &g_uboMemory) != VK_SUCCESS)
        goto fail_buffers;
    vkBindBufferMemory(g_device, g_uboBuffer, g_uboMemory, 0);

    bufInfo.size = sizeof(uint32_t);
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
    VkDescriptorBufferInfo resultInfo = { g_resultBuffer, 0, sizeof(uint32_t) };
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
        vkDeviceWaitIdle(g_device);
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

/* Run compute dispatch; returns winning nonce, -1 if no solution in chunk, or GPU_UNAVAILABLE on failure. */
static int run_gpu_scan(const uint8_t *header76, uint32_t nonceStart, uint32_t nonceEnd,
                        const uint8_t *target, int gpuCores) {
    if (gpuCores < 1) gpuCores = 1;
    uint32_t maxSteps = g_maxWorkGroupSize / 32;
    if (maxSteps > MAX_GPU_WORKGROUP_STEPS)
        maxSteps = MAX_GPU_WORKGROUP_STEPS;
    if ((unsigned)gpuCores > maxSteps)
        gpuCores = (int)maxSteps;
    if (!ensure_compute_resources())
        return GPU_UNAVAILABLE;
    if (!create_compute_pipeline((uint32_t)gpuCores))
        return GPU_UNAVAILABLE;

    uint32_t localSize = 32 * (uint32_t)gpuCores;
    if (localSize > g_maxWorkGroupSize)
        localSize = g_maxWorkGroupSize;
    if (localSize < 1)
        localSize = 1;

    if (!g_workgroup_size_logged) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "GPU workgroup size in use: %u", (unsigned)localSize);
        g_workgroup_size_logged = 1;
    }

    uint32_t totalInv = nonceEnd - nonceStart + 1;
    uint32_t groupCountX = (totalInv + localSize - 1) / localSize;
    if (groupCountX > g_maxWorkGroupCount)
        groupCountX = g_maxWorkGroupCount;
    if (groupCountX == 0)
        return GPU_UNAVAILABLE;

    /* Fill UBO: 76 bytes header (as 19 big-endian uints for shader), 4 nonceStart, 4 nonceEnd, 32 target */
    uint8_t ubo[UBO_SIZE];
    memset(ubo, 0, sizeof(ubo));
    for (int i = 0; i < 19; i++) {
        uint32_t w = (uint32_t)header76[i*4] << 24 | (uint32_t)header76[i*4+1] << 16 |
                     (uint32_t)header76[i*4+2] << 8 | (uint32_t)header76[i*4+3];
        write_le32(ubo + i * 4, w);
    }
    write_le32(ubo + 76, nonceStart);
    write_le32(ubo + 80, nonceEnd);
    memcpy(ubo + 84, target, 32);

    void *ptr;
    if (vkMapMemory(g_device, g_uboMemory, 0, UBO_SIZE, 0, &ptr) != VK_SUCCESS)
        return GPU_UNAVAILABLE;
    memcpy(ptr, ubo, UBO_SIZE);
    vkUnmapMemory(g_device, g_uboMemory);

    uint32_t noWin = 0xFFFFFFFFu;
    if (vkMapMemory(g_device, g_resultMemory, 0, sizeof(uint32_t), 0, &ptr) != VK_SUCCESS)
        return GPU_UNAVAILABLE;
    memcpy(ptr, &noWin, sizeof(noWin));
    vkUnmapMemory(g_device, g_resultMemory);

    vkResetFences(g_device, 1, &g_fence);
    VkCommandBufferBeginInfo beginInfo = { .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
    if (vkBeginCommandBuffer(g_commandBuffer, &beginInfo) != VK_SUCCESS)
        return GPU_UNAVAILABLE;
    vkCmdBindPipeline(g_commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g_pipelines[gpuCores]);
    vkCmdBindDescriptorSets(g_commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, g_pipelineLayout, 0, 1, &g_descriptorSet, 0, NULL);
    vkCmdDispatch(g_commandBuffer, groupCountX, 1, 1);
    if (vkEndCommandBuffer(g_commandBuffer) != VK_SUCCESS)
        return GPU_UNAVAILABLE;
    VkSubmitInfo submitInfo = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .commandBufferCount = 1,
        .pCommandBuffers = &g_commandBuffer,
    };
    if (g_first_dispatch_state == 0) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "First GPU dispatch submitted");
        g_first_dispatch_state = 1;
    }
    if (vkQueueSubmit(g_queue, 1, &submitInfo, g_fence) != VK_SUCCESS) {
        if (g_first_dispatch_state < 2) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "First GPU dispatch failed (queue submit or wait)");
            g_first_dispatch_state = 2;
        }
        return GPU_UNAVAILABLE;
    }
    if (vkWaitForFences(g_device, 1, &g_fence, VK_TRUE, 5000000000ull) != VK_SUCCESS) {
        if (g_first_dispatch_state < 2) {
            __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "First GPU dispatch failed (queue submit or wait)");
            g_first_dispatch_state = 2;
        }
        return GPU_UNAVAILABLE;
    }
    if (g_first_dispatch_state == 1) {
        __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "First GPU dispatch completed");
        g_first_dispatch_state = 2;
    }
    if (vkMapMemory(g_device, g_resultMemory, 0, sizeof(uint32_t), 0, &ptr) != VK_SUCCESS)
        return GPU_UNAVAILABLE;
    memcpy(&noWin, ptr, sizeof(noWin));
    vkUnmapMemory(g_device, g_resultMemory);
    if (noWin == 0xFFFFFFFFu)
        return -1;  /* Chunk scanned, no solution */
    return (int)(int32_t)noWin;
}
#endif

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
Java_com_btcminer_android_mining_NativeMiner_gpuPipelineReady(JNIEnv *env, jclass clazz, jint gpuCores) {
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
    if (!create_compute_pipeline((uint32_t)gpuCores))
        return JNI_FALSE;
    return JNI_TRUE;
#else
    (void)gpuCores;
    return JNI_FALSE;
#endif
}

/* Returns -2 (GPU path unavailable) to Java when Vulkan/SPIR-V or dispatch fails; no CPU fallback. */
JNIEXPORT jint JNICALL
Java_com_btcminer_android_mining_NativeMiner_gpuScanNonces(JNIEnv *env, jclass clazz,
                                                           jbyteArray header76Java,
                                                           jint nonceStart, jint nonceEnd,
                                                           jbyteArray targetJava,
                                                           jint gpuCores) {
    (void)env;
    (void)clazz;
    if (!header76Java || !targetJava ||
        (*env)->GetArrayLength(env, header76Java) != HEADER_PREFIX_SIZE ||
        (*env)->GetArrayLength(env, targetJava) != HASH_SIZE) {
        return (jint)GPU_UNAVAILABLE;
    }

    uint8_t header76[HEADER_PREFIX_SIZE];
    uint8_t target[HASH_SIZE];

#ifdef __ANDROID__
    if (!try_init_vulkan()) {
        return (jint)GPU_UNAVAILABLE;
    }
    (*env)->GetByteArrayRegion(env, header76Java, 0, HEADER_PREFIX_SIZE, (jbyte *)header76);
    (*env)->GetByteArrayRegion(env, targetJava, 0, HASH_SIZE, (jbyte *)target);
    int result = run_gpu_scan(header76, (uint32_t)nonceStart, (uint32_t)nonceEnd, target, (int)gpuCores);
    return (jint)result;
#else
    (void)nonceStart;
    (void)nonceEnd;
    (void)gpuCores;
    return (jint)GPU_UNAVAILABLE;
#endif
}

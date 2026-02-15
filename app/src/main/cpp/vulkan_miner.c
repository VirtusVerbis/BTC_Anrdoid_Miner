/*
 * Vulkan GPU miner JNI.
 * gpuIsAvailable(): initializes Vulkan (instance, device, compute queue). Returns true if Vulkan is present.
 * gpuScanNonces(): scans nonce range. When Vulkan is available uses CPU hashing (same as nativeScanNonces)
 * until a Vulkan compute shader is added; when Vulkan is not available returns -1.
 */
#include "sha256.h"
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <android/log.h>

#ifdef __ANDROID__
#include <vulkan/vulkan.h>
#endif

#define HEADER_PREFIX_SIZE 76
#define BLOCK_HEADER_SIZE 80
#define HASH_SIZE 32
#define LOG_TAG "VulkanMiner"

static int hash_meets_target(const uint8_t *hash, const uint8_t *target) {
    return memcmp(hash, target, HASH_SIZE) <= 0;
}

#ifdef __ANDROID__
static VkInstance g_instance = VK_NULL_HANDLE;
static VkDevice g_device = VK_NULL_HANDLE;
static VkPhysicalDevice g_physicalDevice = VK_NULL_HANDLE;
static VkQueue g_queue = VK_NULL_HANDLE;
static int g_vulkan_available = -1; /* -1 = not tried, 0 = no, 1 = yes */

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

    uint32_t queueCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(g_physicalDevice, &queueCount, NULL);
    if (queueCount == 0) {
        vkDestroyInstance(g_instance, NULL);
        g_instance = VK_NULL_HANDLE;
        g_physicalDevice = VK_NULL_HANDLE;
        return 0;
    }

    VkQueueFamilyProperties *props = (VkQueueFamilyProperties *)malloc(queueCount * sizeof(VkQueueFamilyProperties));
    if (!props) {
        vkDestroyInstance(g_instance, NULL);
        g_instance = VK_NULL_HANDLE;
        g_physicalDevice = VK_NULL_HANDLE;
        return 0;
    }
    vkGetPhysicalDeviceQueueFamilyProperties(g_physicalDevice, &queueCount, props);

    uint32_t computeQueueFamily = UINT32_MAX;
    for (uint32_t i = 0; i < queueCount; i++) {
        if (props[i].queueFlags & VK_QUEUE_COMPUTE_BIT) {
            computeQueueFamily = i;
            break;
        }
    }
    free(props);

    if (computeQueueFamily == UINT32_MAX) {
        vkDestroyInstance(g_instance, NULL);
        g_instance = VK_NULL_HANDLE;
        g_physicalDevice = VK_NULL_HANDLE;
        return 0;
    }

    float priority = 1.0f;
    VkDeviceQueueCreateInfo queueInfo = {
        .sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO,
        .queueFamilyIndex = computeQueueFamily,
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

    vkGetDeviceQueue(g_device, computeQueueFamily, 0, &g_queue);
    g_vulkan_available = 1;
    __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, "Vulkan init OK (GPU path uses CPU hashing until compute shader is added)");
    return 1;
}

static void cleanup_vulkan(void) {
    if (g_device != VK_NULL_HANDLE) {
        vkDeviceWaitIdle(g_device);
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
Java_com_btcminer_android_mining_NativeMiner_gpuScanNonces(JNIEnv *env, jclass clazz,
                                                           jbyteArray header76Java,
                                                           jint nonceStart, jint nonceEnd,
                                                           jbyteArray targetJava) {
    (void)env;
    (void)clazz;
    if (!header76Java || !targetJava ||
        (*env)->GetArrayLength(env, header76Java) != HEADER_PREFIX_SIZE ||
        (*env)->GetArrayLength(env, targetJava) != HASH_SIZE) {
        return (jint)-1;
    }

#ifdef __ANDROID__
    if (!try_init_vulkan()) {
        return (jint)-1;
    }
#else
    return (jint)-1;
#endif

    uint8_t header80[BLOCK_HEADER_SIZE];
    uint8_t target[HASH_SIZE];
    uint8_t hash[HASH_SIZE];
    (*env)->GetByteArrayRegion(env, header76Java, 0, HEADER_PREFIX_SIZE, (jbyte *)header80);
    (*env)->GetByteArrayRegion(env, targetJava, 0, HASH_SIZE, (jbyte *)target);

    uint32_t start = (uint32_t)nonceStart;
    uint32_t end = (uint32_t)nonceEnd;
    for (uint32_t nonce = start; nonce <= end; nonce++) {
        header80[76] = (uint8_t)(nonce);
        header80[77] = (uint8_t)(nonce >> 8);
        header80[78] = (uint8_t)(nonce >> 16);
        header80[79] = (uint8_t)(nonce >> 24);
        sha256_double(header80, BLOCK_HEADER_SIZE, hash);
        if (hash_meets_target(hash, target)) {
            return (jint)nonce;
        }
    }
    return (jint)-1;
}

package com.jlmoya.gpu;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;

/**
 * FOUNDATION PROBE (step 1 of the Vulkan de-risk): does LWJGL load the Vulkan loader (the SDK's
 * MoltenVK / kosmickrisp ICD) and enumerate this Mac's GPU? Creates a VkInstance with the
 * portability-enumeration extension+flag (required for Apple's portability drivers) and prints each
 * physical device. No window, no surface — purely "is Vulkan reachable from our JVM".
 */
public final class VulkanProbe {

    private static final String DEFAULT_LOADER =
        "/Users/josemoya/VulkanSDK/1.4.350.1/macOS/lib/libvulkan.dylib";

    public static void main(String[] args) {
        // Point LWJGL straight at the SDK's loader dylib (don't rely on DYLD_*, which SIP can strip).
        String loader = System.getProperty("vk.loader", DEFAULT_LOADER);
        Configuration.VULKAN_LIBRARY_NAME.set(loader);
        System.out.println("[vk] loader = " + loader);
        System.out.println("[vk] VK_ICD_FILENAMES = " + System.getenv("VK_ICD_FILENAMES"));
        System.out.println("[vk] VK_DRIVER_FILES  = " + System.getenv("VK_DRIVER_FILES"));

        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(stack)
                .sType$Default()
                .pApplicationName(stack.UTF8("scilab-vulkan-probe"))
                .applicationVersion(1)
                .pEngineName(stack.UTF8("scilab"))
                .engineVersion(1)
                .apiVersion(VK_API_VERSION_1_1);

            // Apple drivers are "portability" implementations -> must opt in.
            PointerBuffer exts = stack.pointers(stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME));

            VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack)
                .sType$Default()
                .flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
                .pApplicationInfo(app)
                .ppEnabledExtensionNames(exts);

            PointerBuffer pInstance = stack.mallocPointer(1);
            int err = vkCreateInstance(ci, null, pInstance);
            if (err != VK_SUCCESS) {
                System.err.println("[vk] vkCreateInstance FAILED: " + err
                                   + " (VK_ERROR_INCOMPATIBLE_DRIVER=" + VK_ERROR_INCOMPATIBLE_DRIVER
                                   + ", VK_ERROR_EXTENSION_NOT_PRESENT=" + VK_ERROR_EXTENSION_NOT_PRESENT + ")");
                return;
            }
            VkInstance instance = new VkInstance(pInstance.get(0), ci);
            System.out.println("[vk] instance created OK");

            IntBuffer pCount = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, pCount, null);
            int n = pCount.get(0);
            System.out.println("[vk] physical devices: " + n);

            if (n > 0) {
                PointerBuffer devs = stack.mallocPointer(n);
                vkEnumeratePhysicalDevices(instance, pCount, devs);
                for (int i = 0; i < n; i++) {
                    VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(i), instance);
                    VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
                    vkGetPhysicalDeviceProperties(pd, props);
                    int a = props.apiVersion();
                    String type;
                    switch (props.deviceType()) {
                        case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: type = "integrated-GPU"; break;
                        case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU:   type = "discrete-GPU";   break;
                        case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU:    type = "virtual-GPU";    break;
                        case VK_PHYSICAL_DEVICE_TYPE_CPU:            type = "CPU";            break;
                        default:                                     type = "other";         break;
                    }
                    System.out.println("[vk]   device[" + i + "] = \"" + props.deviceNameString() + "\""
                        + "  api=" + VK_VERSION_MAJOR(a) + "." + VK_VERSION_MINOR(a) + "." + VK_VERSION_PATCH(a)
                        + "  type=" + type);
                }
                System.out.println("[vk] RESULT: Vulkan is reachable + a GPU enumerates. Foundation step 1 PASS.");
            } else {
                System.err.println("[vk] RESULT: instance OK but NO physical device — the ICD isn't being "
                                   + "found. Check VK_ICD_FILENAMES / VK_DRIVER_FILES (source setup-env.sh).");
            }
            vkDestroyInstance(instance, null);
        }
    }
}

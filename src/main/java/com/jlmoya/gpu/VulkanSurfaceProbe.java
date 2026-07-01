package com.jlmoya.gpu;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkMetalSurfaceCreateInfoEXT;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkSurfaceCapabilitiesKHR;
import org.lwjgl.vulkan.VkSurfaceFormatKHR;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.memPointerBuffer;
import static org.lwjgl.vulkan.EXTMetalSurface.VK_EXT_METAL_SURFACE_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTMetalSurface.vkCreateMetalSurfaceEXT;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;

/**
 * FOUNDATION PROBE (step 2): turn Layer-1's CAMetalLayer into a {@code VkSurfaceKHR} via
 * {@code vkCreateMetalSurfaceEXT}, then confirm a present-capable queue family and read the swapchain
 * parameters (caps, formats, present modes). No swapchain/render yet — proves the surface path and
 * gathers what step 3 needs. Auto-exits in a few seconds.
 */
public final class VulkanSurfaceProbe {

    static final String LOADER = "/Users/josemoya/VulkanSDK/1.4.350.1/macOS/lib/libvulkan.dylib";

    public static void main(String[] args) throws Exception {
        Configuration.VULKAN_LIBRARY_NAME.set(System.getProperty("vk.loader", LOADER));
        final GpuSurfaceComponent c = new GpuSurfaceComponent();
        c.setPreferredSize(new Dimension(480, 360));
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("vk surface probe (auto-closes)");
            f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            f.add(c, BorderLayout.CENTER);
            f.pack();
            f.setLocation(80, 80);
            f.setVisible(true);
        });
        Thread t = new Thread(() -> probe(c), "vk-surface-probe");
        t.setDaemon(true);
        t.start();
        t.join(15000);
        System.out.println("[vks] finished");
        System.exit(0);
    }

    static void probe(GpuSurfaceComponent comp) {
        final NativeSurface s = waitForSurface(comp);
        if (s == null || s.handle() == 0L) {
            System.err.println("[vks] no CAMetalLayer acquired");
            return;
        }
        final long caMetalLayer = s.handle();
        System.out.println("[vks] CAMetalLayer = 0x" + Long.toHexString(caMetalLayer)
                           + "  " + s.width() + "x" + s.height());

        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                .apiVersion(VK_API_VERSION_1_1);
            PointerBuffer exts = stack.pointers(
                stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME),
                stack.UTF8(VK_KHR_SURFACE_EXTENSION_NAME),
                stack.UTF8(VK_EXT_METAL_SURFACE_EXTENSION_NAME));
            VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack).sType$Default()
                .flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR)
                .pApplicationInfo(app)
                .ppEnabledExtensionNames(exts);
            PointerBuffer pInstance = stack.mallocPointer(1);
            int e = vkCreateInstance(ci, null, pInstance);
            if (e != VK_SUCCESS) {
                System.err.println("[vks] vkCreateInstance failed: " + e);
                return;
            }
            VkInstance instance = new VkInstance(pInstance.get(0), ci);
            System.out.println("[vks] instance OK (KHR_surface + EXT_metal_surface enabled)");

            // CAMetalLayer -> VkSurfaceKHR
            // pLayer is a `const CAMetalLayer*`. LWJGL's setter stores the PointerBuffer's ADDRESS into
            // the field, so wrap the layer pointer as a 1-element buffer whose address IS the layer.
            VkMetalSurfaceCreateInfoEXT sci = VkMetalSurfaceCreateInfoEXT.calloc(stack)
                .sType$Default()
                .pLayer(memPointerBuffer(caMetalLayer, 1));
            LongBuffer pSurface = stack.mallocLong(1);
            e = vkCreateMetalSurfaceEXT(instance, sci, null, pSurface);
            if (e != VK_SUCCESS) {
                System.err.println("[vks] vkCreateMetalSurfaceEXT FAILED: " + e);
                vkDestroyInstance(instance, null);
                return;
            }
            long surface = pSurface.get(0);
            System.out.println("[vks] VkSurfaceKHR created = 0x" + Long.toHexString(surface));

            IntBuffer pc = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, pc, null);
            PointerBuffer devs = stack.mallocPointer(pc.get(0));
            vkEnumeratePhysicalDevices(instance, pc, devs);
            VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(0), instance);

            vkGetPhysicalDeviceQueueFamilyProperties(pd, pc, null);
            int qn = pc.get(0);
            VkQueueFamilyProperties.Buffer qfp = VkQueueFamilyProperties.calloc(qn, stack);
            vkGetPhysicalDeviceQueueFamilyProperties(pd, pc, qfp);
            int graphics = -1, present = -1;
            for (int i = 0; i < qn; i++) {
                if ((qfp.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0 && graphics < 0) {
                    graphics = i;
                }
                IntBuffer sup = stack.mallocInt(1);
                vkGetPhysicalDeviceSurfaceSupportKHR(pd, i, surface, sup);
                if (sup.get(0) == VK_TRUE && present < 0) {
                    present = i;
                }
            }
            System.out.println("[vks] queue families (" + qn + "): graphics=" + graphics + " present=" + present);

            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(pd, surface, caps);
            System.out.println("[vks] caps: minImages=" + caps.minImageCount()
                               + " maxImages=" + caps.maxImageCount()
                               + " currentExtent=" + caps.currentExtent().width() + "x" + caps.currentExtent().height()
                               + " supportedUsage=0x" + Integer.toHexString(caps.supportedUsageFlags()));

            vkGetPhysicalDeviceSurfaceFormatsKHR(pd, surface, pc, null);
            int fn = pc.get(0);
            VkSurfaceFormatKHR.Buffer fmts = VkSurfaceFormatKHR.calloc(fn, stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(pd, surface, pc, fmts);
            StringBuilder fb = new StringBuilder();
            for (int i = 0; i < Math.min(fn, 6); i++) {
                fb.append(" fmt=").append(fmts.get(i).format()).append("/cs=").append(fmts.get(i).colorSpace());
            }
            System.out.println("[vks] surface formats (" + fn + "):" + fb);

            vkGetPhysicalDeviceSurfacePresentModesKHR(pd, surface, pc, null);
            int pmN = pc.get(0);
            IntBuffer pm = stack.mallocInt(pmN);
            vkGetPhysicalDeviceSurfacePresentModesKHR(pd, surface, pc, pm);
            StringBuilder pmb = new StringBuilder();
            for (int i = 0; i < pmN; i++) {
                pmb.append(' ').append(pm.get(i));   // 0=IMMEDIATE 1=MAILBOX 2=FIFO 3=FIFO_RELAXED
            }
            System.out.println("[vks] present modes (" + pmN + "):" + pmb + "  (2=FIFO always available)");

            if (present >= 0 && graphics >= 0 && fn > 0) {
                System.out.println("[vks] RESULT: CAMetalLayer -> VkSurfaceKHR works, present-capable queue + "
                                   + "swapchain params available. Step 2 PASS.");
            } else {
                System.err.println("[vks] RESULT: surface created but missing a present queue or formats.");
            }

            vkDestroySurfaceKHR(instance, surface, null);
            vkDestroyInstance(instance, null);
        }
    }

    private static NativeSurface waitForSurface(GpuSurfaceComponent c) {
        for (int i = 0; i < 400; i++) {
            NativeSurface s = c.surface();
            if (s != null && s.handle() != 0L && c.getWidth() > 0) {
                return s;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return c.surface();
    }
}

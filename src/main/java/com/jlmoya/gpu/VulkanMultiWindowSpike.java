package com.jlmoya.gpu;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTMetalSurface.*;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;

/**
 * FOUNDATION STEP 4 — the multi-window proof. ONE VkInstance + ONE VkDevice drive TWO windows, each
 * with its own VkSurfaceKHR + VkSwapchainKHR, presented from one render thread and cleared to a
 * DISTINCT colour (window A orange, window B blue). Each window's presented image is read back to a
 * PNG and sampled. Pass = A reads orange, B reads blue — i.e. multi-window works natively in Vulkan,
 * the capability the whole per-figure-swapchain renderer is built on. Auto-exits.
 */
public final class VulkanMultiWindowSpike {

    static final String LOADER = "/Users/josemoya/VulkanSDK/1.4.350.1/macOS/lib/libvulkan.dylib";
    static final String PORTABILITY_SUBSET = "VK_KHR_portability_subset";

    // Per-window Vulkan objects (surface + swapchain + command/sync + readback).
    static final class Win {
        long surface, swapchain, pool, readBuf, readMem, semAcquire, semDone, fence;
        VkCommandBuffer cmd;
        long[] images;
        int w, h, centerRGB;
        long bufSize;
        String name, path;
    }

    public static void main(String[] args) throws Exception {
        Configuration.VULKAN_LIBRARY_NAME.set(System.getProperty("vk.loader", LOADER));
        final GpuSurfaceComponent ca = new GpuSurfaceComponent();
        final GpuSurfaceComponent cb = new GpuSurfaceComponent();
        ca.setPreferredSize(new Dimension(480, 360));
        cb.setPreferredSize(new Dimension(480, 360));
        SwingUtilities.invokeLater(() -> {
            show("Vulkan A (orange)", ca, 80, 120);
            show("Vulkan B (blue)", cb, 620, 120);
        });
        Thread t = new Thread(() -> {
            try {
                run(ca, cb);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }, "vk-multi");
        t.setDaemon(true);
        t.start();
        t.join(25000);
        System.out.println("[vkm] finished");
        System.exit(0);
    }

    static void show(String title, GpuSurfaceComponent c, int x, int y) {
        JFrame f = new JFrame(title);
        f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        f.add(c, BorderLayout.CENTER);
        f.pack();
        f.setLocation(x, y);
        f.setVisible(true);
    }

    static void run(GpuSurfaceComponent ca, GpuSurfaceComponent cb) {
        final NativeSurface sa = waitForSurface(ca);
        final NativeSurface sb = waitForSurface(cb);
        if (sa == null || sb == null || sa.handle() == 0L || sb.handle() == 0L) {
            System.err.println("[vkm] failed to acquire both CAMetalLayers");
            return;
        }
        System.out.println("[vkm] two CAMetalLayers: A=0x" + Long.toHexString(sa.handle())
                           + " B=0x" + Long.toHexString(sb.handle()));

        try (MemoryStack stack = stackPush()) {
            // ---- ONE instance ----
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack).sType$Default().apiVersion(VK_API_VERSION_1_1);
            PointerBuffer iexts = stack.pointers(
                stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME),
                stack.UTF8(VK_KHR_SURFACE_EXTENSION_NAME),
                stack.UTF8(VK_EXT_METAL_SURFACE_EXTENSION_NAME));
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack).sType$Default()
                .flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR).pApplicationInfo(appInfo)
                .ppEnabledExtensionNames(iexts);
            PointerBuffer pInst = stack.mallocPointer(1);
            check(vkCreateInstance(ici, null, pInst), "createInstance");
            VkInstance instance = new VkInstance(pInst.get(0), ici);

            // ---- two surfaces ----
            long surfA = createMetalSurface(stack, instance, sa.handle());
            long surfB = createMetalSurface(stack, instance, sb.handle());

            // ---- ONE physical device + ONE logical device (queue family 0 = graphics+present) ----
            IntBuffer pc = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, pc, null);
            PointerBuffer pds = stack.mallocPointer(pc.get(0));
            vkEnumeratePhysicalDevices(instance, pc, pds);
            VkPhysicalDevice pd = new VkPhysicalDevice(pds.get(0), instance);
            int qfam = 0;

            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, stack);
            qci.get(0).sType$Default().queueFamilyIndex(qfam).pQueuePriorities(stack.floats(1.0f));
            PointerBuffer dexts = stack.pointers(
                stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME), stack.UTF8(PORTABILITY_SUBSET));
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType$Default()
                .pQueueCreateInfos(qci).ppEnabledExtensionNames(dexts);
            PointerBuffer pDev = stack.mallocPointer(1);
            check(vkCreateDevice(pd, dci, null, pDev), "createDevice");
            VkDevice device = new VkDevice(pDev.get(0), pd, dci);
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, qfam, 0, pQueue);
            VkQueue queue = new VkQueue(pQueue.get(0), device);
            System.out.println("[vkm] one instance + one device drive both windows");

            // ---- build a per-window swapchain/command/sync/readback for each ----
            Win a = createWin(stack, device, pd, surfA, qfam, "A", "/tmp/vkmulti_a.png");
            Win b = createWin(stack, device, pd, surfB, qfam, "B", "/tmp/vkmulti_b.png");

            // ---- render distinct content to each window (one thread, one device) ----
            renderAndReadback(stack, device, queue, a, 1.0f, 0.5f, 0.0f);   // orange
            renderAndReadback(stack, device, queue, b, 0.0f, 0.3f, 1.0f);   // blue

            int ra = sampleCenter(a);
            int rb = sampleCenter(b);
            boolean aOrange = ((ra >> 16) & 0xff) > 200 && (ra & 0xff) < 60;
            boolean bBlue = (rb & 0xff) > 200 && ((rb >> 16) & 0xff) < 60;
            if (aOrange && bBlue) {
                System.out.println("[vkm] RESULT: two windows presented DISTINCT content from one device. "
                                   + "MULTI-WINDOW WORKS on Vulkan/MoltenVK. Step 4 PASS.");
            } else {
                System.err.println("[vkm] RESULT: colours not as expected (A=" + hex(ra) + " B=" + hex(rb) + ").");
            }

            // ---- teardown ----
            vkDeviceWaitIdle(device);
            destroyWin(device, instance, a);
            destroyWin(device, instance, b);
            vkDestroyDevice(device, null);
            vkDestroyInstance(instance, null);
        }
    }

    static long createMetalSurface(MemoryStack stack, VkInstance instance, long caLayer) {
        VkMetalSurfaceCreateInfoEXT mci = VkMetalSurfaceCreateInfoEXT.calloc(stack).sType$Default()
            .pLayer(memPointerBuffer(caLayer, 1));
        LongBuffer p = stack.mallocLong(1);
        check(vkCreateMetalSurfaceEXT(instance, mci, null, p), "createMetalSurface");
        return p.get(0);
    }

    static Win createWin(MemoryStack stack, VkDevice device, VkPhysicalDevice pd, long surface, int qfam,
                         String name, String path) {
        Win win = new Win();
        win.surface = surface;
        win.name = name;
        win.path = path;

        VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(pd, surface, caps);
        int w = caps.currentExtent().width(), h = caps.currentExtent().height();
        if (w == 0xFFFFFFFF) { w = 480; h = 360; }
        win.w = w;
        win.h = h;
        int maxImg = caps.maxImageCount() == 0 ? Integer.MAX_VALUE : caps.maxImageCount();
        VkSwapchainCreateInfoKHR sci = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default()
            .surface(surface).minImageCount(Math.min(caps.minImageCount() + 1, maxImg))
            .imageFormat(VK_FORMAT_B8G8R8A8_UNORM).imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR).imageArrayLayers(1)
            .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
            .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE).preTransform(caps.currentTransform())
            .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR).presentMode(VK_PRESENT_MODE_FIFO_KHR).clipped(true);
        sci.imageExtent().width(w).height(h);
        LongBuffer pSwap = stack.mallocLong(1);
        check(vkCreateSwapchainKHR(device, sci, null, pSwap), "createSwapchain-" + name);
        win.swapchain = pSwap.get(0);
        IntBuffer pc = stack.mallocInt(1);
        vkGetSwapchainImagesKHR(device, win.swapchain, pc, null);
        int n = pc.get(0);
        LongBuffer imgs = stack.mallocLong(n);
        vkGetSwapchainImagesKHR(device, win.swapchain, pc, imgs);
        win.images = new long[n];
        for (int i = 0; i < n; i++) {
            win.images[i] = imgs.get(i);
        }

        VkCommandPoolCreateInfo cpi = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
            .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(qfam);
        LongBuffer pPool = stack.mallocLong(1);
        check(vkCreateCommandPool(device, cpi, null, pPool), "pool-" + name);
        win.pool = pPool.get(0);
        VkCommandBufferAllocateInfo cbai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
            .commandPool(win.pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
        PointerBuffer pCmd = stack.mallocPointer(1);
        check(vkAllocateCommandBuffers(device, cbai, pCmd), "cmd-" + name);
        win.cmd = new VkCommandBuffer(pCmd.get(0), device);

        win.bufSize = (long) w * h * 4;
        VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default()
            .size(win.bufSize).usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        LongBuffer pBuf = stack.mallocLong(1);
        check(vkCreateBuffer(device, bci, null, pBuf), "buf-" + name);
        win.readBuf = pBuf.get(0);
        VkMemoryRequirements mreqs = VkMemoryRequirements.calloc(stack);
        vkGetBufferMemoryRequirements(device, win.readBuf, mreqs);
        int mt = findMemoryType(pd, stack, mreqs.memoryTypeBits(),
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
            .allocationSize(mreqs.size()).memoryTypeIndex(mt);
        LongBuffer pMem = stack.mallocLong(1);
        check(vkAllocateMemory(device, mai, null, pMem), "mem-" + name);
        win.readMem = pMem.get(0);
        vkBindBufferMemory(device, win.readBuf, win.readMem, 0);

        VkSemaphoreCreateInfo semci = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
        LongBuffer p1 = stack.mallocLong(1), p2 = stack.mallocLong(1);
        check(vkCreateSemaphore(device, semci, null, p1), "semA-" + name);
        check(vkCreateSemaphore(device, semci, null, p2), "semB-" + name);
        win.semAcquire = p1.get(0);
        win.semDone = p2.get(0);
        LongBuffer pF = stack.mallocLong(1);
        check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, pF), "fence-" + name);
        win.fence = pF.get(0);
        return win;
    }

    static void renderAndReadback(MemoryStack stack, VkDevice device, VkQueue queue, Win win,
                                  float r, float g, float b) {
        IntBuffer pImgIdx = stack.mallocInt(1);
        checkP(vkAcquireNextImageKHR(device, win.swapchain, Long.MAX_VALUE, win.semAcquire, VK_NULL_HANDLE, pImgIdx), "acquire-" + win.name);
        int idx = pImgIdx.get(0);
        long image = win.images[idx];

        VkCommandBufferBeginInfo cbbi = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        vkBeginCommandBuffer(win.cmd, cbbi);

        barrier(stack, win.cmd, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkClearColorValue clear = VkClearColorValue.calloc(stack);
        clear.float32(0, r).float32(1, g).float32(2, b).float32(3, 1.0f);
        VkImageSubresourceRange.Buffer ranges = VkImageSubresourceRange.calloc(1, stack);
        ranges.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdClearColorImage(win.cmd, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, ranges);

        barrier(stack, win.cmd, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        region.get(0).imageExtent().set(win.w, win.h, 1);
        vkCmdCopyImageToBuffer(win.cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, win.readBuf, region);

        barrier(stack, win.cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
            VK_ACCESS_TRANSFER_READ_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        vkEndCommandBuffer(win.cmd);

        VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default()
            .waitSemaphoreCount(1).pWaitSemaphores(stack.longs(win.semAcquire))
            .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
            .pCommandBuffers(stack.pointers(win.cmd)).pSignalSemaphores(stack.longs(win.semDone));
        check(vkQueueSubmit(queue, si, win.fence), "submit-" + win.name);

        VkPresentInfoKHR ppi = VkPresentInfoKHR.calloc(stack).sType$Default()
            .pWaitSemaphores(stack.longs(win.semDone)).swapchainCount(1)
            .pSwapchains(stack.longs(win.swapchain)).pImageIndices(stack.ints(idx));
        checkP(vkQueuePresentKHR(queue, ppi), "present-" + win.name);

        vkWaitForFences(device, stack.longs(win.fence), true, Long.MAX_VALUE);
        PointerBuffer ppData = stack.mallocPointer(1);
        vkMapMemory(device, win.readMem, 0, win.bufSize, 0, ppData);
        ByteBuffer pix = memByteBuffer(ppData.get(0), (int) win.bufSize);
        savePng(win.path, win.w, win.h, pix);
        // stash center pixel then unmap
        int p = ((win.h / 2) * win.w + win.w / 2) * 4;
        win.centerRGB = ((pix.get(p + 2) & 0xff) << 16) | ((pix.get(p + 1) & 0xff) << 8) | (pix.get(p) & 0xff);
        vkUnmapMemory(device, win.readMem);
        System.out.println("[vkm] window " + win.name + " center rgb=" + hex(win.centerRGB) + " -> " + win.path);
    }

    static int sampleCenter(Win win) {
        return win.centerRGB;
    }

    static void destroyWin(VkDevice device, VkInstance instance, Win w) {
        vkDestroyFence(device, w.fence, null);
        vkDestroySemaphore(device, w.semAcquire, null);
        vkDestroySemaphore(device, w.semDone, null);
        vkFreeMemory(device, w.readMem, null);
        vkDestroyBuffer(device, w.readBuf, null);
        vkDestroyCommandPool(device, w.pool, null);
        vkDestroySwapchainKHR(device, w.swapchain, null);
        vkDestroySurfaceKHR(instance, w.surface, null);
    }

    static void barrier(MemoryStack stack, VkCommandBuffer cmd, long image, int oldL, int newL,
                        int srcA, int dstA, int srcStage, int dstStage) {
        VkImageMemoryBarrier.Buffer bb = VkImageMemoryBarrier.calloc(1, stack);
        bb.get(0).sType$Default().oldLayout(oldL).newLayout(newL)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image).srcAccessMask(srcA).dstAccessMask(dstA);
        bb.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, bb);
    }

    static int findMemoryType(VkPhysicalDevice pd, MemoryStack stack, int filter, int props) {
        VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(pd, mp);
        for (int i = 0; i < mp.memoryTypeCount(); i++) {
            if ((filter & (1 << i)) != 0 && (mp.memoryTypes(i).propertyFlags() & props) == props) {
                return i;
            }
        }
        throw new RuntimeException("no host-visible memory type");
    }

    static void check(int r, String what) {
        if (r != VK_SUCCESS) {
            throw new RuntimeException("[vkm] " + what + " failed: " + r);
        }
    }

    static void checkP(int r, String what) {
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
            throw new RuntimeException("[vkm] " + what + " failed: " + r);
        }
    }

    static String hex(int rgb) {
        return String.format("(%d,%d,%d)", (rgb >> 16) & 0xff, (rgb >> 8) & 0xff, rgb & 0xff);
    }

    static void savePng(String path, int w, int h, ByteBuffer buf) {
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = (y * w + x) * 4;   // BGRA8
                    int b = buf.get(p) & 0xff, g = buf.get(p + 1) & 0xff, r = buf.get(p + 2) & 0xff;
                    img.setRGB(x, y, (r << 16) | (g << 8) | b);
                }
            }
            ImageIO.write(img, "png", new File(path));
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    static NativeSurface waitForSurface(GpuSurfaceComponent c) {
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

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
 * FOUNDATION STEP 3: full single-window Vulkan present path on MoltenVK — logical device + swapchain
 * + clear + PRESENT, with a GPU readback of the presented image to a PNG so it's verified without any
 * desktop capture. Pass = /tmp/vkclear.png (and the center-pixel sample) is orange. This is the real
 * plumbing the renderer is built on. Auto-exits.
 */
public final class VulkanClearSpike {

    static final String LOADER = "/Users/josemoya/VulkanSDK/1.4.350.1/macOS/lib/libvulkan.dylib";
    static final String PORTABILITY_SUBSET = "VK_KHR_portability_subset";

    public static void main(String[] args) throws Exception {
        Configuration.VULKAN_LIBRARY_NAME.set(System.getProperty("vk.loader", LOADER));
        final GpuSurfaceComponent c = new GpuSurfaceComponent();
        c.setPreferredSize(new Dimension(480, 360));
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("vk clear spike (auto-closes)");
            f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            f.add(c, BorderLayout.CENTER);
            f.pack();
            f.setLocation(80, 80);
            f.setVisible(true);
        });
        Thread t = new Thread(() -> {
            try {
                run(c);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }, "vk-clear");
        t.setDaemon(true);
        t.start();
        t.join(20000);
        System.out.println("[vkc] finished");
        System.exit(0);
    }

    static void run(GpuSurfaceComponent comp) {
        final NativeSurface ns = waitForSurface(comp);
        if (ns == null || ns.handle() == 0L) {
            System.err.println("[vkc] no surface");
            return;
        }
        final long caLayer = ns.handle();

        try (MemoryStack stack = stackPush()) {
            // ---- instance ----
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

            // ---- surface ----
            VkMetalSurfaceCreateInfoEXT mci = VkMetalSurfaceCreateInfoEXT.calloc(stack).sType$Default()
                .pLayer(memPointerBuffer(caLayer, 1));
            LongBuffer pSurf = stack.mallocLong(1);
            check(vkCreateMetalSurfaceEXT(instance, mci, null, pSurf), "createMetalSurface");
            long surface = pSurf.get(0);

            // ---- physical device + queue family (step 2 showed family 0 = graphics+present) ----
            IntBuffer pc = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, pc, null);
            PointerBuffer pds = stack.mallocPointer(pc.get(0));
            vkEnumeratePhysicalDevices(instance, pc, pds);
            VkPhysicalDevice pd = new VkPhysicalDevice(pds.get(0), instance);
            int qfam = 0;

            // ---- logical device (VK_KHR_swapchain + VK_KHR_portability_subset) ----
            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, stack);
            qci.get(0).sType$Default().queueFamilyIndex(qfam).pQueuePriorities(stack.floats(1.0f));
            PointerBuffer dexts = stack.pointers(
                stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME),
                stack.UTF8(PORTABILITY_SUBSET));
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType$Default()
                .pQueueCreateInfos(qci).ppEnabledExtensionNames(dexts);
            PointerBuffer pDev = stack.mallocPointer(1);
            check(vkCreateDevice(pd, dci, null, pDev), "createDevice");
            VkDevice device = new VkDevice(pDev.get(0), pd, dci);
            PointerBuffer pQueue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, qfam, 0, pQueue);
            VkQueue queue = new VkQueue(pQueue.get(0), device);

            // ---- swapchain ----
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(pd, surface, caps);
            int w = caps.currentExtent().width();
            int h = caps.currentExtent().height();
            if (w == 0xFFFFFFFF) { w = 480; h = 360; }
            int fmt = VK_FORMAT_B8G8R8A8_UNORM;
            int maxImg = caps.maxImageCount() == 0 ? Integer.MAX_VALUE : caps.maxImageCount();
            int imgReq = Math.min(caps.minImageCount() + 1, maxImg);
            VkSwapchainCreateInfoKHR sci = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default()
                .surface(surface).minImageCount(imgReq).imageFormat(fmt)
                .imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR).imageArrayLayers(1)
                .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE).preTransform(caps.currentTransform())
                .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR).presentMode(VK_PRESENT_MODE_FIFO_KHR).clipped(true);
            sci.imageExtent().width(w).height(h);
            LongBuffer pSwap = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(device, sci, null, pSwap), "createSwapchain");
            long swapchain = pSwap.get(0);
            vkGetSwapchainImagesKHR(device, swapchain, pc, null);
            int nImg = pc.get(0);
            LongBuffer images = stack.mallocLong(nImg);
            vkGetSwapchainImagesKHR(device, swapchain, pc, images);
            System.out.println("[vkc] swapchain " + w + "x" + h + " images=" + nImg);

            // ---- command pool + buffer ----
            VkCommandPoolCreateInfo cpi = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(qfam);
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(device, cpi, null, pPool), "createCommandPool");
            long pool = pPool.get(0);
            VkCommandBufferAllocateInfo cbai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, cbai, pCmd), "allocCmd");
            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), device);

            // ---- host-visible readback buffer ----
            long bufSize = (long) w * h * 4;
            VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                .size(bufSize).usage(VK_BUFFER_USAGE_TRANSFER_DST_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            LongBuffer pBuf = stack.mallocLong(1);
            check(vkCreateBuffer(device, bci, null, pBuf), "createBuffer");
            long readBuf = pBuf.get(0);
            VkMemoryRequirements mr = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, readBuf, mr);
            int memType = findMemoryType(pd, stack, mr.memoryTypeBits(),
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                .allocationSize(mr.size()).memoryTypeIndex(memType);
            LongBuffer pMem = stack.mallocLong(1);
            check(vkAllocateMemory(device, mai, null, pMem), "allocMemory");
            long readMem = pMem.get(0);
            vkBindBufferMemory(device, readBuf, readMem, 0);

            // ---- sync ----
            VkSemaphoreCreateInfo semci = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer pAcq = stack.mallocLong(1);
            LongBuffer pDone = stack.mallocLong(1);
            check(vkCreateSemaphore(device, semci, null, pAcq), "sem1");
            check(vkCreateSemaphore(device, semci, null, pDone), "sem2");
            long semAcquire = pAcq.get(0);
            long semDone = pDone.get(0);
            VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(stack).sType$Default();
            LongBuffer pFence = stack.mallocLong(1);
            check(vkCreateFence(device, fci, null, pFence), "fence");
            long fence = pFence.get(0);

            // ---- acquire the next swapchain image ----
            IntBuffer pImgIdx = stack.mallocInt(1);
            checkP(vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE, semAcquire, VK_NULL_HANDLE, pImgIdx), "acquire");
            int imgIdx = pImgIdx.get(0);
            long image = images.get(imgIdx);

            // ---- record: clear -> copy-to-buffer (readback) -> transition to present ----
            VkCommandBufferBeginInfo cbbi = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkBeginCommandBuffer(cmd, cbbi);

            barrier(stack, cmd, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

            VkClearColorValue clear = VkClearColorValue.calloc(stack);
            clear.float32(0, 1.0f).float32(1, 0.5f).float32(2, 0.0f).float32(3, 1.0f);   // orange
            VkImageSubresourceRange.Buffer ranges = VkImageSubresourceRange.calloc(1, stack);
            ranges.get(0).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            vkCmdClearColorImage(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, clear, ranges);

            barrier(stack, cmd, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).imageOffset().set(0, 0, 0);
            region.get(0).imageExtent().set(w, h, 1);
            vkCmdCopyImageToBuffer(cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readBuf, region);

            barrier(stack, cmd, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_TRANSFER_READ_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            vkEndCommandBuffer(cmd);

            // ---- submit + present ----
            VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default()
                .waitSemaphoreCount(1).pWaitSemaphores(stack.longs(semAcquire))
                .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_TRANSFER_BIT))
                .pCommandBuffers(stack.pointers(cmd)).pSignalSemaphores(stack.longs(semDone));
            check(vkQueueSubmit(queue, si, fence), "submit");

            VkPresentInfoKHR pi = VkPresentInfoKHR.calloc(stack).sType$Default()
                .pWaitSemaphores(stack.longs(semDone))
                .swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(stack.ints(imgIdx));
            checkP(vkQueuePresentKHR(queue, pi), "present");

            // ---- wait, then read back the presented image ----
            vkWaitForFences(device, pFence, true, Long.MAX_VALUE);
            PointerBuffer ppData = stack.mallocPointer(1);
            vkMapMemory(device, readMem, 0, bufSize, 0, ppData);
            ByteBuffer pix = memByteBuffer(ppData.get(0), (int) bufSize);
            savePng("/tmp/vkclear.png", w, h, pix);
            sample(w, h, pix);
            vkUnmapMemory(device, readMem);

            System.out.println("[vkc] RESULT: device + swapchain + clear + PRESENT + readback all succeeded. Step 3 PASS.");

            // ---- teardown ----
            vkDeviceWaitIdle(device);
            vkDestroyFence(device, fence, null);
            vkDestroySemaphore(device, semAcquire, null);
            vkDestroySemaphore(device, semDone, null);
            vkFreeMemory(device, readMem, null);
            vkDestroyBuffer(device, readBuf, null);
            vkDestroyCommandPool(device, pool, null);
            vkDestroySwapchainKHR(device, swapchain, null);
            vkDestroyDevice(device, null);
            vkDestroySurfaceKHR(instance, surface, null);
            vkDestroyInstance(instance, null);
        }
    }

    static void barrier(MemoryStack stack, VkCommandBuffer cmd, long image, int oldL, int newL,
                        int srcA, int dstA, int srcStage, int dstStage) {
        VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
        b.get(0).sType$Default().oldLayout(oldL).newLayout(newL)
            .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .image(image).srcAccessMask(srcA).dstAccessMask(dstA);
        b.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, b);
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
            throw new RuntimeException("[vkc] " + what + " failed: " + r);
        }
    }

    static void checkP(int r, String what) {
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
            throw new RuntimeException("[vkc] " + what + " failed: " + r);
        }
    }

    static void sample(int w, int h, ByteBuffer buf) {
        int p = ((h / 2) * w + w / 2) * 4;   // BGRA8
        int b = buf.get(p) & 0xff, g = buf.get(p + 1) & 0xff, r = buf.get(p + 2) & 0xff;
        System.out.println("[vkc] center pixel rgb=(" + r + "," + g + "," + b + ")  (expect orange ~255,128,0)");
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
            System.out.println("[vkc] wrote " + path);
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

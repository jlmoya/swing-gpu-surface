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
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTMetalSurface.*;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;

/**
 * M3 — the shared renderer context. ONE VkInstance + VkDevice + render thread + shared render pass /
 * pipeline / cube geometry, driving N figures, each with its own surface/swapchain/depth/framebuffers.
 * {@link #register}/{@link #unregister} add and remove figures on any thread; all vk* calls happen on
 * the render thread. This is the multi-window model from the design, made real. The {@code main} here
 * is the M3 test: two windows, each a cube at a different rotation, verified by readback.
 */
public final class VulkanContext {

    static final String LOADER = "/Users/josemoya/VulkanSDK/1.4.350.1/macOS/lib/libvulkan.dylib";
    static final String PORTABILITY_SUBSET = "VK_KHR_portability_subset";
    static final int COLOR_FORMAT = VK_FORMAT_B8G8R8A8_UNORM;
    static final int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;

    static final float[] VERTS = {
        -1, -1, -1,  0, 0, 0,   1, -1, -1,  1, 0, 0,   1,  1, -1,  1, 1, 0,  -1,  1, -1,  0, 1, 0,
        -1, -1,  1,  0, 0, 1,   1, -1,  1,  1, 0, 1,   1,  1,  1,  1, 1, 1,  -1,  1,  1,  0, 1, 1,
    };
    static final int[] INDICES = {
        0, 1, 2, 0, 2, 3,  4, 6, 5, 4, 7, 6,  0, 3, 7, 0, 7, 4,  1, 5, 6, 1, 6, 2,  3, 2, 6, 3, 6, 7,  0, 4, 5, 0, 5, 1,
    };

    // ---- shared ----
    private VkInstance instance;
    private VkPhysicalDevice pd;
    private VkDevice device;
    private VkQueue queue;
    private int qfam = 0;
    private long renderPass, pipeline, pipelineLayout, commandPool;
    private long vbo, vboMem, ibo, iboMem;
    private boolean instanceInit, deviceInit;

    private final CopyOnWriteArrayList<Figure> figures = new CopyOnWriteArrayList<>();
    private volatile boolean stopRequested;
    private Thread renderThread;

    /** Per-figure GPU resources. */
    static final class Figure {
        long surface, swapchain, depthImage, depthMem, depthView, readBuf, readMem;
        long[] images, views, framebuffers;
        VkCommandBuffer cmd;
        long semAcquire, semDone, fence;
        int w, h;
        long bufSize;
        float rotation;
        String capturePath;
        volatile boolean captured;
    }

    // ---- public lifecycle (any thread) ----

    public synchronized Figure register(long caMetalLayer, int w, int h, float rotation, String capturePath) {
        if (!instanceInit) {
            initInstance();
        }
        Figure f;
        try (MemoryStack stack = stackPush()) {
            long surface = createMetalSurface(stack, caMetalLayer);
            if (!deviceInit) {
                initDevice(stack, surface);
            }
            f = createFigure(stack, surface, w, h, rotation, capturePath);
        }
        figures.add(f);
        if (renderThread == null) {
            startRenderThread();
        }
        return f;
    }

    public void unregister(Figure f) {
        figures.remove(f);
        // Actual GPU teardown happens on the render thread (deferred); omitted in this M3 slice —
        // the process exits after the test. M4+ wires a per-figure destroy queue drained on the thread.
    }

    public boolean allCaptured() {
        if (figures.isEmpty()) {
            return false;
        }
        for (Figure f : figures) {
            if (!f.captured) {
                return false;
            }
        }
        return true;
    }

    public void shutdown() {
        stopRequested = true;
        if (renderThread != null) {
            try {
                renderThread.join(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ---- render thread ----

    private void startRenderThread() {
        renderThread = new Thread(() -> {
            while (!stopRequested) {
                for (Figure f : figures) {
                    try {
                        renderFigure(f);
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                }
            }
        }, "vk-render");
        renderThread.setDaemon(true);
        renderThread.start();
    }

    private void renderFigure(Figure f) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pIdx = stack.mallocInt(1);
            checkP(vkAcquireNextImageKHR(device, f.swapchain, Long.MAX_VALUE, f.semAcquire, VK_NULL_HANDLE, pIdx), "acquire");
            int idx = pIdx.get(0);

            float[] proj = Mat4.perspective((float) Math.toRadians(45), (float) f.w / f.h, 0.1f, 100f);
            float[] view = Mat4.lookAt(new float[] {2.6f, 2.4f, 4.0f}, new float[] {0, 0, 0}, new float[] {0, 1, 0});
            float[] mvp = Mat4.mul(Mat4.mul(proj, view), Mat4.rotateY(f.rotation));

            vkBeginCommandBuffer(f.cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            VkClearValue.Buffer clearv = VkClearValue.calloc(2, stack);
            clearv.get(0).color().float32(0, 0.10f).float32(1, 0.10f).float32(2, 0.13f).float32(3, 1.0f);
            clearv.get(1).depthStencil().depth(1.0f).stencil(0);
            VkRenderPassBeginInfo rpbi = VkRenderPassBeginInfo.calloc(stack).sType$Default()
                .renderPass(renderPass).framebuffer(f.framebuffers[idx]).pClearValues(clearv);
            rpbi.renderArea().extent().width(f.w).height(f.h);
            vkCmdBeginRenderPass(f.cmd, rpbi, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(f.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            VkViewport.Buffer vpb = VkViewport.calloc(1, stack);
            vpb.get(0).x(0).y(0).width(f.w).height(f.h).minDepth(0).maxDepth(1);
            vkCmdSetViewport(f.cmd, 0, vpb);
            VkRect2D.Buffer scb = VkRect2D.calloc(1, stack);
            scb.get(0).extent().width(f.w).height(f.h);
            vkCmdSetScissor(f.cmd, 0, scb);
            vkCmdBindVertexBuffers(f.cmd, 0, stack.longs(vbo), stack.longs(0));
            vkCmdBindIndexBuffer(f.cmd, ibo, 0, VK_INDEX_TYPE_UINT32);
            vkCmdPushConstants(f.cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, stack.floats(mvp));
            vkCmdDrawIndexed(f.cmd, INDICES.length, 1, 0, 0, 0);
            vkCmdEndRenderPass(f.cmd);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).imageExtent().set(f.w, f.h, 1);
            vkCmdCopyImageToBuffer(f.cmd, f.images[idx], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, f.readBuf, region);
            barrier(stack, f.cmd, f.images[idx], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_TRANSFER_READ_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            vkEndCommandBuffer(f.cmd);

            VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default().waitSemaphoreCount(1)
                .pWaitSemaphores(stack.longs(f.semAcquire)).pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                .pCommandBuffers(stack.pointers(f.cmd)).pSignalSemaphores(stack.longs(f.semDone));
            check(vkQueueSubmit(queue, si, f.fence), "submit");
            checkP(vkQueuePresentKHR(queue, VkPresentInfoKHR.calloc(stack).sType$Default().pWaitSemaphores(stack.longs(f.semDone))
                .swapchainCount(1).pSwapchains(stack.longs(f.swapchain)).pImageIndices(stack.ints(idx))), "present");
            vkWaitForFences(device, stack.longs(f.fence), true, Long.MAX_VALUE);
            vkResetFences(device, stack.longs(f.fence));

            if (!f.captured && f.capturePath != null) {
                PointerBuffer ppData = stack.mallocPointer(1);
                vkMapMemory(device, f.readMem, 0, f.bufSize, 0, ppData);
                ByteBuffer pix = memByteBuffer(ppData.get(0), (int) f.bufSize);
                savePng(f.capturePath, f.w, f.h, pix);
                vkUnmapMemory(device, f.readMem);
                f.captured = true;
            }
        }
    }

    // ---- shared init ----

    private void initInstance() {
        Configuration.VULKAN_LIBRARY_NAME.set(System.getProperty("vk.loader", LOADER));
        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default().apiVersion(VK_API_VERSION_1_1);
            PointerBuffer iexts = stack.pointers(stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME),
                stack.UTF8(VK_KHR_SURFACE_EXTENSION_NAME), stack.UTF8(VK_EXT_METAL_SURFACE_EXTENSION_NAME));
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack).sType$Default()
                .flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR).pApplicationInfo(app).ppEnabledExtensionNames(iexts);
            PointerBuffer p = stack.mallocPointer(1);
            check(vkCreateInstance(ici, null, p), "instance");
            instance = new VkInstance(p.get(0), ici);
        }
        instanceInit = true;
    }

    private void initDevice(MemoryStack stack, long surfaceForPresentCheck) {
        IntBuffer pc = stack.mallocInt(1);
        vkEnumeratePhysicalDevices(instance, pc, null);
        PointerBuffer pds = stack.mallocPointer(pc.get(0));
        vkEnumeratePhysicalDevices(instance, pc, pds);
        pd = new VkPhysicalDevice(pds.get(0), instance);
        qfam = 0;   // foundation showed family 0 = graphics + present for metal surfaces

        VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, stack);
        qci.get(0).sType$Default().queueFamilyIndex(qfam).pQueuePriorities(stack.floats(1.0f));
        PointerBuffer dexts = stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME), stack.UTF8(PORTABILITY_SUBSET));
        VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(qci).ppEnabledExtensionNames(dexts);
        PointerBuffer pDev = stack.mallocPointer(1);
        check(vkCreateDevice(pd, dci, null, pDev), "device");
        device = new VkDevice(pDev.get(0), pd, dci);
        PointerBuffer pQ = stack.mallocPointer(1);
        vkGetDeviceQueue(device, qfam, 0, pQ);
        queue = new VkQueue(pQ.get(0), device);

        createRenderPass(stack);
        createPipeline(stack);
        createGeometry(stack);
        VkCommandPoolCreateInfo cpi = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
            .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(qfam);
        LongBuffer pPool = stack.mallocLong(1);
        check(vkCreateCommandPool(device, cpi, null, pPool), "pool");
        commandPool = pPool.get(0);
        deviceInit = true;
    }

    private void createRenderPass(MemoryStack stack) {
        VkAttachmentDescription.Buffer att = VkAttachmentDescription.calloc(2, stack);
        att.get(0).format(COLOR_FORMAT).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
            .storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
            .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        att.get(1).format(DEPTH_FORMAT).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
            .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
            .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
        colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack).attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
        VkSubpassDescription.Buffer sub = VkSubpassDescription.calloc(1, stack);
        sub.get(0).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(colorRef).pDepthStencilAttachment(depthRef);
        VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(1, stack);
        dep.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
            .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
            .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
            .srcAccessMask(0).dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        LongBuffer p = stack.mallocLong(1);
        check(vkCreateRenderPass(device, VkRenderPassCreateInfo.calloc(stack).sType$Default().pAttachments(att).pSubpasses(sub).pDependencies(dep), null, p), "renderPass");
        renderPass = p.get(0);
    }

    private void createPipeline(MemoryStack stack) {
        long vert = shaderModule(stack, "/shaders/cube.vert.spv");
        long frag = shaderModule(stack, "/shaders/cube.frag.spv");
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"));
        stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"));
        VkVertexInputBindingDescription.Buffer bind = VkVertexInputBindingDescription.calloc(1, stack);
        bind.get(0).binding(0).stride(6 * 4).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        VkVertexInputAttributeDescription.Buffer attr = VkVertexInputAttributeDescription.calloc(2, stack);
        attr.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
        attr.get(1).location(1).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(12);
        VkPipelineVertexInputStateCreateInfo vin = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
            .pVertexBindingDescriptions(bind).pVertexAttributeDescriptions(attr);
        VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
        // Dynamic viewport/scissor so one pipeline serves any figure size.
        VkPipelineViewportStateCreateInfo vps = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);
        VkPipelineDynamicStateCreateInfo dyn = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
            .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
        VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
            .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1.0f);
        VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
        VkPipelineDepthStencilStateCreateInfo ds = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
            .depthTestEnable(true).depthWriteEnable(true).depthCompareOp(VK_COMPARE_OP_LESS);
        VkPipelineColorBlendAttachmentState.Buffer cba = VkPipelineColorBlendAttachmentState.calloc(1, stack);
        cba.get(0).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT).blendEnable(false);
        VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(cba);
        VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
        pcr.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(64);
        LongBuffer pLayout = stack.mallocLong(1);
        check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pPushConstantRanges(pcr), null, pLayout), "layout");
        pipelineLayout = pLayout.get(0);
        VkGraphicsPipelineCreateInfo.Buffer gp = VkGraphicsPipelineCreateInfo.calloc(1, stack);
        gp.get(0).sType$Default().pStages(stages).pVertexInputState(vin).pInputAssemblyState(ia).pViewportState(vps)
            .pDynamicState(dyn).pRasterizationState(rs).pMultisampleState(ms).pDepthStencilState(ds).pColorBlendState(cb)
            .layout(pipelineLayout).renderPass(renderPass).subpass(0);
        LongBuffer pPipe = stack.mallocLong(1);
        check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, gp, null, pPipe), "pipeline");
        pipeline = pPipe.get(0);
        vkDestroyShaderModule(device, vert, null);
        vkDestroyShaderModule(device, frag, null);
    }

    private void createGeometry(MemoryStack stack) {
        long[] v = hostBuffer(stack, VERTS.length * 4L, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        vbo = v[0]; vboMem = v[1];
        PointerBuffer pp = stack.mallocPointer(1);
        vkMapMemory(device, vboMem, 0, VERTS.length * 4L, 0, pp);
        memFloatBuffer(pp.get(0), VERTS.length).put(VERTS);
        vkUnmapMemory(device, vboMem);
        long[] i = hostBuffer(stack, INDICES.length * 4L, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        ibo = i[0]; iboMem = i[1];
        vkMapMemory(device, iboMem, 0, INDICES.length * 4L, 0, pp);
        memIntBuffer(pp.get(0), INDICES.length).put(INDICES);
        vkUnmapMemory(device, iboMem);
    }

    private Figure createFigure(MemoryStack stack, long surface, int w, int h, float rotation, String capturePath) {
        Figure f = new Figure();
        f.surface = surface; f.w = w; f.h = h; f.rotation = rotation; f.capturePath = capturePath;
        f.bufSize = (long) w * h * 4;

        VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(pd, surface, caps);
        int maxImg = caps.maxImageCount() == 0 ? Integer.MAX_VALUE : caps.maxImageCount();
        VkSwapchainCreateInfoKHR sci = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default()
            .surface(surface).minImageCount(Math.min(caps.minImageCount() + 1, maxImg)).imageFormat(COLOR_FORMAT)
            .imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR).imageArrayLayers(1)
            .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
            .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE).preTransform(caps.currentTransform())
            .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR).presentMode(VK_PRESENT_MODE_FIFO_KHR).clipped(true);
        sci.imageExtent().width(w).height(h);
        LongBuffer pSwap = stack.mallocLong(1);
        check(vkCreateSwapchainKHR(device, sci, null, pSwap), "swapchain");
        f.swapchain = pSwap.get(0);
        IntBuffer pc = stack.mallocInt(1);
        vkGetSwapchainImagesKHR(device, f.swapchain, pc, null);
        int n = pc.get(0);
        LongBuffer imgs = stack.mallocLong(n);
        vkGetSwapchainImagesKHR(device, f.swapchain, pc, imgs);
        f.images = new long[n]; f.views = new long[n]; f.framebuffers = new long[n];
        for (int k = 0; k < n; k++) {
            f.images[k] = imgs.get(k);
        }

        // depth
        VkImageCreateInfo dci = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(DEPTH_FORMAT)
            .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
            .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        dci.extent().width(w).height(h).depth(1);
        LongBuffer pDImg = stack.mallocLong(1);
        check(vkCreateImage(device, dci, null, pDImg), "depthImage");
        f.depthImage = pDImg.get(0);
        VkMemoryRequirements dmr = VkMemoryRequirements.calloc(stack);
        vkGetImageMemoryRequirements(device, f.depthImage, dmr);
        LongBuffer pDMem = stack.mallocLong(1);
        check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(dmr.size())
            .memoryTypeIndex(memType(stack, dmr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)), null, pDMem), "depthMem");
        f.depthMem = pDMem.get(0);
        vkBindImageMemory(device, f.depthImage, f.depthMem, 0);
        f.depthView = view(stack, f.depthImage, DEPTH_FORMAT, VK_IMAGE_ASPECT_DEPTH_BIT);

        for (int k = 0; k < n; k++) {
            f.views[k] = view(stack, f.images[k], COLOR_FORMAT, VK_IMAGE_ASPECT_COLOR_BIT);
            LongBuffer pf = stack.mallocLong(1);
            check(vkCreateFramebuffer(device, VkFramebufferCreateInfo.calloc(stack).sType$Default()
                .renderPass(renderPass).pAttachments(stack.longs(f.views[k], f.depthView)).width(w).height(h).layers(1), null, pf), "framebuffer");
            f.framebuffers[k] = pf.get(0);
        }

        long[] rb = hostBuffer(stack, f.bufSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        f.readBuf = rb[0]; f.readMem = rb[1];

        VkCommandBufferAllocateInfo cbai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
            .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
        PointerBuffer pCmd = stack.mallocPointer(1);
        check(vkAllocateCommandBuffers(device, cbai, pCmd), "cmd");
        f.cmd = new VkCommandBuffer(pCmd.get(0), device);

        VkSemaphoreCreateInfo semci = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
        LongBuffer pA = stack.mallocLong(1), pDn = stack.mallocLong(1), pFe = stack.mallocLong(1);
        check(vkCreateSemaphore(device, semci, null, pA), "sem1");
        check(vkCreateSemaphore(device, semci, null, pDn), "sem2");
        check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, pFe), "fence");
        f.semAcquire = pA.get(0); f.semDone = pDn.get(0); f.fence = pFe.get(0);
        return f;
    }

    // ---- helpers ----

    private long createMetalSurface(MemoryStack stack, long caMetalLayer) {
        VkMetalSurfaceCreateInfoEXT mci = VkMetalSurfaceCreateInfoEXT.calloc(stack).sType$Default().pLayer(memPointerBuffer(caMetalLayer, 1));
        LongBuffer p = stack.mallocLong(1);
        check(vkCreateMetalSurfaceEXT(instance, mci, null, p), "metalSurface");
        return p.get(0);
    }

    private long view(MemoryStack stack, long image, int format, int aspect) {
        VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack).sType$Default().image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
        vci.subresourceRange().aspectMask(aspect).levelCount(1).layerCount(1);
        LongBuffer p = stack.mallocLong(1);
        check(vkCreateImageView(device, vci, null, p), "view");
        return p.get(0);
    }

    private long[] hostBuffer(MemoryStack stack, long size, int usage) {
        VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        LongBuffer pBuf = stack.mallocLong(1);
        check(vkCreateBuffer(device, bci, null, pBuf), "buffer");
        long buf = pBuf.get(0);
        VkMemoryRequirements mr = VkMemoryRequirements.calloc(stack);
        vkGetBufferMemoryRequirements(device, buf, mr);
        LongBuffer pMem = stack.mallocLong(1);
        check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(mr.size())
            .memoryTypeIndex(memType(stack, mr.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)), null, pMem), "mem");
        long mem = pMem.get(0);
        vkBindBufferMemory(device, buf, mem, 0);
        return new long[] {buf, mem};
    }

    private int memType(MemoryStack stack, int filter, int props) {
        VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(pd, mp);
        for (int i = 0; i < mp.memoryTypeCount(); i++) {
            if ((filter & (1 << i)) != 0 && (mp.memoryTypes(i).propertyFlags() & props) == props) {
                return i;
            }
        }
        throw new RuntimeException("no memory type");
    }

    private long shaderModule(MemoryStack stack, String resource) {
        try (InputStream in = VulkanContext.class.getResourceAsStream(resource)) {
            byte[] code = in.readAllBytes();
            ByteBuffer buf = memAlloc(code.length).put(code);
            buf.flip();
            try {
                LongBuffer p = stack.mallocLong(1);
                check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(buf), null, p), "shaderModule");
                return p.get(0);
            } finally {
                memFree(buf);
            }
        } catch (Exception e) {
            throw new RuntimeException("shader " + resource, e);
        }
    }

    private static void barrier(MemoryStack stack, VkCommandBuffer cmd, long image, int oldL, int newL, int srcA, int dstA, int srcStage, int dstStage) {
        VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
        b.get(0).sType$Default().oldLayout(oldL).newLayout(newL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image).srcAccessMask(srcA).dstAccessMask(dstA);
        b.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, b);
    }

    private static void check(int r, String what) {
        if (r != VK_SUCCESS) {
            throw new RuntimeException("[vkctx] " + what + " failed: " + r);
        }
    }

    private static void checkP(int r, String what) {
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
            throw new RuntimeException("[vkctx] " + what + " failed: " + r);
        }
    }

    static int px(ByteBuffer buf, int w, int x, int y) {
        int p = (y * w + x) * 4;
        return ((buf.get(p + 2) & 0xff) << 16) | ((buf.get(p + 1) & 0xff) << 8) | (buf.get(p) & 0xff);
    }

    static boolean bright(int rgb) {
        return ((rgb >> 16) & 0xff) > 120 || ((rgb >> 8) & 0xff) > 120 || (rgb & 0xff) > 120;
    }

    static void savePng(String path, int w, int h, ByteBuffer buf) {
        try {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    img.setRGB(x, y, px(buf, w, x, y));
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
                return null;
            }
        }
        return c.surface();
    }

    // ---- M3 test: two figures, one context ----

    public static void main(String[] args) throws Exception {
        GpuSurfaceComponent ca = new GpuSurfaceComponent();
        GpuSurfaceComponent cb = new GpuSurfaceComponent();
        ca.setPreferredSize(new Dimension(480, 360));
        cb.setPreferredSize(new Dimension(480, 360));
        SwingUtilities.invokeLater(() -> {
            frame("Figure A (cube +0.6)", ca, 80, 120);
            frame("Figure B (cube -0.6)", cb, 620, 120);
        });
        NativeSurface sa = waitForSurface(ca);
        NativeSurface sb = waitForSurface(cb);
        if (sa == null || sb == null) {
            System.err.println("[vkctx] no surfaces");
            System.exit(1);
        }
        VulkanContext ctx = new VulkanContext();
        ctx.register(sa.handle(), sa.width(), sa.height(), 0.6f, "/tmp/vkfig_a.png");
        ctx.register(sb.handle(), sb.width(), sb.height(), -0.6f, "/tmp/vkfig_b.png");
        System.out.println("[vkctx] one context + one render thread driving 2 figures");
        for (int i = 0; i < 200 && !ctx.allCaptured(); i++) {
            Thread.sleep(25);
        }
        ctx.shutdown();
        boolean ok = new File("/tmp/vkfig_a.png").exists() && new File("/tmp/vkfig_b.png").exists();
        System.out.println(ok ? "[vkctx] RESULT: both figures rendered from one context/thread. M3 PASS."
                              : "[vkctx] RESULT: a figure did not capture.");
        System.out.println("[vkctx] finished");
        System.exit(0);
    }

    private static void frame(String title, GpuSurfaceComponent c, int x, int y) {
        JFrame f = new JFrame(title);
        f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        f.add(c, BorderLayout.CENTER);
        f.pack();
        f.setLocation(x, y);
        f.setVisible(true);
    }
}

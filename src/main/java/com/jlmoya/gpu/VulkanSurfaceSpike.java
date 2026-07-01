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
import java.nio.FloatBuffer;
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
 * M4 rendering de-risk — the geometry path a real {@code surf} needs, minus the scirenderer plumbing.
 * Exercises what M1-M3 did not: a per-frame geometry <b>arena</b> (host-visible vertex buffer re-filled
 * each frame, the model the DrawerVisitor emits into), <b>two topologies</b> from one arena (filled
 * triangles for the surface + lines for the axes), and per-vertex <b>colormap</b> shading by height, all
 * depth-tested. Renders a ripple surface + XYZ axes and verifies by readback. M6 reuses this arena +
 * two-pipeline design behind the Scilab canvas.
 */
public final class VulkanSurfaceSpike {

    static final String LOADER = "/Users/josemoya/VulkanSDK/1.4.350.1/macOS/lib/libvulkan.dylib";
    static final String PORTABILITY_SUBSET = "VK_KHR_portability_subset";
    static final int COLOR_FORMAT = VK_FORMAT_B8G8R8A8_UNORM;
    static final int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;
    static final int GRID = 40;                 // surface resolution
    static final int STRIDE_FLOATS = 6;         // pos(3) + color(3)

    private VkInstance instance;
    private VkPhysicalDevice pd;
    private VkDevice device;
    private VkQueue queue;
    private int qfam = 0;
    private long renderPass, triPipeline, linePipeline, pipelineLayout, commandPool;
    private long surface, swapchain, depthImage, depthMem, depthView, arenaBuf, arenaMem, readBuf, readMem;
    private long[] images, views, framebuffers;
    private VkCommandBuffer cmd;
    private long semAcquire, semDone, fence;
    private int w, h;
    private long bufSize, arenaBytes;

    // geometry laid out in the arena: [triangles ...][lines ...]
    private float[] geom;
    private int triVertCount, lineVertStart, lineVertCount;

    public static void main(String[] args) throws Exception {
        VulkanSurfaceSpike app = new VulkanSurfaceSpike();
        GpuSurfaceComponent comp = new GpuSurfaceComponent();
        comp.setPreferredSize(new Dimension(640, 480));
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Vulkan surface (arena: fills + lines)");
            f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            f.add(comp, BorderLayout.CENTER);
            f.pack();
            f.setLocation(120, 120);
            f.setVisible(true);
        });
        NativeSurface s = waitForSurface(comp);
        if (s == null || s.handle() == 0L) {
            System.err.println("[surf] no surface");
            System.exit(1);
        }
        app.run(s.handle(), s.width(), s.height());
        System.exit(0);
    }

    private void run(long caMetalLayer, int width, int height) {
        this.w = width;
        this.h = height;
        this.bufSize = (long) w * h * 4;
        Configuration.VULKAN_LIBRARY_NAME.set(System.getProperty("vk.loader", LOADER));
        buildGeometry();
        try (MemoryStack stack = stackPush()) {
            initInstance(stack);
            surface = createMetalSurface(stack, caMetalLayer);
            initDevice(stack);
            createRenderPass(stack);
            triPipeline = createPipeline(stack, VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            linePipeline = createPipeline(stack, VK_PRIMITIVE_TOPOLOGY_LINE_LIST);
            createSwapchain(stack);
            createArena(stack);
        }
        for (int frame = 0; frame < 3; frame++) {
            renderFrame(0.7f, frame == 0);
        }
        boolean ok = new File("/tmp/vksurf.png").exists();
        System.out.println(ok ? "[surf] RESULT: ripple surface + axes rendered from a per-frame arena (fills + lines). PASS."
                              : "[surf] RESULT: no output.");
        System.out.println("[surf] finished");
    }

    // ---- geometry: a ripple surface (triangles, colormapped by height) + XYZ axes (lines) ----

    private void buildGeometry() {
        float lo = -3f, hi = 3f;
        float[][] z = new float[GRID + 1][GRID + 1];
        float zmin = Float.MAX_VALUE, zmax = -Float.MAX_VALUE;
        for (int i = 0; i <= GRID; i++) {
            for (int j = 0; j <= GRID; j++) {
                float x = lo + (hi - lo) * i / GRID;
                float y = lo + (hi - lo) * j / GRID;
                float r = (float) Math.sqrt(x * x + y * y);
                float v = (float) (Math.cos(2 * r) * Math.exp(-r * 0.45)) * 1.6f;
                z[i][j] = v;
                zmin = Math.min(zmin, v);
                zmax = Math.max(zmax, v);
            }
        }
        // two triangles per cell -> 6 verts/cell
        float[] tri = new float[GRID * GRID * 6 * STRIDE_FLOATS];
        int p = 0;
        for (int i = 0; i < GRID; i++) {
            for (int j = 0; j < GRID; j++) {
                p = quad(tri, p, i, j, z, lo, hi, zmin, zmax);
            }
        }
        triVertCount = p / STRIDE_FLOATS;

        float ax = 3.6f;
        float[] lines = {
            0, 0, 0,  1, 0, 0,   ax, 0, 0,  1, 0, 0,      // X red
            0, 0, 0,  0, 1, 0,   0, ax, 0,  0, 1, 0,      // Y (height) green
            0, 0, 0,  0, 0.4f, 1,   0, 0, ax,  0, 0.4f, 1, // Z blue
        };
        lineVertCount = lines.length / STRIDE_FLOATS;
        lineVertStart = triVertCount;

        geom = new float[tri.length + lines.length];
        System.arraycopy(tri, 0, geom, 0, tri.length);
        System.arraycopy(lines, 0, geom, tri.length, lines.length);
        arenaBytes = (long) geom.length * 4;
    }

    private int quad(float[] a, int p, int i, int j, float[][] z, float lo, float hi, float zmin, float zmax) {
        int[][] corners = {{i, j}, {i + 1, j}, {i + 1, j + 1}, {i, j}, {i + 1, j + 1}, {i, j + 1}};
        for (int[] c : corners) {
            float x = lo + (hi - lo) * c[0] / GRID;
            float y = lo + (hi - lo) * c[1] / GRID;
            float hgt = z[c[0]][c[1]];
            float t = (hgt - zmin) / (zmax - zmin + 1e-6f);
            float[] col = jet(t);
            a[p++] = x;         // world X
            a[p++] = hgt;       // world Y = height (up)
            a[p++] = y;         // world Z
            a[p++] = col[0];
            a[p++] = col[1];
            a[p++] = col[2];
        }
        return p;
    }

    private static float[] jet(float t) {
        t = Math.max(0, Math.min(1, t));
        return new float[] {
            clamp(1.5f - Math.abs(4 * t - 3)),
            clamp(1.5f - Math.abs(4 * t - 2)),
            clamp(1.5f - Math.abs(4 * t - 1)),
        };
    }

    private static float clamp(float v) {
        return Math.max(0, Math.min(1, v));
    }

    // ---- render ----

    private void renderFrame(float angle, boolean capture) {
        try (MemoryStack stack = stackPush()) {
            // fill the arena (the per-frame geometry model)
            PointerBuffer pp = stack.mallocPointer(1);
            vkMapMemory(device, arenaMem, 0, arenaBytes, 0, pp);
            memFloatBuffer(pp.get(0), geom.length).put(geom);
            vkUnmapMemory(device, arenaMem);

            IntBuffer pIdx = stack.mallocInt(1);
            checkP(vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE, semAcquire, VK_NULL_HANDLE, pIdx), "acquire");
            int idx = pIdx.get(0);

            float[] proj = Mat4.perspective((float) Math.toRadians(42), (float) w / h, 0.1f, 100f);
            float[] view = Mat4.lookAt(new float[] {5.2f, 4.4f, 6.2f}, new float[] {0, 0.2f, 0}, new float[] {0, 1, 0});
            float[] mvp = Mat4.mul(Mat4.mul(proj, view), Mat4.rotateY(angle));

            vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            VkClearValue.Buffer clearv = VkClearValue.calloc(2, stack);
            clearv.get(0).color().float32(0, 0.09f).float32(1, 0.09f).float32(2, 0.12f).float32(3, 1.0f);
            clearv.get(1).depthStencil().depth(1.0f).stencil(0);
            VkRenderPassBeginInfo rpbi = VkRenderPassBeginInfo.calloc(stack).sType$Default()
                .renderPass(renderPass).framebuffer(framebuffers[idx]).pClearValues(clearv);
            rpbi.renderArea().extent().width(w).height(h);
            vkCmdBeginRenderPass(cmd, rpbi, VK_SUBPASS_CONTENTS_INLINE);

            VkViewport.Buffer vp = VkViewport.calloc(1, stack);
            vp.get(0).x(0).y(0).width(w).height(h).minDepth(0).maxDepth(1);
            vkCmdSetViewport(cmd, 0, vp);
            VkRect2D.Buffer sc = VkRect2D.calloc(1, stack);
            sc.get(0).extent().width(w).height(h);
            vkCmdSetScissor(cmd, 0, sc);
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(arenaBuf), stack.longs(0));
            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, stack.floats(mvp));

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, triPipeline);
            vkCmdDraw(cmd, triVertCount, 1, 0, 0);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, linePipeline);
            vkCmdDraw(cmd, lineVertCount, 1, lineVertStart, 0);

            vkCmdEndRenderPass(cmd);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).imageExtent().set(w, h, 1);
            vkCmdCopyImageToBuffer(cmd, images[idx], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readBuf, region);
            barrier(stack, cmd, images[idx], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_TRANSFER_READ_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            vkEndCommandBuffer(cmd);

            VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default().waitSemaphoreCount(1)
                .pWaitSemaphores(stack.longs(semAcquire)).pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                .pCommandBuffers(stack.pointers(cmd)).pSignalSemaphores(stack.longs(semDone));
            check(vkQueueSubmit(queue, si, fence), "submit");
            checkP(vkQueuePresentKHR(queue, VkPresentInfoKHR.calloc(stack).sType$Default().pWaitSemaphores(stack.longs(semDone))
                .swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(stack.ints(idx))), "present");
            vkWaitForFences(device, stack.longs(fence), true, Long.MAX_VALUE);
            vkResetFences(device, stack.longs(fence));

            if (capture) {
                PointerBuffer ppData = stack.mallocPointer(1);
                vkMapMemory(device, readMem, 0, bufSize, 0, ppData);
                savePng("/tmp/vksurf.png", w, h, memByteBuffer(ppData.get(0), (int) bufSize));
                vkUnmapMemory(device, readMem);
            }
        }
    }

    // ---- init (mirrors the proven M1-M3 setup) ----

    private void initInstance(MemoryStack stack) {
        VkApplicationInfo appi = VkApplicationInfo.calloc(stack).sType$Default().apiVersion(VK_API_VERSION_1_1);
        PointerBuffer iexts = stack.pointers(stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME),
            stack.UTF8(VK_KHR_SURFACE_EXTENSION_NAME), stack.UTF8(VK_EXT_METAL_SURFACE_EXTENSION_NAME));
        VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack).sType$Default()
            .flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR).pApplicationInfo(appi).ppEnabledExtensionNames(iexts);
        PointerBuffer p = stack.mallocPointer(1);
        check(vkCreateInstance(ici, null, p), "instance");
        instance = new VkInstance(p.get(0), ici);
    }

    private void initDevice(MemoryStack stack) {
        IntBuffer pc = stack.mallocInt(1);
        vkEnumeratePhysicalDevices(instance, pc, null);
        PointerBuffer pds = stack.mallocPointer(pc.get(0));
        vkEnumeratePhysicalDevices(instance, pc, pds);
        pd = new VkPhysicalDevice(pds.get(0), instance);
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
        VkCommandPoolCreateInfo cpi = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
            .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(qfam);
        LongBuffer pPool = stack.mallocLong(1);
        check(vkCreateCommandPool(device, cpi, null, pPool), "pool");
        commandPool = pPool.get(0);
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

    private long createPipeline(MemoryStack stack, int topology) {
        long vert = shaderModule(stack, "/shaders/cube.vert.spv");
        long frag = shaderModule(stack, "/shaders/cube.frag.spv");
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"));
        stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"));
        VkVertexInputBindingDescription.Buffer bind = VkVertexInputBindingDescription.calloc(1, stack);
        bind.get(0).binding(0).stride(STRIDE_FLOATS * 4).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        VkVertexInputAttributeDescription.Buffer attr = VkVertexInputAttributeDescription.calloc(2, stack);
        attr.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
        attr.get(1).location(1).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(12);
        VkPipelineVertexInputStateCreateInfo vin = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
            .pVertexBindingDescriptions(bind).pVertexAttributeDescriptions(attr);
        VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(topology);
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
        if (pipelineLayout == 0L) {
            VkPushConstantRange.Buffer pcr = VkPushConstantRange.calloc(1, stack);
            pcr.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(64);
            LongBuffer pLayout = stack.mallocLong(1);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pPushConstantRanges(pcr), null, pLayout), "layout");
            pipelineLayout = pLayout.get(0);
        }
        VkGraphicsPipelineCreateInfo.Buffer gp = VkGraphicsPipelineCreateInfo.calloc(1, stack);
        gp.get(0).sType$Default().pStages(stages).pVertexInputState(vin).pInputAssemblyState(ia).pViewportState(vps)
            .pDynamicState(dyn).pRasterizationState(rs).pMultisampleState(ms).pDepthStencilState(ds).pColorBlendState(cb)
            .layout(pipelineLayout).renderPass(renderPass).subpass(0);
        LongBuffer pPipe = stack.mallocLong(1);
        check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, gp, null, pPipe), "pipeline");
        vkDestroyShaderModule(device, vert, null);
        vkDestroyShaderModule(device, frag, null);
        return pPipe.get(0);
    }

    private void createSwapchain(MemoryStack stack) {
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
        swapchain = pSwap.get(0);
        IntBuffer pc = stack.mallocInt(1);
        vkGetSwapchainImagesKHR(device, swapchain, pc, null);
        int n = pc.get(0);
        LongBuffer imgs = stack.mallocLong(n);
        vkGetSwapchainImagesKHR(device, swapchain, pc, imgs);
        images = new long[n];
        views = new long[n];
        framebuffers = new long[n];
        for (int k = 0; k < n; k++) {
            images[k] = imgs.get(k);
        }
        VkImageCreateInfo dci = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(DEPTH_FORMAT)
            .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
            .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        dci.extent().width(w).height(h).depth(1);
        LongBuffer pDImg = stack.mallocLong(1);
        check(vkCreateImage(device, dci, null, pDImg), "depthImage");
        depthImage = pDImg.get(0);
        VkMemoryRequirements dmr = VkMemoryRequirements.calloc(stack);
        vkGetImageMemoryRequirements(device, depthImage, dmr);
        LongBuffer pDMem = stack.mallocLong(1);
        check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(dmr.size())
            .memoryTypeIndex(memType(stack, dmr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)), null, pDMem), "depthMem");
        depthMem = pDMem.get(0);
        vkBindImageMemory(device, depthImage, depthMem, 0);
        depthView = view(stack, depthImage, DEPTH_FORMAT, VK_IMAGE_ASPECT_DEPTH_BIT);
        for (int k = 0; k < n; k++) {
            views[k] = view(stack, images[k], COLOR_FORMAT, VK_IMAGE_ASPECT_COLOR_BIT);
            LongBuffer pf = stack.mallocLong(1);
            check(vkCreateFramebuffer(device, VkFramebufferCreateInfo.calloc(stack).sType$Default()
                .renderPass(renderPass).pAttachments(stack.longs(views[k], depthView)).width(w).height(h).layers(1), null, pf), "framebuffer");
            framebuffers[k] = pf.get(0);
        }
        VkCommandBufferAllocateInfo cbai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
            .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
        PointerBuffer pCmd = stack.mallocPointer(1);
        check(vkAllocateCommandBuffers(device, cbai, pCmd), "cmd");
        cmd = new VkCommandBuffer(pCmd.get(0), device);
        VkSemaphoreCreateInfo semci = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
        LongBuffer pA = stack.mallocLong(1), pDn = stack.mallocLong(1), pFe = stack.mallocLong(1);
        check(vkCreateSemaphore(device, semci, null, pA), "sem1");
        check(vkCreateSemaphore(device, semci, null, pDn), "sem2");
        check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, pFe), "fence");
        semAcquire = pA.get(0);
        semDone = pDn.get(0);
        fence = pFe.get(0);
        long[] rb = hostBuffer(stack, bufSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        readBuf = rb[0];
        readMem = rb[1];
    }

    private void createArena(MemoryStack stack) {
        long[] a = hostBuffer(stack, arenaBytes, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        arenaBuf = a[0];
        arenaMem = a[1];
    }

    // ---- helpers (same proven set as the context) ----

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
        try (InputStream in = VulkanSurfaceSpike.class.getResourceAsStream(resource)) {
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
            throw new RuntimeException("[surf] " + what + " failed: " + r);
        }
    }

    private static void checkP(int r, String what) {
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
            throw new RuntimeException("[surf] " + what + " failed: " + r);
        }
    }

    static int px(ByteBuffer buf, int w, int x, int y) {
        int p = (y * w + x) * 4;
        return ((buf.get(p + 2) & 0xff) << 16) | ((buf.get(p + 1) & 0xff) << 8) | (buf.get(p) & 0xff);
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
}

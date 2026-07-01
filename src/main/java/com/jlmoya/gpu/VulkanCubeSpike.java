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

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTMetalSurface.*;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;

/**
 * M2 — geometry + MVP + depth. Real vertex/index buffers (a coloured cube), an MVP push constant, and
 * a depth attachment with depth testing, drawn indexed and read back to a PNG. Pass = the PNG shows a
 * 3-D cube (multiple faces, correct occlusion). Auto-exits. Proves the geometry path the DrawerVisitor
 * will feed.
 */
public final class VulkanCubeSpike {

    static final String LOADER = "/Users/josemoya/VulkanSDK/1.4.350.1/macOS/lib/libvulkan.dylib";
    static final String PORTABILITY_SUBSET = "VK_KHR_portability_subset";
    static final int DEPTH_FORMAT = VK_FORMAT_D32_SFLOAT;

    // 8 cube corners: position (x,y,z) + colour (r,g,b).
    static final float[] VERTS = {
        -1, -1, -1,  0, 0, 0,
         1, -1, -1,  1, 0, 0,
         1,  1, -1,  1, 1, 0,
        -1,  1, -1,  0, 1, 0,
        -1, -1,  1,  0, 0, 1,
         1, -1,  1,  1, 0, 1,
         1,  1,  1,  1, 1, 1,
        -1,  1,  1,  0, 1, 1,
    };
    static final int[] INDICES = {
        0, 1, 2, 0, 2, 3,   // front  (z=-1)
        4, 6, 5, 4, 7, 6,   // back   (z=+1)
        0, 3, 7, 0, 7, 4,   // left   (x=-1)
        1, 5, 6, 1, 6, 2,   // right  (x=+1)
        3, 2, 6, 3, 6, 7,   // top    (y=+1)
        0, 4, 5, 0, 5, 1,   // bottom (y=-1)
    };

    public static void main(String[] args) throws Exception {
        Configuration.VULKAN_LIBRARY_NAME.set(System.getProperty("vk.loader", LOADER));
        final GpuSurfaceComponent c = new GpuSurfaceComponent();
        c.setPreferredSize(new Dimension(480, 360));
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("vk cube (M2, auto-closes)");
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
        }, "vk-cube");
        t.setDaemon(true);
        t.start();
        t.join(20000);
        System.out.println("[vkq] finished");
        System.exit(0);
    }

    static void run(GpuSurfaceComponent comp) {
        final NativeSurface ns = waitForSurface(comp);
        if (ns == null || ns.handle() == 0L) {
            System.err.println("[vkq] no surface");
            return;
        }
        final long caLayer = ns.handle();

        try (MemoryStack stack = stackPush()) {
            // ---- instance / surface / device / swapchain / views (proven) ----
            VkApplicationInfo appInfo = VkApplicationInfo.calloc(stack).sType$Default().apiVersion(VK_API_VERSION_1_1);
            PointerBuffer iexts = stack.pointers(stack.UTF8(VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME),
                stack.UTF8(VK_KHR_SURFACE_EXTENSION_NAME), stack.UTF8(VK_EXT_METAL_SURFACE_EXTENSION_NAME));
            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack).sType$Default()
                .flags(VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR).pApplicationInfo(appInfo).ppEnabledExtensionNames(iexts);
            PointerBuffer pInst = stack.mallocPointer(1);
            check(vkCreateInstance(ici, null, pInst), "instance");
            VkInstance instance = new VkInstance(pInst.get(0), ici);

            VkMetalSurfaceCreateInfoEXT mci = VkMetalSurfaceCreateInfoEXT.calloc(stack).sType$Default().pLayer(memPointerBuffer(caLayer, 1));
            LongBuffer pSurf = stack.mallocLong(1);
            check(vkCreateMetalSurfaceEXT(instance, mci, null, pSurf), "surface");
            long surface = pSurf.get(0);

            IntBuffer pc = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, pc, null);
            PointerBuffer pds = stack.mallocPointer(pc.get(0));
            vkEnumeratePhysicalDevices(instance, pc, pds);
            VkPhysicalDevice pd = new VkPhysicalDevice(pds.get(0), instance);
            int qfam = 0;

            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, stack);
            qci.get(0).sType$Default().queueFamilyIndex(qfam).pQueuePriorities(stack.floats(1.0f));
            PointerBuffer dexts = stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME), stack.UTF8(PORTABILITY_SUBSET));
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(qci).ppEnabledExtensionNames(dexts);
            PointerBuffer pDev = stack.mallocPointer(1);
            check(vkCreateDevice(pd, dci, null, pDev), "device");
            VkDevice device = new VkDevice(pDev.get(0), pd, dci);
            PointerBuffer pQ = stack.mallocPointer(1);
            vkGetDeviceQueue(device, qfam, 0, pQ);
            VkQueue queue = new VkQueue(pQ.get(0), device);

            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(pd, surface, caps);
            int w = caps.currentExtent().width(), h = caps.currentExtent().height();
            if (w == 0xFFFFFFFF) { w = 480; h = 360; }
            int fmt = VK_FORMAT_B8G8R8A8_UNORM;
            int maxImg = caps.maxImageCount() == 0 ? Integer.MAX_VALUE : caps.maxImageCount();
            VkSwapchainCreateInfoKHR sci = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default()
                .surface(surface).minImageCount(Math.min(caps.minImageCount() + 1, maxImg)).imageFormat(fmt)
                .imageColorSpace(VK_COLOR_SPACE_SRGB_NONLINEAR_KHR).imageArrayLayers(1)
                .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT)
                .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE).preTransform(caps.currentTransform())
                .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR).presentMode(VK_PRESENT_MODE_FIFO_KHR).clipped(true);
            sci.imageExtent().width(w).height(h);
            LongBuffer pSwap = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(device, sci, null, pSwap), "swapchain");
            long swapchain = pSwap.get(0);
            vkGetSwapchainImagesKHR(device, swapchain, pc, null);
            int nImg = pc.get(0);
            LongBuffer images = stack.mallocLong(nImg);
            vkGetSwapchainImagesKHR(device, swapchain, pc, images);
            long[] views = new long[nImg];
            for (int i = 0; i < nImg; i++) {
                VkImageViewCreateInfo vci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(images.get(i)).viewType(VK_IMAGE_VIEW_TYPE_2D).format(fmt);
                vci.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
                LongBuffer pv = stack.mallocLong(1);
                check(vkCreateImageView(device, vci, null, pv), "view");
                views[i] = pv.get(0);
            }

            // ---- depth image + view ----
            VkImageCreateInfo dImgCi = VkImageCreateInfo.calloc(stack).sType$Default()
                .imageType(VK_IMAGE_TYPE_2D).format(DEPTH_FORMAT).mipLevels(1).arrayLayers(1)
                .samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            dImgCi.extent().width(w).height(h).depth(1);
            LongBuffer pDImg = stack.mallocLong(1);
            check(vkCreateImage(device, dImgCi, null, pDImg), "depthImage");
            long depthImage = pDImg.get(0);
            VkMemoryRequirements dmr = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, depthImage, dmr);
            VkMemoryAllocateInfo dmai = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(dmr.size())
                .memoryTypeIndex(findMemoryType(pd, stack, dmr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            LongBuffer pDMem = stack.mallocLong(1);
            check(vkAllocateMemory(device, dmai, null, pDMem), "depthMem");
            long depthMem = pDMem.get(0);
            vkBindImageMemory(device, depthImage, depthMem, 0);
            VkImageViewCreateInfo dvci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                .image(depthImage).viewType(VK_IMAGE_VIEW_TYPE_2D).format(DEPTH_FORMAT);
            dvci.subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT).levelCount(1).layerCount(1);
            LongBuffer pDView = stack.mallocLong(1);
            check(vkCreateImageView(device, dvci, null, pDView), "depthView");
            long depthView = pDView.get(0);

            // ---- render pass (color + depth) ----
            VkAttachmentDescription.Buffer att = VkAttachmentDescription.calloc(2, stack);
            att.get(0).format(fmt).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            att.get(1).format(DEPTH_FORMAT).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                .storeOp(VK_ATTACHMENT_STORE_OP_DONT_CARE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
            colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkAttachmentReference depthRef = VkAttachmentReference.calloc(stack).attachment(1).layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer sub = VkSubpassDescription.calloc(1, stack);
            sub.get(0).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1)
                .pColorAttachments(colorRef).pDepthStencilAttachment(depthRef);
            VkSubpassDependency.Buffer dep = VkSubpassDependency.calloc(1, stack);
            dep.get(0).srcSubpass(VK_SUBPASS_EXTERNAL).dstSubpass(0)
                .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT)
                .srcAccessMask(0).dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
            VkRenderPassCreateInfo rpci = VkRenderPassCreateInfo.calloc(stack).sType$Default().pAttachments(att).pSubpasses(sub).pDependencies(dep);
            LongBuffer pRp = stack.mallocLong(1);
            check(vkCreateRenderPass(device, rpci, null, pRp), "renderPass");
            long renderPass = pRp.get(0);

            long[] fbos = new long[nImg];
            for (int i = 0; i < nImg; i++) {
                VkFramebufferCreateInfo fci = VkFramebufferCreateInfo.calloc(stack).sType$Default()
                    .renderPass(renderPass).pAttachments(stack.longs(views[i], depthView)).width(w).height(h).layers(1);
                LongBuffer pf = stack.mallocLong(1);
                check(vkCreateFramebuffer(device, fci, null, pf), "framebuffer");
                fbos[i] = pf.get(0);
            }

            // ---- vertex + index buffers (host-visible) ----
            long[] vb = createHostBuffer(pd, device, stack, VERTS.length * 4L, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            PointerBuffer pp = stack.mallocPointer(1);
            vkMapMemory(device, vb[1], 0, VERTS.length * 4L, 0, pp);
            memFloatBuffer(pp.get(0), VERTS.length).put(VERTS);
            vkUnmapMemory(device, vb[1]);
            long[] ib = createHostBuffer(pd, device, stack, INDICES.length * 4L, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            vkMapMemory(device, ib[1], 0, INDICES.length * 4L, 0, pp);
            memIntBuffer(pp.get(0), INDICES.length).put(INDICES);
            vkUnmapMemory(device, ib[1]);

            // ---- pipeline (vertex input + push constant + depth test) ----
            long vert = shaderModule(device, stack, "/shaders/cube.vert.spv");
            long frag = shaderModule(device, stack, "/shaders/cube.frag.spv");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"));
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"));

            VkVertexInputBindingDescription.Buffer bind = VkVertexInputBindingDescription.calloc(1, stack);
            bind.get(0).binding(0).stride(6 * 4).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
            VkVertexInputAttributeDescription.Buffer attr = VkVertexInputAttributeDescription.calloc(2, stack);
            attr.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0);
            attr.get(1).location(1).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(3 * 4);
            VkPipelineVertexInputStateCreateInfo vin = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
                .pVertexBindingDescriptions(bind).pVertexAttributeDescriptions(attr);
            VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
            VkViewport.Buffer vp = VkViewport.calloc(1, stack);
            vp.get(0).x(0).y(0).width(w).height(h).minDepth(0).maxDepth(1);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.get(0).extent().width(w).height(h);
            VkPipelineViewportStateCreateInfo vps = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                .pViewports(vp).viewportCount(1).pScissors(scissor).scissorCount(1);
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
            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pPushConstantRanges(pcr);
            LongBuffer pLayout = stack.mallocLong(1);
            check(vkCreatePipelineLayout(device, plci, null, pLayout), "layout");
            long layout = pLayout.get(0);
            VkGraphicsPipelineCreateInfo.Buffer gp = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            gp.get(0).sType$Default().pStages(stages).pVertexInputState(vin).pInputAssemblyState(ia)
                .pViewportState(vps).pRasterizationState(rs).pMultisampleState(ms).pDepthStencilState(ds)
                .pColorBlendState(cb).layout(layout).renderPass(renderPass).subpass(0);
            LongBuffer pPipe = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, gp, null, pPipe), "pipeline");
            long pipeline = pPipe.get(0);

            // ---- MVP ----
            float[] proj = Mat4.perspective((float) Math.toRadians(45), (float) w / h, 0.1f, 100f);
            float[] view = Mat4.lookAt(new float[] {2.6f, 2.4f, 4.0f}, new float[] {0, 0, 0}, new float[] {0, 1, 0});
            float[] model = Mat4.rotateY(0.6f);
            float[] mvp = Mat4.mul(Mat4.mul(proj, view), model);

            // ---- command / sync / readback ----
            VkCommandPoolCreateInfo cpi = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(qfam);
            LongBuffer pPool = stack.mallocLong(1);
            check(vkCreateCommandPool(device, cpi, null, pPool), "pool");
            long pool = pPool.get(0);
            VkCommandBufferAllocateInfo cbai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(pool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, cbai, pCmd), "cmd");
            VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), device);

            long bufSize = (long) w * h * 4;
            long[] rb = createHostBuffer(pd, device, stack, bufSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT);
            long readBuf = rb[0], readMem = rb[1];

            VkSemaphoreCreateInfo semci = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer pA = stack.mallocLong(1), pDn = stack.mallocLong(1), pFe = stack.mallocLong(1);
            check(vkCreateSemaphore(device, semci, null, pA), "sem1");
            check(vkCreateSemaphore(device, semci, null, pDn), "sem2");
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default(), null, pFe), "fence");
            long semAcquire = pA.get(0), semDone = pDn.get(0), fence = pFe.get(0);

            // ---- draw ----
            IntBuffer pIdx = stack.mallocInt(1);
            checkP(vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE, semAcquire, VK_NULL_HANDLE, pIdx), "acquire");
            int idx = pIdx.get(0);

            vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            VkClearValue.Buffer clearv = VkClearValue.calloc(2, stack);
            clearv.get(0).color().float32(0, 0.10f).float32(1, 0.10f).float32(2, 0.13f).float32(3, 1.0f);
            clearv.get(1).depthStencil().depth(1.0f).stencil(0);
            VkRenderPassBeginInfo rpbi = VkRenderPassBeginInfo.calloc(stack).sType$Default()
                .renderPass(renderPass).framebuffer(fbos[idx]).pClearValues(clearv);
            rpbi.renderArea().extent().width(w).height(h);
            vkCmdBeginRenderPass(cmd, rpbi, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(vb[0]), stack.longs(0));
            vkCmdBindIndexBuffer(cmd, ib[0], 0, VK_INDEX_TYPE_UINT32);
            vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_VERTEX_BIT, 0, stack.floats(mvp));
            vkCmdDrawIndexed(cmd, INDICES.length, 1, 0, 0, 0);
            vkCmdEndRenderPass(cmd);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).imageExtent().set(w, h, 1);
            vkCmdCopyImageToBuffer(cmd, images.get(idx), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readBuf, region);
            barrier(stack, cmd, images.get(idx), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_TRANSFER_READ_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
            vkEndCommandBuffer(cmd);

            VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default().waitSemaphoreCount(1)
                .pWaitSemaphores(stack.longs(semAcquire)).pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                .pCommandBuffers(stack.pointers(cmd)).pSignalSemaphores(stack.longs(semDone));
            check(vkQueueSubmit(queue, si, fence), "submit");
            checkP(vkQueuePresentKHR(queue, VkPresentInfoKHR.calloc(stack).sType$Default().pWaitSemaphores(stack.longs(semDone))
                .swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(stack.ints(idx))), "present");

            vkWaitForFences(device, stack.longs(fence), true, Long.MAX_VALUE);
            PointerBuffer ppData = stack.mallocPointer(1);
            vkMapMemory(device, readMem, 0, bufSize, 0, ppData);
            ByteBuffer pix = memByteBuffer(ppData.get(0), (int) bufSize);
            savePng("/tmp/vkcube.png", w, h, pix);
            int center = px(pix, w, w / 2, h / 2), corner = px(pix, w, 4, 4);
            System.out.println("[vkq] center rgb=" + rgb(center) + " (cube face), corner rgb=" + rgb(corner) + " (clear)");
            boolean drew = bright(center) && !bright(corner);
            System.out.println(drew ? "[vkq] RESULT: geometry + MVP + depth drew the cube. M2 PASS."
                                    : "[vkq] RESULT: unexpected pixels — check geometry/MVP.");
            vkUnmapMemory(device, readMem);

            vkDeviceWaitIdle(device);
            System.out.println("[vkq] (teardown skipped for brevity — process exits)");
        }
    }

    static long[] createHostBuffer(VkPhysicalDevice pd, VkDevice device, MemoryStack stack, long size, int usage) {
        VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        LongBuffer pBuf = stack.mallocLong(1);
        check(vkCreateBuffer(device, bci, null, pBuf), "createBuffer");
        long buf = pBuf.get(0);
        VkMemoryRequirements mr = VkMemoryRequirements.calloc(stack);
        vkGetBufferMemoryRequirements(device, buf, mr);
        VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(mr.size())
            .memoryTypeIndex(findMemoryType(pd, stack, mr.memoryTypeBits(), VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT));
        LongBuffer pMem = stack.mallocLong(1);
        check(vkAllocateMemory(device, mai, null, pMem), "allocMemory");
        long mem = pMem.get(0);
        vkBindBufferMemory(device, buf, mem, 0);
        return new long[] {buf, mem};
    }

    static long shaderModule(VkDevice device, MemoryStack stack, String resource) {
        try (InputStream in = VulkanCubeSpike.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new RuntimeException("shader not found: " + resource);
            }
            byte[] code = in.readAllBytes();
            ByteBuffer buf = memAlloc(code.length).put(code);
            buf.flip();
            try {
                LongBuffer p = stack.mallocLong(1);
                check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(buf), null, p), "shaderModule " + resource);
                return p.get(0);
            } finally {
                memFree(buf);
            }
        } catch (Exception e) {
            throw new RuntimeException("loadShader " + resource + ": " + e, e);
        }
    }

    static void barrier(MemoryStack stack, VkCommandBuffer cmd, long image, int oldL, int newL, int srcA, int dstA, int srcStage, int dstStage) {
        VkImageMemoryBarrier.Buffer b = VkImageMemoryBarrier.calloc(1, stack);
        b.get(0).sType$Default().oldLayout(oldL).newLayout(newL).srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
            .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).image(image).srcAccessMask(srcA).dstAccessMask(dstA);
        b.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
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
        throw new RuntimeException("no memory type for props 0x" + Integer.toHexString(props));
    }

    static void check(int r, String what) {
        if (r != VK_SUCCESS) {
            throw new RuntimeException("[vkq] " + what + " failed: " + r);
        }
    }

    static void checkP(int r, String what) {
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
            throw new RuntimeException("[vkq] " + what + " failed: " + r);
        }
    }

    static int px(ByteBuffer buf, int w, int x, int y) {
        int p = (y * w + x) * 4;
        return ((buf.get(p + 2) & 0xff) << 16) | ((buf.get(p + 1) & 0xff) << 8) | (buf.get(p) & 0xff);
    }

    static boolean bright(int rgb) {
        return ((rgb >> 16) & 0xff) > 120 || ((rgb >> 8) & 0xff) > 120 || (rgb & 0xff) > 120;
    }

    static String rgb(int c) {
        return "(" + ((c >> 16) & 0xff) + "," + ((c >> 8) & 0xff) + "," + (c & 0xff) + ")";
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
            System.out.println("[vkq] wrote " + path);
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

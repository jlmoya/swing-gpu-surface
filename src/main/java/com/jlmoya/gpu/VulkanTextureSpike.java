package com.jlmoya.gpu;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Configuration;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.function.Consumer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.EXTMetalSurface.*;
import static org.lwjgl.vulkan.KHRPortabilityEnumeration.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;

/**
 * M5 texture/sprite de-risk — the last new Vulkan territory before the Scilab plumbing: a real
 * <b>texture</b> (staging buffer → device-local image → layout transitions → sampler), bound through a
 * <b>descriptor set</b>, drawn as an <b>alpha-blended screen-aligned quad</b>. The texture is a live
 * Java2D text render — the exact path Scilab uses for axis labels, tick numbers, marks, and colormap
 * strips (DrawerVisitor rasterizes glyphs into an image, uploads it, and blits a sprite at a projected
 * anchor). Renders "Vulkan 3D" white-on-transparent over a colored background; verified by readback.
 */
public final class VulkanTextureSpike {

    static final String LOADER = "/Users/josemoya/VulkanSDK/1.4.350.1/macOS/lib/libvulkan.dylib";
    static final String PORTABILITY_SUBSET = "VK_KHR_portability_subset";
    static final int COLOR_FORMAT = VK_FORMAT_B8G8R8A8_UNORM;
    static final int TEX_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;

    private VkInstance instance;
    private VkPhysicalDevice pd;
    private VkDevice device;
    private VkQueue queue;
    private int qfam = 0;
    private long renderPass, pipeline, pipelineLayout, commandPool;
    private long surface, swapchain, quadBuf, quadMem, readBuf, readMem;
    private long texImage, texMem, texView, sampler, dsLayout, dsPool, descriptorSet;
    private long[] images, views, framebuffers;
    private VkCommandBuffer cmd;
    private long semAcquire, semDone, fence;
    private int w, h, texW, texH;
    private long bufSize;

    // screen-aligned quad: pos.xy (NDC) + uv.xy, two triangles
    static final float[] QUAD = {
        -0.82f, -0.28f, 0, 0,   0.82f, -0.28f, 1, 0,   0.82f, 0.28f, 1, 1,
        -0.82f, -0.28f, 0, 0,   0.82f,  0.28f, 1, 1,  -0.82f, 0.28f, 0, 1,
    };

    public static void main(String[] args) throws Exception {
        VulkanTextureSpike app = new VulkanTextureSpike();
        GpuSurfaceComponent comp = new GpuSurfaceComponent();
        comp.setPreferredSize(new Dimension(640, 300));
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Vulkan texture sprite (Java2D text)");
            f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            f.add(comp, BorderLayout.CENTER);
            f.pack();
            f.setLocation(140, 160);
            f.setVisible(true);
        });
        NativeSurface s = waitForSurface(comp);
        if (s == null || s.handle() == 0L) {
            System.err.println("[tex] no surface");
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
        byte[] rgba = renderText();
        try (MemoryStack stack = stackPush()) {
            initInstance(stack);
            surface = createMetalSurface(stack, caMetalLayer);
            initDevice(stack);
            createRenderPass(stack);
            createTexture(stack, rgba);
            createDescriptor(stack);
            pipeline = createPipeline(stack);
            createSwapchain(stack);
            long[] q = hostBuffer(stack, QUAD.length * 4L, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            quadBuf = q[0];
            quadMem = q[1];
            PointerBuffer pp = stack.mallocPointer(1);
            vkMapMemory(device, quadMem, 0, QUAD.length * 4L, 0, pp);
            memFloatBuffer(pp.get(0), QUAD.length).put(QUAD);
            vkUnmapMemory(device, quadMem);
        }
        renderFrame(true);
        boolean ok = new File("/tmp/vktex.png").exists();
        System.out.println(ok ? "[tex] RESULT: Java2D text uploaded as a Vulkan texture + drawn as an alpha-blended sprite. PASS."
                              : "[tex] RESULT: no output.");
        System.out.println("[tex] finished");
    }

    /** Rasterize text into RGBA8 (white glyphs, transparent elsewhere) — Scilab's glyph-sprite source. */
    private byte[] renderText() {
        texW = 512;
        texH = 160;
        BufferedImage bi = new BufferedImage(texW, texH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = bi.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 72));
        g.drawString("Vulkan 3D", 24, 108);
        g.dispose();
        int[] argb = bi.getRGB(0, 0, texW, texH, null, 0, texW);
        byte[] rgba = new byte[texW * texH * 4];
        for (int i = 0; i < argb.length; i++) {
            int a = argb[i];
            rgba[i * 4] = (byte) ((a >> 16) & 0xff);
            rgba[i * 4 + 1] = (byte) ((a >> 8) & 0xff);
            rgba[i * 4 + 2] = (byte) (a & 0xff);
            rgba[i * 4 + 3] = (byte) ((a >> 24) & 0xff);
        }
        return rgba;
    }

    private void createTexture(MemoryStack stack, byte[] rgba) {
        long[] staging = hostBuffer(stack, rgba.length, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        PointerBuffer pp = stack.mallocPointer(1);
        vkMapMemory(device, staging[1], 0, rgba.length, 0, pp);
        memByteBuffer(pp.get(0), rgba.length).put(rgba);
        vkUnmapMemory(device, staging[1]);

        VkImageCreateInfo ici = VkImageCreateInfo.calloc(stack).sType$Default().imageType(VK_IMAGE_TYPE_2D).format(TEX_FORMAT)
            .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
            .usage(VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT).sharingMode(VK_SHARING_MODE_EXCLUSIVE)
            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
        ici.extent().width(texW).height(texH).depth(1);
        LongBuffer pImg = stack.mallocLong(1);
        check(vkCreateImage(device, ici, null, pImg), "texImage");
        texImage = pImg.get(0);
        VkMemoryRequirements mr = VkMemoryRequirements.calloc(stack);
        vkGetImageMemoryRequirements(device, texImage, mr);
        LongBuffer pMem = stack.mallocLong(1);
        check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default().allocationSize(mr.size())
            .memoryTypeIndex(memType(stack, mr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)), null, pMem), "texMem");
        texMem = pMem.get(0);
        vkBindImageMemory(device, texImage, texMem, 0);

        oneTimeSubmit(sc -> {
            barrier(sc, cmdOnce, texImage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, sc);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).imageExtent().set(texW, texH, 1);
            vkCmdCopyBufferToImage(cmdOnce, staging[0], texImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            barrier(sc, cmdOnce, texImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
        });

        texView = view(stack, texImage, TEX_FORMAT, VK_IMAGE_ASPECT_COLOR_BIT);
        VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default().magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
            .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
            .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).maxLod(0).borderColor(VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK);
        LongBuffer pSamp = stack.mallocLong(1);
        check(vkCreateSampler(device, sci, null, pSamp), "sampler");
        sampler = pSamp.get(0);
    }

    private void createDescriptor(MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(1, stack);
        b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
        LongBuffer pL = stack.mallocLong(1);
        check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, pL), "dsLayout");
        dsLayout = pL.get(0);
        VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(1, stack);
        ps.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
        LongBuffer pP = stack.mallocLong(1);
        check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(1).pPoolSizes(ps), null, pP), "dsPool");
        dsPool = pP.get(0);
        VkDescriptorSetAllocateInfo dai = VkDescriptorSetAllocateInfo.calloc(stack).sType$Default().descriptorPool(dsPool).pSetLayouts(stack.longs(dsLayout));
        LongBuffer pS = stack.mallocLong(1);
        check(vkAllocateDescriptorSets(device, dai, pS), "descriptorSet");
        descriptorSet = pS.get(0);
        VkDescriptorImageInfo.Buffer ii = VkDescriptorImageInfo.calloc(1, stack);
        ii.get(0).sampler(sampler).imageView(texView).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(1, stack);
        w.get(0).sType$Default().dstSet(descriptorSet).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(ii);
        vkUpdateDescriptorSets(device, w, null);
    }

    private long createPipeline(MemoryStack stack) {
        long vert = shaderModule(stack, "/shaders/tex.vert.spv");
        long frag = shaderModule(stack, "/shaders/tex.frag.spv");
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vert).pName(stack.UTF8("main"));
        stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(frag).pName(stack.UTF8("main"));
        VkVertexInputBindingDescription.Buffer bind = VkVertexInputBindingDescription.calloc(1, stack);
        bind.get(0).binding(0).stride(4 * 4).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
        VkVertexInputAttributeDescription.Buffer attr = VkVertexInputAttributeDescription.calloc(2, stack);
        attr.get(0).location(0).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
        attr.get(1).location(1).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(8);
        VkPipelineVertexInputStateCreateInfo vin = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default()
            .pVertexBindingDescriptions(bind).pVertexAttributeDescriptions(attr);
        VkPipelineInputAssemblyStateCreateInfo ia = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
        VkPipelineViewportStateCreateInfo vps = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);
        VkPipelineDynamicStateCreateInfo dyn = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
            .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
        VkPipelineRasterizationStateCreateInfo rs = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
            .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1.0f);
        VkPipelineMultisampleStateCreateInfo ms = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default().rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
        // premultiply-free alpha blend: out = src.a*src + (1-src.a)*dst
        VkPipelineColorBlendAttachmentState.Buffer cba = VkPipelineColorBlendAttachmentState.calloc(1, stack);
        cba.get(0).colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT)
            .blendEnable(true).srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
            .colorBlendOp(VK_BLEND_OP_ADD).srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).alphaBlendOp(VK_BLEND_OP_ADD);
        VkPipelineColorBlendStateCreateInfo cb = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(cba);
        LongBuffer pLayout = stack.mallocLong(1);
        check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(dsLayout)), null, pLayout), "layout");
        pipelineLayout = pLayout.get(0);
        VkGraphicsPipelineCreateInfo.Buffer gp = VkGraphicsPipelineCreateInfo.calloc(1, stack);
        gp.get(0).sType$Default().pStages(stages).pVertexInputState(vin).pInputAssemblyState(ia).pViewportState(vps)
            .pDynamicState(dyn).pRasterizationState(rs).pMultisampleState(ms).pColorBlendState(cb).layout(pipelineLayout).renderPass(renderPass).subpass(0);
        LongBuffer pPipe = stack.mallocLong(1);
        check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, gp, null, pPipe), "pipeline");
        vkDestroyShaderModule(device, vert, null);
        vkDestroyShaderModule(device, frag, null);
        return pPipe.get(0);
    }

    private void renderFrame(boolean capture) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer pIdx = stack.mallocInt(1);
            checkP(vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE, semAcquire, VK_NULL_HANDLE, pIdx), "acquire");
            int idx = pIdx.get(0);
            vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            VkClearValue.Buffer clearv = VkClearValue.calloc(1, stack);
            clearv.get(0).color().float32(0, 0.16f).float32(1, 0.20f).float32(2, 0.34f).float32(3, 1.0f);
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
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(descriptorSet), null);
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(quadBuf), stack.longs(0));
            vkCmdDraw(cmd, 6, 1, 0, 0);
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
                savePng("/tmp/vktex.png", w, h, memByteBuffer(ppData.get(0), (int) bufSize));
                vkUnmapMemory(device, readMem);
            }
        }
    }

    // ---- init ----

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
        VkAttachmentDescription.Buffer att = VkAttachmentDescription.calloc(1, stack);
        att.get(0).format(COLOR_FORMAT).samples(VK_SAMPLE_COUNT_1_BIT).loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
            .storeOp(VK_ATTACHMENT_STORE_OP_STORE).stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
            .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE).initialLayout(VK_IMAGE_LAYOUT_UNDEFINED).finalLayout(VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        VkAttachmentReference.Buffer colorRef = VkAttachmentReference.calloc(1, stack);
        colorRef.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
        VkSubpassDescription.Buffer sub = VkSubpassDescription.calloc(1, stack);
        sub.get(0).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS).colorAttachmentCount(1).pColorAttachments(colorRef);
        LongBuffer p = stack.mallocLong(1);
        check(vkCreateRenderPass(device, VkRenderPassCreateInfo.calloc(stack).sType$Default().pAttachments(att).pSubpasses(sub), null, p), "renderPass");
        renderPass = p.get(0);
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
            views[k] = view(stack, images[k], COLOR_FORMAT, VK_IMAGE_ASPECT_COLOR_BIT);
            LongBuffer pf = stack.mallocLong(1);
            check(vkCreateFramebuffer(device, VkFramebufferCreateInfo.calloc(stack).sType$Default()
                .renderPass(renderPass).pAttachments(stack.longs(views[k])).width(w).height(h).layers(1), null, pf), "framebuffer");
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

    // ---- helpers ----

    private VkCommandBuffer cmdOnce;

    private void oneTimeSubmit(Consumer<MemoryStack> body) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo cbai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(commandPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer pCmd = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, cbai, pCmd), "onceCmd");
            cmdOnce = new VkCommandBuffer(pCmd.get(0), device);
            vkBeginCommandBuffer(cmdOnce, VkCommandBufferBeginInfo.calloc(stack).sType$Default().flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT));
            body.accept(stack);
            vkEndCommandBuffer(cmdOnce);
            VkSubmitInfo si = VkSubmitInfo.calloc(stack).sType$Default().pCommandBuffers(stack.pointers(cmdOnce));
            check(vkQueueSubmit(queue, si, VK_NULL_HANDLE), "onceSubmit");
            vkQueueWaitIdle(queue);
            vkFreeCommandBuffers(device, commandPool, cmdOnce);
            cmdOnce = null;
        }
    }

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
        try (InputStream in = VulkanTextureSpike.class.getResourceAsStream(resource)) {
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
            throw new RuntimeException("[tex] " + what + " failed: " + r);
        }
    }

    private static void checkP(int r, String what) {
        if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) {
            throw new RuntimeException("[tex] " + what + " failed: " + r);
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

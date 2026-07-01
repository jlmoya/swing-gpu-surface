package com.jlmoya.gpu.macos;

import com.jlmoya.gpu.NativeSurface;

import org.lwjgl.awt.AWT;
import org.lwjgl.system.macosx.ObjCRuntime;

import java.awt.AWTException;
import java.awt.Component;

import static org.lwjgl.system.JNI.invokePPP;
import static org.lwjgl.system.JNI.invokePPPV;
import static org.lwjgl.system.macosx.ObjCRuntime.objc_getClass;
import static org.lwjgl.system.macosx.ObjCRuntime.sel_getUid;

/**
 * macOS {@link NativeSurface}: creates a {@code CAMetalLayer} and attaches it to the AWT
 * component's backing {@code NSView}, then hands that layer to the GPU backend.
 *
 * <p>The JAWT plumbing is delegated to {@code org.lwjgl.awt.AWT} (lwjgl3-awt) — LWJGL core
 * dropped its JAWT bindings, and lwjgl3-awt is the maintained replacement that
 * {@code AWTVulkanCanvas} itself uses. We then do the one ObjC call lwjgl3-awt doesn't expose:
 * {@code [surfaceLayers setLayer: [CAMetalLayer layer]]}. Vulkan (via MoltenVK) renders
 * into a {@code CAMetalLayer}.
 *
 * <p>Pixel size is reported in physical pixels so the backend resets to native resolution (HiDPI);
 * the renderer sets the layer's {@code drawableSize} from that.
 */
public final class MacOSMetalSurface implements NativeSurface {

    /** objc_msgSend is variadic, so LWJGL exposes it as a function address, not a typed binding. */
    private static final long objc_msgSend =
        ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");

    private final Component component;
    private long metalLayer;   // CAMetalLayer*

    public MacOSMetalSurface(Component component) {
        this.component = component;
        this.metalLayer = attachMetalLayer(component);
    }

    private static float backingScale(Component c) {
        if (c.getGraphicsConfiguration() != null) {
            return (float) c.getGraphicsConfiguration().getDefaultTransform().getScaleX();
        }
        return 1f;
    }

    private static long attachMetalLayer(Component component) {
        if (!AWT.isPlatformSupported()) {
            throw new IllegalStateException("lwjgl3-awt reports this platform unsupported");
        }
        try (AWT awt = new AWT(component)) {        // locks the JAWT drawing surface
            long surfaceLayers = awt.getPlatformInfo();          // id<JAWT_SurfaceLayers>
            long layer = invokePPP(objc_getClass("CAMetalLayer"), sel_getUid("layer"), objc_msgSend);
            invokePPP(layer, sel_getUid("retain"), objc_msgSend);
            invokePPPV(surfaceLayers, sel_getUid("setLayer:"), layer, objc_msgSend);
            return layer;                                        // attachment persists after unlock
        } catch (AWTException e) {
            throw new IllegalStateException("Failed to acquire the AWT drawing surface", e);
        }
    }

    // Pixel sizes are queried fresh each frame (the render thread polls them every frame), and the
    // backing scale is re-read rather than cached so moving the window to a monitor with a different
    // DPI resizes the framebuffer correctly.
    @Override public long  handle() { return metalLayer; }
    @Override public int   width()  { return Math.max(1, Math.round(component.getWidth()  * backingScale(component))); }
    @Override public int   height() { return Math.max(1, Math.round(component.getHeight() * backingScale(component))); }
    @Override public float scale()  { return backingScale(component); }

    @Override
    public void dispose() {
        if (metalLayer != 0) {
            invokePPP(metalLayer, sel_getUid("release"), objc_msgSend);
            metalLayer = 0;
        }
    }
}

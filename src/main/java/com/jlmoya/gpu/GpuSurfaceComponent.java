package com.jlmoya.gpu;

import com.jlmoya.gpu.macos.MacOSMetalSurface;

import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.Graphics;

/**
 * Heavyweight Swing/AWT component that owns a native GPU surface (Layer-1).
 *
 * <p>It renders nothing itself — a GPU backend draws into the native surface from a render
 * thread; this component only manages the native-peer lifecycle and exposes a {@link NativeSurface}.
 * It is deliberately backend-agnostic: the same component feeds the GPU backend (Vulkan/MoltenVK).
 */
public class GpuSurfaceComponent extends Canvas {

    private volatile NativeSurface surface;

    public GpuSurfaceComponent() {
        setIgnoreRepaint(true);          // we present via the GPU, not AWT paint
    }

    /** The native surface, or {@code null} until the component is displayable (addNotify ran). */
    public NativeSurface surface() {
        return surface;
    }

    @Override
    public void addNotify() {
        super.addNotify();               // creates the native peer (an NSView on macOS)
        if (surface == null) {
            surface = createSurface();
        }
    }

    @Override
    public void removeNotify() {
        NativeSurface s = surface;
        surface = null;
        if (s != null) {
            s.dispose();
        }
        super.removeNotify();
    }

    private NativeSurface createSurface() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) {
            return new MacOSMetalSurface(this);
        }
        throw new UnsupportedOperationException(
            "swing-gpu-surface Stage-0 implements macOS only (got os.name=" + os + ")");
    }

    // AWT paint is a no-op: the GPU backend owns the pixels.
    @Override public void paint(Graphics g)  { }
    @Override public void update(Graphics g) { }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        return (d.width > 0 && d.height > 0) ? d : new Dimension(960, 600);
    }
}

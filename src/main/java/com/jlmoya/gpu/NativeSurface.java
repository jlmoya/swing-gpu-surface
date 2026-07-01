package com.jlmoya.gpu;

/**
 * Backend-agnostic handle to a native GPU surface embedded in a Swing component (Layer-1).
 *
 * <p>This is the one genuinely reusable artifact: the GPU backend (Vulkan via MoltenVK)
 * consumes the same thing — a native window/layer handle plus its pixel size and HiDPI scale.
 * On macOS {@link #handle()} is a {@code CAMetalLayer*}; on Windows an {@code HWND}; on Linux
 * an X11 {@code Window}. Implementations are created from a {@link GpuSurfaceComponent} once its
 * native peer exists.
 */
public interface NativeSurface {

    /** Native handle for the GPU backend (the {@code CAMetalLayer} on macOS). 0 if not ready. */
    long handle();

    /** Surface width in physical pixels (logical width * {@link #scale()}). */
    int width();

    /** Surface height in physical pixels (logical height * {@link #scale()}). */
    int height();

    /** Backing scale factor for HiDPI (e.g. 2.0 on a Retina display). */
    float scale();

    /** Release any native resources held by this surface. Safe to call more than once. */
    void dispose();
}

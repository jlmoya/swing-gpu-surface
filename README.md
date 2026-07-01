# swing-gpu-surface — Layer-1 native GPU surface + Vulkan/MoltenVK renderer

A reusable, **backend-agnostic native GPU surface for Swing** (Layer-1): a heavyweight AWT component
that owns a `CAMetalLayer` (macOS) and exposes a `NativeSurface` (native handle + physical pixel size
+ HiDPI scale). On top of it, a **Vulkan** renderer running through **MoltenVK** on macOS.

## Status (macOS, Apple Silicon, MoltenVK)

Foundation de-risk:
- ✅ Vulkan loader + physical-device enumeration (Apple GPU via MoltenVK).
- ✅ `CAMetalLayer → VkSurfaceKHR` (`VK_EXT_metal_surface`) + present-capable queue + swapchain params.
- ⏳ Single-window swapchain + clear + present (verified by readback), then multi-window.

## Layout

```
pom.xml                          LWJGL 3.3.4 (core + vulkan) + lwjgl3-awt 0.2.4; exec plugin
src/main/java/com/jlmoya/gpu/
  NativeSurface.java             backend-agnostic surface handle (Layer-1)
  GpuSurfaceComponent.java       heavyweight Swing component owning the native surface
  macos/MacOSMetalSurface.java   AWT NSView -> CAMetalLayer (lwjgl3-awt JAWT plumbing)
  VulkanProbe.java               foundation probe: loader + physical device
  VulkanSurfaceProbe.java        foundation probe: CAMetalLayer -> VkSurfaceKHR + swapchain params
```

## Run (macOS, with the LunarG Vulkan SDK installed)

```
source <VulkanSDK>/setup-env.sh        # sets the loader + MoltenVK ICD env
mvn -q compile exec:java -Dexec.mainClass=com.jlmoya.gpu.VulkanSurfaceProbe
```

We do **not** pass `-XstartOnFirstThread`: Swing/AWT already owns the macOS main (AppKit) thread.

## Portability

The surface is backend-agnostic. macOS yields a `CAMetalLayer` (→ `VkSurfaceKHR` via
`VK_EXT_metal_surface`); a Windows `HWND` (`VK_KHR_win32_surface`) or Linux X11/Wayland surface slots
in behind the same `NativeSurface` interface, with native Vulkan everywhere off macOS.

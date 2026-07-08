package dev.amdium.util;

import dev.amdium.Amdium;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLCapabilities;

/**
 * Определяет производителя GPU и доступные расширения OpenGL.
 *
 * Минимум: AMD GPU (Radeon RX 400+), OpenGL 4.3+ (MDI + compute)
 * Опционально (дают большой прирост):
 *   - ARB_indirect_parameters → glMultiDrawElementsIndirectCount (без readback)
 *   - ARB_buffer_storage      → persistent mapped buffers
 *   - ARB_sparse_buffer       → отложенная аллокация большого VBO
 *   - ARB_shader_storage_buffer_object → SSBO (есть в GL 4.3)
 *
 * Detects the GPU vendor and available OpenGL extensions.
 *
 * Minimum: AMD GPU (Radeon RX 400+), OpenGL 4.3+ (MDI + compute)
 * Optional (give a large boost):
 *   - ARB_indirect_parameters → glMultiDrawElementsIndirectCount (no readback)
 *   - ARB_buffer_storage      → persistent mapped buffers
 *   - ARB_sparse_buffer       → lazy allocation of a large VBO
 *   - ARB_shader_storage_buffer_object → SSBO (present in GL 4.3)
 */
public class GPUCapabilityDetector {

    private final String vendor;
    private final String renderer;
    private final GLCapabilities caps;

    public GPUCapabilityDetector() {
        this.vendor   = nullSafe(GL11.glGetString(GL11.GL_VENDOR));
        this.renderer = nullSafe(GL11.glGetString(GL11.GL_RENDERER));
        this.caps     = GL.getCapabilities();

        Amdium.LOGGER.info("[Amdium] GL_VENDOR:   {}", vendor);
        Amdium.LOGGER.info("[Amdium] GL_RENDERER: {}", renderer);
        Amdium.LOGGER.info("[Amdium] GL_VERSION:  {}", nullSafe(GL11.glGetString(GL11.GL_VERSION)));
    }

    public boolean isAMD() {
        String v = vendor.toLowerCase();
        String r = renderer.toLowerCase();
        return v.contains("amd") || v.contains("ati")
                || r.contains("amd") || r.contains("radeon")
                || r.contains("rx ") || r.contains("vega")
                || r.contains("navi") || r.contains("gfx");
    }

    public String detectArchitecture() {
        String r = renderer.toLowerCase();
        if (r.contains("rx 7") || r.contains("navi 3") || r.contains("gfx11")) return "RDNA3 (RX 7000)";
        if (r.contains("rx 6") || r.contains("navi 2") || r.contains("gfx10")) return "RDNA2 (RX 6000)";
        if (r.contains("rx 5") || r.contains("navi 1") || r.contains("navi"))  return "RDNA1 (RX 5000)";
        if (r.contains("vega") || r.contains("gfx9"))                          return "Vega (GCN5)";
        if (r.contains("rx 4") || r.contains("rx 5") || r.contains("fiji"))    return "GCN4/Polaris";
        if (r.contains("r9") || r.contains("r7") || r.contains("r5"))          return "GCN2-3";
        return "Unknown AMD";
    }

    /**
     * Multi-Draw Indirect — основная фича. GL 4.3 / ARB_multi_draw_indirect.
     * Multi-Draw Indirect — the main feature. GL 4.3 / ARB_multi_draw_indirect.
     */
    public boolean supportsMDI() {
        return caps.GL_ARB_multi_draw_indirect || caps.OpenGL43;
    }

    /**
     * Compute shaders — GL 4.3 / ARB_compute_shader.
     * Compute shaders — GL 4.3 / ARB_compute_shader.
     */
    public boolean supportsComputeShaders() {
        return caps.OpenGL43 || caps.GL_ARB_compute_shader;
    }

    /**
     * ARB_buffer_storage — persistent mapped buffers. GL 4.4.
     * ARB_buffer_storage — persistent mapped buffers. GL 4.4.
     */
    public boolean supportsPersistentMapping() {
        return caps.OpenGL44 || caps.GL_ARB_buffer_storage;
    }

    /**
     * SSBO — есть в GL 4.3.
     * SSBO — present in GL 4.3.
     */
    public boolean supportsSSBO() {
        return caps.OpenGL43 || caps.GL_ARB_shader_storage_buffer_object;
    }

    /**
     * ARB_indirect_parameters — glMultiDrawElementsIndirectCount.
     * Позволяет GPU читать drawCount из parameter buffer — ноль readback!
     * Доступно на AMD GCN4+ (RX 400+), RDNA — есть.
     *
     * ARB_indirect_parameters — glMultiDrawElementsIndirectCount.
     * Lets the GPU read drawCount from the parameter buffer — zero readback!
     * Available on AMD GCN4+ (RX 400+), present on RDNA.
     */
    public boolean supportsIndirectParameters() {
        return caps.GL_ARB_indirect_parameters || caps.OpenGL46;
    }

    /**
     * ARB_sparse_buffer — отложенная аллокация большого VBO по страницам.
     * ARB_sparse_buffer — page-based lazy allocation of a large VBO.
     */
    public boolean supportsSparseBuffer() {
        return caps.GL_ARB_sparse_buffer;
    }

    /**
     * EXT_mesh_shader — настоящий AMD-совместимый mesh shader (RDNA2+).
     * В LWJGL 3.x поле caps.GL_EXT_mesh_shader может отсутствовать, поэтому
     * проверяем через reflection (safe fallback = false).
     *
     * EXT_mesh_shader — a true AMD-compatible mesh shader (RDNA2+).
     * In LWJGL 3.x the caps.GL_EXT_mesh_shader field may be missing, so we
     * check via reflection (safe fallback = false).
     */
    public boolean supportsEXTMeshShader() {
        try {
            java.lang.reflect.Field f = caps.getClass().getField("GL_EXT_mesh_shader");
            return f.getBoolean(caps);
        } catch (Exception e) {
            return false;
        }
    }

    public String getRendererName() { return renderer; }
    public String getVendorName()   { return vendor;   }

    private static String nullSafe(String s) {
        return s != null ? s : "<null>";
    }
}

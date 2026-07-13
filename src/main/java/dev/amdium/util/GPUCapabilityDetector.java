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

    /**
     * Определяет, является ли GPU интегрированным (APU / iGPU).
     * APU делят память с ОЗУ и имеют меньше VRAM, чем дискретные карты.
     *
     * Известные APU-чипы AMD:
     *   - Radeon 660M / 680M / 780M (RDNA2/3 APU)
     *   - Vega 3/6/7/8/11 (Ryzen 2000-5000 APU)
     *   - Radeon Graphics (Ryzen 7000 APU)
     *   - ATI Radeon HD 3000/4000 series (старые IGP)
     *
     * Также детектим Intel iGPU и ARM Mali как APU-подобные (общая память).
     */
    public boolean isAPU() {
        String v = vendor.toLowerCase();
        String r = renderer.toLowerCase();

        // AMD APU: Radeon 660M/680M/780M, "radeon graphics"
        if (r.contains("radeon 660m") || r.contains("radeon 680m") || r.contains("radeon 780m")
                || r.contains("radeon graphics") || r.contains("radeon(tm) graphics")
                || r.contains("amd radeon graphics")) {
            return true;
        }
        // Vega APU (но НЕ Vega 56/64/20 — это дискретные)
        if (r.contains("vega")) {
            // Дискретные Vega: "vega 56", "vega 64", "vega vii", "vega 20", "radeon vii"
            if (r.contains("vega 56") || r.contains("vega 64")
                    || r.contains("vega vii") || r.contains("radeon vii")
                    || r.contains("vega 20")) {
                return false;
            }
            // Остальные Vega — это APU (Ryzen 2000-5000).
            return true;
        }
        // Intel iGPU — всегда APU-подобные (shared memory).
        if (v.contains("intel")) {
            if (r.contains("iris") || r.contains("uhd") || r.contains("hd graphics")
                    || r.contains("intel(r)")) {
                return true;
            }
        }
        // ARM Mali / Adreno — мобильные iGPU.
        if (v.contains("arm") || r.contains("mali") || r.contains("adreno")) {
            return true;
        }
        return false;
    }

    /**
     * Возвращает безопасный cap для vertexPoolMB на данном GPU.
     * APU ограничиваем 1024 MB, дискретные — config значением (до 4096).
     */
    public int getVertexPoolMBCap(int configValue) {
        if (isAPU()) {
            int capped = Math.min(configValue, 1024);
            if (capped != configValue) {
                Amdium.LOGGER.warn("[Amdium] APU обнаружен ({}). vertexPoolMB ограничен {} MB (config={}).",
                        renderer, capped, configValue);
            }
            return capped;
        }
        return configValue;
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

    /** Multi-Draw Indirect — GL 4.3 / ARB_multi_draw_indirect. */
    public boolean supportsMDI() {
        return caps.GL_ARB_multi_draw_indirect || caps.OpenGL43;
    }

    /** Compute shaders — GL 4.3 / ARB_compute_shader. */
    public boolean supportsComputeShaders() {
        return caps.OpenGL43 || caps.GL_ARB_compute_shader;
    }

    /** ARB_buffer_storage — persistent mapped buffers (GL 4.4). */
    public boolean supportsPersistentMapping() {
        return caps.OpenGL44 || caps.GL_ARB_buffer_storage;
    }

    /** SSBO — GL 4.3. */
    public boolean supportsSSBO() {
        return caps.OpenGL43 || caps.GL_ARB_shader_storage_buffer_object;
    }

    /**
     * ARB_indirect_parameters — glMultiDrawElementsIndirectCount.
     * GPU читает drawCount из parameter buffer — ноль readback.
     */
    public boolean supportsIndirectParameters() {
        return caps.GL_ARB_indirect_parameters || caps.OpenGL46;
    }

    /** ARB_sparse_buffer — отложенная аллокация большого VBO по страницам. */
    public boolean supportsSparseBuffer() {
        return caps.GL_ARB_sparse_buffer;
    }

    /**
     * EXT_mesh_shader (RDNA2+).
     * В LWJGL 3.x поле caps.GL_EXT_mesh_shader может отсутствовать,
     * поэтому проверяем через reflection (safe fallback = false).
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

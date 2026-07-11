package dev.amdium.render;

import dev.amdium.Amdium;
import dev.amdium.benchmark.AmdiumGpuTimer;
import dev.amdium.benchmark.AmdiumTelemetry;
import dev.amdium.config.AmdiumConfig;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Перехватчик draw-вызовов Embedium. v2.2 — с per-command + Hi-Z GPU culling.
 * / Embedium draw-call interceptor. v2.2 — with per-command + Hi-Z GPU culling.
 *
 * Пути:
 * / Paths:
 *
 *  === Путь A: GPU-compute culling (v2.2, per-command + Hi-Z) ===
 *    Требует:
 *      - AmdiumConfig.ENABLE_INTEROP_COMPUTE_CULLING = true,
 *      - PerCommandMetadata не пуст (mixin в RenderSection сработал),
 *      - InteropComputeCuller инициализирован (compute + indirect_parameters).
 *    Делает:
 *      1. CPU строит per-command chunkInfo SSBO (baseVertex → AABB lookup).
 *      2. Compute-шейдер куллит per-command: frustum + fog + Hi-Z occlusion.
 *      3. glMultiDrawElementsIndirectCount — нуль readback.
 *
 *  === Путь B: прямой MDI (fallback, без culling) ===
 *    Если путь A недоступен — обычный glMultiDrawElementsIndirect с CPU-provided count.
 *    Проще, но count известен CPU (не zero-readback).
 *
 *  Путь A включается, если все условия выше выполнены. Иначе — путь B.
 * / Path A is enabled if all conditions above are met. Otherwise — path B.
 */
public class EmbediumInterop {

    private static final org.slf4j.Logger LOGGER = Amdium.LOGGER;

    private static final int COMMAND_STRIDE = 20; // sizeof(DrawElementsIndirectCommand)
    private static final int GL_DRAW_INDIRECT_BUFFER = 0x8F3F;
    private static final boolean ENABLE_EXPERIMENTAL_BASEVERTEX_PASSTHROUGH =
            Boolean.getBoolean("amdium.experimental.embeddiumBaseVertexPassthrough");
    private static final boolean ENABLE_EXPERIMENTAL_MDI =
            Boolean.getBoolean("amdium.experimental.embeddiumMdi");
    private static final boolean ENABLE_EXPERIMENTAL_COMPUTE_CULLING =
            Boolean.getBoolean("amdium.experimental.embeddiumComputeCulling");
    private static final int EXPERIMENTAL_MDI_MIN_COMMANDS = Math.max(0,
            Integer.getInteger("amdium.experimental.embeddiumMdiMinCommands", 96));

    // Indirect buffer для fallback-пути B (без compute).
    private static int indirectBufferId = -1;
    private static long indirectBufferSize = 0;
    private static ByteBuffer cpuStaging = null;

    // Кадровые данные, захваченные LevelRendererMixin для пути A.
    // / Per-frame data captured by LevelRendererMixin for path A.
    private static float[] frameProjView    = new float[16];
    private static float[] frameView        = new float[16];
    private static float[] frameProjection  = new float[16];
    private static float frameCamX, frameCamY, frameCamZ;
    private static float frameFogStart, frameFogEnd;
    private static boolean hasFrameData = false;

    private static final int DEFAULT_PRIMITIVE = org.lwjgl.opengl.GL11.GL_TRIANGLES;

    /**
     * Данные кадра от LevelRendererMixin (interop-режим).
     * / Per-frame data from LevelRendererMixin (interop mode).
     *
     * v2.2: теперь принимает view и projection ОТДЕЛЬНО (для Hi-Z NDC conversion),
     * в дополнение к projView.
     * / v2.2: now accepts view and projection SEPARATELY (for Hi-Z NDC conversion),
     * in addition to projView.
     */
    public static void setFrameData(float[] projView, float[] view, float[] projection,
                                     float camX, float camY, float camZ,
                                     float fogStart, float fogEnd) {
        System.arraycopy(projView, 0, frameProjView, 0, 16);
        System.arraycopy(view, 0, frameView, 0, 16);
        System.arraycopy(projection, 0, frameProjection, 0, 16);
        frameCamX = camX; frameCamY = camY; frameCamZ = camZ;
        frameFogStart = fogStart; frameFogEnd = fogEnd;
        hasFrameData = true;
    }

    /**
     * Инициализация. / Initialization.
     */
    public static void init(boolean supportsIndirectParameters) {
        if (!shouldInterceptEmbeddiumDraws()) {
            LOGGER.warn("[Amdium] Embeddium draw replacement is disabled. "
                    + "Embeddium will render chunks with its original path. "
                    + "Use -Damdium.experimental.embeddiumMdi=true only for draw-equivalence experiments.");
            return;
        }

        if (ENABLE_EXPERIMENTAL_BASEVERTEX_PASSTHROUGH && !ENABLE_EXPERIMENTAL_MDI) {
            LOGGER.warn("[Amdium] Experimental Embeddium BaseVertex passthrough active. "
                    + "Amdium intercepts draws but calls Embeddium's original OpenGL draw shape.");
            return;
        }

        int maxCommands = 4096;
        indirectBufferSize = (long) maxCommands * COMMAND_STRIDE;

        indirectBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBufferId);
        GL15.glBufferData(GL_DRAW_INDIRECT_BUFFER, indirectBufferSize, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);

        cpuStaging = MemoryUtil.memAlloc((int) indirectBufferSize);

        // Initialize v2.2 compute culler.
        boolean computeEnabled = AmdiumConfig.ENABLE_INTEROP_COMPUTE_CULLING.get()
                && ENABLE_EXPERIMENTAL_COMPUTE_CULLING;
        boolean canCompute = Amdium.supportsCompute && Amdium.supportsIndirectParameters;
        if (computeEnabled && canCompute) {
            InteropComputeCuller.init();
        } else {
            LOGGER.info("[Amdium] v2.2 interop compute-culling отключён (config={}, compute={}, indirectParams={}). "
                    + "Используется прямой MDI-путь.",
                    computeEnabled, Amdium.supportsCompute, Amdium.supportsIndirectParameters);
        }

        LOGGER.info("[Amdium] Embedium interop готов. max commands={}, compute-culler={}",
                maxCommands, InteropComputeCuller.isInitialized());
    }

    /**
     * Перехват multiDrawElementsBaseVertex из Embedium.
     * / Intercept multiDrawElementsBaseVertex from Embedium.
     */
    public static void interceptMultiDraw(long pElementPointer, long pElementCount,
                                           long pBaseVertex, int size, int indexTypeSize,
                                           int indexTypeFormat) {
        if (size <= 0) return;
        AmdiumTelemetry.recordInteropBatch(size);

        if (ENABLE_EXPERIMENTAL_BASEVERTEX_PASSTHROUGH && !ENABLE_EXPERIMENTAL_MDI) {
            AmdiumTelemetry.recordInteropFallback(size);
            fallbackToBaseVertex(pElementPointer, pElementCount, pBaseVertex, size,
                    DEFAULT_PRIMITIVE, indexTypeFormat, indexTypeSize);
            return;
        }

        if (!isInitialized()) {
            AmdiumTelemetry.recordInteropFallback(size);
            fallbackToBaseVertex(pElementPointer, pElementCount, pBaseVertex, size,
                    DEFAULT_PRIMITIVE, indexTypeFormat, indexTypeSize);
            return;
        }

        if (ENABLE_EXPERIMENTAL_MDI && size < EXPERIMENTAL_MDI_MIN_COMMANDS) {
            AmdiumTelemetry.recordInteropFallback(size);
            fallbackToBaseVertex(pElementPointer, pElementCount, pBaseVertex, size,
                    DEFAULT_PRIMITIVE, indexTypeFormat, indexTypeSize);
            return;
        }

        // --- Путь A: GPU-compute culling (v2.2) ---
        if (InteropComputeCuller.isInitialized() && hasFrameData) {
            boolean ok = InteropComputeCuller.drawWithCulling(
                    pElementPointer, pElementCount, pBaseVertex,
                    size, indexTypeSize, indexTypeFormat,
                    frameProjView, frameView, frameProjection,
                    frameCamX, frameCamY, frameCamZ,
                    frameFogStart, frameFogEnd);
            if (ok) {
                AmdiumTelemetry.recordInteropComputeBatch(size);
                return;
            }
            // иначе — fallback на путь B
            AmdiumTelemetry.recordInteropFallback(size);
        }

        // --- Путь B: прямой MDI (fallback, без culling) ---
        if ((long) size * COMMAND_STRIDE > indirectBufferSize) {
            fallbackToBaseVertex(pElementPointer, pElementCount, pBaseVertex, size,
                    DEFAULT_PRIMITIVE, indexTypeFormat, indexTypeSize);
            return;
        }
        AmdiumTelemetry.recordInteropDirectBatch(size);

        cpuStaging.clear();
        for (int i = 0; i < size; i++) {
            int count = MemoryUtil.memGetInt(pElementCount + ((long) i * 4));
            long byteOffset = MemoryUtil.memGetLong(pElementPointer + ((long) i * 8));
            int firstIndex = (int) (byteOffset / indexTypeSize);
            int baseVertex = MemoryUtil.memGetInt(pBaseVertex + ((long) i * 4));
            cpuStaging.putInt(count);
            cpuStaging.putInt(1);
            cpuStaging.putInt(firstIndex);
            cpuStaging.putInt(baseVertex);
            cpuStaging.putInt(0);
        }
        cpuStaging.flip();
        int bytesToUpload = size * COMMAND_STRIDE;

        AmdiumGpuTimer.Scope uploadScope = AmdiumGpuTimer.begin(AmdiumGpuTimer.INTEROP_DIRECT_UPLOAD);
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBufferId);
        GL15.glBufferSubData(GL_DRAW_INDIRECT_BUFFER, 0, cpuStaging.limit(bytesToUpload));
        AmdiumGpuTimer.end(uploadScope);
        AmdiumTelemetry.recordUploadBytes(bytesToUpload);
        AmdiumTelemetry.recordBufferSubDataBytes(bytesToUpload);

        AmdiumGpuTimer.Scope drawScope = AmdiumGpuTimer.begin(AmdiumGpuTimer.INTEROP_MDI_DRAW);
        GL43.glMultiDrawElementsIndirect(
                DEFAULT_PRIMITIVE,
                indexTypeFormat,
                0L, size, COMMAND_STRIDE);
        AmdiumGpuTimer.end(drawScope);

        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
    }

    /**
     * Вызывается в конце кадра — обновить Hi-Z пирамиду для следующего кадра.
     * / Called at end of frame — update the Hi-Z pyramid for the next frame.
     */
    public static void endFrame() {
        if (InteropComputeCuller.isInitialized()) {
            InteropComputeCuller.endFrameUpdateHiZ();
        }
    }

    /**
     * Fallback на оригинальный Embedium вызов.
     * / Fallback to the original Embedium call.
     */
    private static void fallbackToBaseVertex(long pElementPointer, long pElementCount,
                                              long pBaseVertex, int size,
                                              int primitiveType, int indexTypeFormat, int indexTypeSize) {
        AmdiumGpuTimer.Scope fallbackScope = AmdiumGpuTimer.begin(AmdiumGpuTimer.INTEROP_BASEVERTEX_FALLBACK);
        GL32.nglMultiDrawElementsBaseVertex(
                primitiveType,
                pElementCount,
                indexTypeFormat,
                pElementPointer,
                size,
                pBaseVertex);
        AmdiumGpuTimer.end(fallbackScope);
    }

    public static boolean isInitialized() {
        return indirectBufferId != -1;
    }

    public static boolean shouldReplaceEmbeddiumDraws() {
        return ENABLE_EXPERIMENTAL_MDI;
    }

    public static boolean shouldInterceptEmbeddiumDraws() {
        return ENABLE_EXPERIMENTAL_MDI || ENABLE_EXPERIMENTAL_BASEVERTEX_PASSTHROUGH;
    }

    public static void destroy() {
        if (cpuStaging != null) { MemoryUtil.memFree(cpuStaging); cpuStaging = null; }
        if (indirectBufferId != -1) { GL15.glDeleteBuffers(indirectBufferId); indirectBufferId = -1; }
        InteropComputeCuller.destroy();
    }
}

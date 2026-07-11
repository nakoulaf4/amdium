package dev.amdium.render;

import dev.amdium.Amdium;
import dev.amdium.config.AmdiumConfig;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Перехватчик draw-вызовов Embedium. v2.3 — оптимизированный interop.
 * / Embedium draw-call interceptor. v2.3 — optimized interop.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * v2.3 ОПТИМИЗАЦИЯ / v2.3 OPTIMIZATION
 * ─────────────────────────────────────────────────────────────────────────
 *
 * v2.2 ПРОБЛЕМА / v2.2 PROBLEM:
 *   Fallback-путь B (без compute culling) использовал glBufferSubData на
 *   одном и том же indirectBufferId каждый кадр — без orphaning. Это
 *   вызывало implicit sync, если GPU ещё читал прошлый кадр.
 *   / The fallback path B (without compute culling) used glBufferSubData on
 *   the same indirectBufferId every frame — without orphaning. This caused
 *   implicit sync if the GPU was still reading the previous frame.
 *
 * v2.3 ИСПРАВЛЕНИЕ / v2.3 FIX:
 *   Добавлен buffer orphaning: glBufferData(size, DYNAMIC_DRAW) с NULL
 *   перед glBufferSubData. Драйвер выдаёт свежий buffer, старый живёт пока
 *   GPU его читает. Стандартный паттерн для DYNAMIC_DRAW буферов.
 *   / Added buffer orphaning: glBufferData(size, DYNAMIC_DRAW) with NULL
 *   before glBufferSubData. The driver hands out a fresh buffer; the old one
 *   lives until the GPU finishes reading it. Standard pattern for DYNAMIC_DRAW.
 *
 *   Также добавлены beginFrame()/endFrame() для RingStreamBuffer.
 *   / Added beginFrame()/endFrame() for the RingStreamBuffer.
 *
 * Пути / Paths:
 *
 *  === Путь A: GPU-compute culling (v2.3, per-command + Hi-Z) ===
 *    Делает:
 *      1. CPU строит per-command chunkInfo SSBO (baseVertex → AABB lookup).
 *      2. Compute-шейдер куллит per-command: frustum + fog + Hi-Z occlusion.
 *      3. glMultiDrawElementsIndirectCount — нуль readback.
 *
 *  === Путь B: прямой MDI (fallback, без culling) ===
 *    Если путь A недоступен — обычный glMultiDrawElementsIndirect с
 *    CPU-provided count + buffer orphaning.
 */
public class EmbediumInterop {

    private static final org.slf4j.Logger LOGGER = Amdium.LOGGER;

    private static final int COMMAND_STRIDE = 20; // sizeof(DrawElementsIndirectCommand)
    private static final int GL_DRAW_INDIRECT_BUFFER = 0x8F3F;

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
     * v2.3: вызывается в HEAD renderLevel — подготавливает ring stream для кадра.
     * / v2.3: called at HEAD renderLevel — prepares the ring stream for the frame.
     */
    public static void beginFrame() {
        if (InteropComputeCuller.isInitialized()) {
            InteropComputeCuller.beginFrame();
        }
    }

    /**
     * Инициализация. / Initialization.
     */
    public static void init(boolean supportsIndirectParameters) {
        int maxCommands = 4096;
        indirectBufferSize = (long) maxCommands * COMMAND_STRIDE;

        indirectBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBufferId);
        GL15.glBufferData(GL_DRAW_INDIRECT_BUFFER, indirectBufferSize, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);

        cpuStaging = MemoryUtil.memAlloc((int) indirectBufferSize);

        // Initialize v2.3 compute culler.
        boolean computeEnabled = AmdiumConfig.ENABLE_INTEROP_COMPUTE_CULLING.get();
        boolean canCompute = Amdium.supportsCompute && Amdium.supportsIndirectParameters;
        if (computeEnabled && canCompute) {
            InteropComputeCuller.init();
        } else {
            LOGGER.info("[Amdium] v2.3 interop compute-culling отключён (config={}, compute={}, indirectParams={}). "
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

        // --- Путь A: GPU-compute culling (v2.3) ---
        if (InteropComputeCuller.isInitialized() && hasFrameData) {
            boolean ok = InteropComputeCuller.drawWithCulling(
                    pElementPointer, pElementCount, pBaseVertex,
                    size, indexTypeSize, indexTypeFormat,
                    frameProjView, frameView, frameProjection,
                    frameCamX, frameCamY, frameCamZ,
                    frameFogStart, frameFogEnd);
            if (ok) return;
            // иначе — fallback на путь B
        }

        // --- Путь B: прямой MDI (fallback, без culling) ---
        if ((long) size * COMMAND_STRIDE > indirectBufferSize) {
            fallbackToBaseVertex(pElementPointer, pElementCount, pBaseVertex, size,
                    DEFAULT_PRIMITIVE, indexTypeFormat, indexTypeSize);
            return;
        }

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

        // v2.3: buffer orphaning — даём драйверу выдать свежий буфер, чтобы
        // избежать implicit sync с прошлым кадром.
        // / v2.3: buffer orphaning — let the driver hand out a fresh buffer to
        // avoid implicit sync with the previous frame.
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBufferId);
        GL15.glBufferData(GL_DRAW_INDIRECT_BUFFER, indirectBufferSize, GL15.GL_DYNAMIC_DRAW);
        GL15.glBufferSubData(GL_DRAW_INDIRECT_BUFFER, 0, cpuStaging.limit(bytesToUpload));

        GL43.glMultiDrawElementsIndirect(
                DEFAULT_PRIMITIVE,
                indexTypeFormat,
                0L, size, COMMAND_STRIDE);

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
        GL32.nglMultiDrawElementsBaseVertex(
                primitiveType,
                pElementCount,
                indexTypeFormat,
                pElementPointer,
                size,
                pBaseVertex);
    }

    public static boolean isInitialized() {
        return indirectBufferId != -1;
    }

    public static void destroy() {
        if (cpuStaging != null) { MemoryUtil.memFree(cpuStaging); cpuStaging = null; }
        if (indirectBufferId != -1) { GL15.glDeleteBuffers(indirectBufferId); indirectBufferId = -1; }
        InteropComputeCuller.destroy();
    }
}

package dev.amdium.render;

import dev.amdium.Amdium;
import dev.amdium.config.AmdiumConfig;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Перехватчик draw-вызовов Embedium.
 * / Embedium draw-call interceptor.
 *
 * Два пути:
 * / Two paths:
 *
 *  === Путь A: GPU-compute culling (рекомендуется, требует GL 4.3 + ARB_indirect_parameters) ===
 *    1. CPU копирует MultiDrawBatch → input SSBO.
 *    2. Compute-шейдер куллит регион (frustum + fog), компактирует команды,
 *       atomicAdd → counter buffer.
 *    3. glMultiDrawElementsIndirectCount — GPU читает drawCount из parameter buffer.
 *       **Нуль readback.** Nvidium-style pipeline.
 *
 *  === Путь B: прямой MDI (fallback, без compute) ===
 *    1. CPU конвертирует MultiDrawBatch → GL_DRAW_INDIRECT_BUFFER.
 *    2. glMultiDrawElementsIndirect (count = CPU-known).
 *    Проще, но count известен CPU (не zero-readback).
 *
 *  Путь A включается, если:
 *  / Path A is enabled if:
 *    - AmdiumConfig.ENABLE_INTEROP_COMPUTE_CULLING = true,
 *    - InteropComputeCuller инициализирован (compute + indirect_parameters поддерживаются).
 *
 *  Если путь A недоступен — fallback на путь B.
 *  / If path A is unavailable — fall back to path B.
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
    private static float[] frameProjView = new float[16];
    private static float frameCamX, frameCamY, frameCamZ;
    private static float frameFogStart, frameFogEnd;
    private static boolean hasFrameData = false;

    // Примитивный тип — GL_TRIANGLES (chunk meshing всегда треугольники).
    private static final int DEFAULT_PRIMITIVE = org.lwjgl.opengl.GL11.GL_TRIANGLES;

    /** Данные кадра от LevelRendererMixin (interop-режим). / Per-frame data from LevelRendererMixin (interop mode). */
    public static void setFrameData(float[] projView, float camX, float camY, float camZ,
                                     float fogStart, float fogEnd) {
        System.arraycopy(projView, 0, frameProjView, 0, 16);
        frameCamX = camX; frameCamY = camY; frameCamZ = camZ;
        frameFogStart = fogStart; frameFogEnd = fogEnd;
        hasFrameData = true;
    }

    /**
     * Инициализация. Вызывается из Amdium.initGPU().
     * / Initialization. Called from Amdium.initGPU().
     */
    public static void init(boolean supportsIndirectParameters) {
        int maxCommands = 4096;
        indirectBufferSize = (long) maxCommands * COMMAND_STRIDE;

        indirectBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBufferId);
        GL15.glBufferData(GL_DRAW_INDIRECT_BUFFER, indirectBufferSize, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);

        cpuStaging = MemoryUtil.memAlloc((int) indirectBufferSize);

        // Инициализируем compute-culler, если включён в конфиге и GPU поддерживает.
        // / Initialize the compute culler if enabled in config and GPU supports it.
        boolean computeEnabled = AmdiumConfig.ENABLE_INTEROP_COMPUTE_CULLING.get();
        boolean canCompute = Amdium.supportsCompute && Amdium.supportsIndirectParameters;
        if (computeEnabled && canCompute) {
            InteropComputeCuller.init();
        } else {
            LOGGER.info("[Amdium] Interop compute-culling отключён (config={}, compute={}, indirectParams={}). "
                    + "Используется прямой MDI-путь.",
                    computeEnabled, Amdium.supportsCompute, Amdium.supportsIndirectParameters);
            // / [Amdium] Interop compute-culling disabled. Using direct MDI path.
        }

        LOGGER.info("[Amdium] Embedium interop готов. max commands={}, compute-culler={}",
                maxCommands, InteropComputeCuller.isInitialized());
        // / [Amdium] Embedium interop ready.
    }

    /**
     * Перехват multiDrawElementsBaseVertex из Embedium.
     * / Intercept multiDrawElementsBaseVertex from Embedium.
     *
     * @param pElementPointer native pointer на long[] (byte offsets в IBO)
     * @param pElementCount   native pointer на int[] (кол-во индексов)
     * @param pBaseVertex     native pointer на int[] (base vertex offsets)
     * @param size            кол-во команд
     * @param indexTypeSize   размер индекса (4 для UNSIGNED_INT)
     * @param indexTypeFormat GL-константа типа индекса (GL_UNSIGNED_INT и т.д.)
     */
    public static void interceptMultiDraw(long pElementPointer, long pElementCount,
                                           long pBaseVertex, int size, int indexTypeSize,
                                           int indexTypeFormat) {
        if (size <= 0) return;

        // --- Путь A: GPU-compute culling ---
        if (InteropComputeCuller.isInitialized() && hasFrameData) {
            boolean ok = InteropComputeCuller.drawWithCulling(
                    pElementPointer, pElementCount, pBaseVertex,
                    size, indexTypeSize, indexTypeFormat,
                    frameProjView, frameCamX, frameCamY, frameCamZ,
                    frameFogStart, frameFogEnd);
            if (ok) return;
            // иначе — fallback на путь B
            // / otherwise — fall back to path B
        }

        // --- Путь B: прямой MDI (fallback) ---
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

        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBufferId);
        GL15.glBufferSubData(GL_DRAW_INDIRECT_BUFFER, 0, cpuStaging.limit(bytesToUpload));

        GL43.glMultiDrawElementsIndirect(
                DEFAULT_PRIMITIVE,
                indexTypeFormat,
                0L, size, COMMAND_STRIDE);

        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
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

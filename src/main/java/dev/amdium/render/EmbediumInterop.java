package dev.amdium.render;

import dev.amdium.Amdium;
import dev.amdium.config.AmdiumConfig;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Перехватчик draw-вызовов Embedium. v2.3.3 — оптимизированный interop.
 *
 * Пути:
 *   Путь A: GPU-compute culling (per-command + Hi-Z) → glMultiDrawElementsIndirectCount
 *   Путь B: прямой MDI (fallback, без culling) → glMultiDrawElementsIndirect с orphaning
 */
public class EmbediumInterop {

    private static final org.slf4j.Logger LOGGER = Amdium.LOGGER;

    private static final int COMMAND_STRIDE = 20;
    private static final int GL_DRAW_INDIRECT_BUFFER = 0x8F3F;

    private static int indirectBufferId = -1;
    private static long indirectBufferSize = 0;
    private static ByteBuffer cpuStaging = null;

    // Переменные кадра от LevelRendererMixin (для пути A).
    private static float[] frameProjView    = new float[16];
    private static float[] frameView        = new float[16];
    private static float[] frameProjection  = new float[16];
    private static float frameCamX, frameCamY, frameCamZ;
    private static float frameFogStart, frameFogEnd;
    private static boolean hasFrameData = false;

    private static final int DEFAULT_PRIMITIVE = org.lwjgl.opengl.GL11.GL_TRIANGLES;

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

    /** Вызывается в HEAD renderLevel — подготавливает ring stream для кадра. */
    public static void beginFrame() {
        if (InteropComputeCuller.isInitialized()) {
            InteropComputeCuller.beginFrame();
        }
    }

    public static void init(boolean supportsIndirectParameters) {
        int maxCommands = 4096;
        indirectBufferSize = (long) maxCommands * COMMAND_STRIDE;

        indirectBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBufferId);
        GL15.glBufferData(GL_DRAW_INDIRECT_BUFFER, indirectBufferSize, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);

        cpuStaging = MemoryUtil.memAlloc((int) indirectBufferSize);

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
     */
    public static void interceptMultiDraw(long pElementPointer, long pElementCount,
                                           long pBaseVertex, int size, int indexTypeSize,
                                           int indexTypeFormat) {
        if (size <= 0) return;

        // Путь A: GPU-compute culling
        if (InteropComputeCuller.isInitialized() && hasFrameData) {
            boolean ok = InteropComputeCuller.drawWithCulling(
                    pElementPointer, pElementCount, pBaseVertex,
                    size, indexTypeSize, indexTypeFormat,
                    frameProjView, frameView, frameProjection,
                    frameCamX, frameCamY, frameCamZ,
                    frameFogStart, frameFogEnd);
            if (ok) return;
        }

        // Путь B: прямой MDI (fallback)
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

        // Buffer orphaning — избегаем implicit sync с прошлым кадром.
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBufferId);
        GL15.glBufferData(GL_DRAW_INDIRECT_BUFFER, indirectBufferSize, GL15.GL_DYNAMIC_DRAW);
        GL15.glBufferSubData(GL_DRAW_INDIRECT_BUFFER, 0, cpuStaging.limit(bytesToUpload));

        GL43.glMultiDrawElementsIndirect(
                DEFAULT_PRIMITIVE,
                indexTypeFormat,
                0L, size, COMMAND_STRIDE);

        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
    }

    /** Вызывается в конце кадра — обновляет Hi-Z пирамиду. */
    public static void endFrame() {
        if (InteropComputeCuller.isInitialized()) {
            InteropComputeCuller.endFrameUpdateHiZ();
        }
    }

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
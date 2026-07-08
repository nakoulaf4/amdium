package dev.amdium.render;

import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL46;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * GPU-compute culler для interop-пути (Embeddium установлен).
 * / GPU-compute culler for the interop path (Embeddium installed).
 *
 * Архитектура (Nvidium-style):
 * / Architecture (Nvidium-style):
 *
 *   1. CPU копирует MultiDrawBatch Embeddium → input SSBO (DrawCmd[]).
 *   2. CPU зануляет counter buffer (4 байта).
 *   3. CPU пишет UBO: frustum planes, camera pos, fog end, region AABB, commandCount.
 *   4. dispatch compute → shader куллит регион (frustum + fog), компактирует команды,
 *      atomicAdd → counter buffer.
 *   5. glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT).
 *   6. bind output SSBO → GL_DRAW_INDIRECT_BUFFER, counter → GL_PARAMETER_BUFFER.
 *   7. glMultiDrawElementsIndirectCount — GPU читает drawCount из parameter buffer.
 *      **Нуль readback.** CPU никогда не спрашивает GPU «сколько команд видно».
 *
 * Если GPU не поддерживает compute (GL 4.3) или ARB_indirect_parameters —
 * culler не инициализируется, EmbediumInterop использует fallback-путь
 * (glMultiDrawElementsIndirect с CPU-provided count).
 * / If the GPU does not support compute (GL 4.3) or ARB_indirect_parameters,
 * the culler is not initialized and EmbediumInterop falls back to
 * glMultiDrawElementsIndirect with a CPU-provided count.
 *
 * Гранулярность куллинга: REGION (8x4x8 chunk-секций). Per-command куллинг —
 * future work (нужен per-command origin SSBO, требует доп. mixin в Embeddium).
 * / Culling granularity: REGION (8x4x8 chunk sections). Per-command culling is
 * future work (needs a per-command origin SSBO, requiring an additional Embeddium mixin).
 */
public class InteropComputeCuller {

    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();

    // --- GL constants (дублируем, чтобы не тащить весь GL46 ради 3 констант) ---
    private static final int GL_DRAW_INDIRECT_BUFFER = 0x8F3F;
    private static final int GL_PARAMETER_BUFFER     = 0x8EE0; // GL_ARB_indirect_parameters, core in 4.6
    private static final int GL_UNIFORM_BUFFER       = 0x8A11;
    private static final int GL_SHADER_STORAGE_BUFFER = 0x90D2;
    private static final int GL_COMMAND_BARRIER_BIT  = 0x40;
    private static final int GL_SHADER_STORAGE_BARRIER_BIT = 0x2000;

    // --- Размеры ---
    private static final int COMMAND_STRIDE = 20;          // sizeof(DrawElementsIndirectCommand)
    private static final int MAX_COMMANDS   = 4096;        // ModelQuadFacing.COUNT(8) * REGION_SIZE(256) + 1
    private static final int UBO_SIZE       = 160;         // см. chunk_culling_interop.comp.glsl
    private static final int WORKGROUP_SIZE = 64;          // AMD wavefront = 64

    // --- GL ресурсы ---
    private static int programId = -1;
    private static int uboId = -1;
    private static int inputBufferId = -1;
    private static int outputBufferId = -1;
    private static int counterBufferId = -1;
    private static long inputBufferSize = 0;
    private static long outputBufferSize = 0;

    // CPU staging для команд (заполняется из MultiDrawBatch Embeddium)
    private static ByteBuffer cpuStaging;

    // CPU staging для UBO
    private static ByteBuffer uboStaging;

    // Локация uniform-блока UBO (binding = 0)
    private static int uboBlockIndex = -1;

    private static boolean initialized = false;

    /** Захваченный region offset (из ChunkShaderInterface.setRegionOffset). / Captured region offset. */
    private static float pendingRegionOffsetX, pendingRegionOffsetY, pendingRegionOffsetZ;
    private static boolean hasPendingRegionOffset = false;

    public static synchronized void init() {
        if (initialized) return;

        // 1. Загружаем и линкуем compute-программу.
        String src = loadShaderSource();
        if (src == null) {
            LOGGER.error("[Amdium] InteropComputeCuller: не удалось загрузить compute-шейдер. "
                    + "GPU-culling в interop отключён.");
            // / [Amdium] InteropComputeCuller: failed to load compute shader. Interop GPU-culling disabled.
            return;
        }

        int shader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL43.glShaderSource(shader, src);
        GL43.glCompileShader(shader);
        if (GL43.glGetShaderi(shader, GL43.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL43.glGetShaderInfoLog(shader, 4096);
            LOGGER.error("[Amdium] Interop compute shader compile error:\n{}", log);
            GL43.glDeleteShader(shader);
            return;
        }

        programId = GL43.glCreateProgram();
        GL43.glAttachShader(programId, shader);
        GL43.glLinkProgram(programId);
        GL43.glDeleteShader(shader);
        if (GL43.glGetProgrami(programId, GL43.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL43.glGetProgramInfoLog(programId, 4096);
            LOGGER.error("[Amdium] Interop compute program link error:\n{}", log);
            GL43.glDeleteProgram(programId);
            programId = -1;
            return;
        }

        uboBlockIndex = GL43.glGetUniformBlockIndex(programId, "FrameData");
        if (uboBlockIndex != -1) {
            GL31.glUniformBlockBinding(programId, uboBlockIndex, 0);
        }

        // 2. UBO (persistent — перевыделять не надо, пишем glBufferSubData каждый кадр).
        uboId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_UNIFORM_BUFFER, uboId);
        GL15.glBufferData(GL_UNIFORM_BUFFER, UBO_SIZE, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL_UNIFORM_BUFFER, 0);

        // 3. Input/Output SSBOs (команды).
        inputBufferSize  = (long) MAX_COMMANDS * COMMAND_STRIDE;
        outputBufferSize = (long) MAX_COMMANDS * COMMAND_STRIDE;

        inputBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, inputBufferId);
        GL15.glBufferData(GL_SHADER_STORAGE_BUFFER, inputBufferSize, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        outputBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, outputBufferId);
        GL15.glBufferData(GL_SHADER_STORAGE_BUFFER, outputBufferSize, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        // 4. Counter buffer = parameter buffer (один буфер, 4 байта).
        counterBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, counterBufferId);
        GL15.glBufferData(GL_SHADER_STORAGE_BUFFER, 4, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        // 5. CPU staging buffers.
        cpuStaging = MemoryUtil.memAlloc((int) inputBufferSize);
        uboStaging = MemoryUtil.memAlloc(UBO_SIZE);

        initialized = true;
        LOGGER.info("[Amdium] InteropComputeCuller инициализирован. program={}, max commands={}, "
                + "workgroup={}.", programId, MAX_COMMANDS, WORKGROUP_SIZE);
        // / [Amdium] InteropComputeCuller initialized.
    }

    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * Захват region offset из ChunkShaderInterface.setRegionOffset (Embeddium).
     * / Capture region offset from Embeddium's ChunkShaderInterface.setRegionOffset.
     *
     * (x, y, z) — это region origin относительно камеры. Абсолютный region origin =
     * (camX + x, camY + y, camZ + z). Region AABB = [origin, origin + (128, 64, 128)].
     * / (x, y, z) is the region origin relative to the camera. Absolute region origin =
     * (camX + x, camY + y, camZ + z). Region AABB = [origin, origin + (128, 64, 128)].
     */
    public static void onRegionOffset(float x, float y, float z) {
        pendingRegionOffsetX = x;
        pendingRegionOffsetY = y;
        pendingRegionOffsetZ = z;
        hasPendingRegionOffset = true;
    }

    /**
     * Главная точка входа: выполнить GPU-culling + IndirectCount draw.
     * / Main entry point: perform GPU culling + IndirectCount draw.
     *
     * @param pElementPointer native pointer на long[] (byte offsets в IBO)
     * @param pElementCount   native pointer на int[] (кол-во индексов)
     * @param pBaseVertex     native pointer на int[] (base vertex offsets)
     * @param size            кол-во команд
     * @param indexTypeSize   размер индекса (4 для UNSIGNED_INT)
     * @param indexTypeFormat GL-константа типа индекса (GL_UNSIGNED_INT и т.д.)
     * @param projView        16 floats — projection×view matrix
     * @param camX/camY/camZ  позиция камеры (world space)
     * @param fogStart/fogEnd параметры тумана
     * @param fogColor        RGBA тумана (не используется culler'ом, но для fallback)
     * @return true если draw выполнен, false если fallback нужен
     */
    public static boolean drawWithCulling(
            long pElementPointer, long pElementCount, long pBaseVertex,
            int size, int indexTypeSize, int indexTypeFormat,
            float[] projView, float camX, float camY, float camZ,
            float fogStart, float fogEnd) {

        if (!initialized || size <= 0) return false;
        if (size > MAX_COMMANDS) return false; // fallback

        // --- 1. Извлекаем frustum planes из projView (Gribb & Hartmann) ---
        float[] frustum = new float[24];
        extractFrustumPlanes(projView, frustum);

        // --- 2. Считаем region AABB ---
        float regOriginX, regOriginY, regOriginZ;
        if (hasPendingRegionOffset) {
            regOriginX = camX + pendingRegionOffsetX;
            regOriginY = camY + pendingRegionOffsetY;
            regOriginZ = camZ + pendingRegionOffsetZ;
        } else {
            // Region offset не захвачен — AABB = весь мир (frustum test пройдёт всегда,
            // работает только fog-distance). Graceful degradation.
            regOriginX = camX;
            regOriginY = camY;
            regOriginZ = camZ;
        }
        // Region size: 8x4x8 chunks = 128x64x128 blocks.
        float regSizeX = 128f, regSizeY = 64f, regSizeZ = 128f;
        float regMinX = regOriginX, regMinY = regOriginY, regMinZ = regOriginZ;
        float regMaxX = regOriginX + regSizeX, regMaxY = regOriginY + regSizeY, regMaxZ = regOriginZ + regSizeZ;

        // --- 3. Копируем MultiDrawBatch → input SSBO ---
        cpuStaging.clear();
        for (int i = 0; i < size; i++) {
            int count = MemoryUtil.memGetInt(pElementCount + ((long) i * 4));
            long byteOffset = MemoryUtil.memGetLong(pElementPointer + ((long) i * 8));
            int firstIndex = (int) (byteOffset / indexTypeSize);
            int baseVertex = MemoryUtil.memGetInt(pBaseVertex + ((long) i * 4));
            cpuStaging.putInt(count);          // count
            cpuStaging.putInt(1);              // instanceCount
            cpuStaging.putInt(firstIndex);     // firstIndex
            cpuStaging.putInt(baseVertex);     // baseVertex
            cpuStaging.putInt(0);              // baseInstance
        }
        cpuStaging.flip();
        int bytesToUpload = size * COMMAND_STRIDE;

        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, inputBufferId);
        GL15.glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, cpuStaging.limit(bytesToUpload));
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        // --- 4. Зануляём counter (CPU пишет 0, compute только atomicAdd) ---
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer zero = stack.mallocInt(1);
            zero.put(0, 0);
            GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, counterBufferId);
            GL15.glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, zero);
            GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        }

        // --- 5. Заполняем UBO (std140, 160 байт) ---
        uboStaging.clear();
        // frustumPlanes[6]: 6 * vec4 = 96 байт
        for (int i = 0; i < 6; i++) {
            int b = i * 4;
            uboStaging.putFloat(frustum[b]);
            uboStaging.putFloat(frustum[b + 1]);
            uboStaging.putFloat(frustum[b + 2]);
            uboStaging.putFloat(frustum[b + 3]);
        }
        // cameraPos (vec3) + fogEnd (float) = 16 байт
        uboStaging.putFloat(camX);
        uboStaging.putFloat(camY);
        uboStaging.putFloat(camZ);
        uboStaging.putFloat(fogEnd);
        // regionMin (vec3) + pad = 16 байт
        uboStaging.putFloat(regMinX);
        uboStaging.putFloat(regMinY);
        uboStaging.putFloat(regMinZ);
        uboStaging.putFloat(0f);
        // regionMax (vec3) + pad = 16 байт
        uboStaging.putFloat(regMaxX);
        uboStaging.putFloat(regMaxY);
        uboStaging.putFloat(regMaxZ);
        uboStaging.putFloat(0f);
        // commandCount (uint) + 12 байт pad = 16 байт
        uboStaging.putInt(size);
        uboStaging.putInt(0);
        uboStaging.putInt(0);
        uboStaging.putInt(0);
        uboStaging.flip();

        GL15.glBindBuffer(GL_UNIFORM_BUFFER, uboId);
        GL15.glBufferSubData(GL_UNIFORM_BUFFER, 0, uboStaging);
        GL15.glBindBuffer(GL_UNIFORM_BUFFER, 0);

        // --- 6. Bind + dispatch compute ---
        GL43.glUseProgram(programId);

        // UBO → binding 0
        GL30.glBindBufferBase(GL_UNIFORM_BUFFER, 0, uboId);
        // SSBOs
        GL42.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, inputBufferId);
        GL42.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, outputBufferId);
        GL42.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, counterBufferId);

        int groups = (size + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
        GL43.glDispatchCompute(groups, 1, 1);

        // --- 7. Barrier: draw должен видеть записи compute ---
        GL42.glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);

        GL43.glUseProgram(0);

        // --- 8. Bind output → GL_DRAW_INDIRECT_BUFFER, counter → GL_PARAMETER_BUFFER ---
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, outputBufferId);
        GL15.glBindBuffer(GL_PARAMETER_BUFFER, counterBufferId);

        // --- 9. glMultiDrawElementsIndirectCount (нуль readback!) ---
        // signature: (mode, type, indirectOffset, parameterOffset, maxCount, stride)
        GL46.glMultiDrawElementsIndirectCount(
                GL11.GL_TRIANGLES,
                indexTypeFormat,
                0L,        // offset в GL_DRAW_INDIRECT_BUFFER
                0L,        // offset в GL_PARAMETER_BUFFER (читаем uint drawCount)
                size,      // maxCount (CPU safety limit)
                COMMAND_STRIDE);

        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        GL15.glBindBuffer(GL_PARAMETER_BUFFER, 0);

        // Сброс флага region offset (он устанавливается заново перед следующим batch)
        hasPendingRegionOffset = false;

        return true;
    }

    /**
     * Извлечение 6 frustum planes из projView (Gribb & Hartmann).
     * / Extract 6 frustum planes from projView (Gribb & Hartmann).
     *
     * planes[i] = (a, b, c, d): a*x + b*y + c*z + d >= 0 — внутри плоскости.
     * / planes[i] = (a, b, c, d): a*x + b*y + c*z + d >= 0 — inside the plane.
     */
    private static void extractFrustumPlanes(float[] m, float[] p) {
        // m в column-major (как от Matrix4f.get()): m[row + col*4]
        // row-major доступ ниже предполагает layout как из projView.get():
        //   m[0]=m00, m[1]=m01, ... m[15]=m33  (row-major)
        p[0]  = m[3] + m[0]; p[1]  = m[7] + m[4]; p[2]  = m[11] + m[8];  p[3]  = m[15] + m[12]; // left
        p[4]  = m[3] - m[0]; p[5]  = m[7] - m[4]; p[6]  = m[11] - m[8];  p[7]  = m[15] - m[12]; // right
        p[8]  = m[3] + m[1]; p[9]  = m[7] + m[5]; p[10] = m[11] + m[9];  p[11] = m[15] + m[13]; // bottom
        p[12] = m[3] - m[1]; p[13] = m[7] - m[5]; p[14] = m[11] - m[9];  p[15] = m[15] - m[13]; // top
        p[16] = m[3] + m[2]; p[17] = m[7] + m[6]; p[18] = m[11] + m[10]; p[19] = m[15] + m[14]; // near
        p[20] = m[3] - m[2]; p[21] = m[7] - m[6]; p[22] = m[11] - m[10]; p[23] = m[15] - m[14]; // far
        // Нормализация (не обязательна для positive-vertex test, но улучшает fog-distance)
        for (int i = 0; i < 6; i++) {
            int b = i * 4;
            float len = (float) Math.sqrt(p[b]*p[b] + p[b+1]*p[b+1] + p[b+2]*p[b+2]);
            if (len > 1e-6f) {
                p[b]   /= len; p[b+1] /= len; p[b+2] /= len; p[b+3] /= len;
            }
        }
    }

    private static String loadShaderSource() {
        String path = "/assets/amdium/shaders/core/chunk_culling_interop.comp.glsl";
        try (InputStream in = InteropComputeCuller.class.getResourceAsStream(path)) {
            if (in == null) {
                LOGGER.error("[Amdium] Compute shader not found on classpath: {}", path);
                return null;
            }
            byte[] bytes = in.readAllBytes();
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("[Amdium] Failed to read compute shader {}: {}", path, e.getMessage());
            return null;
        }
    }

    public static void destroy() {
        if (cpuStaging != null) { MemoryUtil.memFree(cpuStaging); cpuStaging = null; }
        if (uboStaging != null) { MemoryUtil.memFree(uboStaging); uboStaging = null; }
        if (programId != -1) { GL43.glDeleteProgram(programId); programId = -1; }
        if (uboId != -1) { GL15.glDeleteBuffers(uboId); uboId = -1; }
        if (inputBufferId != -1) { GL15.glDeleteBuffers(inputBufferId); inputBufferId = -1; }
        if (outputBufferId != -1) { GL15.glDeleteBuffers(outputBufferId); outputBufferId = -1; }
        if (counterBufferId != -1) { GL15.glDeleteBuffers(counterBufferId); counterBufferId = -1; }
        initialized = false;
    }
}

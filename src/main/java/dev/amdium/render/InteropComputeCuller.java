package dev.amdium.render;

import com.mojang.logging.LogUtils;
import dev.amdium.Amdium;
import dev.amdium.benchmark.AmdiumGpuTimer;
import dev.amdium.benchmark.AmdiumGpuCounterTelemetry;
import dev.amdium.benchmark.AmdiumTelemetry;
import dev.amdium.config.AmdiumConfig;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL46;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

/**
 * GPU-compute culler для interop-пути v2.2.
 * / GPU-compute culler for the interop path v2.2.
 *
 * Архитектура (Nvidium-style, расширенная per-command + Hi-Z):
 * / Architecture (Nvidium-style, extended with per-command + Hi-Z):
 *
 *   1. CPU копирует MultiDrawBatch Embedium → input SSBO (DrawCmd[]).
 *   2. CPU строит per-command chunkInfo SSBO из PerCommandMetadata:
 *      для каждой записи в batch лукап baseVertex → AABB.
 *   3. CPU зануляет counter buffer (4 байта).
 *   4. Bind Hi-Z pyramid как sampler2D на texture unit 5.
 *   5. CPU пишет uniforms: frustum (в projView), camera, fog, chunk count,
 *      Hi-Z dimensions/levels, enable flags.
 *   6. glDispatchCompute(ceil(size/64), 1, 1)
 *      compute-шейдер: per-thread тест AABB vs frustum + fog + Hi-Z,
 *      atomicAdd → counter buffer → compacted output buffer.
 *   7. glMemoryBarrier(SHADER_STORAGE | COMMAND).
 *   8. Bind output → GL_DRAW_INDIRECT_BUFFER, counter → GL_PARAMETER_BUFFER.
 *   9. glMultiDrawElementsIndirectCount — GPU читает drawCount из parameter buffer.
 *      **Нуль readback.** CPU никогда не спрашивает GPU «сколько команд видно».
 *
 * Fallbacks:
 *   - Если PerCommandMetadata пуст (mixin не сработал) → откат на путь B
 *     (прямой MDI с CPU-provided count, без culling). Это v2.1 поведение.
 *   - Если Hi-Z disabled в конфиге → uniform u_EnableHiZ = 0.
 *   - Если GPU не поддерживает compute / indirect_parameters → откат на путь B.
 *
 * / Fallbacks:
 *   - If PerCommandMetadata is empty (mixin didn't fire) → fall back to path B
 *     (direct MDI with CPU-provided count, no culling). This is v2.1 behavior.
 *   - If Hi-Z is disabled in config → uniform u_EnableHiZ = 0.
 *   - If the GPU does not support compute / indirect_parameters → fall back to path B.
 */
public class InteropComputeCuller {

    private static final Logger LOGGER = LogUtils.getLogger();

    // GL constants
    private static final int GL_DRAW_INDIRECT_BUFFER     = 0x8F3F;
    private static final int GL_PARAMETER_BUFFER         = 0x80EE;
    private static final int GL_UNIFORM_BUFFER           = 0x8A11;
    private static final int GL_SHADER_STORAGE_BUFFER    = 0x90D2;
    private static final int GL_COMMAND_BARRIER_BIT      = 0x40;
    private static final int GL_SHADER_STORAGE_BARRIER_BIT = 0x2000;
    private static final int GL_TEXTURE_FETCH_BARRIER_BIT  = 0x8;

    // Sizes
    private static final int COMMAND_STRIDE   = 20;       // sizeof(DrawElementsIndirectCommand)
    private static final int CHUNK_INFO_STRIDE = 48;      // 3 × vec4 = 48 bytes (см. шейдер)
    private static final int MAX_COMMANDS     = 4096;
    private static final int WORKGROUP_SIZE   = 64;       // AMD wavefront = 64

    // Hi-Z sampler binding (см. шейдер: layout(binding = 5))
    private static final int HIZ_TEXTURE_UNIT = 5;

    // GL resources
    private static int programId = -1;
    private static int uboId = -1;
    private static int inputBufferId = -1;       // input draw commands (from Embedium batch)
    private static int outputBufferId = -1;      // compacted output (visible commands)
    private static int counterBufferId = -1;     // atomic counter = drawCount
    private static int chunkInfoBufferId = -1;   // per-command AABB+origin

    private static long inputBufferSize = 0;
    private static long outputBufferSize = 0;
    private static long chunkInfoBufferSize = 0;

    // CPU staging
    private static ByteBuffer cpuStaging;        // for input commands
    private static ByteBuffer chunkInfoStaging;  // for per-command AABB

    // Uniform locations
    private static int u_ProjViewMatrix, u_ViewMatrix, u_ProjectionMatrix;
    private static int u_CameraPos, u_ChunkCount, u_FogStart, u_FogEnd;
    private static int u_HiZWidth, u_HiZHeight, u_HiZLevels;
    private static int u_EnableFrustum, u_EnableFog, u_EnableHiZ;

    private static boolean initialized = false;
    private static float currentRegionOffsetX = 0.0f;
    private static float currentRegionOffsetY = 0.0f;
    private static float currentRegionOffsetZ = 0.0f;

    // Singleton Hi-Z pyramid
    private static final HiZDepthPyramid hiZPyramid = new HiZDepthPyramid();

    /** Инициализация. / Initialization. */
    public static synchronized void init() {
        if (initialized) return;

        // 1. Load + link compute program.
        String src = loadShaderSource("/assets/amdium/shaders/core/chunk_culling_v2.comp.glsl");
        if (src == null) {
            LOGGER.error("[Amdium] InteropComputeCuller v2.2: compute shader not found.");
            return;
        }

        int shader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL43.glShaderSource(shader, src);
        GL43.glCompileShader(shader);
        if (GL43.glGetShaderi(shader, GL43.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL43.glGetShaderInfoLog(shader, 4096);
            LOGGER.error("[Amdium] v2.2 compute shader compile error:\n{}", log);
            GL43.glDeleteShader(shader);
            return;
        }

        programId = GL43.glCreateProgram();
        GL43.glAttachShader(programId, shader);
        GL43.glLinkProgram(programId);
        GL43.glDeleteShader(shader);
        if (GL43.glGetProgrami(programId, GL43.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL43.glGetProgramInfoLog(programId, 4096);
            LOGGER.error("[Amdium] v2.2 compute program link error:\n{}", log);
            GL43.glDeleteProgram(programId);
            programId = -1;
            return;
        }

        // Cache uniform locations.
        u_ProjViewMatrix   = GL20.glGetUniformLocation(programId, "u_ProjViewMatrix");
        u_ViewMatrix       = GL20.glGetUniformLocation(programId, "u_ViewMatrix");
        u_ProjectionMatrix = GL20.glGetUniformLocation(programId, "u_ProjectionMatrix");
        u_CameraPos        = GL20.glGetUniformLocation(programId, "u_CameraPos");
        u_ChunkCount       = GL20.glGetUniformLocation(programId, "u_ChunkCount");
        u_FogStart         = GL20.glGetUniformLocation(programId, "u_FogStart");
        u_FogEnd           = GL20.glGetUniformLocation(programId, "u_FogEnd");
        u_HiZWidth         = GL20.glGetUniformLocation(programId, "u_HiZWidth");
        u_HiZHeight        = GL20.glGetUniformLocation(programId, "u_HiZHeight");
        u_HiZLevels        = GL20.glGetUniformLocation(programId, "u_HiZLevels");
        u_EnableFrustum    = GL20.glGetUniformLocation(programId, "u_EnableFrustum");
        u_EnableFog        = GL20.glGetUniformLocation(programId, "u_EnableFog");
        u_EnableHiZ        = GL20.glGetUniformLocation(programId, "u_EnableHiZ");

        // 2. Allocate SSBOs.
        inputBufferSize     = (long) MAX_COMMANDS * COMMAND_STRIDE;
        outputBufferSize    = (long) MAX_COMMANDS * COMMAND_STRIDE;
        chunkInfoBufferSize = (long) MAX_COMMANDS * CHUNK_INFO_STRIDE;

        inputBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, inputBufferId);
        GL15.glBufferData(GL_SHADER_STORAGE_BUFFER, inputBufferSize, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        outputBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, outputBufferId);
        GL15.glBufferData(GL_SHADER_STORAGE_BUFFER, outputBufferSize, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        counterBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, counterBufferId);
        GL15.glBufferData(GL_SHADER_STORAGE_BUFFER, 4L * Integer.BYTES, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        chunkInfoBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, chunkInfoBufferId);
        GL15.glBufferData(GL_SHADER_STORAGE_BUFFER, chunkInfoBufferSize, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        // 3. CPU staging.
        cpuStaging       = MemoryUtil.memAlloc((int) inputBufferSize);
        chunkInfoStaging = MemoryUtil.memAlloc((int) chunkInfoBufferSize);

        // 4. Init Hi-Z pyramid. Build-only mode lets benchmarks measure copy/mip
        // cost without allowing the pyramid to reject terrain draw commands.
        if (isHiZBuildEnabled()) {
            hiZPyramid.init();
            if (hiZPyramid.isInitialized()) {
                if (isHiZOcclusionEnabled()) {
                    LOGGER.info("[Amdium] Hi-Z occlusion culling включён.");
                } else {
                    LOGGER.info("[Amdium] Hi-Z pyramid build/telemetry enabled; occlusion culling disabled.");
                }
            } else {
                LOGGER.warn("[Amdium] Hi-Z pyramid init failed — occlusion culling disabled.");
            }
        } else {
            LOGGER.info("[Amdium] Hi-Z occlusion culling выключен. "
                    + "Use -Damdium.experimental.hizBuildOnly=true to measure pyramid cost, "
                    + "or -Damdium.experimental.hizOcclusion=true for experimental terrain culling.");
        }

        initialized = true;
        LOGGER.info("[Amdium] InteropComputeCuller v2.2 готов. program={}, max commands={}, "
                + "workgroup={}.", programId, MAX_COMMANDS, WORKGROUP_SIZE);
    }

    /**
     * Вызывается из ChunkShaderInterfaceMixin при каждом setRegionOffset.
     * В v2.2 (per-command culling) region offset НЕ используется — per-command
     * AABB поставляется через PerCommandMetadata. Метод оставлен как no-op
     * для совместимости с ChunkShaderInterfaceMixin.
     *
     * / Called from ChunkShaderInterfaceMixin on each setRegionOffset.
     * In v2.2 (per-command culling), the region offset is NOT used — per-command
     * AABB is supplied via PerCommandMetadata. Method kept as no-op for
     * compatibility with ChunkShaderInterfaceMixin.
     */
    public static void onRegionOffset(float x, float y, float z) {
        currentRegionOffsetX = x;
        currentRegionOffsetY = y;
        currentRegionOffsetZ = z;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static HiZDepthPyramid getHiZPyramid() {
        return hiZPyramid;
    }

    /**
     * Главная точка входа: выполнить per-command GPU culling + IndirectCount draw.
     * / Main entry point: perform per-command GPU culling + IndirectCount draw.
     *
     * @param pElementPointer native pointer на long[] (byte offsets в IBO)
     * @param pElementCount   native pointer на int[] (кол-во индексов)
     * @param pBaseVertex     native pointer на int[] (base vertex offsets)
     * @param size            кол-во команд в batch
     * @param indexTypeSize   размер индекса (4 для UNSIGNED_INT)
     * @param indexTypeFormat GL-константа типа индекса
     * @param projView        16 floats — projection×view matrix (column-major JOML)
     * @param view            16 floats — view matrix only
     * @param projection      16 floats — projection matrix only
     * @param camX/camY/camZ  позиция камеры (world space)
     * @param fogStart/fogEnd параметры тумана
     * @return true если draw выполнен, false если fallback нужен
     */
    public static boolean drawWithCulling(
            long pElementPointer, long pElementCount, long pBaseVertex,
            int size, int indexTypeSize, int indexTypeFormat,
            float[] projView, float[] view, float[] projection,
            float camX, float camY, float camZ,
            float fogStart, float fogEnd) {

        if (!initialized || size <= 0) return false;
        if (size > MAX_COMMANDS) return false; // fallback

        // --- 1. Build per-command chunkInfo SSBO ---
        // Для каждой записи в batch: lookup baseVertex → SectionInfo → AABB.
        int regionOriginX = regionOrigin(Math.round(camX + currentRegionOffsetX));
        int regionOriginY = regionOriginY(Math.round(camY + currentRegionOffsetY));
        int regionOriginZ = regionOrigin(Math.round(camZ + currentRegionOffsetZ));
        int directRegionOriginX = regionOrigin(Math.round(currentRegionOffsetX));
        int directRegionOriginY = regionOriginY(Math.round(currentRegionOffsetY));
        int directRegionOriginZ = regionOrigin(Math.round(currentRegionOffsetZ));
        chunkInfoStaging.clear();
        int capturedCount = 0;
        for (int i = 0; i < size; i++) {
            int baseVertex = MemoryUtil.memGetInt(pBaseVertex + ((long) i * 4));
            PerCommandMetadata.SectionInfo info = PerCommandMetadata.findByBaseVertex(
                    regionOriginX, regionOriginY, regionOriginZ, baseVertex);
            if (info == null && (directRegionOriginX != regionOriginX
                    || directRegionOriginY != regionOriginY
                    || directRegionOriginZ != regionOriginZ)) {
                info = PerCommandMetadata.findByBaseVertex(
                        directRegionOriginX, directRegionOriginY, directRegionOriginZ, baseVertex);
            }

            if (info != null) {
                // 3 × vec4 = 48 байт на команду (см. структуру ChunkInfo в шейдере).
                // / 3 × vec4 = 48 bytes per command (see the ChunkInfo struct in the shader).
                // vec4 aabbMin_origin:    xyz=min, w=origin.x
                chunkInfoStaging.putFloat(info.aabbMinX);
                chunkInfoStaging.putFloat(info.aabbMinY);
                chunkInfoStaging.putFloat(info.aabbMinZ);
                chunkInfoStaging.putFloat(info.originX);
                // vec4 aabbMax_originY:   xyz=max, w=origin.y
                chunkInfoStaging.putFloat(info.aabbMaxX);
                chunkInfoStaging.putFloat(info.aabbMaxY);
                chunkInfoStaging.putFloat(info.aabbMaxZ);
                chunkInfoStaging.putFloat(info.originY);
                // vec4 padding_originZ:   xyz=0, w=origin.z
                chunkInfoStaging.putFloat(0f);
                chunkInfoStaging.putFloat(0f);
                chunkInfoStaging.putFloat(0f);
                chunkInfoStaging.putFloat(info.originZ);
                capturedCount++;
            } else {
                // No metadata for this command — fill with a huge AABB that passes all tests.
                // This way the command is drawn without culling (conservative).
                // / Заполняем огромным AABB, чтобы команда прошла все тесты (conservative).
                chunkInfoStaging.putFloat(-1e9f);
                chunkInfoStaging.putFloat(-1e9f);
                chunkInfoStaging.putFloat(-1e9f);
                chunkInfoStaging.putFloat(0f);
                chunkInfoStaging.putFloat( 1e9f);
                chunkInfoStaging.putFloat( 1e9f);
                chunkInfoStaging.putFloat( 1e9f);
                chunkInfoStaging.putFloat(0f);
                chunkInfoStaging.putFloat(0f);
                chunkInfoStaging.putFloat(0f);
                chunkInfoStaging.putFloat(0f);
                chunkInfoStaging.putFloat(0f);
            }
        }
        AmdiumTelemetry.recordInteropMetadata(size, size - capturedCount);
        chunkInfoStaging.flip();
        int chunkInfoBytes = size * CHUNK_INFO_STRIDE;

        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, chunkInfoBufferId);
        GL15.glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0,
                (ByteBuffer) chunkInfoStaging.limit(chunkInfoBytes));
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        AmdiumTelemetry.recordUploadBytes(chunkInfoBytes);
        AmdiumTelemetry.recordBufferSubDataBytes(chunkInfoBytes);

        // --- 2. Copy MultiDrawBatch → input SSBO (commands) ---
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
        int cmdBytes = size * COMMAND_STRIDE;

        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, inputBufferId);
        GL15.glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0,
                (ByteBuffer) cpuStaging.limit(cmdBytes));
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        AmdiumTelemetry.recordUploadBytes(cmdBytes);
        AmdiumTelemetry.recordBufferSubDataBytes(cmdBytes);

        // --- 3. Zero counter buffer ---
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer zero = stack.callocInt(4);
            GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, counterBufferId);
            GL15.glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, zero);
            GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
            AmdiumTelemetry.recordUploadBytes(4L * Integer.BYTES);
            AmdiumTelemetry.recordBufferSubDataBytes(4L * Integer.BYTES);
        }

        // --- 4. Bind Hi-Z pyramid on texture unit 5 ---
        boolean hiZReady = hiZPyramid.isInitialized()
                && isHiZOcclusionEnabled()
                && hiZPyramid.getTextureId() != -1;
        if (hiZReady) {
            hiZPyramid.bindAsSampler(HIZ_TEXTURE_UNIT);
        }

        // --- 5. Bind compute program + uniforms ---
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        GL43.glUseProgram(programId);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var pv = stack.mallocFloat(16);
            pv.put(projView).flip();
            GL20.glUniformMatrix4fv(u_ProjViewMatrix, false, pv);

            var v = stack.mallocFloat(16);
            v.put(view).flip();
            GL20.glUniformMatrix4fv(u_ViewMatrix, false, v);

            var p = stack.mallocFloat(16);
            p.put(projection).flip();
            GL20.glUniformMatrix4fv(u_ProjectionMatrix, false, p);
        }
        GL20.glUniform3f(u_CameraPos, camX, camY, camZ);
        GL30.glUniform1ui(u_ChunkCount, size);
        GL20.glUniform1f(u_FogStart, fogStart);
        GL20.glUniform1f(u_FogEnd, fogEnd);

        if (hiZReady) {
            GL30.glUniform1ui(u_HiZWidth,  hiZPyramid.getWidth());
            GL30.glUniform1ui(u_HiZHeight, hiZPyramid.getHeight());
            GL30.glUniform1ui(u_HiZLevels, hiZPyramid.getLevels());
            GL30.glUniform1ui(u_EnableHiZ, 1);
        } else {
            GL30.glUniform1ui(u_EnableHiZ, 0);
        }
        GL30.glUniform1ui(u_EnableFrustum, isInteropFrustumEnabled() ? 1 : 0);
        GL30.glUniform1ui(u_EnableFog,     isInteropFogEnabled()     ? 1 : 0);

        // Bind Hi-Z sampler to texture unit 5 (must match shader's layout(binding=5)).
        if (hiZReady) {
            GL20.glUniform1i(GL20.glGetUniformLocation(programId, "u_HiZPyramid"), HIZ_TEXTURE_UNIT);
        }

        // --- 6. Bind SSBOs ---
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, chunkInfoBufferId); // ChunkInfoBuffer
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, inputBufferId);     // InputCommands
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, outputBufferId);    // CompactedCommands
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, counterBufferId);   // AtomicCounter

        // --- 7. Dispatch ---
        int groups = (size + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
        AmdiumTelemetry.recordComputeDispatch(groups, 1, 1, WORKGROUP_SIZE, size);
        AmdiumGpuTimer.Scope computeScope = AmdiumGpuTimer.begin(AmdiumGpuTimer.INTEROP_COMPUTE_CULL);
        GL43.glDispatchCompute(groups, 1, 1);

        // --- 8. Barrier: draw must see compute's writes ---
        GL42.glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT
                | GL_TEXTURE_FETCH_BARRIER_BIT);
        AmdiumGpuTimer.end(computeScope);

        GL43.glUseProgram(previousProgram);

        if (hiZReady) {
            hiZPyramid.unbind(HIZ_TEXTURE_UNIT);
        }

        // --- 9. Bind output → GL_DRAW_INDIRECT_BUFFER, counter → GL_PARAMETER_BUFFER ---
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, outputBufferId);
        GL15.glBindBuffer(GL_PARAMETER_BUFFER, counterBufferId);

        // --- 10. glMultiDrawElementsIndirectCount (zero readback!) ---
        AmdiumGpuTimer.Scope drawScope = AmdiumGpuTimer.begin(AmdiumGpuTimer.INTEROP_MDI_DRAW);
        GL46.glMultiDrawElementsIndirectCount(
                GL11.GL_TRIANGLES,
                indexTypeFormat,
                0L,        // offset in GL_DRAW_INDIRECT_BUFFER
                0L,        // offset in GL_PARAMETER_BUFFER (read uint drawCount)
                size,      // maxCount (CPU safety limit)
                COMMAND_STRIDE);
        AmdiumGpuTimer.end(drawScope);

        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        GL15.glBindBuffer(GL_PARAMETER_BUFFER, 0);
        AmdiumGpuCounterTelemetry.captureCounter(counterBufferId, size);

        return true;
    }

    /**
     * Обновляет Hi-Z пирамиду. Должно вызываться В КОНЦЕ КАЖДОГО КАДРА,
     * после всех chunk render'ов.
     * / Updates the Hi-Z pyramid. Must be called AT THE END OF EACH FRAME,
     * after all chunk renders.
     */
    public static void endFrameUpdateHiZ() {
        if (initialized && isHiZBuildEnabled() && hiZPyramid.isInitialized()) {
            long start = System.nanoTime();
            hiZPyramid.update();
            AmdiumTelemetry.recordHiZUpdate(System.nanoTime() - start, hiZPyramid.getTextureId() != -1);
        }
    }

    private static String loadShaderSource(String path) {
        try (InputStream in = InteropComputeCuller.class.getResourceAsStream(path)) {
            if (in == null) {
                LOGGER.error("[Amdium] Compute shader not found on classpath: {}", path);
                return null;
            }
            byte[] bytes = in.readAllBytes();
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("[Amdium] Failed to read compute shader {}: {}", path, e.getMessage());
            return null;
        }
    }

    public static void destroy() {
        if (cpuStaging != null)       { MemoryUtil.memFree(cpuStaging);       cpuStaging = null; }
        if (chunkInfoStaging != null) { MemoryUtil.memFree(chunkInfoStaging); chunkInfoStaging = null; }
        if (programId != -1)          { GL43.glDeleteProgram(programId);      programId = -1; }
        if (inputBufferId != -1)      { GL15.glDeleteBuffers(inputBufferId);  inputBufferId = -1; }
        if (outputBufferId != -1)     { GL15.glDeleteBuffers(outputBufferId); outputBufferId = -1; }
        if (counterBufferId != -1)    { GL15.glDeleteBuffers(counterBufferId);counterBufferId = -1; }
        if (chunkInfoBufferId != -1)  { GL15.glDeleteBuffers(chunkInfoBufferId); chunkInfoBufferId = -1; }
        hiZPyramid.destroy();
        initialized = false;
    }

    private static int regionOrigin(int blockOrigin) {
        return Math.floorDiv(blockOrigin, 128) * 128;
    }

    private static int regionOriginY(int blockOriginY) {
        return Math.floorDiv(blockOriginY, 64) * 64;
    }

    private static boolean isHiZOcclusionEnabled() {
        return Boolean.getBoolean("amdium.experimental.hizOcclusion")
                && !Boolean.getBoolean("amdium.experimental.hizBuildOnly");
    }

    private static boolean isHiZBuildEnabled() {
        return Boolean.getBoolean("amdium.experimental.hizBuildOnly")
                || isHiZOcclusionEnabled();
    }

    private static boolean isInteropFrustumEnabled() {
        return AmdiumConfig.ENABLE_INTEROP_FRUSTUM.get();
    }

    private static boolean isInteropFogEnabled() {
        return (AmdiumConfig.ENABLE_INTEROP_FOG.get()
                || Boolean.getBoolean("amdium.experimental.forceInteropFog"))
                && !Boolean.getBoolean("amdium.experimental.disableInteropFog");
    }
}

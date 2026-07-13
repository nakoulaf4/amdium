package dev.amdium.render;

import com.mojang.logging.LogUtils;
import dev.amdium.Amdium;
import dev.amdium.config.AmdiumConfig;
import dev.amdium.gl.RingStreamBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL42;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GL45;
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
 * GPU-compute culler для interop-пути v2.3.3.
 *
 * v2.2 → v2.3 фиксы:
 *   1. glClearNamedBufferSubData для counter reset (без CPU upload, без sync).
 *   2. RingStreamBuffer для per-frame chunkInfo + inputCommands uploads.
 *   3. Убран GL_TEXTURE_FETCH_BARRIER_BIT из post-dispatch barrier.
 *   4. Ring из FRAMES_IN_FLIGHT output + counter buffer'ов.
 *   5. Per-command loop: один memGetInt на команду, AABB из SectionInfo.
 *   6. u_HiZPyramid location кэшируется.
 *   7. Enable flags ставятся один раз при init.
 *
 * v2.3.3 фиксы:
 *   - Убран F3 debug overlay и весь связанный код (maybeCaptureDebugStats,
 *     debug staging buffers, DebugCullStats и пр.).
 *   - Вместо copyTo() (GPU→GPU копия ring→fixed buffers) используется
 *     glBindBufferRange — данные читаются шейдером прямо из ring buffer
 *     по нужному offset'у. Это убирает 2 лишних GPU-копии на каждый dispatch.
 *   - RingStreamBuffer.write() теперь учитывает SSBO offset alignment.
 *   - Убраны chunkInfoBufferId / inputBufferId (больше не нужны).
 *   - Убран fallback на glBufferSubData (больше нет фиксированных буферов
 *     для fallback'а — ring биндится напрямую).
 */
public class InteropComputeCuller {

    private static final Logger LOGGER = LogUtils.getLogger();

    // GL constants
    private static final int GL_DRAW_INDIRECT_BUFFER     = 0x8F3F;
    private static final int GL_PARAMETER_BUFFER         = 0x8EE0;
    private static final int GL_SHADER_STORAGE_BUFFER    = 0x90D2;
    private static final int GL_COMMAND_BARRIER_BIT      = 0x40;
    private static final int GL_SHADER_STORAGE_BARRIER_BIT = 0x2000;

    // Sizes
    private static final int COMMAND_STRIDE   = 20;       // sizeof(DrawElementsIndirectCommand)
    private static final int CHUNK_INFO_STRIDE = 48;      // 3 × vec4 = 48 bytes
    private static final int MAX_COMMANDS     = 4096;
    private static int WORKGROUP_SIZE = 64;               // AMD wavefront = 64 (default, configurable)

    // Hi-Z sampler binding (layout(binding = 5) в шейдере)
    private static final int HIZ_TEXTURE_UNIT = 5;

    private static int RING_SIZE = 3;

    // GL resources
    private static int programId = -1;
    private static int[] outputBufferRing;
    private static int[] counterBufferRing;

    private static long inputBufferSize = 0;
    private static long outputBufferSize = 0;
    private static long chunkInfoBufferSize = 0;

    private static RingStreamBuffer ringStream;

    // CPU staging
    private static ByteBuffer cpuStaging;
    private static ByteBuffer chunkInfoStaging;

    // SSBO offset alignment (query at init)
    private static int ssboOffsetAlignment = 16; // safe default

    // Uniform locations
    private static int u_ProjViewMatrix, u_ViewMatrix, u_ProjectionMatrix;
    private static int u_CameraPos, u_ChunkCount, u_FogStart, u_FogEnd;
    private static int u_HiZWidth, u_HiZHeight, u_HiZLevels;
    private static int u_EnableFrustum, u_EnableFog, u_EnableHiZ;
    private static int u_HiZPyramidSampler;

    private static int cachedEnableFrustum = -1;
    private static int cachedEnableFog = -1;
    private static int cachedEnableHiZ = -1;

    private static boolean initialized = false;

    private static int ringIndex = 0;

    private static final HiZDepthPyramid hiZPyramid = new HiZDepthPyramid();

    // Public getters (могут использоваться в логах).
    public static boolean isRingStreamEnabled() { return ringStream != null; }
    public static boolean isHiZActive() {
        return initialized && hiZPyramid.isInitialized() && cachedEnableHiZ == 1;
    }
    public static int getRingSize() { return RING_SIZE; }
    public static int getMaxCommands() { return MAX_COMMANDS; }
    public static int getWorkgroupSize() { return WORKGROUP_SIZE; }

    /** Инициализация. */
    public static synchronized void init() {
        if (initialized) return;

        RING_SIZE = Math.max(2, AmdiumConfig.FRAMES_IN_FLIGHT.get());
        WORKGROUP_SIZE = AmdiumConfig.INTEROP_WORKGROUP_SIZE.get();

        if (!compileAndLinkProgram()) return;

        cacheUniformLocations();

        // Query SSBO offset alignment — нужно для glBindBufferRange.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer alignBuf = stack.mallocInt(1);
            org.lwjgl.opengl.GL44.glGetIntegeri_v(
                    org.lwjgl.opengl.GL44.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT, 0, alignBuf);
            ssboOffsetAlignment = alignBuf.get(0);
        }
        LOGGER.info("[Amdium] SSBO offset alignment: {} bytes", ssboOffsetAlignment);

        // Output + counter ring buffers
        outputBufferSize = (long) MAX_COMMANDS * COMMAND_STRIDE;
        outputBufferRing = new int[RING_SIZE];
        counterBufferRing = new int[RING_SIZE];
        for (int i = 0; i < RING_SIZE; i++) {
            outputBufferRing[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, outputBufferRing[i]);
            GL15.glBufferData(GL_SHADER_STORAGE_BUFFER, outputBufferSize, GL15.GL_DYNAMIC_COPY);

            counterBufferRing[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, counterBufferRing[i]);
            GL15.glBufferData(GL_SHADER_STORAGE_BUFFER, 4, GL15.GL_DYNAMIC_COPY);
        }
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        // CPU staging
        inputBufferSize     = (long) MAX_COMMANDS * COMMAND_STRIDE;
        chunkInfoBufferSize = (long) MAX_COMMANDS * CHUNK_INFO_STRIDE;
        cpuStaging       = MemoryUtil.memAlloc((int) inputBufferSize);
        chunkInfoStaging = MemoryUtil.memAlloc((int) chunkInfoBufferSize);

        // RingStreamBuffer — persistent-mapped ring с per-slot fences.
        // С запасом на выравнивание: каждый frame slice может иметь
        // дополнительный overhead до (ssboAlignment-1) байт на каждую запись.
        long perFrameBytes = inputBufferSize + chunkInfoBufferSize
                + (long) ssboOffsetAlignment * 2; // worst-case alignment padding
        long ringTotalSize = perFrameBytes * RING_SIZE;
        try {
            ringStream = new RingStreamBuffer(ringTotalSize, RING_SIZE, ssboOffsetAlignment);
        } catch (Exception e) {
            LOGGER.error("[Amdium] RingStreamBuffer init failed: {}", e.getMessage());
            ringStream = null;
        }

        // Hi-Z pyramid
        if (AmdiumConfig.ENABLE_HIZ_OCCLUSION.get()) {
            hiZPyramid.init();
            if (hiZPyramid.isInitialized()) {
                LOGGER.info("[Amdium] Hi-Z occlusion culling включён.");
            } else {
                LOGGER.warn("[Amdium] Hi-Z pyramid init failed — occlusion culling disabled.");
            }
        } else {
            LOGGER.info("[Amdium] Hi-Z occlusion culling выключен в конфиге.");
        }

        cachedEnableFrustum = AmdiumConfig.ENABLE_INTEROP_FRUSTUM.get() ? 1 : 0;
        cachedEnableFog     = AmdiumConfig.ENABLE_INTEROP_FOG.get()     ? 1 : 0;
        cachedEnableHiZ     = (hiZPyramid.isInitialized()
                && AmdiumConfig.ENABLE_HIZ_OCCLUSION.get()) ? 1 : 0;

        if (!initialized) {
            initialized = true;
            LOGGER.info("[Amdium] InteropComputeCuller v2.3.3 готов. program={}, max commands={}, "
                    + "workgroup={}, ringSize={}, ringStream={}, ssboAlign={}",
                    programId, MAX_COMMANDS, WORKGROUP_SIZE, RING_SIZE,
                    ringStream != null ? "enabled" : "disabled", ssboOffsetAlignment);
        }
    }

    public static void onRegionOffset(float x, float y, float z) {
        // no-op: per-command AABB comes from PerCommandMetadata SSBO.
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static HiZDepthPyramid getHiZPyramid() {
        return hiZPyramid;
    }

    /**
     * Главная точка входа: per-command GPU culling + IndirectCount draw.
     * Данные (chunkInfo, inputCommands) пишутся в ring buffer и биндятся
     * через glBindBufferRange — без промежуточных GPU-копий.
     */
    public static boolean drawWithCulling(
            long pElementPointer, long pElementCount, long pBaseVertex,
            int size, int indexTypeSize, int indexTypeFormat,
            float[] projView, float[] view, float[] projection,
            float camX, float camY, float camZ,
            float fogStart, float fogEnd) {

        if (!initialized || size <= 0) return false;
        if (size > MAX_COMMANDS) return false;
        if (ringStream == null) return false; // ring required since v2.3.3

        int slot = ringIndex;
        ringIndex = (ringIndex + 1) % RING_SIZE;
        int outBufId      = outputBufferRing[slot];
        int counterBufId  = counterBufferRing[slot];

        // 1. Build per-command chunkInfo + input commands в staging buffers.
        chunkInfoStaging.clear();
        cpuStaging.clear();

        for (int i = 0; i < size; i++) {
            int baseVertex = MemoryUtil.memGetInt(pBaseVertex + ((long) i * 4));

            PerCommandMetadata.SectionInfo info = PerCommandMetadata.findByBaseVertex(baseVertex);
            if (info != null) {
                chunkInfoStaging.putFloat(info.aabbMinX);
                chunkInfoStaging.putFloat(info.aabbMinY);
                chunkInfoStaging.putFloat(info.aabbMinZ);
                chunkInfoStaging.putFloat(info.originX);
                chunkInfoStaging.putFloat(info.aabbMaxX);
                chunkInfoStaging.putFloat(info.aabbMaxY);
                chunkInfoStaging.putFloat(info.aabbMaxZ);
                chunkInfoStaging.putFloat(info.originY);
                chunkInfoStaging.putFloat(0f);
                chunkInfoStaging.putFloat(0f);
                chunkInfoStaging.putFloat(0f);
                chunkInfoStaging.putFloat(info.originZ);
            } else {
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
            }

            int count = MemoryUtil.memGetInt(pElementCount + ((long) i * 4));
            long byteOffset = MemoryUtil.memGetLong(pElementPointer + ((long) i * 8));
            int firstIndex = (int) (byteOffset / indexTypeSize);
            cpuStaging.putInt(count);
            cpuStaging.putInt(1);
            cpuStaging.putInt(firstIndex);
            cpuStaging.putInt(baseVertex);
            cpuStaging.putInt(0);
        }
        chunkInfoStaging.flip();
        cpuStaging.flip();
        int chunkInfoBytes = size * CHUNK_INFO_STRIDE;
        int cmdBytes = size * COMMAND_STRIDE;

        // 2. Пишем в ring buffer (с выравниванием по ssboOffsetAlignment).
        long ciOff = ringStream.write(chunkInfoStaging, chunkInfoBytes);
        long cmdOff = ringStream.write(cpuStaging, cmdBytes);
        if (ciOff < 0 || cmdOff < 0) {
            // Ring overflow — fallback: пропускаем GPU-culling для этого кадра.
            return false;
        }

        // 3. Zero counter buffer
        GL45.glClearNamedBufferSubData(counterBufId,
                GL_R32UI_INTERNAL, 0, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, zeroInt);

        // 4. Bind Hi-Z pyramid
        boolean hiZReady = hiZPyramid.isInitialized()
                && AmdiumConfig.ENABLE_HIZ_OCCLUSION.get()
                && hiZPyramid.getTextureId() != -1;
        if (hiZReady) {
            hiZPyramid.bindAsSampler(HIZ_TEXTURE_UNIT);
        }

        // 5. Bind program + uniforms
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
        GL20.glUniform1i(u_ChunkCount, size);
        GL20.glUniform1f(u_FogStart, fogStart);
        GL20.glUniform1f(u_FogEnd, fogEnd);

        if (hiZReady) {
            GL20.glUniform1i(u_HiZWidth,  hiZPyramid.getWidth());
            GL20.glUniform1i(u_HiZHeight, hiZPyramid.getHeight());
            GL20.glUniform1i(u_HiZLevels, hiZPyramid.getLevels());
        }
        GL20.glUniform1i(u_EnableHiZ, hiZReady ? 1 : 0);
        GL20.glUniform1i(u_EnableFrustum, cachedEnableFrustum);
        GL20.glUniform1i(u_EnableFog,     cachedEnableFog);

        if (hiZReady) {
            GL20.glUniform1i(u_HiZPyramidSampler, HIZ_TEXTURE_UNIT);
        }

        // 6. Bind SSBOs: binding 0/1 — ring buffer через glBindBufferRange,
        //    binding 2/3 — ring slot output/counter через glBindBufferBase.
        GL30.glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0,
                ringStream.getId(), ciOff, chunkInfoBytes);
        GL30.glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 1,
                ringStream.getId(), cmdOff, cmdBytes);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer bufs = stack.mallocInt(2);
            bufs.put(0, outBufId);     // binding 2: CompactedCommands
            bufs.put(1, counterBufId);  // binding 3: AtomicCounter
            org.lwjgl.opengl.GL44.glBindBuffersBase(GL_SHADER_STORAGE_BUFFER, 2, bufs);
        }

        // 7. Dispatch
        int groups = (size + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
        GL43.glDispatchCompute(groups, 1, 1);

        // 8. Barrier (SHADER_STORAGE + COMMAND — без лишнего TEXTURE_FETCH)
        GL42.glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);

        GL43.glUseProgram(0);

        if (hiZReady) {
            hiZPyramid.unbind(HIZ_TEXTURE_UNIT);
        }

        // 9. Bind output → indirect draw
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, outBufId);
        GL15.glBindBuffer(GL_PARAMETER_BUFFER, counterBufId);

        // 10. glMultiDrawElementsIndirectCount (zero readback)
        GL46.glMultiDrawElementsIndirectCount(
                GL11.GL_TRIANGLES,
                indexTypeFormat,
                0L, 0L, size, COMMAND_STRIDE);

        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        GL15.glBindBuffer(GL_PARAMETER_BUFFER, 0);

        return true;
    }

    public static void beginFrame() {
        if (ringStream != null) {
            ringStream.beginFrame();
        }
    }

    public static void endFrameUpdateHiZ() {
        if (initialized && hiZPyramid.isInitialized()) {
            hiZPyramid.update();
        }
        if (ringStream != null) {
            ringStream.endFrame();
        }
    }

    // Constants for glClearNamedBufferSubData
    private static final int GL_R32UI_INTERNAL = 0x8236;
    private static final int GL_RED_INTEGER = 0x8D94;
    private static final int GL_UNSIGNED_INT = 0x1405;

    private static final IntBuffer zeroInt = createZeroInt();

    private static IntBuffer createZeroInt() {
        IntBuffer b = ByteBuffer.allocateDirect(4)
                .order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
        b.put(0, 0);
        return b;
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
        if (ringStream != null)       { ringStream.destroy(); ringStream = null; }
        if (programId != -1)          { GL43.glDeleteProgram(programId);      programId = -1; }
        if (outputBufferRing != null) {
            for (int id : outputBufferRing) if (id != -1) GL15.glDeleteBuffers(id);
            outputBufferRing = null;
        }
        if (counterBufferRing != null) {
            for (int id : counterBufferRing) if (id != -1) GL15.glDeleteBuffers(id);
            counterBufferRing = null;
        }
        hiZPyramid.destroy();
        initialized = false;
    }

    private static boolean compileAndLinkProgram() {
        String src = loadShaderSource("/assets/amdium/shaders/core/chunk_culling_v2.comp.glsl");
        if (src == null) {
            LOGGER.error("[Amdium] InteropComputeCuller: compute shader not found.");
            return false;
        }

        // Подставляем WORKGROUP_SIZE в layout qualifier.
        src = src.replace(
                "layout(local_size_x = 64, local_size_y = 1, local_size_z = 1) in;",
                "layout(local_size_x = " + WORKGROUP_SIZE + ", local_size_y = 1, local_size_z = 1) in;");

        int shader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL43.glShaderSource(shader, src);
        GL43.glCompileShader(shader);
        if (GL43.glGetShaderi(shader, GL43.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL43.glGetShaderInfoLog(shader, 4096);
            LOGGER.error("[Amdium] compute shader compile error (wg={}):\n{}", WORKGROUP_SIZE, log);
            GL43.glDeleteShader(shader);
            return false;
        }

        int newProgram = GL43.glCreateProgram();
        GL43.glAttachShader(newProgram, shader);
        GL43.glLinkProgram(newProgram);
        GL43.glDeleteShader(shader);
        if (GL43.glGetProgrami(newProgram, GL43.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL43.glGetProgramInfoLog(newProgram, 4096);
            LOGGER.error("[Amdium] compute program link error (wg={}):\n{}", WORKGROUP_SIZE, log);
            GL43.glDeleteProgram(newProgram);
            return false;
        }

        if (programId != -1) {
            GL43.glDeleteProgram(programId);
        }
        programId = newProgram;
        return true;
    }

    private static void cacheUniformLocations() {
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
        u_HiZPyramidSampler = GL20.glGetUniformLocation(programId, "u_HiZPyramid");
    }

    /** Перезагрузка конфига: перекомпиляция шейдера если WORKGROUP_SIZE изменился. */
    public static synchronized void refreshConfig() {
        if (!initialized) return;

        int newWg = AmdiumConfig.INTEROP_WORKGROUP_SIZE.get();
        boolean wgChanged = (newWg != WORKGROUP_SIZE);

        cachedEnableFrustum = AmdiumConfig.ENABLE_INTEROP_FRUSTUM.get() ? 1 : 0;
        cachedEnableFog     = AmdiumConfig.ENABLE_INTEROP_FOG.get()     ? 1 : 0;
        cachedEnableHiZ     = (hiZPyramid.isInitialized()
                && AmdiumConfig.ENABLE_HIZ_OCCLUSION.get()) ? 1 : 0;

        if (wgChanged) {
            int oldWg = WORKGROUP_SIZE;
            WORKGROUP_SIZE = newWg;
            LOGGER.info("[Amdium] Перекомпиляция interop compute shader: workgroup {} -> {}",
                    oldWg, newWg);
            if (compileAndLinkProgram()) {
                cacheUniformLocations();
            } else {
                LOGGER.error("[Amdium] Перекомпиляция не удалась, откат workgroup {} -> {}",
                        newWg, oldWg);
                WORKGROUP_SIZE = oldWg;
                if (compileAndLinkProgram()) {
                    cacheUniformLocations();
                }
            }
        }
    }
}
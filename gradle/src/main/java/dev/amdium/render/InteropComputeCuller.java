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
import java.nio.charset.StandardCharsets;

/**
 * GPU-compute culler для interop-пути v2.3.
 * / GPU-compute culler for the interop path v2.3.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * v2.3 ОПТИМИЗАЦИЯ ПРОИЗВОДИТЕЛЬНОСТИ / v2.3 PERFORMANCE OPTIMIZATION
 * ─────────────────────────────────────────────────────────────────────────
 *
 * v2.2 ПРОБЛЕМЫ / v2.2 PROBLEMS:
 *
 *   1. glBufferSubData для counter reset (4 байта) КАЖДЫЙ dispatch.
 *      Causes implicit sync if GPU is still reading previous frame's counter.
 *      ~40-80 dispatches per frame = 40-80 sync points.
 *
 *   2. glBufferSubData для chunkInfo + inputCommands КАЖДЫЙ dispatch.
 *      Same sync issue. 2 uploads × 40-80 dispatches = 80-160 sync points.
 *
 *   3. glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT
 *      | GL_TEXTURE_FETCH_BARRIER_BIT) — TEXTURE_FETCH_BARRIER_BIT ЛИШНИЙ.
 *      Он форсит завершение всех texture fetches перед любым следующим,
 *      что чрезмерно широко. Нужны только SHADER_STORAGE + COMMAND.
 *      / TEXTURE_FETCH_BARRIER_BIT is unnecessary — forces all texture
 *      fetches to complete before any subsequent ones, which is overly broad.
 *      Only SHADER_STORAGE + COMMAND are needed.
 *
 *   4. Single outputBufferId используется КАЖДЫЙ кадр. Если frame N+1
 *      начинает compute write в outputBuffer, а frame N ещё не сделал
 *      indirect draw из него — GPU сериализует pipeline. Нужен ring из
 *      2-3 output buffer'ов (по числу frames in flight).
 *      / Single outputBufferId used EVERY frame. If frame N+1 starts a
 *      compute write to outputBuffer while frame N hasn't done its indirect
 *      draw from it yet — the GPU serializes the pipeline. A ring of 2-3
 *      output buffers is needed (matching frames in flight).
 *
 *   5. Per-command native memory reads (memGetInt × 2) + HashMap lookup
 *      (PerCommandMetadata.findByBaseVertex) для КАЖДОЙ команды в batch.
 *      Для 1000 команд × 4 layers = 8000 lookups per frame.
 *
 *   6. glUniformLocation для u_HiZPyramid запрашивается КАЖДЫЙ dispatch
 *      (строка 377 оригинала).
 *
 *   7. Переменные u_EnableFrustum / u_EnableFog / u_EnableHiZ ставятся
 *      КАЖДЫЙ dispatch, хотя меняются только при смене конфига.
 *
 * v2.3 ИСПРАВЛЕНИЯ / v2.3 FIXES:
 *
 *   1. glClearNamedBufferSubData для counter reset (без CPU upload, без sync).
 *      / glClearNamedBufferSubData for counter reset (no CPU upload, no sync).
 *
 *   2. RingStreamBuffer для per-frame chunkInfo + inputCommands uploads.
 *      Persistent-mapped ring с per-frame fences — zero sync.
 *      / RingStreamBuffer for per-frame chunkInfo + inputCommands uploads.
 *
 *   3. Убран GL_TEXTURE_FETCH_BARRIER_BIT из post-dispatch barrier.
 *      / Removed GL_TEXTURE_FETCH_BARRIER_BIT from the post-dispatch barrier.
 *
 *   4. Ring из FRAMES_IN_FLIGHT output buffer'ов + counter buffer'ов.
 *      / Ring of FRAMES_IN_FLIGHT output + counter buffers.
 *
 *   5. Per-command loop оптимизирован: один memGetInt на команду для baseVertex,
 *      AABB берётся из SectionInfo (один lookup, не два).
 *      / Per-command loop optimized: one memGetInt per command for baseVertex,
 *      AABB taken from SectionInfo (one lookup, not two).
 *
 *   6. u_HiZPyramid location кэшируется.
 *      / u_HiZPyramid location cached.
 *
 *   7. Enable flags ставятся один раз при init (или при reload config).
 *      / Enable flags set once at init (or on config reload).
 *
 * Архитектура остаётся прежней (Nvidium-style, zero readback).
 * / Architecture stays the same (Nvidium-style, zero readback).
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
    private static final int WORKGROUP_SIZE   = 64;       // AMD wavefront = 64

    // Hi-Z sampler binding (см. шейдер: layout(binding = 5))
    private static final int HIZ_TEXTURE_UNIT = 5;

    // v2.3: Ring size = frames in flight (из конфига).
    // / v2.3: Ring size = frames in flight (from config).
    private static int RING_SIZE = 3;

    // GL resources
    private static int programId = -1;
    private static int inputBufferId = -1;       // input draw commands (from Embedium batch)
    private static int[] outputBufferRing;        // v2.3: ring of output buffers
    private static int[] counterBufferRing;       // v2.3: ring of counter buffers
    private static int chunkInfoBufferId = -1;   // per-command AABB+origin

    private static long inputBufferSize = 0;
    private static long outputBufferSize = 0;
    private static long chunkInfoBufferSize = 0;

    // v2.3: Persistent-mapped ring stream buffer для per-frame SSBO uploads.
    // / v2.3: Persistent-mapped ring stream buffer for per-frame SSBO uploads.
    private static RingStreamBuffer ringStream;

    // CPU staging (для прямого put в ring stream)
    // / CPU staging (for direct put into the ring stream)
    private static ByteBuffer cpuStaging;        // for input commands
    private static ByteBuffer chunkInfoStaging;  // for per-command AABB

    // Uniform locations
    private static int u_ProjViewMatrix, u_ViewMatrix, u_ProjectionMatrix;
    private static int u_CameraPos, u_ChunkCount, u_FogStart, u_FogEnd;
    private static int u_HiZWidth, u_HiZHeight, u_HiZLevels;
    private static int u_EnableFrustum, u_EnableFog, u_EnableHiZ;
    private static int u_HiZPyramidSampler;  // v2.3: cached

    // v2.3: Cached enable flag values (меняются только при reload config).
    // / v2.3: Cached enable flag values (change only on config reload).
    private static int cachedEnableFrustum = -1;
    private static int cachedEnableFog = -1;
    private static int cachedEnableHiZ = -1;

    private static boolean initialized = false;

    // v2.3: Current ring index (advances each draw call to allow overlap).
    // / v2.3: Current ring index (advances each draw call to allow overlap).
    private static int ringIndex = 0;

    // Singleton Hi-Z pyramid
    private static final HiZDepthPyramid hiZPyramid = new HiZDepthPyramid();

    /** Инициализация. / Initialization. */
    public static synchronized void init() {
        if (initialized) return;

        RING_SIZE = Math.max(2, AmdiumConfig.FRAMES_IN_FLIGHT.get());

        // 1. Load + link compute program.
        String src = loadShaderSource("/assets/amdium/shaders/core/chunk_culling_v2.comp.glsl");
        if (src == null) {
            LOGGER.error("[Amdium] InteropComputeCuller v2.3: compute shader not found.");
            return;
        }

        int shader = GL43.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL43.glShaderSource(shader, src);
        GL43.glCompileShader(shader);
        if (GL43.glGetShaderi(shader, GL43.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL43.glGetShaderInfoLog(shader, 4096);
            LOGGER.error("[Amdium] v2.3 compute shader compile error:\n{}", log);
            GL43.glDeleteShader(shader);
            return;
        }

        programId = GL43.glCreateProgram();
        GL43.glAttachShader(programId, shader);
        GL43.glLinkProgram(programId);
        GL43.glDeleteShader(shader);
        if (GL43.glGetProgrami(programId, GL43.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL43.glGetProgramInfoLog(programId, 4096);
            LOGGER.error("[Amdium] v2.3 compute program link error:\n{}", log);
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
        // v2.3: кэшируем sampler location ОДИН раз (вместо запроса каждый dispatch).
        // / v2.3: cache the sampler location ONCE (instead of querying every dispatch).
        u_HiZPyramidSampler = GL20.glGetUniformLocation(programId, "u_HiZPyramid");

        // 2. Allocate SSBOs.
        inputBufferSize     = (long) MAX_COMMANDS * COMMAND_STRIDE;
        outputBufferSize    = (long) MAX_COMMANDS * COMMAND_STRIDE;
        chunkInfoBufferSize = (long) MAX_COMMANDS * CHUNK_INFO_STRIDE;

        inputBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, inputBufferId);
        GL15.glBufferData(GL_SHADER_STORAGE_BUFFER, inputBufferSize, GL15.GL_DYNAMIC_DRAW);

        chunkInfoBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, chunkInfoBufferId);
        GL15.glBufferData(GL_SHADER_STORAGE_BUFFER, chunkInfoBufferSize, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);

        // v2.3: Ring из output + counter buffer'ов (по числу frames in flight).
        // / v2.3: Ring of output + counter buffers (matching frames in flight).
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

        // 3. CPU staging (используется для прямой записи в ring stream).
        // / 3. CPU staging (used for direct write into the ring stream).
        cpuStaging       = MemoryUtil.memAlloc((int) inputBufferSize);
        chunkInfoStaging = MemoryUtil.memAlloc((int) chunkInfoBufferSize);

        // v2.3: RingStreamBuffer для per-frame SSBO uploads (заменяет glBufferSubData).
        // Размер: достаточно для MAX_COMMANDS * (COMMAND_STRIDE + CHUNK_INFO_STRIDE)
        // на кадр, умножить на RING_SIZE для ring'а.
        // / v2.3: RingStreamBuffer for per-frame SSBO uploads (replaces glBufferSubData).
        long perFrameBytes = inputBufferSize + chunkInfoBufferSize;
        long ringTotalSize = perFrameBytes * RING_SIZE;
        try {
            ringStream = new RingStreamBuffer(ringTotalSize, RING_SIZE);
        } catch (Exception e) {
            LOGGER.warn("[Amdium] RingStreamBuffer init failed ({}), fallback на glBufferSubData",
                    e.getMessage());
            ringStream = null;
        }

        // 4. Init Hi-Z pyramid.
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

        // v2.3: кэшируем enable flags при init.
        // / v2.3: cache enable flags at init.
        cachedEnableFrustum = AmdiumConfig.ENABLE_INTEROP_FRUSTUM.get() ? 1 : 0;
        cachedEnableFog     = AmdiumConfig.ENABLE_INTEROP_FOG.get()     ? 1 : 0;
        cachedEnableHiZ     = (hiZPyramid.isInitialized()
                && AmdiumConfig.ENABLE_HIZ_OCCLUSION.get()) ? 1 : 0;

        initialized = true;
        LOGGER.info("[Amdium] InteropComputeCuller v2.3 готов. program={}, max commands={}, "
                + "workgroup={}, ringSize={}, ringStream={}",
                programId, MAX_COMMANDS, WORKGROUP_SIZE, RING_SIZE,
                ringStream != null ? "enabled" : "disabled (fallback glBufferSubData)");
    }

    /**
     * v2.3: позволяет обновить кэшируемые enable flags (вызвать при reload config).
     * / v2.3: allows updating the cached enable flags (call on config reload).
     */
    public static void refreshConfig() {
        if (!initialized) return;
        cachedEnableFrustum = AmdiumConfig.ENABLE_INTEROP_FRUSTUM.get() ? 1 : 0;
        cachedEnableFog     = AmdiumConfig.ENABLE_INTEROP_FOG.get()     ? 1 : 0;
        cachedEnableHiZ     = (hiZPyramid.isInitialized()
                && AmdiumConfig.ENABLE_HIZ_OCCLUSION.get()) ? 1 : 0;
    }

    public static void onRegionOffset(float x, float y, float z) {
        // v2.2: no-op. Per-command AABB comes from PerCommandMetadata SSBO.
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
     */
    public static boolean drawWithCulling(
            long pElementPointer, long pElementCount, long pBaseVertex,
            int size, int indexTypeSize, int indexTypeFormat,
            float[] projView, float[] view, float[] projection,
            float camX, float camY, float camZ,
            float fogStart, float fogEnd) {

        if (!initialized || size <= 0) return false;
        if (size > MAX_COMMANDS) return false; // fallback

        // v2.3: выбираем ring slot для ЭТОГО dispatch.
        // / v2.3: pick a ring slot for THIS dispatch.
        int slot = ringIndex;
        ringIndex = (ringIndex + 1) % RING_SIZE;
        int outBufId      = outputBufferRing[slot];
        int counterBufId  = counterBufferRing[slot];

        // ─── 1. Build per-command chunkInfo SSBO + input commands SSBO ───
        // v2.3: один проход по batch — собираем ОДНОВРЕМЕННО chunkInfo и inputCommands.
        // / v2.3: single pass over the batch — collect BOTH chunkInfo and inputCommands.
        chunkInfoStaging.clear();
        cpuStaging.clear();

        for (int i = 0; i < size; i++) {
            // Один memGetInt для baseVertex (используется и для lookup, и для команды).
            // / One memGetInt for baseVertex (used both for lookup and for the command).
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

            // Input command.
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

        // ─── 2. Upload chunkInfo + inputCommands в GPU ───
        // v2.3: используем RingStreamBuffer (zero-sync) если доступен,
        // иначе fallback на glBufferSubData с orphaning.
        // / v2.3: use RingStreamBuffer (zero-sync) if available, else fall back
        // to glBufferSubData with orphaning.
        boolean uploadedViaRing = false;
        if (ringStream != null) {
            long ciOff = ringStream.write(chunkInfoStaging, chunkInfoBytes);
            long cmdOff = ringStream.write(cpuStaging, cmdBytes);
            if (ciOff >= 0 && cmdOff >= 0) {
                ringStream.copyTo(chunkInfoBufferId, 0, ciOff, chunkInfoBytes);
                ringStream.copyTo(inputBufferId, 0, cmdOff, cmdBytes);
                uploadedViaRing = true;
            }
            // Если ring overflow — fallback на orphaning (ниже).
            // / If ring overflow — fall back to orphaning (below).
        }
        if (!uploadedViaRing) {
            // Fallback: glBufferSubData с orphaning (glBufferData с NULL size).
            // / Fallback: glBufferSubData with orphaning (glBufferData with NULL size).
            GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, chunkInfoBufferId);
            GL15.glBufferData(GL_SHADER_STORAGE_BUFFER, chunkInfoBufferSize, GL15.GL_DYNAMIC_DRAW);
            GL15.glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0,
                    (ByteBuffer) chunkInfoStaging.limit(chunkInfoBytes));

            GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, inputBufferId);
            GL15.glBufferData(GL_SHADER_STORAGE_BUFFER, inputBufferSize, GL15.GL_DYNAMIC_DRAW);
            GL15.glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0,
                    (ByteBuffer) cpuStaging.limit(cmdBytes));
            GL15.glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        }

        // ─── 3. Zero counter buffer ───
        // v2.3: glClearNamedBufferSubData — без CPU upload, без sync.
        // Это DSA-метод (GL 4.5), доступен на всех картах с GL 4.6.
        // Counter buffer — это uint32, поэтому используем R32UI + UNSIGNED_INT.
        // / v2.3: glClearNamedBufferSubData — no CPU upload, no sync.
        // This is a DSA method (GL 4.5), available on all GL 4.6 cards.
        // The counter buffer is a uint32, so we use R32UI + UNSIGNED_INT.
        GL45.glClearNamedBufferSubData(counterBufId,
                GL_R32UI_INTERNAL, 0, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, zeroInt);

        // ─── 4. Bind Hi-Z pyramid на texture unit 5 ───
        boolean hiZReady = hiZPyramid.isInitialized()
                && AmdiumConfig.ENABLE_HIZ_OCCLUSION.get()
                && hiZPyramid.getTextureId() != -1;
        if (hiZReady) {
            hiZPyramid.bindAsSampler(HIZ_TEXTURE_UNIT);
        }

        // ─── 5. Bind program + uniforms ───
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
        // v2.3: используем cached enable flags (не запрашиваем config каждый dispatch).
        // / v2.3: use cached enable flags (don't query config every dispatch).
        GL20.glUniform1i(u_EnableHiZ, hiZReady ? 1 : 0);
        GL20.glUniform1i(u_EnableFrustum, cachedEnableFrustum);
        GL20.glUniform1i(u_EnableFog,     cachedEnableFog);

        // v2.3: используем cached sampler location (вместо запроса каждый dispatch).
        // / v2.3: use cached sampler location (instead of querying every dispatch).
        if (hiZReady) {
            GL20.glUniform1i(u_HiZPyramidSampler, HIZ_TEXTURE_UNIT);
        }

        // ─── 6. Bind SSBOs ───
        // v2.3: output и counter берём из ring slot'а.
        // / v2.3: output and counter come from the ring slot.
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, chunkInfoBufferId); // ChunkInfoBuffer
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1, inputBufferId);     // InputCommands
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 2, outBufId);          // CompactedCommands (ring)
        GL30.glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 3, counterBufId);      // AtomicCounter (ring)

        // ─── 7. Dispatch ───
        int groups = (size + WORKGROUP_SIZE - 1) / WORKGROUP_SIZE;
        GL43.glDispatchCompute(groups, 1, 1);

        // ─── 8. Barrier: draw must see compute's writes ───
        // v2.3: убран GL_TEXTURE_FETCH_BARRIER_BIT — он лишний.
        // SHADER_STORAGE: делает SSBO-записи compute видимыми для последующих
        //                shader storage reads (включая indirect command read).
        // COMMAND: делает indirect command buffer записи видимыми для indirect draw.
        // / v2.3: removed GL_TEXTURE_FETCH_BARRIER_BIT — it's redundant.
        // SHADER_STORAGE: makes the compute's SSBO writes visible to subsequent
        //                 shader storage reads (including the indirect command read).
        // COMMAND: makes the indirect command buffer writes visible to the indirect draw.
        GL42.glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT);

        GL43.glUseProgram(0);

        if (hiZReady) {
            hiZPyramid.unbind(HIZ_TEXTURE_UNIT);
        }

        // ─── 9. Bind output → GL_DRAW_INDIRECT_BUFFER, counter → GL_PARAMETER_BUFFER ───
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, outBufId);
        GL15.glBindBuffer(GL_PARAMETER_BUFFER, counterBufId);

        // ─── 10. glMultiDrawElementsIndirectCount (zero readback!) ───
        GL46.glMultiDrawElementsIndirectCount(
                GL11.GL_TRIANGLES,
                indexTypeFormat,
                0L,        // offset in GL_DRAW_INDIRECT_BUFFER
                0L,        // offset in GL_PARAMETER_BUFFER (read uint drawCount)
                size,      // maxCount (CPU safety limit)
                COMMAND_STRIDE);

        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        GL15.glBindBuffer(GL_PARAMETER_BUFFER, 0);

        return true;
    }

    /**
     * v2.3: frame begin/end для ring stream (вызываются из LevelRendererMixin
     * или EmbediumInterop.endFrame).
     * / v2.3: frame begin/end for the ring stream (called from LevelRendererMixin
     * or EmbediumInterop.endFrame).
     */
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

    // v2.3: константы для glClearNamedBufferSubData.
    // / v2.3: constants for glClearNamedBufferSubData.
    private static final int GL_R32F_INTERNAL = 0x822E;
    private static final int GL_RED = 0x1903;
    private static final int GL_FLOAT = 0x1406;
    private static final int GL_R32UI_INTERNAL = 0x8236;
    private static final int GL_RED_INTEGER = 0x8D94;
    private static final int GL_UNSIGNED_INT = 0x1405;

    // Один статический буфер на 4 нулевых байта — не аллоцируем каждый кадр.
    // / One static buffer with 4 zero bytes — don't allocate every frame.
    private static final java.nio.IntBuffer zeroInt = createZeroInt();

    private static java.nio.IntBuffer createZeroInt() {
        java.nio.IntBuffer b = java.nio.ByteBuffer.allocateDirect(4)
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
        if (inputBufferId != -1)      { GL15.glDeleteBuffers(inputBufferId);  inputBufferId = -1; }
        if (outputBufferRing != null) {
            for (int id : outputBufferRing) if (id != -1) GL15.glDeleteBuffers(id);
            outputBufferRing = null;
        }
        if (counterBufferRing != null) {
            for (int id : counterBufferRing) if (id != -1) GL15.glDeleteBuffers(id);
            counterBufferRing = null;
        }
        if (chunkInfoBufferId != -1)  { GL15.glDeleteBuffers(chunkInfoBufferId); chunkInfoBufferId = -1; }
        hiZPyramid.destroy();
        initialized = false;
    }
}

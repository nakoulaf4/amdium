package dev.amdium.render;

import dev.amdium.Amdium;
import dev.amdium.config.AmdiumConfig;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Compute shader для GPU-side frustum culling (vanilla path).
 * / Compute shader for GPU-side frustum culling (vanilla path).
 *
 * ─────────────────────────────────────────────────────────────────────────
 * v2.3 ОПТИМИЗАЦИЯ ПРОИЗВОДИТЕЛЬНОСТИ / v2.3 PERFORMANCE OPTIMIZATION
 * ─────────────────────────────────────────────────────────────────────────
 *
 * v2.2 ПРОБЛЕМЫ / v2.2 PROBLEMS:
 *   1. glBufferSubData для atomic counter reset (4 байта) КАЖДЫЙ dispatch
 *      — implicit sync, если GPU ещё читает счётчик прошлого кадра.
 *      / glBufferSubData for atomic counter reset (4 bytes) EVERY dispatch
 *      — implicit sync if the GPU is still reading the previous frame's counter.
 *
 *   2. glMemoryBarrier(COMMAND | SHADER_STORAGE | BUFFER_UPDATE) —
 *      BUFFER_UPDATE_BARRIER_BIT ЛИШНИЙ (нет glBufferSubData между dispatch
 *      и barrier, только до dispatch).
 *      / glMemoryBarrier(COMMAND | SHADER_STORAGE | BUFFER_UPDATE) —
 *      BUFFER_UPDATE_BARRIER_BIT is unnecessary (no glBufferSubData between
 *      the dispatch and the barrier, only before the dispatch).
 *
 *   3. Fallback-path (без indirectParameters) использует glGetBufferSubData
 *      для readback counter — это GPU stall. На AMD RDNA и NVIDIA RTX 30
 *      indirectParameters поддерживается, так что этот путь обычно не
 *      активен, но если активен — это очень медленно.
 *      / The fallback path (without indirectParameters) uses glGetBufferSubData
 *      for counter readback — this is a GPU stall. On AMD RDNA and NVIDIA RTX 30
 *      indirectParameters is supported, so this path is usually inactive, but
 *      if active — it's very slow.
 *
 * v2.3 ИСПРАВЛЕНИЯ / v2.3 FIXES:
 *   1. glClearNamedBufferSubData для counter reset (без CPU upload, без sync).
 *      / glClearNamedBufferSubData for counter reset (no CPU upload, no sync).
 *
 *   2. Убран BUFFER_UPDATE_BARRIER_BIT.
 *      / Removed BUFFER_UPDATE_BARRIER_BIT.
 *
 *   3. Fallback readback помечен как deprecated warning — рекомендация
 *      включить ARB_indirect_parameters.
 *      / Fallback readback marked as deprecated warning — recommendation
 *      to enable ARB_indirect_parameters.
 *
 * Принцип (GPU-generated draw commands):
 * / Principle (GPU-generated draw commands):
 *   1. CPU загружает ВСЕ draw commands в indirectBuffer (без culling)
 *   2. CPU загружает AABB+origin в chunkInfoSSBO
 *   3. Compute shader проверяет видимость каждого чанка
 *   4. Видимые команды атомарно копируются в compactedOutputBuffer
 *   5. atomicCounter становится drawCount для glMultiDrawElementsIndirectCount
 *   6. CPU НЕ читает результат — parameterBuffer уже содержит правильный count
 *
 * На AMD RDNA wavefront = 64 → local_size_x = 64 идеально.
 * / On AMD RDNA the wavefront = 64 → local_size_x = 64 is ideal.
 */
public class AmdiumComputeCuller {

    private static final int GL_DRAW_INDIRECT_BUFFER = 0x8F3F;
    private static final int GL_PARAMETER_BUFFER = 0x8EE0;

    // v2.3: константы для glClearNamedBufferSubData.
    // / v2.3: constants for glClearNamedBufferSubData.
    private static final int GL_R32UI_INTERNAL = 0x8236;
    private static final int GL_RED_INTEGER = 0x8D94;
    private static final int GL_UNSIGNED_INT = 0x1405;
    private static final java.nio.IntBuffer zeroInt = createZeroInt();

    private static java.nio.IntBuffer createZeroInt() {
        java.nio.IntBuffer b = java.nio.ByteBuffer.allocateDirect(4)
                .order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
        b.put(0, 0);
        return b;
    }

    private int computeProgramId = -1;
    private boolean ready = false;
    private boolean supportsIndirectParameters = false;

    // Uniform locations / Локации uniform-ов
    private int u_ProjViewMatrix = -1;
    private int u_ChunkCount     = -1;
    private int u_CameraPos      = -1;
    private int u_FogStart       = -1;
    private int u_FogEnd         = -1;

    // Compacted output buffer (draw commands после culling)
    // / Compacted output buffer (draw commands after culling)
    private int compactedCommandsId = -1;
    // Atomic counter buffer (uint — drawCount) / Atomic counter buffer (uint — drawCount)
    private int atomicCounterId = -1;

    private int maxChunks;
    private final IntBuffer countReadback = org.lwjgl.system.MemoryUtil.memAllocInt(1);

    // v2.3: флаг — выводили ли мы warning про fallback readback.
    // / v2.3: flag — whether we already warned about fallback readback.
    private boolean warnedAboutReadback = false;

    public void init(int maxChunks, boolean supportsIndirectParameters) {
        this.maxChunks = maxChunks;
        this.supportsIndirectParameters = supportsIndirectParameters;

        try {
            computeProgramId = createComputeProgram();
            cacheUniforms();

            long cmdBufSize = (long) maxChunks * MDIDrawCommandBuffer.COMMAND_STRIDE;

            // Compacted commands (видимые) — bind как GL_DRAW_INDIRECT_BUFFER на момент draw
            // / Compacted commands (visible) — bind as GL_DRAW_INDIRECT_BUFFER at draw time
            compactedCommandsId = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, compactedCommandsId);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, cmdBufSize, GL15.GL_DYNAMIC_COPY);

            // Atomic counter (uint32) / Атомарный счётчик (uint32)
            atomicCounterId = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, atomicCounterId);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 4, GL15.GL_DYNAMIC_COPY);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

            ready = true;
            Amdium.LOGGER.info("[Amdium] Compute culler готов (v2.3). IndirectParameters={}, workgroup={}",
                    supportsIndirectParameters, AmdiumConfig.CULLING_WORKGROUP_SIZE.get());

            if (!supportsIndirectParameters) {
                Amdium.LOGGER.warn("[Amdium] ARB_indirect_parameters НЕ поддерживается. "
                        + "Fallback path использует glGetBufferSubData — это вызывает GPU stall. "
                        + "Рекомендуется обновить драйвер (нужно GL 4.6 / ARB_indirect_parameters).");
            }
        } catch (Exception e) {
            Amdium.LOGGER.error("[Amdium] Ошибка загрузки compute shader: {}", e.getMessage(), e);
            ready = false;
        }
    }

    /**
     * Запускает GPU culling.
     * / Launches GPU culling.
     */
    public int dispatch(int inputChunkInfoSSBO, int indirectBufferId, int chunkCount,
                         float[] projView, float cameraX, float cameraY, float cameraZ,
                         float fogStart, float fogEnd) {
        if (!ready || chunkCount == 0) return 0;

        // v2.3: сбрасываем atomic counter через glClearNamedBufferSubData — без
        // CPU upload, без sync. DSA-метод (GL 4.5).
        // / v2.3: reset the atomic counter via glClearNamedBufferSubData — no
        // CPU upload, no sync. DSA method (GL 4.5).
        GL45.glClearNamedBufferSubData(atomicCounterId,
                GL_R32UI_INTERNAL, 0, 4, GL_RED_INTEGER, GL_UNSIGNED_INT, zeroInt);

        GL20.glUseProgram(computeProgramId);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            var buf = stack.mallocFloat(16);
            buf.put(projView).flip();
            GL20.glUniformMatrix4fv(u_ProjViewMatrix, false, buf);
        }
        GL20.glUniform1i(u_ChunkCount, chunkCount);
        GL20.glUniform3f(u_CameraPos, cameraX, cameraY, cameraZ);
        GL20.glUniform1f(u_FogStart, fogStart);
        GL20.glUniform1f(u_FogEnd, fogEnd);

        // SSBO bindings: / Привязки SSBO:
        //   0: input chunk info (AABB+origin) / 0: входные данные чанка (AABB+origin)
        //   1: input commands (для копирования) / 1: input commands (for copying)
        //   2: output compacted commands / 2: выходные компактные команды
        //   3: atomic counter (drawCount) / 3: atomic counter (drawCount)
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, inputChunkInfoSSBO);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 1, indirectBufferId);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 2, compactedCommandsId);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, 3, atomicCounterId);

        // Dispatch — один thread на чанк / Dispatch — one thread per chunk
        int wg = AmdiumConfig.CULLING_WORKGROUP_SIZE.get();
        int groups = (chunkCount + wg - 1) / wg;
        GL43.glDispatchCompute(groups, 1, 1);

        // v2.3: убран BUFFER_UPDATE_BARRIER_BIT — он лишний.
        // SHADER_STORAGE: SSBO writes от compute видимы для indirect command read.
        // COMMAND: indirect command buffer writes видимы для indirect draw.
        // / v2.3: removed BUFFER_UPDATE_BARRIER_BIT — it's redundant.
        // SHADER_STORAGE: SSBO writes from compute visible to the indirect command read.
        // COMMAND: indirect command buffer writes visible to the indirect draw.
        GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        GL20.glUseProgram(0);

        // Если поддерживается IndirectParameters — НЕ читаем count, GPU сам возьмёт
        // из atomicCounterId (который bind'им как GL_PARAMETER_BUFFER).
        // / If IndirectParameters is supported — do NOT read count, the GPU will take it
        // from atomicCounterId (which we bind as GL_PARAMETER_BUFFER).
        if (supportsIndirectParameters) {
            // Bind atomic counter как parameter buffer / Bind the atomic counter as the parameter buffer
            GL15.glBindBuffer(GL_PARAMETER_BUFFER, atomicCounterId);
            return 0;
        }

        // v2.3: warning один раз — fallback readback медленный.
        // / v2.3: warn once — fallback readback is slow.
        if (!warnedAboutReadback) {
            Amdium.LOGGER.warn("[Amdium] Fallback readback active — это вызывает GPU stall. "
                    + "Включите ARB_indirect_parameters (GL 4.6) для zero-readback.");
            warnedAboutReadback = true;
        }

        // Fallback: readback count (minimal stall — 4 bytes, но stall всё равно есть)
        // / Fallback: readback count (minimal stall — 4 bytes, but the stall is still there)
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, atomicCounterId);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, countReadback);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        return countReadback.get(0);
    }

    public int getCompactedCommandsId() { return compactedCommandsId; }
    public int getAtomicCounterId() { return atomicCounterId; }
    public boolean isReady() { return ready; }

    private int createComputeProgram() throws Exception {
        String source = loadShaderSource("/assets/amdium/shaders/core/chunk_culling.comp.glsl");
        int shader = GL20.glCreateShader(GL43.GL_COMPUTE_SHADER);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new RuntimeException("Compute shader compile error:\n" + log);
        }

        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, shader);
        GL20.glLinkProgram(program);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            GL20.glDeleteShader(shader);
            throw new RuntimeException("Compute program link error:\n" + log);
        }

        GL20.glDeleteShader(shader);
        return program;
    }

    private void cacheUniforms() {
        u_ProjViewMatrix = GL20.glGetUniformLocation(computeProgramId, "u_ProjViewMatrix");
        u_ChunkCount     = GL20.glGetUniformLocation(computeProgramId, "u_ChunkCount");
        u_CameraPos      = GL20.glGetUniformLocation(computeProgramId, "u_CameraPos");
        u_FogStart       = GL20.glGetUniformLocation(computeProgramId, "u_FogStart");
        u_FogEnd         = GL20.glGetUniformLocation(computeProgramId, "u_FogEnd");
    }

    private String loadShaderSource(String path) throws Exception {
        try (InputStream is = AmdiumComputeCuller.class.getResourceAsStream(path)) {
            if (is == null) throw new RuntimeException("Shader not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public void destroy() {
        org.lwjgl.system.MemoryUtil.memFree(countReadback);
        if (computeProgramId != -1) GL20.glDeleteProgram(computeProgramId);
        if (compactedCommandsId != -1) GL15.glDeleteBuffers(compactedCommandsId);
        if (atomicCounterId != -1) GL15.glDeleteBuffers(atomicCounterId);
        ready = false;
    }
}

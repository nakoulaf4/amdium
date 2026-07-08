package dev.amdium.render;

import dev.amdium.Amdium;
import dev.amdium.config.AmdiumConfig;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import java.io.InputStream;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Compute shader для GPU-side frustum culling.
 * Compute shader for GPU-side frustum culling.
 *
 * Принцип (новый — GPU-generated draw commands):
 * Principle (new — GPU-generated draw commands):
 *   1. CPU загружает ВСЕ draw commands в indirectBuffer (без culling)
 *      CPU uploads ALL draw commands into indirectBuffer (no culling)
 *   2. CPU загружает AABB+origin в chunkInfoSSBO
 *      CPU uploads AABB+origin into chunkInfoSSBO
 *   3. Compute shader проверяет видимость каждого чанка
 *      Compute shader checks visibility of each chunk
 *   4. Видимые команды атомарно копируются в compactedOutputBuffer
 *      (через atomicAdd в счётчике)
 *      Visible commands are atomically copied into compactedOutputBuffer
 *      (via atomicAdd on the counter)
 *   5. atomicCounter становится drawCount для glMultiDrawElementsIndirectCount
 *      atomicCounter becomes drawCount for glMultiDrawElementsIndirectCount
 *   6. CPU НЕ читает результат — parameterBuffer уже содержит правильный count
 *      CPU does NOT read the result — parameterBuffer already holds the correct count
 *
 * Если ARB_indirect_parameters недоступен:
 * If ARB_indirect_parameters is unavailable:
 *   - Читаем count через glMapBufferRange (но не glFinish — используем fence)
 *     Read count via glMapBufferRange (but not glFinish — we use a fence)
 *   - Используем обычный glMultiDrawElementsIndirect
 *     Use regular glMultiDrawElementsIndirect
 *
 * На AMD RDNA wavefront = 64 → local_size_x = 64 идеально.
 * On AMD RDNA the wavefront = 64 → local_size_x = 64 is ideal.
 */
public class AmdiumComputeCuller {

    private static final int GL_DRAW_INDIRECT_BUFFER = 0x8F3F;
    private static final int GL_PARAMETER_BUFFER = 0x8EE0;

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
    // Compacted output buffer (draw commands after culling)
    private int compactedCommandsId = -1;
    // Atomic counter buffer (uint — drawCount) / Atomic counter buffer (uint — drawCount)
    private int atomicCounterId = -1;

    private int maxChunks;
    private final IntBuffer countReadback = org.lwjgl.system.MemoryUtil.memAllocInt(1);

    public void init(int maxChunks, boolean supportsIndirectParameters) {
        this.maxChunks = maxChunks;
        this.supportsIndirectParameters = supportsIndirectParameters;

        try {
            computeProgramId = createComputeProgram();
            cacheUniforms();

            long cmdBufSize = (long) maxChunks * MDIDrawCommandBuffer.COMMAND_STRIDE;

            // Compacted commands (видимые) — bind как GL_DRAW_INDIRECT_BUFFER на момент draw
            // Compacted commands (visible) — bind as GL_DRAW_INDIRECT_BUFFER at draw time
            compactedCommandsId = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, compactedCommandsId);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, cmdBufSize, GL15.GL_DYNAMIC_COPY);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

            // Atomic counter (uint32) / Атомарный счётчик (uint32)
            atomicCounterId = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, atomicCounterId);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 4, GL15.GL_DYNAMIC_COPY);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

            ready = true;
            Amdium.LOGGER.info("[Amdium] Compute culler готов. IndirectParameters={}, workgroup={}",
                    supportsIndirectParameters, AmdiumConfig.CULLING_WORKGROUP_SIZE.get());
        } catch (Exception e) {
            Amdium.LOGGER.error("[Amdium] Ошибка загрузки compute shader: {}", e.getMessage(), e);
            ready = false;
        }
    }

    /**
     * Запускает GPU culling.
     * Launches GPU culling.
     *
     * @param inputChunkInfoSSBO  SSBO с AABB+origin всех чанков (из MDIDrawCommandBuffer)
     *                           SSBO with AABB+origin of all chunks (from MDIDrawCommandBuffer)
     * @param inputCommandsSSBO   SSBO с draw командами (или -1 если input = indirect buffer)
     *                           SSBO with draw commands (or -1 if input = indirect buffer)
     * @param indirectBufferId    GL_DRAW_INDIRECT_BUFFER с командами (input для GPU)
     *                           GL_DRAW_INDIRECT_BUFFER with commands (input for the GPU)
     * @param chunkCount          кол-во чанков
     *                           number of chunks
     * @param projView            матрица projection*view
     *                           projection*view matrix
     * @param cameraX/Y/Z         позиция камеры
     *                           camera position
     * @param fogStart/End        дистанция тумана
     *                           fog distance
     * @return количество видимых чанков (для логирования; для draw используем IndirectCount)
     *        number of visible chunks (for logging; for draw we use IndirectCount)
     */
    public int dispatch(int inputChunkInfoSSBO, int indirectBufferId, int chunkCount,
                         float[] projView, float cameraX, float cameraY, float cameraZ,
                         float fogStart, float fogEnd) {
        if (!ready || chunkCount == 0) return 0;

        // Сбрасываем atomic counter в 0 / Reset the atomic counter to 0
        countReadback.put(0, 0);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, atomicCounterId);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, countReadback);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

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

        // Барьер: compute должен закончить запись до того, как indirect draw прочитает
        // Barrier: compute must finish writing before the indirect draw reads
        GL42.glMemoryBarrier(
                GL42.GL_COMMAND_BARRIER_BIT
              | GL43.GL_SHADER_STORAGE_BARRIER_BIT
              | GL42.GL_BUFFER_UPDATE_BARRIER_BIT);

        GL20.glUseProgram(0);

        // Если поддерживается IndirectParameters — НЕ читаем count, GPU сам возьмёт
        // из atomicCounterId (который bind'им как GL_PARAMETER_BUFFER).
        // If IndirectParameters is supported — do NOT read count, the GPU will take it
        // from atomicCounterId (which we bind as GL_PARAMETER_BUFFER).
        if (supportsIndirectParameters) {
            // Bind atomic counter как parameter buffer / Bind the atomic counter as the parameter buffer
            GL15.glBindBuffer(GL_PARAMETER_BUFFER, atomicCounterId);
            // Ноль! GPU сам возьмёт count из parameter buffer.
            // Zero! The GPU will take count from the parameter buffer itself.
            return 0;
        }

        // Fallback: readback count (минимальный stall — 4 байта)
        // Fallback: readback count (minimal stall — 4 bytes)
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, atomicCounterId);
        GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, countReadback);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        return countReadback.get(0);
    }

    /**
     * Возвращает буфер с compacted (видимыми) командами.
     * Returns the buffer with compacted (visible) commands.
     * Bind как GL_DRAW_INDIRECT_BUFFER перед MDI draw.
     * Bind as GL_DRAW_INDIRECT_BUFFER before the MDI draw.
     */
    public int getCompactedCommandsId() { return compactedCommandsId; }

    /**
     * Возвращает atomic counter buffer.
     * Returns the atomic counter buffer.
     * Bind как GL_PARAMETER_BUFFER для glMultiDrawElementsIndirectCount.
     * Bind as GL_PARAMETER_BUFFER for glMultiDrawElementsIndirectCount.
     */
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

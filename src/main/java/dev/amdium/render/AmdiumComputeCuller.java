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
 *
 * Принцип (GPU-generated draw commands):
 *   1. CPU загружает все draw commands в indirectBuffer (без culling)
 *   2. CPU загружает AABB+origin в chunkInfoSSBO
 *   3. Compute shader проверяет видимость каждого чанка
 *   4. Видимые команды атомарно копируются в compactedOutputBuffer
 *   5. atomicCounter = drawCount для glMultiDrawElementsIndirectCount
 *   6. CPU НЕ читает результат — zero readback (при поддержке IndirectParameters)
 */
public class AmdiumComputeCuller {

    private static final int GL_DRAW_INDIRECT_BUFFER = 0x8F3F;
    private static final int GL_PARAMETER_BUFFER = 0x8EE0;

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

    private int u_ProjViewMatrix = -1;
    private int u_ChunkCount     = -1;
    private int u_CameraPos      = -1;
    private int u_FogStart       = -1;
    private int u_FogEnd         = -1;

    private int compactedCommandsId = -1;
    private int atomicCounterId = -1;

    private int maxChunks;
    private final IntBuffer countReadback = org.lwjgl.system.MemoryUtil.memAllocInt(1);

    private boolean warnedAboutReadback = false;

    public void init(int maxChunks, boolean supportsIndirectParameters) {
        this.maxChunks = maxChunks;
        this.supportsIndirectParameters = supportsIndirectParameters;

        try {
            computeProgramId = createComputeProgram();
            cacheUniforms();

            long cmdBufSize = (long) maxChunks * MDIDrawCommandBuffer.COMMAND_STRIDE;

            compactedCommandsId = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, compactedCommandsId);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, cmdBufSize, GL15.GL_DYNAMIC_COPY);

            atomicCounterId = GL15.glGenBuffers();
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, atomicCounterId);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, 4, GL15.GL_DYNAMIC_COPY);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

            ready = true;
            Amdium.LOGGER.info("[Amdium] Compute culler готов. IndirectParameters={}, workgroup={}",
                    supportsIndirectParameters, AmdiumConfig.CULLING_WORKGROUP_SIZE.get());

            if (!supportsIndirectParameters) {
                Amdium.LOGGER.warn("[Amdium] ARB_indirect_parameters НЕ поддерживается. "
                        + "Fallback path использует glGetBufferSubData — GPU stall. "
                        + "Рекомендуется GL 4.6+ драйвер.");
            }
        } catch (Exception e) {
            Amdium.LOGGER.error("[Amdium] Ошибка загрузки compute shader: {}", e.getMessage(), e);
            ready = false;
        }
    }

    /** Запускает GPU culling. Возвращает 0 при IndirectParameters (GPU сам читает count). */
    public int dispatch(int inputChunkInfoSSBO, int indirectBufferId, int chunkCount,
                         float[] projView, float cameraX, float cameraY, float cameraZ,
                         float fogStart, float fogEnd) {
        if (!ready || chunkCount == 0) return 0;

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

        // SSBO: 0=ChunkInfo, 1=InputCommands, 2=CompactedCommands, 3=AtomicCounter
        try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
            java.nio.IntBuffer bufs = stack.mallocInt(4);
            bufs.put(0, inputChunkInfoSSBO);
            bufs.put(1, indirectBufferId);
            bufs.put(2, compactedCommandsId);
            bufs.put(3, atomicCounterId);
            org.lwjgl.opengl.GL44.glBindBuffersBase(GL43.GL_SHADER_STORAGE_BUFFER, 0, bufs);
        }

        int wg = AmdiumConfig.CULLING_WORKGROUP_SIZE.get();
        int groups = (chunkCount + wg - 1) / wg;
        GL43.glDispatchCompute(groups, 1, 1);

        // SHADER_STORAGE + COMMAND — без лишнего BUFFER_UPDATE
        GL42.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT | GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        GL20.glUseProgram(0);

        if (supportsIndirectParameters) {
            GL15.glBindBuffer(GL_PARAMETER_BUFFER, atomicCounterId);
            return 0;
        }

        if (!warnedAboutReadback) {
            Amdium.LOGGER.warn("[Amdium] Fallback readback active — GPU stall. "
                    + "Включите ARB_indirect_parameters для zero-readback.");
            warnedAboutReadback = true;
        }

        // Fallback: readback 4 байта (сталл неизбежен)
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
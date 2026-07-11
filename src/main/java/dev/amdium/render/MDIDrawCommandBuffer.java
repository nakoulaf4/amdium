package dev.amdium.render;

import dev.amdium.Amdium;
import dev.amdium.benchmark.AmdiumTelemetry;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Буфер для Multi-Draw Indirect команд.
 * Buffer for Multi-Draw Indirect commands.
 *
 * Хранит DrawElementsIndirectCommand[]:
 * Stores DrawElementsIndirectCommand[]:
 *   uint  count         — кол-во индексов (= quadCount * 6)
 *   uint  instanceCount — 1
 *   uint  firstIndex    — 0 (общий IBO, baseVertex смещает)
 *   int   baseVertex    — slot * MAX_VERTS_PER_CHUNK
 *   uint  baseInstance  — chunk ID для lookup в шейдере (получаем origin)
 *
 * Размер одной команды = 20 байт (5 * uint).
 * Single command size = 20 bytes (5 * uint).
 *
 * Использует THREE стратегии в зависимости от возможностей GPU:
 * Uses THREE strategies depending on GPU capabilities:
 *
 *   1. GPU-generated (compute writes indirect buf) + IndirectCount
 *      → glMultiDrawElementsIndirectCount (ноль CPU, ноль readback)
 *      → glMultiDrawElementsIndirectCount (zero CPU, zero readback)
 *
 *   2. CPU-built + IndirectCount
 *      → glMultiDrawElementsIndirectCount (ноль readback)
 *      → glMultiDrawElementsIndirectCount (zero readback)
 *
 *   3. CPU-built + readback (fallback для старых GCN)
 *      → glMultiDrawElementsIndirect с drawcount из readback (есть stall)
 *      → glMultiDrawElementsIndirect with drawcount from readback (stall)
 *
 * Все стратегии шарят один GL_DRAW_INDIRECT_BUFFER и GL_PARAMETER_BUFFER.
 * All strategies share a single GL_DRAW_INDIRECT_BUFFER and GL_PARAMETER_BUFFER.
 */
public class MDIDrawCommandBuffer {

    // sizeof(DrawElementsIndirectCommand) / sizeof(DrawElementsIndirectCommand)
    public static final int COMMAND_STRIDE = 20;

    // GL targets / GL-таргеты
    private static final int GL_DRAW_INDIRECT_BUFFER = 0x8F3F;
    private static final int GL_PARAMETER_BUFFER = 0x80EE; // GL_PARAMETER_BUFFER_ARB

    private int indirectBufferId = -1;   // GL_DRAW_INDIRECT_BUFFER
    private int parameterBufferId = -1;  // для ARB_indirect_parameters (count) / for ARB_indirect_parameters (count)
    private int chunkInfoSSBOId   = -1;  // SSBO с AABB+origin для compute shader / SSBO with AABB+origin for the compute shader

    // Persistent CPU staging для команд (не аллоцировать каждый кадр!)
    // Persistent CPU staging for commands (do not allocate every frame!)
    private ByteBuffer cpuCommandBuffer;
    private ByteBuffer cpuAabbBuffer;
    private IntBuffer  cpuCountBuffer;

    private int maxCommands;
    private int pendingCommandCount = 0;

    // Поддержка ARB_indirect_parameters / ARB_indirect_parameters support
    private boolean supportsIndirectParameters = false;

    public void init(int maxChunks, boolean supportsIndirectParameters) {
        this.maxCommands = maxChunks;
        this.supportsIndirectParameters = supportsIndirectParameters;

        long commandBufSize = (long) maxChunks * COMMAND_STRIDE;
        // 2*vec4 = 32 байта на чанк / 2*vec4 = 32 bytes per chunk
        long aabbBufSize    = (long) maxChunks * 32;

        // --- Indirect draw buffer --- / --- Буфер indirect draw ---
        indirectBufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBufferId);
        GL15.glBufferData(GL_DRAW_INDIRECT_BUFFER, commandBufSize, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);

        // --- Parameter buffer (для glMultiDrawElementsIndirectCount) ---
        // --- Parameter buffer (for glMultiDrawElementsIndirectCount) ---
        if (supportsIndirectParameters) {
            parameterBufferId = GL15.glGenBuffers();
            GL15.glBindBuffer(GL_PARAMETER_BUFFER, parameterBufferId);
            GL15.glBufferData(GL_PARAMETER_BUFFER, 4, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL_PARAMETER_BUFFER, 0);
        }

        // --- SSBO с AABB+origin для compute culler ---
        // --- SSBO with AABB+origin for the compute culler ---
        chunkInfoSSBOId = GL15.glGenBuffers();
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, chunkInfoSSBOId);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, aabbBufSize, GL15.GL_DYNAMIC_DRAW);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        // Persistent CPU staging (malloc один раз)
        // Persistent CPU staging (malloc once)
        cpuCommandBuffer = MemoryUtil.memAlloc((int) commandBufSize);
        cpuAabbBuffer    = MemoryUtil.memAlloc((int) aabbBufSize);
        cpuCountBuffer   = MemoryUtil.memAllocInt(1);

        Amdium.LOGGER.info("[Amdium] MDI buffer: {} команд, indirectParameters={}",
                maxChunks, supportsIndirectParameters);
    }

    /** Сброс pending команд. / Reset pending commands. */
    public void beginFrame() {
        pendingCommandCount = 0;
        cpuCommandBuffer.clear();
        cpuAabbBuffer.clear();
    }

    /**
     * Добавляет одну draw-команду.
     * Adds a single draw command.
     *
     * @param count        кол-во индексов (= quadCount * 6)
     *                     number of indices (= quadCount * 6)
     * @param baseVertex   slot * MAX_VERTS_PER_CHUNK
     * @param baseInstance chunk ID (для lookup в u_ChunkOrigins SSBO)
     *                     chunk ID (for lookup in the u_ChunkOrigins SSBO)
     * @param aabbMin/Max  AABB чанка для GPU culling
     *                     chunk AABB for GPU culling
     * @param originX/Y/Z  origin чанка (для vertex shader offset)
     *                     chunk origin (for the vertex shader offset)
     */
    public void addCommand(int count, int baseVertex, int baseInstance,
                            float aabbMinX, float aabbMinY, float aabbMinZ,
                            float aabbMaxX, float aabbMaxY, float aabbMaxZ,
                            float originX, float originY, float originZ) {
        if (pendingCommandCount >= maxCommands) return;

        // DrawElementsIndirectCommand (20 байт) / DrawElementsIndirectCommand (20 bytes)
        cpuCommandBuffer.putInt(count);
        cpuCommandBuffer.putInt(1);            // instanceCount
        cpuCommandBuffer.putInt(0);            // firstIndex (общий IBO) / firstIndex (shared IBO)
        cpuCommandBuffer.putInt(baseVertex);
        cpuCommandBuffer.putInt(baseInstance);
        pendingCommandCount++;

        // AABB + origin (32 байта: vec4 min, vec4 max; origin упакован в w)
        // AABB + origin (32 bytes: vec4 min, vec4 max; origin packed into w)
        cpuAabbBuffer.putFloat(aabbMinX);
        cpuAabbBuffer.putFloat(aabbMinY);
        cpuAabbBuffer.putFloat(aabbMinZ);
        cpuAabbBuffer.putFloat(originX);  // origin.x в w (переиспользуем padding) / origin.x in w (reuse the padding)
        cpuAabbBuffer.putFloat(aabbMaxX);
        cpuAabbBuffer.putFloat(aabbMaxY);
        cpuAabbBuffer.putFloat(aabbMaxZ);
        cpuAabbBuffer.putFloat(originY);  // origin.y / origin.y
    }

    /**
     * Загружает собранные команды в GPU для CPU-side culling.
     * Uploads the assembled commands to the GPU for CPU-side culling.
     * Используется когда compute shader отключён.
     * Used when the compute shader is disabled.
     */
    public void flushCPU() {
        cpuCommandBuffer.flip();
        cpuAabbBuffer.flip();

        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBufferId);
        GL15.glBufferSubData(GL_DRAW_INDIRECT_BUFFER, 0, cpuCommandBuffer);
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        AmdiumTelemetry.recordUploadBytes((long) pendingCommandCount * COMMAND_STRIDE);
        AmdiumTelemetry.recordBufferSubDataBytes((long) pendingCommandCount * COMMAND_STRIDE);

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, chunkInfoSSBOId);
        GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, cpuAabbBuffer);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
        AmdiumTelemetry.recordUploadBytes((long) pendingCommandCount * 32L);
        AmdiumTelemetry.recordBufferSubDataBytes((long) pendingCommandCount * 32L);
    }

    /**
     * CPU frustum culling + загрузка отфильтрованных команд.
     * CPU frustum culling + upload of the filtered commands.
     * Возвращает количество видимых команд.
     * Returns the number of visible commands.
     */
    public int flushWithCPUCulling(float[] frustumPlanes) {
        // Создаём отфильтрованный буфер на месте (используем limit)
        // Create a filtered buffer in place (using limit)
        int visible = 0;
        ByteBuffer filtered = cpuCommandBuffer.duplicate();
        filtered.clear();

        // Перечитываем AABB-часть / Re-read the AABB portion
        cpuAabbBuffer.flip();
        ByteBuffer aabbView = cpuAabbBuffer.duplicate();

        for (int i = 0; i < pendingCommandCount; i++) {
            float minX = aabbView.getFloat();
            float minY = aabbView.getFloat();
            float minZ = aabbView.getFloat();
            aabbView.getFloat(); // skip origin.x
            float maxX = aabbView.getFloat();
            float maxY = aabbView.getFloat();
            float maxZ = aabbView.getFloat();
            aabbView.getFloat(); // skip origin.y

            if (isVisibleAABB(minX, minY, minZ, maxX, maxY, maxZ, frustumPlanes)) {
                // Копируем команду из исходного буфера
                // Copy the command from the source buffer
                // Исходный буфер: i*20 .. i*20+20
                // Source buffer: i*20 .. i*20+20
                // Но мы писали последовательно, поэтому нужно перечитать
                // But we wrote sequentially, so we need to re-read
                // Проще: перезапишем filtered последовательно
                // Simpler: overwrite filtered sequentially
                int srcPos = i * COMMAND_STRIDE;
                cpuCommandBuffer.position(srcPos);
                cpuCommandBuffer.limit(srcPos + COMMAND_STRIDE);
                filtered.put(cpuCommandBuffer);
                visible++;
            }
        }
        cpuCommandBuffer.clear();
        filtered.flip();

        // Загружаем отфильтрованные команды / Upload the filtered commands
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBufferId);
        GL15.glBufferSubData(GL_DRAW_INDIRECT_BUFFER, 0, filtered);
        GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        AmdiumTelemetry.recordUploadBytes((long) visible * COMMAND_STRIDE);
        AmdiumTelemetry.recordBufferSubDataBytes((long) visible * COMMAND_STRIDE);

        // Перезагружаем AABB (для indirectCount если включён)
        // Reload AABB (for indirectCount if enabled)
        cpuAabbBuffer.clear();

        pendingCommandCount = visible;
        return visible;
    }

    /**
     * Вызывает один MDI draw call с учётом IndirectCount.
     * Issues a single MDI draw call accounting for IndirectCount.
     *
     * @param useIndirectCount если true и поддерживается — glMultiDrawElementsIndirectCount
     *                        if true and supported — glMultiDrawElementsIndirectCount
     * @param maxDrawCount     максимум команд (для IndirectCount = pendingCommandCount)
     *                        maximum number of commands (for IndirectCount = pendingCommandCount)
     */
    public void dispatchDraw(int indexType, boolean useIndirectCount, int maxDrawCount) {
        if (pendingCommandCount == 0) return;

        if (useIndirectCount && supportsIndirectParameters) {
            // glMultiDrawElementsIndirectCount читает count из parameter buffer
            // glMultiDrawElementsIndirectCount reads count from the parameter buffer
            // Сигнатура: (mode, type, indirectBufferOffset, parameterBufferOffset, maxDrawCount, stride)
            // Signature: (mode, type, indirectBufferOffset, parameterBufferOffset, maxDrawCount, stride)
            // Ядро OpenGL 4.6 / ARB_indirect_parameters
            // OpenGL 4.6 core / ARB_indirect_parameters
            GL15.glBindBuffer(GL_PARAMETER_BUFFER, parameterBufferId);
            GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBufferId);
            GL46.glMultiDrawElementsIndirectCount(
                    GL11.GL_TRIANGLES, indexType, 0L,
                    0L, maxDrawCount, COMMAND_STRIDE);
            GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
            GL15.glBindBuffer(GL_PARAMETER_BUFFER, 0);
        } else {
            // Обычный MDI с известным CPU drawcount
            // Regular MDI with a known CPU drawcount
            GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, indirectBufferId);
            GL43.glMultiDrawElementsIndirect(
                    GL11.GL_TRIANGLES, indexType, 0L,
                    pendingCommandCount, COMMAND_STRIDE);
            GL15.glBindBuffer(GL_DRAW_INDIRECT_BUFFER, 0);
        }
    }

    /** Записывает drawcount в parameter buffer (для CPU-side path).
     *  Writes drawcount into the parameter buffer (for the CPU-side path). */
    public void uploadParameterCount(int count) {
        if (!supportsIndirectParameters) return;
        cpuCountBuffer.put(0, count);
        GL15.glBindBuffer(GL_PARAMETER_BUFFER, parameterBufferId);
        GL15.glBufferSubData(GL_PARAMETER_BUFFER, 0, cpuCountBuffer);
        GL15.glBindBuffer(GL_PARAMETER_BUFFER, 0);
        AmdiumTelemetry.recordUploadBytes(4);
        AmdiumTelemetry.recordBufferSubDataBytes(4);
    }

    private static boolean isVisibleAABB(float minX, float minY, float minZ,
                                          float maxX, float maxY, float maxZ,
                                          float[] planes) {
        for (int p = 0; p < 6; p++) {
            float nx = planes[p * 4];
            float ny = planes[p * 4 + 1];
            float nz = planes[p * 4 + 2];
            float nd = planes[p * 4 + 3];

            float px = (nx >= 0) ? maxX : minX;
            float py = (ny >= 0) ? maxY : minY;
            float pz = (nz >= 0) ? maxZ : minZ;

            if (nx * px + ny * py + nz * pz + nd < 0) return false;
        }
        return true;
    }

    public int getChunkInfoSSBOId()   { return chunkInfoSSBOId; }
    public int getIndirectBufferId()  { return indirectBufferId; }
    public int getParameterBufferId() { return parameterBufferId; }
    public int getPendingCount()      { return pendingCommandCount; }
    public boolean supportsIndirectParameters() { return supportsIndirectParameters; }

    public void destroy() {
        if (cpuCommandBuffer != null) { MemoryUtil.memFree(cpuCommandBuffer); cpuCommandBuffer = null; }
        if (cpuAabbBuffer != null)    { MemoryUtil.memFree(cpuAabbBuffer); cpuAabbBuffer = null; }
        if (cpuCountBuffer != null)   { MemoryUtil.memFree(cpuCountBuffer); cpuCountBuffer = null; }
        if (indirectBufferId != -1)   { GL15.glDeleteBuffers(indirectBufferId); indirectBufferId = -1; }
        if (parameterBufferId != -1)  { GL15.glDeleteBuffers(parameterBufferId); parameterBufferId = -1; }
        if (chunkInfoSSBOId != -1)    { GL15.glDeleteBuffers(chunkInfoSSBOId); chunkInfoSSBOId = -1; }
    }
}

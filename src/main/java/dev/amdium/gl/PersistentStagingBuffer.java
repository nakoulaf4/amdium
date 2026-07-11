package dev.amdium.gl;

import dev.amdium.Amdium;
import dev.amdium.benchmark.AmdiumGpuTimer;
import dev.amdium.benchmark.AmdiumTelemetry;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL44;

import java.nio.ByteBuffer;

/**
 * Persistent-mapped staging buffer с ring'ом и per-frame fences.
 * Persistent-mapped staging buffer with a ring and per-frame fences.
 *
 * Аналог nvidium's UploadingBufferStream. Используется для заливки vertex
 * данных чанков в GPU-буфер без синхронизации:
 * Analog of nvidium's UploadingBufferStream. Used to stream vertex
 * data of chunks into a GPU buffer without synchronization:
 *
 *   1. CPU пишет в mapped region (zero-copy с CPU стороны)
 *      CPU writes into the mapped region (zero-copy from the CPU side)
 *   2. glFlushMappedNamedBufferRange — уведомляем GPU
 *      glFlushMappedNamedBufferRange — notify the GPU
 *   3. glCopyNamedBufferSubData — GPU копирует staging → destination
 *      glCopyNamedBufferSubData — GPU copies staging → destination
 *   4. fence в конце кадра — для повторного использования этой части ring'а
 *      fence at end of frame — to reuse this part of the ring
 *
 * На AMD RDNA это работает на порядок быстрее glBufferSubData, потому что
 * нет driver-side синхронизации и orphaning.
 * On AMD RDNA this is an order of magnitude faster than glBufferSubData,
 * because there is no driver-side synchronization or orphaning.
 *
 * Размер ring'а = maxFrames * stagingMB. 3 кадра * 8 MB = 24 MB staging.
 * Ring size = maxFrames * stagingMB. 3 frames * 8 MB = 24 MB staging.
 */
public final class PersistentStagingBuffer {

    // GL_COPY_READ_BUFFER / GL_COPY_WRITE_BUFFER определены в GL31 (LWJGL 3.x)
    // GL_COPY_READ_BUFFER / GL_COPY_WRITE_BUFFER are defined in GL31 (LWJGL 3.x)
    private static final int COPY_READ_BUFFER  = GL31.GL_COPY_READ_BUFFER;
    private static final int COPY_WRITE_BUFFER = GL31.GL_COPY_WRITE_BUFFER;

    private final int bufferId;
    private final long size;
    private final ByteBuffer mapped;
    private final GlFence fences;

    private long writeOffset;
    private final long frameBudget;

    public PersistentStagingBuffer(long sizeBytes, int framesInFlight) {
        this.size = sizeBytes;
        this.frameBudget = sizeBytes / framesInFlight;
        this.fences = new GlFence(framesInFlight);
        this.writeOffset = 0;

        bufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(COPY_WRITE_BUFFER, bufferId);

        int flags = GL44.GL_MAP_WRITE_BIT
                | GL44.GL_MAP_PERSISTENT_BIT
                | GL44.GL_MAP_COHERENT_BIT;
        GL44.glBufferStorage(COPY_WRITE_BUFFER, sizeBytes, flags);

        int mapFlags = GL44.GL_MAP_WRITE_BIT
                | GL44.GL_MAP_PERSISTENT_BIT
                | GL44.GL_MAP_COHERENT_BIT;
        mapped = GL30.glMapBufferRange(COPY_WRITE_BUFFER, 0, sizeBytes, mapFlags);

        if (mapped == null) {
            throw new RuntimeException("PersistentStagingBuffer: glMapBufferRange вернул null");
        }

        GL15.glBindBuffer(COPY_WRITE_BUFFER, 0);
        Amdium.LOGGER.info("[Amdium] Staging buffer создан: {} KB, {} кадров в полёте",
                sizeBytes / 1024, framesInFlight);
    }

    /**
     * Копирует данные в destination buffer через staging.
     * Copies data into the destination buffer via staging.
     */
    public void upload(int destBufferId, long destOffset, ByteBuffer data, int length) {
        // Если данные больше frame budget — fallback на прямой glBufferSubData
        // If the data is larger than the frame budget — fall back to direct glBufferSubData
        if (length > frameBudget) {
            AmdiumGpuTimer.Scope uploadScope = AmdiumGpuTimer.begin(AmdiumGpuTimer.AMDIUM_VERTEX_UPLOAD_SUBDATA);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, destBufferId);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, destOffset, data.limit(data.position() + length));
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            AmdiumGpuTimer.end(uploadScope);
            AmdiumTelemetry.recordUploadBytes(length);
            AmdiumTelemetry.recordBufferSubDataBytes(length);
            return;
        }

        // Проверяем, есть ли место в текущем frame slice
        // Check whether there is room in the current frame slice
        if (writeOffset + length > getCurrentFrameEnd()) {
            fences.waitOldest();
            writeOffset = alignToFrame(writeOffset);
            if (writeOffset + length > size) {
                writeOffset = 0;
                fences.waitOldest();
            }
        }

        // Копируем данные в mapped region
        // Copy the data into the mapped region
        ByteBuffer src = data.duplicate();
        src.limit(src.position() + length);
        ByteBuffer dst = mapped.duplicate();
        dst.position((int) writeOffset);
        dst.limit((int) writeOffset + length);
        dst.put(src);

        // GPU-side copy staging → destination / GPU-копирование staging → destination
        // Сигнатура: GL45.glCopyNamedBufferSubData(int read, int write, long readOff, long writeOff, long size)
        // Signature: GL45.glCopyNamedBufferSubData(int read, int write, long readOff, long writeOff, long size)
        AmdiumGpuTimer.Scope copyScope = AmdiumGpuTimer.begin(AmdiumGpuTimer.AMDIUM_VERTEX_UPLOAD_COPY);
        org.lwjgl.opengl.GL45.glCopyNamedBufferSubData(bufferId, destBufferId, writeOffset, destOffset, (long) length);
        AmdiumGpuTimer.end(copyScope);
        AmdiumTelemetry.recordUploadBytes(length);
        AmdiumTelemetry.recordPersistentCopyBytes(length);

        writeOffset += length;
    }

    /**
     * Завершает кадр — вставляет fence.
     * Finishes the frame — inserts a fence.
     */
    public void endFrame() {
        fences.push();
        writeOffset = alignToFrame(writeOffset);
        if (writeOffset >= size) writeOffset = 0;
    }

    private long getCurrentFrameEnd() {
        long frameIdx = writeOffset / frameBudget;
        return Math.min((frameIdx + 1) * frameBudget, size);
    }

    private long alignToFrame(long offset) {
        return (offset / frameBudget) * frameBudget;
    }

    public void destroy() {
        fences.waitAll();
        GL15.glBindBuffer(COPY_WRITE_BUFFER, bufferId);
        GL15.glUnmapBuffer(COPY_WRITE_BUFFER);
        GL15.glBindBuffer(COPY_WRITE_BUFFER, 0);
        GL15.glDeleteBuffers(bufferId);
    }

    public long getSize() { return size; }
}

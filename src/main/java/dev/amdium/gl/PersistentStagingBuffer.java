package dev.amdium.gl;

import dev.amdium.Amdium;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;

/**
 * Persistent-mapped staging buffer с ring'ом и per-frame fences.
 * Аналог nvidium's UploadingBufferStream.
 *
 * CPU пишет в mapped region (zero-copy), затем GPU копирует staging → destination
 * через glCopyNamedBufferSubData. Coherent mapping → flush не нужен.
 */
public final class PersistentStagingBuffer {

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
        Amdium.LOGGER.info("[Amdium] Staging buffer: {} KB, {} кадров в полёте",
                sizeBytes / 1024, framesInFlight);
    }

    /**
     * Копирует данные в destination buffer через staging.
     * Если данные больше frame budget — fallback на прямой glBufferSubData.
     */
    public void upload(int destBufferId, long destOffset, ByteBuffer data, int length) {
        if (length > frameBudget) {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, destBufferId);
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, destOffset, data.limit(data.position() + length));
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            return;
        }

        if (writeOffset + length > getCurrentFrameEnd()) {
            fences.waitOldest();
            writeOffset = alignToFrame(writeOffset);
            if (writeOffset + length > size) {
                writeOffset = 0;
                fences.waitOldest();
            }
        }

        ByteBuffer src = data.duplicate();
        src.limit(src.position() + length);
        ByteBuffer dst = mapped.duplicate();
        dst.position((int) writeOffset);
        dst.limit((int) writeOffset + length);
        dst.put(src);

        // GPU-side copy staging → destination
        GL45.glCopyNamedBufferSubData(bufferId, destBufferId, writeOffset, destOffset, (long) length);

        writeOffset += length;
    }

    /** Завершает кадр — вставляет fence. */
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
        GL15.glDeleteBuffers(bufferId);
    }

    public long getSize() { return size; }
}
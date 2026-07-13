package dev.amdium.gl;

import dev.amdium.Amdium;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;

import java.nio.ByteBuffer;

/**
 * Persistent-mapped ring stream buffer для частых per-frame SSBO uploads.
 *
 * Per-slot fences: каждый слот имеет свой fence, beginFrame() ждёт
 * только fence текущего слота (если он уже использовался).
 *
 * Поддерживает SSBO offset alignment: write() округляет offset вверх
 * до alignment перед каждой записью, чтобы данные могли биндиться
 * через glBindBufferRange.
 */
public final class RingStreamBuffer {

    private static final int COPY_WRITE_BUFFER = GL31.GL_COPY_WRITE_BUFFER;

    private final int bufferId;
    private final long size;
    private final ByteBuffer mapped;
    private final int ringSize;
    private final long[] slotFences;
    private final int alignment; // SSBO offset alignment

    private final long frameBudget;
    private long frameWriteOffset;
    private int currentFrame;

    /**
     * @param sizeBytes   общий размер ring buffer'а
     * @param framesInFlight  число слотов в ring'е
     * @param ssboAlignment   GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT
     */
    public RingStreamBuffer(long sizeBytes, int framesInFlight, int ssboAlignment) {
        this.size = sizeBytes;
        this.ringSize = framesInFlight;
        this.alignment = ssboAlignment;
        this.frameBudget = sizeBytes / framesInFlight;
        this.slotFences = new long[ringSize];
        this.frameWriteOffset = 0;
        this.currentFrame = 0;

        bufferId = GL15.glGenBuffers();
        GL15.glBindBuffer(COPY_WRITE_BUFFER, bufferId);

        int storageFlags = GL44.GL_MAP_WRITE_BIT
                | GL44.GL_MAP_PERSISTENT_BIT
                | GL44.GL_MAP_COHERENT_BIT;
        GL44.glBufferStorage(COPY_WRITE_BUFFER, sizeBytes, storageFlags);

        int mapFlags = GL44.GL_MAP_WRITE_BIT
                | GL44.GL_MAP_PERSISTENT_BIT
                | GL44.GL_MAP_COHERENT_BIT;
        mapped = GL30.glMapBufferRange(COPY_WRITE_BUFFER, 0, sizeBytes, mapFlags);
        if (mapped == null) {
            throw new RuntimeException("RingStreamBuffer: glMapBufferRange returned null");
        }
        GL15.glBindBuffer(COPY_WRITE_BUFFER, 0);

        Amdium.LOGGER.info("[Amdium] RingStreamBuffer: {} KB, {} frames, frame budget {} KB, alignment {}",
                sizeBytes / 1024, ringSize, frameBudget / 1024, alignment);
    }

    /**
     * Начало кадра: ждать fence текущего слайса (если он уже использовался).
     */
    public void beginFrame() {
        long f = slotFences[currentFrame];
        if (f != 0) {
            GlFence.waitSingle(f);
            slotFences[currentFrame] = 0;
        }
        frameWriteOffset = (long) currentFrame * frameBudget;
    }

    /**
     * Записывает `length` байт из `data` в текущий frame slice с выравниванием
     * по alignment. Возвращает абсолютный offset внутри ring buffer'а.
     */
    public long write(ByteBuffer data, int length) {
        // Выравниваем текущий offset
        long aligned = alignUp(frameWriteOffset, alignment);
        long end = (long) currentFrame * frameBudget + frameBudget;

        if (aligned + length > end) {
            Amdium.LOGGER.warn("[Amdium] RingStreamBuffer frame slice overflow: aligned={} + {} > {}",
                    aligned - (long) currentFrame * frameBudget, length, frameBudget);
            return -1;
        }

        int dataPos = data.position();
        int dataLimit = data.limit();
        data.limit(dataPos + length);

        ByteBuffer slice = mapped.slice();
        slice.position((int) aligned);
        slice.limit((int) (aligned + length));
        slice.put(data);

        data.position(dataPos);
        data.limit(dataLimit);

        frameWriteOffset = aligned + length;
        return aligned;
    }

    /**
     * Конец кадра: вставляем fence для текущего слайса и переходим к следующему.
     */
    public void endFrame() {
        if (frameWriteOffset != (long) currentFrame * frameBudget) {
            slotFences[currentFrame] = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }
        currentFrame = (currentFrame + 1) % ringSize;
    }

    public int getId() { return bufferId; }
    public long getSize() { return size; }
    public long getFrameBudget() { return frameBudget; }
    public int getRingSize() { return ringSize; }

    public void destroy() {
        for (int i = 0; i < ringSize; i++) {
            if (slotFences[i] != 0) {
                GlFence.waitSingle(slotFences[i]);
                slotFences[i] = 0;
            }
        }
        GL15.glBindBuffer(COPY_WRITE_BUFFER, bufferId);
        GL15.glUnmapBuffer(COPY_WRITE_BUFFER);
        GL15.glBindBuffer(COPY_WRITE_BUFFER, 0);
        GL15.glDeleteBuffers(bufferId);
    }

    private static long alignUp(long value, int alignment) {
        return (value + alignment - 1) & ~((long) alignment - 1);
    }
}
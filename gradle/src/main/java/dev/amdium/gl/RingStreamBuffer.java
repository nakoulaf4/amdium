package dev.amdium.gl;

import dev.amdium.Amdium;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL44;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Persistent-mapped ring stream buffer для частых per-frame SSBO uploads.
 * / Persistent-mapped ring stream buffer for frequent per-frame SSBO uploads.
 *
 * ЗАЧЕМ / WHY:
 *   glBufferSubData на буфере, который GPU всё ещё читает с прошлого кадра,
 *   вызывает неявную синхронизацию (driver orphaning или stall). При 40-80
 *   загрузок на кадр это даёт 2-8 ms потерь — основная причина просадки FPS.
 *   / glBufferSubData on a buffer the GPU is still reading from the previous
 *   frame triggers implicit synchronization (driver orphaning or stall). With
 *   40-80 uploads per frame this costs 2-8 ms — the main cause of FPS drop.
 *
 * КАК / HOW:
 *   - Один большой persistent-mapped buffer (GL_MAP_WRITE_BIT | PERSISTENT | COHERENT).
 *   - Ring из `framesInFlight` слайсов. Каждый кадр пишет в свой слайс.
 *   - Per-frame fence гарантирует, что GPU дорисовал прошлый кадр перед
 *     перезаписью его слайса.
 *   - glCopyNamedBufferSubData копирует staging -> destination (GPU-side, zero sync).
 *   - COHERENT mapping → НЕ нужен glFlushMappedNamedBufferRange (в отличие от
 *     старого PersistentStagingBuffer, который делал лишний flush).
 *
 * ИСПОЛЬЗОВАНИЕ / USAGE:
 *   ring.beginFrame();                      // ждать fence прошлого кадра
 *   long off = ring.write(data, len);        // пишет в mapped region
 *   glCopyNamedBufferSubData(ring.getId(), destId, off, destOffset, len);
 *   ring.endFrame();                         // push fence
 */
public final class RingStreamBuffer {

    private static final int COPY_WRITE_BUFFER = GL31.GL_COPY_WRITE_BUFFER;

    private final int bufferId;
    private final long size;
    private final ByteBuffer mapped;
    private final GlFence fences;

    private final long frameBudget;
    private long frameWriteOffset;   // абсолютный оффсет записи в ring
    private int currentFrame;        // 0 .. framesInFlight-1

    public RingStreamBuffer(long sizeBytes, int framesInFlight) {
        this.size = sizeBytes;
        this.frameBudget = sizeBytes / framesInFlight;
        this.fences = new GlFence(framesInFlight);
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

        Amdium.LOGGER.info("[Amdium] RingStreamBuffer: {} KB, {} frames in flight, frame budget {} KB",
                sizeBytes / 1024, framesInFlight, frameBudget / 1024);
    }

    /**
     * Начало кадра: ждать fence текущего слайса (если GPU ещё не дорисовал
     * прошлый кадр в этом слайсе). Сбрасывает frame-local write offset.
     * / Frame start: wait for the current slice's fence (if the GPU hasn't
     * finished the previous frame in this slice). Resets the frame-local
     * write offset.
     */
    public void beginFrame() {
        // Ждём fence именно ТЕКУЩЕГО слайса (это fence, вставленный N кадров назад).
        // / Wait for the fence of the CURRENT slice (inserted N frames ago).
        fences.waitOldest();
        frameWriteOffset = (long) currentFrame * frameBudget;
    }

    /**
     * Записывает `length` байт из `data` в текущий frame slice и возвращает
     * абсолютный offset внутри ring buffer'а. Этот offset нужно передать в
     * glCopyNamedBufferSubData как readOffset.
     * / Writes `length` bytes from `data` into the current frame slice and
     * returns the absolute offset inside the ring buffer. Pass this offset
     * to glCopyNamedBufferSubData as readOffset.
     *
     * ВАЖНО: caller НЕ должен писать больше frameBudget за кадр!
     * / IMPORTANT: the caller MUST NOT write more than frameBudget per frame!
     */
    public long write(ByteBuffer data, int length) {
        if (length > frameBudget) {
            // Слишком много для одного слайса — вернём -1, пусть caller сделает fallback.
            // / Too much for one slice — return -1 and let the caller fall back.
            return -1;
        }
        long frameEnd = (long) currentFrame * frameBudget + frameBudget;
        if (frameWriteOffset + length > frameEnd) {
            // Переполнение frame slice — это баг в caller'е. Логируем и fallback.
            // / Frame slice overflow — this is a caller bug. Log and fall back.
            Amdium.LOGGER.warn("[Amdium] RingStreamBuffer frame slice overflow: wrote {}+{} > {}",
                    frameWriteOffset - (long) currentFrame * frameBudget, length, frameBudget);
            return -1;
        }

        // Прямой put в mapped buffer без duplicate() (без heap-аллокации).
        // / Direct put into the mapped buffer without duplicate() (no heap alloc).
        int dataPos = data.position();
        int dataLimit = data.limit();
        data.limit(dataPos + length);

        // Используем slice mapped с нужной позиции — без duplicate().
        // slice() создаёт новый ByteBuffer, но он дешёвый (heap object), и без
        // него в Java NIO нельзя сместить позицию в mapped buffer. Альтернатива —
        // memCopy, но это тоже overhead. Оставляем slice, это стандартный паттерн.
        // / slice() creates a new ByteBuffer, but it's cheap (a heap object), and
        // without it Java NIO cannot offset the position in the mapped buffer.
        // Alternative is memCopy, but that's also overhead. Keep slice — it's
        // the standard pattern.
        ByteBuffer slice = mapped.slice();
        slice.position((int) frameWriteOffset);
        slice.limit((int) (frameWriteOffset + length));
        slice.put(data);

        // Восстанавливаем позицию/лимит data.
        // / Restore data's position/limit.
        data.position(dataPos);
        data.limit(dataLimit);

        long result = frameWriteOffset;
        frameWriteOffset += length;
        return result;
    }

    /**
     * Копирует записанные данные в destination buffer (GPU-side, без CPU-GPU sync).
     * / Copies the written data into the destination buffer (GPU-side, no CPU-GPU sync).
     *
     * @param destBufferId целевой GL buffer
     * @param destOffset   оффсет внутри destination
     * @param srcOffset    оффсет внутри ring (возвращён из write())
     * @param length       длина в байтах
     */
    public void copyTo(int destBufferId, long destOffset, long srcOffset, long length) {
        GL45.glCopyNamedBufferSubData(bufferId, destBufferId, srcOffset, destOffset, length);
    }

    /**
     * Конец кадра: вставляем fence для текущего слайса и переходим к следующему.
     * / End of frame: insert a fence for the current slice and advance to the next.
     */
    public void endFrame() {
        fences.push();
        currentFrame = (currentFrame + 1) % (int) (size / frameBudget);
    }

    public int getId() { return bufferId; }
    public long getSize() { return size; }
    public long getFrameBudget() { return frameBudget; }

    public void destroy() {
        fences.waitAll();
        GL15.glBindBuffer(COPY_WRITE_BUFFER, bufferId);
        GL15.glUnmapBuffer(COPY_WRITE_BUFFER);
        GL15.glBindBuffer(COPY_WRITE_BUFFER, 0);
        GL15.glDeleteBuffers(bufferId);
    }
}

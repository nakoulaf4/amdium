package dev.amdium.gl;

import dev.amdium.Amdium;
import org.lwjgl.opengl.GL32;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Ring из GL fence'ов для frame pacing.
 * A ring of GL fences for frame pacing.
 *
 * Каждый кадр мы вставляем fence после записи в staging buffer.
 * Each frame we insert a fence after writing to the staging buffer.
 * Когда staging заполняется, мы ждём самый старый fence — это гарантирует,
 * что GPU закончил читать те данные и их можно перезаписать.
 * When staging fills up, we wait for the oldest fence — this guarantees
 * that the GPU has finished reading that data and it can be overwritten.
 *
 * Идея скопирована из nvidium (UploadingBufferStream.java) —
 * проверенный паттерн для persistent-mapped buffers на AMD.
 * The idea is copied from nvidium (UploadingBufferStream.java) —
 * a proven pattern for persistent-mapped buffers on AMD.
 *
 * Размер ring = количество кадров "в полёте". 3 — стандарт для triple buffering.
 * Ring size = number of frames "in flight". 3 is the standard for triple buffering.
 */
public final class GlFence {

    private final Queue<Long> fences = new ArrayDeque<>();
    private final int maxFrames;

    public GlFence(int maxFrames) {
        this.maxFrames = maxFrames;
    }

    /**
     * Вставляет fence в конец текущей GPU-очереди команд.
     * Inserts a fence at the end of the current GPU command queue.
     */
    public void push() {
        while (fences.size() >= maxFrames) {
            long oldest = fences.poll();
            waitFence(oldest);
        }
        long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        fences.add(fence);
    }

    /**
     * Ждёт, пока GPU не дорисует до самого старого fence.
     * Waits until the GPU has reached the oldest fence.
     * Используется перед перезаписью начала staging buffer'а.
     * Used before overwriting the start of the staging buffer.
     */
    public void waitOldest() {
        Long oldest = fences.peek();
        if (oldest == null) return;
        waitFence(oldest);
        fences.poll();
    }

    /**
     * Полное ожидание всех fence'ов (используется при destroy).
     * Full wait for all fences (used on destroy).
     */
    public void waitAll() {
        while (!fences.isEmpty()) {
            waitFence(fences.poll());
        }
    }

    private static void waitFence(long fence) {
        if (fence == 0) return;
        // Сначала неблокирующий poll / First a non-blocking poll
        int result = GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0);
        if (result == GL32.GL_ALREADY_SIGNALED || result == GL32.GL_CONDITION_SATISFIED) {
            GL32.glDeleteSync(fence);
            return;
        }
        // Блокирующее ожидание с таймаутом 1 секунда (больше — значит завис GPU)
        // Blocking wait with a 1-second timeout (longer means the GPU hung)
        long start = System.nanoTime();
        while (result == GL32.GL_TIMEOUT_EXPIRED) {
            result = GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
            if (System.nanoTime() - start > 5_000_000_000L) {
                Amdium.LOGGER.warn("[Amdium] Fence wait timeout (>5s) — GPU завис?");
                break;
            }
        }
        GL32.glDeleteSync(fence);
    }

    public int pending() { return fences.size(); }
}

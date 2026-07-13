package dev.amdium.gl;

import dev.amdium.Amdium;
import org.lwjgl.opengl.GL32;

import java.util.ArrayDeque;
import java.util.Queue;

/**
 * Ring из GL fence'ов для frame pacing.
 * Идея из nvidium (UploadingBufferStream.java).
 */
public final class GlFence {

    private final Queue<Long> fences = new ArrayDeque<>();
    private final int maxFrames;

    public GlFence(int maxFrames) {
        this.maxFrames = maxFrames;
    }

    /** Вставляет fence в конец GPU-очереди команд. */
    public void push() {
        while (fences.size() >= maxFrames) {
            long oldest = fences.poll();
            waitFence(oldest);
        }
        long fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        fences.add(fence);
    }

    /** Ждёт самый старый fence и убирает его. Используется перед перезаписью staging. */
    public void waitOldest() {
        Long oldest = fences.peek();
        if (oldest == null) return;
        waitFence(oldest);
        fences.poll();
    }

    /** Полное ожидание всех fence'ов (для destroy). */
    public void waitAll() {
        while (!fences.isEmpty()) {
            waitFence(fences.poll());
        }
    }

    /** Ждёт конкретный fence. После вызова handle удаляется. */
    public static void waitSingle(long fence) {
        waitFence(fence);
    }

    private static void waitFence(long fence) {
        if (fence == 0) return;
        int result = GL32.glClientWaitSync(fence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 0);
        if (result == GL32.GL_ALREADY_SIGNALED || result == GL32.GL_CONDITION_SATISFIED) {
            GL32.glDeleteSync(fence);
            return;
        }
        // Блокирующее ожидание с таймаутом 1с (больше — значит GPU завис)
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
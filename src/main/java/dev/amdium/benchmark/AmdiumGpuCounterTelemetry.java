package dev.amdium.benchmark;

import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

/**
 * Asynchronously captures per-batch GPU draw counters for benchmark telemetry.
 * A fence covers all counter copies for one frame; data is read only after that
 * fence reports completion, so telemetry never waits for the GPU.
 */
public final class AmdiumGpuCounterTelemetry {
    private static final int RING_SIZE = 8;
    private static final int MAX_BATCHES_PER_FRAME = 1024;
    private static final int COUNTERS_PER_BATCH = 4;
    private static final int COUNTER_STRIDE = COUNTERS_PER_BATCH * Integer.BYTES;
    private static final long BUFFER_BYTES = (long) MAX_BATCHES_PER_FRAME * COUNTER_STRIDE;

    private static final Slot[] SLOTS = new Slot[RING_SIZE];
    private static Slot active;
    private static boolean initialized;
    private static int frameIndex;

    private AmdiumGpuCounterTelemetry() {}

    public static Resolved beginFrame() {
        if (!AmdiumBenchmark.isEnabled()) return Resolved.EMPTY;
        ensureInitialized();
        frameIndex++;

        Resolved resolved = resolveAvailable();
        active = null;
        for (Slot slot : SLOTS) {
            if (!slot.inFlight) {
                slot.batchCount = 0;
                slot.inputCommands = 0;
                slot.frameIssued = frameIndex;
                active = slot;
                break;
            }
        }
        if (active == null) resolved.droppedFrames++;
        resolved.pendingFrames = pendingFrames();
        return resolved;
    }

    public static void captureCounter(int counterBufferId, int inputCommands) {
        if (active == null || active.batchCount >= MAX_BATCHES_PER_FRAME) return;
        long offset = (long) active.batchCount * COUNTER_STRIDE;
        GL45.glCopyNamedBufferSubData(counterBufferId, active.bufferId, 0L, offset, COUNTER_STRIDE);
        active.batchCount++;
        active.inputCommands += Math.max(inputCommands, 0);
    }

    public static void endFrame() {
        if (active == null) return;
        if (active.batchCount > 0) {
            active.fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            active.inFlight = true;
        }
        active = null;
    }

    public static void destroy() {
        if (!initialized) return;
        for (Slot slot : SLOTS) {
            if (slot.fence != 0L) GL32.glDeleteSync(slot.fence);
            if (slot.bufferId != 0) GL15.glDeleteBuffers(slot.bufferId);
        }
        active = null;
        initialized = false;
    }

    private static void ensureInitialized() {
        if (initialized) return;
        for (int i = 0; i < SLOTS.length; i++) {
            int bufferId = GL45.glCreateBuffers();
            GL45.glNamedBufferData(bufferId, BUFFER_BYTES, GL15.GL_STREAM_READ);
            SLOTS[i] = new Slot(bufferId);
        }
        initialized = true;
    }

    private static Resolved resolveAvailable() {
        Resolved resolved = new Resolved();
        for (Slot slot : SLOTS) {
            if (!slot.inFlight || !isReady(slot.fence)) continue;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer counts = stack.mallocInt(slot.batchCount * COUNTERS_PER_BATCH);
                GL45.glGetNamedBufferSubData(slot.bufferId, 0L, counts);
                long visible = 0L;
                long frustumRejected = 0L;
                long fogRejected = 0L;
                long hiZRejected = 0L;
                for (int i = 0; i < slot.batchCount; i++) {
                    int offset = i * COUNTERS_PER_BATCH;
                    visible += Integer.toUnsignedLong(counts.get(offset));
                    frustumRejected += Integer.toUnsignedLong(counts.get(offset + 1));
                    fogRejected += Integer.toUnsignedLong(counts.get(offset + 2));
                    hiZRejected += Integer.toUnsignedLong(counts.get(offset + 3));
                }
                resolved.resolvedFrames++;
                resolved.resolvedBatches += slot.batchCount;
                resolved.inputCommands += slot.inputCommands;
                resolved.visibleCommands += visible;
                resolved.frustumRejectedCommands += frustumRejected;
                resolved.fogRejectedCommands += fogRejected;
                resolved.hiZRejectedCommands += hiZRejected;
                resolved.maxLatencyFrames = Math.max(
                        resolved.maxLatencyFrames, Math.max(0, frameIndex - slot.frameIssued));
            }

            GL32.glDeleteSync(slot.fence);
            slot.fence = 0L;
            slot.inFlight = false;
        }
        resolved.rejectedCommands = Math.max(0L, resolved.inputCommands - resolved.visibleCommands);
        return resolved;
    }

    private static boolean isReady(long fence) {
        int status = GL32.glClientWaitSync(fence, 0, 0L);
        return status == GL32.GL_ALREADY_SIGNALED || status == GL32.GL_CONDITION_SATISFIED;
    }

    private static int pendingFrames() {
        int pending = 0;
        for (Slot slot : SLOTS) {
            if (slot.inFlight) pending++;
        }
        return pending;
    }

    private static final class Slot {
        private final int bufferId;
        private long fence;
        private int frameIssued;
        private int batchCount;
        private long inputCommands;
        private boolean inFlight;

        private Slot(int bufferId) {
            this.bufferId = bufferId;
        }
    }

    public static final class Resolved {
        private static final Resolved EMPTY = new Resolved();

        public int resolvedFrames;
        public int resolvedBatches;
        public long inputCommands;
        public long visibleCommands;
        public long rejectedCommands;
        public long frustumRejectedCommands;
        public long fogRejectedCommands;
        public long hiZRejectedCommands;
        public int pendingFrames;
        public int droppedFrames;
        public int maxLatencyFrames;
    }
}

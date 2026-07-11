package dev.amdium.benchmark;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;

import java.util.ArrayDeque;
import java.util.Iterator;

public final class AmdiumGpuTimer {
    public static final String FRAME = "frame";
    public static final String AMDIUM_COMPUTE_CULL = "amdium_compute_cull";
    public static final String AMDIUM_MDI_DRAW = "amdium_mdi_draw";
    public static final String AMDIUM_MDI_UPLOAD = "amdium_mdi_upload";
    public static final String AMDIUM_VERTEX_UPLOAD_COPY = "amdium_vertex_upload_copy";
    public static final String AMDIUM_VERTEX_UPLOAD_SUBDATA = "amdium_vertex_upload_subdata";
    public static final String AMDIUM_PARAMETER_UPLOAD = "amdium_parameter_upload";
    public static final String AMDIUM_COUNT_READBACK = "amdium_count_readback";
    public static final String INTEROP_COMPUTE_CULL = "interop_compute_cull";
    public static final String INTEROP_MDI_DRAW = "interop_mdi_draw";
    public static final String INTEROP_DIRECT_UPLOAD = "interop_direct_upload";
    public static final String INTEROP_BASEVERTEX_FALLBACK = "interop_basevertex_fallback";
    public static final String HIZ_COPY_DEPTH = "hiz_copy_depth";
    public static final String HIZ_BUILD_MIPS = "hiz_build_mips";

    private static final int MAX_PENDING_SAMPLES = 4096;
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("amdium.benchmark.gpuTimers", "true"));
    private static final boolean PASS_TIMERS_ENABLED = Boolean.parseBoolean(
            System.getProperty("amdium.benchmark.gpuPassTimers", "false"));

    private static final Object LOCK = new Object();
    private static final ArrayDeque<PendingSample> PENDING = new ArrayDeque<>();
    private static int frameIndex = 0;
    private static boolean warnedUnavailable = false;

    private AmdiumGpuTimer() {}

    public static ResolvedFrame beginFrame() {
        synchronized (LOCK) {
            frameIndex++;
            return resolveAvailable();
        }
    }

    public static Scope begin(String name) {
        if (!isUsable()) return Scope.NOOP;
        if (!FRAME.equals(name) && !PASS_TIMERS_ENABLED) return Scope.NOOP;
        int startQuery = GL15.glGenQueries();
        int endQuery = GL15.glGenQueries();
        GL33.glQueryCounter(startQuery, GL33.GL_TIMESTAMP);
        return new Scope(name, startQuery, endQuery, frameIndex);
    }

    public static void end(Scope scope) {
        if (scope == null || scope == Scope.NOOP) return;
        GL33.glQueryCounter(scope.endQuery, GL33.GL_TIMESTAMP);
        synchronized (LOCK) {
            PENDING.addLast(new PendingSample(scope.name, scope.startQuery, scope.endQuery, scope.frameIssued));
            while (PENDING.size() > MAX_PENDING_SAMPLES) {
                PendingSample dropped = PENDING.removeFirst();
                delete(dropped);
            }
        }
    }

    public static int pendingSamples() {
        synchronized (LOCK) {
            return PENDING.size();
        }
    }

    private static boolean isUsable() {
        if (!ENABLED || !AmdiumBenchmark.isEnabled()) return false;
        try {
            return GL.getCapabilities() != null && GL.getCapabilities().OpenGL33;
        } catch (IllegalStateException e) {
            if (!warnedUnavailable) {
                warnedUnavailable = true;
            }
            return false;
        }
    }

    private static ResolvedFrame resolveAvailable() {
        ResolvedFrame resolved = new ResolvedFrame();
        Iterator<PendingSample> iterator = PENDING.iterator();
        while (iterator.hasNext()) {
            PendingSample sample = iterator.next();
            int startReady = GL15.glGetQueryObjecti(sample.startQuery, GL15.GL_QUERY_RESULT_AVAILABLE);
            int endReady = GL15.glGetQueryObjecti(sample.endQuery, GL15.GL_QUERY_RESULT_AVAILABLE);
            if (startReady == 0 || endReady == 0) {
                resolved.unresolvedSamples++;
                continue;
            }

            long start = GL33.glGetQueryObjectui64(sample.startQuery, GL15.GL_QUERY_RESULT);
            long end = GL33.glGetQueryObjectui64(sample.endQuery, GL15.GL_QUERY_RESULT);
            long nanos = Math.max(0L, end - start);
            int latency = Math.max(0, frameIndex - sample.frameIssued);
            resolved.record(sample.name, nanos, latency);
            delete(sample);
            iterator.remove();
        }
        resolved.pendingSamples = PENDING.size();
        return resolved;
    }

    private static void delete(PendingSample sample) {
        GL15.glDeleteQueries(sample.startQuery);
        GL15.glDeleteQueries(sample.endQuery);
    }

    public static final class Scope {
        private static final Scope NOOP = new Scope("", -1, -1, -1);

        private final String name;
        private final int startQuery;
        private final int endQuery;
        private final int frameIssued;

        private Scope(String name, int startQuery, int endQuery, int frameIssued) {
            this.name = name;
            this.startQuery = startQuery;
            this.endQuery = endQuery;
            this.frameIssued = frameIssued;
        }
    }

    private static final class PendingSample {
        private final String name;
        private final int startQuery;
        private final int endQuery;
        private final int frameIssued;

        private PendingSample(String name, int startQuery, int endQuery, int frameIssued) {
            this.name = name;
            this.startQuery = startQuery;
            this.endQuery = endQuery;
            this.frameIssued = frameIssued;
        }
    }

    public static final class ResolvedFrame {
        public long frameNanos;
        public long amdiumComputeCullNanos;
        public long amdiumMdiDrawNanos;
        public long amdiumMdiUploadNanos;
        public long amdiumVertexUploadCopyNanos;
        public long amdiumVertexUploadSubDataNanos;
        public long amdiumParameterUploadNanos;
        public long amdiumCountReadbackNanos;
        public long interopComputeCullNanos;
        public long interopMdiDrawNanos;
        public long interopDirectUploadNanos;
        public long interopBaseVertexFallbackNanos;
        public long hizCopyDepthNanos;
        public long hizBuildMipsNanos;
        public int resolvedSamples;
        public int unresolvedSamples;
        public int pendingSamples;
        public int maxLatencyFrames;

        private void record(String name, long nanos, int latencyFrames) {
            resolvedSamples++;
            maxLatencyFrames = Math.max(maxLatencyFrames, latencyFrames);
            switch (name) {
                case FRAME -> frameNanos += nanos;
                case AMDIUM_COMPUTE_CULL -> amdiumComputeCullNanos += nanos;
                case AMDIUM_MDI_DRAW -> amdiumMdiDrawNanos += nanos;
                case AMDIUM_MDI_UPLOAD -> amdiumMdiUploadNanos += nanos;
                case AMDIUM_VERTEX_UPLOAD_COPY -> amdiumVertexUploadCopyNanos += nanos;
                case AMDIUM_VERTEX_UPLOAD_SUBDATA -> amdiumVertexUploadSubDataNanos += nanos;
                case AMDIUM_PARAMETER_UPLOAD -> amdiumParameterUploadNanos += nanos;
                case AMDIUM_COUNT_READBACK -> amdiumCountReadbackNanos += nanos;
                case INTEROP_COMPUTE_CULL -> interopComputeCullNanos += nanos;
                case INTEROP_MDI_DRAW -> interopMdiDrawNanos += nanos;
                case INTEROP_DIRECT_UPLOAD -> interopDirectUploadNanos += nanos;
                case INTEROP_BASEVERTEX_FALLBACK -> interopBaseVertexFallbackNanos += nanos;
                case HIZ_COPY_DEPTH -> hizCopyDepthNanos += nanos;
                case HIZ_BUILD_MIPS -> hizBuildMipsNanos += nanos;
                default -> {
                }
            }
        }
    }
}

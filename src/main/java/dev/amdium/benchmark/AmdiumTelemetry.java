package dev.amdium.benchmark;

import dev.amdium.Amdium;

import java.util.Locale;

public final class AmdiumTelemetry {
    private static final Object LOCK = new Object();

    private static Frame frame = new Frame();
    private static long frameIndex = 0;

    private AmdiumTelemetry() {}

    public static void beginRenderFrame() {
        synchronized (LOCK) {
            AmdiumGpuTimer.ResolvedFrame gpu = AmdiumGpuTimer.beginFrame();
            AmdiumGpuCounterTelemetry.Resolved counters = AmdiumGpuCounterTelemetry.beginFrame();
            frame = new Frame();
            frame.frameIndex = ++frameIndex;
            frame.amdiumActive = Amdium.active;
            frame.embeddiumInteropActive = Amdium.embediumInteropActive;
            frame.supportsCompute = Amdium.supportsCompute;
            frame.supportsIndirectParameters = Amdium.supportsIndirectParameters;
            frame.gpuFrameNanos = gpu.frameNanos;
            frame.gpuAmdiumComputeCullNanos = gpu.amdiumComputeCullNanos;
            frame.gpuAmdiumMdiDrawNanos = gpu.amdiumMdiDrawNanos;
            frame.gpuAmdiumMdiUploadNanos = gpu.amdiumMdiUploadNanos;
            frame.gpuAmdiumVertexUploadCopyNanos = gpu.amdiumVertexUploadCopyNanos;
            frame.gpuAmdiumVertexUploadSubDataNanos = gpu.amdiumVertexUploadSubDataNanos;
            frame.gpuAmdiumParameterUploadNanos = gpu.amdiumParameterUploadNanos;
            frame.gpuAmdiumCountReadbackNanos = gpu.amdiumCountReadbackNanos;
            frame.gpuInteropComputeCullNanos = gpu.interopComputeCullNanos;
            frame.gpuInteropMdiDrawNanos = gpu.interopMdiDrawNanos;
            frame.gpuInteropDirectUploadNanos = gpu.interopDirectUploadNanos;
            frame.gpuInteropBaseVertexFallbackNanos = gpu.interopBaseVertexFallbackNanos;
            frame.gpuHiZCopyDepthNanos = gpu.hizCopyDepthNanos;
            frame.gpuHiZBuildMipsNanos = gpu.hizBuildMipsNanos;
            frame.gpuResolvedSamples = gpu.resolvedSamples;
            frame.gpuUnresolvedSamples = gpu.unresolvedSamples;
            frame.gpuPendingSamples = gpu.pendingSamples;
            frame.gpuMaxQueryLatencyFrames = gpu.maxLatencyFrames;
            frame.interopCullResolvedFrames = counters.resolvedFrames;
            frame.interopCullResolvedBatches = counters.resolvedBatches;
            frame.interopCullResolvedInputCommands = counters.inputCommands;
            frame.interopCullVisibleCommands = counters.visibleCommands;
            frame.interopCullRejectedCommands = counters.rejectedCommands;
            frame.interopCullFrustumRejectedCommands = counters.frustumRejectedCommands;
            frame.interopCullFogRejectedCommands = counters.fogRejectedCommands;
            frame.interopCullHiZRejectedCommands = counters.hiZRejectedCommands;
            frame.interopCullPendingFrames = counters.pendingFrames;
            frame.interopCullDroppedFrames = counters.droppedFrames;
            frame.interopCullMaxLatencyFrames = counters.maxLatencyFrames;
        }
    }

    public static Snapshot endRenderFrame(long renderNanos) {
        synchronized (LOCK) {
            AmdiumGpuCounterTelemetry.endFrame();
            frame.renderNanos = renderNanos;
            return new Snapshot(frame);
        }
    }

    public static void recordVanillaLayer(int inputCommands, int cpuKnownDrawnCommands,
                                           boolean computeCulling, boolean indirectCount) {
        synchronized (LOCK) {
            frame.vanillaLayers++;
            frame.vanillaInputCommands += Math.max(inputCommands, 0);
            frame.vanillaCpuKnownDrawnCommands += Math.max(cpuKnownDrawnCommands, 0);
            if (computeCulling) frame.vanillaComputeLayers++;
            if (indirectCount) frame.vanillaIndirectCountLayers++;
        }
    }

    public static void recordInteropBatch(int commandCount) {
        synchronized (LOCK) {
            frame.interopBatches++;
            frame.interopInputCommands += Math.max(commandCount, 0);
        }
    }

    public static void recordInteropComputeBatch(int commandCount) {
        synchronized (LOCK) {
            frame.interopComputeBatches++;
            frame.interopComputeCommands += Math.max(commandCount, 0);
        }
    }

    public static void recordInteropDirectBatch(int commandCount) {
        synchronized (LOCK) {
            frame.interopDirectBatches++;
            frame.interopDirectCommands += Math.max(commandCount, 0);
        }
    }

    public static void recordInteropFallback(int commandCount) {
        synchronized (LOCK) {
            frame.interopFallbackBatches++;
            frame.interopFallbackCommands += Math.max(commandCount, 0);
        }
    }

    public static void recordInteropMetadata(int totalCommands, int missingCommands) {
        synchronized (LOCK) {
            frame.interopMetadataTotal += Math.max(totalCommands, 0);
            frame.interopMetadataMissing += Math.max(missingCommands, 0);
        }
    }

    public static void recordUploadBytes(long bytes) {
        synchronized (LOCK) {
            frame.uploadBytes += Math.max(bytes, 0L);
            frame.uploadOperations++;
        }
    }

    public static void recordBufferSubDataBytes(long bytes) {
        synchronized (LOCK) {
            frame.bufferSubDataBytes += Math.max(bytes, 0L);
            frame.bufferSubDataOperations++;
        }
    }

    public static void recordPersistentCopyBytes(long bytes) {
        synchronized (LOCK) {
            frame.persistentCopyBytes += Math.max(bytes, 0L);
            frame.persistentCopyOperations++;
        }
    }

    public static void recordComputeDispatch(int groupsX, int groupsY, int groupsZ, int workgroupSize, int inputItems) {
        synchronized (LOCK) {
            frame.computeDispatches++;
            frame.computeGroups += (long) Math.max(groupsX, 0) * Math.max(groupsY, 0) * Math.max(groupsZ, 0);
            frame.computeThreads += (long) Math.max(groupsX, 0) * Math.max(groupsY, 0) * Math.max(groupsZ, 0)
                    * Math.max(workgroupSize, 0);
            frame.computeInputItems += Math.max(inputItems, 0);
        }
    }

    public static void recordCpuReadback() {
        synchronized (LOCK) {
            frame.cpuReadbacks++;
        }
    }

    public static void recordHiZUpdate(long nanos, boolean updated) {
        synchronized (LOCK) {
            frame.hizUpdateNanos += Math.max(nanos, 0L);
            frame.hizUpdateAttempts++;
            if (updated) frame.hizUpdates++;
        }
    }

    public static String csvHeader() {
        return "frame_index,elapsed_ms,phase,scene,profile,label,render_ms,"
                + "amdium_active,embeddium_interop_active,supports_compute,supports_indirect_parameters,"
                + "vanilla_layers,vanilla_input_commands,vanilla_cpu_known_drawn_commands,"
                + "vanilla_compute_layers,vanilla_indirect_count_layers,"
                + "interop_batches,interop_input_commands,interop_compute_batches,interop_compute_commands,"
                + "interop_direct_batches,interop_direct_commands,interop_fallback_batches,interop_fallback_commands,"
                + "interop_metadata_total,interop_metadata_missing,hiz_update_attempts,hiz_updates,hiz_update_ms,"
                + "upload_bytes,upload_operations,buffer_subdata_bytes,buffer_subdata_operations,"
                + "persistent_copy_bytes,persistent_copy_operations,compute_dispatches,compute_groups,"
                + "compute_threads,compute_input_items,cpu_readbacks,"
                + "gpu_frame_ms,gpu_amdium_compute_cull_ms,gpu_amdium_mdi_draw_ms,gpu_amdium_mdi_upload_ms,"
                + "gpu_amdium_vertex_upload_copy_ms,gpu_amdium_vertex_upload_subdata_ms,"
                + "gpu_amdium_parameter_upload_ms,gpu_amdium_count_readback_ms,"
                + "gpu_interop_compute_cull_ms,gpu_interop_mdi_draw_ms,gpu_interop_direct_upload_ms,"
                + "gpu_interop_basevertex_fallback_ms,gpu_hiz_copy_depth_ms,gpu_hiz_build_mips_ms,"
                + "gpu_resolved_samples,gpu_unresolved_samples,gpu_pending_samples,gpu_max_query_latency_frames,"
                + "interop_cull_resolved_frames,interop_cull_resolved_batches,"
                + "interop_cull_resolved_input_commands,interop_cull_visible_commands,"
                + "interop_cull_rejected_commands,interop_cull_rejection_pct,"
                + "interop_cull_pending_frames,interop_cull_dropped_frames,interop_cull_max_latency_frames,"
                + "interop_cull_frustum_rejected_commands,interop_cull_fog_rejected_commands,"
                + "interop_cull_hiz_rejected_commands";
    }

    public static final class Snapshot {
        private final long frameIndex;
        private final long renderNanos;
        private final boolean amdiumActive;
        private final boolean embeddiumInteropActive;
        private final boolean supportsCompute;
        private final boolean supportsIndirectParameters;
        private final int vanillaLayers;
        private final int vanillaInputCommands;
        private final int vanillaCpuKnownDrawnCommands;
        private final int vanillaComputeLayers;
        private final int vanillaIndirectCountLayers;
        private final int interopBatches;
        private final int interopInputCommands;
        private final int interopComputeBatches;
        private final int interopComputeCommands;
        private final int interopDirectBatches;
        private final int interopDirectCommands;
        private final int interopFallbackBatches;
        private final int interopFallbackCommands;
        private final int interopMetadataTotal;
        private final int interopMetadataMissing;
        private final int hizUpdateAttempts;
        private final int hizUpdates;
        private final long hizUpdateNanos;
        private final long uploadBytes;
        private final int uploadOperations;
        private final long bufferSubDataBytes;
        private final int bufferSubDataOperations;
        private final long persistentCopyBytes;
        private final int persistentCopyOperations;
        private final int computeDispatches;
        private final long computeGroups;
        private final long computeThreads;
        private final int computeInputItems;
        private final int cpuReadbacks;
        private final long gpuFrameNanos;
        private final long gpuAmdiumComputeCullNanos;
        private final long gpuAmdiumMdiDrawNanos;
        private final long gpuAmdiumMdiUploadNanos;
        private final long gpuAmdiumVertexUploadCopyNanos;
        private final long gpuAmdiumVertexUploadSubDataNanos;
        private final long gpuAmdiumParameterUploadNanos;
        private final long gpuAmdiumCountReadbackNanos;
        private final long gpuInteropComputeCullNanos;
        private final long gpuInteropMdiDrawNanos;
        private final long gpuInteropDirectUploadNanos;
        private final long gpuInteropBaseVertexFallbackNanos;
        private final long gpuHiZCopyDepthNanos;
        private final long gpuHiZBuildMipsNanos;
        private final int gpuResolvedSamples;
        private final int gpuUnresolvedSamples;
        private final int gpuPendingSamples;
        private final int gpuMaxQueryLatencyFrames;
        private final int interopCullResolvedFrames;
        private final int interopCullResolvedBatches;
        private final long interopCullResolvedInputCommands;
        private final long interopCullVisibleCommands;
        private final long interopCullRejectedCommands;
        private final int interopCullPendingFrames;
        private final int interopCullDroppedFrames;
        private final int interopCullMaxLatencyFrames;
        private final long interopCullFrustumRejectedCommands;
        private final long interopCullFogRejectedCommands;
        private final long interopCullHiZRejectedCommands;

        private Snapshot(Frame frame) {
            this.frameIndex = frame.frameIndex;
            this.renderNanos = frame.renderNanos;
            this.amdiumActive = frame.amdiumActive;
            this.embeddiumInteropActive = frame.embeddiumInteropActive;
            this.supportsCompute = frame.supportsCompute;
            this.supportsIndirectParameters = frame.supportsIndirectParameters;
            this.vanillaLayers = frame.vanillaLayers;
            this.vanillaInputCommands = frame.vanillaInputCommands;
            this.vanillaCpuKnownDrawnCommands = frame.vanillaCpuKnownDrawnCommands;
            this.vanillaComputeLayers = frame.vanillaComputeLayers;
            this.vanillaIndirectCountLayers = frame.vanillaIndirectCountLayers;
            this.interopBatches = frame.interopBatches;
            this.interopInputCommands = frame.interopInputCommands;
            this.interopComputeBatches = frame.interopComputeBatches;
            this.interopComputeCommands = frame.interopComputeCommands;
            this.interopDirectBatches = frame.interopDirectBatches;
            this.interopDirectCommands = frame.interopDirectCommands;
            this.interopFallbackBatches = frame.interopFallbackBatches;
            this.interopFallbackCommands = frame.interopFallbackCommands;
            this.interopMetadataTotal = frame.interopMetadataTotal;
            this.interopMetadataMissing = frame.interopMetadataMissing;
            this.hizUpdateAttempts = frame.hizUpdateAttempts;
            this.hizUpdates = frame.hizUpdates;
            this.hizUpdateNanos = frame.hizUpdateNanos;
            this.uploadBytes = frame.uploadBytes;
            this.uploadOperations = frame.uploadOperations;
            this.bufferSubDataBytes = frame.bufferSubDataBytes;
            this.bufferSubDataOperations = frame.bufferSubDataOperations;
            this.persistentCopyBytes = frame.persistentCopyBytes;
            this.persistentCopyOperations = frame.persistentCopyOperations;
            this.computeDispatches = frame.computeDispatches;
            this.computeGroups = frame.computeGroups;
            this.computeThreads = frame.computeThreads;
            this.computeInputItems = frame.computeInputItems;
            this.cpuReadbacks = frame.cpuReadbacks;
            this.gpuFrameNanos = frame.gpuFrameNanos;
            this.gpuAmdiumComputeCullNanos = frame.gpuAmdiumComputeCullNanos;
            this.gpuAmdiumMdiDrawNanos = frame.gpuAmdiumMdiDrawNanos;
            this.gpuAmdiumMdiUploadNanos = frame.gpuAmdiumMdiUploadNanos;
            this.gpuAmdiumVertexUploadCopyNanos = frame.gpuAmdiumVertexUploadCopyNanos;
            this.gpuAmdiumVertexUploadSubDataNanos = frame.gpuAmdiumVertexUploadSubDataNanos;
            this.gpuAmdiumParameterUploadNanos = frame.gpuAmdiumParameterUploadNanos;
            this.gpuAmdiumCountReadbackNanos = frame.gpuAmdiumCountReadbackNanos;
            this.gpuInteropComputeCullNanos = frame.gpuInteropComputeCullNanos;
            this.gpuInteropMdiDrawNanos = frame.gpuInteropMdiDrawNanos;
            this.gpuInteropDirectUploadNanos = frame.gpuInteropDirectUploadNanos;
            this.gpuInteropBaseVertexFallbackNanos = frame.gpuInteropBaseVertexFallbackNanos;
            this.gpuHiZCopyDepthNanos = frame.gpuHiZCopyDepthNanos;
            this.gpuHiZBuildMipsNanos = frame.gpuHiZBuildMipsNanos;
            this.gpuResolvedSamples = frame.gpuResolvedSamples;
            this.gpuUnresolvedSamples = frame.gpuUnresolvedSamples;
            this.gpuPendingSamples = frame.gpuPendingSamples;
            this.gpuMaxQueryLatencyFrames = frame.gpuMaxQueryLatencyFrames;
            this.interopCullResolvedFrames = frame.interopCullResolvedFrames;
            this.interopCullResolvedBatches = frame.interopCullResolvedBatches;
            this.interopCullResolvedInputCommands = frame.interopCullResolvedInputCommands;
            this.interopCullVisibleCommands = frame.interopCullVisibleCommands;
            this.interopCullRejectedCommands = frame.interopCullRejectedCommands;
            this.interopCullPendingFrames = frame.interopCullPendingFrames;
            this.interopCullDroppedFrames = frame.interopCullDroppedFrames;
            this.interopCullMaxLatencyFrames = frame.interopCullMaxLatencyFrames;
            this.interopCullFrustumRejectedCommands = frame.interopCullFrustumRejectedCommands;
            this.interopCullFogRejectedCommands = frame.interopCullFogRejectedCommands;
            this.interopCullHiZRejectedCommands = frame.interopCullHiZRejectedCommands;
        }

        public String toCsvRow(double elapsedMs, String phase, String scene, String profile, String label) {
            return String.format(Locale.ROOT,
                    "%d,%.3f,%s,%s,%s,%s,%.3f,%s,%s,%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.3f,"
                            + "%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,"
                            + "%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%d,%d,%d,%d,"
                            + "%d,%d,%d,%d,%d,%.3f,%d,%d,%d,%d,%d,%d",
                    frameIndex,
                    elapsedMs,
                    csv(phase),
                    csv(scene),
                    csv(profile),
                    csv(label),
                    renderNanos / 1_000_000.0,
                    amdiumActive,
                    embeddiumInteropActive,
                    supportsCompute,
                    supportsIndirectParameters,
                    vanillaLayers,
                    vanillaInputCommands,
                    vanillaCpuKnownDrawnCommands,
                    vanillaComputeLayers,
                    vanillaIndirectCountLayers,
                    interopBatches,
                    interopInputCommands,
                    interopComputeBatches,
                    interopComputeCommands,
                    interopDirectBatches,
                    interopDirectCommands,
                    interopFallbackBatches,
                    interopFallbackCommands,
                    interopMetadataTotal,
                    interopMetadataMissing,
                    hizUpdateAttempts,
                    hizUpdates,
                    hizUpdateNanos / 1_000_000.0,
                    uploadBytes,
                    uploadOperations,
                    bufferSubDataBytes,
                    bufferSubDataOperations,
                    persistentCopyBytes,
                    persistentCopyOperations,
                    computeDispatches,
                    computeGroups,
                    computeThreads,
                    computeInputItems,
                    cpuReadbacks,
                    gpuFrameNanos / 1_000_000.0,
                    gpuAmdiumComputeCullNanos / 1_000_000.0,
                    gpuAmdiumMdiDrawNanos / 1_000_000.0,
                    gpuAmdiumMdiUploadNanos / 1_000_000.0,
                    gpuAmdiumVertexUploadCopyNanos / 1_000_000.0,
                    gpuAmdiumVertexUploadSubDataNanos / 1_000_000.0,
                    gpuAmdiumParameterUploadNanos / 1_000_000.0,
                    gpuAmdiumCountReadbackNanos / 1_000_000.0,
                    gpuInteropComputeCullNanos / 1_000_000.0,
                    gpuInteropMdiDrawNanos / 1_000_000.0,
                    gpuInteropDirectUploadNanos / 1_000_000.0,
                    gpuInteropBaseVertexFallbackNanos / 1_000_000.0,
                    gpuHiZCopyDepthNanos / 1_000_000.0,
                    gpuHiZBuildMipsNanos / 1_000_000.0,
                    gpuResolvedSamples,
                    gpuUnresolvedSamples,
                    gpuPendingSamples,
                    gpuMaxQueryLatencyFrames,
                    interopCullResolvedFrames,
                    interopCullResolvedBatches,
                    interopCullResolvedInputCommands,
                    interopCullVisibleCommands,
                    interopCullRejectedCommands,
                    interopCullResolvedInputCommands == 0L ? 0.0
                            : interopCullRejectedCommands * 100.0 / interopCullResolvedInputCommands,
                    interopCullPendingFrames,
                    interopCullDroppedFrames,
                    interopCullMaxLatencyFrames,
                    interopCullFrustumRejectedCommands,
                    interopCullFogRejectedCommands,
                    interopCullHiZRejectedCommands);
        }

        private static String csv(String value) {
            if (value == null) return "";
            String escaped = value.replace("\"", "\"\"");
            return "\"" + escaped + "\"";
        }
    }

    private static final class Frame {
        private long frameIndex;
        private long renderNanos;
        private boolean amdiumActive;
        private boolean embeddiumInteropActive;
        private boolean supportsCompute;
        private boolean supportsIndirectParameters;
        private int vanillaLayers;
        private int vanillaInputCommands;
        private int vanillaCpuKnownDrawnCommands;
        private int vanillaComputeLayers;
        private int vanillaIndirectCountLayers;
        private int interopBatches;
        private int interopInputCommands;
        private int interopComputeBatches;
        private int interopComputeCommands;
        private int interopDirectBatches;
        private int interopDirectCommands;
        private int interopFallbackBatches;
        private int interopFallbackCommands;
        private int interopMetadataTotal;
        private int interopMetadataMissing;
        private int hizUpdateAttempts;
        private int hizUpdates;
        private long hizUpdateNanos;
        private long uploadBytes;
        private int uploadOperations;
        private long bufferSubDataBytes;
        private int bufferSubDataOperations;
        private long persistentCopyBytes;
        private int persistentCopyOperations;
        private int computeDispatches;
        private long computeGroups;
        private long computeThreads;
        private int computeInputItems;
        private int cpuReadbacks;
        private long gpuFrameNanos;
        private long gpuAmdiumComputeCullNanos;
        private long gpuAmdiumMdiDrawNanos;
        private long gpuAmdiumMdiUploadNanos;
        private long gpuAmdiumVertexUploadCopyNanos;
        private long gpuAmdiumVertexUploadSubDataNanos;
        private long gpuAmdiumParameterUploadNanos;
        private long gpuAmdiumCountReadbackNanos;
        private long gpuInteropComputeCullNanos;
        private long gpuInteropMdiDrawNanos;
        private long gpuInteropDirectUploadNanos;
        private long gpuInteropBaseVertexFallbackNanos;
        private long gpuHiZCopyDepthNanos;
        private long gpuHiZBuildMipsNanos;
        private int gpuResolvedSamples;
        private int gpuUnresolvedSamples;
        private int gpuPendingSamples;
        private int gpuMaxQueryLatencyFrames;
        private int interopCullResolvedFrames;
        private int interopCullResolvedBatches;
        private long interopCullResolvedInputCommands;
        private long interopCullVisibleCommands;
        private long interopCullRejectedCommands;
        private int interopCullPendingFrames;
        private int interopCullDroppedFrames;
        private int interopCullMaxLatencyFrames;
        private long interopCullFrustumRejectedCommands;
        private long interopCullFogRejectedCommands;
        private long interopCullHiZRejectedCommands;
    }
}

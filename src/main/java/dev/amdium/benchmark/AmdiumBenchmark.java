package dev.amdium.benchmark;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import dev.amdium.Amdium;
import net.minecraft.client.Minecraft;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class AmdiumBenchmark {
    private static final boolean ENABLED = Boolean.parseBoolean(
            System.getProperty("amdium.benchmark.enabled", "false"));
    private static final int WARMUP_SECONDS = intProperty("amdium.benchmark.warmupSeconds", 15);
    private static final int DURATION_SECONDS = intProperty("amdium.benchmark.durationSeconds", 60);
    private static final boolean QUIT_ON_COMPLETE = Boolean.parseBoolean(
            System.getProperty("amdium.benchmark.quitOnComplete", "true"));
    private static final String PROFILE = System.getProperty("amdium.benchmark.profile", "default");
    private static final String LABEL = System.getProperty("amdium.benchmark.label", "");
    private static final String PHASES_PATH = System.getProperty("amdium.benchmark.phases", "").trim();

    private static final Object LOCK = new Object();
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

    private static boolean started = false;
    private static boolean completed = false;
    private static long startedNanos = 0L;
    private static long phaseStartedNanos = 0L;
    private static long renderStartNanos = 0L;
    private static List<Phase> phases;
    private static int phaseIndex = 0;
    private static BufferedWriter writer;
    private static Path outputPath;

    private AmdiumBenchmark() {}

    public static void onRenderLevelStart() {
        if (!ENABLED || completed) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.player == null) return;

        synchronized (LOCK) {
            if (!started) {
                start();
            }
            renderStartNanos = System.nanoTime();
            AmdiumTelemetry.beginRenderFrame();
        }
    }

    public static void onRenderLevelEnd() {
        if (!ENABLED || completed) return;

        synchronized (LOCK) {
            if (!started || writer == null || renderStartNanos == 0L) return;

            long now = System.nanoTime();
            AmdiumTelemetry.Snapshot snapshot = AmdiumTelemetry.endRenderFrame(now - renderStartNanos);
            double elapsedSeconds = (now - startedNanos) / 1_000_000_000.0;
            double elapsedMs = elapsedSeconds * 1000.0;
            Phase current = phases.get(phaseIndex);
            double phaseElapsedSeconds = (now - phaseStartedNanos) / 1_000_000_000.0;

            if (phaseElapsedSeconds >= current.warmupSeconds
                    && phaseElapsedSeconds <= current.warmupSeconds + current.durationSeconds) {
                write(snapshot.toCsvRow(elapsedMs, "measure", current.name, PROFILE, LABEL));
            } else if (phaseElapsedSeconds < current.warmupSeconds) {
                write(snapshot.toCsvRow(elapsedMs, "warmup", current.name, PROFILE, LABEL));
            }

            if (phaseElapsedSeconds >= current.warmupSeconds + current.durationSeconds) {
                advancePhase(now);
            }
        }
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    private static void start() {
        started = true;
        startedNanos = System.nanoTime();
        phaseStartedNanos = startedNanos;
        phases = loadPhases();
        phaseIndex = 0;
        applyCameraDelta(phases.get(0));
        outputPath = resolveOutputPath();

        try {
            Path parent = outputPath.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            writer = Files.newBufferedWriter(outputPath, StandardCharsets.UTF_8);
            writer.write(AmdiumTelemetry.csvHeader());
            writer.newLine();
            Amdium.LOGGER.info("[Amdium Benchmark] Started. phases={} firstPhase={} output={}",
                    phases.size(), phases.get(0).name, outputPath.toAbsolutePath());
        } catch (IOException e) {
            completed = true;
            Amdium.LOGGER.error("[Amdium Benchmark] Failed to open output file {}: {}",
                    outputPath.toAbsolutePath(), e.getMessage(), e);
        }
    }

    private static List<Phase> loadPhases() {
        if (PHASES_PATH.isEmpty()) {
            return List.of(new Phase("default", WARMUP_SECONDS, DURATION_SECONDS, 0.0f, 0.0f));
        }

        Path path = Paths.get(PHASES_PATH).toAbsolutePath().normalize();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            PhaseConfig config = new Gson().fromJson(reader, PhaseConfig.class);
            if (config == null || config.phases == null || config.phases.length == 0) {
                throw new IllegalArgumentException("phase config must contain at least one phase");
            }
            return Arrays.stream(config.phases)
                    .map(Phase::validated)
                    .toList();
        } catch (IOException | JsonParseException | IllegalArgumentException e) {
            throw new IllegalStateException("Failed to load benchmark phases from " + path + ": "
                    + e.getMessage(), e);
        }
    }

    private static void advancePhase(long now) {
        phaseIndex++;
        if (phaseIndex >= phases.size()) {
            finish();
            return;
        }
        phaseStartedNanos = now;
        Phase next = phases.get(phaseIndex);
        applyCameraDelta(next);
        Amdium.LOGGER.info("[Amdium Benchmark] Starting phase {}/{}: {} (warmup={}s duration={}s)",
                phaseIndex + 1, phases.size(), next.name, next.warmupSeconds, next.durationSeconds);
    }

    private static void applyCameraDelta(Phase phase) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.player == null) return;
        float yaw = minecraft.player.getYRot() + phase.yawDeltaDegrees;
        float pitch = Math.max(-90.0f, Math.min(90.0f,
                minecraft.player.getXRot() + phase.pitchDeltaDegrees));
        minecraft.player.setYRot(yaw);
        minecraft.player.setYHeadRot(yaw);
        minecraft.player.setXRot(pitch);
        minecraft.player.yRotO = yaw;
        minecraft.player.yHeadRotO = yaw;
        minecraft.player.xRotO = pitch;
    }

    private static void write(String row) {
        try {
            writer.write(row);
            writer.newLine();
        } catch (IOException e) {
            Amdium.LOGGER.error("[Amdium Benchmark] Failed to write CSV row: {}", e.getMessage(), e);
            finish();
        }
    }

    private static void finish() {
        if (completed) return;
        completed = true;
        AmdiumGpuCounterTelemetry.destroy();
        try {
            if (writer != null) {
                writer.flush();
                writer.close();
            }
            Amdium.LOGGER.info("[Amdium Benchmark] Completed. output={}", outputPath.toAbsolutePath());
        } catch (IOException e) {
            Amdium.LOGGER.error("[Amdium Benchmark] Failed to close CSV output: {}", e.getMessage(), e);
        }

        if (QUIT_ON_COMPLETE) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null) {
                minecraft.stop();
            }
        }
    }

    private static Path resolveOutputPath() {
        String configured = System.getProperty("amdium.benchmark.output", "").trim();
        String safeProfile = sanitize(PROFILE.isBlank() ? "default" : PROFILE);
        String fileName = "amdium-benchmark-" + safeProfile + "-" + LocalDateTime.now().format(FILE_TIME) + ".csv";
        if (configured.isEmpty()) {
            return Paths.get("amdium-benchmarks", fileName);
        }

        Path configuredPath = Paths.get(configured);
        if (configured.endsWith("/") || Files.isDirectory(configuredPath)) {
            return configuredPath.resolve(fileName);
        }
        return configuredPath;
    }

    private static int intProperty(String name, int fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static final class PhaseConfig {
        private Phase[] phases;
    }

    private static final class Phase {
        private String name;
        private int warmupSeconds;
        private int durationSeconds;
        private float yawDeltaDegrees;
        private float pitchDeltaDegrees;

        private Phase(String name, int warmupSeconds, int durationSeconds,
                      float yawDeltaDegrees, float pitchDeltaDegrees) {
            this.name = name;
            this.warmupSeconds = warmupSeconds;
            this.durationSeconds = durationSeconds;
            this.yawDeltaDegrees = yawDeltaDegrees;
            this.pitchDeltaDegrees = pitchDeltaDegrees;
        }

        private static Phase validated(Phase phase) {
            if (phase == null || phase.name == null || phase.name.isBlank()) {
                throw new IllegalArgumentException("each phase requires a non-empty name");
            }
            if (phase.warmupSeconds < 0 || phase.durationSeconds <= 0) {
                throw new IllegalArgumentException("phase '" + phase.name
                        + "' requires warmupSeconds >= 0 and durationSeconds > 0");
            }
            if (!Float.isFinite(phase.yawDeltaDegrees) || !Float.isFinite(phase.pitchDeltaDegrees)) {
                throw new IllegalArgumentException("phase '" + phase.name
                        + "' camera deltas must be finite");
            }
            phase.name = sanitize(phase.name);
            return phase;
        }
    }
}

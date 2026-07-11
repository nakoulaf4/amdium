# Amdium Benchmarking

This repository keeps benchmark inputs outside version control. Pass the world,
Java runtime, output location, and optional Embeddium jar at launch time.

## Run a reproducible client benchmark

```bash
scripts/run-benchmark.sh \
  --world "/path/to/minecraft/saves/some-minecraft-benchmark-world" \
  --profile both \
  --embeddium-jar /path/to/embeddium.jar \
  --warmup 30 \
  --duration 180 \
  --output build/benchmarks
```

The script copies the world to `run/saves/amdium-benchmark-<profile>` and starts
Minecraft through `gradlew runClient` with `--quickPlaySingleplayer`. Relative
output paths are resolved under the repository root. The source world is not
modified.

Benchmarks run fullscreen by default, using the monitor's native mode. Use a
fixed window size when you want to isolate CPU, draw submission, culling, or
driver overhead from raw pixel fill cost:

```bash
scripts/run-benchmark.sh \
  --world "/path/to/saves/Minecraft Bench Mark" \
  --profile both \
  --window-size 1280x720 \
  --warmup 30 \
  --duration 180
```

Use fullscreen native resolution for final user-facing comparisons and for
finding bandwidth, depth pyramid, overdraw, fragment shading, and presentation
bottlenecks. Use fixed lower resolutions such as `1280x720` or `1600x900` for
early optimization discovery when the question is whether fewer draw calls,
less upload traffic, better culling, or fewer synchronization points improve
the renderer.

`--window-size` writes Minecraft's own `options.txt` window size. Some Wayland
compositors may still tile or resize windowed clients. Keep compositor-specific
window rules outside the repository, for example in your local desktop config
or in the command wrapper you use to launch the benchmark.

The script also writes `pauseOnLostFocus:false` before every run. This avoids a
benchmark accidentally starting in the pause menu when the game briefly loses
focus while joining the quick-play world.

## Multi-phase scenarios

Pass `--phases` to run several reproducible camera scenarios in one Minecraft
session. The JSON controls warmup and measurement duration per phase and can
apply relative yaw or pitch changes at phase boundaries. Its timings override
the command's single-phase `--warmup` and `--duration` values.

The repository includes `benchmarks/phases/mixed-and-terrain-occlusion.json`:

```json
{
  "phases": [
    {
      "name": "mixed-rendering",
      "warmupSeconds": 10,
      "durationSeconds": 30
    },
    {
      "name": "terrain-occlusion",
      "warmupSeconds": 5,
      "durationSeconds": 30,
      "yawDeltaDegrees": 90
    }
  ]
}
```

The second phase rotates the player and camera exactly 90 degrees to the right,
then waits five seconds before recording. GPU queries and asynchronous counter
results from the previous scene therefore resolve during warmup rather than
contaminating the next measurement window.

```bash
scripts/run-benchmark.sh \
  --world "/path/to/saves/Minecraft Bench Mark" \
  --profile embeddium \
  --phases benchmarks/phases/mixed-and-terrain-occlusion.json \
  --label mixed-and-terrain-occlusion
```

Each CSV row keeps `phase=warmup|measure`; the separate `scene` column contains
the configured phase name. Camera deltas are cumulative, making later phases
relative to the previous one. Phase names must be non-empty, warmups must be
non-negative, and measurement durations must be positive.

Use `--repeat N` for decisions that need run-to-run variance. Each repetition
copies the source world again and starts a fresh client. Output must be a
directory; labels receive `-r1`, `-r2`, and so on.

```bash
scripts/run-benchmark.sh \
  --world "/path/to/saves/Minecraft Bench Mark" \
  --profile embeddium \
  --phases benchmarks/phases/mixed-and-terrain-occlusion.json \
  --repeat 3 \
  --label hybrid-mdi-96
```

Use `--profile amdium` for Amdium without Embeddium and `--profile embeddium`
for Amdium with an Embeddium jar copied into `run/mods` for that run. In
ForgeGradle's development runtime, production Embeddium jars are not safe to use
directly because their refmaps target runtime names. The script therefore
prefers ForgeGradle's mapped cache jar under
`~/.gradle/caches/forge_gradle/deobf_dependencies/...`.

By default, the Embeddium profile leaves Embeddium's original chunk draw path
active. Amdium's Embeddium MDI replacement is currently an explicit
draw-equivalence experiment because incorrect replacement can render entities
while making terrain chunks invisible. Enable it only for focused debugging:

First validate that the mixin/cancel path itself is safe by using BaseVertex
passthrough. This intercepts Embeddium's draw and records telemetry, but still
uses Embeddium's original OpenGL draw shape:

```bash
JAVA_TOOL_OPTIONS="-Damdium.experimental.embeddiumBaseVertexPassthrough=true" \
  scripts/run-benchmark.sh --world "/path/to/saves/Minecraft Bench Mark" --profile embeddium
```

Then test direct MDI command conversion:

```bash
JAVA_TOOL_OPTIONS="-Damdium.experimental.embeddiumMdi=true" \
  scripts/run-benchmark.sh --world "/path/to/saves/Minecraft Bench Mark" --profile embeddium
```

For hybrid experiments, keep batches below a command threshold on Embeddium's
original BaseVertex path while converting larger batches to MDI:

```bash
JAVA_TOOL_OPTIONS="-Damdium.experimental.embeddiumMdi=true -Damdium.experimental.embeddiumMdiMinCommands=64" \
  scripts/run-benchmark.sh --world "/path/to/saves/Minecraft Bench Mark" --profile embeddium
```

The measured 2.2.0 default is 96 when the MDI experiment is enabled. Set the
property explicitly only for threshold sweeps; `0` forces every batch through
MDI.

The compute-culling/IndirectCount layer is gated separately:

```bash
JAVA_TOOL_OPTIONS="-Damdium.experimental.embeddiumMdi=true -Damdium.experimental.embeddiumComputeCulling=true -Damdium.experimental.forceInteropFog=true" \
  scripts/run-benchmark.sh --world "/path/to/saves/Minecraft Bench Mark" --profile embeddium
```

To measure Hi-Z depth-pyramid update cost without allowing it to hide terrain,
use build-only mode. This should keep block rendering equivalent to the
compute-culling run while filling the Hi-Z telemetry columns:

```bash
JAVA_TOOL_OPTIONS="-Damdium.experimental.embeddiumMdi=true -Damdium.experimental.embeddiumComputeCulling=true -Damdium.experimental.hizBuildOnly=true" \
  scripts/run-benchmark.sh --world "/path/to/saves/Minecraft Bench Mark" --profile embeddium
```

Actual Hi-Z terrain occlusion is still an opt-in performance experiment. No
visual errors were observed after the camera-relative frustum fix, but the
2.2.0 benchmark below found it slower than both no-Hi-Z and build-only modes:

```bash
JAVA_TOOL_OPTIONS="-Damdium.experimental.embeddiumMdi=true -Damdium.experimental.embeddiumComputeCulling=true -Damdium.experimental.hizOcclusion=true" \
  scripts/run-benchmark.sh --world "/path/to/saves/Minecraft Bench Mark" --profile embeddium
```

On systems that need an explicit JDK:

```bash
scripts/run-benchmark.sh \
  --java-home /path/to/openjdk-17 \
  --world "/path/to/saves/Minecraft Bench Mark" \
  --profile amdium
```

## CSV output

The mod writes one CSV row per rendered world frame. Warmup rows are marked
`phase=warmup`; measured rows are marked `phase=measure`. Multi-phase runs also
write the configured scenario name to `scene`.

Important columns:

- `render_ms`: CPU-side time spent inside `LevelRenderer.renderLevel`.
- `gpu_frame_ms`: async OpenGL timestamp duration for world rendering. GPU
  query results are delayed by a few frames; use `gpu_max_query_latency_frames`
  and `gpu_pending_samples` to verify healthy collection.
- `gpu_amdium_compute_cull_ms`, `gpu_interop_compute_cull_ms`: GPU time spent
  in the compute culling dispatch plus required barriers.
- `gpu_amdium_mdi_draw_ms`, `gpu_interop_mdi_draw_ms`: GPU time attributed to
  indirect chunk draws.
- `gpu_hiz_copy_depth_ms`, `gpu_hiz_build_mips_ms`: GPU time spent updating the
  depth pyramid used for occlusion culling.
- `vanilla_input_commands`: commands captured by Amdium's vanilla-path MDI layer.
- `interop_input_commands`: commands received from Embeddium batches.
- `interop_compute_batches`: Embeddium batches drawn through the compute culler.
- `interop_fallback_batches`: batches that could not use the compute path.
- `interop_metadata_missing`: commands without per-command metadata.
- `interop_cull_resolved_input_commands`, `interop_cull_visible_commands`,
  `interop_cull_rejected_commands`, `interop_cull_rejection_pct`: exact input
  and compacted output totals from asynchronously resolved compute batches.
- `interop_cull_frustum_rejected_commands`,
  `interop_cull_fog_rejected_commands`, `interop_cull_hiz_rejected_commands`:
  mutually exclusive rejection reasons, evaluated in that order by the shader.
- `interop_cull_pending_frames`, `interop_cull_dropped_frames`,
  `interop_cull_max_latency_frames`: health indicators for the asynchronous
  counter readback ring. A reliable run has zero dropped frames.
- `hiz_update_ms`: CPU-side time spent updating the Hi-Z pyramid.
- `upload_bytes`, `buffer_subdata_bytes`, `persistent_copy_bytes`: transfer
  pressure that helps separate upload bottlenecks from shader/draw bottlenecks.
- `compute_dispatches`, `compute_groups`, `compute_threads`,
  `compute_input_items`: workload shape for AMD wave64-sensitive compute paths.
- `cpu_readbacks`: CPU/GPU synchronization hazard counter.

For reliable comparisons, keep the same world copy, view position, render
distance, graphics settings, display mode, resolution, driver settings, JVM,
warmup, and duration across runs.

When comparing GPU timer columns, exclude zero values. Timestamp results are
resolved asynchronously, so a zero denotes a frame without a resolved sample,
not a zero-cost GPU operation.

The low-overhead GPU frame timestamp is enabled by default. Detailed pass
timers are disabled because an interop frame can contain roughly 150 batches;
timing every upload, dispatch, and draw can issue hundreds of query commands
per frame and materially distort comparisons. Use `--gpu-pass-timers` only for
focused attribution runs, and compare performance only between runs with the
same timer mode. Use `--no-gpu-timers` to disable all timestamp queries.

## Optimization result ledger

This ledger records isolated decisions that feed into the eventual aggregate
2.2.x comparison. Percentages here are not additive: the final result must be
measured again with all accepted changes enabled because optimizations can
overlap or shift the bottleneck.

### 2.2.0: disable redundant Embeddium interop fog-distance culling by default

Status: accepted into the default configuration. The GPU fog-distance test
remains available as `enableInteropFog` for scenes or renderer configurations
where Embeddium submits sections beyond the effective fog distance.

The benchmark compared the compute-frustum path with fog-distance culling on
and off. Disabling the test improved all primary timing summaries in this run:

| Metric | Fog on | Fog off | Change |
| --- | ---: | ---: | ---: |
| CPU `render_ms` mean | 12.182 ms | 11.883 ms | -2.45% |
| GPU frame mean, resolved samples only | 15.541 ms | 15.114 ms | -2.75% |
| GPU frame p50, resolved samples only | 11.741 ms | 11.303 ms | -3.73% |
| GPU frame p95, resolved samples only | 23.703 ms | 23.152 ms | -2.32% |
| Interop MDI draw mean | 3.787 ms | 3.733 ms | -1.43% |
| Interop input commands/frame | 6498.810 | 6376.446 | -1.88% |

The source runs were labelled `embeddium-compute-frustum-default-no-hiz` and
`embeddium-compute-frustum-no-fog-no-hiz`, with 2037 and 2096 measured frames.
There were no missing metadata entries, fallback batches, CPU readbacks, or
Hi-Z updates in either run.

The result is treated as a small accepted optimization, not a universal 2.75%
claim. The identical scene still produced a 1.88% difference in average input
commands because fixed-duration runs sample slightly different frame and world
simulation phases. However, the direction was consistent across CPU, GPU
median, GPU tail, and MDI draw time, and the extra fog test is usually redundant
after Embeddium's own distance filtering.
Its contribution to the later aggregate result is therefore recorded as an
expected low-single-digit improvement that must be revalidated in the final
combined A/B benchmark.

Reproduction commands (portable; Java and world locations are supplied by the
caller):

```bash
JAVA_TOOL_OPTIONS="-Damdium.experimental.embeddiumMdi=true -Damdium.experimental.embeddiumComputeCulling=true" \
  scripts/run-benchmark.sh \
    --world "/path/to/saves/Minecraft Bench Mark" \
    --profile embeddium \
    --warmup 10 \
    --duration 30 \
    --label embeddium-compute-frustum-default-no-hiz

JAVA_TOOL_OPTIONS="-Damdium.experimental.embeddiumMdi=true -Damdium.experimental.embeddiumComputeCulling=true" \
  scripts/run-benchmark.sh \
    --world "/path/to/saves/Minecraft Bench Mark" \
    --profile embeddium \
    --warmup 10 \
    --duration 30 \
    --label embeddium-compute-frustum-no-fog-no-hiz
```

### 2.2.0: Hi-Z pyramid build-only cost

Status: measured, not accepted as an optimization by itself. Build-only mode
measures the fixed pyramid cost without using it for culling.

The `embeddium-compute-frustum-no-fog-hiz-build-only` run contained 2120
measured frames and one Hi-Z update per frame. Across 1519 resolved GPU samples,
depth copy averaged 0.340 ms and mip construction averaged 0.260 ms, for a
combined measured pyramid cost of approximately 0.600 ms per resolved update.
It performed no CPU readbacks. This is the minimum cost that working Hi-Z
occlusion must recover by rejecting enough rendering work before it can improve
the aggregate result.

```bash
JAVA_TOOL_OPTIONS="-Damdium.experimental.embeddiumMdi=true -Damdium.experimental.embeddiumComputeCulling=true -Damdium.experimental.hizBuildOnly=true -Damdium.experimental.disableInteropFog=true" \
  scripts/run-benchmark.sh \
    --world "/path/to/saves/Minecraft Bench Mark" \
    --profile embeddium \
    --warmup 10 \
    --duration 30 \
    --label embeddium-compute-frustum-no-fog-hiz-build-only
```

### 2.2.0: Hi-Z occlusion retest after frustum correction

Status: visually correct in the tested benchmark route, but rejected as a
default performance optimization for the current implementation and scene.
The earlier missing terrain was not reproduced after changing the frustum test
to use camera-relative AABBs. Camera rotation, movement, terrain, and activated
blocks remained visibly correct throughout this run.

Compared with build-only, enabling AABB occlusion increased resolved mean GPU
frame time from 15.358 ms to 16.586 ms (`+8.00%`) and CPU `render_ms` from
11.806 ms to 12.431 ms (`+5.29%`). This happened despite the occlusion run
receiving 6696.365 rather than 6974.007 interop commands per frame (`-3.98%`).
Compared with no Hi-Z, resolved mean GPU frame time regressed by `+9.74%`.

| Metric | No Hi-Z | Build-only | Hi-Z occlusion |
| --- | ---: | ---: | ---: |
| CPU `render_ms` mean | 11.883 ms | 11.806 ms | 12.431 ms |
| GPU frame mean, resolved samples only | 15.114 ms | 15.358 ms | 16.586 ms |
| GPU frame p50, resolved samples only | 11.303 ms | 11.378 ms | 12.278 ms |
| GPU frame p95, resolved samples only | 23.152 ms | 23.964 ms | 25.345 ms |
| Interop compute cull mean | 0.499 ms | 0.491 ms | 0.602 ms |
| Interop MDI draw mean | 3.733 ms | 4.140 ms | 4.062 ms |
| Hi-Z copy + mip means | 0 ms | 0.600 ms | 0.619 ms |
| Interop input commands/frame | 6376.446 | 6974.007 | 6696.365 |

The source run was labelled
`embeddium-compute-frustum-no-fog-hiz-occlusion-retest`, with 1987 measured
frames and 1389 resolved GPU-frame samples. It had one Hi-Z update per frame,
no missing metadata, no fallback batches, and no CPU readbacks.

This run predates compacted-output telemetry and therefore cannot quantify how
many draws Hi-Z rejected. New benchmark CSVs resolve that counter asynchronously
without stalling the render thread. The next design must either make the pyramid
cheaper, make each AABB test cheaper, or reject enough expensive rendering to
recover at least the measured 0.619 ms pyramid cost plus the additional
compute-culling cost.

```bash
JAVA_TOOL_OPTIONS="-Damdium.experimental.embeddiumMdi=true -Damdium.experimental.embeddiumComputeCulling=true -Damdium.experimental.hizOcclusion=true -Damdium.experimental.disableInteropFog=true" \
  scripts/run-benchmark.sh \
    --world "/path/to/saves/Minecraft Bench Mark" \
    --profile embeddium \
    --warmup 10 \
    --duration 30 \
    --label embeddium-compute-frustum-no-fog-hiz-occlusion-retest
```

### 2.2.0: multi-phase Hi-Z rejection breakdown

Status: rejected as a default optimization. The visual result was correct, the
asynchronous counter ring had no dropped frames or invariant failures, and no
OpenGL errors occurred. However, Hi-Z regressed both configured scenes.

The runs `embeddium-multiphase-hiz` and `embeddium-multiphase-no-hiz` used the
same world and `mixed-and-terrain-occlusion.json`. The second scene was produced
by the configured exact 90-degree yaw change, not manual camera input.

| Scene and metric | No Hi-Z | Hi-Z | Change |
| --- | ---: | ---: | ---: |
| Mixed CPU `render_ms` mean | 14.599 ms | 15.206 ms | +4.16% |
| Mixed GPU frame mean | 18.514 ms | 20.495 ms | +10.70% |
| Mixed GPU frame p50 | 21.809 ms | 24.074 ms | +10.39% |
| Mixed compute cull mean | 0.789 ms | 0.968 ms | +22.69% |
| Mixed MDI draw mean | 4.811 ms | 5.038 ms | +4.72% |
| Terrain CPU `render_ms` mean | 8.015 ms | 8.912 ms | +11.19% |
| Terrain GPU frame mean | 7.512 ms | 8.695 ms | +15.75% |
| Terrain GPU frame p50 | 6.169 ms | 6.871 ms | +11.38% |
| Terrain compute cull mean | 0.723 ms | 0.867 ms | +19.92% |
| Terrain MDI draw mean | 3.884 ms | 4.047 ms | +4.20% |

Reason counters show why the pyramid cost did not amortize:

| Scene | Frustum rejection | Additional Hi-Z rejection | Hi-Z rejects/frame |
| --- | ---: | ---: | ---: |
| `mixed-rendering` | 0.72129% | 0.35981% | 34.12 |
| `terrain-occlusion` | 0.96375% | 0.00204% | 0.23 |

The terrain phase rejected only 636 of 31,125,120 resolved input commands via
Hi-Z. Its depth copy and mip build cost averaged 0.540 ms per resolved update,
before the additional compute-culling cost. Mixed rendering rejected 56,124 of
15,598,205 commands, but still regressed because a 0.36% command reduction was
far below break-even.

The likely limiter for the rotated terrain scene is the conservative
`HIZ_MAX_OCCLUSION_BOUNDS_PX=96` guard. A projected chunk AABB larger than 96
pixels bypasses Hi-Z and is treated as visible. Nearby wall and terrain bounds
are likely to hit this path. This must be confirmed with early-out reason
counters before changing the threshold, because relaxing it changes the
correctness envelope.

```bash
JAVA_TOOL_OPTIONS="-Damdium.experimental.embeddiumMdi=true -Damdium.experimental.embeddiumComputeCulling=true -Damdium.experimental.hizOcclusion=true -Damdium.experimental.disableInteropFog=true" \
  scripts/run-benchmark.sh \
    --world "/path/to/saves/Minecraft Bench Mark" \
    --profile embeddium \
    --phases benchmarks/phases/mixed-and-terrain-occlusion.json \
    --label embeddium-multiphase-hiz
```

### 2.2.0: direct MDI versus compute culling

Status: superseded by the hybrid threshold result below. This comparison used
detailed per-pass timers in both paths, so its compute-versus-direct direction
remains useful, but its absolute end-to-end values include query overhead.
Compute culling remains opt-in.

| Scene and metric | Compute culling | Direct MDI | Change |
| --- | ---: | ---: | ---: |
| Mixed CPU `render_ms` mean | 14.599 ms | 10.788 ms | -26.10% |
| Mixed GPU frame mean | 18.514 ms | 11.276 ms | -39.10% |
| Mixed GPU frame p50 | 21.809 ms | 8.809 ms | -59.61% |
| Mixed GPU frame p95 | 26.958 ms | 18.984 ms | -29.58% |
| Mixed MDI draw mean | 4.811 ms | 4.237 ms | -11.93% |
| Terrain CPU `render_ms` mean | 8.015 ms | 5.297 ms | -33.91% |
| Terrain GPU frame mean | 7.512 ms | 6.303 ms | -16.09% |
| Terrain GPU frame p50 | 6.169 ms | 4.761 ms | -22.82% |
| Terrain GPU frame p95 | 12.554 ms | 10.376 ms | -17.35% |

The compute path rejected only 0.72348% of mixed-rendering commands and
0.96375% of terrain-occlusion commands. That saving could not recover its
per-command metadata construction, extra uploads, counter resets, dispatches,
barriers, and atomic compaction.

In mixed rendering, direct MDI reduced upload traffic from 641,245 to 182,121
bytes/frame (`-71.60%`) and buffer update operations from 453.4 to 147.4 per
frame (`-67.50%`). In terrain occlusion, traffic fell from 771,544 to 226,200
bytes/frame (`-70.68%`) and updates from 462 to 154 per frame (`-66.67%`). No
fallback batches, CPU readbacks, or OpenGL errors occurred.

The source runs were labelled `embeddium-multiphase-no-hiz` and
`embeddium-multiphase-direct-mdi`. Compute culling should only be reconsidered
after command metadata becomes GPU-resident or an adaptive policy can prove a
rejection rate high enough to amortize the culling pipeline.

```bash
JAVA_TOOL_OPTIONS="-Damdium.experimental.embeddiumMdi=true" \
  scripts/run-benchmark.sh \
    --world "/path/to/saves/Minecraft Bench Mark" \
    --profile embeddium \
    --phases benchmarks/phases/mixed-and-terrain-occlusion.json \
    --label embeddium-multiphase-direct-mdi
```

### 2.2.0: hybrid MDI threshold and low-overhead timer correction

Status: accepted. GPU frame comparisons now use only one timestamp pair per
frame. Detailed per-pass timers are opt-in because timing roughly 150 batches
previously generated hundreds of query commands per frame and biased native
Embeddium comparisons against Amdium.

With frame-only timing, forcing every batch through MDI improved mixed-rendering
GPU time but regressed terrain. A command-count threshold keeps small batches on
Embeddium's original BaseVertex draw and converts only larger batches to MDI.

| Path | Mixed CPU | Mixed GPU | Terrain CPU | Terrain GPU |
| --- | ---: | ---: | ---: | ---: |
| Native Embeddium | 10.104 ms | 11.917 ms | 4.582 ms | 5.348 ms |
| All batches MDI | 10.029 ms | 9.272 ms | 4.696 ms | 5.907 ms |
| Hybrid threshold 32 | 10.095 ms | 9.528 ms | 4.357 ms | 6.520 ms |
| Hybrid threshold 64 | 10.065 ms | 10.901 ms | 4.386 ms | 4.994 ms |
| Hybrid threshold 96 | 10.093 ms | 11.056 ms | 4.372 ms | 5.166 ms |

The initial sweep favored 64, but it still loaded compute-only metadata mixins.
After those mixins were gated off and both native and hybrid paths were rerun,
64 regressed terrain mean GPU time by 4.55%. A clean 96 rerun produced the best
confirmed worst-case result:

| Clean comparison | Native Embeddium | Hybrid threshold 96 | Change |
| --- | ---: | ---: | ---: |
| Mixed CPU mean | 10.122 ms | 10.001 ms | -1.20% |
| Mixed GPU mean | 11.886 ms | 11.216 ms | -5.64% |
| Mixed GPU p50 | 9.070 ms | 8.718 ms | -3.88% |
| Mixed GPU p95 | 19.423 ms | 18.408 ms | -5.23% |
| Terrain CPU mean | 4.420 ms | 4.440 ms | +0.45% |
| Terrain GPU mean | 5.233 ms | 4.899 ms | -6.38% |
| Terrain GPU p50 | 4.303 ms | 3.910 ms | -9.13% |
| Terrain GPU p95 | 8.923 ms | 8.531 ms | -4.39% |

The earlier threshold-96 sweep also improved both GPU scene means, supporting
the direction across two runs. No OpenGL errors were observed.

The accepted threshold is the default whenever
`amdium.experimental.embeddiumMdi=true`; use
`amdium.experimental.embeddiumMdiMinCommands` only to override it.
Compute-only metadata and region-offset mixins are not loaded for native or
hybrid MDI runs; they are gated on `embeddiumComputeCulling` to avoid unrelated
Embeddium hooks and mesh-update work.

```bash
JAVA_TOOL_OPTIONS="-Damdium.experimental.embeddiumMdi=true" \
  scripts/run-benchmark.sh \
    --world "/path/to/saves/Minecraft Bench Mark" \
    --profile embeddium \
    --phases benchmarks/phases/mixed-and-terrain-occlusion.json \
    --label embeddium-multiphase-hybrid-mdi-96
```

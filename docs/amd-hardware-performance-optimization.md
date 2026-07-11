# Amdium AMD Hardware Performance Optimization Backlog

Date: 2026-07-10

This document ranks optimization potential for Amdium by likely impact first. It is based on the current repository, especially:

- `src/main/java/dev/amdium/render/*`
- `src/main/java/dev/amdium/gl/*`
- `src/main/java/dev/amdium/mixin/*`
- `src/main/resources/assets/amdium/shaders/core/*`
- ForgeGradle cached Minecraft 1.20.1 mappings and the mapped Embeddium 0.3.31 dependency already present in the local Gradle cache.
- Targeted ForgeFlower decompilation of the mapped local classes into `build/amdium-pipeline-inspect/decompiled`.

I did not download unofficial Minecraft source. The legal path used here is ForgeGradle/Mojang mappings, local mapped jars, targeted ForgeFlower decompilation of a few relevant classes, and public documentation. With Java 17 available, `compileJava` succeeds. `genSources` is not registered in this ForgeGradle 6 project; `gradlew tasks --all` lists mapping/run/IDE tasks but no source-generation task.

## Current measured status (2026-07-11)

The ranking below is the original investigation backlog, not the current work
order. The critical Embeddium mixin, deterministic multi-phase benchmark,
asynchronous GPU counters, reason telemetry, and GPU frame timers now work.

Measured decisions:

- Hi-Z remains disabled: it regressed GPU frame time by 10-16% and rejected
  only 0.36% of commands in mixed rendering and 0.002% in terrain occlusion.
- Per-command compute culling remains disabled: it rejected less than 1% while
  rebuilding and uploading command metadata every frame.
- Hybrid MDI is the current candidate: batches below 96 commands use
  Embeddium's BaseVertex path; larger batches use MDI.
- In clean frame-timer-only runs, hybrid-96 improved mean GPU frame time by
  5.64% in mixed rendering and 6.38% in terrain occlusion versus native
  Embeddium. CPU mean improved 1.20% in mixed and regressed 0.45% in terrain.
- Detailed per-pass timestamp queries are opt-in because hundreds of queries per
  frame materially distorted earlier end-to-end comparisons.

Current priority order:

1. Repeat clean native versus hybrid-96 runs and report run-to-run variance.
2. Make large-batch command data GPU-resident or update it only when dirty.
3. Reduce CPU conversion and upload work in the accepted hybrid path.
4. Keep compute culling and Hi-Z as isolated research paths until their input
   preparation costs are removed and a high-rejection workload proves value.

## Ranking Model

Impact is ranked by expected end-user FPS/frame-time improvement on AMD GPUs, then by how much it removes architectural risk. "High" items should be measured first with GPU/CPU frame captures because they can change where the bottleneck moves.

Validation baseline:

- Build a fixed camera benchmark world with high render distance, dense terrain, caves, translucent blocks, and large occluders.
- Record vanilla, Embeddium-only, and Amdium+Embeddium frame times.
- Capture CPU time, GPU time, draw count, command count, visible count, upload bytes, Hi-Z cost, cull shader cost, and fallback count.
- Use Radeon GPU Profiler/Radeon Developer Panel where possible, plus Java Flight Recorder or Spark for CPU allocation and hot paths.

## Highest Impact First

### 1. Fix the Embeddium interop mixin target class name

**Impact:** Critical runtime blocker for the main realistic path.

**Current state:** The mapped Embeddium 0.3.31 jar contains `me.jellysquid.mods.sodium.client.gl.device.GLRenderDevice$ImmediateDrawCommandList`, but `EmbeddiumDrawCommandListMixin` targets `me.jellysquid.mods.sodium.client.gl.device.GlRenderDevice$ImmediateDrawCommandList`. The only difference is `GLRenderDevice` vs `GlRenderDevice`, but JVM class names are case-sensitive.

**Code anchor:** `src/main/java/dev/amdium/mixin/EmbeddiumDrawCommandListMixin.java`.

**Pipeline fact from local mapped decompilation:** `ImmediateDrawCommandList.multiDrawElementsBaseVertex(MultiDrawBatch, GlIndexType)` calls LWJGL's base-vertex multidraw entry point with `batch.pElementCount`, `batch.pElementPointer`, `batch.size()`, and `batch.pBaseVertex`.

**Potential work:**

- Change the mixin target string to `me.jellysquid.mods.sodium.client.gl.device.GLRenderDevice$ImmediateDrawCommandList`.
- Add a descriptor validation test or startup self-check for the exact target class and method.
- Fail visibly in debug builds if the interop mixin is not applied.
- Add a frame counter for intercepted Embeddium batches; zero interceptions with Embeddium loaded should be treated as a broken install.

**Why AMD:** If the interop mixin does not apply, none of the MDI, GPU culling, Hi-Z, or indirect-count work affects the Embeddium path most users will actually run.

**Validation:** With Embeddium 0.3.31 loaded, logs/counters show intercepted `multiDrawElementsBaseVertex` batches every terrain frame.

### 2. Fix the vanilla draw interception target

**Impact:** Critical runtime blocker for the vanilla path.

**Current state:** `VertexBufferMixin` intercepts `drawWithShader(...)`, but Minecraft 1.20.1 `LevelRenderer.renderChunkLayer` binds each chunk `VertexBuffer` and calls `VertexBuffer.draw()` directly after setting `CHUNK_OFFSET`. Sky/cloud paths use `drawWithShader`, but chunk terrain does not.

**Code anchor:** `src/main/java/dev/amdium/mixin/VertexBufferMixin.java`.

**Pipeline fact from local mapped decompilation:** `renderChunkLayer` iterates `renderChunksInFrustum`, skips empty compiled chunks for the current `RenderType`, sets the chunk offset uniform from the render chunk origin minus camera position, binds the chunk `VertexBuffer`, then calls `draw()`.

**Potential work:**

- Move terrain draw interception to `VertexBuffer.draw()` or inject directly into `LevelRenderer.renderChunkLayer` at the `VertexBuffer.draw()` call site.
- Preserve the currently active `RenderType`, chunk origin, and layer lookup so `registerChunk(...)` receives the correct section/layer.
- Avoid intercepting sky/cloud/UI `VertexBuffer` draws.
- Add a terrain-only interception counter.

**Why AMD:** If vanilla terrain draws are not intercepted, the custom MDI renderer may upload metadata but never replace the actual chunk draw loop.

**Validation:** In vanilla mode, per-layer terrain draw calls are canceled/replaced, and the MDI draw counter is nonzero while vanilla `VertexBuffer.draw()` terrain count drops.

### 3. Add a deterministic benchmark and telemetry layer

**Impact:** Highest overall because every GPU optimization below needs proof.

**Current state:** There are debug config toggles, but no structured frame telemetry or repeatable benchmark harness.

**Potential work:**

- Add per-frame counters for input commands, visible commands, culled by frustum, culled by fog, culled by Hi-Z, fallback batches, upload bytes, GL buffer updates, Hi-Z build time, compute dispatch time, and draw time.
- Add rolling percentiles: p50, p95, p99 CPU frame time and GPU frame time.
- Add debug groups around Amdium passes: command upload, chunk info upload, culling dispatch, Hi-Z copy, Hi-Z mips, MDI draw.
- Add a benchmark command or config mode that records 30-120 seconds to CSV/JSON.
- Add capture recipes for RDNA2 and RDNA3 cards.

**Why AMD:** RGP is much more useful when GPU events are grouped and named. AMD tuning without GPU timing often optimizes the wrong side of the frame.

**Validation:** A benchmark run produces stable deltas within a small tolerance across three repeated runs.

### 4. Fix the vanilla-path shared index buffer

**Impact:** Critical correctness blocker, and correctness must come before performance results.

**Current state:** `AmdiumVertexPool` writes the same six indices repeatedly: `0,1,2,0,2,3`. It does not add `quad * 4`, so multi-quad draws appear to reuse the first four vertices instead of each quad's vertices.

**Code anchor:** `src/main/java/dev/amdium/render/AmdiumVertexPool.java`, around the IBO build loop.

**Potential work:**

- Generate indices as `base = quad * 4`; write `base+0, base+1, base+2, base+0, base+2, base+3`.
- Prefer a 16-bit index buffer for the per-slot pattern if `MAX_VERTS_PER_CHUNK <= 65535`.
- Select `GL_UNSIGNED_SHORT` or `GL_UNSIGNED_INT` consistently in all draw calls.

**Why AMD:** Correct index locality and half-size indices reduce vertex and index bandwidth pressure. More importantly, bad draws make every frame-time measurement suspect.

**Validation:** Render a chunk layer with many quads and verify all faces draw correctly. Compare vertex shader invocation counts and index bandwidth before/after.

### 5. Make the persistent staging ring actually advance

**Impact:** Critical upload-path correctness and performance issue.

**Current state:** `PersistentStagingBuffer.alignToFrame()` floors to the current frame slice. `endFrame()` calls it after pushing a fence, which can reset `writeOffset` to the start of the same slice instead of advancing to the next slice.

**Code anchor:** `src/main/java/dev/amdium/gl/PersistentStagingBuffer.java`.

**Potential work:**

- Replace floor alignment with "next frame start" alignment for end-of-frame advancement.
- Track `currentFrameIndex` explicitly instead of inferring it from `writeOffset`.
- Only wait on a slice's fence before reusing that exact slice.
- Add assertions/counters for slice reuse while fence is pending.

**Why AMD:** Persistent mapped upload streams are valuable on AMD only if they avoid implicit synchronization. Reusing a busy slice can force stalls or corrupt uploads.

**Validation:** Record no CPU waits in the normal upload path at steady state. Stress with repeated chunk rebuilds and verify no visual corruption.

### 6. Fix buffer storage flags for mutable GPU buffers

**Impact:** Critical correctness/performance issue on strict drivers.

**Current state:** The large VBO is allocated with `glBufferStorage` flags `GL_MAP_WRITE_BIT | GL_MAP_PERSISTENT_BIT`, but the code does not persistently map the VBO itself and later updates it via copy/subdata paths.

**Code anchor:** `src/main/java/dev/amdium/render/AmdiumVertexPool.java`.

**Potential work:**

- If the VBO is a copy destination and may be updated by `glBufferSubData`, allocate with `GL_DYNAMIC_STORAGE_BIT`.
- If the VBO itself is persistently mapped, actually map and write it through a ring with explicit synchronization.
- Keep staging buffers and destination buffers conceptually separate.
- Add GL debug output in dev mode and fail fast on invalid operation.

**Why AMD:** AMD's Windows and Mesa drivers may expose invalid storage usage as stalls, validation errors, or silent fallback behavior.

**Validation:** Enable GL debug output and run chunk rebuild stress without storage-related errors.

### 7. Replace fixed 32K-vertex slots with an arena allocator

**Impact:** Very high, especially at high render distances.

**Current state:** The vanilla path reserves `MAX_VERTS_PER_CHUNK * VERTEX_STRIDE` per section slot, about 1.125 MiB per slot at 32768 vertices and 36 bytes/vertex. Most sections use far less.

**Code anchor:** `AmdiumVertexPool`.

**Potential work:**

- Move from fixed slots to an Embeddium/Sodium-like arena with variable-sized allocations.
- Allocate per layer or per face slice instead of whole section slots.
- Compact or defragment lazily when rebuild pressure is low.
- Keep a stable section handle that points to allocation offset/count.
- Make pool size auto-tune from VRAM budget instead of a static MB config.

**Why AMD:** RDNA cards are fast but memory bandwidth and VRAM residency still matter. Wasting hundreds of MiB also increases pressure on other mods, resource packs, and shader pipelines.

**Validation:** Measure average vertex pool occupancy, fragmentation, and maximum loaded sections at render distance 16/24/32.

### 8. Make Embeddium interop the primary optimized path

**Impact:** Very high for realistic modpacks.

**Current state:** The code has a custom vanilla path and an Embeddium interop path. Most performance-focused users will already run Embeddium. The interop path avoids replacing the whole chunk renderer and should be treated as the main product surface.

**Potential work:**

- Prioritize correctness, metrics, and AMD tuning for interop first.
- Keep vanilla path conservative and simpler.
- Explicitly document feature parity differences.
- Add startup logs that show active path and disabled features.

**Why AMD:** Embeddium already solves large CPU-side chunk renderer problems. Amdium should add GPU-driven draw/cull acceleration without duplicating chunk build and render scheduling complexity.

**Validation:** Benchmark Embeddium-only vs Amdium interop on the same world and ensure Amdium does not regress CPU frame time.

### 9. Stop rebuilding all interop draw command and chunk info buffers every draw

**Impact:** Very high in command-heavy scenes.

**Current state:** `InteropComputeCuller.drawWithCulling()` iterates every command, looks up metadata by base vertex, writes chunk info, then converts Embeddium CPU arrays into an input SSBO every intercepted batch.

**Code anchor:** `src/main/java/dev/amdium/render/InteropComputeCuller.java`.

**Potential work:**

- Build GPU-resident per-region/per-pass indirect command buffers when Embeddium section data changes, not every frame.
- Store command metadata alongside region upload data at `setMeshes`/`onBufferResized`.
- On each frame, dispatch compute over already-resident commands.
- Update only dirty regions after chunk rebuilds.
- Keep a CPU fallback path only for unsupported or oversized batches.

**Why AMD:** The GPU can cull and draw quickly, but per-frame CPU conversion plus `glBufferSubData` can move the bottleneck back to the CPU driver path.

**Validation:** CPU samples should show near-zero time in command conversion during camera-only movement.

### 10. Use a robust metadata key for interop per-command culling

**Impact:** Very high because metadata misses disable culling quality.

**Current state:** `PerCommandMetadata` maps only `baseVertex -> SectionInfo`. Base vertex values can collide across regions, passes, or buffers because they are offsets in the current region buffer, not globally unique identities.

**Code anchors:** `PerCommandMetadata`, `SectionRenderDataStorageMixin`, `InteropComputeCuller`.

**Potential work:**

- Key metadata by `(region identity, terrain pass, baseVertex)` or a stable region buffer allocation handle.
- Pass region identity into the draw interception path.
- Track metadata coverage percentage per frame.
- Log/rate-limit warnings when metadata is missing above a threshold.

**Why AMD:** Hi-Z and frustum culling are only useful when the shader receives real AABBs. The current huge-AABB fallback is safe but draws everything.

**Validation:** In a benchmark scene, metadata coverage should be near 100 percent for opaque terrain commands.

### 11. Cache Hi-Z sampler uniform and other uniform locations outside hot loops

**Impact:** High, easy win.

**Current state:** `InteropComputeCuller` calls `glGetUniformLocation(programId, "u_HiZPyramid")` during each culling draw when Hi-Z is ready.

**Code anchor:** `InteropComputeCuller.drawWithCulling()`.

**Potential work:**

- Cache `u_HiZPyramid` during initialization with the other uniform locations.
- Consider a frame UBO for matrices, camera, fog, dimensions, and enable flags.
- Avoid repeated `ForgeConfigSpec.get()` calls in hot loops by snapshotting config into frame-local booleans.

**Why AMD:** Driver calls in the draw path reduce CPU throughput and can serialize validation work.

**Validation:** CPU profile no longer shows uniform-location queries during rendering.

### 12. Remove per-layer `glGetTexLevelParameteri` atlas queries

**Impact:** High in the vanilla path.

**Current state:** `LevelRendererMixin` queries block atlas width/height through GL every `renderChunkLayer` head.

**Code anchor:** `src/main/java/dev/amdium/mixin/LevelRendererMixin.java`.

**Potential work:**

- Query atlas dimensions only on resource reload or texture atlas recreation.
- Cache them in a renderer state object.
- If no public API exists, query once per reload with a GL call, not per layer per frame.

**Why AMD:** `glGet*` calls are classic synchronization hazards and can flush driver work.

**Validation:** No GL getter appears in the per-frame chunk layer path.

### 13. Make Hi-Z cheaper before making it more aggressive

**Impact:** High, especially at high resolutions.

**Current state:** `HiZDepthPyramid.update()` copies full-resolution depth to R32F, then dispatches one compute pass per mip level with barriers after every level.

**Code anchor:** `src/main/java/dev/amdium/render/HiZDepthPyramid.java`.

**Potential work:**

- Build the pyramid at half or quarter resolution first.
- Benchmark full, half, and quarter resolution on 1080p, 1440p, and 4K.
- Cache copy shader uniform locations.
- Avoid `glGetInteger`/`glGetIntegerv` state restore in the hot path by owning explicit render state transitions.
- Consider a depth blit/copy path where compatible, with shader copy only as fallback.
- Double-buffer the Hi-Z texture so the culler never samples a texture being updated.

**Why AMD:** Hi-Z can save many draws, but full-screen passes plus mip dispatch/barriers can dominate when terrain is already cheaply rendered.

**Validation:** Report Hi-Z cost vs saved draw cost. Keep Hi-Z enabled only when it is net-positive.

### 14. Precompute frustum planes once per frame instead of in every compute invocation

**Impact:** High for command-heavy batches.

**Current state:** The compute shaders extract and normalize six frustum planes per invocation from the projection-view matrix.

**Code anchors:** `chunk_culling.comp.glsl`, `chunk_culling_v2.comp.glsl`.

**Potential work:**

- Extract frustum planes on CPU once per frame, as the mixin already does for vanilla CPU fallback.
- Upload planes in a UBO/SSBO.
- In compute, load six planes directly.
- Keep matrix only for Hi-Z projection if needed.

**Why AMD:** This reduces ALU and VGPR pressure, improving wave occupancy and culling throughput on RDNA.

**Validation:** Shader ISA/register usage and dispatch time drop in RGP.

### 15. Reduce Hi-Z per-command math

**Impact:** High when many commands survive frustum/fog.

**Current state:** `chunk_culling_v2.comp.glsl` projects eight AABB corners per command and samples one center texel from the selected mip.

**Potential work:**

- Do a cheap bounding sphere or center-depth test first.
- Use near/far conservative screen rect approximations before projecting all eight corners.
- Use four sample points for large screen rectangles or a min/max strategy to reduce false visibility.
- Early-out tiny boxes where Hi-Z is not worth the cost.
- Disable Hi-Z for very near chunks to avoid false positives and wasted work.

**Why AMD:** Reducing VGPR count and matrix multiplies can improve occupancy more than increasing workgroup size.

**Validation:** Compare cull shader time and false-visible rate.

### 16. Split culling into coarse region pass plus fine section pass

**Impact:** High in scenes with large out-of-frustum or fog-hidden areas.

**Current state:** The v2 shader is per-command. The old interop shader had a region-level idea, but the active v2 path goes straight to command-level work.

**Potential work:**

- First cull render regions or section clusters.
- Dispatch fine per-command culling only for visible clusters.
- Keep per-region counters and command ranges.
- Use fog/render-distance tests at region granularity before Hi-Z.

**Why AMD:** Coarse rejection avoids launching thousands of fine-grained threads and reduces atomic output pressure.

**Validation:** In high render-distance scenes, fine culling invocation count drops significantly.

### 17. Replace global atomic compaction with lower-contention compaction

**Impact:** High when many commands are visible.

**Current state:** Every visible command does `atomicAdd(drawCount, 1)`.

**Potential work:**

- Use per-workgroup local counters and prefix offsets.
- Two-pass compaction: count per group, prefix sums, then scatter.
- Or write a visibility bitset and use indirect count only after a prefix pass.
- Keep the simple atomic path for small batches.

**Why AMD:** Atomics can serialize when most chunks are visible, which is common in open terrain.

**Validation:** Compare dispatch time in open terrain vs occluded city/cave scenes.

### 18. Stop using `glBufferSubData` for hot interop buffers

**Impact:** High on CPU-bound scenes.

**Current state:** Interop input command, chunk info, and counter buffers are updated with `glBufferSubData` every draw.

**Potential work:**

- Use persistently mapped ring buffers for command and chunk info uploads.
- Use `glClearNamedBufferSubData` or mapped writes for counter reset.
- Batch multiple region uploads into a larger per-frame ring.
- Avoid bind/unbind churn by using DSA functions.

**Why AMD:** Persistent mapped rings reduce driver synchronization risk and lower CPU overhead.

**Validation:** CPU driver time and upload stalls decrease under chunk rebuild pressure.

### 19. Increase and resize `MAX_COMMANDS`

**Impact:** Medium to high because fallbacks erase benefits.

**Current state:** Interop culling has `MAX_COMMANDS = 4096`; oversized batches return false and fall back.

**Potential work:**

- Track fallback due to size.
- Grow buffers dynamically with hysteresis.
- Use segmented dispatch/draw for very large batches.
- Set sane caps from GPU limits.

**Why AMD:** A single fallback in a heavy scene can reintroduce CPU draw overhead and hide the value of culling.

**Validation:** Oversized fallback count is zero in stress scenes.

### 20. Move frame data into a UBO or SSBO

**Impact:** Medium to high.

**Current state:** Matrix, camera, fog, dimensions, and toggles are sent through many uniforms per intercepted draw.

**Potential work:**

- Create one frame UBO updated once per frame.
- Bind it for all culling dispatches.
- Use stable std140/std430 packing with vec4 alignment.
- Reuse the same UBO in vanilla and interop cullers.

**Why AMD:** Fewer driver calls and clearer data layout improve CPU throughput.

**Validation:** Per-draw uniform calls drop to near zero.

### 21. Make command generation fully GPU-visible

**Impact:** Medium to high, longer-term.

**Current state:** Amdium converts CPU-side draw arrays to indirect commands.

**Potential work:**

- Capture Embeddium's region allocation data and create indirect command buffers at upload time.
- Store firstIndex/count/baseVertex/baseInstance in GPU buffers.
- Per frame, compute writes only the compacted visible command list.
- Eliminate CPU pointer reads from `MultiDrawBatch` in the hot draw path.

**Why AMD:** This moves Amdium toward a true GPU-driven renderer rather than a CPU-assisted MDI wrapper.

**Validation:** Camera-only movement performs no command upload.

### 22. Avoid drawing translucent terrain through the same culling/draw assumptions

**Impact:** Medium to high for correctness and performance consistency.

**Current state:** The vanilla layer model includes translucent terrain. Sorting and blending rules can differ from opaque chunk rendering.

**Potential work:**

- Keep opaque layers on the aggressive MDI/Hi-Z path.
- Treat translucent with conservative frustum/fog culling first.
- Verify sort order and depth-write state before applying Hi-Z occlusion.
- Add per-layer benchmark counters.

**Why AMD:** Incorrect translucent handling causes artifacts; over-aggressive culling can produce flicker that invalidates performance gains.

**Validation:** Glass/water/leaves scenes render correctly while opaque layers still benefit.

### 23. Use 16-bit shared indices where possible

**Impact:** Medium.

**Current state:** Draws use `GL_UNSIGNED_INT`, and the shared index buffer is built as an `IntBuffer`.

**Potential work:**

- Build a `short` index pattern for up to 32768 vertices per draw.
- Draw with `GL_UNSIGNED_SHORT`.
- Keep 32-bit as fallback only if a section allocation can exceed 65535 vertices.

**Why AMD:** Halving index bandwidth helps vertex-heavy chunk rendering and improves cache behavior.

**Validation:** GPU vertex/index fetch bandwidth decreases with identical visual output.

### 24. Compact or transform the vertex format

**Impact:** Medium to high, larger architectural change.

**Current state:** Amdium stores vanilla `DefaultVertexFormat.BLOCK` at 36 bytes per vertex.

**Potential work:**

- Keep local block position in 16-bit or 10-bit packed form.
- Pack light, color, normal, and overlay into 32-bit words.
- Store atlas UV in a compact fixed-point representation where safe.
- Convert on upload or integrate earlier in chunk mesh build.

**Why AMD:** Terrain rendering is often bandwidth-bound. Smaller vertices allow more visible geometry before memory becomes the bottleneck.

**Validation:** Compare bytes per visible vertex and GPU memory bandwidth.

### 25. Reuse Embeddium culling output instead of duplicating frustum work

**Impact:** Medium to high.

**Current state:** Amdium can run GPU frustum over commands that Embeddium may already have CPU-culled.

**Potential work:**

- Determine exactly what Embeddium has already culled before `MultiDrawBatch`.
- Disable duplicate GPU frustum when it does not reduce command count.
- Keep fog/Hi-Z as Amdium-specific value.
- Add config auto-mode: frustum only when metadata proves it saves work.

**Why AMD:** The best GPU optimization is avoiding unnecessary GPU work.

**Validation:** GPU cull time drops without increasing visible draw count.

### 26. Add adaptive Hi-Z enablement

**Impact:** Medium to high.

**Current state:** Hi-Z is config-driven.

**Potential work:**

- Track Hi-Z cost and saved commands over a rolling window.
- Disable Hi-Z temporarily when it is net-negative.
- Enable aggressively in caves/cities/mountains where occlusion wins.
- Add per-resolution thresholds.

**Why AMD:** Hi-Z wins are scene-dependent. Adaptive behavior can improve worst-case frame time.

**Validation:** Open flat worlds no longer pay heavy Hi-Z cost; occluded worlds keep the gain.

### 27. Add GPU memory budget detection and auto-sizing

**Impact:** Medium.

**Current state:** Pool size is a static config value.

**Potential work:**

- Detect VRAM budget where available.
- Select vertex pool, staging, command, and Hi-Z sizes from budget tiers.
- Add guardrails for APUs and low-VRAM GPUs.
- Expose "Auto", "Conservative", and "Max performance" modes.

**Why AMD:** AMD desktop GPUs, laptop GPUs, and APUs have very different memory limits.

**Validation:** No OOM or paging symptoms on low-VRAM devices; high-end cards use larger pools safely.

### 28. Eliminate per-origin heap/native allocation

**Impact:** Medium in chunk rebuild bursts.

**Current state:** `AmdiumRenderer.uploadOrigin()` allocates and frees a native `FloatBuffer` per origin update.

**Potential work:**

- Use a reusable direct buffer or MemoryStack where safe.
- Batch origin updates into a mapped SSBO ring.
- Upload origins as part of chunk metadata rather than separate calls.

**Why AMD:** Allocation churn increases CPU overhead and can hide GPU gains.

**Validation:** CPU allocation profile shows no native allocation per chunk origin.

### 29. Replace reflection-based VBO ID lookup

**Impact:** Medium and improves reliability.

**Current state:** `ReflectionUtil` chooses the first `int` field in `VertexBuffer`.

**Potential work:**

- Use a Mixin accessor for the exact field.
- Or intercept bind/upload points where the GL buffer ID is known.
- Validate against mappings and fail loudly in dev builds if accessor changes.

**Why AMD:** This is mostly correctness, but wrong buffer IDs cause fallback or duplicate rendering.

**Validation:** Accessor passes on Forge 1.20.1 and Embeddium combinations.

### 30. Cache and minimize GL state restoration in Hi-Z

**Impact:** Medium.

**Current state:** Hi-Z update saves framebuffer and viewport with `glGet*`.

**Potential work:**

- Integrate Hi-Z pass into a known render phase with explicit previous/next state.
- Avoid querying state from GL.
- Cache FBO/viewport state in Amdium's renderer where possible.

**Why AMD:** GL getters can synchronize. Explicit state ownership is faster and less fragile.

**Validation:** No `glGet*` appears in render captures during Hi-Z update.

### 31. Use DSA consistently

**Impact:** Medium.

**Current state:** Code mixes bind-to-edit and some DSA calls.

**Potential work:**

- Use `glCreateBuffers`, `glNamedBufferStorage`, `glNamedBufferSubData`, `glBindBufferBase`, and `glVertexArray*` APIs where available.
- Keep fallback for older GL only if required.
- Centralize GL buffer utilities.

**Why AMD:** DSA reduces accidental state churn and often reduces driver validation overhead.

**Validation:** Fewer bind calls in API trace.

### 32. Tune workgroup sizes with data, not assumptions

**Impact:** Medium.

**Current state:** The code assumes `local_size_x = 64` is ideal for RDNA.

**Potential work:**

- Benchmark 32, 64, 128, and 256 local sizes per architecture.
- Add startup architecture heuristic, but keep measurement override.
- Track dispatch occupancy and time.

**Why AMD:** RDNA supports wave32 and wave64 behavior depending on shader/compiler choices. OpenGL does not give perfect control, so empirical tuning matters.

**Validation:** RGP captures show the selected size is fastest on target GPUs.

### 33. Reduce shader VGPR pressure

**Impact:** Medium.

**Current state:** Culling shaders use arrays, matrix math, AABB corner arrays, and multiple branches.

**Potential work:**

- Remove per-thread frustum extraction.
- Avoid local arrays for eight corners where possible.
- Split Hi-Z culling into a separate pass for candidates only.
- Pack toggles into specialization variants or compile-time defines if runtime branches cost too much.

**Why AMD:** Lower VGPR usage increases occupancy on RDNA/GCN.

**Validation:** Shader compiler stats or RGP show fewer registers and shorter dispatch time.

### 34. Separate opaque, cutout, cutout-mipped, and translucent culling policies

**Impact:** Medium.

**Current state:** Layer handling is broad.

**Potential work:**

- Opaque: full frustum/fog/Hi-Z.
- Cutout: frustum/fog/Hi-Z with conservative alpha assumptions.
- Translucent: frustum/fog first; Hi-Z only after correctness proof.
- Track per-layer win/loss.

**Why AMD:** Per-layer specialization avoids expensive work where it does not pay.

**Validation:** Layer counters show expected command reduction without artifacts.

### 35. Make metadata and buffers lifetime-safe on world/resource reload

**Impact:** Medium.

**Current state:** Cleanup exists, but interop maps and GL resources should be stress-tested across world switches and resource reloads.

**Potential work:**

- Clear per-command metadata on region/storage destruction, not only shutdown.
- Use weak/explicit lifetimes for `SectionStorageBridge`.
- Recreate shader programs and cached uniforms on resource reload.
- Add dev assertions for stale base vertices.

**Why AMD:** Stale metadata can create wrong culling and intermittent driver-visible invalid memory patterns.

**Validation:** Repeated world switches do not grow metadata maps or produce stale draw commands.

### 36. Add fallback counters and make fallback visible

**Impact:** Medium.

**Current state:** Some fallbacks are silent or only logged.

**Potential work:**

- Count fallback reasons: no metadata, batch too large, Hi-Z unavailable, compute unavailable, indirect parameters unavailable, pool full.
- Add a debug overlay or CSV output.
- Rate-limit logs to avoid stutter.

**Why AMD:** Performance problems often come from accidentally running fallback paths.

**Validation:** Benchmark output identifies zero unexpected fallbacks.

### 37. Avoid per-frame config reads in render hot paths

**Impact:** Medium.

**Current state:** Forge config values are read inside render/cull paths.

**Potential work:**

- Snapshot config into a small immutable renderer settings object each frame or on config change.
- Read booleans once before a batch loop.
- Keep UI config updates separate from renderer hot state.

**Why AMD:** This is CPU-side, but driver overhead reductions make Java overhead more visible.

**Validation:** CPU profile shows no config access in tight render loops.

### 38. Add GL debug labels and object names

**Impact:** Medium for tuning velocity.

**Current state:** GL objects are anonymous.

**Potential work:**

- Label buffers, textures, VAOs, programs, and debug groups through KHR_debug.
- Label per pass: Amdium/HiZCopy, Amdium/HiZMips, Amdium/Cull, Amdium/MDIDraw.

**Why AMD:** RGP/API traces become actionable.

**Validation:** Captures show named events and resources.

### 39. Use separate fast paths for "no Hi-Z" and "Hi-Z"

**Impact:** Medium.

**Current state:** Runtime uniform toggles branch inside a single compute shader.

**Potential work:**

- Compile shader variants: frustum+fog only, frustum+fog+Hi-Z, Hi-Z only if needed.
- Use the cheapest variant per scene/config.
- Avoid branches and unused uniforms in common paths.

**Why AMD:** Static variants can reduce registers and branches.

**Validation:** Variant shader dispatch time improves over the dynamic branch version.

### 40. Improve distance/fog culling math

**Impact:** Medium.

**Current state:** Fog culling uses distance to closest AABB point.

**Potential work:**

- Use squared distance to avoid `length`.
- Apply render-distance culling independent of fog when safe.
- Use region-level fog early-out before per-command work.

**Why AMD:** Avoiding square roots and fine-grained work improves compute throughput.

**Validation:** Dispatch time drops with identical visible command count.

### 41. Avoid copying depth through a fragment shader when possible

**Impact:** Medium.

**Current state:** Hi-Z depth copy is a fullscreen draw from depth texture into R32F.

**Potential work:**

- Test direct depth texture sampling in the Hi-Z builder.
- Test `glBlitFramebuffer` or compatible copy routes on target drivers.
- Keep shader copy fallback for incompatible depth formats.

**Why AMD:** Removing a fullscreen pass or reducing format conversion saves bandwidth.

**Validation:** Hi-Z copy time decreases without depth interpretation errors.

### 42. Build Hi-Z only after opaque depth is ready

**Impact:** Medium.

**Current state:** Hi-Z update runs at `renderLevel` return for next frame.

**Potential work:**

- Confirm depth contains the desired occluders and excludes problematic transparent layers.
- If possible, build immediately after opaque terrain for cleaner occlusion input.
- Keep one-frame latency but improve occluder quality.

**Why AMD:** Better occluder quality increases cull rate for the same Hi-Z cost.

**Validation:** Hi-Z culled count increases without visible popping.

### 43. Add temporal hysteresis to occlusion

**Impact:** Medium.

**Current state:** Hi-Z uses previous-frame depth but no explicit hysteresis policy.

**Potential work:**

- Keep recently visible chunks visible for N frames near screen edges.
- Bias occlusion by camera velocity.
- Disable occlusion for chunks crossing near plane or screen edge.

**Why AMD:** Conservative temporal policies prevent flicker while still culling stable occluded geometry.

**Validation:** Fast camera movement has no visible pop-in.

### 44. Track and optimize CPU allocation paths

**Impact:** Medium.

**Current state:** Several hot paths allocate buffers or arrays.

**Potential work:**

- Audit `new float[]`, `memAlloc`, duplicate buffers, and object maps in render paths.
- Use reusable buffers or stack allocations.
- Add JFR allocation tests around chunk rebuild storms.

**Why AMD:** Once GPU draw overhead is lower, Java allocation spikes become visible frame-time spikes.

**Validation:** Allocation rate during rendering is near zero outside chunk rebuild code.

### 45. Replace Java object metadata with packed arrays

**Impact:** Medium.

**Current state:** Chunk and section metadata use objects and maps.

**Potential work:**

- Store origins/AABBs in primitive arrays keyed by dense handles.
- Keep free lists for handles.
- Mirror the same layout to SSBOs.

**Why AMD:** Packed CPU data also maps cleanly to GPU buffers and improves cache locality.

**Validation:** CPU time in metadata lookup and update drops.

### 46. Batch origin and chunk-info updates

**Impact:** Medium.

**Current state:** Origin updates can be separate small GL updates.

**Potential work:**

- Accumulate dirty origins and upload in one contiguous buffer update.
- Store origin in the same section metadata buffer used by culling.
- Avoid one GL call per section.

**Why AMD:** Many tiny buffer updates cost more in driver overhead than the data size suggests.

**Validation:** GL call count decreases during chunk rebuilds.

### 47. Add culling granularity below section level only if data supports it

**Impact:** Medium, later-stage.

**Current state:** AABB is section-sized, so a small visible face can keep a whole section command visible.

**Potential work:**

- Use Embeddium's facing/slice data to create per-facing AABBs.
- Track smaller meshlet-like bounds during chunk mesh build.
- Caution: more commands can increase overhead if not GPU-resident.

**Why AMD:** Finer culling can save vertex/fragment work in caves and dense builds.

**Validation:** Culled vertex count improves more than command count overhead increases.

### 48. Use mesh/task shaders only as a future branch

**Impact:** Potentially high, but high risk.

**Current state:** OpenGL 4.3/4.6 MDI is the practical target for Minecraft 1.20.1 Forge.

**Potential work:**

- Investigate EXT_mesh_shader only as an experimental path for RDNA2+ and compatible drivers.
- Do not block the main OpenGL MDI path on mesh shaders.
- Consider a Vulkan renderer only as a separate product-level effort.

**Why AMD:** Mesh shaders can help, but compatibility risk is large in modded Minecraft.

**Validation:** Experimental branch only after MDI path is stable and measured.

### 49. Keep shader-pack compatibility boundaries explicit

**Impact:** Medium from product perspective.

**Current state:** Custom chunk shaders are simplified compared to vanilla and may not align with shader packs.

**Potential work:**

- Detect Iris/Oculus/shader-pack contexts and disable unsafe paths.
- Document supported render pipelines.
- Keep interop acceleration focused on safe draw command replacement where possible.

**Why AMD:** Users chasing performance often also use shader packs; wrong compatibility creates support load.

**Validation:** Known shader-pack setups fail safe instead of rendering incorrectly.

### 50. Make AMD architecture detection more exact

**Impact:** Low to medium.

**Current state:** Architecture detection uses renderer-name substring heuristics.

**Potential work:**

- Add more renderer strings: RDNA4, APUs, Steam Deck/Van Gogh, Phoenix/Hawk Point, Linux Mesa `gfx` names.
- Separate "AMD detected" from "known tuning profile".
- Use runtime benchmark auto-tuning over hard-coded assumptions.

**Why AMD:** APUs and desktop GPUs need different memory and workgroup defaults.

**Validation:** Logs identify common AMD cards correctly.

### 51. Handle APUs conservatively

**Impact:** Low to medium but important for users.

**Potential work:**

- Lower default pool sizes on integrated GPUs.
- Reduce Hi-Z resolution.
- Favor CPU/GPU overlap and lower bandwidth over maximum batching.

**Why AMD:** APUs share memory bandwidth with the CPU, so large copies and pools can hurt more than they help.

**Validation:** APU benchmark does not regress Embeddium-only.

### 52. Add auto-disable for pathological driver paths

**Impact:** Low to medium.

**Potential work:**

- Detect missing `ARB_indirect_parameters` and avoid count readback paths unless explicitly enabled.
- Detect GL errors and disable only the broken feature, not the whole mod.
- Persist last-known-bad feature flags per GPU/driver if needed.

**Why AMD:** Driver/version diversity is large, especially across Windows and Mesa.

**Validation:** Unsupported systems fall back cleanly.

## Additional Optimization Potential

53. Convert `AmdiumComputeCuller` fallback count readback to an asynchronous ring with delayed readback, or avoid this path entirely when indirect parameters are unavailable.

54. Add `GL_COMMAND_BARRIER_BIT` and `GL_SHADER_STORAGE_BARRIER_BIT` only where required; benchmark whether current barriers are broader than needed.

55. Replace counter reset `glBufferSubData` with `glClearNamedBufferSubData` or persistent mapped writes.

56. Avoid binding SSBOs back to zero in hot paths unless a known downstream state conflict requires it.

57. Store command counts and metadata in one structure-of-arrays layout to improve coalesced GPU reads.

58. Pack AABB min/max as signed 16-bit section-local coordinates plus region origin when precision allows.

59. Pack enable flags into a single uint and avoid multiple uniform calls.

60. Make fog and frustum toggles compile-time shader defines for the benchmarked production profiles.

61. Add fast reject for `size <= smallThreshold` where culling overhead costs more than direct MDI.

62. Add fast accept for regions fully inside frustum to skip six-plane tests per section.

63. Use camera-cell coherence: when camera stays within the same block/section and frustum changes little, reuse previous visibility for stable chunks.

64. Track occlusion confidence per chunk and avoid repeated Hi-Z tests for chunks that are consistently visible.

65. Use a bitset visibility buffer for debug and validation instead of CPU readback.

66. Add a GPU-side stats buffer but read it back only every N frames with fences.

67. Validate that `gl_BaseInstance`/`GL_ARB_shader_draw_parameters` is available before enabling the custom MDI vertex shader path.

68. Add a strict startup capability matrix: MDI, compute, SSBO, buffer storage, indirect parameters, shader draw parameters, KHR_debug.

69. Separate CPU fallback draw from optimized draw code so fallbacks cannot accidentally inherit hot-path state assumptions.

70. Rate-limit warnings for pool full and oversized chunks; repeated logging can create visible stutter.

71. Add a "no debug logging in render path" invariant enforced by tests or code review.

72. Replace `HashMap<VertexBuffer, VboLookup>` with identity/weak lifetime semantics or explicit unlinking to avoid retaining stale buffers.

73. Verify `SectionStorageBridge` entries are removed when Embeddium regions are destroyed.

74. Track `PerCommandMetadata.size()` over time and assert it returns near baseline after leaving a world.

75. Add compatibility tests for Embeddium 0.3.31 exact method descriptors and fail visibly if descriptors change.

76. Remove the stub `RenderSectionMixin` once the real metadata path is proven, or keep it documented as legacy only.

77. Validate `RenderRegionMixin.createStorage` is enough to cover all storages and passes in Embeddium 0.3.31.

78. Include terrain pass in metadata keys, not only section/base vertex.

79. Add one central `RenderPathState` object for active path, capabilities, config snapshot, and counters.

80. Make config UI expose Hi-Z, frustum, fog, and benchmark toggles already present in config but not all shown in UI.

81. Add a "performance preset" control: Safe, Balanced, AMD Max, Debug.

82. Add an A/B runtime switch for Hi-Z resolution without restarting.

83. Add a correctness test scene for atlas UV, lightmap UV, normal, overlay, cutout discard, and fog.

84. Validate block atlas UV handling: the vertex shader currently passes atlas UV through while also having `u_TextureScale`; remove unused scale if not needed.

85. Confirm lightmap division by 240.0 across all relevant vertex data sources and modded light formats.

86. Avoid turning the light layer on/off inside every MDI draw if Minecraft render state already guarantees it.

87. Cache block atlas and lightmap texture bindings when state is already correct.

88. Avoid rebinding element array buffer after VAO bind if the VAO owns it.

89. Avoid unbinding VAO/program/textures in production hot paths unless required by Minecraft state expectations.

90. Add a render-state restore audit for each injection point so state minimization is safe.

91. Use a single immutable quad index buffer shared across paths.

92. Consider one index buffer per max vertex tier to avoid huge always-worst-case buffers.

93. Avoid CPU culling in `MDIDrawCommandBuffer.flushWithCPUCulling()` as a normal fallback; it mutates buffers and can become a CPU hotspot.

94. If CPU culling remains, use primitive arrays and SIMD-friendly loops instead of ByteBuffer position/limit mutation.

95. Avoid `ByteBuffer.limit(...)` mutation on buffers that upstream code may reuse; duplicate first, then limit the duplicate.

96. Add GL object leak checks on shutdown and resource reload.

97. Add out-of-memory handling for large pools and degrade to smaller pools instead of disabling the renderer.

98. Add sparse buffer support only after arena allocation is stable.

99. Track upload bytes per chunk rebuild and compare against Embeddium's native upload path.

100. Avoid copying vanilla vertex data twice in the vanilla path where possible.

101. Investigate intercepting chunk mesh build output before vanilla `VertexBuffer.upload` so Amdium does not depend on reflected VBO IDs.

102. Keep `AmdiumRenderer` and `EmbediumInterop` lifecycles symmetrical: init, begin frame, draw, end frame, destroy.

103. Add tests around render distance changes and pool resizing.

104. Add tests around resource pack atlas resize and reload.

105. Add tests around world switching, dimension switching, and server disconnect.

106. Add tests around enabling/disabling Embeddium/Rubidium and exact dependency ranges.

107. Validate Forge 47.2.0 vs cached Forge 47.4.5 differences do not change target method descriptors.

108. Keep mappings and Embeddium version assumptions in one document or constants file.

109. Add a startup warning when Java/Minecraft/Forge/Embeddium versions are outside the tested matrix.

110. Consider splitting public config from experimental flags so users do not enable unstable paths unknowingly.

111. Add shader compile log and source variant name to errors.

112. Add a debug mode that writes active shader defines and GL capabilities to a file.

113. Validate all shader storage layouts with explicit byte-size constants shared between Java and GLSL.

114. Generate Java/GLSL layout constants from one source or add runtime asserts.

115. Avoid comments claiming "zero-copy" where a GPU-side copy still occurs; precise naming prevents wrong future optimizations.

116. Rename "AmdiumComputeCuller" and "InteropComputeCuller" responsibilities if both remain, so vanilla and interop paths do not diverge silently.

117. Consolidate duplicate shader loading/compilation utilities.

118. Consolidate GL constants into a small utility or use LWJGL constants consistently.

119. Remove unused imports and stale comments that refer to old 32-byte vertex stride.

120. Add a profiling README with exact launch flags, test world, render distance, GPU driver version, and expected CSV columns.

121. Add a small "known bottlenecks" log line after benchmark mode completes.

122. Make every optimization above prove itself against Embeddium-only, not only vanilla Minecraft.

## Suggested Implementation Order

1. Fix the two interception blockers: Embeddium `GLRenderDevice$ImmediateDrawCommandList` target and vanilla terrain `VertexBuffer.draw()` interception.
2. Add telemetry immediately after interception works: counters, debug groups, benchmark CSV.
3. Fix the remaining correctness blockers: index buffer, staging ring, buffer storage flags, metadata keys.
4. Measure Embeddium-only vs Amdium interop and make interop the primary path.
5. Remove per-frame interop command rebuilds and hot `glBufferSubData`.
6. Optimize Hi-Z only after its cost and saved work are visible.
7. Revisit bigger architecture changes: arena allocation, compact vertices, GPU-resident command buffers.

## Public References

- Khronos OpenGL registry: `GL_ARB_multi_draw_indirect`, `GL_ARB_indirect_parameters`, and `GL_ARB_buffer_storage`.
- AMD GPUOpen performance guidance and Radeon GPU Profiler documentation.
- ForgeGradle/Mojang mapping workflow for legal Minecraft source-level inspection.

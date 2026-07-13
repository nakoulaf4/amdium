# Amdium — AMD-optimized chunk renderer for Minecraft 1.20.1 (Forge)

**Version:** v2.3.3
**Compatibility:** Minecraft 1.20.1 + Forge 47.x + Embeddium 0.3.31 (optional)
**Target:** AMD GPU (RDNA/GCN4+), but works on any card with OpenGL 4.4+ (4.6 preferred)

---

## What this mod is

Amdium is a mod that replaces dozens/hundreds of chunk draw calls with a **single**
Multi-Draw Indirect (MDI) call, plus GPU-side frustum/fog/Hi-Z occlusion culling
via a compute shader. The architecture is copied from Nvidium, but adapted for
AMD specifics (wavefront 64, ARB_indirect_parameters, persistent mapping).

It works in two modes:
- **vanilla MDI** — if Embeddium is NOT installed. Intercepts the vanilla chunk renderer.
- **Embeddium interop** — if Embeddium is installed. Intercepts its `multiDrawElementsBaseVertex`
  and replaces it with MDI + per-command GPU culling.

---

## FULL LIST OF OPTIMIZATIONS (v2.3.3)

### Category A: Critical (fixed in v2.3.0, cause of FPS drops)

| # | Optimization | File | Effect |
|---|-------------|------|--------|
| A1 | Cache block atlas size (updated once per second instead of 8× per frame) | `LevelRendererMixin.java` | removed 4-8 ms sync from `glGetTexLevelParameteri` |
| A2 | Cache uniform locations in Hi-Z copy pass + removed `glClear` | `HiZDepthPyramid.java` | removed 2 sync queries per frame |
| A3 | `glClearNamedBufferSubData` for atomic counter reset (instead of `glBufferSubData`) | `InteropComputeCuller.java`, `AmdiumComputeCuller.java` | removed 40-80 sync points per frame |
| A4 | `RingStreamBuffer` for per-frame SSBO uploads (instead of `glBufferSubData`) | `RingStreamBuffer.java` (new) | removed 80-160 sync points per frame |
| A5 | Removed `glFlushMappedNamedBufferRange` on coherent mapping | `PersistentStagingBuffer.java` | removed an extra driver call on every chunk upload |
| A6 | Ring of 3 output + counter buffers (instead of one) | `InteropComputeCuller.java` | removed pipeline serialization between frames |

### Category B: F3 Debug Overlay (added in v2.3.1)

| # | Feature | File |
|---|------|------|
| B1 | F3 overlay shows Amdium status (active/inactive, mode, caps) | `AmdiumDebugOverlay.java` (new) |
| B2 | F3 overlay shows ring stream status (OK/fallback) + Hi-Z status | `AmdiumDebugOverlay.java` |
| B3 | F3 overlay shows culled/total (% + frame lag) — **non-blocking readback** | `InteropComputeCuller.maybeCaptureDebugStats()` |
| B4 | F3 overlay shows path B fallback counter (if > 0 — culling is failing) | `EmbediumInterop.pathBFramesInLastSecond()` |

**B3 — ARCHITECTURALLY IMPORTANT:** culled-stats readback is completely disabled when F3 is closed
(first line of the method is `if (!renderDebug) return`). When F3 is open — a
non-blocking pattern is used: `glCopyNamedBufferSubData` (GPU-side copy) + `glFenceSync` +
`glClientWaitSync(timeout=0)`. If the fence isn't ready — the old value is shown, no waiting.
This guarantees that the zero-readback pipeline is NOT broken for the sake of a nice number in F3.

### Category C: RingStreamBuffer fix (fixed in v2.3.1)

| # | Optimization | File |
|---|-------------|------|
| C1 | Per-slot fences instead of a shared FIFO `GlFence` queue | `RingStreamBuffer.java` |
| C2 | `GlFence.waitSingle()` — public static method to wait on a specific fence | `GlFence.java` |

**Problem in v2.3.0:** `beginFrame()` called `fences.waitOldest()` unconditionally every frame.
With RING_SIZE=3, already by frame 2 this waited on frame 1's fence before writing to slot 1,
which had never actually been used. Real CPU/GPU overlap was limited to ~1 frame
instead of the intended 3.

**Fix in v2.3.1:** each slot has its own fence. `beginFrame()` waits ONLY on
the current slot's fence, and ONLY if it's non-zero (the slot has already been used). For the first
RING_SIZE frames there's no waiting at all.

### Category D: Multi-bind (added in v2.3.1)

| # | Optimization | File |
|---|-------------|------|
| D1 | `glBindBuffersBase` (one GL call instead of 4× `glBindBufferBase`) for SSBO bindings | `InteropComputeCuller.java`, `AmdiumComputeCuller.java` |

4 SSBO binds → 1 multi-bind call. Saves on driver overhead on every dispatch.

### Category E: Configurable workgroup size (added in v2.3.2)

| # | Feature | File |
|---|------|------|
| E1 | `AmdiumConfig.INTEROP_WORKGROUP_SIZE` (32-256, default 64) | `AmdiumConfig.java` |
| E2 | String substitution of `local_size_x` before `glShaderSource` | `InteropComputeCuller.compileAndLinkProgram()` |
| E3 | `refreshConfig()` — recompiles the program when workgroup size changes + rollback on error | `InteropComputeCuller.refreshConfig()` |

Allows A/B testing of workgroup size (32 for NVIDIA warp, 64 for AMD wavefront)
without recompiling the mod. If the new value fails to compile — automatic rollback.

### Category F: Hi-Z 2 mips per dispatch (added in v2.3.3)

| # | Optimization | File |
|---|-------------|------|
| F1 | Compute shader builds 2 mip levels per dispatch via shared-memory reduction | `hiz_build.comp.glsl` |
| F2 | Dispatch loop with step 2 (10-11 dispatch+barrier pairs → 5-6) | `HiZDepthPyramid.java` |
| F3 | `u_BuildSecondMip` uniform for the edge case (last level) | `hiz_build.comp.glsl` |

**Why not full FidelityFX SPD?** Classic SPD requires binding all mip levels
(8-13 image bindings), but `GL_MAX_IMAGE_UNITS = 8` on some AMD cards. The compromise is 2 mips
per dispatch (3 image bindings), which fits within the limit. Full SPD — only after an
explicit runtime check of `GL_MAX_IMAGE_UNITS` (TODO for a future version).

### Category G: APU safety (added in v2.3.3)

| # | Feature | File |
|---|------|------|
| G1 | `GPUCapabilityDetector.isAPU()` — detects APUs (Radeon 660M/680M/780M, Vega 3-11, Intel iGPU, ARM Mali) | `GPUCapabilityDetector.java` |
| G2 | `getVertexPoolMBCap()` — caps vertexPoolMB at 1024 MB on APUs | `GPUCapabilityDetector.java` |
| G3 | `Amdium.effectiveVertexPoolMB` — static field, used in `AmdiumVertexPool.init()` | `Amdium.java`, `AmdiumVertexPool.java` |

APUs share memory with system RAM — with `vertexPoolMB=4096` (as for a discrete RD 32 card)
a Radeon 660M could crash the game with OOM. Now it's automatically capped at 1024 MB.

### Category H: UX tooltip (added in v2.3.3)

| # | Feature | File |
|---|------|------|
| H1 | Detailed comment for `FRAMES_IN_FLIGHT` (2 = min input lag, 3 = optimal, 4 = max smoothness) | `AmdiumConfig.java` |
| H2 | Comment for `MDI_VERTEX_POOL_MB` about the APU auto-cap | `AmdiumConfig.java` |
| H3 | Comment for `INTEROP_WORKGROUP_SIZE` about the AMD/NVIDIA difference | `AmdiumConfig.java` |

---

## ARCHITECTURE — brief overview

```
┌─────────────────────────────────────────────────────────────┐
│  Minecraft 1.20.1 + Forge 47.x                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  Embedium 0.3.31 (optional)                            │  │
│  │  ┌──────────────────────────────────────────────────┐  │  │
│  │  │  Amdium v2.3.3                                   │  │  │
│  │  │                                                  │  │  │
│  │  │  ┌──────────────────┐   ┌─────────────────────┐  │  │  │
│  │  │  │ Vanilla MDI path │   │ Embedium interop    │  │  │  │
│  │  │  │ (if no Embed)    │   │ (if Embed present)  │  │  │  │
│  │  │  └────────┬─────────┘   └──────────┬──────────┘  │  │  │
│  │  │           │                        │             │  │  │
│  │  │           ▼                        ▼             │  │  │
│  │  │  ┌────────────────────────────────────────────┐  │  │  │
│  │  │  │ GPU compute culling (frustum + fog + Hi-Z) │  │  │  │
│  │  │  │ → glMultiDrawElementsIndirectCount         │  │  │  │
│  │  │  │   (zero CPU readback)                      │  │  │  │
│  │  │  └────────────────────────────────────────────┘  │  │  │
│  │  └──────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### Zero-readback pipeline (the main trick)

```
Frame N:
  1. CPU writes draw commands → inputBufferId (RingStreamBuffer, zero-sync)
  2. CPU writes chunk AABBs → chunkInfoBufferId (RingStreamBuffer, zero-sync)
  3. glClearNamedBufferSubData(counterBufId, 0)  ← no CPU upload
  4. glDispatchCompute(culler)                    ← GPU culls, writes compacted commands
  5. glMemoryBarrier(SHADER_STORAGE | COMMAND)
  6. glMultiDrawElementsIndirectCount(outBufId, counterBufId)
     ↑ GPU reads drawCount from counterBufId ITSELF — CPU is NOT involved!

Whole frame: 0 CPU↔GPU sync queries (except F3 debug, gated by renderDebug).
```

---

## HOW TO BUILD

### Requirements
- JDK 17
- Forge 1.20.1 MDK (47.x)
- Embeddium 0.3.31 (compileOnly, optional at runtime)

### Steps
```bash
# 1. Unpack amdium_fixed_v2.3.3.zip on top of the mod sources
# 2. Update gradle.properties: mod_version=2.3.3
# 3. Build
./gradlew build

# 4. The JAR will be in build/libs/amdium-2.3.3.jar
# 5. Place it in .minecraft/mods/
```

---

## CONFIGURATION

Config file: `.minecraft/config/amdium-client.toml`

### Recommended settings

**Discrete GPU (RTX 3060, RX 6000+):**
```toml
[general.mdi]
vertexPoolMB = 1024          # 512 for RD 12, 4096 for RD 32
[general.buffers]
framesInFlight = 3           # optimal for most cases
[general.interop]
enableInteropComputeCulling = true
enableInteropFrustum = true
enableInteropFog = true
enableHiZOcclusion = true    # killer feature
interopWorkgroupSize = 64    # 32 for NVIDIA, 64 for AMD
```

**APU (Radeon 660M, Vega iGPU):**
```toml
[general.mdi]
vertexPoolMB = 1024          # automatically capped at 1024 on APU
[general.buffers]
framesInFlight = 3           # APU is weaker, triple buffering helps
[general.interop]
enableHiZOcclusion = true    # provides the main gain
interopWorkgroupSize = 64    # AMD wavefront
```

**Competitive (minimal latency):**
```toml
[general.buffers]
framesInFlight = 2           # minimal input lag, but CPU may wait on GPU
```

---

## F3 DEBUG OVERLAY

When F3 is opened, the following lines appear in the left panel:

```
[Amdium] active — Embeddium interop
 caps: MDI=✓ Compute=✓ Persistent=✓ IndirectCount=✓
 APU: yes, pool=1024 MB (capped)
 ring=OK  HiZ=OK  wg=64  ring=3  maxCmd=4096
 culled: 342/1024 (33%, lagging by 3 frame(s))
```

If culling occasionally fails:
```
 ⚠ path B fallback: 5 frame(s)/sec (culling is failing!)
```

**Note:** the `culled` stat uses non-blocking readback. When F3 is closed —
this path is completely disabled, the zero-readback pipeline is NOT broken.

---

## OPTIMIZATION STATUS

| Category | Status | Version |
|-----------|--------|--------|
| A1-A6 Critical fixes | ✅ Done | v2.3.0 |
| B1-B4 F3 overlay | ✅ Done | v2.3.1 |
| C1-C2 RingStreamBuffer fix | ✅ Done | v2.3.1 |
| D1 Multi-bind | ✅ Done | v2.3.1 |
| E1-E3 Configurable workgroup | ✅ Done | v2.3.2 |
| F1-F3 Hi-Z 2 mips/dispatch | ✅ Done | v2.3.3 |
| G1-G3 APU safety | ✅ Done | v2.3.3 |
| H1-H3 UX tooltips | ✅ Done | v2.3.3 |

### TODO (not done, low priority)

- Full FidelityFX SPD (after checking `GL_MAX_IMAGE_UNITS > 8` at runtime)
- Mesh shaders (EXT_mesh_shader on RDNA2+) — experimental, needs a separate path
- Sparse buffer (ARB_sparse_buffer) for lazy allocation of a large VBO

---

## FILES (changed/new in v2.3.x)

### New files
- `src/main/java/dev/amdium/gl/RingStreamBuffer.java` (v2.3.0, updated v2.3.1)
- `src/main/java/dev/amdium/debug/AmdiumDebugOverlay.java` (v2.3.1)

### Significantly changed
- `src/main/java/dev/amdium/render/InteropComputeCuller.java` (v2.3.0, 2.3.1, 2.3.2)
- `src/main/java/dev/amdium/render/HiZDepthPyramid.java` (v2.3.0, 2.3.3)
- `src/main/java/dev/amdium/render/AmdiumComputeCuller.java` (v2.3.0, 2.3.1)
- `src/main/java/dev/amdium/render/AmdiumRenderer.java` (v2.3.0)
- `src/main/java/dev/amdium/render/EmbediumInterop.java` (v2.3.0, 2.3.1)
- `src/main/java/dev/amdium/mixin/LevelRendererMixin.java` (v2.3.0)
- `src/main/java/dev/amdium/gl/PersistentStagingBuffer.java` (v2.3.0)
- `src/main/java/dev/amdium/gl/GlFence.java` (v2.3.1 — added `waitSingle`)
- `src/main/java/dev/amdium/config/AmdiumConfig.java` (v2.3.2, 2.3.3)
- `src/main/java/dev/amdium/util/GPUCapabilityDetector.java` (v2.3.3)
- `src/main/java/dev/amdium/Amdium.java` (v2.3.3)
- `src/main/java/dev/amdium/render/AmdiumVertexPool.java` (v2.3.3)
- `src/main/resources/assets/amdium/shaders/core/hiz_build.comp.glsl` (v2.3.0, 2.3.3)

### Untouched (original)
- `src/main/java/dev/amdium/AmdiumConfigScreen.java`
- `src/main/java/dev/amdium/mixin/AmdiumMixinPlugin.java`
- `src/main/java/dev/amdium/mixin/ChunkShaderInterfaceMixin.java`
- `src/main/java/dev/amdium/mixin/EmbeddiumDrawCommandListMixin.java`
- `src/main/java/dev/amdium/mixin/MinecraftMixin.java`
- `src/main/java/dev/amdium/mixin/RenderChunkMixin.java`
- `src/main/java/dev/amdium/mixin/RenderRegionMixin.java`
- `src/main/java/dev/amdium/mixin/RenderSectionMixin.java`
- `src/main/java/dev/amdium/mixin/SectionRenderDataStorageMixin.java`
- `src/main/java/dev/amdium/mixin/VertexBufferMixin.java`
- `src/main/java/dev/amdium/render/AmdiumVertexPool.java` (except v2.3.3 APU fix)
- `src/main/java/dev/amdium/render/ChunkMetadataStore.java`
- `src/main/java/dev/amdium/render/MDIDrawCommandBuffer.java`
- `src/main/java/dev/amdium/render/PerCommandMetadata.java`
- `src/main/java/dev/amdium/render/SectionStorageBridge.java`
- `src/main/java/dev/amdium/util/IRenderChunkAccess.java`
- `src/main/java/dev/amdium/util/ReflectionUtil.java`
- `src/main/resources/assets/amdium/shaders/core/chunk_culling.comp.glsl`
- `src/main/resources/assets/amdium/shaders/core/chunk_culling_v2.comp.glsl` (used in v2.3.2 via substitution)
- `src/main/resources/assets/amdium/shaders/core/chunk_culling_interop.comp.glsl`
- `src/main/resources/assets/amdium/shaders/core/chunk_fragment.fsh`
- `src/main/resources/assets/amdium/shaders/core/chunk_vertex.vsh`
- `src/main/resources/META-INF/mods.toml`
- `src/main/resources/amdium.mixins.json`
- `src/main/resources/pack.mcmeta`
- `build.gradle`, `gradle.properties`, `settings.gradle`

---

## ROLLBACK

If something goes wrong:
1. Restore the original files from the source archive
2. The changes do NOT touch `mods.toml`, `amdium.mixins.json`, `build.gradle` —
   the project structure is unchanged
3. All changes are backward compatible (new config options have defaults, fallback
   paths are preserved)

---

## CONTACT / troubleshooting

If you have problems, check the log for:
- `[Amdium] RingStreamBuffer (v2.3.1 per-slot fences): ... KB, 3 frames in flight` — ring stream active
- `[Amdium] InteropComputeCuller v2.3 ready. ... ringStream=enabled` — interop culler with ring
- `[Amdium] Hi-Z pyramid builder ready (v2.3.3 2-mip-per-dispatch)` — Hi-Z active
- `[Amdium] APU detected (...). vertexPoolMB capped at 1024 MB` — APU cap triggered
- `[Amdium] Fallback readback active` — driver update needed (requires GL 4.6 / ARB_indirect_parameters)
- `[Amdium] ⚠ path B fallback` (in F3) — culling is failing, check `MAX_COMMANDS` (4096)

---
---

# Amdium — AMD-оптимизированный рендерер чанков для Minecraft 1.20.1 (Forge)

**Версия:** v2.3.3
**Совместимость:** Minecraft 1.20.1 + Forge 47.x + Embeddium 0.3.31 (опционально)
**Цель:** AMD GPU (RDNA/GCN4+), но работает на любой карте с OpenGL 4.4+ (лучше 4.6)

---

## Что это за мод

Amdium — это мод, который заменяет десятки/сотни draw-вызовов чанков на **один**
Multi-Draw Indirect (MDI) вызов, плюс GPU-side frustum/fog/Hi-Z occlusion culling
через compute shader. Архитектура скопирована с Nvidium, но адаптирована под
специфику AMD (wavefront 64, ARB_indirect_parameters, persistent mapping).

Работает в двух режимах:
- **vanilla MDI** — если Embeddium НЕ установлен. Перехватывает vanilla chunk renderer.
- **Embeddium interop** — если Embeddium установлен. Перехватывает его `multiDrawElementsBaseVertex`
  и заменяет на MDI + per-command GPU culling.

---

## ПОЛНЫЙ СПИСОК ОПТИМИЗАЦИЙ (v2.3.3)

### Категория A: Critical (исправлены в v2.3.0, причина просадки FPS)

| # | Оптимизация | Файл | Эффект |
|---|-------------|------|--------|
| A1 | Кэш размеров block atlas (обновление раз в секунду вместо 8× на кадр) | `LevelRendererMixin.java` | убран 4-8 ms sync от `glGetTexLevelParameteri` |
| A2 | Кэш uniform locations в Hi-Z copy pass + убран `glClear` | `HiZDepthPyramid.java` | убрано 2 sync query на кадр |
| A3 | `glClearNamedBufferSubData` для atomic counter reset (вместо `glBufferSubData`) | `InteropComputeCuller.java`, `AmdiumComputeCuller.java` | убрано 40-80 точек синхронизации на кадр |
| A4 | `RingStreamBuffer` для per-frame SSBO uploads (вместо `glBufferSubData`) | `RingStreamBuffer.java` (новый) | убрано 80-160 точек синхронизации на кадр |
| A5 | Убран `glFlushMappedNamedBufferRange` на coherent mapping | `PersistentStagingBuffer.java` | убран лишний driver call на каждый chunk upload |
| A6 | Ring из 3 output + counter buffer'ов (вместо одного) | `InteropComputeCuller.java` | убрана pipeline serialization между кадрами |

### Категория B: F3 Debug Overlay (добавлено в v2.3.1)

| # | Фича | Файл |
|---|------|------|
| B1 | F3 overlay показывает статус Amdium (active/inactive, режим, caps) | `AmdiumDebugOverlay.java` (новый) |
| B2 | F3 overlay показывает ring stream status (OK/fallback) + Hi-Z status | `AmdiumDebugOverlay.java` |
| B3 | F3 overlay показывает culled/total (% + frame lag) — **non-blocking readback** | `InteropComputeCuller.maybeCaptureDebugStats()` |
| B4 | F3 overlay показывает path B fallback counter (если > 0 — culling отваливается) | `EmbediumInterop.pathBFramesInLastSecond()` |

**B3 — АРХИТЕКТУРНО ВАЖНО:** readback culled stats полностью отключён, когда F3 закрыт
(первая строка метода — `if (!renderDebug) return`). Когда F3 открыт — используется
non-blocking pattern: `glCopyNamedBufferSubData` (GPU-side copy) + `glFenceSync` +
`glClientWaitSync(timeout=0)`. Если fence не готов — показываем старое значение, НЕ ждём.
Это гарантирует, что zero-readback pipeline НЕ нарушается ради красивой циферки в F3.

### Категория C: RingStreamBuffer fix (исправлено в v2.3.1)

| # | Оптимизация | Файл |
|---|-------------|------|
| C1 | Per-slot fences вместо общей FIFO-очереди `GlFence` | `RingStreamBuffer.java` |
| C2 | `GlFence.waitSingle()` — публичный статический метод для ожидания конкретного fence | `GlFence.java` |

**Проблема v2.3.0:** `beginFrame()` вызывал `fences.waitOldest()` безусловно каждый кадр.
При RING_SIZE=3 уже на 2-м кадре это ждало fence 1-го кадра перед записью в слот 1,
который вообще ни разу не был занят. Реальный overlap CPU/GPU ограничивался ~1 кадром
вместо заявленных 3.

**Фикс v2.3.1:** каждый слот имеет свой собственный fence. `beginFrame()` ждёт ТОЛЬКО
fence текущего слота и ТОЛЬКО если он ненулевой (слот уже использовался). Первые
RING_SIZE кадров ожидания нет вообще.

### Категория D: Multi-bind (добавлено в v2.3.1)

| # | Оптимизация | Файл |
|---|-------------|------|
| D1 | `glBindBuffersBase` (один GL call вместо 4× `glBindBufferBase`) для SSBO bindings | `InteropComputeCuller.java`, `AmdiumComputeCuller.java` |

4 SSBO binds → 1 multi-bind call. Экономия на driver overhead при каждом dispatch.

### Категория E: Configurable workgroup size (добавлено в v2.3.2)

| # | Фича | Файл |
|---|------|------|
| E1 | `AmdiumConfig.INTEROP_WORKGROUP_SIZE` (32-256, default 64) | `AmdiumConfig.java` |
| E2 | Строковая подстановка `local_size_x` перед `glShaderSource` | `InteropComputeCuller.compileAndLinkProgram()` |
| E3 | `refreshConfig()` — перекомпиляция program при смене workgroup size + rollback при ошибке | `InteropComputeCuller.refreshConfig()` |

Позволяет A/B тестировать workgroup size (32 для NVIDIA warp, 64 для AMD wavefront)
без перекомпиляции мода. Если новое значение не компилируется — автоматический откат.

### Категория F: Hi-Z 2 mips per dispatch (добавлено в v2.3.3)

| # | Оптимизация | Файл |
|---|-------------|------|
| F1 | Compute shader строит 2 mip level'а за один dispatch через shared-memory reduction | `hiz_build.comp.glsl` |
| F2 | Цикл dispatch с шагом 2 (10-11 dispatch+barrier пар → 5-6) | `HiZDepthPyramid.java` |
| F3 | `u_BuildSecondMip` uniform для edge case (последний level) | `hiz_build.comp.glsl` |

**Почему не полный FidelityFX SPD?** Классический SPD требует bind'ить все mip levels
(8-13 image bindings), но `GL_MAX_IMAGE_UNITS = 8` на части AMD. Компромисс — 2 mips
за dispatch (3 image bindings), укладывается в лимит. Полный SPD — только после
явной проверки `GL_MAX_IMAGE_UNITS` в рантайме (TODO для будущей версии).

### Категория G: APU safety (добавлено в v2.3.3)

| # | Фича | Файл |
|---|------|------|
| G1 | `GPUCapabilityDetector.isAPU()` — детектит APU (Radeon 660M/680M/780M, Vega 3-11, Intel iGPU, ARM Mali) | `GPUCapabilityDetector.java` |
| G2 | `getVertexPoolMBCap()` — ограничивает vertexPoolMB до 1024 MB на APU | `GPUCapabilityDetector.java` |
| G3 | `Amdium.effectiveVertexPoolMB` — static поле, используется в `AmdiumVertexPool.init()` | `Amdium.java`, `AmdiumVertexPool.java` |

APU делят память с ОЗУ — при `vertexPoolMB=4096` (как для RD 32 на дискретной карте)
Radeon 660M может уронить игру по OOM. Теперь автоматически ограничивается до 1024 MB.

### Категория H: UX tooltip (добавлено в v2.3.3)

| # | Фича | Файл |
|---|------|------|
| H1 | Подробный comment у `FRAMES_IN_FLIGHT` (2 = min input lag, 3 = оптимально, 4 = max плавность) | `AmdiumConfig.java` |
| H2 | Comment у `MDI_VERTEX_POOL_MB` про APU-автоограничение | `AmdiumConfig.java` |
| H3 | Comment у `INTEROP_WORKGROUP_SIZE` про AMD/NVIDIA разницу | `AmdiumConfig.java` |

---

## АРХИТЕКТУРА — кратко

```
┌─────────────────────────────────────────────────────────────┐
│  Minecraft 1.20.1 + Forge 47.x                              │
│  ┌────────────────────────────────────────────────────────┐  │
│  │  Embedium 0.3.31 (опционально)                        │  │
│  │  ┌──────────────────────────────────────────────────┐  │  │
│  │  │  Amdium v2.3.3                                   │  │  │
│  │  │                                                  │  │  │
│  │  │  ┌──────────────────┐   ┌─────────────────────┐  │  │  │
│  │  │  │ Vanilla MDI path │   │ Embedium interop    │  │  │  │
│  │  │  │ (если нет Embed) │   │ (если есть Embed)   │  │  │  │
│  │  │  └────────┬─────────┘   └──────────┬──────────┘  │  │  │
│  │  │           │                        │             │  │  │
│  │  │           ▼                        ▼             │  │  │
│  │  │  ┌────────────────────────────────────────────┐  │  │  │
│  │  │  │ GPU compute culling (frustum + fog + Hi-Z) │  │  │  │
│  │  │  │ → glMultiDrawElementsIndirectCount         │  │  │  │
│  │  │  │   (zero CPU readback)                      │  │  │  │
│  │  │  └────────────────────────────────────────────┘  │  │  │
│  │  └──────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
```

### Zero-readback pipeline (главный приём)

```
Frame N:
  1. CPU writes draw commands → inputBufferId (RingStreamBuffer, zero-sync)
  2. CPU writes chunk AABBs → chunkInfoBufferId (RingStreamBuffer, zero-sync)
  3. glClearNamedBufferSubData(counterBufId, 0)  ← без CPU upload
  4. glDispatchCompute(culler)                    ← GPU culls, writes compacted commands
  5. glMemoryBarrier(SHADER_STORAGE | COMMAND)
  6. glMultiDrawElementsIndirectCount(outBufId, counterBufId)
     ↑ GPU САМ читает drawCount из counterBufId — CPU НЕ участвует!

Весь кадр: 0 CPU↔GPU синхронных запросов (кроме F3 debug, gated by renderDebug).
```

---

## КАК СОБРАТЬ

### Требования
- JDK 17
- Forge 1.20.1 MDK (47.x)
- Embeddium 0.3.31 (compileOnly, опционально в рантайме)

### Шаги
```bash
# 1. Распаковать amdium_fixed_v2.3.3.zip поверх исходников мода
# 2. Обновить gradle.properties: mod_version=2.3.3
# 3. Собрать
./gradlew build

# 4. JAR будет в build/libs/amdium-2.3.3.jar
# 5. Положить в .minecraft/mods/
```

---

## КОНФИГУРАЦИЯ

Конфиг-файл: `.minecraft/config/amdium-client.toml`

### Рекомендуемые настройки

**Дискретная карта (RTX 3060, RX 6000+):**
```toml
[general.mdi]
vertexPoolMB = 1024          # 512 для RD 12, 4096 для RD 32
[general.buffers]
framesInFlight = 3           # оптимально для большинства
[general.interop]
enableInteropComputeCulling = true
enableInteropFrustum = true
enableInteropFog = true
enableHiZOcclusion = true    # киллер-фича
interopWorkgroupSize = 64    # 32 для NVIDIA, 64 для AMD
```

**APU (Radeon 660M, Vega iGPU):**
```toml
[general.mdi]
vertexPoolMB = 1024          # автоматически capped до 1024 на APU
[general.buffers]
framesInFlight = 3           # APU слабее, triple buffering помогает
[general.interop]
enableHiZOcclusion = true    # даёт основной прирост
interopWorkgroupSize = 64    # AMD wavefront
```

**Competitive (минимальная задержка):**
```toml
[general.buffers]
framesInFlight = 2           # минимальный input lag, но CPU может ждать GPU
```

---

## F3 DEBUG OVERLAY

При открытии F3 в левой панели появляются строки:

```
[Amdium] активен — Embeddium interop
 caps: MDI=✓ Compute=✓ Persistent=✓ IndirectCount=✓
 APU: да, pool=1024 MB (capped)
 ring=OK  HiZ=OK  wg=64  ring=3  maxCmd=4096
 culled: 342/1024 (33%, отстаёт на 3 кадр.)
```

Если culling периодически отваливается:
```
 ⚠ path B fallback: 5 кадр./сек (culling отваливается!)
```

**Важно:** статистика `culled` использует non-blocking readback. Когда F3 закрыт —
этот путь полностью отключён, zero-readback pipeline НЕ нарушается.

---

## СТАТУС ОПТИМИЗАЦИЙ

| Категория | Статус | Версия |
|-----------|--------|--------|
| A1-A6 Critical fixes | ✅ Сделано | v2.3.0 |
| B1-B4 F3 overlay | ✅ Сделано | v2.3.1 |
| C1-C2 RingStreamBuffer fix | ✅ Сделано | v2.3.1 |
| D1 Multi-bind | ✅ Сделано | v2.3.1 |
| E1-E3 Configurable workgroup | ✅ Сделано | v2.3.2 |
| F1-F3 Hi-Z 2 mips/dispatch | ✅ Сделано | v2.3.3 |
| G1-G3 APU safety | ✅ Сделано | v2.3.3 |
| H1-H3 UX tooltips | ✅ Сделано | v2.3.3 |

### TODO (не сделано, низкий приоритет)

- Полный FidelityFX SPD (после проверки `GL_MAX_IMAGE_UNITS > 8` в рантайме)
- Mesh shaders (EXT_mesh_shader на RDNA2+) — экспериментально, нужен отдельный путь
- Sparse buffer (ARB_sparse_buffer) для lazy-аллокации большого VBO

---

## ФАЙЛЫ (изменённые/новые в v2.3.x)

### Новые файлы
- `src/main/java/dev/amdium/gl/RingStreamBuffer.java` (v2.3.0, обновлён v2.3.1)
- `src/main/java/dev/amdium/debug/AmdiumDebugOverlay.java` (v2.3.1)

### Значительно изменённые
- `src/main/java/dev/amdium/render/InteropComputeCuller.java` (v2.3.0, 2.3.1, 2.3.2)
- `src/main/java/dev/amdium/render/HiZDepthPyramid.java` (v2.3.0, 2.3.3)
- `src/main/java/dev/amdium/render/AmdiumComputeCuller.java` (v2.3.0, 2.3.1)
- `src/main/java/dev/amdium/render/AmdiumRenderer.java` (v2.3.0)
- `src/main/java/dev/amdium/render/EmbediumInterop.java` (v2.3.0, 2.3.1)
- `src/main/java/dev/amdium/mixin/LevelRendererMixin.java` (v2.3.0)
- `src/main/java/dev/amdium/gl/PersistentStagingBuffer.java` (v2.3.0)
- `src/main/java/dev/amdium/gl/GlFence.java` (v2.3.1 — добавлен `waitSingle`)
- `src/main/java/dev/amdium/config/AmdiumConfig.java` (v2.3.2, 2.3.3)
- `src/main/java/dev/amdium/util/GPUCapabilityDetector.java` (v2.3.3)
- `src/main/java/dev/amdium/Amdium.java` (v2.3.3)
- `src/main/java/dev/amdium/render/AmdiumVertexPool.java` (v2.3.3)
- `src/main/resources/assets/amdium/shaders/core/hiz_build.comp.glsl` (v2.3.0, 2.3.3)

### Не тронуты (оригинальные)
- `src/main/java/dev/amdium/AmdiumConfigScreen.java`
- `src/main/java/dev/amdium/mixin/AmdiumMixinPlugin.java`
- `src/main/java/dev/amdium/mixin/ChunkShaderInterfaceMixin.java`
- `src/main/java/dev/amdium/mixin/EmbeddiumDrawCommandListMixin.java`
- `src/main/java/dev/amdium/mixin/MinecraftMixin.java`
- `src/main/java/dev/amdium/mixin/RenderChunkMixin.java`
- `src/main/java/dev/amdium/mixin/RenderRegionMixin.java`
- `src/main/java/dev/amdium/mixin/RenderSectionMixin.java`
- `src/main/java/dev/amdium/mixin/SectionRenderDataStorageMixin.java`
- `src/main/java/dev/amdium/mixin/VertexBufferMixin.java`
- `src/main/java/dev/amdium/render/AmdiumVertexPool.java` (кроме v2.3.3 APU fix)
- `src/main/java/dev/amdium/render/ChunkMetadataStore.java`
- `src/main/java/dev/amdium/render/MDIDrawCommandBuffer.java`
- `src/main/java/dev/amdium/render/PerCommandMetadata.java`
- `src/main/java/dev/amdium/render/SectionStorageBridge.java`
- `src/main/java/dev/amdium/util/IRenderChunkAccess.java`
- `src/main/java/dev/amdium/util/ReflectionUtil.java`
- `src/main/resources/assets/amdium/shaders/core/chunk_culling.comp.glsl`
- `src/main/resources/assets/amdium/shaders/core/chunk_culling_v2.comp.glsl` (используется в v2.3.2 подстановкой)
- `src/main/resources/assets/amdium/shaders/core/chunk_culling_interop.comp.glsl`
- `src/main/resources/assets/amdium/shaders/core/chunk_fragment.fsh`
- `src/main/resources/assets/amdium/shaders/core/chunk_vertex.vsh`
- `src/main/resources/META-INF/mods.toml`
- `src/main/resources/amdium.mixins.json`
- `src/main/resources/pack.mcmeta`
- `build.gradle`, `gradle.properties`, `settings.gradle`

---

## ОТКАТ

Если что-то пойдёт не так:
1. Верните оригинальные файлы из исходного архива
2. Изменения НЕ затрагивают `mods.toml`, `amdium.mixins.json`, `build.gradle` —
   структура проекта не меняется
3. Все изменения обратно совместимы (новые config-опции имеют дефолты, fallback
   пути сохранены)

---

## КОНТАКТ / troubleshooting

При проблемах проверьте лог на:
- `[Amdium] RingStreamBuffer (v2.3.1 per-slot fences): ... KB, 3 frames in flight` — ring stream активен
- `[Amdium] InteropComputeCuller v2.3 готов. ... ringStream=enabled` — interop culler с ring'ом
- `[Amdium] Hi-Z pyramid builder готов (v2.3.3 2-mip-per-dispatch)` — Hi-Z активен
- `[Amdium] APU обнаружен (...). vertexPoolMB ограничен 1024 MB` — APU cap сработал
- `[Amdium] Fallback readback active` — нужно обновить драйвер (нужен GL 4.6 / ARB_indirect_parameters)
- `[Amdium] ⚠ path B fallback` (в F3) — culling отваливается, проверьте `MAX_COMMANDS` (4096)
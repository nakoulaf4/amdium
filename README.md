# Amdium (Русский)

# Amdium v2.0 — AMD-оптимизированный рендерер для Minecraft 1.20.1 Forge

## Что нового в v2.0 (полная переработка)

Версия 1.0 имела критический баг: **ни один чанк не загружался в vertex pool**
(методы `registerChunk`/`uploadChunk` имели ноль callers), а `ci.cancel()` отсутствовал
в `renderChunkLayer`. В результате мод добавлял overhead поверх vanilla без какой-либо
отдачи — FPS был ниже, чем без мода.

Версия 2.0 полностью переписана и теперь **реально работает**:

| Что было в v1.0 | Что стало в v2.0 |
|---|---|
| Upload path не подключён | Mixin в `RenderSection.uploadVertexBuffer` + `setOrigin` |
| `registerChunk` не вызывается | Mixin в `VertexBuffer.drawChunkLayer` регистрирует каждый чанк |
| Нет `ci.cancel()` | `cancel()` в `drawChunkLayer` отменяет каждый vanilla draw call |
| GPU→CPU readback каждый кадр | `glMultiDrawElementsIndirectCount` + `ARB_indirect_parameters` — ноль readback |
| `memAlloc/memFree` в горячем пути | Persistent CPU staging buffers (malloc один раз) |
| CPU-side сборка команд | GPU-generated draw commands (compute shader пишет в indirect buffer) |
| Обычный `glBufferSubData` upload | Persistent-mapped staging + `glCopyNamedBufferSubData` + per-frame fences |
| Frustum-only culling | GPU frustum + distance culling через compute (occlusion в плане) |

---

## Совместимость с Embedium / Rubidium

Amdium автоматически **детектит** Embedium и Rubidium на старте и работает в двух режимах:

### Режим A: Интеграция с Embedium (MDI-ускоритель)

**Если установлен Embedium (или Rubidium):** Amdium активируется в режиме **MDI-ускорителя**.
Embedium продолжает делать свою отличную работу — meshing, vertex format, batched uploads.
Amdium перехватывает финальные draw-вызовы Embedium и заменяет их на
`glMultiDrawElementsIndirect` / `glMultiDrawElementsIndirectCount`:

```
Embedium (meshing):                 Amdium (drawing):
  build chunk meshes                  intercept multiDrawElementsBaseVertex
  compact vertex format               ↓
  region-based VBO                    convert to GL_DRAW_INDIRECT_BUFFER
  face culling                        ↓
  ↓                                   glMultiDrawElementsIndirect (1 call)
  MultiDrawBatch (CPU commands)       ↓
                                      optional: ARB_indirect_parameters (0 readback)
```

Моды **кооперируются** — каждый делает свою сильную сторону. Никакого конфликта.

### Режим B: Полный vanilla path

**Если ни Embedium, ни Rubidium не установлены:** Amdium работает в полном режиме,
заменяя vanilla chunk renderer на свой MDI-pipeline (mixin'ы на vanilla `LevelRenderer`,
`VertexBuffer`, `SectionRenderDispatcher`).

### Логи при запуске

```
[Amdium] Обнаружен Embedium. Amdium активируется в режиме MDI-ускорителя...
[Amdium] Embedium interop активирован. Draw-вызовы перехватываются → MDI.

# или без Embedium:
[Amdium] Мод загружен. Инициализация GPU произойдёт в FMLClientSetupEvent.
[Amdium] Рендерер активирован. Режим: MDI + GPU-Culling
```

---

## Настройка мода

Кнопка **«Config»** доступна в меню **Mods → Amdium → Config**.

Forge автоматически строит UI из `ForgeConfigSpec` — каждый `.comment(...)` из `AmdiumConfig`
становится tooltip'ом, а переводы берутся из `assets/amdium/lang/{en_us,ru_ru}.json`.

Все опции можно менять прямо в игре — изменения применяются на лету (для части опций
может потребоваться перезапуск мира).

---

## Технологии

### 1. Multi-Draw Indirect (MDI) — главная фича
Один вызов `glMultiDrawElementsIndirect` заменяет сотни `glDrawElements`.
Все вершины всех чанков лежат в едином глобальном VBO. Общий IBO
с повторяющимся quad-паттерном `{0,1,2,0,2,3}` шарится всеми чанками.

### 2. GPU-generated draw commands (новое в v2.0)
Compute shader проверяет видимость каждого чанка и атомарно копирует
его draw-команду в compacted output buffer. `atomicAdd` становится
`drawCount` для `glMultiDrawElementsIndirectCount`. **CPU не участвует.**

### 3. ARB_indirect_parameters (новое в v2.0)
`glMultiDrawElementsIndirectCount` читает drawCount прямо из GPU
parameter buffer. Полностью убирает `glGetBufferSubData` readback stall.

### 4. Persistent-mapped staging buffer (новое в v2.0)
Zero-copy uploads через persistent-mapped ring buffer (32-64 MB)
с per-frame `glFenceSync`. `glCopyNamedBufferSubData` делает GPU-side
копирование. Аналог паттерна из nvidium `UploadingBufferStream`.

### 5. GPU frustum culling через compute shader
Workgroup 64 = 1 wavefront на AMD. Positive-vertex AABB test против
6 frustum planes (метод Gribb/Hartmann). Плюс distance culling по fog end.

---

## Структура проекта

```
src/main/java/dev/amdium/
├── Amdium.java                         # Точка входа, GPU init + shutdown
├── config/
│   └── AmdiumConfig.java                # Forge конфиг
├── render/
│   ├── AmdiumRenderer.java              # Координатор кадра + shader program
│   ├── AmdiumVertexPool.java            # VBO + общий IBO + slot allocator
│   ├── AmdiumComputeCuller.java         # Compute shader для GPU culling
│   ├── MDIDrawCommandBuffer.java        # Indirect command buffer (CPU staging)
│   └── ChunkMetadataStore.java          # chunkPos → {slot, layerData, AABB}
├── gl/
│   ├── GlFence.java                     # Per-frame fence ring
│   └── PersistentStagingBuffer.java     # Persistent-mapped upload staging
├── mixin/
│   ├── MinecraftMixin.java              # Init + shutdown hooks
│   ├── LevelRendererMixin.java          # renderLevel + renderChunkLayer
│   ├── SectionRenderDispatcherMixin.java # uploadVertexBuffer + setOrigin + release
│   ├── VertexBufferMixin.java           # drawChunkLayer → registerChunk + cancel
│   ├── RenderSectionAccessor.java       # @Accessor для RenderSection
│   └── VertexBufferAccessor.java        # @Accessor для VertexBuffer
└── util/
    └── GPUCapabilityDetector.java       # AMD детекция + GL capabilities
```

```
src/main/resources/assets/amdium/shaders/core/
├── chunk_culling.comp.glsl              # GPU culling + compacted commands
├── chunk_vertex.vsh                     # Vertex shader с SSBO origins
└── chunk_fragment.fsh                   # Fragment shader с fog + lightmap
```

---

## Оркестрация кадра

```
LevelRenderer.renderLevel HEAD
  └→ Сохраняем projView, camera, frustum planes

LevelRenderer.renderChunkLayer HEAD (один раз на слой)
  └→ AmdiumRenderer.beginFrame() — сброс pending команд

  ↓ Vanilla вызывает VertexBuffer.drawChunkLayer для каждой видимой секции
  ↓ (vanilla делает frustum culling сама)

VertexBuffer.drawChunkLayer HEAD (для каждой секции)
  ├→ Resolve vboId → chunkPos + layerIndex (из ChunkMetadataStore)
  ├→ AmdiumRenderer.registerChunk(packedPos, layerIndex) → добавляет MDI команду
  └→ ci.cancel() — отменяем vanilla draw call (ноль лишних glDrawElements)

LevelRenderer.renderChunkLayer TAIL
  ├→ AmdiumRenderer.drawLayer(...)
  │   ├→ (если compute) MDIDrawCommandBuffer.flushCPU()
  │   │                 → загрузка всех команд в SSBO
  │   ├→ (если compute) AmdiumComputeCuller.dispatch()
  │   │                 → compute shader culls + compacts
  │   │                 → atomic counter = drawCount (без readback!)
  │   ├→ glMultiDrawElementsIndirectCount или glMultiDrawElementsIndirect
  │   └→ Один draw call на весь слой
  └→ AmdiumRenderer.endFrame() — staging fence
```

---

## Требования

| Компонент        | Минимум             | Рекомендуется      |
|------------------|---------------------|--------------------|
| GPU              | Radeon RX 400+      | RDNA1+ (RX 5000+)  |
| OpenGL           | 4.3                 | 4.6                |
| Minecraft        | 1.20.1              | 1.20.1             |
| Forge            | 47.2.0+             | latest             |
| Java             | 17                  | 17+                |

**Опциональные расширения** (автодетектятся, дают прирост):
- `ARB_indirect_parameters` — есть на всех AMD RDNA/GCN4+
- `ARB_buffer_storage` — есть на всех AMD RDNA/GCN4+
- `ARB_sparse_buffer` — для очень больших render distances

---

## Конфигурация

Все опции в `config/amdium-client.toml`:

```toml
[general.mdi]
enableMDI = true
vertexPoolMB = 512          # 512 для RD 12-16, 1024 для RD 32

[general.culling]
enableComputeCulling = true
workgroupSize = 64          # AMD wavefront = 64

[general.indirect]
enableIndirectCount = true  # без readback stall!

[general.buffers]
enablePersistentMapping = true
framesInFlight = 3

[general.integration]
disableVanillaLayer = true  # полностью отменять vanilla render

[general.debug]
logFrameStats = false
```

---

## Сборка

```bash
./gradlew build
# JAR будет в build/libs/amdium-2.0.0.jar
```

---

## Ожидаемый прирост FPS

| Render Distance | Vanilla draw calls | Amdium draw calls | Ожидаемый прирост |
|-----------------|--------------------|-------------------|-------------------|
| 8               | ~250               | 1-4               | 1.3–1.8×          |
| 12              | ~750               | 1-4               | 1.5–2.5×          |
| 16              | ~1400              | 1-4               | 2–3×              |
| 32              | ~5000              | 1-4               | 3–5×              |

*Прирост зависит от CPU (draw call overhead bottleneck) и от того, поддерживает
ли GPU `ARB_indirect_parameters` (если нет — будет readback stall).*

---

# Amdium (English)

# Amdium v2.0 — AMD-optimized renderer for Minecraft 1.20.1 Forge

## What's new in v2.0 (full rewrite)

Version 1.0 had a critical bug: **not a single chunk was ever loaded into the vertex pool**
(the `registerChunk` / `uploadChunk` methods had zero callers), and `ci.cancel()` was missing
in `renderChunkLayer`. As a result, the mod added overhead on top of vanilla with no benefit
whatsoever — FPS was lower than without the mod.

Version 2.0 has been fully rewritten and now **actually works**:

| v1.0 | v2.0 |
|---|---|
| Upload path not connected | Mixin in `RenderSection.uploadVertexBuffer` + `setOrigin` |
| `registerChunk` never called | Mixin in `VertexBuffer.drawChunkLayer` registers every chunk |
| No `ci.cancel()` | `cancel()` in `drawChunkLayer` cancels every vanilla draw call |
| GPU→CPU readback every frame | `glMultiDrawElementsIndirectCount` + `ARB_indirect_parameters` — zero readback |
| `memAlloc/memFree` in the hot path | Persistent CPU staging buffers (malloc once) |
| CPU-side command assembly | GPU-generated draw commands (compute shader writes into the indirect buffer) |
| Plain `glBufferSubData` upload | Persistent-mapped staging + `glCopyNamedBufferSubData` + per-frame fences |
| Frustum-only culling | GPU frustum + distance culling via compute (occlusion planned) |

---

## Embedium / Rubidium compatibility

Amdium automatically **detects** Embedium and Rubidium at startup and operates in two modes:

### Mode A: Embedium integration (MDI accelerator)

**If Embedium (or Rubidium) is installed:** Amdium activates in **MDI accelerator mode**.
Embedium keeps doing what it does best — meshing, vertex format, batched uploads.
Amdium intercepts Embedium's final draw calls and replaces them with
`glMultiDrawElementsIndirect` / `glMultiDrawElementsIndirectCount`:

```
Embedium (meshing):                 Amdium (drawing):
  build chunk meshes                  intercept multiDrawElementsBaseVertex
  compact vertex format               ↓
  region-based VBO                    convert to GL_DRAW_INDIRECT_BUFFER
  face culling                        ↓
  ↓                                   glMultiDrawElementsIndirect (1 call)
  MultiDrawBatch (CPU commands)       ↓
                                      optional: ARB_indirect_parameters (0 readback)
```

The mods **cooperate** — each one does its strong side. No conflict.

### Mode B: Full vanilla path

**If neither Embedium nor Rubidium is installed:** Amdium runs in full mode,
replacing the vanilla chunk renderer with its own MDI pipeline (mixins on vanilla
`LevelRenderer`, `VertexBuffer`, `SectionRenderDispatcher`).

### Startup logs

```
[Amdium] Embedium detected. Amdium activates in MDI-accelerator mode...
[Amdium] Embedium interop activated. Draw calls intercepted → MDI.

# or without Embedium:
[Amdium] Mod loaded. GPU initialization will happen in FMLClientSetupEvent.
[Amdium] Renderer activated. Mode: MDI + GPU-Culling
```

---

## Configuration

The **"Config"** button is available from **Mods → Amdium → Config**.

Forge automatically builds the UI from the `ForgeConfigSpec` — every `.comment(...)`
in `AmdiumConfig` becomes a tooltip, and translations are loaded from
`assets/amdium/lang/{en_us,ru_ru}.json`.

All options can be changed in-game — changes apply on the fly (some options may
require a world reload).

---

## Technologies

### 1. Multi-Draw Indirect (MDI) — the main feature
A single `glMultiDrawElementsIndirect` call replaces hundreds of `glDrawElements`.
All vertices of all chunks live in a single global VBO. A shared IBO with a repeating
quad pattern `{0,1,2,0,2,3}` is reused by every chunk.

### 2. GPU-generated draw commands (new in v2.0)
A compute shader tests each chunk's visibility and atomically copies its draw command
into a compacted output buffer. `atomicAdd` becomes the `drawCount` for
`glMultiDrawElementsIndirectCount`. **The CPU is not involved.**

### 3. ARB_indirect_parameters (new in v2.0)
`glMultiDrawElementsIndirectCount` reads `drawCount` directly from a GPU parameter
buffer. It completely removes the `glGetBufferSubData` readback stall.

### 4. Persistent-mapped staging buffer (new in v2.0)
Zero-copy uploads via a persistent-mapped ring buffer (32-64 MB) with per-frame
`glFenceSync`. `glCopyNamedBufferSubData` performs GPU-side copying. This mirrors
the pattern used in nvidium's `UploadingBufferStream`.

### 5. GPU frustum culling via compute shader
A workgroup of 64 equals one wavefront on AMD. A positive-vertex AABB test is run
against 6 frustum planes (Gribb/Hartmann method), plus distance culling at fog end.

---

## Project structure

```
src/main/java/dev/amdium/
├── Amdium.java                         # Entry point, GPU init + shutdown
├── config/
│   └── AmdiumConfig.java                # Forge config
├── render/
│   ├── AmdiumRenderer.java              # Frame coordinator + shader program
│   ├── AmdiumVertexPool.java            # VBO + shared IBO + slot allocator
│   ├── AmdiumComputeCuller.java         # Compute shader for GPU culling
│   ├── MDIDrawCommandBuffer.java        # Indirect command buffer (CPU staging)
│   └── ChunkMetadataStore.java          # chunkPos → {slot, layerData, AABB}
├── gl/
│   ├── GlFence.java                     # Per-frame fence ring
│   └── PersistentStagingBuffer.java     # Persistent-mapped upload staging
├── mixin/
│   ├── MinecraftMixin.java              # Init + shutdown hooks
│   ├── LevelRendererMixin.java          # renderLevel + renderChunkLayer
│   ├── SectionRenderDispatcherMixin.java # uploadVertexBuffer + setOrigin + release
│   ├── VertexBufferMixin.java           # drawChunkLayer → registerChunk + cancel
│   ├── RenderSectionAccessor.java       # @Accessor for RenderSection
│   └── VertexBufferAccessor.java        # @Accessor for VertexBuffer
└── util/
    └── GPUCapabilityDetector.java       # AMD detection + GL capabilities
```

```
src/main/resources/assets/amdium/shaders/core/
├── chunk_culling.comp.glsl              # GPU culling + compacted commands
├── chunk_vertex.vsh                     # Vertex shader with SSBO origins
└── chunk_fragment.fsh                   # Fragment shader with fog + lightmap
```

---

## Frame orchestration

```
LevelRenderer.renderLevel HEAD
  └→ Save projView, camera, frustum planes

LevelRenderer.renderChunkLayer HEAD (once per layer)
  └→ AmdiumRenderer.beginFrame() — reset pending commands

  ↓ Vanilla calls VertexBuffer.drawChunkLayer for every visible section
  ↓ (vanilla performs frustum culling itself)

VertexBuffer.drawChunkLayer HEAD (for each section)
  ├→ Resolve vboId → chunkPos + layerIndex (from ChunkMetadataStore)
  ├→ AmdiumRenderer.registerChunk(packedPos, layerIndex) → adds an MDI command
  └→ ci.cancel() — cancel the vanilla draw call (zero extra glDrawElements)

LevelRenderer.renderChunkLayer TAIL
  ├→ AmdiumRenderer.drawLayer(...)
  │   ├→ (if compute) MDIDrawCommandBuffer.flushCPU()
  │   │                 → upload all commands into the SSBO
  │   ├→ (if compute) AmdiumComputeCuller.dispatch()
  │   │                 → compute shader culls + compacts
  │   │                 → atomic counter = drawCount (no readback!)
  │   ├→ glMultiDrawElementsIndirectCount or glMultiDrawElementsIndirect
  │   └→ A single draw call for the entire layer
  └→ AmdiumRenderer.endFrame() — staging fence
```

---

## Requirements

| Component        | Minimum             | Recommended        |
|------------------|---------------------|--------------------|
| GPU              | Radeon RX 400+      | RDNA1+ (RX 5000+)  |
| OpenGL           | 4.3                 | 4.6                |
| Minecraft        | 1.20.1              | 1.20.1             |
| Forge            | 47.2.0+             | latest             |
| Java             | 17                  | 17+                |

**Optional extensions** (auto-detected, provide a boost):
- `ARB_indirect_parameters` — present on all AMD RDNA/GCN4+
- `ARB_buffer_storage` — present on all AMD RDNA/GCN4+
- `ARB_sparse_buffer` — for very large render distances

---

## Configuration

All options live in `config/amdium-client.toml`:

```toml
[general.mdi]
enableMDI = true
vertexPoolMB = 512          # 512 for RD 12-16, 1024 for RD 32

[general.culling]
enableComputeCulling = true
workgroupSize = 64          # AMD wavefront = 64

[general.indirect]
enableIndirectCount = true  # no readback stall!

[general.buffers]
enablePersistentMapping = true
framesInFlight = 3

[general.integration]
disableVanillaLayer = true  # fully cancel vanilla rendering

[general.debug]
logFrameStats = false
```

---

## Build

```bash
./gradlew build
# The JAR will be in build/libs/amdium-2.0.0.jar
```

---

## Expected FPS gain

| Render Distance | Vanilla draw calls | Amdium draw calls | Expected gain |
|-----------------|--------------------|-------------------|---------------|
| 8               | ~250               | 1-4               | 1.3–1.8×      |
| 12              | ~750               | 1-4               | 1.5–2.5×      |
| 16              | ~1400              | 1-4               | 2–3×          |
| 32              | ~5000              | 1-4               | 3–5×          |

*The gain depends on the CPU (draw-call overhead bottleneck) and on whether the
GPU supports `ARB_indirect_parameters` (if not, there will be a readback stall).*

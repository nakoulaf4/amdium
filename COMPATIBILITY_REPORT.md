# Amdium × Embeddium — Отчёт по совместимости / Compatibility Report

**Версия мода / Mod version:** Amdium 2.1.0 (interop GPU-culling) · Minecraft 1.20.1 · Forge 47.2.0
**Целевая версия Embeddium / Target Embeddium:** 0.3.31+mc1.20.1
**Дата анализа / Analysis date:** 2026-07-08

---

## 🆕 Что добавлено в v2.1 (поверх исправлений v2.0)

**GPU-compute culling в interop-пути — настоящая фича Amdium поверх Embeddium.**

Когда установлен Embeddium, Amdium теперь не просто конвертирует batch в MDI —
он запускает compute-шейдер, который куллит регион (frustum + fog) и компактирует
draw-команды на GPU, а `glMultiDrawElementsIndirectCount` читает `drawCount`
прямо с GPU. **Нуль readback** — Nvidium-style pipeline.

/ When Embeddium is installed, Amdium now does more than convert the batch to MDI —
it runs a compute shader that culls the region (frustum + fog) and compacts the
draw commands on the GPU, and `glMultiDrawElementsIndirectCount` reads `drawCount`
straight from the GPU. **Zero readback** — a Nvidium-style pipeline.

### Новые файлы / New files
- `src/main/resources/assets/amdium/shaders/core/chunk_culling_interop.comp.glsl` —
  compute-шейдер (GLSL 430): region frustum + fog test, atomicAdd compaction.
- `src/main/java/dev/amdium/render/InteropComputeCuller.java` — загрузка шейдера,
  UBO/SSBO management, dispatch, `glMultiDrawElementsIndirectCount`.
- `src/main/java/dev/amdium/mixin/ChunkShaderInterfaceMixin.java` — захват region
  offset из `ChunkShaderInterface.setRegionOffset` (region AABB для culler'а).

### Конфиг / Config
Новая опция `enableInteropComputeCulling` (по умолчанию `true`):
- `true` + GPU поддерживает GL 4.3 + ARB_indirect_parameters → путь A (compute + zero readback).
- иначе → путь B (прямой MDI, `glMultiDrawElementsIndirect`, CPU-provided count).

### Архитектура пути A / Path A architecture

```
Embeddium DefaultChunkRenderer.render():
  fillCommandBuffer → MultiDrawBatch (CPU, per region)
  setRegionOffset(x,y,z)          ← Amdium ChunkShaderInterfaceMixin захватывает offset
  executeDrawBatch → multiDrawElementsBaseVertex(batch, indexType)
         │
         ▼  ← Amdium EmbeddiumDrawCommandListMixin @Inject HEAD (cancellable)
  EmbediumInterop.interceptMultiDraw(...)
         │
         ▼  (если InteropComputeCuller активен)
  InteropComputeCuller.drawWithCulling():
    1. CPU: MultiDrawBatch → input SSBO (DrawCmd[])
    2. CPU: zero counter buffer (4 байта)
    3. CPU: UBO ← frustum planes + camera + fog + region AABB + commandCount
    4. glDispatchCompute(ceil(size/64), 1, 1)
         │
         ▼  compute shader (chunk_culling_interop.comp.glsl):
         - thread 0: region AABB vs 6 frustum planes (Gribb/Hartmann) + fog distance
         - если регион невидим → outCount остаётся 0 (draw пропускается)
         - иначе: каждый поток atomicAdd(outCount,1) + копирует команду в output
    5. glMemoryBarrier(SHADER_STORAGE | COMMAND)
    6. bind output → GL_DRAW_INDIRECT_BUFFER, counter → GL_PARAMETER_BUFFER
    7. glMultiDrawElementsIndirectCount(TRIANGLES, type, 0, 0, maxCount, 20)
       ↑ GPU читает drawCount из parameter buffer — CPU НЕ участвует
    8. ci.cancel() → оригинальный nglMultiDrawElementsBaseVertex не вызывается
```

### Гранулярность куллинга / Culling granularity
**Region-level** (8×4×8 chunk-секций = 128×64×128 блоков). Все команды в одном
`multiDrawElementsBaseVertex` Embeddium принадлежат одному региону, поэтому
region-test делается один раз (thread 0 каждой workgroup). Embeddium уже CPU-culls
секции, так что frustum-тест региона избыточен — **но fog-distance тест региона
даёт реальную выгоду**: регионы за fog end (ночь, дождь, плотный туман) полностью
пропускаются. Архитектура (per-command SSBO + atomicAdd + IndirectCount) готова
для будущего per-command/per-section куллинга — достаточно populate per-command
AABB SSBO (требует доп. mixin в `DefaultChunkRenderer.fillCommandBuffer`).

/ Region-level. Embeddium already CPU-culls sections, so the region frustum test
is redundant — but the region fog-distance test gives a real benefit: regions
beyond fog end (night, rain, dense fog) are skipped entirely. The architecture
(per-command SSBO + atomicAdd + IndirectCount) is ready for future per-command
culling — just populate a per-command AABB SSBO (needs an additional mixin into
`DefaultChunkRenderer.fillCommandBuffer`).

### Честно о ценности / Honest value assessment
- **Embeddium уже делает CPU frustum + occlusion culling** секций. Amdium в
  interop НЕ заменяет его — он добавляет GPU-side fog-distance куллинг регионов
  + zero-readback architecture.
- Главное достижение — **zero-readback pipeline**: `drawCount` генерируется на
  GPU, CPU никогда не ждёт GPU. Это фундамент для будущего GPU occlusion (Hi-Z)
  culling, который Embeddium's CPU occlusion не может делать эффективно.
- На GPU БЕЗ ARB_indirect_parameters или compute — автоматически fallback на
  путь B (прямой MDI).

/ Embeddium already does CPU frustum + occlusion culling of sections. Amdium in
interop does NOT replace it — it adds GPU-side fog-distance region culling +
zero-readback architecture. The main achievement is the zero-readback pipeline;
it's the foundation for future GPU occlusion (Hi-Z) culling that Embeddium's CPU
occlusion can't do efficiently.

---

## Краткое резюме / Executive Summary

Мод Amdium v2.0 **не мог работать вместе с Embeddium** из-за шести ошибок —
три критические (мод не загружается / interop никогда не срабатывает / детектор
Embeddium помечает мод как «tainting»), три архитектурные. Все шесть исправлены
в этой версии. После исправлений моды **дополняют друг друга**: Embeddium делает
meshing и batched uploads, Amdium перехватывает финальные draw-вызовы и заменяет
их на `glMultiDrawElementsIndirect`.

/ Amdium v2.0 **could not work alongside Embeddium** due to six bugs — three
critical (the mod fails to load / the interop never fires / Embeddium's taint
detector flags the mod), three architectural. All six are fixed in this version.
After the fixes the mods **complement each other**: Embeddium does meshing and
batched uploads, Amdium intercepts the final draw calls and replaces them with
`glMultiDrawElementsIndirect`.

---

## Найденные проблемы / Issues Found

### 🔴 Критическая 1 — Мод не загружается: `NoSuchMethodException: dev.amdium.Amdium.<init>()`

**Симптом / Symptom** (из `run/crash-reports/crash-2026-07-08_19.43.12-fml.txt`):
```
Failure message: Amdium (amdium) has failed to load correctly
    java.lang.NoSuchMethodException: dev.amdium.Amdium.<init>()
Exception message: java.lang.NoSuchMethodException: dev.amdium.Amdium.<init>()
    at java.lang.Class.getDeclaredConstructor(Class.java:2756)
    at net.minecraftforge.fml.javafmlmod.FMLModContainer.constructMod(FMLModContainer.java:68)
```

**Причина / Cause:**
Класс `@Mod` имел единственный конструктор `Amdium(FMLJavaModLoadingContext)`.
В Forge 1.20.1 (47.x), особенно в userdev-окружении ForgeGradle, инъекция
`FMLJavaModLoadingContext` в конструктор ненадёжна — `FMLModContainer` может не
найти конструктор с этим параметром и откатиться к поиску no-arg конструктора,
которого тоже нет → краш.

/ The `@Mod` class had a single constructor `Amdium(FMLJavaModLoadingContext)`.
In Forge 1.20.1 (47.x), especially under the ForgeGradle userdev environment,
`FMLJavaModLoadingContext` injection into the constructor is unreliable —
`FMLModContainer` may fail to find the parameterized constructor and fall back to
looking for a no-arg constructor, which also does not exist → crash.

**Исправление / Fix:**
Конструктор без аргументов + `FMLJavaModLoadingContext.get()`:
```java
public Amdium() {
    FMLJavaModLoadingContext context = FMLJavaModLoadingContext.get();
    context.getModEventBus().addListener(this::clientSetup);
    ...
}
```
Это самый надёжный паттерн для Forge 1.20.1 — работает и в userdev, и в
production.
/ No-arg constructor + `FMLJavaModLoadingContext.get()`. This is the most reliable
pattern for Forge 1.20.1 — it works both in userdev and in production.

**Файл / File:** `src/main/java/dev/amdium/Amdium.java`

---

### 🔴 Критическая 2 — Interop НИКОГДА не перехватывал draw-вызовы

**Симптом / Symptom:**
Embeddium продолжала рисовать чанки сама; Amdium не вмешивался. В логах нет
ошибок (потому что `require = 0` глушит провал инъекции).

**Причина / Cause:**
`EmbeddiumDrawCommandListMixin` объявлял параметры обработчика как `Object`:
```java
private void amdium$onMultiDrawBaseVertex(Object batch, Object indexType, CallbackInfo ci)
```
Но целевой метод Embeddium:
```java
void multiDrawElementsBaseVertex(MultiDrawBatch batch, GlIndexType indexType)
```
**Mixin сопоставляет методы по дескриптору** (имя + типы параметров), а не только
по имени. Дескриптор `(Ljava/lang/Object;Ljava/lang/Object;...)V` не совпадает с
целевым `(Lme/jellysquid/.../MultiDrawBatch;Lme/jellysquid/.../GlIndexType;)V`.
С `require = 0` инъекция **молча проваливалась** — interop был мёртвым кодом.

/ `EmbeddiumDrawCommandListMixin` declared the handler parameters as `Object`, but
**Mixin matches methods by descriptor** (name + parameter types), not just by
name. The `Object`-typed descriptor does not match the target's
`MultiDrawBatch;GlIndexType` descriptor. With `require = 0` the injection
**silently failed** — the interop was dead code.

**Исправление / Fix:**
1. Параметры — настоящие типы Embeddium:
   ```java
   private void amdium$onMultiDrawBaseVertex(MultiDrawBatch batch, GlIndexType indexType, CallbackInfo ci)
   ```
2. Чтобы класс mixin'а можно было загрузить только когда Embeddium установлен,
   добавлен **Mixin-плагин** `AmdiumMixinPlugin` (см. Критическую 3 ниже),
   который применяет этот mixin **только** при наличии Embeddium.
3. Reflection убран — поля `MultiDrawBatch` публичные, доступ прямой:
   ```java
   int size = batch.size;
   long pElementPointer = batch.pElementPointer;   // и т.д.
   int indexTypeSize = indexType.getStride();        // вместо switch по ordinal
   ```

**Файлы / Files:**
- `src/main/java/dev/amdium/mixin/EmbeddiumDrawCommandListMixin.java`
- `src/main/java/dev/amdium/mixin/AmdiumMixinPlugin.java` (новый / new)

---

### 🔴 Критическая 3 — Embeddium MixinTaintDetector помечал Amdium как «tainting»

**Симптом / Symptom:**
В логах Embeddium появилось бы:
```
[Embeddium-MixinTaintDetector] Mod(s) [amdium] are modifying Embeddium class
  me.jellysquid.mods.sodium.client.gl.device.GlRenderDevice$ImmediateDrawCommandList,
  which may cause instability.
Mod mixin into Embeddium internals detected. This instance is now tainted.
```

**Причина / Cause:**
Embeddium содержит `org.embeddedt.embeddium_integrity.MixinTaintDetector` —
расширение Mixin, которое отслеживает любой мод, делающий mixin в классы из
пакетов `me.jellysquid.mods.sodium.*` и `org.embeddedt.embeddium.*`.

Правила детектора (из исходника):
```java
var embeddiumDeps = deps.stream()
    .filter(dep -> dep.getModId().equals("embeddium")).toList();
// Флаг "tainting", если НЕТ зависимости на embeddium ИЛИ
// зависимость не зафиксирована на одной версии (range с lower == upper)
if(embeddiumDeps.isEmpty() || embeddiumDeps.stream().anyMatch(d -> !isDepSingleVersion(d))) {
    map.computeIfAbsent(modId, ...).add(mixin);  // пометить как tainting
}
```

Amdium делал mixin в `GlRenderDevice$ImmediateDrawCommandList` (пакет
`me.jellysquid.mods.sodium.*`), но в `mods.toml` **вообще не было зависимости на
embeddium** → детектор помечал экземпляр как «tainted» (по умолчанию уровень
`WARN` — не крашит, но логирует предупреждение и лишает поддержки).

/ Embeddium ships `MixinTaintDetector`, which tracks any mod mixing into the
`me.jellysquid.mods.sodium.*` / `org.embeddedt.embeddium.*` packages. Amdium mixed
into `GlRenderDevice$ImmediateDrawCommandList` but declared **no embeddium
dependency at all** in `mods.toml` → the detector flagged the instance as
"tainted" (default level `WARN` — no crash, but a warning is logged and support is
voided).

**Исправление / Fix:**
Добавлена зависимость с **зафиксированной версией** (single-version range
`[0.3.31]` — lower bound == upper bound, что проходит проверку
`isDepSingleVersion`):
```toml
[[dependencies.amdium]]
    modId      = "embeddium"
    mandatory  = false
    versionRange = "[0.3.31]"
    ordering   = "AFTER"
    side       = "CLIENT"
```

- `mandatory = false` — мод работает и без Embeddium (vanilla path).
- `versionRange = "[0.3.31]"` — Maven-синтаксис «ровно 0.3.31» (оба бонда равны),
  что удовлетворяет `isDepSingleVersion`. Это версия, против которой Amdium
  компилируется (`compileOnly fg.deobf("maven.modrinth:embeddium:0.3.31+mc1.20.1")`).
- `ordering = "AFTER"` — классы Embeddium загружены ДО применения mixin'а Amdium.

Аналогично добавлена зависимость на `rubidium` (`[0.7.1]`) на случай, если
установлен оригинальный Rubidium.

/ A pinned single-version dependency `[0.3.31]` was added (Maven syntax for
"exactly 0.3.31", which satisfies `isDepSingleVersion`). This is the version
Amdium compiles against. `mandatory = false` keeps the vanilla path working
without Embeddium. `ordering = "AFTER"` ensures Embeddium's classes are loaded
before Amdium's mixin is applied.

**⚠️ Важное замечание / Important caveat:**
При выходе новой версии Embeddium (0.3.32+) нужно обновить `[0.3.31]` в `mods.toml`
и `compileOnly` в `build.gradle`, иначе при установленном 0.3.32 зависимость не
сработает (mandatory=false → мод загрузится, но детектор снова пометит как
tainting, потому что объявленная версия не совпадает с установленной).

/ When a new Embeddium version (0.3.32+) is released, update `[0.3.31]` in
`mods.toml` and the `compileOnly` line in `build.gradle`, otherwise the
dependency won't match the installed version and the taint detector will fire
again.

**Файл / File:** `src/main/resources/META-INF/mods.toml`

---

### 🟡 Архитектурная 4 — `glMultiDrawElementsIndirectCount` не давал выгоды в interop-пути

**Проблема / Problem:**
`EmbediumInterop` использовал `glMultiDrawElementsIndirectCount` +
`glBufferSubData` на parameter buffer каждый кадр. Но в interop-пути `drawCount`
УЖЕ известен на CPU (`batch.size()`) — поэтому «zero readback» не достигается:
мы всё равно загружаем count с CPU. `glBufferSubData` на parameter buffer
добавляет sync-point **без выгоды**.

`ARB_indirect_parameters` имеет смысл **только** когда count генерируется на GPU
(compute-шейдером через `atomicAdd`). В interop-пути этого нет.

/ `EmbediumInterop` used `glMultiDrawElementsIndirectCount` + `glBufferSubData`
on a parameter buffer every frame. But in the interop path `drawCount` is ALREADY
known on the CPU (`batch.size()`) — so "zero readback" is not achieved.
`ARB_indirect_parameters` only makes sense when the count is generated on the GPU
(via `atomicAdd` in a compute shader), which the interop path does not do.

**Исправление / Fix:**
Используется обычный `glMultiDrawElementsIndirect` (CPU-provided count) — проще и
быстрее. Поле `useIndirectParameters` сохранено для будущего GPU-culling пути.

/ Plain `glMultiDrawElementsIndirect` (CPU-provided count) is now used — simpler
and faster. The `useIndirectParameters` field is retained for a future GPU-culling
path.

**Файл / File:** `src/main/java/dev/amdium/render/EmbediumInterop.java`

---

### 🟡 Архитектурная 5 — Vanilla mixin'ы применялись всегда, даже с Embeddium

**Проблема / Problem:**
`LevelRendererMixin`, `VertexBufferMixin`, `RenderChunkMixin` перечислены в
`amdium.mixins.json` безусловно. Когда Embeddium установлен, Embeddium сам
заменяет chunk renderer, и эти mixin'ы бесполезны (они проверяют `Amdium.active`
и выходят). Но они всё равно инъектируются в bytecode — лишний overhead + риск
конфликта точек инъекции с mixin'ами Embeddium.

/ The vanilla mixins were listed unconditionally. When Embeddium is installed
they are useless (guarded by `Amdium.active`) but still injected into bytecode —
extra overhead and a risk of injection-point conflicts with Embeddium's mixins.

**Исправление / Fix:**
`AmdiumMixinPlugin.shouldApplyMixin()` применяет:
- `EmbeddiumDrawCommandListMixin` — **только** при наличии Embeddium/Rubidium;
- `LevelRendererMixin`, `VertexBufferMixin`, `RenderChunkMixin` — **только** при
  ОТСУТСТВИИ Embeddium/Rubidium;
- `MinecraftMixin` (cleanup) — всегда.

/ `AmdiumMixinPlugin.shouldApplyMixin()` applies the Embeddium mixin only when
Embeddium is present, the vanilla-path mixins only when it is absent, and the
cleanup mixin always.

**Файлы / Files:**
- `src/main/java/dev/amdium/mixin/AmdiumMixinPlugin.java` (новый / new)
- `src/main/resources/amdium.mixins.json` (добавлен `"plugin"`)

---

### 🔴 Критическая 7 — Опечатка `GL_PARAMETER_BUFFER = 0x80EE` (должно быть `0x8EE0`)

**Проблема / Problem:**
В трёх файлах vanilla-path (`MDIDrawCommandBuffer.java`, `AmdiumComputeCuller.java`,
`AmdiumRenderer.java`) константа `GL_PARAMETER_BUFFER` была определена как `0x80EE`.
Правильное значение — **`0x8EE0`** (`GL_PARAMETER_BUFFER_ARB`, core в OpenGL 4.6).

`0x80EE` не является валидной GL-константой для parameter buffer — `glBindBuffer(0x80EE, ...)`
биндит буфер к несуществующему/чужому target, и `glMultiDrawElementsIndirectCount` читал
бы `drawCount` из непривязанного буфера → undefined behavior (вероятно 0 команд или краш
драйвера). Vanilla-path IndirectCount был сломан.

/ `0x80EE` is not a valid GL enum for the parameter buffer — `glBindBuffer(0x80EE, ...)`
binds to a non-existent target, and `glMultiDrawElementsIndirectCount` would read
`drawCount` from an unbound buffer → undefined behavior. The vanilla-path IndirectCount
was broken.

**Исправление / Fix:** `sed -i 's/0x80EE/0x8EE0/g'` во всех трёх файлах.

**Файлы / Files:** `MDIDrawCommandBuffer.java`, `AmdiumComputeCuller.java`, `AmdiumRenderer.java`

---

### 🟡 Архитектурная 6 — Ошибка упаковки: файлы не в той директории

**Проблема / Problem:**
`ReflectionUtil.java` и `IRenderChunkAccess.java` лежали в
`src/main/java/dev/amdium/mixin/`, но объявляли `package dev.amdium.util`. В
стандартном Gradle это **ошибка компиляции** (путь файла должен совпадать с
package). `ReflectionUtil` используется в `VertexBufferMixin` через FQN
`dev.amdium.util.ReflectionUtil`.

/ `ReflectionUtil.java` and `IRenderChunkAccess.java` were in the `mixin/`
directory but declared `package dev.amdium.util` — a compile error in standard
Gradle (the file path must match the package).

**Исправление / Fix:**
Оба файла перенесены в `src/main/java/dev/amdium/util/`.

**Файлы / Files:**
- `src/main/java/dev/amdium/util/ReflectionUtil.java` (перемещён / moved)
- `src/main/java/dev/amdium/util/IRenderChunkAccess.java` (перемещён / moved)

> ℹ️ `IRenderChunkAccess` в коде нигде не используется (dead code). Можно удалить
> в будущем. / `IRenderChunkAccess` is unused (dead code) — can be removed later.

---

## Как теперь работают моды вместе / How the mods cooperate now

```
┌─────────────────────────────────────────────────────────────────┐
│  Embeddium (meshing + batching)                                 │
│    build chunk meshes → compact vertex format → region VBO      │
│    assemble MultiDrawBatch (CPU native memory)                  │
│         │                                                       │
│         ▼                                                       │
│    GlRenderDevice.ImmediateDrawCommandList                      │
│        .multiDrawElementsBaseVertex(batch, indexType)           │
│         │                                                       │
│         ▼  ← Amdium mixin @Inject HEAD (cancellable)            │
│    ┌─────────────────────────────────────────────────────────┐  │
│    │  Amdium EmbediumInterop.interceptMultiDraw(...)         │  │
│    │    1. MultiDrawBatch → DrawElementsIndirectCommand[]    │  │
│    │       (cpuStaging ByteBuffer, 20 байт/команда)          │  │
│    │    2. glBufferSubData → GL_DRAW_INDIRECT_BUFFER         │  │
│    │    3. glMultiDrawElementsIndirect (1 draw call)         │  │
│    │    4. ci.cancel() → оригинальный BaseVertex не вызывается│  │
│    └─────────────────────────────────────────────────────────┘  │
│         │                                                       │
│         ▼                                                       │
│    GPU рисует все чанки одним indirect draw                     │
└─────────────────────────────────────────────────────────────────┘
```

- **Embeddium** делает то, что умеет лучше всего: meshing, vertex format,
  batched uploads, CPU frustum culling.
- **Amdium** перехватывает финальный `multiDrawElementsBaseVertex` и заменяет на
  `glMultiDrawElementsIndirect`. Embeddium уже батчит всё в один вызов
  `nglMultiDrawElementsBaseVertex`, поэтому **сокращения количества draw-call'ов
  нет** — но `glMultiDrawElementsIndirect` читает команды из GPU-буфера, что на
  AMD-драйверах часто эффективнее (меньше overhead на driver validation).

/ Embeddium does meshing + batching; Amdium intercepts the final
`multiDrawElementsBaseVertex` and replaces it with `glMultiDrawElementsIndirect`.
Embeddium already batches into one call, so there is no draw-call count
reduction — but `glMultiDrawElementsIndirect` reads commands from a GPU buffer,
which on AMD drivers is often more efficient (less driver-validation overhead).

---

## Сборка / Build

```bash
cd amdium
./gradlew build
# JAR: build/libs/amdium-2.0.0.jar
```

Требования / Requirements: JDK 17, интернет для скачивания зависимостей Forge +
Embeddium (Modrinth maven).

---

## Файлы, изменённые в этом исправлении / Files changed

| Файл / File | Действие / Action |
|---|---|
| `src/main/java/dev/amdium/Amdium.java` | Конструктор без аргументов / No-arg constructor |
| `src/main/java/dev/amdium/mixin/AmdiumMixinPlugin.java` | **Новый / New** — условное применение mixin'ов |
| `src/main/java/dev/amdium/mixin/EmbeddiumDrawCommandListMixin.java` | Настоящие типы вместо Object / Real types instead of Object |
| `src/main/java/dev/amdium/mixin/ChunkShaderInterfaceMixin.java` | **Новый (v2.1)** — захват region offset для GPU culler |
| `src/main/java/dev/amdium/mixin/LevelRendererMixin.java` | Захват frustum+fog в ОБА режимах (vanilla + interop) |
| `src/main/java/dev/amdium/render/EmbediumInterop.java` | Два пути: compute+IndirectCount (A) / прямой MDI (B) |
| `src/main/java/dev/amdium/render/InteropComputeCuller.java` | **Новый (v2.1)** — compute-шейдер + dispatch + zero-readback |
| `src/main/resources/assets/amdium/shaders/core/chunk_culling_interop.comp.glsl` | **Новый (v2.1)** — compute-шейдер |
| `src/main/java/dev/amdium/config/AmdiumConfig.java` | + опция `enableInteropComputeCulling` |
| `src/main/java/dev/amdium/AmdiumConfigScreen.java` | + кнопка для новой опции |
| `src/main/resources/assets/amdium/lang/{en_us,ru_ru}.json` | + переводы новой опции |
| `src/main/resources/META-INF/mods.toml` | Зависимость `[0.3.31]` на embeddium + rubidium |
| `src/main/resources/amdium.mixins.json` | `"plugin"` + `ChunkShaderInterfaceMixin` |
| `src/main/java/dev/amdium/util/ReflectionUtil.java` | Перемещён из `mixin/` / Moved from `mixin/` |
| `src/main/java/dev/amdium/util/IRenderChunkAccess.java` | Перемещён из `mixin/` / Moved from `mixin/` |

---

## Рекомендации на будущее / Future recommendations

1. **Per-command / per-section GPU culling (v2.2).** Текущий culler работает на
   уровне региона. Чтобы куллить отдельные секции/команды на GPU, нужен per-command
   origin SSBO. Заполнять его можно mixin'ом в `DefaultChunkRenderer.fillCommandBuffer`
   (захват `chunkX/Y/Z` + `baseVertex` через `@Redirect` на `addDrawCommands`).
   Тогда compute-шейдер тестирует AABB каждой секции против frustum + Hi-Z depth
   pyramid — настоящий GPU occlusion culling, который Embeddium's CPU occlusion
   не может делать эффективно.

2. **Hi-Z GPU occlusion culling.** Связать depth buffer как texture, строить
   mip-pyramid (min depth), compute-шейдер тестит AABB каждой секции против
   пирамиды. Это «киллер-фича» Nvidium — полностью GPU-driven occlusion.

3. **Тестирование на разных драйверах AMD.** `glMultiDrawElementsIndirect` /
   `glMultiDrawElementsIndirectCount` ведут себя по-разному на AMDVLK / Mesa RADV /
   Windows Adrenalin. Стоит проверить на всех трёх.

4. **Удалить dead code.** `IRenderChunkAccess` не используется.

5. **Версионирование Embeddium-зависимости.** При выходе Embeddium 0.3.32+
   обновить `[0.3.31]` → `[0.3.32]` в `mods.toml` и `compileOnly` в `build.gradle`.

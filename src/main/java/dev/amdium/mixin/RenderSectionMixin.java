package dev.amdium.mixin;

import dev.amdium.Amdium;
import dev.amdium.render.PerCommandMetadata;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin в Embedium's RenderSection — ЗАГЛУШКА (no-op).
 * / Mixin into Embedium's RenderSection — STUB (no-op).
 *
 * ВАЖНО (v2.2 FIX) / IMPORTANT (v2.2 FIX):
 * В Embedium 0.3.31 класс RenderSection НЕ имеет метода upload().
 * Инъекция amdium$onSectionUpload с require=0 молча пропускается.
 * Реальный захват per-section baseVertex теперь реализован в
 * SectionRenderDataStorageMixin (перехват setMeshes/removeMeshes/onBufferResized).
 *
 * Этот класс оставлен в mixins.json для обратной совместимости.
 * / In Embedium 0.3.31, RenderSection has NO upload() method.
 * The injection amdium$onSectionUpload with require=0 is silently skipped.
 * Real per-section baseVertex capture is now in SectionRenderDataStorageMixin
 * (intercepting setMeshes/removeMeshes/onBufferResized).
 *
 * This class is kept in mixins.json for backward compatibility.
 */
@Mixin(value = RenderSection.class, remap = false)
public abstract class RenderSectionMixin {

    // Shadow геттеры chunk-координат (стандартные в Sodium/Embedium).
    @Shadow public abstract int getChunkX();
    @Shadow public abstract int getChunkY();
    @Shadow public abstract int getChunkZ();

    // Shadow для получения region и baseVertex внутри region VBO.
    // RenderRegion.Manager или RenderRegion содержат информацию о выделенном
    // vertex-диапазоне для каждой секции.
    // RenderRegion.Manager or RenderRegion holds the allocated vertex range
    // for each section.
    @Shadow private RenderRegion region;

    /**
     * Захват per-section метаданных после upload'а геометрии в region VBO.
     * / Capture per-section metadata after geometry upload into the region VBO.
     *
     * Целевой метод: RenderSection.upload(CommandList commandList, …)
     * Точная сигнатура может варьироваться — ниже используется строковая форма
     * дескриптора для надёжности. Если Embedium 0.3.31 имеет другую сигнатуру,
     * нужно подкорректировать @Inject(method = "…").
     *
     * / Target method: RenderSection.upload(CommandList commandList, …)
     * The exact signature may vary — the descriptor string form is used below
     * for reliability. If Embedium 0.3.31 has a different signature, adjust the
     * @Inject(method = "…") accordingly.
     */
    @Inject(
            method = "upload",
            at = @At("TAIL"),
            require = 0
    )
    private void amdium$onSectionUpload(CallbackInfo ci) {
        if (!Amdium.embediumInteropActive) return;

        try {
            // Вычисляем chunk origin в world-координатах.
            int chunkX = getChunkX();
            int chunkY = getChunkY();
            int chunkZ = getChunkZ();
            float originX = (float) (chunkX << 4);   // chunkX * 16
            float originY = (float) (chunkY << 4);
            float originZ = (float) (chunkZ << 4);

            // Получаем baseVertex секции в её region VBO.
            // В Sodium/Embedium RenderSection имеет RenderSectionStatus или
            //region-allocated vertex offset. Точный путь зависит от версии.
            //
            // Здесь мы используем region.getSectionBaseVertex(chunkX, chunkY, chunkZ)
            // как гипотетический API. В реальной реализации это может быть
            // ((RenderRegionAccess) region).getSectionBaseVertex(this) или подобное.
            //
            // In Sodium/Embedium, RenderSection has a RenderSectionStatus or
            // region-allocated vertex offset. The exact path depends on the version.
            //
            // Here we use region.getSectionBaseVertex(chunkX, chunkY, chunkZ)
            // as a hypothetical API. In the real implementation this might be
            // ((RenderRegionAccess) region).getSectionBaseVertex(this) or similar.
            int baseVertex = amdium$getSectionBaseVertex();

            if (baseVertex >= 0) {
                long packedPos = net.minecraft.core.SectionPos.asLong(chunkX, chunkY, chunkZ);
                PerCommandMetadata.register(baseVertex, packedPos, originX, originY, originZ);
            }
        } catch (Throwable t) {
            // Тихо логируем — interop не должен крашить игру при ошибке захвата.
            Amdium.LOGGER.debug("[Amdium] RenderSection capture failed: {}", t.getMessage());
        }
    }

    /**
     * Получает baseVertex секции в region VBO.
     * ВНИМАНИЕ: реализация зависит от конкретной версии Embedium. Здесь —
     * гипотетический путь через反射. В реальном продакшене нужно:
     *   1. Создать @Accessor интерфейс для RenderRegion (RenderRegionAccess),
     *      открывающий package-private поле allocatedVertexCount или подобное.
     *   2. Либо mixin в RenderRegion.Manager.upload() — он знает baseVertex
     *      в момент upload'а.
     *
     * / Gets the section's baseVertex in the region VBO.
     * WARNING: the implementation depends on the specific Embedium version. Here
     * is a hypothetical path via reflection. In real production:
     *   1. Create an @Accessor interface for RenderRegion (RenderRegionAccess)
     *      exposing the package-private allocatedVertexCount or similar field.
     *   2. Or mixin into RenderRegion.Manager.upload() — it knows baseVertex
     *      at upload time.
     */
    private int amdium$getSectionBaseVertex() {
        // ВАЖНО: это STUB. Реальная реализация требует:
        //   - либо @Accessor mixin в RenderRegion для приватного поля vertexOffset
        //   - либо захвата через @Redirect в момент вызова region.upload(...)
        //
        // Для PROTOTYPE-версии возвращаем -1 (culling отключится, fallback на
        // region-level culling из v2.1). Это безопасно.
        //
        //   - either an @Accessor mixin into RenderRegion for the private vertexOffset field
        //   - or capture via @Redirect at the moment region.upload(...) is called
        //
        // For the PROTOTYPE version, return -1 (culling disables, falls back to
        // the v2.1 region-level culling). This is safe.
        return -1;
    }
}

package dev.amdium.mixin;

import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.amdium.render.SectionStorageBridge;

/**
 * Mixin в Embedium's RenderRegion — устанавливает обратную ссылку
 * parentRegion в SectionRenderDataStorage.
 *
 * / Mixin into Embedium's RenderRegion — sets the parentRegion back-reference
 * in SectionRenderDataStorage.
 *
 * Когда RenderRegion.createStorage(pass) создаёт новый SectionRenderDataStorage,
 * мы сохраняем ссылку на этот RenderRegion. Это позволяет
 * SectionRenderDataStorageMixin вычислять chunk-координаты секций по
 * localSectionIndex + region origin.
 *
 * / When RenderRegion.createStorage(pass) creates a new SectionRenderDataStorage,
 * we save a reference to this RenderRegion. This enables
 * SectionRenderDataStorageMixin to compute section chunk coordinates from
 * localSectionIndex + region origin.
 */
@Mixin(value = RenderRegion.class, remap = false)
public class RenderRegionMixin {

    @Inject(
            method = "createStorage",
            at = @At("RETURN"),
            require = 0
    )
    private void amdium$onCreateStorage(TerrainRenderPass pass,
                                         CallbackInfoReturnable<SectionRenderDataStorage> cir) {
        SectionRenderDataStorage storage = cir.getReturnValue();
        if (storage != null) {
            SectionStorageBridge.setParentRegion(storage, (RenderRegion)(Object)this);
        }
    }
}
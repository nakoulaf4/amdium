package dev.amdium.mixin;

import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.amdium.Amdium;
import dev.amdium.render.ChunkMetadataStore;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Mixin в vanilla ChunkRenderDispatcher.RenderChunk — регистрация чанка
 * в ChunkMetadataStore при setOrigin.
 *
 * / Mixin into vanilla ChunkRenderDispatcher.RenderChunk — register chunk
 * in ChunkMetadataStore at setOrigin.
 *
 * ВАЖНО / IMPORTANT:
 * Этот mixin применяется ТОЛЬКО когда Embeddium НЕ установлен
 * (AmdiumMixinPlugin.shouldApplyMixin → return !hasSodiumLikeRenderer()).
 * Когда Embeddium есть — per-section metadata захватывается через
 * SectionRenderDataStorageMixin (Embeddium path).
 *
 * / This mixin is applied ONLY when Embeddium is NOT installed
 * (AmdiumMixinPlugin.shouldApplyMixin → return !hasSodiumLikeRenderer()).
 * When Embeddium is present — per-section metadata is captured via
 * SectionRenderDataStorageMixin (Embeddium path).
 *
 * Minecraft 1.20.1: setOrigin(int x, int y, int z) — дескриптор (III)V.
 * / Minecraft 1.20.1: setOrigin(int x, int y, int z) — descriptor (III)V.
 */
@Mixin(ChunkRenderDispatcher.RenderChunk.class)
public abstract class RenderChunkMixin {

    @Shadow public abstract BlockPos getOrigin();
    @Shadow @Final private Map<RenderType, VertexBuffer> buffers;

    @Inject(method = "setOrigin(III)V", at = @At("TAIL"))
    private void amdium$onSetOrigin(int x, int y, int z, CallbackInfo ci) {
        if (!Amdium.active) return;

        // SectionPos работает с section-координатами (block / 16).
        // / SectionPos operates on section coordinates (block / 16).
        long packedPos = SectionPos.asLong(
                SectionPos.blockToSectionCoord(x),
                SectionPos.blockToSectionCoord(y),
                SectionPos.blockToSectionCoord(z));

        // Origin в world-координатах (float для SSBO origins[slot].xyz).
        // / World-space origin (float for the origins[slot].xyz SSBO).
        ChunkMetadataStore.register(packedPos, (float) x, (float) y, (float) z);

        // Связываем инстансы VertexBuffer с координатами чанка.
        // / Link VertexBuffer instances to chunk coordinates.
        if (buffers != null) {
            for (Map.Entry<RenderType, VertexBuffer> entry : buffers.entrySet()) {
                int layerIndex = amdium$getLayerIndex(entry.getKey());
                if (layerIndex >= 0 && entry.getValue() != null) {
                    ChunkMetadataStore.linkBufferInstance(entry.getValue(), packedPos, layerIndex);
                }
            }
        }
    }

    @Inject(method = "reset()V", at = @At("HEAD"))
    private void amdium$onReset(CallbackInfo ci) {
        if (!Amdium.active) return;

        BlockPos pos = this.getOrigin();
        if (pos == null) return;

        long packedPos = SectionPos.asLong(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ()));

        ChunkMetadataStore.remove(packedPos);
    }

    private int amdium$getLayerIndex(RenderType type) {
        if (type == RenderType.solid()) return 0;
        if (type == RenderType.cutoutMipped()) return 1;
        if (type == RenderType.cutout()) return 2;
        if (type == RenderType.translucent()) return 3;
        return -1;
    }
}
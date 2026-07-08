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

@Mixin(ChunkRenderDispatcher.RenderChunk.class)
public abstract class RenderChunkMixin {

    // Заменяем проблемное @Shadow поле на стабильный @Shadow метод
    // Replace the problematic @Shadow field with a stable @Shadow method
    @Shadow public abstract BlockPos getOrigin();
    @Shadow @Final private Map<RenderType, VertexBuffer> buffers;

    @Inject(method = "setOrigin(III)V", at = @At("TAIL"))
    private void amdium$onSetOrigin(int x, int y, int z, CallbackInfo ci) {
        if (!Amdium.active) return;

        long packedPos = SectionPos.asLong(
                SectionPos.blockToSectionCoord(x),
                SectionPos.blockToSectionCoord(y),
                SectionPos.blockToSectionCoord(z));

        // Используем аргументы метода (x, y, z) напрямую вместо обращения к полям
        // Use the method arguments (x, y, z) directly instead of accessing fields
        ChunkMetadataStore.register(packedPos, (float) x, (float) y, (float) z);

        // Связываем инстансы VertexBuffer с координатами чанка сразу при инициализации
        // Link VertexBuffer instances to chunk coordinates right at initialization
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

        // Безопасно получаем позицию через метод
        // Safely get the position via a method
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

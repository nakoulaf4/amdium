package dev.amdium.mixin;

import dev.amdium.Amdium;
import dev.amdium.render.PerCommandMetadata;
import dev.amdium.render.SectionStorageBridge;
import me.jellysquid.mods.sodium.client.gl.arena.GlBufferSegment;
import me.jellysquid.mods.sodium.client.gl.util.VertexRange;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.render.chunk.LocalSectionIndex;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataUnsafe;
import me.jellysquid.mods.sodium.client.render.chunk.data.SectionRenderDataStorage;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import net.minecraft.core.SectionPos;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SectionRenderDataStorage.class, remap = false)
public class SectionRenderDataStorageMixin {

    @Inject(
            method = "setMeshes",
            at = @At("TAIL"),
            require = 0
    )
    private void amdium$afterSetMeshes(int localSectionIndex,
                                       GlBufferSegment allocation,
                                       @Nullable GlBufferSegment indexAllocation,
                                       VertexRange[] ranges,
                                       CallbackInfo ci) {
        if (!Amdium.embediumInteropActive) return;

        SectionRenderDataStorage self = (SectionRenderDataStorage) (Object) this;
        RenderRegion parent = SectionStorageBridge.getParentRegion(self);
        if (parent == null) return;

        long pMeshData = self.getDataPointer(localSectionIndex);
        int sliceMask = SectionRenderDataUnsafe.getSliceMask(pMeshData);
        if (sliceMask == 0) return;

        int rChunkX = parent.getChunkX();
        int rChunkY = parent.getChunkY();
        int rChunkZ = parent.getChunkZ();
        int chunkX = rChunkX + LocalSectionIndex.unpackX(localSectionIndex);
        int chunkY = rChunkY + LocalSectionIndex.unpackY(localSectionIndex);
        int chunkZ = rChunkZ + LocalSectionIndex.unpackZ(localSectionIndex);

        float originX = (float) (chunkX << 4);
        float originY = (float) (chunkY << 4);
        float originZ = (float) (chunkZ << 4);
        long packedPos = SectionPos.asLong(chunkX, chunkY, chunkZ);

        for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
            if ((sliceMask & (1 << facing)) != 0) {
                int bv = SectionRenderDataUnsafe.getVertexOffset(pMeshData, facing);
                if (bv >= 0) {
                    PerCommandMetadata.register(bv, packedPos, originX, originY, originZ);
                }
            }
        }
    }

    @Inject(
            method = "removeMeshes",
            at = @At("HEAD"),
            require = 0
    )
    private void amdium$beforeRemoveMeshes(int localSectionIndex, CallbackInfo ci) {
        if (!Amdium.embediumInteropActive) return;

        SectionRenderDataStorage self = (SectionRenderDataStorage) (Object) this;
        RenderRegion parent = SectionStorageBridge.getParentRegion(self);
        if (parent == null) return;

        int rChunkX = parent.getChunkX();
        int rChunkY = parent.getChunkY();
        int rChunkZ = parent.getChunkZ();
        int chunkX = rChunkX + LocalSectionIndex.unpackX(localSectionIndex);
        int chunkY = rChunkY + LocalSectionIndex.unpackY(localSectionIndex);
        int chunkZ = rChunkZ + LocalSectionIndex.unpackZ(localSectionIndex);

        float originX = (float) (chunkX << 4);
        float originY = (float) (chunkY << 4);
        float originZ = (float) (chunkZ << 4);

        long pMeshData = self.getDataPointer(localSectionIndex);
        int sliceMask = SectionRenderDataUnsafe.getSliceMask(pMeshData);

        for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
            if ((sliceMask & (1 << facing)) != 0) {
                int bv = SectionRenderDataUnsafe.getVertexOffset(pMeshData, facing);
                PerCommandMetadata.remove(bv, originX, originY, originZ);
            }
        }
    }

    @Inject(
            method = "onBufferResized",
            at = @At("TAIL"),
            require = 0
    )
    private void amdium$afterBufferResized(CallbackInfo ci) {
        if (!Amdium.embediumInteropActive) return;

        SectionRenderDataStorage self = (SectionRenderDataStorage) (Object) this;
        RenderRegion parent = SectionStorageBridge.getParentRegion(self);
        if (parent == null) return;

        int rChunkX = parent.getChunkX();
        int rChunkY = parent.getChunkY();
        int rChunkZ = parent.getChunkZ();

        for (int sectionIndex = 0; sectionIndex < RenderRegion.REGION_SIZE; sectionIndex++) {
            long pMeshData = self.getDataPointer(sectionIndex);
            int sliceMask = SectionRenderDataUnsafe.getSliceMask(pMeshData);
            if (sliceMask == 0) continue;

            int chunkX = rChunkX + LocalSectionIndex.unpackX(sectionIndex);
            int chunkY = rChunkY + LocalSectionIndex.unpackY(sectionIndex);
            int chunkZ = rChunkZ + LocalSectionIndex.unpackZ(sectionIndex);

            float originX = (float) (chunkX << 4);
            float originY = (float) (chunkY << 4);
            float originZ = (float) (chunkZ << 4);
            long packedPos = SectionPos.asLong(chunkX, chunkY, chunkZ);

            for (int facing = 0; facing < ModelQuadFacing.COUNT; facing++) {
                if ((sliceMask & (1 << facing)) != 0) {
                    int bv = SectionRenderDataUnsafe.getVertexOffset(pMeshData, facing);
                    if (bv >= 0) {
                        PerCommandMetadata.register(bv, packedPos, originX, originY, originZ);
                    }
                }
            }
        }
    }
}

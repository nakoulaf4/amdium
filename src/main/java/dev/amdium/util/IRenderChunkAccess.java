package dev.amdium.util;

import net.minecraft.core.BlockPos;
import com.mojang.blaze3d.vertex.VertexBuffer;

/**
 * Интерфейс-аксессор (Duck Typing) для безопасного получения
 * приватных полей из ChunkRenderDispatcher$RenderChunk.
 * Accessor interface (duck typing) for safely reading private fields
 * from ChunkRenderDispatcher$RenderChunk.
 */
public interface IRenderChunkAccess {
    BlockPos amdium$getOrigin();
    BlockPos amdium$getRenderOrigin();
    VertexBuffer[] amdium$getVertexBuffers();
}

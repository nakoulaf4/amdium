package dev.amdium.mixin;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.amdium.Amdium;
import dev.amdium.render.AmdiumRenderer;
import dev.amdium.render.ChunkMetadataStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(VertexBuffer.class)
public abstract class VertexBufferMixin {

    /**
     * Перехватываем нативную загрузку данных геометрии.
     * Intercept the native upload of geometry data.
     */
    @Inject(method = "upload(Lcom/mojang/blaze3d/vertex/BufferBuilder$RenderedBuffer;)V", at = @At("TAIL"))
    private void amdium$onUpload(BufferBuilder.RenderedBuffer data, CallbackInfo ci) {
        if (!Amdium.active) return;

        try {
            VertexBuffer vbo = (VertexBuffer) (Object) this;

            // Находим к какому чанку/слою привязан этот буфер
            // Find which chunk/layer this buffer is bound to
            ChunkMetadataStore.VboLookup lookup = ChunkMetadataStore.findByBufferInstance(vbo);
            if (lookup == null) return; // Буфер не принадлежит рендеру чанков (например, Skybox или интерфейс) / The buffer does not belong to chunk rendering (e.g. Skybox or UI)

            ByteBuffer vertexData = data.vertexBuffer();
            if (vertexData == null || !vertexData.hasRemaining()) return;
            // FIX #2: DefaultVertexFormat.BLOCK в 1.20.1 = 36 bytes (с UV1 Overlay).
            // Должно совпадать с AmdiumVertexPool.VERTEX_STRIDE.
            // DefaultVertexFormat.BLOCK in 1.20.1 = 36 bytes (incl. UV1 Overlay).
            // Must match AmdiumVertexPool.VERTEX_STRIDE.
            int vertexCount = vertexData.remaining() / dev.amdium.render.AmdiumVertexPool.VERTEX_STRIDE;

            int vanillaVboId = dev.amdium.util.ReflectionUtil.getVertexBufferId(vbo);
            if (vanillaVboId <= 0) return;

            // Заливаем в MDI пул Amdium / Upload into the Amdium MDI pool
            int slot = AmdiumRenderer.INSTANCE.uploadChunkLayer(lookup.packedPos, vanillaVboId, lookup.layerIndex, vertexData);
            if (slot < 0) return;

            // Сохраняем метаданные / Save metadata
            ChunkMetadataStore.setLayerData(lookup.packedPos, lookup.layerIndex, vanillaVboId, vertexCount, slot);

            // Отправляем origin чанка в SSBO / Send the chunk origin to the SSBO
            ChunkMetadataStore.ChunkMeta meta = ChunkMetadataStore.get(lookup.packedPos);
            if (meta != null) {
                AmdiumRenderer.INSTANCE.uploadOrigin(slot, meta.originX, meta.originY, meta.originZ);
            }

        } catch (Exception e) {
            Amdium.LOGGER.error("[Amdium] Ошибка при перехвате upload: {}", e.getMessage());
        }
    }

    /**
     * Отменяем стандартный draw call майнкрафта, если чанк обрабатывается Amdium.
     * Cancel Minecraft's standard draw call if the chunk is handled by Amdium.
     */
    @Inject(
            method = "drawWithShader(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;Lnet/minecraft/client/renderer/ShaderInstance;)V",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void amdium$onDrawChunkLayer(org.joml.Matrix4f modelViewMatrix, org.joml.Matrix4f projectionMatrix, net.minecraft.client.renderer.ShaderInstance shader, CallbackInfo ci) {
        if (!Amdium.active) return;

        int vanillaVboId = dev.amdium.util.ReflectionUtil.getVertexBufferId((VertexBuffer) (Object) this);
        if (vanillaVboId == 0) return;

        ChunkMetadataStore.VboLookup lookup = ChunkMetadataStore.findByVboId(vanillaVboId);
        if (lookup == null) return;

        // Регистрируем чанк в очереди отрисовки MDI / Register the chunk in the MDI draw queue
        AmdiumRenderer.INSTANCE.registerChunk(lookup.packedPos, lookup.layerIndex);

        // Отменяем стандартный glDrawElements / Cancel the standard glDrawElements
        ci.cancel();
    }
}

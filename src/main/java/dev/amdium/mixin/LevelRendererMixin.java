package dev.amdium.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.amdium.Amdium;
import dev.amdium.config.AmdiumConfig;
import dev.amdium.render.AmdiumRenderer;
import dev.amdium.render.EmbediumInterop;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Перехват рендеринга чанков в LevelRenderer.
 * / Interception of chunk rendering in LevelRenderer.
 *
 * В vanilla-path режиме (Amdium.active): оркеструет MDI-кадр Amdium.
 * / In vanilla-path mode (Amdium.active): orchestrates Amdium's MDI frame.
 *
 * В interop-режиме (Amdium.embediumInteropActive): только ЗАХВАТЫВАЕТ
 * projView, camera и fog для InteropComputeCuller. Сам draw не делается —
 * Embedium рисует сама, Amdium перехватывает в EmbeddiumDrawCommandListMixin.
 * / In interop mode (Amdium.embediumInteropActive): only CAPTURES projView,
 * camera and fog for InteropComputeCuller. The draw is not done here —
 * Embedium draws itself, Amdium intercepts in EmbeddiumDrawCommandListMixin.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Shadow private ClientLevel level;

    // Кэш кадра / Per-frame cache
    private final float[] amdium$projView = new float[16];
    private final float[] amdium$frustum  = new float[24];
    private float amdium$camX, amdium$camY, amdium$camZ;

    // Atlas dimensions (для UV normalize в шейдере) / Atlas dimensions (for UV normalization in the shader)
    private int amdium$atlasWidth = 256;
    private int amdium$atlasHeight = 256;

    @Inject(method = "renderLevel", at = @At("HEAD"))
    private void amdium$onRenderLevelHead(
            PoseStack poseStack,
            float partialTick,
            long finishNanoTime,
            boolean renderBlockOutline,
            Camera camera,
            GameRenderer gameRenderer,
            LightTexture lightTexture,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        // Захват кадровых данных нужен в ОБОИХ режимах:
        // - vanilla path: для AmdiumRenderer.drawLayer
        // - interop path: для InteropComputeCuller (frustum + camera + fog)
        // / Frame data capture is needed in BOTH modes:
        // - vanilla path: for AmdiumRenderer.drawLayer
        // - interop path: for InteropComputeCuller (frustum + camera + fog)
        if (!Amdium.active && !Amdium.embediumInteropActive) return;

        amdium$camX = (float) camera.getPosition().x;
        amdium$camY = (float) camera.getPosition().y;
        amdium$camZ = (float) camera.getPosition().z;

        Matrix4f view = new Matrix4f(poseStack.last().pose());
        Matrix4f pv   = new Matrix4f(projectionMatrix).mul(view);
        pv.get(amdium$projView);

        amdium$extractFrustumPlanes(pv);

        // В interop-режиме: пробрасываем projView + camera в EmbediumInterop,
        // чтобы InteropComputeCuller имел frustum + camera для GPU-culling.
        // / In interop mode: forward projView + camera to EmbediumInterop,
        // so InteropComputeCuller has frustum + camera for GPU culling.
        if (Amdium.embediumInteropActive) {
            float[] fogColor = RenderSystem.getShaderFogColor();
            if (fogColor == null) fogColor = new float[]{1f, 1f, 1f, 1f};
            float fogStart = RenderSystem.getShaderFogStart();
            float fogEnd   = RenderSystem.getShaderFogEnd();
            EmbediumInterop.setFrameData(amdium$projView,
                    amdium$camX, amdium$camY, amdium$camZ,
                    fogStart, fogEnd);
        }
    }

    /**
     * HEAD renderChunkLayer: начало кадра для этого слоя.
     * / HEAD renderChunkLayer: start of frame for this layer.
     */
    @Inject(method = "renderChunkLayer", at = @At("HEAD"))
    private void amdium$onRenderChunkLayerHead(
            net.minecraft.client.renderer.RenderType renderType,
            PoseStack poseStack,
            double camX,
            double camY,
            double camZ,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        if (!Amdium.active) return; // interop-режим не использует beginFrame

        AmdiumRenderer renderer = AmdiumRenderer.INSTANCE;
        if (!renderer.isInitialized()) return;

        renderer.beginFrame();

        try {
            net.minecraft.client.Minecraft.getInstance().getModelManager()
                    .getAtlas(TextureAtlas.LOCATION_BLOCKS);
        } catch (Exception ignored) {}
    }

    /**
     * TAIL renderChunkLayer: один MDI draw call для всего слоя.
     * / TAIL renderChunkLayer: a single MDI draw call for the whole layer.
     */
    @Inject(method = "renderChunkLayer", at = @At("RETURN"))
    private void amdium$onRenderChunkLayerTail(
            net.minecraft.client.renderer.RenderType renderType,
            PoseStack poseStack,
            double camX,
            double camY,
            double camZ,
            Matrix4f projectionMatrix,
            CallbackInfo ci
    ) {
        if (!Amdium.active) return; // interop-режим не использует drawLayer

        AmdiumRenderer renderer = AmdiumRenderer.INSTANCE;
        if (!renderer.isInitialized()) return;

        float[] fogColor = RenderSystem.getShaderFogColor();
        if (fogColor == null) fogColor = new float[]{1f, 1f, 1f, 1f};
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd   = RenderSystem.getShaderFogEnd();

        int atlasW = amdium$atlasWidth;
        int atlasH = amdium$atlasHeight;

        renderer.drawLayer(
                amdium$projView,
                amdium$camX, amdium$camY, amdium$camZ,
                amdium$frustum,
                fogColor, fogStart, fogEnd,
                atlasW, atlasH
        );

        renderer.endFrame();
    }

    /**
     * Извлечение 6 frustum planes из projView (метод Gribb & Hartmann).
     * / Extract 6 frustum planes from projView (Gribb & Hartmann method).
     */
    private void amdium$extractFrustumPlanes(Matrix4f m) {
        float[] p = amdium$frustum;
        p[0]  = m.m30() + m.m00(); p[1]  = m.m31() + m.m01();
        p[2]  = m.m32() + m.m02(); p[3]  = m.m33() + m.m03();
        p[4]  = m.m30() - m.m00(); p[5]  = m.m31() - m.m01();
        p[6]  = m.m32() - m.m02(); p[7]  = m.m33() - m.m03();
        p[8]  = m.m30() + m.m10(); p[9]  = m.m31() + m.m11();
        p[10] = m.m32() + m.m12(); p[11] = m.m33() + m.m13();
        p[12] = m.m30() - m.m10(); p[13] = m.m31() - m.m11();
        p[14] = m.m32() - m.m12(); p[15] = m.m33() - m.m13();
        p[16] = m.m30() + m.m20(); p[17] = m.m31() + m.m21();
        p[18] = m.m32() + m.m22(); p[19] = m.m33() + m.m23();
        p[20] = m.m30() - m.m20(); p[21] = m.m31() - m.m21();
        p[22] = m.m32() - m.m22(); p[23] = m.m33() - m.m23();
        for (int i = 0; i < 6; i++) {
            int b = i * 4;
            float len = (float) Math.sqrt(p[b]*p[b] + p[b+1]*p[b+1] + p[b+2]*p[b+2]);
            if (len > 1e-6f) { p[b]/=len; p[b+1]/=len; p[b+2]/=len; p[b+3]/=len; }
        }
    }
}

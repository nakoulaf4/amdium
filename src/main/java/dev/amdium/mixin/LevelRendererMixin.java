package dev.amdium.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.amdium.Amdium;
import dev.amdium.config.AmdiumConfig;
import dev.amdium.render.AmdiumRenderer;
import dev.amdium.render.EmbediumInterop;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import org.lwjgl.opengl.GL11;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Перехват рендеринга чанков в LevelRenderer (v2.3).
 * / Interception of chunk rendering in LevelRenderer (v2.3).
 *
 * ─────────────────────────────────────────────────────────────────────────
 * v2.3 ОПТИМИЗАЦИЯ ПРОИЗВОДИТЕЛЬНОСТИ / v2.3 PERFORMANCE OPTIMIZATION
 * ─────────────────────────────────────────────────────────────────────────
 *
 * v2.2 ПРОБЛЕМА / v2.2 PROBLEM:
 *   amdium$onRenderChunkLayerHead вызывал glGetTexLevelParameteri ДВАЖДЫ
 *   (GL_TEXTURE_WIDTH + GL_TEXTURE_HEIGHT) для block atlas на КАЖДЫЙ из 4
 *   слоёв КАЖДЫЙ кадр. Это синхронный CPU-GPU query — драйвер обязан
 *   flushed'нуть command queue и дождаться ответа GPU. Каждый query ~0.5-1 ms,
 *   8 queries на кадр = 4-8 ms (!). Это ОСНОВНАЯ причина просадки FPS в
 *   vanilla-path режиме.
 *   / amdium$onRenderChunkLayerHead called glGetTexLevelParameteri TWICE
 *   (GL_TEXTURE_WIDTH + GL_TEXTURE_HEIGHT) for the block atlas on EACH of the
 *   4 layers EVERY frame. This is a synchronous CPU-GPU query — the driver
 *   must flush the command queue and wait for the GPU's reply. Each query is
 *   ~0.5-1 ms, 8 queries per frame = 4-8 ms (!). This is THE main cause of
 *   FPS drop in vanilla-path mode.
 *
 *   Дополнительно: RenderSystem.setShaderTexture(0, texId) модифицировал
 *   global GL state, который потом приходилось восстанавливать.
 *   / Additionally: RenderSystem.setShaderTexture(0, texId) modified global
 *   GL state which then had to be restored.
 *
 * v2.3 ИСПРАВЛЕНИЕ / v2.3 FIX:
 *   1. Размеры block atlas кэшируются и обновляются ТОЛЬКО при смене
 *      resource pack (через hook на TextureManager.Stitcher или просто
 *      раз в 100 кадров, если stitch event не доступен). Для 1.20.1
 *      используем простой throttle: проверяем не чаще раза в секунду.
 *      / Block atlas dimensions are cached and refreshed ONLY on resource
 *      pack change (via a hook on TextureManager.Stitcher, or simply once
 *      every 100 frames if the stitch event isn't available). For 1.20.1
 *      we use a simple throttle: check no more than once per second.
 *
 *   2. RenderSystem.setShaderTexture убран — мы используем прямую
 *      GL11.glBindTexture на временно привязанной текстуре для query,
 *      затем восстанавливаем предыдущую привязку. Это безопаснее для
 *      Embedium и vanilla state.
 *      / RenderSystem.setShaderTexture removed — we use a direct
 *      GL11.glBindTexture on a temporarily bound texture for the query,
 *      then restore the previous binding. This is safer for Embedium and
 *      vanilla state.
 *
 *   3. v2.3 interop: beginFrame вызывается в HEAD renderLevel (через
 *      EmbediumInterop.beginFrame), чтобы подговить ring stream.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {

    @Shadow private ClientLevel level;

    // Кэш кадра / Per-frame cache
    private final float[] amdium$projView = new float[16];
    private final float[] amdium$view       = new float[16];
    private final float[] amdium$projection = new float[16];
    private final float[] amdium$frustum  = new float[24];
    private float amdium$camX, amdium$camY, amdium$camZ;

    // Atlas dimensions — кэшируются, обновляются редко.
    private int amdium$atlasWidth = 256;
    private int amdium$atlasHeight = 256;
    private int amdium$atlasTexId = -1;

    // v2.3: throttle для atlas query — не чаще раза в секунду.
    private long amdium$lastAtlasCheckMs = 0;
    private static final long ATLAS_CHECK_INTERVAL_MS = 1000L;

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
        if (!Amdium.active && !Amdium.embediumInteropActive) return;

        amdium$camX = (float) camera.getPosition().x;
        amdium$camY = (float) camera.getPosition().y;
        amdium$camZ = (float) camera.getPosition().z;

        Matrix4f view = new Matrix4f(poseStack.last().pose());
        Matrix4f pv   = new Matrix4f(projectionMatrix).mul(view);
        pv.get(amdium$projView);
        view.get(amdium$view);
        projectionMatrix.get(amdium$projection);

        amdium$extractFrustumPlanes(pv);

        // v2.3: beginFrame для interop ring stream.
        if (Amdium.embediumInteropActive) {
            float[] fogColor = RenderSystem.getShaderFogColor();
            if (fogColor == null) fogColor = new float[]{1f, 1f, 1f, 1f};
            float fogStart = RenderSystem.getShaderFogStart();
            float fogEnd   = RenderSystem.getShaderFogEnd();
            EmbediumInterop.setFrameData(amdium$projView, amdium$view, amdium$projection,
                    amdium$camX, amdium$camY, amdium$camZ,
                    fogStart, fogEnd);
            EmbediumInterop.beginFrame();
        }
    }

    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void amdium$onRenderLevelTail(
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
        if (!Amdium.embediumInteropActive) return;
        EmbediumInterop.endFrame();
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

        // v2.3: обновляем размеры block atlas ТОЛЬКО раз в секунду (не каждый кадр!).
        long now = System.currentTimeMillis();
        if (now - amdium$lastAtlasCheckMs > ATLAS_CHECK_INTERVAL_MS) {
            amdium$lastAtlasCheckMs = now;
            amdium$refreshAtlasSize();
        }
    }

    /**
     * v2.3: запрос размеров atlas с минимальным state pollution.
     * / v2.3: query atlas dimensions with minimal state pollution.
     */
    private void amdium$refreshAtlasSize() {
        try {
            TextureAtlas atlas = Minecraft.getInstance().getModelManager()
                    .getAtlas(TextureAtlas.LOCATION_BLOCKS);
            int texId = atlas.getId();
            if (texId <= 0) return;

            // Если ID текстуры не менялся И размеры уже валидны — пропускаем query.
            // valid — skip the query.
            if (texId == amdium$atlasTexId
                    && amdium$atlasWidth > 0
                    && amdium$atlasHeight > 0) {
                return;
            }
            amdium$atlasTexId = texId;

            // Сохраняем предыдущую привязку texture unit 0, делаем query, восстанавливаем.
            int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            amdium$atlasWidth  = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            amdium$atlasHeight = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        } catch (Throwable ignored) {
            // Если что-то пошло не так — оставляем предыдущее значение.
        }
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
        if (!Amdium.active) return;

        AmdiumRenderer renderer = AmdiumRenderer.INSTANCE;
        if (!renderer.isInitialized()) return;

        float[] fogColor = RenderSystem.getShaderFogColor();
        if (fogColor == null) fogColor = new float[]{1f, 1f, 1f, 1f};
        float fogStart = RenderSystem.getShaderFogStart();
        float fogEnd   = RenderSystem.getShaderFogEnd();

        renderer.drawLayer(
                amdium$projView,
                amdium$camX, amdium$camY, amdium$camZ,
                amdium$frustum,
                fogColor, fogStart, fogEnd,
                amdium$atlasWidth, amdium$atlasHeight
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

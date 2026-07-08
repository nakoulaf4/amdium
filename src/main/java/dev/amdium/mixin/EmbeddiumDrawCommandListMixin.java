package dev.amdium.mixin;

import dev.amdium.Amdium;
import dev.amdium.render.EmbediumInterop;
import me.jellysquid.mods.sodium.client.gl.device.MultiDrawBatch;
import me.jellysquid.mods.sodium.client.gl.tessellation.GlIndexType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin в Embeddium's ImmediateDrawCommandList — перехват multiDrawElementsBaseVertex.
 * / Mixin into Embeddium's ImmediateDrawCommandList — intercept multiDrawElementsBaseVertex.
 *
 * ВАЖНО (почему это работает) / IMPORTANT (why this works):
 *
 * Раньше (v2.0) параметры метода объявлялись как Object, чтобы не ссылаться на классы
 * Embeddium в compile-time. Но Mixin сопоставляет методы по ДЕСКРИПТОРУ (имя + типы
 * параметров), а не только по имени. Дескриптор (Ljava/lang/Object;Ljava/lang/Object;)V
 * НЕ совпадает с целевым (Lme/jellysquid/.../MultiDrawBatch;Lme/jellysquid/.../GlIndexType;)V.
 * С require=0 инъекция молча проваливалась — interop НИКОГДА не перехватывал draw-вызовы.
 * / Previously (v2.0) the method parameters were declared as Object, to avoid referencing
 * Embeddium classes at compile time. But Mixin matches methods by DESCRIPTOR (name +
 * parameter types), not just by name. The descriptor (Ljava/lang/Object;Ljava/lang/Object;)V
 * does NOT match the target (Lme/jellysquid/.../MultiDrawBatch;Lme/jellysquid/.../GlIndexType;)V.
 * With require=0 the injection silently failed — the interop NEVER intercepted draw calls.
 *
 * Теперь параметры — настоящие типы Embeddium (MultiDrawBatch, GlIndexType). Это работает
 * потому, что AmdiumMixinPlugin применяет этот mixin ТОЛЬКО когда Embeddium установлен
 * (см. AmdiumMixinPlugin.shouldApplyMixin). Если Embeddium нет — класс mixin'а вообще
 * не загружается, и NoClassDefFoundError не возникает.
 * / Now the parameters are the real Embeddium types (MultiDrawBatch, GlIndexType). This works
 * because AmdiumMixinPlugin applies this mixin ONLY when Embeddium is installed
 * (see AmdiumMixinPlugin.shouldApplyMixin). If Embeddium is absent, the mixin class is never
 * loaded at all, so no NoClassDefFoundError can occur.
 *
 * Embeddium — compileOnly-зависимость (см. build.gradle), поэтому классы доступны
 * при компиляции. Поля MultiDrawBatch (pElementPointer, pElementCount, pBaseVertex, size)
 * публичные — обращаемся напрямую, без reflection (быстрее и типобезопасно).
 * / Embeddium is a compileOnly dependency (see build.gradle), so the classes are available
 * at compile time. The MultiDrawBatch fields (pElementPointer, pElementCount, pBaseVertex,
 * size) are public — we access them directly, without reflection (faster and type-safe).
 *
 * Логика:
 * / Logic:
 * Если Amdium активен в interop-режиме → конвертируем MultiDrawBatch в
 * GL_DRAW_INDIRECT_BUFFER, вызываем glMultiDrawElementsIndirect, cancel().
 * / If Amdium is active in interop mode → convert MultiDrawBatch into
 * GL_DRAW_INDIRECT_BUFFER, call glMultiDrawElementsIndirect, cancel().
 * Иначе — пропускаем, Embeddium рисует сама.
 * / Otherwise — skip, Embeddium draws itself.
 */
@Mixin(targets = "me.jellysquid.mods.sodium.client.gl.device.GlRenderDevice$ImmediateDrawCommandList", remap = false)
public class EmbeddiumDrawCommandListMixin {

    /**
     * Перехват multiDrawElementsBaseVertex.
     * / Intercept multiDrawElementsBaseVertex.
     *
     * Сигнатура целевого метода (Embeddium):
     * / Target method signature (Embeddium):
     *   void multiDrawElementsBaseVertex(MultiDrawBatch batch, GlIndexType indexType)
     *
     * Типы параметров совпадают ТОЧНО — Mixin найдёт метод по дескриптору.
     * / Parameter types match EXACTLY — Mixin finds the method by descriptor.
     */
    @Inject(
            method = "multiDrawElementsBaseVertex",
            at = @At("HEAD"),
            cancellable = true,
            require = 0
    )
    private void amdium$onMultiDrawBaseVertex(MultiDrawBatch batch, GlIndexType indexType, CallbackInfo ci) {
        // Только если Amdium в interop-режиме.
        // / Only if Amdium is in interop mode.
        if (!Amdium.embediumInteropActive || !EmbediumInterop.isInitialized()) {
            return; // пропускаем — Embeddium рисует сама / skip — Embeddium draws itself
        }
        if (batch == null || batch.isEmpty()) {
            return;
        }

        try {
            // Поля MultiDrawBatch публичные — прямой доступ, без reflection.
            // / MultiDrawBatch fields are public — direct access, no reflection.
            int size = batch.size;
            if (size <= 0) return;

            // GlIndexType.getStride() возвращает размер индекса в байтах (1/2/4).
            // / GlIndexType.getStride() returns the index size in bytes (1/2/4).
            int indexTypeSize = indexType.getStride();

            // Перехватываем — конвертируем в MDI.
            // / Intercept — convert to MDI.
            EmbediumInterop.interceptMultiDraw(
                    batch.pElementPointer,
                    batch.pElementCount,
                    batch.pBaseVertex,
                    size,
                    indexTypeSize,
                    indexType.getFormatId()
            );

            // Отменяем оригинальный nglMultiDrawElementsBaseVertex.
            // / Cancel the original nglMultiDrawElementsBaseVertex.
            ci.cancel();

        } catch (Throwable e) {
            // Если что-то пошло не так — fallback на Embeddium (не cancel).
            // / If something went wrong — fallback to Embeddium (do not cancel).
            Amdium.LOGGER.warn("[Amdium] Embeddium interop error: {}. Fallback на Embeddium.",
                    e.getMessage());
            // / [Amdium] Embeddium interop error: {}. Fallback to Embeddium.
        }
    }
}

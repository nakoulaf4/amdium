package dev.amdium.mixin;

import dev.amdium.Amdium;
import dev.amdium.render.PerCommandMetadata;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.amdium.render.SectionStorageBridge;

/**
 * Mixin в Minecraft ТОЛЬКО для cleanup при закрытии.
 * Mixin into Minecraft ONLY for cleanup on shutdown.
 *
 * ВАЖНО: инициализация GPU перенесена в Amdium.clientSetup(FMLClientSetupEvent),
 *         потому что чтение ForgeConfigSpec в <init> RETURN бросает
 *         IllegalStateException "Cannot get config value before config is loaded".
 * IMPORTANT: GPU initialization has been moved to Amdium.clientSetup(FMLClientSetupEvent),
 *            because reading ForgeConfigSpec at <init> RETURN throws
 *            IllegalStateException "Cannot get config value before config is loaded".
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    /**
     * Cleanup при закрытии Minecraft.
     * В 1.20.1 метод close() существует в Minecraft (implements AutoCloseable).
     * Cleanup on Minecraft shutdown.
     * In 1.20.1 the close() method exists in Minecraft (implements AutoCloseable).
     */
    @Inject(method = "close", at = @At("HEAD"), require = 0)
    private void amdium$onClose(CallbackInfo ci) {
        try {
            // v2.2: Очистка per-command метаданных и parent region мапы.
            // Перекрываем NoClassDefFoundError — если Embeddium не установлен,
            // класс SectionRenderDataStorageMixin не загружен.
            // / v2.2: Cleanup per-command metadata and parent region map.
            // Catch NoClassDefFoundError — if Embeddium is not installed,
            // SectionRenderDataStorageMixin class is not loaded.
            PerCommandMetadata.clear();
            try {
                dev.amdium.render.SectionStorageBridge.clearParentRegions();
            } catch (NoClassDefFoundError ignored) {
                // Embeddium не установлен — нечего чистить.
                // / Embeddium not installed — nothing to clean.
            }
            Amdium.shutdown();
        } catch (Exception e) {
            Amdium.LOGGER.warn("[Amdium] Ошибка shutdown: {}", e.getMessage());
            // / [Amdium] Shutdown error
        }
    }
}

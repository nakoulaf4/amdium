package dev.amdium.mixin;

import dev.amdium.Amdium;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
            Amdium.shutdown();
        } catch (Exception e) {
            Amdium.LOGGER.warn("[Amdium] Ошибка shutdown: {}", e.getMessage());
            // / [Amdium] Shutdown error
        }
    }
}

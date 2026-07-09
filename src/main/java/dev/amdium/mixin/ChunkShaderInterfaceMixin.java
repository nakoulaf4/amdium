package dev.amdium.mixin;

import dev.amdium.render.InteropComputeCuller;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin в Embeddium's ChunkShaderInterface — захват region offset.
 * / Mixin into Embeddium's ChunkShaderInterface — capture region offset.
 *
 * Embeddium вызывает setRegionOffset(x, y, z) ОДИН РАЗ на каждый регион
 * прямо перед executeDrawBatch → multiDrawElementsBaseVertex. Значения (x,y,z) —
 * это region origin относительно камеры: absolute = camera + (x,y,z).
 * / Embeddium calls setRegionOffset(x, y, z) ONCE per region, right before
 * executeDrawBatch → multiDrawElementsBaseVertex. The (x,y,z) values are the
 * region origin relative to the camera: absolute = camera + (x,y,z).
 *
 * Захват позволяет Amdium вычислить region AABB = [origin, origin + (128,64,128)]
 * и передать его в compute-шейдер для frustum + fog куллинга.
 * / The capture lets Amdium compute the region AABB = [origin, origin + (128,64,128)]
 * and pass it to the compute shader for frustum + fog culling.
 *
 * Класс ChunkShaderInterface — public (доступен на compileOnly-classpath), поэтому
 * используем value = ChunkShaderInterface.class вместо строковой формы targets.
 * Это позволяет Mixin AP верифицировать цель и сигнатуры методов на этапе компиляции.
 * / The ChunkShaderInterface class is public (available on the compileOnly classpath),
 * so we use value = ChunkShaderInterface.class instead of the string targets form.
 * This lets the Mixin AP verify the target and method signatures at compile time.
 *
 * Mixin применяется только при наличии Embeddium (AmdiumMixinPlugin).
 * / The mixin is applied only when Embeddium is present (AmdiumMixinPlugin).
 */
@Mixin(value = ChunkShaderInterface.class, remap = false)
public class ChunkShaderInterfaceMixin {

    @Inject(
            method = "setRegionOffset(FFF)V",
            at = @At("HEAD"),
            require = 0
    )
    private void amdium$captureRegionOffset(float x, float y, float z, CallbackInfo ci) {
        // Пробрасываем в culler. Если culler не активен — вызов дешёвый (одно присваивание).
        // / Forward to the culler. If the culler is not active, the call is cheap (one assignment).
        InteropComputeCuller.onRegionOffset(x, y, z);
    }
}

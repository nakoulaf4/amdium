package dev.amdium.mixin;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;
import org.objectweb.asm.tree.ClassNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin-плагин Amdium: условное применение mixin'ов в зависимости от того,
 какой рендерер чанков установлен.
 * / Amdium mixin plugin: conditional application of mixins depending on which
 * chunk renderer is installed.
 *
 * Зачем это нужно / Why this is needed:
 *
 * 1. EmbeddiumDrawCommandListMixin ссылается на классы Embeddium
 * (MultiDrawBatch, GlIndexType) напрямую. Если Embeddium не установлен, попытка
 * загрузить этот mixin-класс бросит NoClassDefFoundError. Плагин НЕ применяет
 * этот mixin, когда Embeddium нет — класс просто не загружается.
 * / EmbeddiumDrawCommandListMixin references Embeddium classes
 * (MultiDrawBatch, GlIndexType) directly. If Embeddium is not installed, loading
 * this mixin class would throw NoClassDefFoundError. The plugin does NOT apply
 * this mixin when Embeddium is absent — the class is simply never loaded.
 *
 * 2. Vanilla mixin'ы (LevelRendererMixin, VertexBufferMixin, RenderChunkMixin)
 * нужны ТОЛЬКО в режиме полного vanilla path (когда Embeddium НЕ установлен).
 * Когда Embeddium есть — Embeddium сам заменяет chunk renderer, и наши mixin'ы
 * на vanilla-классы бесполезны (они всё равно проверяют Amdium.active и выходят).
 * Плагин пропускает их, чтобы не плодить лишний bytecode и не рисковать
 * конфликтами точек инъекции с mixin'ами Embeddium.
 * / Vanilla mixins (LevelRendererMixin, VertexBufferMixin, RenderChunkMixin)
 * are only needed in full-vanilla-path mode (when Embeddium is NOT installed).
 * When Embeddium is present, Embeddium itself replaces the chunk renderer, and
 * our mixins on vanilla classes are useless (they check Amdium.active and bail
 * anyway). The plugin skips them to avoid extra bytecode and reduce the risk of
 * injection-point conflicts with Embeddium's own mixins.
 *
 * 3. MinecraftMixin (cleanup на shutdown) и EmbeddiumDrawCommandListMixin применяются
 * по условию наличия Embeddium.
 * / MinecraftMixin (cleanup on shutdown) is always applied;
 * EmbeddiumDrawCommandListMixin is applied only when Embeddium is present.
 */
public class AmdiumMixinPlugin implements IMixinConfigPlugin {

    private static final Logger LOGGER = LoggerFactory.getLogger("Amdium-MixinPlugin");

    /** Имя mixin-конфига, которое обрабатывает этот плагин. / The mixin config name this plugin handles. */
    private static final String CONFIG_NAME = "amdium.mixins.json";

    /** Кэшированный флаг: установлен ли Embeddium. / Cached flag: is Embeddium installed. */
    private static Boolean embeddiumPresent;
    /** Кэшированный флаг: установлен ли Rubidium. / Cached flag: is Rubidium installed. */
    private static Boolean rubidiumPresent;

    @Override
    public void onLoad(String mixinConfig) {
        // Проверяем наличие Embedium/Rubidium ОДИН раз при загрузке конфига.
        // ModList может быть ещё не готов на самом раннем этапе, поэтому защищаемся.
        // ModList might not be ready at the very early stage, so guard against that.
        try {
            boolean emb = FMLLoader.getLoadingModList().getModFileById("embeddium") != null;
            boolean rub = FMLLoader.getLoadingModList().getModFileById("rubidium") != null;
            embeddiumPresent = emb;
            rubidiumPresent = rub;
            LOGGER.info("[Amdium] MixinPlugin onLoad: embeddium={}, rubidium={}", emb, rub);
        } catch (Throwable t) {
            // ModList ещё не инициализирован — считаем, что ничего нет.
            // На стадии applyMixins флаг будет пересчитан.
            // The flag will be recomputed at shouldApplyMixin time.
            embeddiumPresent = null;
            rubidiumPresent = null;
        }
    }

    /**
     * Возвращает true, если установлен Embedium ИЛИ Rubidium (оба заменяют vanilla renderer).
     * / Returns true if Embedium OR Rubidium is installed (both replace the vanilla renderer).
     */
    private boolean hasSodiumLikeRenderer() {
        if (embeddiumPresent == null || rubidiumPresent == null) {
            try {
                embeddiumPresent = FMLLoader.getLoadingModList().getModFileById("embeddium") != null;
                rubidiumPresent = FMLLoader.getLoadingModList().getModFileById("rubidium") != null;
            } catch (Throwable t) {
                return false;
            }
        }
        return embeddiumPresent || rubidiumPresent;
    }

    @Override
    public String getRefMapperConfig() {
        return null; // используем default refmap / use default refmap
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Embeddium-специфичные mixin'ы — только если Embeddium/Rubidium установлен.
        //
        // v2.2 FIX: добавлены SectionRenderDataStorageMixin и RenderRegionMixin —
        // они тоже ссылаются на классы Embeddium (SectionRenderDataStorage, RenderRegion,
        // SectionRenderDataUnsafe, ModelQuadFacing, LocalSectionIndex).
        // they also reference Embeddium classes (SectionRenderDataStorage, RenderRegion,
        // SectionRenderDataUnsafe, ModelQuadFacing, LocalSectionIndex).
        if (mixinClassName.endsWith("EmbeddiumDrawCommandListMixin")
                || mixinClassName.endsWith("ChunkShaderInterfaceMixin")
                || mixinClassName.endsWith("RenderSectionMixin")
                || mixinClassName.endsWith("SectionRenderDataStorageMixin")
                || mixinClassName.endsWith("RenderRegionMixin")) {
            boolean apply = hasSodiumLikeRenderer();
            if (!apply) {
                LOGGER.info("[Amdium] Пропускаю {} — Embeddium/Rubidium не обнаружен.",
                        mixinClassName);
            } else {
                LOGGER.info("[Amdium] Применяю {} — обнаружен Embeddium/Rubidium.",
                        mixinClassName);
            }
            return apply;
        }

        // LevelRendererMixin нужен в ОБОИХ режимах:
        //  - vanilla path: оркеструет MDI-кадр Amdium (beginFrame/drawLayer/endFrame)
        //  - interop path: ЗАХВАТЫВАЕТ projView + camera + fog для InteropComputeCuller
        //    (draw не делается — Embeddium рисует сама, перехват в EmbeddiumDrawCommandListMixin)
        //  - vanilla path: orchestrates Amdium's MDI frame
        //  - interop path: CAPTURES projView + camera + fog for InteropComputeCuller
        //    (no draw here — Embeddium draws itself, interception in EmbeddiumDrawCommandListMixin)
        if (mixinClassName.endsWith("LevelRendererMixin")) {
            return true;
        }

        // Чисто vanilla-path mixin'ы (VertexBuffer, RenderChunk) — только если
        // Embeddium/Rubidium НЕ установлен (иначе они конфликтуют с Embeddium).
        // Embeddium/Rubidium is NOT installed (otherwise they conflict with Embeddium).
        if (mixinClassName.endsWith("VertexBufferMixin")
                || mixinClassName.endsWith("RenderChunkMixin")) {
            boolean apply = !hasSodiumLikeRenderer();
            if (!apply) {
                LOGGER.info("[Amdium] Пропускаю {} — обнаружен Embeddium/Rubidium, "
                        + "vanilla path не нужен.", mixinClassName);
            }
            return apply;
        }

        // Остальные mixin'ы (например, MinecraftMixin для cleanup) — применяем всегда.
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
        // no-op
    }

    @Override
    public List<String> getMixins() {
        // Возвращаем null — все mixin'ы берутся из json-конфига.
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // no-op
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        // no-op
    }
}
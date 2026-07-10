package dev.amdium;

import com.mojang.logging.LogUtils;
import dev.amdium.config.AmdiumConfig;
import dev.amdium.render.AmdiumRenderer;
import dev.amdium.render.EmbediumInterop;
import dev.amdium.util.GPUCapabilityDetector;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Точка входа мода Amdium.
 * / Amdium mod entry point.
 *
 * ВАЖНО / IMPORTANT:
 * Конструктор НЕ принимает FMLJavaModLoadingContext. В Forge 1.20.1 (47.x) в userdev-окружении
 * инъекция контекста в конструктор ненадёжна — FMLModContainer может не найти конструктор
 * (NoSuchMethodException: dev.amdium.Amdium.<init>()), что и происходило в v2.0.
 * / The constructor does NOT accept FMLJavaModLoadingContext. In Forge 1.20.1 (47.x) under the
 * userdev environment, context injection into the constructor is unreliable — FMLModContainer
 * may fail to find the constructor (NoSuchMethodException: dev.amdium.Amdium.<init>()), which is
 * exactly what happened in v2.0.
 *
 * Надёжный паттерн: конструктор без аргументов + FMLJavaModLoadingContext.get().
 * / Reliable pattern: no-arg constructor + FMLJavaModLoadingContext.get().
 */
@Mod(Amdium.MOD_ID)
public class Amdium {

    public static final String MOD_ID = "amdium";
    public static final Logger LOGGER = LogUtils.getLogger();

    // Флаги поддержки возможностей GPU / GPU capability support flags
    public static boolean active = false;
    public static boolean supportsMDI = false;
    public static boolean supportsCompute = false;
    public static boolean supportsPersistentMapping = false;
    public static boolean supportsIndirectParameters = false;

    // Флаг совместимости с Embedium / Embedium compatibility flag
    // Если true — Embedium заменяет vanilla chunk renderer, Amdium работает как
    // MDI-ускоритель поверх Embedium (перехватывает её draw-вызовы).
    // / If true — Embedium replaces the vanilla chunk renderer, Amdium works as an
    // MDI accelerator on top of Embedium (intercepts its draw calls).
    public static boolean embediumDetected = false;
    public static boolean rubidiumDetected = false;

    // Активный режим interop с Embedium (true = перехват draw, false = vanilla path)
    // / Active interop mode with Embedium (true = intercept draws, false = vanilla path)
    public static boolean embediumInteropActive = false;

    /**
     * Конструктор без аргументов — единственный надёжный вариант для Forge 1.20.1.
     * / No-arg constructor — the only reliable option for Forge 1.20.1.
     */
    public Amdium() {
        FMLJavaModLoadingContext context = FMLJavaModLoadingContext.get();
        context.getModEventBus().addListener(this::clientSetup);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, AmdiumConfig.SPEC);

        // Регистрируем Config-экран: кнопка "Config" в меню Mods.
        // / Register a Config screen: the "Config" button in the Mods menu.
        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) ->
                        new AmdiumConfigScreen(parent)));

        // Детект Embedium / Rubidium на ранней стадии.
        // / Detect Embedium / Rubidium at an early stage.
        embediumDetected = ModList.get().isLoaded("embeddium");
        rubidiumDetected = ModList.get().isLoaded("rubidium");
        if (embediumDetected || rubidiumDetected) {
            String compat = embediumDetected ? "Embedium" : "Rubidium";
            LOGGER.info("[Amdium] Обнаружен {}. Amdium активируется в режиме MDI-ускорителя "
                    + "(перехватывает draw-вызовы и заменяет на Multi-Draw Indirect).", compat);
            LOGGER.info("[Amdium] {} detected. Amdium activates in MDI-accelerator mode "
                    + "(intercepts draw calls and replaces them with Multi-Draw Indirect).", compat);
        } else {
            LOGGER.info("[Amdium] Мод загружен. Инициализация GPU произойдёт в FMLClientSetupEvent.");
            // / [Amdium] Mod loaded. GPU initialization will happen in FMLClientSetupEvent.
        }
    }

    /**
     * Вызывается Forge после загрузки конфига и создания OpenGL-контекста.
     * / Called by Forge after the config is loaded and the OpenGL context is created.
     */
    public void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                Amdium.initGPU();
            } catch (Exception e) {
                Amdium.LOGGER.error("[Amdium] Ошибка инициализации GPU: {}", e.getMessage(), e);
                // / [Amdium] GPU initialization error
            }
        });
    }

    /**
     * Инициализация GPU: детекция возможностей + создание рендерера.
     * / GPU initialization: capability detection + renderer creation.
     *
     * Два пути:
     * / Two paths:
     *   1. Embedium/Rubidium установлен → инициализируем ТОЛЬКО EmbediumInterop
     *      (vanilla path НЕ активируется, чтобы не конфликтовать с Embedium)
     *      / Embedium/Rubidium installed → initialize ONLY EmbediumInterop
     *      (vanilla path is NOT activated, to avoid conflict with Embedium)
     *   2. Embedium НЕ установлен → полный vanilla path (свои mixin'ы на vanilla-классы)
     *      / Embedium NOT installed → full vanilla path (own mixins on vanilla classes)
     */
    public static void initGPU() {
        GPUCapabilityDetector detector = new GPUCapabilityDetector();

        if (!detector.isAMD()) {
            LOGGER.warn("[Amdium] Обнаружена не AMD видеокарта ({}). Мод будет работать, "
                    + "но оптимизации могут быть менее эффективны.", detector.getRendererName());
            // / [Amdium] Non-AMD GPU detected ({}). The mod will work, but optimizations may be less effective.
        } else {
            LOGGER.info("[Amdium] AMD GPU: {} ({})", detector.getRendererName(), detector.detectArchitecture());
        }

        LOGGER.info("[Amdium] Проверка возможностей OpenGL...");
        supportsMDI                = detector.supportsMDI();
        supportsCompute            = detector.supportsComputeShaders();
        supportsPersistentMapping  = detector.supportsPersistentMapping();
        supportsIndirectParameters = detector.supportsIndirectParameters();

        LOGGER.info("[Amdium]   Multi-Draw Indirect (MDI):           {}", supportsMDI);
        LOGGER.info("[Amdium]   Compute Shaders (GL 4.3+):          {}", supportsCompute);
        LOGGER.info("[Amdium]   Persistent Mapping (ARB_buffer_storage): {}", supportsPersistentMapping);
        LOGGER.info("[Amdium]   Indirect Parameters (ARB_indirect_parameters): {}", supportsIndirectParameters);

        if (!supportsMDI) {
            LOGGER.error("[Amdium] MDI не поддерживается — видеокарта слишком старая. Мод отключён.");
            // / [Amdium] MDI not supported — GPU too old. Mod disabled.
            return;
        }

        try {
            if (embediumDetected || rubidiumDetected) {
                // === Режим interop с Embedium ===
                // / === Interop mode with Embedium ===
                // Активируем ТОЛЬКО interop, НЕ активируем vanilla path
                // / Activate ONLY interop, DO NOT activate vanilla path
                EmbediumInterop.init(supportsIndirectParameters);
                embediumInteropActive = true;
                // active остаётся false — vanilla mixin'ы НЕ сработают
                // / active stays false — vanilla mixins will NOT trigger
                LOGGER.info("[Amdium] Embedium interop активирован. Draw-вызовы перехватываются → MDI.");
                // / [Amdium] Embedium interop activated. Draw calls intercepted → MDI.
            } else {
                // === Полный vanilla path ===
                // / === Full vanilla path ===
                active = true;
                AmdiumRenderer.INSTANCE.init(
                        supportsCompute,
                        supportsPersistentMapping,
                        supportsIndirectParameters);

                if (!AmdiumRenderer.INSTANCE.isInitialized()) {
                    LOGGER.error("[Amdium] Рендерер не смог инициализироваться. Мод отключён.");
                    active = false;
                    return;
                }

                LOGGER.info("[Amdium] Рендерер активирован. Режим: {}",
                        supportsCompute ? "MDI + GPU-Culling" : "MDI + CPU-Culling");
            }
        } catch (Exception e) {
            LOGGER.error("[Amdium] Фатальная ошибка инициализации: {}", e.getMessage(), e);
            active = false;
            embediumInteropActive = false;
        }
    }

    /**
     * Вызывается при закрытии мира / клиента.
     * / Called when the world / client is shutting down.
     */
    public static void shutdown() {
        if (embediumInteropActive) {
            EmbediumInterop.destroy();
            embediumInteropActive = false;
        }
        if (active) {
            AmdiumRenderer.INSTANCE.destroy();
            active = false;
        }
    }
}
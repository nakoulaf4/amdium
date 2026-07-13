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
 * Конструктор без аргументов — надёжный паттерн для Forge 1.20.1 (47.x),
 * т.к. инъекция контекста в конструктор ненадёжна в userdev-окружении.
 */
@Mod(Amdium.MOD_ID)
public class Amdium {

    public static final String MOD_ID = "amdium";
    public static final Logger LOGGER = LogUtils.getLogger();

    // Флаги поддержки возможностей GPU
    public static boolean active = false;
    public static boolean supportsMDI = false;
    public static boolean supportsCompute = false;
    public static boolean supportsPersistentMapping = false;
    public static boolean supportsIndirectParameters = false;

    // Флаг совместимости с Embedium/Rubidium
    public static boolean embediumDetected = false;
    public static boolean rubidiumDetected = false;

    // Активный режим interop (true = перехват draw, false = vanilla path)
    public static boolean embediumInteropActive = false;

    // APU-флаг + capped vertexPoolMB (вычисляется в initGPU)
    public static boolean isAPU = false;
    public static int effectiveVertexPoolMB = 1024;

    public Amdium() {
        FMLJavaModLoadingContext context = FMLJavaModLoadingContext.get();
        context.getModEventBus().addListener(this::clientSetup);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, AmdiumConfig.SPEC);

        ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) ->
                        new AmdiumConfigScreen(parent)));

        embediumDetected = ModList.get().isLoaded("embeddium");
        rubidiumDetected = ModList.get().isLoaded("rubidium");
        if (embediumDetected || rubidiumDetected) {
            String compat = embediumDetected ? "Embedium" : "Rubidium";
            LOGGER.info("[Amdium] Обнаружен {}. Amdium активируется в режиме MDI-ускорителя "
                    + "(перехватывает draw-вызовы и заменяет на Multi-Draw Indirect).", compat);
        } else {
            LOGGER.info("[Amdium] Мод загружен. Инициализация GPU произойдёт в FMLClientSetupEvent.");
        }
    }

    public void clientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            try {
                Amdium.initGPU();
            } catch (Exception e) {
                Amdium.LOGGER.error("[Amdium] Ошибка инициализации GPU: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * Инициализация GPU: детекция возможностей + создание рендерера.
     * Embedium/Rubidium → только EmbediumInterop; иначе — полный vanilla path.
     */
    public static void initGPU() {
        GPUCapabilityDetector detector = new GPUCapabilityDetector();

        if (!detector.isAMD()) {
            LOGGER.warn("[Amdium] Обнаружена не AMD видеокарта ({}). Мод будет работать, "
                    + "но оптимизации могут быть менее эффективны.", detector.getRendererName());
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

        isAPU = detector.isAPU();
        effectiveVertexPoolMB = detector.getVertexPoolMBCap(AmdiumConfig.MDI_VERTEX_POOL_MB.get());
        LOGGER.info("[Amdium]   APU (shared memory):                 {}", isAPU);
        LOGGER.info("[Amdium]   Effective vertexPoolMB:              {}", effectiveVertexPoolMB);

        if (!supportsMDI) {
            LOGGER.error("[Amdium] MDI не поддерживается — видеокарта слишком старая. Мод отключён.");
            return;
        }

        try {
            if (embediumDetected || rubidiumDetected) {
                EmbediumInterop.init(supportsIndirectParameters);
                embediumInteropActive = true;
                // active остаётся false — vanilla mixin'ы НЕ сработают
                LOGGER.info("[Amdium] Embedium interop активирован. Draw-вызовы перехватываются → MDI.");
            } else {
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
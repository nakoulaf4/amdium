package dev.amdium.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class AmdiumConfig {

    public static final ForgeConfigSpec SPEC;

    // --- MDI ---
    public static final ForgeConfigSpec.BooleanValue ENABLE_MDI;
    public static final ForgeConfigSpec.IntValue MDI_VERTEX_POOL_MB;

    // --- Compute Culling ---
    public static final ForgeConfigSpec.BooleanValue ENABLE_COMPUTE_CULLING;
    public static final ForgeConfigSpec.IntValue CULLING_WORKGROUP_SIZE;

    // --- Indirect Count ---
    public static final ForgeConfigSpec.BooleanValue ENABLE_INDIRECT_COUNT;

    // --- Interop compute culling (Embeddium path) ---
    public static final ForgeConfigSpec.BooleanValue ENABLE_INTEROP_COMPUTE_CULLING;
    public static final ForgeConfigSpec.BooleanValue ENABLE_INTEROP_FRUSTUM;
    public static final ForgeConfigSpec.BooleanValue ENABLE_INTEROP_FOG;
    public static final ForgeConfigSpec.BooleanValue ENABLE_HIZ_OCCLUSION;
    public static final ForgeConfigSpec.IntValue INTEROP_WORKGROUP_SIZE;

    // --- Persistent Mapping ---
    public static final ForgeConfigSpec.BooleanValue ENABLE_PERSISTENT_MAPPING;

    // --- Frame pacing ---
    public static final ForgeConfigSpec.IntValue FRAMES_IN_FLIGHT;

    // --- Debug ---
    public static final ForgeConfigSpec.BooleanValue DEBUG_SHOW_CULLED;
    public static final ForgeConfigSpec.BooleanValue DEBUG_LOG_FRAME_STATS;
    // полностью отменять vanilla render
    public static final ForgeConfigSpec.BooleanValue DISABLE_VANILLA_LAYER;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.comment("=== Amdium — AMD Renderer Config ===").push("general");

        builder.comment("Настройки Multi-Draw Indirect").push("mdi");
        ENABLE_MDI = builder
                .comment("Включить MDI рендеринг. Объединяет все draw-вызовы чанков в один.")
                .define("enableMDI", true);
        MDI_VERTEX_POOL_MB = builder
                .comment("Размер глобального vertex pool в мегабайтах.",
                         "32K verts на чанк × 36 байт = ~1.1 MB на чанк.",
                         "512 чанков для RD 12, 1024 чанков для RD 16, 4096 для RD 32.",
                         "Рекомендуется 512 MB для RD 12, 1024 MB для RD 16, 4096 MB для RD 32.",
                         "для APU (Radeon 660M, Vega iGPU и т.п.) автоматически",
                         "ограничивается 1024 MB чтобы не уронить игру по OOM — APU делит",
                         "память с ОЗУ. Дискретные карты используют полный диапазон.")
                .defineInRange("vertexPoolMB", 1024, 256, 4096);
        builder.pop();

        builder.comment("Настройки GPU Culling через compute shader (требует OpenGL 4.3)").push("culling");
        ENABLE_COMPUTE_CULLING = builder
                .comment("GPU frustum culling через compute shader.",
                         "Убирает невидимые чанки на GPU без нагрузки на CPU.")
                .define("enableComputeCulling", true);
        CULLING_WORKGROUP_SIZE = builder
                .comment("Размер workgroup для compute shader (степень 2).",
                         "На AMD оптимально 64 (один wavefront).")
                .defineInRange("workgroupSize", 64, 32, 256);
        builder.pop();

        builder.comment("Настройки Indirect Count (ARB_indirect_parameters)").push("indirect");
        ENABLE_INDIRECT_COUNT = builder
                .comment("Использовать glMultiDrawElementsIndirectCount — GPU читает drawCount",
                         "из parameter buffer, БЕЗ CPU readback. Убирает pipeline stall.",
                         "Требует ARB_indirect_parameters (есть на всех AMD RDNA/GCN4+).")
                .define("enableIndirectCount", true);
        builder.pop();

        builder.comment("Настройки interop-куллинга (при установленном Embeddium/Rubidium)").push("interop");
        ENABLE_INTEROP_COMPUTE_CULLING = builder
                .comment("GPU-compute куллинг в interop-режиме с Embeddium.",
                         "per-command culling (frustum + fog + Hi-Z occlusion), zero readback.",
                         "Требует OpenGL 4.3 (compute) + ARB_indirect_parameters.",
                         "Если выключено или GPU не поддерживает — используется прямой MDI-путь",
                         "(glMultiDrawElementsIndirect с CPU-provided count).")
                .define("enableInteropComputeCulling", true);

        ENABLE_INTEROP_FRUSTUM = builder
                .comment("Включить per-command frustum culling на GPU (поверх CPU culling Embeddium).",
                         "Дублирует Embedium frustum на GPU, но позволяет zero-readback pipeline.",
                         "Если выключено — frustum test пропускается (только fog + Hi-Z).")
                .define("enableInteropFrustum", true);

        ENABLE_INTEROP_FOG = builder
                .comment("Включить per-command fog-distance culling на GPU.",
                         "Секции дальше fog end полностью отсекаются на GPU без CPU работы.",
                         "Особенно полезно ночью, в дождь или с плотным туманом.")
                .define("enableInteropFog", true);

        ENABLE_HIZ_OCCLUSION = builder
                .comment("Включить Hi-Z GPU occlusion culling (киллер-фича v2.2).",
                         "Строит depth pyramid из предыдущего кадра, каждой видимой команде",
                         "проверяет AABB против пирамиды → отсекает секции заслонённые зданиями/горами.",
                         "1-frame latency (как у Nvidium). Требует OpenGL 4.3 (image load/store).",
                         "ЭТО даёт основной прирост FPS поверх Embedium.",
                         "Если выключено — только frustum + fog, occlusion не делается.")
                .define("enableHiZOcclusion", true);

        INTEROP_WORKGROUP_SIZE = builder
                .comment("Размер workgroup для interop compute culling shader (степень 2).",
                         "На AMD RDNA оптимально 64 (один wavefront).",
                         "На NVIDIA можно 32 (warp) или 64 — разница минимальна.",
                         "Изменение требует перекомпиляции шейдера (автоматически через Config screen).",
                         "Если новое значение не компилируется — автоматический откат к старому.")
                .defineInRange("interopWorkgroupSize", 64, 32, 256);
        builder.pop();

        builder.comment("Настройки persistent mapped buffers (требует OpenGL 4.4)").push("buffers");
        ENABLE_PERSISTENT_MAPPING = builder
                .comment("Использовать persistent mapped staging buffer для zero-copy uploads.")
                .define("enablePersistentMapping", true);
        FRAMES_IN_FLIGHT = builder
                .comment("Количество кадров в очереди GPU (frame pacing).",
                         "2 = двойная буферизация — минимальная задержка (input lag),",
                         "    но CPU может ждать GPU в тяжёлых кадрах.",
                         "3 = тройная буферизация (рекомендуется) — плавнее на слабых CPU/APU,",
                         "    но чуть больше input lag (~1 кадр). Оптимально для большинства.",
                         "4 = больше VRAM, ещё плавнее, но избыточно для большинства сцен.",
                         "Для минимальной задержки (competitive) — 2. Для плавности — 3.")
                .defineInRange("framesInFlight", 3, 2, 4);
        builder.pop();

        builder.comment("Интеграция с vanilla").push("integration");
        DISABLE_VANILLA_LAYER = builder
                .comment("Полностью отменять vanilla renderChunkLayer (true) или рисовать",
                         "MDI параллельно с vanilla для отладки (false).",
                         "Включайте true для production — иначе рисуем дважды.")
                .define("disableVanillaLayer", true);
        builder.pop();

        builder.comment("Отладка").push("debug");
        DEBUG_SHOW_CULLED = builder
                .comment("Показывать wireframe для отброшенных culling'ом чанков.")
                .define("showCulled", false);
        DEBUG_LOG_FRAME_STATS = builder
                .comment("Логировать статистику: draw calls, culled chunks в консоль.")
                .define("logFrameStats", false);
        builder.pop();

        builder.pop();
        SPEC = builder.build();
    }
}

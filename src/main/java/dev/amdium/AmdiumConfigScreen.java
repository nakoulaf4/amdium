package dev.amdium;

import dev.amdium.config.AmdiumConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

public class AmdiumConfigScreen extends Screen {
    private final Screen parent;

    public AmdiumConfigScreen(Screen parent) {
        // Подтягиваем локализованный заголовок окна / Fetch the localized screen title
        super(Component.translatable("amdium.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        // Разметка для двух колонок кнопок / Layout layout for two button columns
        int btnWidth = 200;
        int btnHeight = 20;
        int leftColX = this.width /2 - btnWidth - 5;
        int rightColX = this.width / 2 + 5;

        // --- ЛЕВАЯ КОЛОНКА (Основные оптимизации) / LEFT COLUMN (Core Optimizations) ---

        // Multi-Draw Indirect
        this.addRenderableWidget(createBooleanButton(leftColX, 80, btnWidth, btnHeight,
                "amdium.config.enableMDI", AmdiumConfig.ENABLE_MDI));

        // GPU Compute Culling
        this.addRenderableWidget(createBooleanButton(leftColX, 105, btnWidth, btnHeight,
                "amdium.config.enableComputeCulling", AmdiumConfig.ENABLE_COMPUTE_CULLING));

        // Indirect Count
        this.addRenderableWidget(createBooleanButton(leftColX, 130, btnWidth, btnHeight,
                "amdium.config.enableIndirectCount", AmdiumConfig.ENABLE_INDIRECT_COUNT));

        // Interop GPU Culling (Embeddium)
        this.addRenderableWidget(createBooleanButton(leftColX, 155, btnWidth, btnHeight,
                "amdium.config.enableInteropComputeCulling", AmdiumConfig.ENABLE_INTEROP_COMPUTE_CULLING));

        // --- ПРАВАЯ КОЛОНКА (Буферы и Отладка) / RIGHT COLUMN (Buffers & Debug) ---

        // Persistent Mapping
        this.addRenderableWidget(createBooleanButton(rightColX, 80, btnWidth, btnHeight,
                "amdium.config.enablePersistentMapping", AmdiumConfig.ENABLE_PERSISTENT_MAPPING));

        // Disable Vanilla Layer
        this.addRenderableWidget(createBooleanButton(rightColX, 105, btnWidth, btnHeight,
                "amdium.config.disableVanillaLayer", AmdiumConfig.DISABLE_VANILLA_LAYER));

        // Show Culled (Wireframe)
        this.addRenderableWidget(createBooleanButton(rightColX, 130, btnWidth, btnHeight,
                "amdium.config.showCulled", AmdiumConfig.DEBUG_SHOW_CULLED));


        // --- Кнопка "ГОТОВО" / "DONE" Button ---
        this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> {
                    if (this.minecraft != null) {
                        this.minecraft.setScreen(this.parent);
                    }
                })
                .bounds(this.width / 2 - 100, this.height - 28, 200, 20)
                .build());
    }

    /**
     * Универсальный метод создания кнопок с поддержкой мультиязычности и динамических тултипов.
     * Universal method for creating buttons with localization and dynamic tooltips support.
     * * @param translationKey Базовый ключ локализации / Base translation key (e.g., "amdium.config.enableMDI")
     */
    private Button createBooleanButton(int x, int y, int width, int height, String translationKey, ForgeConfigSpec.BooleanValue configEntry) {
        return Button.builder(buildButtonMessage(translationKey, configEntry.get()), button -> {
                    // Переключаем значение в конфигурации / Toggle the configuration value
                    boolean newValue = !configEntry.get();
                    configEntry.set(newValue);
                    AmdiumConfig.SPEC.save();

                    // Обновляем текст на кнопке / Update the button text
                    button.setMessage(buildButtonMessage(translationKey, newValue));
                })
                .bounds(x, y, width, height)
                // Автоматически ищет ключ + суффикс ".tooltip" / Automatically looks up key + ".tooltip" suffix
                .tooltip(Tooltip.create(Component.translatable(translationKey + ".tooltip")))
                .build();
    }

    /**
     * Формирует локализованную строку для кнопки вида "Имя настройки: ВКЛ/ВЫКЛ"
     * Builds a localized string for the button like "Setting Name: ON/OFF"
     * * Использует ванильные ключи перевода "options.on" и "options.off" для авто-перевода статуса.
     * Uses vanilla translation keys "options.on" and "options.off" for automatic status translation.
     */
    private Component buildButtonMessage(String translationKey, boolean isEnabled) {
        Component statusComponent = Component.translatable(isEnabled ? "options.on" : "options.off");
        return Component.translatable(translationKey)
                .append(": ")
                .append(statusComponent);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Фон меню / Screen background
        this.renderBackground(guiGraphics);

        // Заголовок экрана / Screen title
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, 20, 0xFFFFFF);

        // Определяем ключ перевода в зависимости от состояния GPU / Determine translation key based on GPU state
        String statusStateKey = Amdium.embediumInteropActive  ? "amdium.config.status.embedium" :
                Amdium.active                 ? "amdium.config.status.vanilla" :
                "amdium.config.status.disabled";

        // Собираем финальный компонент "Статус: [Значение]" / Build the final "Status: [Value]" component
        Component statusComponent = Component.translatable("amdium.config.status", Component.translatable(statusStateKey));

        // Рисуем локализованный статус / Draw localized status
        guiGraphics.drawCenteredString(this.font, statusComponent, this.width / 2, 50, 0xAAAAAA);

        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
}
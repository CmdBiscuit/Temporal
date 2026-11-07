package com.temporal.client.gui;

import com.temporal.TemporalConfig;
import com.temporal.TemporalConfig.AntiAliasing;
import com.temporal.TemporalConfig.DlssModel;
import com.temporal.TemporalConfig.FrameGenerationMethod;
import com.temporal.TemporalConfig.FrameGenerationMode;
import com.temporal.TemporalConfig.QualityMode;
import com.temporal.TemporalConfig.Upscaler;
import com.temporal.client.DynamicResolution;
import com.temporal.client.FirstLaunch;
import com.temporal.client.bridge.Bridge;
import com.temporal.client.bridge.FrameGeneration;
import com.temporal.client.nis.NisScaler;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraftforge.common.ForgeConfigSpec;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector2i;
import org.joml.Vector2ic;

/**
 * Temporal's settings, in its own pixel-art style: a dark glass panel with Temporal's icon, two pages (upscaling, frame
 * generation), switches, sliders and value pickers instead of a plain list. Changes apply as they're made and are
 * saved when the screen closes.
 */
public final class TemporalScreen extends Screen {
    private static final int MAX_PANEL_WIDTH = 360;
    private static final int PANEL_HEIGHT = 230;
    private static final long RESET_CONFIRM_MS = 3000;
    private static final int FOOTER_BUTTON_WIDTH = 80;
    private static Page lastPage = Page.UPSCALING;

    private enum Page { UPSCALING, FRAME_GENERATION }

    @Nullable private final Screen parent;
    private final List<OptionRow> rows = new ArrayList<>();
    private Page page = lastPage;
    private boolean rebuild;
    private PixelButton resetButton;
    private long resetArmedUntil;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int contentX;
    private int contentY;
    private int contentWidth;
    private int contentBottom;

    public TemporalScreen(@Nullable Screen parent) {
        super(Component.translatable("temporal.gui.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        rows.clear();
        panelWidth = Math.min(width - 12, MAX_PANEL_WIDTH);
        panelHeight = Math.min(height - 8, PANEL_HEIGHT);
        panelX = (width - panelWidth) / 2;
        panelY = (height - panelHeight) / 2;
        contentX = panelX + 9;
        contentWidth = panelWidth - 18;

        int tabY = panelY + 33;
        int tabWidth = (contentWidth - 4) / 2;
        addRenderableWidget(new PixelButton(contentX, tabY, tabWidth, 16, Component.translatable("temporal.gui.page.upscaling"),
                () -> show(Page.UPSCALING), true).selected(page == Page.UPSCALING));
        addRenderableWidget(new PixelButton(contentX + tabWidth + 4, tabY, contentWidth - tabWidth - 4, 16,
                Component.translatable("temporal.gui.page.frameGeneration"), () -> show(Page.FRAME_GENERATION), true)
                .selected(page == Page.FRAME_GENERATION));

        contentY = tabY + 23;
        contentBottom = panelY + panelHeight - 33;
        if (page == Page.UPSCALING) {
            addUpscalingRows();
        } else {
            addFrameGenerationRows();
        }

        int footerY = panelY + panelHeight - 27;
        resetButton = addRenderableWidget(new PixelButton(contentX, footerY, FOOTER_BUTTON_WIDTH, 20,
                Component.translatable("temporal.gui.reset"), this::reset));
        resetArmedUntil = 0;
        addRenderableWidget(new PixelButton(contentX + contentWidth - FOOTER_BUTTON_WIDTH, footerY, FOOTER_BUTTON_WIDTH, 20,
                CommonComponents.GUI_DONE, this::onClose));
    }

    private void addUpscalingRows() {
        Upscaler upscaler = TemporalConfig.UPSCALER.get();
        boolean gpu = onGpu(upscaler);
        BooleanSupplier enabled = TemporalConfig.ENABLED::get;
        Supplier<Component> switchedOff = () -> Component.translatable("temporal.gui.why.disabled");
        int step = Math.min(Look.ROW_HEIGHT + 2, (contentBottom - contentY) / 7);
        int y = contentY;

        addRow(new ToggleRow(contentX, y, contentWidth, name("enabled"), tooltip("enabled"), () -> true, () -> null,
                TemporalConfig.ENABLED::get, value -> change(TemporalConfig.ENABLED, value)));
        y += step;
        addRow(new CycleRow<>(contentX, y, contentWidth, name("upscaler"), windowsOnly(tooltip("upscaler")), enabled, switchedOff,
                List.of(Upscaler.values()), TemporalConfig.UPSCALER::get, value -> {
                    change(TemporalConfig.UPSCALER, value);
                    rebuild = true; // quality mode and render scale take turns
                }, Upscaler::getTranslatedName));
        y += step;
        if (gpu) {
            QualityMode[] modes = QualityMode.values();
            addRow(new StepSliderRow(contentX, y, contentWidth, name("qualityMode"), tooltip("qualityMode"), enabled,
                    switchedOff, modes.length, () -> TemporalConfig.QUALITY_MODE.get().ordinal(),
                    index -> change(TemporalConfig.QUALITY_MODE, modes[index]), index -> modes[index].getTranslatedName()));
        } else {
            addRow(new StepSliderRow(contentX, y, contentWidth, name("renderScale"), tooltip("renderScale"), enabled,
                    switchedOff, 16, () -> (int) Math.round((TemporalConfig.RENDER_SCALE.get() - 0.25) / 0.05),
                    index -> change(TemporalConfig.RENDER_SCALE, 0.25 + index * 0.05),
                    index -> Component.literal(Math.round((0.25 + index * 0.05) * 100) + "%")));
        }
        y += step;
        int[] targets = TemporalConfig.DYNAMIC_RESOLUTION_TARGETS;
        addRow(new StepSliderRow(contentX, y, contentWidth, name("dynamicResolution"), tooltip("dynamicResolution"),
                DynamicResolution::applies,
                () -> !TemporalConfig.ENABLED.get() ? switchedOff.get() : Component.translatable("temporal.gui.why.qualityMode"),
                targets.length, () -> nearest(targets, TemporalConfig.DYNAMIC_RESOLUTION_TARGET.get()),
                index -> change(TemporalConfig.DYNAMIC_RESOLUTION_TARGET, targets[index]),
                index -> targets[index] == 0 ? Component.translatable("temporal.configuration.dynamicResolution.off")
                        : Component.literal(String.valueOf(targets[index]))));
        y += step;
        // With DLSS the slider sets NIS sharpening after DLSS, a separate value (off by default).
        Supplier<ForgeConfigSpec.DoubleValue> sharpness = () -> TemporalConfig.UPSCALER.get() == Upscaler.DLSS && Bridge.osSupported()
                ? TemporalConfig.DLSS_SHARPNESS : TemporalConfig.SHARPNESS;
        addRow(new StepSliderRow(contentX, y, contentWidth, name("sharpness"), tooltip("sharpness"),
                () -> TemporalConfig.ENABLED.get() && sharpens(TemporalConfig.UPSCALER.get()),
                () -> !TemporalConfig.ENABLED.get() ? switchedOff.get()
                        : TemporalConfig.UPSCALER.get() == Upscaler.DLSS ? Component.literal(NisScaler.unavailableReason() + ".")
                        : Component.translatable("temporal.gui.why.fsrOnly"),
                21, () -> (int) Math.round(sharpness.get().get() / 0.05),
                index -> change(sharpness.get(), index * 0.05), index -> Component.literal(index * 5 + "%")));
        y += step;
        addRow(new ToggleRow(contentX, y, contentWidth, name("antiAliasing"), tooltip("antiAliasing"),
                () -> TemporalConfig.ENABLED.get() && !onGpu(TemporalConfig.UPSCALER.get()),
                () -> !TemporalConfig.ENABLED.get() ? switchedOff.get() : Component.translatable("temporal.gui.why.ownAntiAliasing"),
                () -> TemporalConfig.ANTI_ALIASING.get() == AntiAliasing.TAA,
                value -> change(TemporalConfig.ANTI_ALIASING, value ? AntiAliasing.TAA : AntiAliasing.OFF)));
        y += step;
        addRow(new CycleRow<>(contentX, y, contentWidth, name("dlssModel"), tooltip("dlssModel"),
                () -> TemporalConfig.ENABLED.get() && TemporalConfig.UPSCALER.get() == Upscaler.DLSS && Bridge.osSupported(),
                () -> !Bridge.osSupported() ? Component.translatable("temporal.gui.why.windowsOnly")
                        : !TemporalConfig.ENABLED.get() ? switchedOff.get() : Component.translatable("temporal.gui.why.dlssOnly"),
                List.of(DlssModel.values()), TemporalConfig.DLSS_MODEL::get, value -> change(TemporalConfig.DLSS_MODEL, value),
                DlssModel::getTranslatedName));
    }

    private void addFrameGenerationRows() {
        FrameGenerationMode[] modes = FrameGenerationMode.values();
        FrameGenerationSlider slider = addRenderableWidget(new FrameGenerationSlider(contentX, contentY, contentWidth,
                name("frameGeneration"), windowsOnly(tooltip("frameGeneration")), () -> TemporalConfig.FRAME_GENERATION.get().ordinal(),
                index -> change(TemporalConfig.FRAME_GENERATION, modes[index]), index -> modes[index].getTranslatedName(),
                FrameGeneration::supportedMultiplier));
        slider.active = Bridge.osSupported();
        int y = contentY + FrameGenerationSlider.HEIGHT + 4;
        addRow(new CycleRow<>(contentX, y, contentWidth, name("frameGenerationMethod"), tooltip("frameGenerationMethod"),
                Bridge::osSupported, () -> Component.translatable("temporal.gui.why.windowsOnly"),
                List.of(FrameGenerationMethod.values()), TemporalConfig.FRAME_GENERATION_METHOD::get,
                value -> change(TemporalConfig.FRAME_GENERATION_METHOD, value), FrameGenerationMethod::getTranslatedName));
        y += Look.ROW_HEIGHT + 2;
        addRow(new ToggleRow(contentX, y, contentWidth, name("fpsCounter"), tooltip("fpsCounter"), () -> true, () -> null,
                TemporalConfig.FPS_COUNTER::get, value -> change(TemporalConfig.FPS_COUNTER, value)));
    }

    private void addRow(OptionRow row) {
        rows.add(row);
        addRenderableWidget(row);
    }

    /** The step whose value is closest to {@code value} (a hand-edited config file may hold one between steps). */
    private static int nearest(int[] values, int value) {
        int best = 0;
        for (int i = 1; i < values.length; i++) {
            if (Math.abs(values[i] - value) < Math.abs(values[best] - value)) {
                best = i;
            }
        }
        return best;
    }

    /** DLSS or FSR 3, and able to run here (off Windows they fall back to FSR 1, which uses Render Scale and TAA). */
    private static boolean onGpu(Upscaler upscaler) {
        return (upscaler == Upscaler.DLSS || upscaler == Upscaler.FSR3) && Bridge.osSupported();
    }

    /** Off Windows, an explanation starts with why DLSS, FSR 3 and frame generation aren't there. */
    private static Component windowsOnly(Component description) {
        return Bridge.osSupported() ? description : Component.empty()
                .append(Component.translatable("temporal.gui.why.windowsOnly").withStyle(ChatFormatting.GOLD))
                .append("\n\n").append(description);
    }

    private static boolean sharpens(Upscaler upscaler) {
        return upscaler == Upscaler.FSR || upscaler == Upscaler.FSR3 || upscaler == Upscaler.NIS
                || upscaler == Upscaler.DLSS && (!Bridge.osSupported() || NisScaler.unavailableReason() == null);
    }

    private static Component name(String key) {
        return Component.translatable("temporal.configuration." + key);
    }

    private static Component tooltip(String key) {
        return Component.translatable("temporal.configuration." + key + ".tooltip");
    }

    /** Applies a setting right away; other rows may stop or start applying because of it. */
    private <T> void change(ForgeConfigSpec.ConfigValue<T> setting, T value) {
        FirstLaunch.settingsChanged();
        setting.set(value);
        rows.forEach(OptionRow::refresh);
    }

    private void show(Page next) {
        page = next;
        lastPage = next;
        rebuild = true;
    }

    /** Every setting back to its default, on a second click within a few seconds (a stray click shouldn't undo it all). */
    private void reset() {
        long now = Util.getMillis();
        if (now > resetArmedUntil) {
            resetArmedUntil = now + RESET_CONFIRM_MS;
            resetButton.setMessage(Component.translatable("temporal.gui.reset.confirm").withStyle(ChatFormatting.GOLD));
            return;
        }
        resetArmedUntil = 0;
        for (ForgeConfigSpec.ConfigValue<?> setting : List.of(TemporalConfig.ENABLED, TemporalConfig.RENDER_SCALE,
                TemporalConfig.UPSCALER, TemporalConfig.SHARPNESS, TemporalConfig.ANTI_ALIASING, TemporalConfig.QUALITY_MODE,
                TemporalConfig.DLSS_MODEL, TemporalConfig.FRAME_GENERATION, TemporalConfig.FRAME_GENERATION_METHOD,
                TemporalConfig.FPS_COUNTER, TemporalConfig.DYNAMIC_RESOLUTION_TARGET, TemporalConfig.DLSS_SHARPNESS)) {
            restoreDefault(setting);
        }
        // Like a first launch: the best upscaler this PC runs, not the FSR 1 default.
        FirstLaunch.applyRecommended();
        rebuild = true;
    }

    private static <T> void restoreDefault(ForgeConfigSpec.ConfigValue<T> setting) {
        setting.set(setting.getDefault());
    }

    @Override
    public void onClose() {
        TemporalConfig.SPEC.save();
        minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (rebuild) {
            // Rebuilt here rather than in a widget's callback, which runs while the widgets are being iterated.
            rebuild = false;
            int focused = rows.indexOf(getFocused());
            rebuildWidgets();
            if (focused >= 0 && focused < rows.size()) {
                setFocused(rows.get(focused));
            }
        }
        if (resetArmedUntil != 0 && Util.getMillis() > resetArmedUntil) {
            resetArmedUntil = 0;
            resetButton.setMessage(Component.translatable("temporal.gui.reset"));
        }
        renderBackground(graphics); // 1.20.1's Screen.render leaves the background to the screen
        super.render(graphics, mouseX, mouseY, partialTick);
        explainSetting();
    }

    /**
     * The hovered (or keyboard-focused) setting's explanation as a tooltip that stays on screen. Vanilla's widget
     * tooltips wrap at 170 and, when there's no room below the widget, go above it even past the top of the screen,
     * which the longer explanations did at large GUI scales.
     */
    private void explainSetting() {
        AbstractWidget subject = null;
        for (GuiEventListener child : children()) {
            if (child instanceof Explained && child instanceof AbstractWidget widget && widget.isHovered()) {
                subject = widget;
            }
        }
        boolean keyboard = subject == null && minecraft.getLastInputType().isKeyboard();
        if (keyboard && getFocused() instanceof Explained && getFocused() instanceof AbstractWidget focused) {
            subject = focused;
        }
        if (subject == null) {
            return;
        }
        Component text = ((Explained) subject).explanation();
        int wrap = Math.min(Math.max(170, width / 2 - 20), width - 20);
        List<FormattedCharSequence> lines = font.split(text, wrap);
        if (lines.size() * 10 + 12 > height - 10) {
            lines = font.split(text, width - 20); // a big GUI scale in a small window: use the full width
        }
        setTooltipForNextRenderPass(lines, new BesideWidget(subject.getRectangle(), keyboard), true);
    }

    /**
     * Places a tooltip next to the pointer (or the focused widget), below the widget it explains or else above it, and
     * when neither has room, on the roomier side pushed back onto the screen.
     */
    private record BesideWidget(ScreenRectangle widget, boolean keyboard) implements ClientTooltipPositioner {
        /** The tooltip's frame reaches 4 px past its text. */
        private static final int MARGIN = 5;

        @Override
        public Vector2ic positionTooltip(int screenWidth, int screenHeight, int mouseX, int mouseY, int tooltipWidth,
                                         int tooltipHeight) {
            int x = keyboard ? widget.left() + 8 : mouseX + 12;
            if (!keyboard && x + tooltipWidth > screenWidth - MARGIN) {
                x = mouseX - 12 - tooltipWidth;
            }
            x = Math.max(MARGIN, Math.min(x, screenWidth - MARGIN - tooltipWidth));
            int below = widget.bottom() + 6;
            int above = widget.top() - 6 - tooltipHeight;
            int y;
            if (below + tooltipHeight <= screenHeight - MARGIN) {
                y = below;
            } else if (above >= MARGIN) {
                y = above;
            } else {
                y = screenHeight - widget.bottom() >= widget.top() ? below : above;
            }
            y = Math.max(MARGIN, Math.min(y, screenHeight - MARGIN - tooltipHeight));
            return new Vector2i(x, y);
        }
    }

    @Override
    public void renderBackground(GuiGraphics graphics) {
        super.renderBackground(graphics);
        Look.translucent(graphics, 1.0F); // the panel's glass lets a little of the world through
        Look.blit(graphics, Look.PANEL, panelX, panelY, panelWidth, panelHeight);
        renderHeader(graphics);
        Look.blit(graphics, Look.INSET, contentX - 3, contentY - 4, contentWidth + 6, contentBottom - contentY + 7);
        Component hint = Component.translatable("temporal.gui.hint");
        int footerY = panelY + panelHeight - 27;
        if (font.width(hint) <= contentWidth - 2 * FOOTER_BUTTON_WIDTH - 12) {
            graphics.drawString(font, hint, contentX + (contentWidth - font.width(hint)) / 2, footerY + 6, Look.TEXT_DISABLED);
        }
    }

    private void renderHeader(GuiGraphics graphics) {
        Look.blit(graphics, Look.ICON, panelX + 10, panelY + 8, 16, 16);
        graphics.pose().pushPose();
        graphics.pose().translate(panelX + 31, panelY + 9, 0.0F);
        graphics.pose().scale(2.0F, 2.0F, 1.0F);
        graphics.drawString(font, title, 0, 0, Look.TEXT);
        graphics.pose().popPose();

        int titleRight = panelX + 31 + font.width(title) * 2;
        int right = panelX + panelWidth - 11;
        Component summary = summary();
        if (font.width(summary) <= right - titleRight - 10) {
            graphics.drawString(font, summary, right - font.width(summary), panelY + 13, Look.TEXT_DIM);
        } else {
            Look.scrollingString(graphics, font, summary, titleRight + 10, panelY + 9, right, panelY + 25, Look.TEXT_DIM);
        }

        // A pixel gradient rule under the header: cyan in the middle, fading into the panel's edges.
        int lineY = panelY + 29;
        int left = contentX;
        int width = contentWidth;
        graphics.fill(left, lineY, left + width, lineY + 1, Look.SEPARATOR);
        graphics.fill(left + width / 8, lineY, left + width - width / 8, lineY + 1, Look.CYAN_DARK);
        graphics.fill(left + width / 4, lineY, left + width - width / 4, lineY + 1, Look.CYAN_DIM);
    }

    /** "DLSS · Quality · Frame Gen 4x": what's switched on, at a glance. */
    private static Component summary() {
        MutableComponent text = Component.empty();
        if (TemporalConfig.ENABLED.get()) {
            Upscaler upscaler = TemporalConfig.UPSCALER.get();
            // Off Windows a DLSS / FSR 3 setting runs as FSR 1: say what actually runs.
            boolean standIn = (upscaler == Upscaler.DLSS || upscaler == Upscaler.FSR3) && !Bridge.osSupported();
            text.append((standIn ? Upscaler.FSR : upscaler).getTranslatedName()).append(" · ");
            if (onGpu(upscaler)) {
                text.append(TemporalConfig.QUALITY_MODE.get().getTranslatedName());
            } else {
                text.append(Math.round(TemporalConfig.RENDER_SCALE.get() * 100) + "%");
            }
        } else {
            text.append(Component.translatable("temporal.gui.summary.off"));
        }
        FrameGenerationMode frameGeneration = TemporalConfig.FRAME_GENERATION.get();
        if (frameGeneration != FrameGenerationMode.OFF && Bridge.osSupported()) {
            text.append(" · ").append(Component.translatable("temporal.gui.summary.frameGeneration",
                    frameGeneration.getTranslatedName()));
        }
        return text;
    }
}

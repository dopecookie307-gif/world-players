package com.worldplayers.client;

import com.worldplayers.resident.WorkZone;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 改工作区域名字（v0.2.12）：清单里点名字打开，输入框回车直接存；
 * 底下是「储存」按钮（同款灰白原版按钮 + 按钮音效，主人 2026-11-20）。ESC 取消。
 */
public class RenameZoneScreen extends Screen {
    private static final int PANEL_W = 200;
    private static final int PANEL_H = 94;
    private static final int BTN_W = 84;
    private final WorkZone zone;
    /** 改名后返回哪一页（透传给详情页）。 */
    private final int from;
    @javax.annotation.Nullable
    private final String fromZoneType;
    private final ManualTheme theme = ManualThemes.current();
    private EditBox box;
    private int panelLeft;
    private int panelTop;

    public RenameZoneScreen(WorkZone zone) {
        this(zone, WorkZoneDetailScreen.FROM_REGION, null);
    }

    public RenameZoneScreen(WorkZone zone, int from, @javax.annotation.Nullable String fromZoneType) {
        super(Component.translatable("gui.worldplayers.zone.rename"));
        this.zone = zone;
        this.from = from;
        this.fromZoneType = fromZoneType;
    }

    @Override
    protected void init() {
        this.panelLeft = (this.width - PANEL_W) / 2;
        this.panelTop = (this.height - PANEL_H) / 2;
        this.box = new EditBox(this.font, this.panelLeft + 10, this.panelTop + 30,
            PANEL_W - 20, 18, Component.empty());
        this.box.setValue(this.zone.name());
        this.box.setMaxLength(32);
        this.box.setFocused(true);
        this.addRenderableWidget(this.box);
        this.setInitialFocus(this.box);
    }

    /** 有输入框的界面不要原版菜单模糊（主人 2026-11-20 要求，和储存界面同一条）。 */
    @Override
    public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        // 故意留空：不模糊
    }

    private int buttonX() {
        return this.panelLeft + (PANEL_W - BTN_W) / 2;
    }

    private int buttonY() {
        return this.panelTop + PANEL_H - 30;
    }

    private boolean isOverButton(double mouseX, double mouseY) {
        int x = buttonX();
        int y = buttonY();
        return mouseX >= x && mouseX <= x + BTN_W && mouseY >= y && mouseY <= y + 20;
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        gui.fill(0, 0, this.width, this.height, 0x99000000);
        gui.fill(this.panelLeft, this.panelTop, this.panelLeft + PANEL_W, this.panelTop + PANEL_H, 0xF0201A0E);
        gui.fill(this.panelLeft, this.panelTop, this.panelLeft + PANEL_W, this.panelTop + 1, 0xFF808080);
        gui.fill(this.panelLeft, this.panelTop + PANEL_H - 1,
            this.panelLeft + PANEL_W, this.panelTop + PANEL_H, 0xFF808080);
        Component title = Component.translatable("gui.worldplayers.zone.rename");
        gui.drawString(this.font, title,
            this.panelLeft + (PANEL_W - this.font.width(title)) / 2, this.panelTop + 10, 0xFFFFFF, true);

        // 储存按钮（同款灰白原版按钮）
        int bx = buttonX();
        int by = buttonY();
        boolean hovered = isOverButton(mouseX, mouseY);
        gui.blitSprite(ResourceLocation.withDefaultNamespace(
            hovered ? "widget/button_highlighted" : "widget/button"), bx, by, BTN_W, 20);
        Component label = Component.translatable("gui.worldplayers.zone.save");
        gui.drawString(this.font, label, bx + (BTN_W - this.font.width(label)) / 2, by + 6,
            0xFFFFFF, true);

        super.render(gui, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverButton(mouseX, mouseY)) {
            ZoneUi.click();
            this.saveAndClose();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
                || keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER) {
            this.saveAndClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void saveAndClose() {
        String name = this.box == null ? "" : this.box.getValue().strip();
        if (!name.isEmpty() && !name.equals(this.zone.name())) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.worldplayers.network.ZoneRenamePayload(
                    this.zone.name(), this.zone.origin(), name));
        }
        // 改名后回到详情页（沿用新的名字）
        WorkZone shown = name.isEmpty() ? this.zone : this.zone.withName(name);
        if (this.minecraft != null) {
            this.minecraft.setScreen(new WorkZoneDetailScreen(shown, this.from, this.fromZoneType));
        }
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new WorkZoneDetailScreen(this.zone, this.from, this.fromZoneType));
        }
    }

    private void backToManual() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(new ManualScreen());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

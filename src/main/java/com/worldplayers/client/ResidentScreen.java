package com.worldplayers.client;

import com.worldplayers.WorldPlayers;
import com.worldplayers.menu.ResidentMenu;
import com.worldplayers.network.ResidentSettingsPayload;
import com.worldplayers.resident.ResidentEntity;
import com.worldplayers.resident.ResidentSkins;
import java.util.List;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 居民背包界面（176×300，原版物品栏风格）。
 *
 * <p>右侧自上而下：名称栏（点进去才编辑，✔ 确认）→ 生命值 → 饥饿值 → 心情值 → 体力值 → 模型 → 皮肤。</p>
 */
public class ResidentScreen extends AbstractContainerScreen<ResidentMenu> {
    private static final ResourceLocation TEXTURE = WorldPlayers.id("textures/gui/resident.png");
    private static final int WIDTH = ResidentMenu.IMAGE_WIDTH;
    private static final int HEIGHT = ResidentMenu.IMAGE_HEIGHT;

    private static final int PREVIEW_X1 = 31;
    private static final int PREVIEW_Y1 = 7;
    private static final int PREVIEW_X2 = 91;
    private static final int PREVIEW_Y2 = 123;

    private static final int GAUGE_X = 97;
    private static final int GAUGE_W = 70;
    private static final int GAUGE_H = 11;
    private static final int[] GAUGE_Y = {27, 43, 59, 75};
    /** 心情值栏位统一用粉紫色（和 Jade 里的心情行同一个色）。 */
    private static final int MOOD_COLOR = 0xFFFF55FF;

    private static final int NAME_BOX_X = 98;
    private static final int NAME_BOX_Y = 9;
    private static final int NAME_BOX_W = 47;
    private static final int NAME_BOX_H = 12;
    private static final int CONFIRM_BUTTON_X = 149;
    private static final int CONFIRM_BUTTON_Y = 7;
    private static final int CONFIRM_BUTTON_W = 18;
    private static final int CONFIRM_BUTTON_H = 14;

    private static final int MODEL_BUTTON_X = 96;
    private static final int MODEL_BUTTON_Y = 92;
    private static final int SKIN_BUTTON_X = 96;
    private static final int SKIN_BUTTON_Y = 109;
    private static final int BUTTON_W = 72;
    private static final int BUTTON_H = 15;

    /** 正版账号皮肤：画在 GUI 框外、名字勾的右边，图标是铁胸甲（盔甲值符号）。 */
    private static final int IMPORT_ICON_SIZE = 16;
    private static final int IMPORT_ICON_X = WIDTH + 3;
    private static final int IMPORT_ICON_Y = 7;
    /** 同一个位置再往下：金镐子 = 打开皮肤文件夹（customs）。两个图标都用物品渲染，自动跟材质包。 */
    private static final int FOLDER_ICON_Y = IMPORT_ICON_Y + IMPORT_ICON_SIZE + 2;

    private static final int LIST_ITEM_H = 11;
    private static final int LIST_VISIBLE = 8;
    private static final int LIST_W = 72;

    private static final String[] MODEL_LABELS = {"Wide", "Slim", "Kid-Wide", "Kid-Slim"};

    private EditBox nameBox;
    private Button modelButton;
    private Button skinButton;

    private int selectedModel; // 0 = Wide, 1 = Slim
    private String selectedSkin = "";
    /** 上一次从服务端看到的皮肤名：正版皮肤下载完服务端会改它，这里靠它把皮肤栏刷成新选择。 */
    private String lastResidentSkin = "";

    private enum Dropdown { NONE, MODEL, SKIN }

    private Dropdown openDropdown = Dropdown.NONE;
    private int scroll;
    private boolean draggingScrollbar;
    /** 只有点击了名字栏才进入输入状态；否则 E 仍然关闭界面（和原版箱子界面一致）。 */
    private boolean editingName;

    public ResidentScreen(ResidentMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = WIDTH;
        this.imageHeight = HEIGHT;
        this.inventoryLabelY = 100000;
        this.titleLabelX = 100000;
        ResidentEntity resident = menu.resident();
        if (resident != null) {
            this.selectedModel = Math.clamp(resident.modelType() - 1, 0, MODEL_LABELS.length - 1);
            this.selectedSkin = resident.skinName();
            this.lastResidentSkin = resident.skinName();
        }
    }

    @Override
    protected void init() {
        super.init();
        ResidentEntity resident = this.menu.resident();
        // 从「正版账号皮肤」界面回来时皮肤可能已经换了：这里重新对齐一次，免得旧值又被推回服务端
        if (resident != null) {
            this.selectedModel = Math.clamp(resident.modelType() - 1, 0, MODEL_LABELS.length - 1);
            this.selectedSkin = resident.skinName();
            this.lastResidentSkin = resident.skinName();
        }

        String currentName = "";
        if (resident != null && resident.getCustomName() != null) {
            currentName = resident.getCustomName().getString();
        }
        this.nameBox = new EditBox(this.font, this.leftPos + NAME_BOX_X, this.topPos + NAME_BOX_Y,
            NAME_BOX_W, NAME_BOX_H, Component.translatable("gui.worldplayers.resident.name"));
        this.nameBox.setMaxLength(64);
        this.nameBox.setBordered(false);
        this.nameBox.setTextColor(0xFFFFFF);
        this.nameBox.setValue(currentName);
        this.addRenderableWidget(this.nameBox);

        Button confirmButton = Button.builder(
                Component.literal("\u2714"),
                button -> this.confirmName())
            .bounds(this.leftPos + CONFIRM_BUTTON_X, this.topPos + CONFIRM_BUTTON_Y,
                CONFIRM_BUTTON_W, CONFIRM_BUTTON_H)
            .build();
        this.addRenderableWidget(confirmButton);

        this.modelButton = Button.builder(
                modelLabel(this.selectedModel),
                button -> this.toggleDropdown(Dropdown.MODEL))
            .bounds(this.leftPos + MODEL_BUTTON_X, this.topPos + MODEL_BUTTON_Y, BUTTON_W, BUTTON_H)
            .build();
        this.addRenderableWidget(this.modelButton);

        this.skinButton = Button.builder(
                skinLabel(this.selectedSkin),
                button -> this.toggleDropdown(Dropdown.SKIN))
            .bounds(this.leftPos + SKIN_BUTTON_X, this.topPos + SKIN_BUTTON_Y, BUTTON_W, BUTTON_H)
            .build();
        this.addRenderableWidget(this.skinButton);
    }

    /** 打勾：播放按钮音效、退出输入模式、立刻保存名字。 */
    private void confirmName() {
        this.editingName = false;
        this.nameBox.setFocused(false);
        this.setFocused(null);
        if (this.minecraft != null) {
            this.minecraft.getSoundManager().play(
                SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), 1.0F));
        }
        this.pushSettings();
    }

    private void toggleDropdown(Dropdown dropdown) {
        if (this.openDropdown == dropdown) {
            this.openDropdown = Dropdown.NONE;
        } else {
            this.openDropdown = dropdown;
            this.scroll = 0;
        }
    }

    private static Component modelLabel(int model) {
        return Component.translatable("gui.worldplayers.model", MODEL_LABELS[model]);
    }

    /** 皮肤按钮文字：空 = 默认皮肤（走语言文件），否则显示皮肤文件名（过长截断）。 */
    private static Component skinLabel(String skin) {
        if (skin == null || skin.isEmpty()) {
            return Component.translatable("gui.worldplayers.skin.default");
        }
        return Component.translatable("gui.worldplayers.skin", shortName(skin));
    }

    private static String shortName(String skin) {
        if (skin == null || skin.isEmpty()) {
            return "";
        }
        return skin.length() > 10 ? skin.substring(0, 10) : skin;
    }

    // ------------------------------------------------------------------
    // 渲染
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        ResidentEntity resident = this.menu.resident();
        if (resident != null) {
            // 正版皮肤下载完，服务端会把皮肤名换掉：这里跟着刷新皮肤栏和按钮文字
            String currentSkin = resident.skinName();
            if (!currentSkin.equals(this.lastResidentSkin)) {
                this.lastResidentSkin = currentSkin;
                this.selectedSkin = currentSkin;
                if (this.skinButton != null) {
                    this.skinButton.setMessage(skinLabel(currentSkin));
                }
            }
            InventoryScreen.renderEntityInInventoryFollowsMouse(guiGraphics,
                this.leftPos + PREVIEW_X1, this.topPos + PREVIEW_Y1,
                this.leftPos + PREVIEW_X2, this.topPos + PREVIEW_Y2,
                52, 0.0625F, mouseX, mouseY, resident);
            this.renderGauges(guiGraphics, resident);
        }

        if (this.openDropdown != Dropdown.NONE) {
            this.renderDropdown(guiGraphics, mouseX, mouseY);
        }
        this.renderSkinImportIcon(guiGraphics, mouseX, mouseY);
        this.renderTooltip(guiGraphics, mouseX, mouseY);
    }

    /** 框外右侧两个图标：铁胸甲 = 正版账号皮肤；金镐子 = 打开自定义皮肤文件夹。 */
    private void renderSkinImportIcon(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        int x = this.leftPos + IMPORT_ICON_X;
        int chestY = this.topPos + IMPORT_ICON_Y;
        int pickY = this.topPos + FOLDER_ICON_Y;
        boolean chestHover = insideIcon(mouseX, mouseY, x, chestY);
        boolean pickHover = insideIcon(mouseX, mouseY, x, pickY);
        if (chestHover) {
            guiGraphics.fill(x - 1, chestY - 1, x + IMPORT_ICON_SIZE + 1, chestY + IMPORT_ICON_SIZE + 1, 0x66FFFFFF);
        }
        if (pickHover) {
            guiGraphics.fill(x - 1, pickY - 1, x + IMPORT_ICON_SIZE + 1, pickY + IMPORT_ICON_SIZE + 1, 0x66FFFFFF);
        }
        guiGraphics.renderItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE), x, chestY);
        guiGraphics.renderItem(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.GOLDEN_PICKAXE), x, pickY);
        if (chestHover) {
            guiGraphics.renderTooltip(this.font,
                Component.translatable("gui.worldplayers.player_skin.button"), mouseX, mouseY);
        } else if (pickHover) {
            guiGraphics.renderTooltip(this.font,
                Component.translatable("gui.worldplayers.player_skin.folder"), mouseX, mouseY);
        }
    }

    private static boolean insideIcon(int mouseX, int mouseY, int x, int y) {
        return mouseX >= x && mouseX <= x + IMPORT_ICON_SIZE
            && mouseY >= y && mouseY <= y + IMPORT_ICON_SIZE;
    }

    /** 直接调系统文件管理器打开 customs 目录（没有就建一个）。 */
    private void openSkinFolder() {
        try {
            java.nio.file.Path dir = ResidentSkins.customs();
            java.nio.file.Files.createDirectories(dir);
            net.minecraft.Util.getPlatform().openFile(dir.toFile());
        } catch (Exception e) {
            WorldPlayers.LOGGER.warn("World Players: 打开皮肤文件夹失败", e);
        }
    }

    private void renderGauges(GuiGraphics guiGraphics, ResidentEntity resident) {
        float maxHealth = resident.getMaxHealth();
        this.drawGauge(guiGraphics, 0, maxHealth <= 0 ? 0 : resident.getHealth() / maxHealth, 0xFFFF4040,
            Component.translatable("gui.worldplayers.stat.health",
                (int) Math.ceil(resident.getHealth()), (int) maxHealth));
        this.drawGauge(guiGraphics, 1, resident.hunger() / 100.0F, 0xFFFFD24A,
            Component.translatable("gui.worldplayers.stat.hunger", (int) Math.ceil(resident.hunger())));
        this.drawGauge(guiGraphics, 2, resident.mood() / 100.0F, MOOD_COLOR,
            Component.translatable("gui.worldplayers.stat.mood", (int) Math.ceil(resident.mood())));
        this.drawGauge(guiGraphics, 3, resident.stamina() / 100.0F, 0xFF58D858,
            Component.translatable("gui.worldplayers.stat.stamina", (int) Math.ceil(resident.stamina())));
    }

    private void drawGauge(GuiGraphics guiGraphics, int index, float fill, int color, Component label) {
        int x = this.leftPos + GAUGE_X;
        int y = this.topPos + GAUGE_Y[index];
        int filled = Mth.clamp(Math.round(GAUGE_W * Mth.clamp(fill, 0.0F, 1.0F)), 0, GAUGE_W);
        if (filled > 0) {
            guiGraphics.fill(x, y, x + filled, y + GAUGE_H, color);
            guiGraphics.fill(x, y, x + filled, y + 1, 0x55FFFFFF);
        }
        guiGraphics.drawCenteredString(this.font, label, x + GAUGE_W / 2, y + 1, 0xFFFFFFFF);
    }

    @Override
    protected void renderBg(GuiGraphics guiGraphics, float partialTick, int mouseX, int mouseY) {
        // 8 参版本：显式给出纹理实际尺寸，否则默认按 256×256 采样 → 图被拉伸
        guiGraphics.blit(TEXTURE, this.leftPos, this.topPos, 0, 0, this.imageWidth, this.imageHeight,
            WIDTH, HEIGHT);
    }

    @Override
    protected void renderLabels(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        // 背景纹理自带分区，不画标题
    }

    // ------------------------------------------------------------------
    // 下拉 + 滚动
    // ------------------------------------------------------------------

    private List<String> dropdownEntries() {
        if (this.openDropdown == Dropdown.MODEL) {
            return List.of(MODEL_LABELS);
        }
        return ResidentSkins.all();
    }

    private int visibleItems() {
        return Math.min(this.dropdownEntries().size(), LIST_VISIBLE);
    }

    private int maxScroll() {
        return Math.max(0, this.dropdownEntries().size() - LIST_VISIBLE);
    }

    private int listX() {
        return this.leftPos + (this.openDropdown == Dropdown.MODEL ? MODEL_BUTTON_X : SKIN_BUTTON_X);
    }

    private int listY() {
        return this.topPos + (this.openDropdown == Dropdown.MODEL ? MODEL_BUTTON_Y : SKIN_BUTTON_Y)
            + BUTTON_H + 1;
    }

    private int listHeight() {
        return this.visibleItems() * LIST_ITEM_H + 2;
    }

    private void renderDropdown(GuiGraphics guiGraphics, int mouseX, int mouseY) {
        List<String> entries = this.dropdownEntries();
        int shown = this.visibleItems();
        int x = this.listX();
        int y = this.listY();
        int h = this.listHeight();

        // 抬到最上层：物品是按更深的 z 画的
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 400.0F);

        guiGraphics.fill(x - 1, y - 1, x + LIST_W + 1, y + h + 1, 0xFF000000);
        guiGraphics.fill(x, y, x + LIST_W, y + h, 0xFF3A3A3A);

        boolean scrollable = entries.size() > LIST_VISIBLE;
        int scrollbarW = scrollable ? 5 : 0;
        int textArea = LIST_W - scrollbarW - 5;

        for (int i = 0; i < shown; i++) {
            int index = i + this.scroll;
            if (index >= entries.size()) {
                break;
            }
            String label = entries.get(index);
            int itemY = y + 1 + i * LIST_ITEM_H;
            boolean hovered = mouseX >= x && mouseX <= x + LIST_W - scrollbarW
                && mouseY >= itemY && mouseY <= itemY + LIST_ITEM_H - 1;
            if (hovered) {
                guiGraphics.fill(x, itemY, x + LIST_W - scrollbarW, itemY + LIST_ITEM_H, 0xFF6A6A6A);
            }
            boolean selected = this.openDropdown == Dropdown.MODEL
                ? index == this.selectedModel
                : label.equals(this.selectedSkin);
            int color = selected ? 0xFFE8C24A : 0xFFE0E0E0;

            if (this.font.width(label) <= textArea) {
                guiGraphics.drawString(this.font, label, x + 3, itemY + 2, color, false);
            } else if (hovered) {
                int full = this.font.width(label) + 16;
                int offset = (int) ((System.currentTimeMillis() / 40L) % full);
                guiGraphics.enableScissor(x + 2, itemY, x + 2 + textArea, itemY + LIST_ITEM_H);
                guiGraphics.drawString(this.font, label, x + 3 - offset, itemY + 2, color, false);
                guiGraphics.disableScissor();
            } else {
                String clipped = this.font.plainSubstrByWidth(label, textArea - 6);
                guiGraphics.drawString(this.font, clipped + "\u2026", x + 3, itemY + 2, color, false);
            }
        }

        if (scrollable) {
            int barX = x + LIST_W - scrollbarW;
            guiGraphics.fill(barX, y + 1, barX + scrollbarW - 1, y + h - 1, 0xFF202020);
            int trackH = h - 2;
            int thumbH = Math.max(10, trackH * LIST_VISIBLE / entries.size());
            int thumbY = y + 1 + (int) ((trackH - thumbH)
                * (this.scroll / (float) Math.max(1, this.maxScroll())));
            guiGraphics.fill(barX + 1, thumbY, barX + scrollbarW - 1, thumbY + thumbH, 0xFFB0B0B0);
        }

        guiGraphics.pose().popPose();
    }

    // ------------------------------------------------------------------
    // 输入
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.openDropdown != Dropdown.NONE) {
            List<String> entries = this.dropdownEntries();
            int x = this.listX();
            int y = this.listY();
            int shown = this.visibleItems();
            int h = this.listHeight();

            boolean scrollable = entries.size() > LIST_VISIBLE;
            if (scrollable && mouseX >= x + LIST_W - 5 && mouseX <= x + LIST_W
                    && mouseY >= y && mouseY <= y + h) {
                this.draggingScrollbar = true;
                this.updateScrollFromMouse(mouseY, y, h);
                return true;
            }

            for (int i = 0; i < shown; i++) {
                int index = i + this.scroll;
                if (index >= entries.size()) {
                    break;
                }
                int itemY = y + 1 + i * LIST_ITEM_H;
                if (mouseX >= x && mouseX <= x + LIST_W
                        && mouseY >= itemY && mouseY <= itemY + LIST_ITEM_H - 1) {
                    this.applyChoice(index, entries.get(index));
                    return true;
                }
            }
            this.openDropdown = Dropdown.NONE;
            return true;
        }

        // 框外的两个图标
        int iconX = this.leftPos + IMPORT_ICON_X;
        int chestY = this.topPos + IMPORT_ICON_Y;
        int pickY = this.topPos + FOLDER_ICON_Y;
        if (insideIcon((int) mouseX, (int) mouseY, iconX, chestY)) {
            ResidentEntity resident = this.menu.resident();
            if (resident != null && this.minecraft != null) {
                this.minecraft.setScreen(new PlayerSkinScreen(resident, this));
            }
            return true;
        }
        if (insideIcon((int) mouseX, (int) mouseY, iconX, pickY)) {
            this.openSkinFolder();
            return true;
        }

        // 只有点在名字栏上才进入输入状态
        boolean onNameArea = mouseX >= this.leftPos + NAME_BOX_X - 3
            && mouseX <= this.leftPos + NAME_BOX_X + NAME_BOX_W + 3
            && mouseY >= this.topPos + NAME_BOX_Y - 3
            && mouseY <= this.topPos + NAME_BOX_Y + NAME_BOX_H + 3;
        this.editingName = onNameArea;
        this.nameBox.setFocused(onNameArea);
        this.setFocused(onNameArea ? this.nameBox : null);

        return super.mouseClicked(mouseX, mouseY, button);
    }

    /** 输入状态下所有按键都由输入框吃掉（E 就是字母 e），只有 ESC 退出编辑。 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.editingName && this.nameBox != null) {
            if (keyCode == 256) { // ESC：退出编辑，不关界面
                this.editingName = false;
                this.nameBox.setFocused(false);
                this.setFocused(null);
                return true;
            }
            if (keyCode == 257 || keyCode == 335) { // Enter / 小键盘 Enter
                this.confirmName();
                return true;
            }
            this.nameBox.keyPressed(keyCode, scanCode, modifiers);
            // 关键：吃掉按键，绝不能再传回原版（否则 E 会被当成"打开物品栏"而关界面）
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.editingName && this.nameBox != null && this.nameBox.charTyped(codePoint, modifiers)) {
            return true;
        }
        return super.charTyped(codePoint, modifiers);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingScrollbar) {
            this.updateScrollFromMouse(mouseY, this.listY(), this.listHeight());
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void updateScrollFromMouse(double mouseY, int y, int h) {
        int trackH = Math.max(1, h - 2);
        float ratio = (float) ((mouseY - y - 1) / trackH);
        this.scroll = Mth.clamp(Math.round(ratio * this.maxScroll()), 0, this.maxScroll());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.openDropdown == Dropdown.SKIN && this.maxScroll() > 0) {
            this.scroll = Mth.clamp(this.scroll - (int) Math.signum(scrollY), 0, this.maxScroll());
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void applyChoice(int index, String value) {
        if (this.openDropdown == Dropdown.MODEL) {
            this.selectedModel = index;
            this.modelButton.setMessage(modelLabel(index));
        } else {
            this.selectedSkin = value;
            this.skinButton.setMessage(skinLabel(value));
        }
        this.openDropdown = Dropdown.NONE;
        this.pushSettings();
    }

    // ------------------------------------------------------------------
    // 保存
    // ------------------------------------------------------------------

    private void pushSettings() {
        ResidentEntity resident = this.menu.resident();
        if (resident == null) {
            return;
        }
        String name = this.nameBox != null ? this.nameBox.getValue() : "";
        PacketDistributor.sendToServer(
            new ResidentSettingsPayload(resident.getId(), name, this.selectedModel + 1, this.selectedSkin));
    }

    @Override
    public void removed() {
        this.pushSettings();
        super.removed();
    }

    @Override
    public void onClose() {
        this.pushSettings();
        super.onClose();
    }
}
